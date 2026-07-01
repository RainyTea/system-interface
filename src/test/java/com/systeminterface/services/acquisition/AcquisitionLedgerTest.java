package com.systeminterface.services.acquisition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AcquisitionLedgerTest
{
	private static final int SALMON = 331;
	private static final int IRON = 440;
	private static final Object TILE = "tile-a";
	private static final Object OTHER_TILE = "tile-b";

	private AcquisitionLedger<String> ledger;

	@Before
	public void setUp()
	{
		ledger = new AcquisitionLedger<>(itemId ->
		{
			if (itemId == SALMON)
			{
				return "fishing";
			}
			if (itemId == IRON)
			{
				return "mining";
			}
			return null;
		}, 1);
	}

	@Test
	public void signalThenInventoryGain_acquiresAndConsumesSignal()
	{
		ledger.applyInventoryDiff(inv(), 0);
		assertTrue(ledger.recordSignal("fishing", 1).isEmpty());

		List<AcquisitionLedger.Change<String>> changes = ledger.applyInventoryDiff(inv(SALMON, 1), 1);
		assertChange(changes, AcquisitionLedger.ChangeType.ACQUIRED, "fishing", SALMON, 1);

		assertTrue(ledger.applyInventoryDiff(inv(SALMON, 6), 1).isEmpty());
	}

	@Test
	public void inventoryBeforeSignal_sameTick_isFlushedBySignal()
	{
		ledger.applyInventoryDiff(inv(), 0);
		assertTrue(ledger.applyInventoryDiff(inv(SALMON, 1), 1).isEmpty());

		List<AcquisitionLedger.Change<String>> changes = ledger.recordSignal("fishing", 1);
		assertChange(changes, AcquisitionLedger.ChangeType.ACQUIRED, "fishing", SALMON, 1);
	}

	@Test
	public void bankWithdrawWithoutSignal_isIgnored()
	{
		ledger.applyInventoryDiff(inv(), 0);
		assertTrue(ledger.applyInventoryDiff(inv(SALMON, 5), 3).isEmpty());
		ledger.expireStalePending(5);

		assertTrue(ledger.drop(SALMON, 5, TILE, 6).isEmpty());
	}

	@Test
	public void dropRepickBeforeDespawn_restoresKeptOnly()
	{
		ledger.applyInventoryDiff(inv(), 0);
		ledger.recordSignal("fishing", 1);
		ledger.applyInventoryDiff(inv(SALMON, 1), 1);

		assertChange(ledger.drop(SALMON, 1, TILE, 2),
			AcquisitionLedger.ChangeType.DROPPED, "fishing", SALMON, 1);
		ledger.applyInventoryDiff(inv(), 2);
		ledger.applyInventoryDiff(inv(SALMON, 1), 5);
		ledger.groundDespawned(SALMON, 1, TILE, 5);

		assertChange(ledger.reconcileTick(5),
			AcquisitionLedger.ChangeType.RESTORED, "fishing", SALMON, 1);
	}

	@Test
	public void bankedAcquisition_thenWithdrawAndDrop_doesNotDeduct()
	{
		ledger.applyInventoryDiff(inv(), 0);
		ledger.recordSignal("fishing", 1);
		ledger.applyInventoryDiff(inv(SALMON, 3), 1);
		ledger.applyInventoryDiff(inv(), 2);

		ledger.applyInventoryDiff(inv(SALMON, 3), 5);
		ledger.expireStalePending(7);

		assertTrue(ledger.drop(SALMON, 3, TILE, 8).isEmpty());
	}

	@Test
	public void expectedPickup_fifoSourcesArePreservedThroughDrop()
	{
		AcquisitionLedger<String> combat = new AcquisitionLedger<>(itemId -> null, 1);
		combat.applyExpectedInventoryDiff(inv(), 0);
		combat.recordExpected("goblin", SALMON, 3);
		combat.recordExpected("cow", SALMON, 2);

		List<AcquisitionLedger.Change<String>> pickedUp =
			combat.applyExpectedInventoryDiff(inv(SALMON, 4), 1);
		assertEquals(2, pickedUp.size());
		assertChange(pickedUp.get(0), AcquisitionLedger.ChangeType.ACQUIRED, "goblin", SALMON, 3);
		assertChange(pickedUp.get(1), AcquisitionLedger.ChangeType.ACQUIRED, "cow", SALMON, 1);

		List<AcquisitionLedger.Change<String>> dropped = combat.drop(SALMON, 4, TILE, 2);
		assertEquals(2, dropped.size());
		assertChange(dropped.get(0), AcquisitionLedger.ChangeType.DROPPED, "goblin", SALMON, 3);
		assertChange(dropped.get(1), AcquisitionLedger.ChangeType.DROPPED, "cow", SALMON, 1);
	}

	@Test
	public void expectedPickup_ambiguousSourcesResolveByGroundLocation()
	{
		AcquisitionLedger<String> combat = new AcquisitionLedger<>(itemId -> null, 1);
		combat.applyExpectedInventoryDiff(inv(), 0);
		combat.recordExpected("goblin", SALMON, 1, TILE);
		combat.recordExpected("man", SALMON, 1, OTHER_TILE);

		assertTrue(combat.applyExpectedInventoryDiff(inv(SALMON, 1), 1).isEmpty());
		combat.groundDespawned(SALMON, 1, OTHER_TILE, 1);

		List<AcquisitionLedger.Change<String>> manPickup = combat.reconcileTick(1);
		assertChange(manPickup, AcquisitionLedger.ChangeType.ACQUIRED, "man", SALMON, 1);

		assertTrue(combat.applyExpectedInventoryDiff(inv(SALMON, 2), 2).isEmpty());
		combat.groundDespawned(SALMON, 1, TILE, 2);
		List<AcquisitionLedger.Change<String>> goblinPickup = combat.reconcileTick(2);
		assertChange(goblinPickup, AcquisitionLedger.ChangeType.ACQUIRED, "goblin", SALMON, 1);
	}

	@Test
	public void expectedPickup_sameSourceMultipleGroundLotsEachResolve()
	{
		AcquisitionLedger<String> combat = new AcquisitionLedger<>(itemId -> null, 1);
		combat.applyExpectedInventoryDiff(inv(), 0);
		combat.recordExpected("goblin", SALMON, 1, TILE);
		combat.recordExpected("goblin", SALMON, 1, OTHER_TILE);

		assertTrue(combat.applyExpectedInventoryDiff(inv(SALMON, 1), 1).isEmpty());
		combat.groundDespawned(SALMON, 1, TILE, 1);
		assertChange(combat.reconcileTick(1), AcquisitionLedger.ChangeType.ACQUIRED, "goblin", SALMON, 1);

		assertTrue(combat.applyExpectedInventoryDiff(inv(SALMON, 2), 2).isEmpty());
		combat.groundDespawned(SALMON, 1, OTHER_TILE, 2);
		assertChange(combat.reconcileTick(2), AcquisitionLedger.ChangeType.ACQUIRED, "goblin", SALMON, 1);
	}

	@Test
	public void expectedPickup_groundStackCannotCreditMoreThanInventoryGain()
	{
		AcquisitionLedger<String> combat = new AcquisitionLedger<>(itemId -> null, 1);
		combat.applyExpectedInventoryDiff(inv(), 0);
		combat.recordExpected("goblin", SALMON, 2, TILE);

		assertTrue(combat.applyExpectedInventoryDiff(inv(SALMON, 1), 1).isEmpty());
		combat.groundDespawned(SALMON, 2, TILE, 1);

		assertChange(combat.reconcileTick(1),
			AcquisitionLedger.ChangeType.ACQUIRED, "goblin", SALMON, 1);
	}

	@Test
	public void expectedPickup_repickRestoresOriginalSource()
	{
		AcquisitionLedger<String> combat = new AcquisitionLedger<>(itemId -> null, 1);
		combat.applyExpectedInventoryDiff(inv(), 0);
		combat.recordExpected("goblin", SALMON, 1);
		combat.applyExpectedInventoryDiff(inv(SALMON, 1), 1);

		combat.drop(SALMON, 1, TILE, 2);
		combat.applyExpectedInventoryDiff(inv(), 2);
		combat.applyExpectedInventoryDiff(inv(SALMON, 1), 5);
		combat.groundDespawned(SALMON, 1, TILE, 5);

		List<AcquisitionLedger.Change<String>> restored = combat.reconcileTick(5);
		assertChange(restored, AcquisitionLedger.ChangeType.RESTORED, "goblin", SALMON, 1);
	}

	@Test
	public void expectedPickup_bankedThenWithdrawnDropDoesNotDeduct()
	{
		AcquisitionLedger<String> combat = new AcquisitionLedger<>(itemId -> null, 1);
		combat.applyExpectedInventoryDiff(inv(), 0);
		combat.recordExpected("goblin", SALMON, 2);
		combat.applyExpectedInventoryDiff(inv(SALMON, 2), 1);
		combat.applyExpectedInventoryDiff(inv(), 2);

		combat.applyExpectedInventoryDiff(inv(SALMON, 2), 5);

		assertTrue(combat.drop(SALMON, 2, TILE, 6).isEmpty());
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

	private static void assertChange(List<AcquisitionLedger.Change<String>> changes,
		AcquisitionLedger.ChangeType type, String source, int itemId, int qty)
	{
		assertEquals(1, changes.size());
		assertChange(changes.get(0), type, source, itemId, qty);
	}

	private static void assertChange(AcquisitionLedger.Change<String> change,
		AcquisitionLedger.ChangeType type, String source, int itemId, int qty)
	{
		assertEquals(type, change.getType());
		assertEquals(source, change.getSource());
		assertEquals(itemId, change.getItemId());
		assertEquals(qty, change.getQty());
	}
}
