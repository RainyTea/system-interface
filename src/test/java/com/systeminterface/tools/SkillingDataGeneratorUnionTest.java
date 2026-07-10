package com.systeminterface.tools;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SkillingDataGeneratorUnionTest
{
	private static final Gson GSON = new Gson();
	private static JsonObject o(String j) { return GSON.fromJson(j, JsonObject.class); }

	@Test
	public void union_preservesCuratedWhenWikiUnderResolves()
	{
		// Wiki resolves fewer ids than curated (the generic-facility case): nothing may be lost.
		List<Integer> curated = Arrays.asList(1276, 1278, 1286, 3033);
		List<Integer> wiki = Arrays.asList(1276, 1278);
		assertEquals(curated, SkillingDataGenerator.unionIds(curated, wiki));
	}

	@Test
	public void union_appendsNewWikiIdsDeduped()
	{
		List<Integer> curated = Arrays.asList(10822);
		List<Integer> wiki = Arrays.asList(10822, 36683, 40756);
		assertEquals(Arrays.asList(10822, 36683, 40756), SkillingDataGenerator.unionIds(curated, wiki));
	}

	@Test
	public void union_emptyExisting_isWikiOnly()
	{
		assertEquals(Arrays.asList(5, 6),
			SkillingDataGenerator.unionIds(Collections.emptyList(), Arrays.asList(5, 6)));
	}

	@Test
	public void existingIds_readsNestedMainAction()
	{
		JsonObject nested = o("{\"mainAction\":{\"itemId\":1515,\"objectIds\":[10822,36683]},\"extraRolls\":{}}");
		assertEquals(Arrays.asList(10822, 36683), SkillingDataGenerator.existingIds(nested, "objectIds"));
	}

	@Test
	public void existingIds_readsLegacyFlat_andHandlesMissing()
	{
		JsonObject flat = o("{\"itemId\":1515,\"objectIds\":[10822]}");
		assertEquals(Arrays.asList(10822), SkillingDataGenerator.existingIds(flat, "objectIds"));
		assertTrue(SkillingDataGenerator.existingIds(flat, "npcIds").isEmpty());
		assertTrue(SkillingDataGenerator.existingIds(null, "objectIds").isEmpty());
	}
}
