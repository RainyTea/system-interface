package com.systeminterface.services.lookup;

import java.util.Set;
import net.runelite.api.Item;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HeldItemCacheTest
{
	private static Item item(int id) { return new Item(id, 1); }

	@Test
	public void idsOf_unionsInventoryAndWorn_dropsNegatives()
	{
		Item[] inv = { item(5073), item(-1), item(1521) };
		Item[] worn = { item(28136) };
		Set<Integer> ids = HeldItemCache.idsOf(inv, worn);
		assertTrue(ids.contains(5073));
		assertTrue(ids.contains(1521));
		assertTrue(ids.contains(28136));
		assertFalse(ids.contains(-1));
	}

	@Test
	public void idsOf_handlesNullArrays()
	{
		assertTrue(HeldItemCache.idsOf(null, null).isEmpty());
	}
}
