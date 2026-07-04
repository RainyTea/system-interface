package com.systeminterface.services.profit;

import com.systeminterface.services.acquisition.AcquisitionLedger;
import com.systeminterface.services.state.StateTracker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntToLongFunction;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.game.ItemStack;

/**
 * Tracks per-monster profitability: lifetime GE value dropped vs value actually kept.
 *
 * <p>Combat loot registers expected item lots by target when RuneLite reports a loot event.
 * Later inventory gains drain those expected lots FIFO through {@link AcquisitionLedger}; drops
 * drain the live kept lots FIFO. Banked/used loot is finalized as kept once it leaves inventory
 * without a drop, so withdrawing the same item id later cannot debit the original target.
 *
 * <p>Same-item-id-from-different-source disambiguation is resolved by ground location: an expected
 * lot carries the dropping NPC's tile, and a re-pickup is attributed to the lot whose ground stack
 * despawned in that same tick. Inventory-slot provenance lets a drop debit the specific target a
 * slot's units came from.
 *
 * <p>Value lookups happen on the client thread (via {@link ItemValuer}, itself backed by
 * {@code ItemManager}). The ledger is session-scoped; only the lifetime totals on
 * {@code TargetState} persist. All methods run on the client thread.
 */
@Slf4j
@Singleton
public final class ProfitTracker
{
	private final StateTracker stateTracker;
	private final IntToLongFunction priceFn;
	private final AcquisitionLedger<LootSource> acquisitionLedger;
	private final Map<Integer, SlotLot> liveSlots = new HashMap<>();
	private final List<SlotGain> pendingSlotGains = new ArrayList<>();
	private Map<Integer, InventorySlot> lastInventorySlots;

	@Inject
	public ProfitTracker(ItemValuer valuer, StateTracker stateTracker)
	{
		this(stateTracker, valuer::unitValue);
	}

	ProfitTracker(StateTracker stateTracker, IntToLongFunction priceFn)
	{
		this.stateTracker = stateTracker;
		this.priceFn = priceFn;
		this.acquisitionLedger = new AcquisitionLedger<>(itemId -> null, 0);
	}

	/** Records a monster's drop: credits total-dropped value and registers expected pickup lots. */
	public void onLoot(String target, Collection<ItemStack> items)
	{
		onLoot(target, items, null);
	}

	/** Records a monster's drop at its ground location for same-item source disambiguation. */
	public void onLoot(String target, Collection<ItemStack> items, Object location)
	{
		if (target == null || items == null)
		{
			return;
		}
		for (ItemStack stack : items)
		{
			final int qty = stack.getQuantity();
			if (qty <= 0)
			{
				continue;
			}
			final long unit = unitValue(stack.getId());
			if (unit <= 0)
			{
				continue;
			}
			stateTracker.recordDropValue(target, unit * qty);
			acquisitionLedger.recordExpected(new LootSource(target, unit), stack.getId(), qty, location);
		}
	}

	/** Diffs the inventory against the last snapshot and credits matched pickups as kept. */
	public void onInventoryChanged(ItemContainer inventory)
	{
		if (inventory == null)
		{
			return;
		}
		final Map<Integer, InventorySlot> slots = snapshot(inventory);
		final List<SlotGain> slotGains = slotGains(slots);
		removeLostSlots(slots);

		final Collection<AcquisitionLedger.Change<LootSource>> changes =
			acquisitionLedger.applyExpectedInventoryDiff(count(inventory), 0);
		applyLedgerChanges(changes);
		rememberSlotSources(changes, slotGains);
		for (SlotGain gain : slotGains)
		{
			if (gain.qty > 0)
			{
				pendingSlotGains.add(gain);
			}
		}
		lastInventorySlots = slots;
	}

	void onInventoryChanged(Map<Integer, Integer> inventory)
	{
		applyLedgerChanges(acquisitionLedger.applyExpectedInventoryDiff(inventory, 0));
	}

	/** The player dropped {@code qty} of {@code itemId}; subtract any of it that was live kept loot. */
	public void onItemDropped(int itemId, int qty)
	{
		applyLedgerChanges(acquisitionLedger.dropFinalized(itemId, qty));
	}

	/** A real ground drop: subtract now, but allow a same-tick despawn/pickup to restore it. */
	public void onItemDropped(int itemId, int qty, Object location, long tick)
	{
		applyLedgerChanges(acquisitionLedger.drop(itemId, qty, location, tick));
	}

	public void onItemDropped(int itemId, int qty, int slot, Object location, long tick)
	{
		final SlotLot slotLot = liveSlots.get(slot);
		final LootSource source = slotLot != null && slotLot.itemId == itemId ? slotLot.source : null;
		applyLedgerChanges(acquisitionLedger.drop(itemId, qty, location, tick, source));
		consumeSlot(slot, itemId, qty);
	}

	/** Non-ground removals such as Bury/Eat/Drink: subtract live kept loot permanently. */
	public void onItemRemovedFinalized(int itemId, int qty)
	{
		applyLedgerChanges(acquisitionLedger.dropFinalized(itemId, qty));
	}

	public void onItemRemovedFinalized(int itemId, int qty, int slot)
	{
		final SlotLot slotLot = liveSlots.get(slot);
		final LootSource source = slotLot != null && slotLot.itemId == itemId ? slotLot.source : null;
		applyLedgerChanges(acquisitionLedger.dropFinalized(itemId, qty, source));
		consumeSlot(slot, itemId, qty);
	}

	public void onItemDespawned(int itemId, int qty, Object location, long tick)
	{
		acquisitionLedger.groundDespawned(itemId, qty, location, tick);
	}

	public boolean onGameTick(long tick)
	{
		final Collection<AcquisitionLedger.Change<LootSource>> changes =
			acquisitionLedger.reconcileTick(tick);
		final boolean changed = applyLedgerChanges(changes);
		rememberSlotSources(changes, pendingSlotGains);
		pendingSlotGains.clear();
		return changed;
	}

	/** Clears all session ledgers (call on logout / profile switch). */
	public void reset()
	{
		acquisitionLedger.reset();
		liveSlots.clear();
		pendingSlotGains.clear();
		lastInventorySlots = null;
	}

	private boolean applyLedgerChanges(Collection<AcquisitionLedger.Change<LootSource>> changes)
	{
		boolean changed = false;
		for (AcquisitionLedger.Change<LootSource> change : changes)
		{
			changed = true;
			final LootSource source = change.getSource();
			final long value = source.unitValue * change.getQty();
			switch (change.getType())
			{
				case ACQUIRED:
				case RESTORED:
					stateTracker.recordKeptDelta(source.target, value);
					stateTracker.recordKeptItem(source.target, change.getItemId(), change.getQty());
					break;
				case DROPPED:
					stateTracker.recordKeptDelta(source.target, -value);
					stateTracker.recordKeptItem(source.target, change.getItemId(), -change.getQty());
					break;
				default:
					break;
			}
		}
		return changed;
	}

	private static Map<Integer, Integer> count(ItemContainer container)
	{
		final Map<Integer, Integer> counts = new HashMap<>();
		for (Item item : container.getItems())
		{
			final int id = item.getId();
			if (id < 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			counts.merge(id, item.getQuantity(), Integer::sum);
		}
		return counts;
	}

	private static Map<Integer, InventorySlot> snapshot(ItemContainer container)
	{
		final Map<Integer, InventorySlot> slots = new HashMap<>();
		final Item[] items = container.getItems();
		for (int i = 0; i < items.length; i++)
		{
			final Item item = items[i];
			if (item != null && item.getId() >= 0 && item.getQuantity() > 0)
			{
				slots.put(i, new InventorySlot(item.getId(), item.getQuantity()));
			}
		}
		return slots;
	}

	private List<SlotGain> slotGains(Map<Integer, InventorySlot> current)
	{
		if (lastInventorySlots == null)
		{
			return new ArrayList<>();
		}

		final List<SlotGain> gains = new ArrayList<>();
		for (Map.Entry<Integer, InventorySlot> entry : current.entrySet())
		{
			final int slot = entry.getKey();
			final InventorySlot now = entry.getValue();
			final InventorySlot before = lastInventorySlots.get(slot);
			final int gained = before != null && before.itemId == now.itemId
				? now.qty - before.qty
				: now.qty;
			if (gained > 0)
			{
				gains.add(new SlotGain(slot, now.itemId, gained));
			}
		}
		return gains;
	}

	private void removeLostSlots(Map<Integer, InventorySlot> current)
	{
		if (lastInventorySlots == null)
		{
			return;
		}

		for (Map.Entry<Integer, InventorySlot> entry : lastInventorySlots.entrySet())
		{
			final int slot = entry.getKey();
			final InventorySlot before = entry.getValue();
			final InventorySlot now = current.get(slot);
			final int lost = now != null && now.itemId == before.itemId
				? before.qty - now.qty
				: before.qty;
			if (lost > 0)
			{
				consumeSlot(slot, before.itemId, lost);
			}
		}
	}

	private void rememberSlotSources(Collection<AcquisitionLedger.Change<LootSource>> changes,
		List<SlotGain> slotGains)
	{
		for (AcquisitionLedger.Change<LootSource> change : changes)
		{
			if (change.getType() != AcquisitionLedger.ChangeType.ACQUIRED
				&& change.getType() != AcquisitionLedger.ChangeType.RESTORED)
			{
				continue;
			}

			int remaining = change.getQty();
			for (SlotGain gain : slotGains)
			{
				if (remaining <= 0)
				{
					break;
				}
				if (gain.itemId != change.getItemId() || gain.qty <= 0)
				{
					continue;
				}
				final int take = Math.min(remaining, gain.qty);
				rememberSlotSource(gain.slot, change.getItemId(), change.getSource(), take);
				gain.qty -= take;
				remaining -= take;
			}
		}
	}

	private void rememberSlotSource(int slot, int itemId, LootSource source, int qty)
	{
		if (qty <= 0 || source == null)
		{
			return;
		}
		final SlotLot existing = liveSlots.get(slot);
		if (existing != null && existing.itemId == itemId && Objects.equals(existing.source, source))
		{
			existing.qty += qty;
		}
		else
		{
			liveSlots.put(slot, new SlotLot(itemId, source, qty));
		}
	}

	private void consumeSlot(int slot, int itemId, int qty)
	{
		final SlotLot existing = liveSlots.get(slot);
		if (existing == null || existing.itemId != itemId || qty <= 0)
		{
			return;
		}
		existing.qty -= qty;
		if (existing.qty <= 0)
		{
			liveSlots.remove(slot);
		}
	}

	/** Per-unit value in GP, floored at zero. The valuer already applies coin/platinum face value. */
	private long unitValue(int itemId)
	{
		return Math.max(0, priceFn.applyAsLong(itemId));
	}

	private static final class LootSource
	{
		private final String target;
		private final long unitValue;

		private LootSource(String target, long unitValue)
		{
			this.target = target;
			this.unitValue = unitValue;
		}

		@Override
		public boolean equals(Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof LootSource))
			{
				return false;
			}
			LootSource that = (LootSource) o;
			return unitValue == that.unitValue && Objects.equals(target, that.target);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(target, unitValue);
		}
	}

	private static final class InventorySlot
	{
		private final int itemId;
		private final int qty;

		private InventorySlot(int itemId, int qty)
		{
			this.itemId = itemId;
			this.qty = qty;
		}
	}

	private static final class SlotGain
	{
		private final int slot;
		private final int itemId;
		private int qty;

		private SlotGain(int slot, int itemId, int qty)
		{
			this.slot = slot;
			this.itemId = itemId;
			this.qty = qty;
		}
	}

	private static final class SlotLot
	{
		private final int itemId;
		private final LootSource source;
		private int qty;

		private SlotLot(int itemId, LootSource source, int qty)
		{
			this.itemId = itemId;
			this.source = source;
			this.qty = qty;
		}
	}
}
