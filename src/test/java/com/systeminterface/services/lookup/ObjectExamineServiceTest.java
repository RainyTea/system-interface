package com.systeminterface.services.lookup;

import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ObjectExamineServiceTest
{
	private ObjectExamineService svc(Path dir) throws Exception
	{
		Path store = dir.resolve("object-examines.json");
		return new ObjectExamineService(new Gson(), store, Runnable::run);
	}

	@Test
	public void curatedIsReturnedAndWinsOverObserved() throws Exception
	{
		Path dir = Files.createTempDirectory("oe");
		ObjectExamineService s = svc(dir);
		s.loadCurated(new ByteArrayInputStream("{\"1234\":\"A curated bank booth.\"}".getBytes(StandardCharsets.UTF_8)));
		s.capture(1234, "Bank booth", "An observed bank booth.");

		assertEquals("A curated bank booth.", s.getExamine(1234));
	}

	@Test
	public void observedIsReturnedWhenNoCuratedEntry() throws Exception
	{
		Path dir = Files.createTempDirectory("oe");
		ObjectExamineService s = svc(dir);
		s.capture(42, "Door", "A sturdy door.");

		assertEquals("A sturdy door.", s.getExamine(42));
		assertNull(s.getExamine(99));
	}

	@Test
	public void observedCapturesSurviveReload() throws Exception
	{
		java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("oe");
		ObjectExamineService s1 = svc(dir);
		s1.capture(7, "Altar", "A holy altar.");

		ObjectExamineService s2 = svc(dir);
		s2.loadLocal();

		assertEquals("A holy altar.", s2.getExamine(7));
	}

	@Test
	public void parsesExamineFieldFromInfoboxWikitext()
	{
		String wt = "{{Infobox Scenery\n|name = Bank booth\n|examine = Bank your items here.\n|members = Yes\n}}";
		assertEquals("Bank your items here.", ObjectExamineService.parseExamine(wt));
		assertNull(ObjectExamineService.parseExamine("no infobox here"));
	}

	@Test
	public void objectExamineMessageWithinWindowIsCaptured() throws Exception
	{
		ObjectExamineService s = svc(java.nio.file.Files.createTempDirectory("oe"));
		s.recordPendingExamine(500, "Fern", 10);
		s.onObjectExamineMessage("A leafy fern.", 11);
		assertEquals("A leafy fern.", s.getExamine(500));
	}

	@Test
	public void objectExamineMessageOutsideWindowIsIgnored() throws Exception
	{
		ObjectExamineService s = svc(java.nio.file.Files.createTempDirectory("oe"));
		s.recordPendingExamine(500, "Fern", 10);
		s.onObjectExamineMessage("A leafy fern.", 99);
		assertNull(s.getExamine(500));
	}
}
