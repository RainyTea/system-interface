package com.systeminterface.tools;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkillingMainActionMergeTest
{
	private static final Gson GSON = new Gson();
	private static JsonObject o(String j) { return GSON.fromJson(j, JsonObject.class); }

	@Test
	public void replacesMainAction_keepsExtraRolls()
	{
		JsonObject resource = o("{\"mainAction\":{\"name\":\"Oak logs\",\"itemId\":1521,\"objectIds\":[10820]},"
			+ "\"extraRolls\":{\"uses\":[\"Fletching:20\"],\"rate\":0.5}}");
		JsonObject fresh = o("{\"source\":\"recipe\",\"name\":\"Oak logs\",\"itemId\":1521,"
			+ "\"levelRequired\":15,\"xpPerAction\":37.5,\"objectIds\":[10820,36686,40758]}");
		SkillingMainActionMerge.apply(resource, fresh);
		assertEquals(3, resource.getAsJsonObject("mainAction").getAsJsonArray("objectIds").size());
		assertEquals(15, resource.getAsJsonObject("mainAction").get("levelRequired").getAsInt());
		assertEquals("Fletching:20", resource.getAsJsonObject("extraRolls").getAsJsonArray("uses").get(0).getAsString());
		assertEquals(0.5, resource.getAsJsonObject("extraRolls").get("rate").getAsDouble(), 1e-9);
	}

	@Test
	public void migratesLegacyFlatResource()
	{
		// A flat resource (no mainAction/extraRolls) is migrated: curated keys move to extraRolls.
		JsonObject resource = o("{\"name\":\"Oak logs\",\"itemId\":1521,\"levelRequired\":15,"
			+ "\"objectIds\":[10820],\"uses\":[\"Fletching:20\"],\"petBaseChanceOverride\":null}");
		JsonObject fresh = o("{\"name\":\"Oak logs\",\"itemId\":1521,\"levelRequired\":15,\"objectIds\":[10820,36686]}");
		SkillingMainActionMerge.apply(resource, fresh);
		assertTrue(resource.has("mainAction"));
		assertTrue(resource.has("extraRolls"));
		assertFalse(resource.has("levelRequired")); // top-level generated keys moved under mainAction
		assertEquals("Fletching:20", resource.getAsJsonObject("extraRolls").getAsJsonArray("uses").get(0).getAsString());
		assertEquals(2, resource.getAsJsonObject("mainAction").getAsJsonArray("objectIds").size());
	}
}
