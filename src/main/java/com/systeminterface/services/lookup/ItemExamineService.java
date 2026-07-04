package com.systeminterface.services.lookup;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Provides item examine text by fetching the OSRS Wiki item mapping. A single
 * HTTP call loads all items; subsequent lookups are served from the in-memory
 * cache. Uses the same wiki ecosystem as the drop-table fetcher.
 */
@Slf4j
@Singleton
public final class ItemExamineService
{
	private static final String MAPPING_URL = "https://prices.runescape.wiki/api/v1/osrs/mapping";
	private static final String USER_AGENT = "system-interface-plugin (RuneLite hub plugin)";

	private final OkHttpClient okHttpClient;
	private final Gson gson;

	private volatile Map<Integer, String> examineById;
	private final AtomicBoolean fetching = new AtomicBoolean(false);

	@Inject
	ItemExamineService(OkHttpClient okHttpClient, Gson gson)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
	}

	/**
	 * @return the OSRS examine text for the given item ID, or {@code null} if
	 *         the mapping hasn't loaded yet or the item isn't in it. On first
	 *         call, kicks off the async fetch.
	 */
	public String getExamine(int itemId)
	{
		final Map<Integer, String> idx = examineById;
		if (idx == null)
		{
			fetch();
			return null;
		}
		return idx.get(itemId);
	}

	/** True once the mapping has been fetched and lookups are available. */
	public boolean isReady()
	{
		return examineById != null;
	}

	private void fetch()
	{
		if (!fetching.compareAndSet(false, true))
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
					if (entries == null)
					{
						return;
					}
					final Map<Integer, String> idx = new HashMap<>(entries.length);
					for (MappingEntry e : entries)
					{
						if (e.examine != null && !e.examine.isEmpty())
						{
							idx.put(e.id, e.examine);
						}
					}
					examineById = idx;
					log.debug("Item mapping loaded: {} items with examine text", idx.size());
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

	@SuppressWarnings("unused")
	private static final class MappingEntry
	{
		int id;
		String examine;
		String name;
		boolean members;
	}
}
