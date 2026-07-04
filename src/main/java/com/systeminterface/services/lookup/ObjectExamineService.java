package com.systeminterface.services.lookup;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Unified object-examine lookup: bundled curated -> local observed -> (wiki, added later). */
@Slf4j
@Singleton
public final class ObjectExamineService
{
	private static final String BUNDLED = "/com/systeminterface/services/lookup/object-examines.json";

	enum Source { CURATED, OBSERVED, WIKI }

	static final class Stored
	{
		String name;
		String text;
		Source source;
		Stored(String name, String text, Source source) { this.name = name; this.text = text; this.source = source; }
	}

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final Path localStore;
	private final Executor io;
	private final Map<Integer, String> curated = new HashMap<>();
	private final Map<Integer, Stored> observed = new ConcurrentHashMap<>();
	private final java.util.Set<String> inFlight = ConcurrentHashMap.newKeySet();

	private static final long CAPTURE_WINDOW_TICKS = 2;
	private int pendingObjectId = -1;
	private String pendingName;
	private long pendingTick = Long.MIN_VALUE;

	@Inject
	public ObjectExamineService(OkHttpClient okHttpClient, Gson gson)
	{
		this(okHttpClient, gson, RuneLite.RUNELITE_DIR.toPath().resolve("system-interface").resolve("object-examines.json"),
			r -> new Thread(r, "object-examine-io").start());
	}

	ObjectExamineService(Gson gson, Path localStore, Executor io)
	{
		this(null, gson, localStore, io);
	}

	private ObjectExamineService(OkHttpClient okHttpClient, Gson gson, Path localStore, Executor io)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.localStore = localStore;
		this.io = io;
	}

	/** Loads the bundled curated set from the plugin's classpath resource. Call once at startup. */
	public void loadCurated()
	{
		loadCurated(getClass().getResourceAsStream(BUNDLED));
	}

	/** Loads the bundled curated set: JSON object of { "objectId": "examine text" }. */
	void loadCurated(InputStream json)
	{
		if (json == null)
		{
			return;
		}
		final Type t = new TypeToken<Map<String, String>>() {}.getType();
		try (InputStreamReader r = new InputStreamReader(json, StandardCharsets.UTF_8))
		{
			final Map<String, String> raw = gson.fromJson(r, t);
			if (raw != null)
			{
				raw.forEach((k, v) -> curated.put(Integer.parseInt(k.trim()), v));
			}
		}
		catch (Exception e)
		{
			log.debug("Failed to load bundled object examines", e);
		}
	}

	public String getExamine(int objectId)
	{
		final String c = curated.get(objectId);
		if (c != null)
		{
			return c;
		}
		final Stored s = observed.get(objectId);
		return s == null ? null : s.text;
	}

	public void capture(int objectId, String name, String text)
	{
		store(objectId, name, text, Source.OBSERVED);
	}

	/** Loads this user's previously-observed captures from disk. Call once at startup. */
	public void loadLocal()
	{
		if (!Files.exists(localStore))
		{
			return;
		}
		final Type t = new TypeToken<Map<Integer, Stored>>() {}.getType();
		try (java.io.Reader r = Files.newBufferedReader(localStore, StandardCharsets.UTF_8))
		{
			final Map<Integer, Stored> loaded = gson.fromJson(r, t);
			if (loaded != null)
			{
				observed.putAll(loaded);
			}
		}
		catch (Exception e)
		{
			log.debug("Failed to load observed object examines", e);
		}
	}

	private void store(int objectId, String name, String text, Source source)
	{
		if (objectId < 0 || text == null || text.isEmpty())
		{
			return;
		}
		observed.put(objectId, new Stored(name, text, source));
		final Map<Integer, Stored> snapshot = new HashMap<>(observed);
		io.execute(() -> persist(snapshot));
	}

	private void persist(Map<Integer, Stored> snapshot)
	{
		try
		{
			Files.createDirectories(localStore.getParent());
			Files.write(localStore, gson.toJson(snapshot).getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.debug("Failed to persist observed object examines", e);
		}
	}

	/** Extracts the {@code |examine = ...} infobox field from wikitext, or null. */
	static String parseExamine(String wikitext)
	{
		if (wikitext == null)
		{
			return null;
		}
		final java.util.regex.Matcher m = java.util.regex.Pattern
			.compile("\\|\\s*examine\\s*=\\s*([^\\n|}]+)")
			.matcher(wikitext);
		if (m.find())
		{
			final String v = m.group(1).trim();
			return v.isEmpty() ? null : v;
		}
		return null;
	}

	/** Remember the object whose native Examine was just clicked, to correlate the chat line. */
	public void recordPendingExamine(int objectId, String name, long tick)
	{
		pendingObjectId = objectId;
		pendingName = name;
		pendingTick = tick;
	}

	/** An OBJECT_EXAMINE chat line arrived — capture it against the pending object if fresh. */
	public void onObjectExamineMessage(String text, long tick)
	{
		if (pendingObjectId >= 0 && tick >= pendingTick && tick - pendingTick <= CAPTURE_WINDOW_TICKS)
		{
			capture(pendingObjectId, pendingName, text);
		}
		pendingObjectId = -1;
	}

	/** Best-effort async fetch of an object's wiki examine; stores it as a WIKI-sourced entry. */
	public void fetchWiki(int objectId, String name)
	{
		if (okHttpClient == null || name == null || name.isEmpty() || getExamine(objectId) != null
			|| !inFlight.add(name))
		{
			return;
		}
		final HttpUrl url = HttpUrl.parse("https://oldschool.runescape.wiki/api.php").newBuilder()
			.addQueryParameter("action", "parse").addQueryParameter("page", name)
			.addQueryParameter("prop", "wikitext").addQueryParameter("format", "json")
			.addQueryParameter("formatversion", "2").addQueryParameter("redirects", "true").build();
		final Request req = new Request.Builder().url(url)
			.header("User-Agent", "system-interface-plugin (RuneLite hub plugin)").build();
		okHttpClient.newCall(req).enqueue(new Callback()
		{
			@Override public void onFailure(Call call, IOException e) { inFlight.remove(name); }
			@Override public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					if (response.isSuccessful() && body != null)
					{
						final com.google.gson.JsonObject root = gson.fromJson(body.string(), com.google.gson.JsonObject.class);
						final String wt = root.getAsJsonObject("parse").get("wikitext").getAsString();
						final String examine = parseExamine(wt);
						if (examine != null)
						{
							store(objectId, name, examine, Source.WIKI);
						}
					}
				}
				catch (Exception ignored) { /* leave unlearned */ }
				finally { inFlight.remove(name); }
			}
		});
	}
}
