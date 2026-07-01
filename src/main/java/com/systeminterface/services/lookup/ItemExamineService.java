package com.systeminterface.services.lookup;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Provides item examine text from the OSRS Wiki price mapping. Cached mapping
 * data is loaded from disk first; live refreshes use OkHttp asynchronously.
 */
@Slf4j
@Singleton
public final class ItemExamineService
{
	private static final String MAPPING_URL = "https://prices.runescape.wiki/api/v1/osrs/mapping";
	private static final String USER_AGENT = "system-interface-plugin (RuneLite hub plugin)";
	static final String CACHE_FILE_NAME = "item-mapping-examines.json";

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final Path cacheFile;

	private volatile Map<Integer, String> examineById;
	private volatile boolean cacheLoaded;
	private volatile String provenance;
	private volatile CompletableFuture<Void> warmupFuture;

	private final AtomicBoolean fetching = new AtomicBoolean(false);
	private final AtomicBoolean liveFetchStarted = new AtomicBoolean(false);
	private final AtomicBoolean loadingCache = new AtomicBoolean(false);

	@Inject
	ItemExamineService(OkHttpClient okHttpClient, Gson gson, ScheduledExecutorService executor)
	{
		this(okHttpClient, gson, executor,
			RuneLite.RUNELITE_DIR.toPath().resolve("system-interface").resolve(CACHE_FILE_NAME));
	}

	ItemExamineService(OkHttpClient okHttpClient, Gson gson,
		ScheduledExecutorService executor, Path cacheFile)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.executor = executor;
		this.cacheFile = cacheFile;
	}

	public String getExamine(int itemId)
	{
		return getExamine(itemId, true);
	}

	/**
	 * @param allowLiveFetch whether a cache miss may start a live mapping fetch.
	 *        Cached mapping data is still used when this is false.
	 */
	public String getExamine(int itemId, boolean allowLiveFetch)
	{
		final Map<Integer, String> idx = examineById;
		if (!cacheLoaded)
		{
			warmUpAsync(allowLiveFetch);
			return idx == null ? null : idx.get(itemId);
		}
		if (allowLiveFetch && (idx == null || !idx.containsKey(itemId)))
		{
			fetch();
		}
		return idx == null ? null : idx.get(itemId);
	}

	/**
	 * Schedules cache loading off the caller thread and optionally starts a live
	 * refresh. The returned future completes after disk cache loading, not after
	 * the OkHttp network response.
	 */
	public CompletableFuture<Void> warmUpAsync(boolean allowLiveFetch)
	{
		if (cacheLoaded)
		{
			if (allowLiveFetch)
			{
				fetch();
			}
			return CompletableFuture.completedFuture(null);
		}
		if (!loadingCache.compareAndSet(false, true))
		{
			final CompletableFuture<Void> existing = warmupFuture;
			return existing == null ? CompletableFuture.completedFuture(null) : existing;
		}

		final CompletableFuture<Void> future = new CompletableFuture<>();
		warmupFuture = future;
		executor.execute(() ->
		{
			try
			{
				loadCache();
				if (allowLiveFetch)
				{
					fetch();
				}
				future.complete(null);
			}
			catch (Exception e)
			{
				log.debug("Item mapping cache warmup failed", e);
				examineById = Collections.emptyMap();
				cacheLoaded = true;
				future.completeExceptionally(e);
			}
			finally
			{
				loadingCache.set(false);
			}
		});
		return future;
	}

	public boolean isReady()
	{
		return cacheLoaded || examineById != null;
	}

	public String getProvenance()
	{
		return provenance;
	}

	private void loadCache()
	{
		if (cacheLoaded)
		{
			return;
		}
		if (!Files.exists(cacheFile))
		{
			examineById = Collections.emptyMap();
			cacheLoaded = true;
			return;
		}
		try (Reader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8))
		{
			final MappingEntry[] entries = gson.fromJson(reader, MappingEntry[].class);
			examineById = index(entries);
			provenance = "wiki-mapping-cache";
			cacheLoaded = true;
			log.debug("Item mapping cache loaded: {} items with examine text", examineById.size());
		}
		catch (IOException | JsonSyntaxException e)
		{
			log.debug("Failed to load item mapping cache {}", cacheFile, e);
			examineById = Collections.emptyMap();
			cacheLoaded = true;
		}
	}

	private void fetch()
	{
		if (!liveFetchStarted.compareAndSet(false, true)
			|| !fetching.compareAndSet(false, true))
		{
			return;
		}

		final Request request = new Request.Builder()
			.url(MAPPING_URL)
			.header("User-Agent", USER_AGENT)
			.header("Accept", "application/json")
			.build();

		log.debug("Fetching item mapping from {}", MAPPING_URL);

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Item mapping fetch failed", e);
				fetching.set(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						log.debug("Item mapping fetch unsuccessful: {}", response.code());
						return;
					}
					final MappingEntry[] entries = gson.fromJson(body.charStream(), MappingEntry[].class);
					examineById = index(entries);
					provenance = "wiki-mapping-live";
					cacheLoaded = true;
					saveCache(entries);
					log.debug("Item mapping loaded: {} items with examine text", examineById.size());
				}
				catch (Exception e)
				{
					log.debug("Item mapping parse error", e);
				}
				finally
				{
					fetching.set(false);
				}
			}
		});
	}

	private Map<Integer, String> index(MappingEntry[] entries)
	{
		if (entries == null)
		{
			return Collections.emptyMap();
		}
		final Map<Integer, String> idx = new HashMap<>(entries.length);
		for (MappingEntry entry : entries)
		{
			if (entry.examine != null && !entry.examine.isEmpty())
			{
				idx.put(entry.id, entry.examine);
			}
		}
		return idx;
	}

	private void saveCache(MappingEntry[] entries)
	{
		try
		{
			final Path parent = cacheFile.getParent();
			if (parent != null)
			{
				Files.createDirectories(parent);
			}
			try (Writer writer = Files.newBufferedWriter(cacheFile, StandardCharsets.UTF_8))
			{
				gson.toJson(entries, writer);
			}
		}
		catch (IOException e)
		{
			log.debug("Failed to write item mapping cache {}", cacheFile, e);
		}
	}

	@SuppressWarnings("unused")
	private static final class MappingEntry
	{
		int id;
		String examine;
		String name;
		boolean members;
	}
}
