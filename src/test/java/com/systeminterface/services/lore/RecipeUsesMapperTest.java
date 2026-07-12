package com.systeminterface.services.lore;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.systeminterface.services.wiki.BucketRow;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RecipeUsesMapperTest
{
	private static final Gson GSON = new Gson();

	private static BucketRow row(String json)
	{
		return new BucketRow(GSON.fromJson(json, JsonObject.class));
	}

	/** Uncut sapphire → Sapphire: skills carry name/level/experience; tools is a string. */
	@Test
	public void mapUse_fullRecipe()
	{
		String r = "{\"page_name\":\"Sapphire\",\"production_json\":\"{\\\"ticks\\\":\\\"2\\\","
			+ "\\\"materials\\\":[{\\\"quantity\\\":\\\"1\\\",\\\"name\\\":\\\"Uncut sapphire\\\"}],"
			+ "\\\"tools\\\":\\\"Chisel\\\",\\\"skills\\\":[{\\\"experience\\\":\\\"50\\\","
			+ "\\\"level\\\":\\\"20\\\",\\\"name\\\":\\\"Crafting\\\",\\\"boostable\\\":\\\"yes\\\"}],"
			+ "\\\"members\\\":false,\\\"output\\\":{\\\"cost\\\":225,\\\"quantity\\\":\\\"1\\\","
			+ "\\\"name\\\":\\\"Sapphire\\\"}}\"}";
		UseEntry e = RecipeUsesMapper.mapUse(row(r), GSON);
		assertEquals("Sapphire", e.getOutputName());
		assertEquals("Crafting", e.getSkill());
		assertEquals(Integer.valueOf(20), e.getLevel());
		assertEquals(Integer.valueOf(50), e.getXp());
	}

	/** Dragon bonemeal: skills empty, facilities present → skill-less entry, facility kept. */
	@Test
	public void mapUse_skillLessRecipeKeepsFacility()
	{
		String r = "{\"page_name\":\"Dragon bonemeal\",\"production_json\":\"{\\\"ticks\\\":\\\"\\\","
			+ "\\\"materials\\\":[{\\\"quantity\\\":\\\"1\\\",\\\"name\\\":\\\"Dragon bones\\\"}],"
			+ "\\\"facilities\\\":\\\"Bone grinder\\\",\\\"skills\\\":[],\\\"members\\\":true,"
			+ "\\\"output\\\":{\\\"cost\\\":0,\\\"quantity\\\":\\\"1\\\",\\\"name\\\":\\\"Dragon bonemeal\\\"}}\"}";
		UseEntry e = RecipeUsesMapper.mapUse(row(r), GSON);
		assertEquals("Dragon bonemeal", e.getOutputName());
		assertNull(e.getSkill());
		assertNull(e.getLevel());
		assertEquals("Bone grinder", e.getFacility());
	}

	/** Output name falls back to page_name when output.name is absent. */
	@Test
	public void mapUse_missingOutputName_usesPageName()
	{
		String r = "{\"page_name\":\"Some product\",\"production_json\":\"{\\\"skills\\\":[],"
			+ "\\\"output\\\":{\\\"cost\\\":0}}\"}";
		UseEntry e = RecipeUsesMapper.mapUse(row(r), GSON);
		assertEquals("Some product", e.getOutputName());
	}

	/** Malformed production_json → null, never throws. */
	@Test
	public void mapUse_malformedJson_isNull()
	{
		assertNull(RecipeUsesMapper.mapUse(row("{\"page_name\":\"X\",\"production_json\":\"not json\"}"), GSON));
		assertNull(RecipeUsesMapper.mapUse(row("{\"page_name\":\"X\"}"), GSON));
		assertNull(RecipeUsesMapper.mapUse(null, GSON));
	}

	/** bestUse: lowest required level wins; skill-less entries rank after skilled; tiebreak = first. */
	@Test
	public void bestUse_selectionRule()
	{
		UseEntry lvl20 = new UseEntry("Sapphire", null, "Crafting", 20, 50);
		UseEntry lvl5 = new UseEntry("Arrow shaft", null, "Fletching", 5, 5);
		UseEntry noSkill = new UseEntry("Bonemeal", "Bone grinder", null, null, null);
		assertEquals("Arrow shaft", RecipeUsesMapper.bestUse(Arrays.asList(lvl20, lvl5, noSkill)).getOutputName());
		assertEquals("Bonemeal", RecipeUsesMapper.bestUse(Collections.singletonList(noSkill)).getOutputName());
		UseEntry noSkill2 = new UseEntry("Blessed bones", "Altar", null, null, null);
		assertEquals("Bonemeal", RecipeUsesMapper.bestUse(Arrays.asList(noSkill, noSkill2)).getOutputName());
		assertNull(RecipeUsesMapper.bestUse(Collections.emptyList()));
		assertNull(RecipeUsesMapper.bestUse(null));
	}
}
