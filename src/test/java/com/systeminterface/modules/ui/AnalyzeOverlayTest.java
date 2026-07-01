package com.systeminterface.modules.ui;

import com.google.gson.Gson;
import com.systeminterface.modules.skills.ResourceData;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AnalyzeOverlayTest
{
	private ResourceData data;

	@Before
	public void setUp()
	{
		data = ResourceData.load(new Gson());
	}

	@Test
	public void resourceOutputLabel_usesCleanItemNameWithoutValue()
	{
		ResourceData.ResourceEntry oakLogs = data.forItemId(1521);

		assertEquals("Oak logs", AnalyzeOverlay.resourceOutputLabel(oakLogs));
	}

	@Test
	public void resourceOutputLabel_handlesMissingEntry()
	{
		assertEquals("", AnalyzeOverlay.resourceOutputLabel(null));
	}

	@Test
	public void methodLabel_returnsStandaloneMethodForResourceRows()
	{
		assertEquals("Harpoon", AnalyzeOverlay.methodLabel("harpoon"));
		assertEquals("Big net", AnalyzeOverlay.methodLabel("bignet"));
		assertEquals("", AnalyzeOverlay.methodLabel(null));
	}

	@Test
	public void explicitNotedName_addsNotedSuffixOnce()
	{
		assertEquals("Oak logs (noted)", AnalyzeOverlay.explicitNotedName("Oak logs", true));
		assertEquals("Oak logs (noted)", AnalyzeOverlay.explicitNotedName("Oak logs (noted)", true));
		assertEquals("Oak logs", AnalyzeOverlay.explicitNotedName("Oak logs", false));
	}

	@Test
	public void inferNpcRoleAndService_usesNameAndExamineText()
	{
		assertEquals("Guide", AnalyzeOverlay.inferNpcRole("Lumbridge Guide", "He can help new players."));
		assertEquals("Information", AnalyzeOverlay.inferNpcService("Lumbridge Guide", "He can help new players."));

		assertEquals("Merchant", AnalyzeOverlay.inferNpcRole("Shopkeeper", "Runs a useful general store."));
		assertEquals("Trading/shop", AnalyzeOverlay.inferNpcService("Shopkeeper", "Runs a useful general store."));

		assertEquals("Non-combat NPC", AnalyzeOverlay.inferNpcRole("Man", "One of many citizens."));
		assertNull(AnalyzeOverlay.inferNpcService("Man", "One of many citizens."));
	}
}
