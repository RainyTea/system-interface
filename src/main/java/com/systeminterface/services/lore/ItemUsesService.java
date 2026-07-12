package com.systeminterface.services.lore;

import com.google.gson.Gson;
import com.systeminterface.core.SystemInterfaceConfig;
import com.systeminterface.services.wiki.BucketClient;
import com.systeminterface.services.wiki.BucketQuery;
import com.systeminterface.services.wiki.BucketRow;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Lazy, cached "what is this item used for" lookups from the OSRS-Wiki {@code recipe} bucket
 * (ingredient match via {@code uses_material}). Cache-first: memory → disk → one network fetch.
 * All HTTP on the OkHttp pool via {@link BucketClient}; {@link #get} is a non-blocking snapshot
 * read, safe on the render thread. Opt-in: no fetch unless {@code enableWikiLookup} is on.
 */
@Slf4j
@Singleton
public final class ItemUsesService
{
	private static final Path CACHE_DIR =
		RuneLite.RUNELITE_DIR.toPath().resolve("system-interface").resolve("lore-cache").resolve("uses");

	/** Bumped when lore parsing changes; older cache files are discarded and refetched. */
	private static final int CACHE_SCHEMA = 3;

	private static final class CacheFile
	{
		int schema;
		List<UseEntry> uses;
	}

	private final BucketClient bucketClient;
	private final Gson gson;
	private final SystemInterfaceConfig config;

	// Fetched results by item name. An EMPTY list = fetched-and-none (negative cache).
	private final Map<String, List<UseEntry>> uses = new ConcurrentHashMap<>();
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

	@Inject
	public ItemUsesService(BucketClient bucketClient, Gson gson, SystemInterfaceConfig config)
	{
		this.bucketClient = bucketClient;
		this.gson = gson;
		this.config = config;
	}

	/**
	 * Uses for {@code itemName}: null until fetched (kicks a background fetch on first miss),
	 * empty when the wiki has none. Never blocks.
	 */
	public List<UseEntry> get(String itemName)
	{
		if (itemName == null || itemName.isEmpty())
		{
			return null;
		}
		final List<UseEntry> cached = uses.get(itemName);
		if (cached != null)
		{
			return cached;
		}
		if (loadFromDisk(itemName))
		{
			return uses.get(itemName);
		}
		if (config.enableWikiLookup())
		{
			fetch(itemName);
		}
		return null;
	}

	private void fetch(String itemName)
	{
		if (!inFlight.add(itemName))
		{
			return;
		}
		final BucketQuery q = new BucketQuery("recipe")
			.select("page_name", "production_json")
			.where("uses_material", itemName)
			.limit(50);
		bucketClient.query(q, rows ->
		{
			final List<UseEntry> parsed = new ArrayList<>();
			for (BucketRow row : rows)
			{
				final UseEntry e = RecipeUsesMapper.mapUse(row, gson);
				if (e != null)
				{
					parsed.add(e);
				}
			}
			final List<UseEntry> result = Collections.unmodifiableList(parsed);
			uses.put(itemName, result);
			saveToDisk(itemName, result);
			inFlight.remove(itemName);
		}, () ->
		{
			// Network error: allow a retry on a later appraise; nothing cached.
			log.debug("recipe uses fetch failed for '{}'", itemName);
			inFlight.remove(itemName);
		});
	}

	private boolean loadFromDisk(String itemName)
	{
		final Path file = CACHE_DIR.resolve(sanitize(itemName) + ".json");
		if (!Files.isRegularFile(file))
		{
			return false;
		}
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			final CacheFile cf = gson.fromJson(r, CacheFile.class);
			if (cf == null || cf.schema != CACHE_SCHEMA || cf.uses == null)
			{
				return false;
			}
			uses.put(itemName, Collections.unmodifiableList(cf.uses));
			return true;
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Failed to read uses cache for '{}'", itemName, e);
			return false;
		}
	}

	private void saveToDisk(String itemName, List<UseEntry> result)
	{
		try
		{
			Files.createDirectories(CACHE_DIR);
			CacheFile cf = new CacheFile();
			cf.schema = CACHE_SCHEMA;
			cf.uses = result;
			Files.write(CACHE_DIR.resolve(sanitize(itemName) + ".json"),
				gson.toJson(cf).getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.debug("Failed to write uses cache for '{}'", itemName, e);
		}
	}

	private static String sanitize(String name)
	{
		return name.replaceAll("[^A-Za-z0-9_-]", "_");
	}
}
