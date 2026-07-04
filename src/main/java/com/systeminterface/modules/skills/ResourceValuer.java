package com.systeminterface.modules.skills;

import java.util.Map;
import java.util.function.IntToLongFunction;
import net.runelite.api.gameval.ItemID;

/**
 * Values gathered skilling resources in GP, mirroring how
 * {@link com.systeminterface.services.profit.ProfitTracker} values combat loot: coins are worth
 * 1, platinum tokens 1000, and everything else its Grand Exchange price (floored at 0).
 *
 * <p>The GE price is supplied as a function so the logic stays pure and unit-testable —
 * the panel passes {@code id -> itemManager.getItemPrice(id)}.
 */
public final class ResourceValuer
{
	private ResourceValuer()
	{
		// utility class
	}

	/** Per-unit GP value of {@code itemId}, using {@code gePrice} for non-coin items. */
	public static long unitValue(int itemId, IntToLongFunction gePrice)
	{
		if (itemId == ItemID.COINS)
		{
			return 1L;
		}
		if (itemId == ItemID.PLATINUM)
		{
			return 1000L;
		}
		return Math.max(0L, gePrice.applyAsLong(itemId));
	}

	/** Total GP of a {@code itemId -> quantity} map, summing {@code unitValue × quantity}. */
	public static long totalValue(Map<Integer, Long> counts, IntToLongFunction gePrice)
	{
		long sum = 0L;
		for (Map.Entry<Integer, Long> e : counts.entrySet())
		{
			sum += unitValue(e.getKey(), gePrice) * e.getValue();
		}
		return sum;
	}
}
