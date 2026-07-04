package com.systeminterface.modules.ui;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ItemGainPingsTest
{
	@Test
	public void accumulatesSameItemAndRefreshesFade()
	{
		ItemGainPings pings = new ItemGainPings();
		pings.add(1515, "Yew logs", 1, false, 1000);
		pings.add(1515, "Yew logs", 2, false, 1500);
		List<ItemGainPings.Ping> v = pings.visible(1600);
		assertEquals(1, v.size());
		assertEquals(3, v.get(0).qty);
	}

	@Test
	public void commonLineExpiresAfterTtl()
	{
		ItemGainPings pings = new ItemGainPings();
		pings.add(1515, "Yew logs", 1, false, 0);
		assertFalse(pings.visible(ItemGainPings.COMMON_TTL_MS - 1).isEmpty());
		assertTrue(pings.visible(ItemGainPings.COMMON_TTL_MS + 1).isEmpty());
	}

	@Test
	public void notableLineLivesLongerThanCommon()
	{
		ItemGainPings pings = new ItemGainPings();
		pings.add(5073, "Bird nest", 1, true, 0);
		assertFalse(pings.visible(ItemGainPings.COMMON_TTL_MS + 1).isEmpty()); // still visible past common TTL
		assertTrue(pings.visible(ItemGainPings.NOTABLE_TTL_MS + 1).isEmpty());
	}

	@Test
	public void isNotable_trackedOrRewardOrOverThreshold()
	{
		assertTrue(ItemGainPings.isNotable(1, 0, true, false, 0));      // tracked
		assertTrue(ItemGainPings.isNotable(1, 0, false, true, 0));      // curated reward/pet
		assertTrue(ItemGainPings.isNotable(1, 5000, false, false, 1000)); // over threshold
		assertFalse(ItemGainPings.isNotable(1, 500, false, false, 1000)); // under threshold
		assertFalse(ItemGainPings.isNotable(1, 5000, false, false, 0));   // value rule disabled
	}
}
