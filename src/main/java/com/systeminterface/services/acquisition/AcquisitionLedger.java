package com.systeminterface.services.acquisition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Session-scoped provenance ledger for item acquisitions.
 *
 * <p>The ledger owns live-vs-finalized kept state and source attribution. Skilling uses
 * signal-gated acquisitions, where an item id resolves to a skill. Combat uses expected
 * acquisitions, where a loot event registers FIFO lots by target before an inventory pickup.
 *
 * @param <S> source type, such as {@code Skill} for skilling or a target record for combat loot
 */
public final class AcquisitionLedger<S>
{
	private static final int MAX_EXPECTED_STACKS = 2_000;

	private final Function<Integer, S> sourceForItem;
	private final BiPredicate<S, Integer> itemMatchesSource;
	private final int signalWindowTicks;

	private final Map<Integer, Deque<Lot<S>>> expected = new HashMap<>();
	private final Map<Integer, Deque<Lot<S>>> liveAcquired = new HashMap<>();
	private final Map<Object, Map<Integer, Deque<Lot<S>>>> groundDrops = new HashMap<>();
	private final Map<Integer, Integer> unexplainedGainsThisTick = new HashMap<>();
	private final Map<Integer, Deque<Lot<S>>> pickedUpExpectedThisTick = new HashMap<>();
	private final Map<Integer, Deque<Lot<S>>> pickedUpOwnedDropsThisTick = new HashMap<>();
	private final Map<Integer, Integer> pendingGains = new HashMap<>();

	private Map<Integer, Integer> lastInventory;
	private S lastSignalSource;
	private long lastSignalTick = Long.MIN_VALUE;
	private long pendingGainsTick = Long.MIN_VALUE;
	private int expectedStacks;

	public AcquisitionLedger(Function<Integer, S> sourceForItem, int signalWindowTicks)
	{
		this(sourceForItem, (source, itemId) -> Objects.equals(sourceForItem.apply(itemId), source),
			signalWindowTicks);
	}

	public AcquisitionLedger(Function<Integer, S> sourceForItem,
		BiPredicate<S, Integer> itemMatchesSource, int signalWindowTicks)
	{
		this.sourceForItem = Objects.requireNonNull(sourceForItem, "sourceForItem");
		this.itemMatchesSource = Objects.requireNonNull(itemMatchesSource, "itemMatchesSource");
		this.signalWindowTicks = signalWindowTicks;
	}

	/**
	 * Registers item quantities expected from a source before the inventory pickup occurs.
	 */
	public void recordExpected(S source, int itemId, int qty)
	{
		recordExpected(source, itemId, qty, null);
	}

	/**
	 * Registers item quantities expected from a source at a known ground location.
	 */
	public void recordExpected(S source, int itemId, int qty, Object location)
	{
		if (source == null || itemId < 0 || qty <= 0)
		{
			return;
		}
		if (expectedStacks >= MAX_EXPECTED_STACKS)
		{
			expected.clear();
			expectedStacks = 0;
		}
		addLot(expected, new Lot<>(source, itemId, qty, location));
		expectedStacks++;
	}

	/**
	 * Diffs inventory and credits gains only when they match previously expected source lots.
	 */
	public List<Change<S>> applyExpectedInventoryDiff(Map<Integer, Integer> current, long tick)
	{
		final Map<Integer, Integer> now = current == null ? Collections.emptyMap() : current;
		if (lastInventory == null)
		{
			lastInventory = new HashMap<>(now);
			return Collections.emptyList();
		}

		final List<Change<S>> changes = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : now.entrySet())
		{
			final int itemId = entry.getKey();
			final int gained = entry.getValue() - lastInventory.getOrDefault(itemId, 0);
			if (gained > 0)
			{
				int credited = 0;
				if (!requiresGroundResolution(itemId))
				{
					for (Lot<S> lot : drainExpected(itemId, gained))
					{
						acquire(changes, lot.source, itemId, lot.qty);
						credited += lot.qty;
					}
				}
				if (credited < gained)
				{
					unexplainedGainsThisTick.merge(itemId, gained - credited, Integer::sum);
				}
			}
		}
		clampLiveToInventory(now);
		lastInventory = new HashMap<>(now);
		return changes;
	}

	/**
	 * Reconciles an inventory snapshot against the previous one and emits signal-gated changes.
	 */
	public List<Change<S>> applyInventoryDiff(Map<Integer, Integer> current, long tick)
	{
		final Map<Integer, Integer> now = current == null ? Collections.emptyMap() : current;
		if (lastInventory == null)
		{
			lastInventory = new HashMap<>(now);
			return Collections.emptyList();
		}

		final List<Change<S>> changes = new ArrayList<>();
		boolean creditedFreshGain = false;
		for (Map.Entry<Integer, Integer> entry : now.entrySet())
		{
			final int itemId = entry.getKey();
			final int gained = entry.getValue() - lastInventory.getOrDefault(itemId, 0);
			if (gained <= 0)
			{
				continue;
			}

			final S source = sourceForFreshGain(itemId, tick);
			if (source == null)
			{
				continue;
			}

			if (signalFreshFor(source, tick))
			{
				acquire(changes, source, itemId, gained);
				creditedFreshGain = true;
			}
			else
			{
				pendingGains.merge(itemId, gained, Integer::sum);
				pendingGainsTick = tick;
				unexplainedGainsThisTick.merge(itemId, gained, Integer::sum);
			}
		}

		if (creditedFreshGain)
		{
			consumeSignal();
		}
		clampLiveToInventory(now);
		lastInventory = new HashMap<>(now);
		return changes;
	}

	/**
	 * Records an action signal and flushes any same-tick inventory gains waiting for provenance.
	 */
	public List<Change<S>> recordSignal(S source, long tick)
	{
		lastSignalSource = source;
		lastSignalTick = tick;
		if (source == null || pendingGains.isEmpty() || tick - pendingGainsTick > signalWindowTicks)
		{
			return Collections.emptyList();
		}

		final List<Change<S>> changes = new ArrayList<>();
		final java.util.Iterator<Map.Entry<Integer, Integer>> it = pendingGains.entrySet().iterator();
		while (it.hasNext())
		{
			final Map.Entry<Integer, Integer> entry = it.next();
			if (itemMatchesSource.test(source, entry.getKey()))
			{
				acquire(changes, source, entry.getKey(), entry.getValue());
				it.remove();
			}
		}
		if (!changes.isEmpty())
		{
			consumeSignal();
		}
		return changes;
	}

	/**
	 * Drops only live acquired units. Banked/used/finalized units are no longer live and are ignored.
	 */
	public List<Change<S>> drop(int itemId, int qty, Object location, long tick)
	{
		return drop(itemId, qty, location, true, null);
	}

	public List<Change<S>> drop(int itemId, int qty, Object location, long tick, S preferredSource)
	{
		return drop(itemId, qty, location, true, preferredSource);
	}

	/**
	 * Drops live acquired units and treats the kept loss as final immediately.
	 */
	public List<Change<S>> dropFinalized(int itemId, int qty)
	{
		return drop(itemId, qty, null, false, null);
	}

	public List<Change<S>> dropFinalized(int itemId, int qty, S preferredSource)
	{
		return drop(itemId, qty, null, false, preferredSource);
	}

	private List<Change<S>> drop(int itemId, int qty, Object location, boolean trackGround, S preferredSource)
	{
		if (qty <= 0)
		{
			return Collections.emptyList();
		}

		List<Lot<S>> dropped = preferredSource == null
			? drainLots(liveAcquired.get(itemId), qty)
			: drainLots(liveAcquired.get(itemId), qty, preferredSource);
		if (preferredSource != null && dropped.isEmpty())
		{
			dropped = drainLots(liveAcquired.get(itemId), qty);
		}
		removeIfEmpty(liveAcquired, itemId);
		if (dropped.isEmpty())
		{
			return Collections.emptyList();
		}

		final List<Change<S>> changes = new ArrayList<>();
		final Map<Integer, Deque<Lot<S>>> at = trackGround
			? groundDrops.computeIfAbsent(location, k -> new HashMap<>()) : null;
		for (Lot<S> lot : dropped)
		{
			if (trackGround)
			{
				addLot(at, lot.copy());
			}
			changes.add(Change.dropped(lot.source, itemId, lot.qty));
		}
		return changes;
	}

	/**
	 * Buffers one of this ledger's ground drops leaving the world. Reconciliation happens end-of-tick.
	 */
	public void groundDespawned(int itemId, int qty, Object location, long tick)
	{
		expectedLootDespawned(itemId, qty, location);

		final Map<Integer, Deque<Lot<S>>> at = groundDrops.get(location);
		if (at == null)
		{
			return;
		}
		final List<Lot<S>> gone = drainLots(at.get(itemId), qty <= 0 ? Integer.MAX_VALUE : qty);
		removeIfEmpty(at, itemId);
		if (at.isEmpty())
		{
			groundDrops.remove(location);
		}
		for (Lot<S> lot : gone)
		{
			addLot(pickedUpOwnedDropsThisTick, lot);
		}
	}

	/**
	 * Resolves expected ground loot and owned dropped units when their despawn coincides with
	 * an unexplained same-item inventory gain in the same tick.
	 */
	public List<Change<S>> reconcileTick(long tick)
	{
		final List<Change<S>> changes = new ArrayList<>();
		for (Map.Entry<Integer, Deque<Lot<S>>> entry : pickedUpExpectedThisTick.entrySet())
		{
			final int itemId = entry.getKey();
			final int gains = unexplainedGainsThisTick.getOrDefault(itemId, 0);
			if (gains <= 0)
			{
				continue;
			}
			for (Lot<S> lot : drainLots(entry.getValue(), gains))
			{
				acquire(changes, lot.source, itemId, lot.qty);
				unexplainedGainsThisTick.put(itemId,
					unexplainedGainsThisTick.getOrDefault(itemId, 0) - lot.qty);
			}
		}

		for (Map.Entry<Integer, Deque<Lot<S>>> entry : pickedUpOwnedDropsThisTick.entrySet())
		{
			final int itemId = entry.getKey();
			final int gains = unexplainedGainsThisTick.getOrDefault(itemId, 0);
			if (gains <= 0)
			{
				continue;
			}
			for (Lot<S> lot : drainLots(entry.getValue(), gains))
			{
				addLot(liveAcquired, lot.copy());
				unexplainedGainsThisTick.put(itemId,
					unexplainedGainsThisTick.getOrDefault(itemId, 0) - lot.qty);
				changes.add(Change.restored(lot.source, itemId, lot.qty));
			}
		}
		pickedUpExpectedThisTick.clear();
		pickedUpOwnedDropsThisTick.clear();
		unexplainedGainsThisTick.clear();
		return changes;
	}

	public void expireStalePending(long tick)
	{
		if (!pendingGains.isEmpty() && tick - pendingGainsTick > signalWindowTicks)
		{
			pendingGains.clear();
		}
	}

	public void reset()
	{
		expected.clear();
		liveAcquired.clear();
		groundDrops.clear();
		unexplainedGainsThisTick.clear();
		pickedUpExpectedThisTick.clear();
		pickedUpOwnedDropsThisTick.clear();
		pendingGains.clear();
		lastInventory = null;
		lastSignalSource = null;
		lastSignalTick = Long.MIN_VALUE;
		pendingGainsTick = Long.MIN_VALUE;
		expectedStacks = 0;
	}

	private void acquire(List<Change<S>> changes, S source, int itemId, int qty)
	{
		addLot(liveAcquired, new Lot<>(source, itemId, qty));
		changes.add(Change.acquired(source, itemId, qty));
	}

	private List<Lot<S>> drainExpected(int itemId, int qty)
	{
		final Deque<Lot<S>> lots = expected.get(itemId);
		if (lots == null)
		{
			return Collections.emptyList();
		}
		final List<Lot<S>> drained = new ArrayList<>();
		int remaining = qty;
		while (remaining > 0 && !lots.isEmpty())
		{
			final Lot<S> lot = lots.peekFirst();
			final int take = Math.min(remaining, lot.qty);
			drained.add(new Lot<>(lot.source, itemId, take, lot.location));
			lot.qty -= take;
			remaining -= take;
			if (lot.qty == 0)
			{
				lots.removeFirst();
				expectedStacks--;
			}
		}
		removeIfEmpty(expected, itemId);
		return drained;
	}

	private void expectedLootDespawned(int itemId, int qty, Object location)
	{
		if (location == null)
		{
			return;
		}
		final Deque<Lot<S>> lots = expected.get(itemId);
		if (lots == null)
		{
			return;
		}

		final List<Lot<S>> drained = new ArrayList<>();
		int remaining = qty <= 0 ? Integer.MAX_VALUE : qty;
		final java.util.Iterator<Lot<S>> it = lots.iterator();
		while (remaining > 0 && it.hasNext())
		{
			final Lot<S> lot = it.next();
			if (!Objects.equals(location, lot.location))
			{
				continue;
			}
			final int take = Math.min(remaining, lot.qty);
			drained.add(new Lot<>(lot.source, lot.itemId, take, lot.location));
			lot.qty -= take;
			remaining -= take;
			if (lot.qty == 0)
			{
				it.remove();
				expectedStacks--;
			}
		}
		removeIfEmpty(expected, itemId);
		for (Lot<S> lot : drained)
		{
			addLot(pickedUpExpectedThisTick, lot);
		}
	}

	private boolean requiresGroundResolution(int itemId)
	{
		final Deque<Lot<S>> lots = expected.get(itemId);
		if (lots == null)
		{
			return false;
		}

		for (Lot<S> lot : lots)
		{
			if (lot.location != null)
			{
				return true;
			}
		}
		return false;
	}

	private static <S> List<Lot<S>> drainLots(Deque<Lot<S>> lots, int qty)
	{
		if (lots == null || qty <= 0)
		{
			return Collections.emptyList();
		}
		final List<Lot<S>> drained = new ArrayList<>();
		int remaining = qty;
		while (remaining > 0 && !lots.isEmpty())
		{
			final Lot<S> lot = lots.peekFirst();
			final int take = Math.min(remaining, lot.qty);
			drained.add(new Lot<>(lot.source, lot.itemId, take, lot.location));
			lot.qty -= take;
			remaining -= take;
			if (lot.qty == 0)
			{
				lots.removeFirst();
			}
		}
		return drained;
	}

	private static <S> List<Lot<S>> drainLots(Deque<Lot<S>> lots, int qty, S source)
	{
		if (lots == null || qty <= 0)
		{
			return Collections.emptyList();
		}
		final List<Lot<S>> drained = new ArrayList<>();
		int remaining = qty;
		final java.util.Iterator<Lot<S>> it = lots.iterator();
		while (remaining > 0 && it.hasNext())
		{
			final Lot<S> lot = it.next();
			if (!Objects.equals(source, lot.source))
			{
				continue;
			}
			final int take = Math.min(remaining, lot.qty);
			drained.add(new Lot<>(lot.source, lot.itemId, take, lot.location));
			lot.qty -= take;
			remaining -= take;
			if (lot.qty == 0)
			{
				it.remove();
			}
		}
		return drained;
	}

	private boolean signalFreshFor(S source, long tick)
	{
		return source != null
			&& Objects.equals(source, lastSignalSource)
			&& lastSignalTick != Long.MIN_VALUE
			&& tick >= lastSignalTick
			&& tick - lastSignalTick <= signalWindowTicks;
	}

	private S sourceForFreshGain(int itemId, long tick)
	{
		if (lastSignalSource != null
			&& signalFreshFor(lastSignalSource, tick)
			&& itemMatchesSource.test(lastSignalSource, itemId))
		{
			return lastSignalSource;
		}
		return sourceForItem.apply(itemId);
	}

	private void consumeSignal()
	{
		lastSignalSource = null;
		lastSignalTick = Long.MIN_VALUE;
	}

	private void clampLiveToInventory(Map<Integer, Integer> current)
	{
		final java.util.Iterator<Map.Entry<Integer, Deque<Lot<S>>>> it =
			liveAcquired.entrySet().iterator();
		while (it.hasNext())
		{
			final Map.Entry<Integer, Deque<Lot<S>>> entry = it.next();
			final int inv = current.getOrDefault(entry.getKey(), 0);
			int excess = count(entry.getValue()) - inv;
			while (excess > 0 && !entry.getValue().isEmpty())
			{
				final Lot<S> lot = entry.getValue().peekLast();
				final int take = Math.min(excess, lot.qty);
				lot.qty -= take;
				excess -= take;
				if (lot.qty == 0)
				{
					entry.getValue().removeLast();
				}
			}
			if (entry.getValue().isEmpty())
			{
				it.remove();
			}
		}
	}

	private static <S> void addLot(Map<Integer, Deque<Lot<S>>> lotsByItem, Lot<S> lot)
	{
		lotsByItem.computeIfAbsent(lot.itemId, k -> new LinkedList<>()).addLast(lot);
	}

	private static <S> int count(Deque<Lot<S>> lots)
	{
		int total = 0;
		for (Lot<S> lot : lots)
		{
			total += lot.qty;
		}
		return total;
	}

	private static <S> void removeIfEmpty(Map<Integer, Deque<Lot<S>>> map, int itemId)
	{
		final Deque<Lot<S>> lots = map.get(itemId);
		if (lots != null && lots.isEmpty())
		{
			map.remove(itemId);
		}
	}

	public enum ChangeType
	{
		ACQUIRED,
		DROPPED,
		RESTORED
	}

	public static final class Change<S>
	{
		private final ChangeType type;
		private final S source;
		private final int itemId;
		private final int qty;

		private Change(ChangeType type, S source, int itemId, int qty)
		{
			this.type = type;
			this.source = source;
			this.itemId = itemId;
			this.qty = qty;
		}

		public static <S> Change<S> acquired(S source, int itemId, int qty)
		{
			return new Change<>(ChangeType.ACQUIRED, source, itemId, qty);
		}

		public static <S> Change<S> dropped(S source, int itemId, int qty)
		{
			return new Change<>(ChangeType.DROPPED, source, itemId, qty);
		}

		public static <S> Change<S> restored(S source, int itemId, int qty)
		{
			return new Change<>(ChangeType.RESTORED, source, itemId, qty);
		}

		public ChangeType getType()
		{
			return type;
		}

		public S getSource()
		{
			return source;
		}

		public int getItemId()
		{
			return itemId;
		}

		public int getQty()
		{
			return qty;
		}
	}

	private static final class Lot<S>
	{
		private final S source;
		private final int itemId;
		private final Object location;
		private int qty;

		private Lot(S source, int itemId, int qty)
		{
			this(source, itemId, qty, null);
		}

		private Lot(S source, int itemId, int qty, Object location)
		{
			this.source = source;
			this.itemId = itemId;
			this.qty = qty;
			this.location = location;
		}

		private Lot<S> copy()
		{
			return new Lot<>(source, itemId, qty, location);
		}
	}
}
