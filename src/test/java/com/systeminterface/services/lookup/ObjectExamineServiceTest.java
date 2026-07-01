package com.systeminterface.services.lookup;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ObjectExamineServiceTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private ScheduledExecutorService executor;

	@After
	public void tearDown()
	{
		if (executor != null)
		{
			executor.shutdownNow();
		}
	}

	@Test
	public void observe_storesObservedObjectExamineWithProvenance() throws Exception
	{
		ObjectExamineService service = service();

		assertEquals(ObjectExamineService.MergeResult.STORED, service.observe(8513, "Yew tree",
			"A fully grown Yew tree.",
			new ObjectExamineService.ObservedWorldPoint(3210, 3213, 0), 239));
		drainExecutor();

		ObjectExamineService.ObjectExamineEntry entry = service.lookup(8513, "Yew tree");
		assertEquals("A fully grown Yew tree.", entry.examineText);
		assertEquals("observed-examine", entry.source);
		assertEquals("observed", entry.confidence);
		assertEquals(239, entry.build);
		assertEquals(1, entry.seenCount);
		assertTrue(Files.exists(userFile()));
	}

	@Test
	public void observe_mergesSafeDuplicateAndSeenCount()
	{
		ObjectExamineService service = service();

		assertEquals(ObjectExamineService.MergeResult.STORED, service.observe(1276, "Tree",
			"One of the most common trees in RuneScape.",
			(ObjectExamineService.ObservedWorldPoint) null, 238));
		assertEquals(ObjectExamineService.MergeResult.MERGED, service.observe(1276, "Tree",
			"One of the most common trees in RuneScape.",
			(ObjectExamineService.ObservedWorldPoint) null, 239));

		ObjectExamineService.ObjectExamineEntry entry = service.lookup(1276, "Tree");
		assertEquals(2, entry.seenCount);
		assertEquals(239, entry.build);
	}

	@Test
	public void observe_conflictsOnSameIdDifferentName()
	{
		ObjectExamineService service = service();

		assertEquals(ObjectExamineService.MergeResult.STORED, service.observe(1000, "Door",
			"A sturdy door.", (ObjectExamineService.ObservedWorldPoint) null, 1));
		assertEquals(ObjectExamineService.MergeResult.CONFLICT, service.observe(1000, "Trapdoor",
			"A hidden trapdoor.", (ObjectExamineService.ObservedWorldPoint) null, 1));

		assertEquals("A sturdy door.", service.getExamine(1000, "Door"));
		assertNull(service.getExamine(1000, "Trapdoor"));
	}

	@Test
	public void observe_conflictsOnSameIdSameNameDifferentText()
	{
		ObjectExamineService service = service();

		assertEquals(ObjectExamineService.MergeResult.STORED, service.observe(2000, "Crate",
			"An old crate.", (ObjectExamineService.ObservedWorldPoint) null, 1));
		assertEquals(ObjectExamineService.MergeResult.CONFLICT, service.observe(2000, "Crate",
			"A suspicious crate.", (ObjectExamineService.ObservedWorldPoint) null, 1));

		assertEquals("An old crate.", service.getExamine(2000, "Crate"));
	}

	@Test
	public void observe_ignoresEmptyAndBrokenExamineText()
	{
		ObjectExamineService service = service();

		assertEquals(ObjectExamineService.MergeResult.IGNORED, service.observe(3000, "Rock",
			" ", (ObjectExamineService.ObservedWorldPoint) null, 1));
		assertEquals(ObjectExamineService.MergeResult.IGNORED, service.observe(3000, "Rock",
			"No examine available.", (ObjectExamineService.ObservedWorldPoint) null, 1));
		assertNull(service.getExamine(3000, "Rock"));
	}

	@Test
	public void warmUp_loadsUserBeforeBundledBeforeImported() throws Exception
	{
		write(userFile(), "[{\"objectId\":4000,\"name\":\"Statue\",\"examineText\":\"User text.\",\"source\":\"observed-examine\"}]");
		write(bundledFile(), "[{\"objectId\":4000,\"name\":\"Statue\",\"examineText\":\"Bundled text.\",\"source\":\"bundled-baseline\"},"
			+ "{\"objectId\":4001,\"name\":\"Fountain\",\"examineText\":\"Bundled fountain.\",\"source\":\"bundled-baseline\"}]");
		write(importedFile(), "[{\"objectId\":4000,\"name\":\"Statue\",\"examineText\":\"Imported text.\",\"source\":\"imported-observed\"},"
			+ "{\"objectId\":4002,\"name\":\"Bench\",\"examineText\":\"Imported bench.\",\"source\":\"imported-observed\"}]");
		ObjectExamineService service = service();

		service.warmUpAsync().get(5, TimeUnit.SECONDS);

		assertEquals("User text.", service.getExamine(4000, "Statue"));
		assertEquals("observed-examine", service.getProvenance(4000, "Statue"));
		assertEquals("Bundled fountain.", service.getExamine(4001, "Fountain"));
		assertEquals("Imported bench.", service.getExamine(4002, "Bench"));
	}

	@Test
	public void warmUp_ignoresMalformedEntriesConservatively() throws Exception
	{
		write(userFile(), "[{\"objectId\":5000,\"name\":\"\",\"examineText\":\"Missing name.\"},"
			+ "{\"objectId\":5001,\"name\":\"Rock\",\"examineText\":\"\"}]");
		ObjectExamineService service = service();

		service.warmUpAsync().get(5, TimeUnit.SECONDS);

		assertNull(service.getExamine(5000, ""));
		assertNull(service.getExamine(5001, "Rock"));
	}

	@Test
	public void observe_writesOnlyUserStoreAndDoesNotShareAutomatically() throws Exception
	{
		ObjectExamineService service = service();

		service.observe(6000, "Barrel", "A wooden barrel.",
			(ObjectExamineService.ObservedWorldPoint) null, 1);
		drainExecutor();

		assertTrue(Files.exists(userFile()));
		assertFalse(Files.exists(importedFile()));
	}

	@Test
	public void addWikiExamine_feedsUnifiedLookupAndPersistsCache() throws Exception
	{
		ObjectExamineService service = service();

		assertEquals(ObjectExamineService.MergeResult.STORED,
			service.addWikiExamine(10820, "Oak tree", "A beautiful old oak.", "Oak tree"));
		drainExecutor();

		assertEquals("A beautiful old oak.", service.getExamine(10820, "Oak tree"));
		assertEquals("wiki", service.getProvenance(10820, "Oak tree"));
		assertTrue(service.hasExamine(10820, "Oak tree"));
		assertTrue(Files.exists(wikiFile()));
		assertFalse(Files.exists(importedFile()));
	}

	@Test
	public void observedLocalEntryOverridesWikiEntry()
	{
		ObjectExamineService service = service();

		assertEquals(ObjectExamineService.MergeResult.STORED,
			service.addWikiExamine(40756, "Yew tree", "A splendid yew tree.", "Yew tree"));
		assertEquals(ObjectExamineService.MergeResult.STORED,
			service.observe(40756, "Yew tree", "A locally observed yew.",
				(ObjectExamineService.ObservedWorldPoint) null, 239));

		assertEquals("A locally observed yew.", service.getExamine(40756, "Yew tree"));
		assertEquals("observed-examine", service.getProvenance(40756, "Yew tree"));
	}

	@Test
	public void wikiExamine_ignoresMalformedText()
	{
		ObjectExamineService service = service();

		assertEquals(ObjectExamineService.MergeResult.IGNORED,
			service.addWikiExamine(100, "Crate", "No examine available.", "Crate"));
		assertNull(service.getExamine(100, "Crate"));
	}

	private ObjectExamineService service()
	{
		executor = Executors.newSingleThreadScheduledExecutor();
		return new ObjectExamineService(new Gson(), executor, userFile(), importedFile(), bundledFile());
	}

	private void drainExecutor() throws Exception
	{
		executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
	}

	private Path userFile()
	{
		return temporaryFolder.getRoot().toPath().resolve(ObjectExamineService.USER_FILE_NAME);
	}

	private Path importedFile()
	{
		return temporaryFolder.getRoot().toPath().resolve(ObjectExamineService.IMPORTED_FILE_NAME);
	}

	private Path wikiFile()
	{
		return temporaryFolder.getRoot().toPath().resolve(ObjectExamineService.WIKI_FILE_NAME);
	}

	private Path bundledFile()
	{
		return temporaryFolder.getRoot().toPath().resolve(ObjectExamineService.BUNDLED_FILE_NAME);
	}

	private static void write(Path file, String json) throws Exception
	{
		Files.write(file, json.getBytes(StandardCharsets.UTF_8));
	}
}
