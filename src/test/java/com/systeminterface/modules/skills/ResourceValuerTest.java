package com.systeminterface.modules.skills;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntToLongFunction;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for {@link ResourceValuer} — the skilling-resource GP valuation that feeds
 * the all-time profit figure. The GE price is stubbed so the tests are deterministic.
 */
public class ResourceValuerTest
{
	/** Iron ore (id 440) priced at 120; everything else 0 unless overridden below. */
	private static final IntToLongFunction PRICES = id ->
	{
		switch (id)
		{
			case 440: return 120L;   // iron ore
			case 1515: return 250L;  // yew logs
			default: return 0L;
		}
	};

	@Test
	public void unitValue_coinsAreOneRegardlessOfGePrice()
	{
		assertEquals(1L, ResourceValuer.unitValue(ItemID.COINS, id -> 999_999L));
	}

	@Test
	public void unitValue_platinumIsThousand()
	{
		assertEquals(1000L, ResourceValuer.unitValue(ItemID.PLATINUM, id -> 0L));
	}

	@Test
	public void unitValue_otherItemsUseGePrice()
	{
		assertEquals(120L, ResourceValuer.unitValue(440, PRICES));
	}

	@Test
	public void unitValue_negativeGePriceFlooredToZero()
	{
		assertEquals(0L, ResourceValuer.unitValue(440, id -> -50L));
	}

	@Test
	public void totalValue_sumsCountTimesUnitValue()
	{
		Map<Integer, Long> counts = new HashMap<>();
		counts.put(440, 100L);   // 100 iron ore × 120 = 12,000
		counts.put(1515, 40L);   // 40 yew logs × 250 = 10,000
		assertEquals(22_000L, ResourceValuer.totalValue(counts, PRICES));
	}

	@Test
	public void totalValue_emptyIsZero()
	{
		assertEquals(0L, ResourceValuer.totalValue(new HashMap<>(), PRICES));
	}

	@Test
	public void totalValue_mixesCoinsWithPricedItems()
	{
		Map<Integer, Long> counts = new HashMap<>();
		counts.put(ItemID.COINS, 5_000L);  // 5,000 coins × 1 = 5,000
		counts.put(440, 10L);              // 10 iron ore × 120 = 1,200
		assertEquals(6_200L, ResourceValuer.totalValue(counts, PRICES));
	}
}
