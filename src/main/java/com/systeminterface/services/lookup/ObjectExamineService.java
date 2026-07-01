package com.systeminterface.services.lookup;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLite;

/**
 * Local observed examine text for world/environment objects.
 *
 * Unknown objects keep native Examine. If the player uses native Examine and the
 * client emits useful examine text, this service stores it locally by object id
 * and name. Future Appraise calls can then preserve the original examine text
 * without forcing a broad object database or live network source.
 */
@Slf4j
@Singleton
public final class ObjectExamineService
{
	public static final String USER_FILE_NAME = "user-object-examines.json";
	public static final String IMPORTED_FILE_NAME = "imported-object-examines.json";
	public static final String BUNDLED_FILE_NAME = "system-object-examines.json";
	public static final String WIKI_FILE_NAME = "wiki-object-examines.json";

	private static final String OBSERVED_SOURCE = "observed-examine";
	private static final String IMPORTED_SOURCE = "imported-observed";
	private static final String BUNDLED_SOURCE = "bundled-baseline";
	private static final String WIKI_SOURCE = "wiki";
	private static final String OBSERVED_CONFIDENCE = "observed";
	private static final String WIKI_CONFIDENCE = "wiki-page";

	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final Path userFile;
	private final Path importedFile;
	private final Path bundledFile;
	private final Path wikiFile;
	private final boolean loadBundledResource;

	private volatile Map<Integer, ObjectExamineEntry> sessionById = Collections.emptyMap();
	private volatile Map<Integer, ObjectExamineEntry> userById = Collections.emptyMap();
	private volatile Map<Integer, ObjectExamineEntry> wikiById = Collections.emptyMap();
	private volatile Map<Integer, ObjectExamineEntry> bundledById = Collections.emptyMap();
	private volatile Map<Integer, ObjectExamineEntry> importedById = Collections.emptyMap();
	private volatile boolean loaded;
	private volatile CompletableFuture<Void> warmupFuture;
	private final AtomicBoolean loading = new AtomicBoolean(false);

	@Inject
	ObjectExamineService(Gson gson, ScheduledExecutorService executor)
	{
		this(gson, executor,
			RuneLite.RUNELITE_DIR.toPath().resolve("system-interface").resolve(USER_FILE_NAME),
			RuneLite.RUNELITE_DIR.toPath().resolve("system-interface").resolve(IMPORTED_FILE_NAME),
			RuneLite.RUNELITE_DIR.toPath().resolve("system-interface").resolve(WIKI_FILE_NAME),
			null,
			true);
	}

	ObjectExamineService(Gson gson, ScheduledExecutorService executor, Path userFile,
		Path importedFile, Path bundledFile)
	{
		this(gson, executor, userFile, importedFile,
			userFile == null || userFile.getParent() == null ? null : userFile.getParent().resolve(WIKI_FILE_NAME),
			bundledFile, false);
	}

	private ObjectExamineService(Gson gson, ScheduledExecutorService executor, Path userFile,
		Path importedFile, Path wikiFile, Path bundledFile, boolean loadBundledResource)
	{
		this.gson = gson;
		this.executor = executor;
		this.userFile = userFile;
		this.importedFile = importedFile;
		this.wikiFile = wikiFile;
		this.bundledFile = bundledFile;
		this.loadBundledResource = loadBundledResource;
	}

	public CompletableFuture<Void> warmUpAsync()
	{
		if (loaded)
		{
			return CompletableFuture.completedFuture(null);
		}
		if (!loading.compareAndSet(false, true))
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
				loadAll();
				loaded = true;
				future.complete(null);
			}
			catch (Exception e)
			{
				log.debug("Object examine store warmup failed", e);
				loaded = true;
				future.completeExceptionally(e);
			}
			finally
			{
				loading.set(false);
			}
		});
		return future;
	}

	public boolean isReady()
	{
		return loaded;
	}

	public boolean hasExamine(int objectId, String name)
	{
		return lookup(objectId, name) != null;
	}

	public String getExamine(int objectId, String name)
	{
		final ObjectExamineEntry entry = lookup(objectId, name);
		return entry == null ? null : entry.examineText;
	}

	public String getProvenance(int objectId, String name)
	{
		final ObjectExamineEntry entry = lookup(objectId, name);
		return entry == null ? null : entry.source;
	}

	public ObjectExamineEntry lookup(int objectId, String name)
	{
		ObjectExamineEntry entry = compatible(sessionById, objectId, name);
		if (entry != null)
		{
			return copy(entry);
		}
		entry = compatible(userById, objectId, name);
		if (entry != null)
		{
			return copy(entry);
		}
		entry = compatible(wikiById, objectId, name);
		if (entry != null)
		{
			return copy(entry);
		}
		entry = compatible(bundledById, objectId, name);
		if (entry != null)
		{
			return copy(entry);
		}
		entry = compatible(importedById, objectId, name);
		return entry == null ? null : copy(entry);
	}

	public MergeResult observe(int objectId, String name, String examineText,
		WorldPoint worldPoint, int build)
	{
		return observe(objectId, name, examineText,
			worldPoint == null ? null : new ObservedWorldPoint(worldPoint.getX(), worldPoint.getY(), worldPoint.getPlane()),
			build);
	}

	MergeResult observe(int objectId, String name, String examineText,
		ObservedWorldPoint worldPoint, int build)
	{
		final ObjectExamineEntry entry = observedEntry(objectId, name, examineText, worldPoint, build);
		if (entry == null)
		{
			return MergeResult.IGNORED;
		}

		final MergeResult userResult;
		synchronized (this)
		{
			sessionById = mergeInto(sessionById, entry).map;
			final MergeOutcome userOutcome = mergeInto(userById, entry);
			userById = userOutcome.map;
			userResult = userOutcome.result;
		}

		if (userResult == MergeResult.STORED || userResult == MergeResult.MERGED)
		{
			saveUserAsync();
		}
		return userResult;
	}

	public MergeResult addWikiExamine(int objectId, String name, String examineText, String wikiPage)
	{
		final ObjectExamineEntry entry = wikiEntry(objectId, name, examineText, wikiPage);
		if (entry == null)
		{
			return MergeResult.IGNORED;
		}

		final MergeResult result;
		synchronized (this)
		{
			final MergeOutcome outcome = mergeInto(wikiById, entry);
			wikiById = outcome.map;
			result = outcome.result;
		}

		if (result == MergeResult.STORED || result == MergeResult.MERGED)
		{
			saveWikiAsync();
		}
		return result;
	}

	private ObjectExamineEntry observedEntry(int objectId, String name, String examineText,
		ObservedWorldPoint worldPoint, int build)
	{
		final String cleanName = cleanName(name);
		final String cleanExamine = cleanExamine(examineText);
		if (objectId < 0 || cleanName == null || cleanExamine == null)
		{
			return null;
		}
		final String now = Instant.now().toString();
		final ObjectExamineEntry entry = new ObjectExamineEntry();
		entry.objectId = objectId;
		entry.name = cleanName;
		entry.examineText = cleanExamine;
		entry.firstSeen = now;
		entry.lastSeen = now;
		entry.worldPoint = worldPoint;
		entry.source = OBSERVED_SOURCE;
		entry.build = build;
		entry.confidence = OBSERVED_CONFIDENCE;
		entry.seenCount = 1;
		return entry;
	}

	private ObjectExamineEntry wikiEntry(int objectId, String name, String examineText, String wikiPage)
	{
		final String cleanName = cleanName(name);
		final String cleanExamine = cleanExamine(examineText);
		final String cleanPage = cleanName(wikiPage);
		if (objectId < 0 || cleanName == null || cleanExamine == null || cleanPage == null)
		{
			return null;
		}
		final String now = Instant.now().toString();
		final ObjectExamineEntry entry = new ObjectExamineEntry();
		entry.objectId = objectId;
		entry.name = cleanName;
		entry.examineText = cleanExamine;
		entry.firstSeen = now;
		entry.lastSeen = now;
		entry.source = WIKI_SOURCE;
		entry.confidence = WIKI_CONFIDENCE;
		entry.wikiPage = cleanPage;
		entry.seenCount = 1;
		return entry;
	}

	private void loadAll()
	{
		final Map<Integer, ObjectExamineEntry> loadedUser = readFile(userFile, OBSERVED_SOURCE);
		final Map<Integer, ObjectExamineEntry> loadedWiki = readFile(wikiFile, WIKI_SOURCE);
		final Map<Integer, ObjectExamineEntry> loadedImported = readFile(importedFile, IMPORTED_SOURCE);
		final Map<Integer, ObjectExamineEntry> loadedBundled = loadBundledResource
			? readBundledResource()
			: readFile(bundledFile, BUNDLED_SOURCE);
		synchronized (this)
		{
			userById = mergeExisting(loadedUser, userById);
			wikiById = mergeExisting(loadedWiki, wikiById);
			importedById = loadedImported;
			bundledById = loadedBundled;
		}
	}

	private Map<Integer, ObjectExamineEntry> mergeExisting(Map<Integer, ObjectExamineEntry> base,
		Map<Integer, ObjectExamineEntry> overlay)
	{
		Map<Integer, ObjectExamineEntry> out = base;
		for (ObjectExamineEntry entry : overlay.values())
		{
			out = mergeInto(out, entry).map;
		}
		return out;
	}

	private Map<Integer, ObjectExamineEntry> readBundledResource()
	{
		try (Reader reader = new java.io.InputStreamReader(
			ObjectExamineService.class.getResourceAsStream(BUNDLED_FILE_NAME), StandardCharsets.UTF_8))
		{
			return index(gson.fromJson(reader, ObjectExamineEntry[].class), BUNDLED_SOURCE);
		}
		catch (Exception e)
		{
			log.debug("No bundled object examine baseline loaded", e);
			return Collections.emptyMap();
		}
	}

	private Map<Integer, ObjectExamineEntry> readFile(Path file, String defaultSource)
	{
		if (file == null || !Files.exists(file))
		{
			return Collections.emptyMap();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			return index(gson.fromJson(reader, ObjectExamineEntry[].class), defaultSource);
		}
		catch (IOException | JsonSyntaxException e)
		{
			log.debug("Failed to load object examines from {}", file, e);
			return Collections.emptyMap();
		}
	}

	private Map<Integer, ObjectExamineEntry> index(ObjectExamineEntry[] entries, String defaultSource)
	{
		if (entries == null || entries.length == 0)
		{
			return Collections.emptyMap();
		}
		Map<Integer, ObjectExamineEntry> out = new HashMap<>();
		for (ObjectExamineEntry raw : entries)
		{
			final ObjectExamineEntry entry = normalized(raw, defaultSource);
			if (entry == null)
			{
				continue;
			}
			final MergeOutcome outcome = mergeInto(out, entry);
			out = outcome.map;
			if (outcome.result == MergeResult.CONFLICT)
			{
				log.debug("Ignored conflicting object examine entry id={} name={}", entry.objectId, entry.name);
			}
		}
		return Collections.unmodifiableMap(out);
	}

	private ObjectExamineEntry normalized(ObjectExamineEntry raw, String defaultSource)
	{
		if (raw == null || raw.objectId < 0)
		{
			return null;
		}
		final String name = cleanName(raw.name);
		final String examine = cleanExamine(raw.examineText);
		if (name == null || examine == null)
		{
			return null;
		}
		final ObjectExamineEntry entry = copy(raw);
		entry.name = name;
		entry.examineText = examine;
		entry.source = cleanName(entry.source) == null ? defaultSource : entry.source.trim();
		entry.confidence = cleanName(entry.confidence) == null ? OBSERVED_CONFIDENCE : entry.confidence.trim();
		entry.seenCount = Math.max(1, entry.seenCount);
		return entry;
	}

	private MergeOutcome mergeInto(Map<Integer, ObjectExamineEntry> source,
		ObjectExamineEntry entry)
	{
		final Map<Integer, ObjectExamineEntry> out = new HashMap<>(source);
		final ObjectExamineEntry existing = out.get(entry.objectId);
		if (existing == null)
		{
			out.put(entry.objectId, copy(entry));
			return new MergeOutcome(Collections.unmodifiableMap(out), MergeResult.STORED);
		}
		if (!sameText(existing.name, entry.name))
		{
			return new MergeOutcome(source, MergeResult.CONFLICT);
		}
		if (!sameText(existing.examineText, entry.examineText))
		{
			return new MergeOutcome(source, MergeResult.CONFLICT);
		}

		final ObjectExamineEntry merged = copy(existing);
		merged.firstSeen = earlier(existing.firstSeen, entry.firstSeen);
		merged.lastSeen = later(existing.lastSeen, entry.lastSeen);
		merged.worldPoint = entry.worldPoint != null ? entry.worldPoint : existing.worldPoint;
		merged.build = Math.max(existing.build, entry.build);
		merged.seenCount = Math.max(1, existing.seenCount) + Math.max(1, entry.seenCount);
		if (cleanName(merged.source) == null)
		{
			merged.source = entry.source;
		}
		if (cleanName(merged.confidence) == null)
		{
			merged.confidence = entry.confidence;
		}
		out.put(entry.objectId, merged);
		return new MergeOutcome(Collections.unmodifiableMap(out), MergeResult.MERGED);
	}

	private ObjectExamineEntry compatible(Map<Integer, ObjectExamineEntry> source, int objectId, String name)
	{
		if (objectId < 0)
		{
			return null;
		}
		final ObjectExamineEntry entry = source.get(objectId);
		if (entry == null)
		{
			return null;
		}
		final String cleanName = cleanName(name);
		if (cleanName != null && !sameText(cleanName, entry.name))
		{
			return null;
		}
		return entry;
	}

	private void saveUserAsync()
	{
		executor.execute(this::saveUser);
	}

	private void saveWikiAsync()
	{
		executor.execute(() -> saveMap(wikiFile, wikiById, "wiki object examine"));
	}

	private void saveUser()
	{
		saveMap(userFile, userById, "object examine store");
	}

	private void saveMap(Path file, Map<Integer, ObjectExamineEntry> map, String label)
	{
		if (file == null)
		{
			return;
		}
		try
		{
			final Path parent = file.getParent();
			if (parent != null)
			{
				Files.createDirectories(parent);
			}
			final List<ObjectExamineEntry> entries = new ArrayList<>(map.values());
			entries.sort(Comparator.comparingInt(e -> e.objectId));
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8))
			{
				gson.toJson(entries, writer);
			}
		}
		catch (IOException e)
		{
			log.debug("Failed to write {} {}", label, file, e);
		}
	}

	public static String cleanExamine(String examineText)
	{
		if (examineText == null)
		{
			return null;
		}
		final String text = examineText.trim();
		if (text.isEmpty())
		{
			return null;
		}
		final String lower = text.toLowerCase(Locale.ROOT);
		if ("null".equals(lower) || "unknown".equals(lower) || "nothing interesting happens.".equals(lower)
			|| lower.contains("no examine"))
		{
			return null;
		}
		return text;
	}

	private static String cleanName(String name)
	{
		if (name == null)
		{
			return null;
		}
		final String clean = name.trim();
		return clean.isEmpty() ? null : clean;
	}

	private static boolean sameText(String a, String b)
	{
		return a != null && b != null && a.equalsIgnoreCase(b);
	}

	private static String earlier(String a, String b)
	{
		if (a == null || a.isEmpty())
		{
			return b;
		}
		if (b == null || b.isEmpty())
		{
			return a;
		}
		return a.compareTo(b) <= 0 ? a : b;
	}

	private static String later(String a, String b)
	{
		if (a == null || a.isEmpty())
		{
			return b;
		}
		if (b == null || b.isEmpty())
		{
			return a;
		}
		return a.compareTo(b) >= 0 ? a : b;
	}

	private static ObjectExamineEntry copy(ObjectExamineEntry entry)
	{
		final ObjectExamineEntry copy = new ObjectExamineEntry();
		copy.objectId = entry.objectId;
		copy.name = entry.name;
		copy.examineText = entry.examineText;
		copy.firstSeen = entry.firstSeen;
		copy.lastSeen = entry.lastSeen;
		copy.worldPoint = entry.worldPoint == null ? null
			: new ObservedWorldPoint(entry.worldPoint.x, entry.worldPoint.y, entry.worldPoint.plane);
		copy.source = entry.source;
		copy.build = entry.build;
		copy.confidence = entry.confidence;
		copy.seenCount = entry.seenCount;
		copy.wikiPage = entry.wikiPage;
		return copy;
	}

	public enum MergeResult
	{
		STORED,
		MERGED,
		CONFLICT,
		IGNORED
	}

	private static final class MergeOutcome
	{
		private final Map<Integer, ObjectExamineEntry> map;
		private final MergeResult result;

		private MergeOutcome(Map<Integer, ObjectExamineEntry> map, MergeResult result)
		{
			this.map = map;
			this.result = result;
		}
	}

	@SuppressWarnings("unused")
	public static final class ObjectExamineEntry
	{
		public int objectId;
		public String name;
		public String examineText;
		public String firstSeen;
		public String lastSeen;
		public ObservedWorldPoint worldPoint;
		public String source;
		public int build;
		public String confidence;
		public int seenCount;
		public String wikiPage;
	}

	@SuppressWarnings("unused")
	public static final class ObservedWorldPoint
	{
		public int x;
		public int y;
		public int plane;

		public ObservedWorldPoint()
		{
		}

		public ObservedWorldPoint(int x, int y, int plane)
		{
			this.x = x;
			this.y = y;
			this.plane = plane;
		}
	}
}
