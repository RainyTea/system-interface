package com.systeminterface.tools;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SkillingRecipeMapperTest
{
	private static final Gson GSON = new Gson();
	private static JsonObject pj(String j) { return GSON.fromJson(j, JsonObject.class); }

	@Test
	public void mapsWoodcuttingGather()
	{
		JsonObject p = pj("{\"materials\":[],\"tools\":\"[[Axe]]\",\"facilities\":\"[[Oak tree]]\","
			+ "\"skills\":[{\"experience\":\"37.5\",\"level\":\"15\",\"name\":\"Woodcutting\"}]}");
		SkillingRecipeMapper.GenFields g = SkillingRecipeMapper.mapGathering("Woodcutting", p);
		assertEquals(15, g.level);
		assertEquals(37.5, g.xp, 1e-9);
		assertEquals("Oak tree", g.facilityName);
		assertTrue(g.secondaryNames.isEmpty());
		assertNull(g.method);
	}

	@Test
	public void mapsFishingWithSecondaryAndMethod()
	{
		JsonObject p = pj("{\"materials\":[{\"name\":\"Feather\",\"quantity\":1}],"
			+ "\"tools\":\"[[Fly fishing rod]]\",\"facilities\":\"[[Rod Fishing spot (lure, bait)]]\","
			+ "\"skills\":[{\"experience\":\"50\",\"level\":\"20\",\"name\":\"Fishing\"}]}");
		SkillingRecipeMapper.GenFields g = SkillingRecipeMapper.mapGathering("Fishing", p);
		assertEquals(20, g.level);
		assertEquals("Rod Fishing spot (lure, bait)", g.facilityName);
		assertTrue(g.secondaryNames.contains("Feather"));
		assertEquals("lure", g.method);
	}

	@Test
	public void skipsProcessingRecipe()
	{
		JsonObject p = pj("{\"materials\":[],\"tools\":\"[[Tinderbox]]\",\"facilities\":\"N/A\","
			+ "\"skills\":[{\"experience\":\"202.5\",\"level\":\"60\",\"name\":\"Firemaking\"}]}");
		assertNull(SkillingRecipeMapper.mapGathering("Woodcutting", p));
	}

	@Test
	public void methodFor_derivesFromToolAndSpot()
	{
		assertEquals("harpoon", SkillingRecipeMapper.methodFor("[[Harpoon]]", "[[Fishing spot (cage, harpoon)]]"));
		assertEquals("cage", SkillingRecipeMapper.methodFor("[[Lobster pot]]", "[[Fishing spot (cage, harpoon)]]"));
		assertEquals("net", SkillingRecipeMapper.methodFor("[[Small fishing net]]", "[[Fishing spot (net)]]"));
		assertNull(SkillingRecipeMapper.methodFor("[[Axe]]", "[[Oak tree]]"));
	}
}
