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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Lazy, cached NPC lore lookups from the OSRS-Wiki {@code infobox_npc} bucket. Cache-first:
 * memory → disk → one network fetch. All HTTP on the OkHttp pool via {@link BucketClient};
 * {@link #get} is a non-blocking snapshot read, safe on the render thread. Opt-in: no fetch
 * unless {@code enableWikiLookup} is on.
 */
@Slf4j
@Singleton
public final class NpcLoreService
{
	private static final Path CACHE_DIR =
		RuneLite.RUNELITE_DIR.toPath().resolve("system-interface").resolve("lore-cache").resolve("npc");

	// Fetched-and-none sentinel: kept in the memory map only (never serialized to disk) so a
	// negative result still short-circuits future fetches without producing a bogus disk entry.
	private static final NpcLore NONE = new NpcLore(null, null, null, null);

	/** Bumped when lore parsing changes; older cache files are discarded and refetched. */
	private static final int CACHE_SCHEMA = 4;

	private static final class CacheFile
	{
		int schema;
		NpcLore lore;
	}

	private final BucketClient bucketClient;
	private final Gson gson;
	private final SystemInterfaceConfig config;

	// Fetched results by npc name. NONE = fetched-and-none (negative cache, memory only).
	private final Map<String, NpcLore> lore = new ConcurrentHashMap<>();
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

	@Inject
	public NpcLoreService(BucketClient bucketClient, Gson gson, SystemInterfaceConfig config)
	{
		this.bucketClient = bucketClient;
		this.gson = gson;
		this.config = config;
	}

	/**
	 * Lore for {@code npcName}: null until fetched (kicks a background fetch on first miss) or
	 * when the wiki has none. Never blocks.
	 */
	public NpcLore get(String npcName)
	{
		if (npcName == null || npcName.isEmpty())
		{
			return null;
		}
		final NpcLore cached = lore.get(npcName);
		if (cached != null)
		{
			return cached == NONE ? null : cached;
		}
		if (loadFromDisk(npcName))
		{
			final NpcLore l = lore.get(npcName);
			return l == NONE ? null : l;
		}
		if (config.enableWikiLookup())
		{
			fetch(npcName);
		}
		return null;
	}

	private void fetch(String npcName)
	{
		if (!inFlight.add(npcName))
		{
			return;
		}
		final BucketQuery q = new BucketQuery("infobox_npc")
			.select("page_name", "npc_name", "examine", "location", "quest", "image")
			.where("npc_name", npcName)
			.limit(10);
		bucketClient.query(q, rows ->
		{
			final BucketRow best = NpcLoreMapper.pickBest(rows, npcName);
			final NpcLore l = best != null ? NpcLoreMapper.map(best) : null;
			lore.put(npcName, l != null ? l : NONE);
			if (l != null)
			{
				saveToDisk(npcName, l);
			}
			inFlight.remove(npcName);
		}, () ->
		{
			// Network error: allow a retry on a later appraise; nothing cached.
			log.debug("npc lore fetch failed for '{}'", npcName);
			inFlight.remove(npcName);
		});
	}

	private boolean loadFromDisk(String npcName)
	{
		final Path file = CACHE_DIR.resolve(sanitize(npcName) + ".json");
		if (!Files.isRegularFile(file))
		{
			return false;
		}
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			final CacheFile cf = gson.fromJson(r, CacheFile.class);
			if (cf == null || cf.schema != CACHE_SCHEMA || cf.lore == null)
			{
				return false;
			}
			lore.put(npcName, cf.lore);
			return true;
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Failed to read npc lore cache for '{}'", npcName, e);
			return false;
		}
	}

	private void saveToDisk(String npcName, NpcLore l)
	{
		try
		{
			Files.createDirectories(CACHE_DIR);
			CacheFile cf = new CacheFile();
			cf.schema = CACHE_SCHEMA;
			cf.lore = l;
			Files.write(CACHE_DIR.resolve(sanitize(npcName) + ".json"),
				gson.toJson(cf).getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.debug("Failed to write npc lore cache for '{}'", npcName, e);
		}
	}

	private static String sanitize(String name)
	{
		return name.replaceAll("[^A-Za-z0-9_-]", "_");
	}
}
