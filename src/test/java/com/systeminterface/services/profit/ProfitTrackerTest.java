package com.systeminterface.services.profit;

import com.google.gson.Gson;
import com.systeminterface.services.state.SessionTotals;
import com.systeminterface.services.state.StateTracker;
import com.systeminterface.services.state.TargetState;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.game.ItemStack;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ProfitTrackerTest
{
	private static final int SALMON = 331;
	private static final long SALMON_PRICE = 100L;
	private static final Object TILE = "tile-a";
	private static final Object OTHER_TILE = "tile-b";

	private ScheduledExecutorService executor;
	private StateTracker stateTracker;
	private ProfitTracker profitTracker;

	@Before
	public void setUp()
	{
		executor = Executors.newSingleThreadScheduledExecutor();
		stateTracker = new StateTracker(new Gson(), executor, new SessionTotals());
		profitTracker = new ProfitTracker(stateTracker, id -> id == SALMON ? SALMON_PRICE : 0L);
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	@Test
	public void expectedPickupCreditsTargetsFifo()
	{
		profitTracker.onInventoryChanged(inv());
		profitTracker.onLoot("Goblin", Collections.singletonList(stack(SALMON, 3)));
		profitTracker.onLoot("Cow", Collections.singletonList(stack(SALMON, 2)));

		profitTracker.onInventoryChanged(inv(SALMON, 4));

		assertTarget("Goblin", 300L, 300L, 3);
		assertTarget("Cow", 100L, 200L, 1);
	}

	@Test
	public void bankWithdrawWithoutExpectedLootIsIgnored()
	{
		profitTracker.onInventoryChanged(inv());
		profitTracker.onInventoryChanged(inv(SALMON, 5));

		assertNull(stateTracker.get("Goblin"));
	}

	@Test
	public void dropDebitsLiveKeptLotsFifo()
	{
		profitTracker.onInventoryChanged(inv());
		profitTracker.onLoot("Goblin", Collections.singletonList(stack(SALMON, 3)));
		profitTracker.onLoot("Cow", Collections.singletonList(stack(SALMON, 2)));
		profitTracker.onInventoryChanged(inv(SALMON, 4));

		profitTracker.onItemDropped(SALMON, 4);
		profitTracker.onInventoryChanged(inv());

		assertTarget("Goblin", 0L, 300L, 0);
		assertTarget("Cow", 0L, 200L, 0);
	}

	@Test
	public void dropThenRepickRestoresOriginalTarget()
	{
		profitTracker.onInventoryChanged(inv());
		profitTracker.onLoot("Goblin", Collections.singletonList(stack(SALMON, 1)));
		profitTracker.onInventoryChanged(inv(SALMON, 1));

		profitTracker.onItemDropped(SALMON, 1, TILE, 2);
		profitTracker.onInventoryChanged(inv());
		assertTarget("Goblin", 0L, 100L, 0);

		profitTracker.onInventoryChanged(inv(SALMON, 1));
		profitTracker.onItemDespawned(SALMON, 1, TILE, 5);
		profitTracker.onGameTick(5);

		assertTarget("Goblin", 100L, 100L, 1);
	}

	@Test
	public void sameItemIdFromAnotherNpcDoesNotRestoreEarlierDrop()
	{
		profitTracker.onInventoryChanged(inv());
		profitTracker.onLoot("Goblin", Collections.singletonList(stack(SALMON, 1)));
		profitTracker.onInventoryChanged(inv(SALMON, 1));
		profitTracker.onItemDropped(SALMON, 1, TILE, 2);
		profitTracker.onInventoryChanged(inv());

		profitTracker.onLoot("Cow", Collections.singletonList(stack(SALMON, 1)));
		profitTracker.onInventoryChanged(inv(SALMON, 1));
		profitTracker.onItemDespawned(SALMON, 1, TILE, 5);
		profitTracker.onGameTick(5);

		assertTarget("Goblin", 0L, 100L, 0);
		assertTarget("Cow", 100L, 100L, 1);
	}

	@Test
	public void sameItemIdFromMultipleNpcsCreditsPickupByGroundLocation()
	{
		profitTracker.onInventoryChanged(inv());
		profitTracker.onLoot("Goblin", Collections.singletonList(stack(SALMON, 1)), TILE);
		profitTracker.onLoot("Man", Collections.singletonList(stack(SALMON, 1)), OTHER_TILE);

		profitTracker.onInventoryChanged(inv(SALMON, 1));
		profitTracker.onItemDespawned(SALMON, 1, OTHER_TILE, 5);
		profitTracker.onGameTick(5);

		assertTarget("Goblin", 0L, 100L, 0);
		assertTarget("Man", 100L, 100L, 1);

		profitTracker.onInventoryChanged(inv(SALMON, 2));
		profitTracker.onItemDespawned(SALMON, 1, TILE, 6);
		profitTracker.onGameTick(6);

		assertTarget("Goblin", 100L, 100L, 1);
		assertTarget("Man", 100L, 100L, 1);
	}

	@Test
	public void sameItemIdDropUsesInventorySlotSource()
	{
		profitTracker.onInventoryChanged(container());
		profitTracker.onLoot("Goblin", Collections.singletonList(stack(SALMON, 1)), TILE);
		profitTracker.onLoot("Man", Collections.singletonList(stack(SALMON, 1)), OTHER_TILE);

		profitTracker.onInventoryChanged(container(0, SALMON, 1));
		profitTracker.onItemDespawned(SALMON, 1, OTHER_TILE, 5);
		profitTracker.onGameTick(5);

		profitTracker.onInventoryChanged(container(0, SALMON, 1, 1, SALMON, 1));
		profitTracker.onItemDespawned(SALMON, 1, TILE, 6);
		profitTracker.onGameTick(6);

		profitTracker.onItemDropped(SALMON, 1, 1, TILE, 7);
		profitTracker.onInventoryChanged(container(0, SALMON, 1));

		assertTarget("Goblin", 0L, 100L, 0);
		assertTarget("Man", 100L, 100L, 1);
	}

	@Test
	public void sameNpcMultipleSameItemPickupsAllRegister()
	{
		profitTracker.onInventoryChanged(container());
		profitTracker.onLoot("Goblin", Collections.singletonList(stack(SALMON, 1)), TILE);
		profitTracker.onLoot("Goblin", Collections.singletonList(stack(SALMON, 1)), OTHER_TILE);

		profitTracker.onInventoryChanged(container(0, SALMON, 1));
		profitTracker.onItemDespawned(SALMON, 1, TILE, 5);
		profitTracker.onGameTick(5);

		profitTracker.onInventoryChanged(container(0, SALMON, 1, 1, SALMON, 1));
		profitTracker.onItemDespawned(SALMON, 1, OTHER_TILE, 6);
		profitTracker.onGameTick(6);

		assertTarget("Goblin", 200L, 200L, 2);

		profitTracker.onItemDropped(SALMON, 1, 1, TILE, 7);
		profitTracker.onInventoryChanged(container(0, SALMON, 1));

		assertTarget("Goblin", 100L, 200L, 1);
	}

	@Test
	public void bankedLootThenWithdrawAndDropDoesNotDebitKept()
	{
		profitTracker.onInventoryChanged(inv());
		profitTracker.onLoot("Goblin", Collections.singletonList(stack(SALMON, 2)));
		profitTracker.onInventoryChanged(inv(SALMON, 2));

		profitTracker.onInventoryChanged(inv());
		profitTracker.onInventoryChanged(inv(SALMON, 2));
		profitTracker.onItemDropped(SALMON, 2);

		assertTarget("Goblin", 200L, 200L, 2);
	}

	private void assertTarget(String target, long keptValue, long totalValue, int keptQty)
	{
		TargetState state = stateTracker.get(target);
		assertEquals(keptValue, state.getKeptValue());
		assertEquals(totalValue, state.getTotalDropValue());
		assertEquals(keptQty, state.getKeptItems().getOrDefault(SALMON, 0).intValue());
	}

	private static ItemStack stack(int itemId, int qty)
	{
		return new ItemStack(itemId, qty);
	}

	private static Map<Integer, Integer> inv(int... idQtyPairs)
	{
		Map<Integer, Integer> map = new HashMap<>();
		for (int i = 0; i < idQtyPairs.length; i += 2)
		{
			map.put(idQtyPairs[i], idQtyPairs[i + 1]);
		}
		return map;
	}

	private static ItemContainer container(int... slotItemQtyTriples)
	{
		Item[] items = new Item[28];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(-1, 0);
		}
		for (int i = 0; i < slotItemQtyTriples.length; i += 3)
		{
			items[slotItemQtyTriples[i]] = new Item(slotItemQtyTriples[i + 1], slotItemQtyTriples[i + 2]);
		}
		return new FakeItemContainer(items);
	}

	private static final class FakeItemContainer implements ItemContainer
	{
		private final Item[] items;

		private FakeItemContainer(Item[] items)
		{
			this.items = items;
		}

		@Override
		public int getId()
		{
			return 0;
		}

		@Override
		public Item[] getItems()
		{
			return items;
		}

		@Override
		public Item getItem(int slot)
		{
			return slot >= 0 && slot < items.length ? items[slot] : null;
		}

		@Override
		public boolean contains(int itemId)
		{
			return count(itemId) > 0;
		}

		@Override
		public int count(int itemId)
		{
			int total = 0;
			for (Item item : items)
			{
				if (item.getId() == itemId)
				{
					total += item.getQuantity();
				}
			}
			return total;
		}

		@Override
		public int size()
		{
			return items.length;
		}

		@Override
		public int count()
		{
			int total = 0;
			for (Item item : items)
			{
				if (item.getId() >= 0 && item.getQuantity() > 0)
				{
					total++;
				}
			}
			return total;
		}

		@Override
		public int find(int itemId)
		{
			for (int i = 0; i < items.length; i++)
			{
				if (items[i].getId() == itemId)
				{
					return i;
				}
			}
			return -1;
		}

		@Override
		public net.runelite.api.Node getNext()
		{
			return null;
		}

		@Override
		public net.runelite.api.Node getPrevious()
		{
			return null;
		}

		@Override
		public long getHash()
		{
			return 0;
		}
	}
}
