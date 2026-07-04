package com.systeminterface.modules.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Pure accumulation model for the item-gain ping channel (spec §4). Each item type keeps one live
 * line whose quantity ticks up and whose fade timer refreshes while gains keep landing; the line
 * slides out after its TTL. Notable gains (curated reward/pet, tracked, or over a value threshold)
 * live longer. All timing is caller-supplied {@code nowMs} so this is deterministic and testable.
 * Single-threaded by contract (client thread) — no synchronization.
 *
 * <p>{@code @Singleton} so the overlay and the plugin's gain/gather callbacks share one instance.
 */
@Singleton
public final class ItemGainPings
{
	@Inject
	public ItemGainPings()
	{
	}

	public static final long COMMON_TTL_MS = 4000L;
	public static final long NOTABLE_TTL_MS = 9000L;

	public static final class Ping
	{
		public final int itemId;
		public final String name;
		public final long qty;
		public final boolean notable;
		public final long lastUpdateMs;

		Ping(int itemId, String name, long qty, boolean notable, long lastUpdateMs)
		{
			this.itemId = itemId;
			this.name = name;
			this.qty = qty;
			this.notable = notable;
			this.lastUpdateMs = lastUpdateMs;
		}

		Ping accumulate(long moreQty, boolean nowNotable, long nowMs)
		{
			return new Ping(itemId, name, qty + moreQty, notable || nowNotable, nowMs);
		}

		long ttl()
		{
			return notable ? NOTABLE_TTL_MS : COMMON_TTL_MS;
		}
	}

	private final Map<Integer, Ping> lines = new LinkedHashMap<>();

	/** Adds a gain: accumulates into the item's live line (refreshing its fade) or starts one. */
	public void add(int itemId, String name, long qty, boolean notable, long nowMs)
	{
		if (qty <= 0)
		{
			return;
		}
		final Ping existing = lines.get(itemId);
		lines.put(itemId, existing == null
			? new Ping(itemId, name, qty, notable, nowMs)
			: existing.accumulate(qty, notable, nowMs));
	}

	/** Lines still within their TTL (most-recent first). Prunes expired lines as a side effect. */
	public List<Ping> visible(long nowMs)
	{
		final Iterator<Map.Entry<Integer, Ping>> it = lines.entrySet().iterator();
		while (it.hasNext())
		{
			final Ping p = it.next().getValue();
			if (nowMs - p.lastUpdateMs > p.ttl())
			{
				it.remove();
			}
		}
		final List<Ping> out = new ArrayList<>(lines.values());
		out.sort(Comparator.comparingLong((Ping p) -> p.lastUpdateMs).reversed());
		return out;
	}

	/** Notable = tracked, a curated reward/pet, or unit value at/above a positive threshold. */
	public static boolean isNotable(int itemId, long unitValue, boolean tracked,
		boolean curatedRewardOrPet, int valueThreshold)
	{
		if (tracked || curatedRewardOrPet)
		{
			return true;
		}
		return valueThreshold > 0 && unitValue >= valueThreshold;
	}
}
