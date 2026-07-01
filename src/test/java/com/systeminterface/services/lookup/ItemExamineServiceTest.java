package com.systeminterface.services.lookup;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ItemExamineServiceTest
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
	public void warmUpAsync_loadsCachedMappingExamineText() throws Exception
	{
		Path cacheFile = temporaryFolder.newFile(ItemExamineService.CACHE_FILE_NAME).toPath();
		Files.write(cacheFile, ("[{\"id\":995,\"name\":\"Coins\",\"examine\":\"Lovely money!\","
			+ "\"members\":false}]").getBytes(StandardCharsets.UTF_8));
		executor = Executors.newSingleThreadScheduledExecutor();
		ItemExamineService service = new ItemExamineService(new OkHttpClient(), new Gson(), executor, cacheFile);

		service.warmUpAsync(false).get(5, TimeUnit.SECONDS);

		assertTrue(service.isReady());
		assertEquals("Lovely money!", service.getExamine(995, false));
		assertEquals("wiki-mapping-cache", service.getProvenance());
	}
}
