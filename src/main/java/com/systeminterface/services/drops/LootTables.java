package com.systeminterface.services.drops;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.systeminterface.core.SystemInterfaceConfig;
import com.systeminterface.services.state.StateTracker;
import com.systeminterface.services.wiki.BucketClient;
import com.systeminterface.services.wiki.BucketQuery;
import com.systeminterface.services.wiki.BucketRow;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.OkHttpClient;

/**
 * Registry of {@link DropTable}s, keyed by canonical target name.
 *
 * <p>Tables come from four sources, evaluated in this order:
 * <ol>
 *   <li><b>Bundled.</b> Manifest at {@code /com/systeminterface/drops/index.json};
 *       each entry is a sibling JSON file. Adding a bundled table is a no-code
 *       change — drop the file in resources and add its name to the manifest.</li>
 *   <li><b>Wiki cache.</b> {@code ~/.runelite/system-interface/wiki-cache/}.
 *       Tables previously fetched from the OSRS Wiki, cached as JSON. Loaded
 *       eagerly on startup so they're available offline.</li>
 *   <li><b>User overrides.</b> {@code ~/.runelite/system-interface/loot-tables/*.json}.
 *       Same shape as bundled; user files always win on collision.</li>
 *   <li><b>Live Bucket fetch.</b> On a cache miss for an NPC the player just
 *       interacted with, query the OSRS-Wiki Bucket API ({@code action=bucket})
 *       for {@code dropsline} rows, enrich with an {@code infobox_monster} row,
 *       and save the mapped table as JSON in the wiki cache. Opt-in (default
 *       off) per the AGENTS.md third-party-server rule.</li>
 * </ol>
 *
 * <p>Subtable handling note: the OSRS Wiki pre-multiplies effective rates on
 * each monster page (e.g. Man's herbs show {@code rarity=1/22.3} which is
 * {@code 23/128 × 32/128}), so we get correct per-kill rates without modelling
 * subtables explicitly. Items inside subtables that the wiki <em>doesn't</em>
 * pre-multiply will be missing from the parsed table — a known gap that can be
 * closed later by shipping subtable definitions and resolving references.
 */
@Slf4j
@Singleton
public final class LootTables
{
	// ---------------------------------------------------------------------
	// Constants
	// ---------------------------------------------------------------------

	private static final String CLASSPATH_DIR = "/com/systeminterface/drops/";
	private static final String MANIFEST_RESOURCE = CLASSPATH_DIR + "index.json";

	private static final Path STATE_DIR = RuneLite.RUNELITE_DIR.toPath().resolve("system-interface");
	private static final Path USER_OVERRIDE_DIR = STATE_DIR.resolve("loot-tables");
	private static final Path WIKI_CACHE_DIR = STATE_DIR.resolve("wiki-cache");

	// ---------------------------------------------------------------------
	// Collaborators
	// ---------------------------------------------------------------------

	private final Gson gson;
	private final OkHttpClient okHttpClient;
	private final SystemInterfaceConfig config;
	private final StateTracker stateTracker;
	private final BucketClient bucketClient;

	private final Map<String, DropTable> tablesByTarget = new ConcurrentHashMap<>();
	/** Targets we've already kicked off a fetch for in this session. */
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	/** Targets the wiki returned no useful data for — don't retry this session. */
	private final Set<String> missingFromWiki = ConcurrentHashMap.newKeySet();

	@Inject
	public LootTables(
		Gson gson,
		OkHttpClient okHttpClient,
		SystemInterfaceConfig config,
		StateTracker stateTracker,
		BucketClient bucketClient)
	{
		this.gson = gson;
		this.okHttpClient = okHttpClient;
		this.config = config;
		this.stateTracker = stateTracker;
		this.bucketClient = bucketClient;

		loadBundled();
		loadWikiCache();
		loadUserOverrides();
		log.debug("LootTables ready: {} table(s) loaded — {}", tablesByTarget.size(), tablesByTarget.keySet());
	}

	// ---------------------------------------------------------------------
	// Public API
	// ---------------------------------------------------------------------

	/**
	 * @return the loot table for {@code targetName}, or {@code null} if none
	 *         is registered. If the wiki-lookup config is on and we don't yet
	 *         have a table for this target, kicks off an async fetch that may
	 *         populate the table for a future render.
	 */
	public DropTable forTarget(String targetName)
	{
		if (targetName == null)
		{
			return null;
		}
		DropTable table = tablesByTarget.get(targetName);
		// Re-fetch when we have no table, or when a wiki-sourced table was parsed
		// by an older schema (parser has since improved). User/bundled tables are
		// authoritative and never re-fetched.
		final boolean staleWiki = table != null
			&& table.getOrigin() != null && table.getOrigin().startsWith("wiki")
			&& table.getSchema() < DropslineMapper.CURRENT_SCHEMA;
		if ((table == null || staleWiki) && config.enableWikiLookup()
			&& !inFlight.contains(targetName)
			&& !missingFromWiki.contains(targetName))
		{
			kickOffWikiFetch(targetName);
		}
		return table;
	}

	/** Names of every target with a registered loot table. */
	public Set<String> registeredTargets()
	{
		return Collections.unmodifiableSet(tablesByTarget.keySet());
	}

	// ---------------------------------------------------------------------
	// Bundled (classpath)
	// ---------------------------------------------------------------------

	private void loadBundled()
	{
		final Manifest manifest = readManifest();
		if (manifest == null || manifest.tables == null)
		{
			log.debug("No bundled loot-table manifest at {} — shipping zero tables.", MANIFEST_RESOURCE);
			return;
		}
		for (String file : manifest.tables)
		{
			if (file == null || file.isEmpty())
			{
				continue;
			}
			loadFromClasspath(CLASSPATH_DIR + file);
		}
	}

	private Manifest readManifest()
	{
		try (InputStream in = LootTables.class.getResourceAsStream(MANIFEST_RESOURCE))
		{
			if (in == null)
			{
				return null;
			}
			try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				return gson.fromJson(r, Manifest.class);
			}
		}
		catch (IOException | JsonSyntaxException e)
		{
			log.debug("Failed to read bundled manifest", e);
			return null;
		}
	}

	private void loadFromClasspath(String resource)
	{
		try (InputStream in = LootTables.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				log.debug("Bundled loot table missing: {}", resource);
				return;
			}
			try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				DropTable table = gson.fromJson(r, DropTable.class);
				register(table, "bundled:" + resource);
			}
		}
		catch (IOException | JsonSyntaxException e)
		{
			log.debug("Failed to load loot table {}", resource, e);
		}
	}

	// ---------------------------------------------------------------------
	// On-disk JSON sources (wiki cache + user overrides)
	// ---------------------------------------------------------------------

	private void loadWikiCache()
	{
		loadDirJson(WIKI_CACHE_DIR, "wiki-cache");
	}

	private void loadUserOverrides()
	{
		loadDirJson(USER_OVERRIDE_DIR, "user");
	}

	private void loadDirJson(Path dir, String originPrefix)
	{
		try
		{
			Files.createDirectories(dir);
		}
		catch (IOException e)
		{
			log.debug("Failed to create {} dir {}", originPrefix, dir, e);
			return;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json"))
		{
			for (Path file : stream)
			{
				loadFromFile(file, originPrefix);
			}
		}
		catch (IOException e)
		{
			log.debug("Failed to scan {} dir {}", originPrefix, dir, e);
		}
	}

	private void loadFromFile(Path file, String originPrefix)
	{
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			DropTable table = gson.fromJson(r, DropTable.class);
			register(table, originPrefix + ":" + file.getFileName());
		}
		catch (IOException | JsonSyntaxException e)
		{
			log.debug("Failed to load loot table {}", file, e);
		}
	}

	// ---------------------------------------------------------------------
	// Live wiki fetch (opt-in)
	// ---------------------------------------------------------------------

	private void kickOffWikiFetch(String targetName)
	{
		if (!inFlight.add(targetName))
		{
			return;
		}
		final String page = PageNameOverrides.resolve(targetName);

		final BucketQuery dropsQuery = new BucketQuery("dropsline")
			.select("item_name", "drop_json", "rare_drop_table")
			.where("page_name", page)
			.limit(500);

		bucketClient.query(dropsQuery, dropRows ->
		{
			final DropTable table = DropslineMapper.newTable(targetName);
			for (BucketRow row : dropRows)
			{
				final DropTable.Entry entry = DropslineMapper.mapDrop(row, gson);
				if (entry != null)
				{
					table.drops.add(entry);
				}
			}
			if (table.getDrops().isEmpty())
			{
				log.debug("Bucket returned no parseable drops for '{}'", targetName);
				missingFromWiki.add(targetName);
				inFlight.remove(targetName);
				return;
			}
			// Enrich with monster stats, then register+cache regardless of whether that second query succeeds.
			final BucketQuery monsterQuery = new BucketQuery("infobox_monster")
				.select("name", "combat_level", "hitpoints", "is_members_only", "examine",
					"max_hit", "attack_style", "elemental_weakness", "slayer_level", "image")
				.where("name", page)
				.limit(3);
			bucketClient.query(monsterQuery, monsterRows ->
			{
				if (!monsterRows.isEmpty())
				{
					DropslineMapper.applyMonster(table, monsterRows.get(0));
				}
				finishFetch(targetName, table);
			}, () -> finishFetch(targetName, table));
		}, () ->
		{
			log.debug("Bucket dropsline fetch failed for '{}'", targetName);
			// Network/error: don't mark permanently missing — allow a retry next time.
			inFlight.remove(targetName);
		});
	}

	/** Registers + caches a freshly-fetched table and clears the in-flight flag. */
	private void finishFetch(String targetName, DropTable table)
	{
		register(table, "bucket:" + targetName);
		saveWikiCache(table);
		stateTracker.bumpGeneration();
		inFlight.remove(targetName);
		log.debug("Bucket fetch complete for '{}': {} drop(s) cached.", targetName, table.getDrops().size());
	}

	private void saveWikiCache(DropTable table)
	{
		try
		{
			Files.createDirectories(WIKI_CACHE_DIR);
			Path file = WIKI_CACHE_DIR.resolve(sanitizeForFile(table.getTarget()) + ".json");
			String json = gson.toJson(table);
			Files.write(file, json.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.debug("Failed to write wiki cache for '{}'", table.getTarget(), e);
		}
	}

	// ---------------------------------------------------------------------
	// Common
	// ---------------------------------------------------------------------

	private void register(DropTable table, String origin)
	{
		if (table == null || table.getTarget() == null || table.getTarget().isEmpty())
		{
			log.debug("Skipping null/headerless loot table from {}", origin);
			return;
		}
		table.origin = origin;
		DropTable previous = tablesByTarget.put(table.getTarget(), table);
		if (previous != null)
		{
			log.debug("Loot table for '{}' overridden by {}", table.getTarget(), origin);
		}
	}

	private static String sanitizeForFile(String name)
	{
		return name.replaceAll("[^A-Za-z0-9_-]", "_");
	}

	// ---------------------------------------------------------------------
	// Gson DTOs
	// ---------------------------------------------------------------------

	/** Shape of {@code index.json}. */
	private static final class Manifest
	{
		List<String> tables;
	}
}


