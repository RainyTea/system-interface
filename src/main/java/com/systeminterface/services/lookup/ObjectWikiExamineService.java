package com.systeminterface.services.lookup;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.systeminterface.modules.skills.ResourceData;
import com.systeminterface.services.state.StateTracker;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Focused OSRS Wiki feeder for object examine text.
 *
 * This service does not become a second Appraise lookup path. It only fetches
 * confident resource/object pages, parses examine text for the exact object id,
 * and inserts the result into {@link ObjectExamineService}.
 */
@Slf4j
@Singleton
public final class ObjectWikiExamineService
{
	private static final String WIKI_API_BASE = "https://oldschool.runescape.wiki/api.php";
	private static final String USER_AGENT = "system-interface-plugin (RuneLite hub plugin)";

	private static final Set<String> AMBIGUOUS_PAGES = ambiguousPages();

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ObjectExamineService objectExamineService;
	private final StateTracker stateTracker;

	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	private final Set<String> missing = ConcurrentHashMap.newKeySet();

	@Inject
	ObjectWikiExamineService(OkHttpClient okHttpClient, Gson gson,
		ObjectExamineService objectExamineService, StateTracker stateTracker)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.objectExamineService = objectExamineService;
		this.stateTracker = stateTracker;
	}

	public void requestResourceObjectExamine(int objectId, String objectName,
		List<ResourceData.ResourceEntry> entries, boolean allowLiveFetch)
	{
		if (!allowLiveFetch)
		{
			log.debug("Object wiki examine skipped id={} name='{}': wiki lookup disabled", objectId, objectName);
			return;
		}
		if (objectId < 0 || objectName == null)
		{
			log.debug("Object wiki examine skipped id={} name='{}': missing id/name", objectId, objectName);
			return;
		}
		if (objectExamineService.hasExamine(objectId, objectName))
		{
			log.debug("Object wiki examine skipped id={} name='{}': already known", objectId, objectName);
			return;
		}
		final String page = confidentPage(entries);
		if (page == null)
		{
			log.debug("Object wiki examine skipped id={} name='{}': no confident resource page", objectId, objectName);
			return;
		}
		request(objectId, objectName, page);
	}

	public void requestObjectExamine(int objectId, String objectName, boolean allowLiveFetch)
	{
		if (!allowLiveFetch)
		{
			log.debug("Object wiki examine skipped id={} name='{}': wiki lookup disabled", objectId, objectName);
			return;
		}
		if (objectId < 0 || objectName == null)
		{
			log.debug("Object wiki examine skipped id={} name='{}': missing id/name", objectId, objectName);
			return;
		}
		if (objectExamineService.hasExamine(objectId, objectName))
		{
			log.debug("Object wiki examine skipped id={} name='{}': already known", objectId, objectName);
			return;
		}
		final String page = confidentObjectPage(objectName);
		if (page == null)
		{
			log.debug("Object wiki examine skipped id={} name='{}': ambiguous object page", objectId, objectName);
			return;
		}
		request(objectId, objectName, page);
	}

	static String confidentPage(List<ResourceData.ResourceEntry> entries)
	{
		if (entries == null || entries.isEmpty())
		{
			return null;
		}
		String page = null;
		for (ResourceData.ResourceEntry entry : entries)
		{
			if (entry == null || entry.isRewardOnly())
			{
				continue;
			}
			final String candidate = cleanPage(entry.getWikiPage());
			if (candidate == null || isAmbiguousPage(candidate))
			{
				continue;
			}
			if (page == null)
			{
				page = candidate;
			}
			else if (!page.equalsIgnoreCase(candidate))
			{
				return null;
			}
		}
		return page;
	}

	static String confidentObjectPage(String objectName)
	{
		final String page = cleanPage(objectName);
		return page == null || isAmbiguousPage(page) ? null : page;
	}

	private void request(int objectId, String objectName, String page)
	{
		final String key = objectId + ":" + page.toLowerCase(Locale.ROOT);
		if (missing.contains(key))
		{
			log.debug("Object wiki examine skipped id={} name='{}' page='{}': missing cached this session",
				objectId, objectName, page);
			return;
		}
		if (!inFlight.add(key))
		{
			log.debug("Object wiki examine skipped id={} name='{}' page='{}': already in flight",
				objectId, objectName, page);
			return;
		}

		final HttpUrl base = HttpUrl.parse(WIKI_API_BASE);
		if (base == null)
		{
			inFlight.remove(key);
			missing.add(key);
			return;
		}
		final HttpUrl url = base.newBuilder()
			.addQueryParameter("action", "parse")
			.addQueryParameter("page", page)
			.addQueryParameter("prop", "wikitext")
			.addQueryParameter("format", "json")
			.addQueryParameter("formatversion", "2")
			.addQueryParameter("redirects", "true")
			.build();

		final Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
			.header("Accept", "application/json")
			.build();

		log.debug("Object wiki examine fetch starting id={} name='{}' page='{}'", objectId, objectName, page);
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Object wiki examine fetch failed id={} page='{}'", objectId, page, e);
				inFlight.remove(key);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						log.debug("Object wiki examine fetch unsuccessful id={} page='{}': {}",
							objectId, page, response.code());
						missing.add(key);
						return;
					}
					final WikiApiResponse api = gson.fromJson(body.charStream(), WikiApiResponse.class);
					final String title = api == null || api.parse == null || api.parse.title == null
						? page : api.parse.title;
					String examine = api == null || api.parse == null
						? null : WikiObjectExamineParser.parseExamineForObject(api.parse.wikitext, objectId);
					if (examine == null && api != null && api.parse != null)
					{
						examine = WikiObjectExamineParser.parseExamineForPageName(
							api.parse.wikitext, objectName, title);
						if (examine != null)
						{
							log.debug("Object wiki examine accepted by page-name fallback id={} name='{}' page='{}'",
								objectId, objectName, title);
						}
					}
					if (examine == null)
					{
						log.debug("Object wiki examine missing/ambiguous id={} page='{}'", objectId, page);
						missing.add(key);
						return;
					}
					final ObjectExamineService.MergeResult result =
						objectExamineService.addWikiExamine(objectId, objectName, examine, title);
					if (result == ObjectExamineService.MergeResult.STORED
						|| result == ObjectExamineService.MergeResult.MERGED)
					{
						stateTracker.bumpGeneration();
					}
					log.debug("Object wiki examine cached id={} name='{}' page='{}' result={}",
						objectId, objectName, title, result);
				}
				catch (JsonSyntaxException e)
				{
					log.debug("Object wiki examine parse error id={} page='{}'", objectId, page, e);
				}
				finally
				{
					inFlight.remove(key);
				}
			}
		});
	}

	private static String cleanPage(String page)
	{
		if (page == null)
		{
			return null;
		}
		final String clean = page.trim();
		return clean.isEmpty() ? null : clean;
	}

	private static boolean isAmbiguousPage(String page)
	{
		return AMBIGUOUS_PAGES.contains(page.toLowerCase(Locale.ROOT));
	}

	private static Set<String> ambiguousPages()
	{
		final Set<String> pages = new HashSet<>();
		pages.add("tree");
		pages.add("trees");
		pages.add("rocks");
		pages.add("rock");
		pages.add("door");
		pages.add("crate");
		pages.add("stairs");
		pages.add("ladder");
		return pages;
	}

	private static final class WikiApiResponse
	{
		@SerializedName("parse")
		ParseBlock parse;
	}

	private static final class ParseBlock
	{
		@SerializedName("title")
		String title;

		@SerializedName("wikitext")
		String wikitext;
	}
}
