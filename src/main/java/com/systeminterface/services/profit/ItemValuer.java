package com.systeminterface.services.profit;

/**
 * Resolves the per-unit GP value of an item id. Abstracts {@code ItemManager} price lookups
 * (plus coin/platinum face values) behind a one-method seam so profit logic is testable with a
 * plain fake instead of the RuneLite item service.
 */
@FunctionalInterface
public interface ItemValuer
{
	/** Per-unit value in GP, or {@code 0} for untradeable/valueless items. */
	long unitValue(int itemId);
}
