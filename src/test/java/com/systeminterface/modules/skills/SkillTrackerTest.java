package com.systeminterface.modules.skills;

import com.google.gson.Gson;
import com.systeminterface.services.state.SessionTotals;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntToLongFunction;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Provenance tests for {@link SkillTracker}'s gather ledger.
 *
 * <p>Core principle under test: <b>provenance comes from events, not inventory diffs.</b>
 * A resource is only credited as "gathered" when a coincident action signal occurs
 * (a "You catch ..." chat message and/or a Fishing XP {@code StatChanged}). Items that
 * enter the inventory without that signal — GE collects, bank withdrawals, trades — must
 * never touch the gathered/kept counts. The inventory diff is used only to reconcile the
 * kept-vs-dropped state of items that were legitimately gathered.
 *
 * <p>Loads the real bundled {@code ResourceData.json} and stubs item pricing so the tests
 * are deterministic and need no live {@code Client}/{@code ItemManager}.
 */
public class SkillTrackerTest
{
	private static final int SALMON = 331;  // lure fish, Skill.FISHING
	private static final int IRON = 440;    // iron ore, Skill.MINING
	private static final int BIRD_NEST = 5073;  // Phase-2 reward, resolves to Skill.WOODCUTTING
	private static final long SALMON_PRICE = 100L;

	private static final IntToLongFunction PRICES = id ->
	{
		switch (id)
		{
			case SALMON: return SALMON_PRICE;
			case 317: return 10L;  // raw shrimps
			case BIRD_NEST: return 0L;  // clue/seed nests may have 0 curated price; still must credit
			default: return 0L;
		}
	};

	private SkillTracker tracker;

	@Before
	public void setUp()
	{
		ResourceData data = ResourceData.load(new Gson());
		tracker = new SkillTracker(data, PRICES);
	}

	/** Opaque ground-tile key for drop/despawn correlation (a WorldPoint in production). */
	private static final Object TILE = "tile-a";

	private static Map<Integer, Integer> inv(int... idQtyPairs)
	{
		Map<Integer, Integer> m = new HashMap<>();
		for (int i = 0; i < idQtyPairs.length; i += 2)
		{
			m.put(idQtyPairs[i], idQtyPairs[i + 1]);
		}
		return m;
	}

	/** Drop {@code qty} of {@code itemId} at the test tile on {@code tick}. */
	private void drop(int itemId, int qty, long tick)
	{
		tracker.onItemDropped(itemId, qty, TILE, tick);
	}

	/** A ground item of mine despawns at the test tile (picked up or timed out). */
	private void despawn(int itemId, int qty, long tick)
	{
		tracker.onGroundResourceDespawned(itemId, qty, TILE, tick);
	}

	private long keptCount(Skill skill, int itemId)
	{
		SkillTracker.SkillState st = tracker.getSkillState(skill);
		return st == null ? 0L : st.getResourceCount(itemId);
	}

	private long grossCount(Skill skill, int itemId)
	{
		SkillTracker.SkillState st = tracker.getSkillState(skill);
		return st == null ? 0L : st.getGrossResourceCounts().getOrDefault(itemId, 0L);
	}

	/** Catch a fish (catch signal + fish in inventory) → gathered +1, session +1, value credited. */
	@Test
	public void catch_creditsGatherAndSession()
	{
		tracker.applyInventoryDiff(inv(), 0);            // baseline
		tracker.recordGatherSignal(Skill.FISHING, 1);    // "You catch ..." / Fishing XP
		tracker.applyInventoryDiff(inv(SALMON, 1), 1);   // fish enters inventory same tick

		assertEquals(1L, keptCount(Skill.FISHING, SALMON));
		assertEquals(1L, grossCount(Skill.FISHING, SALMON));
		assertEquals(SALMON_PRICE, tracker.getSkillingTotalGp());
		assertEquals(SALMON_PRICE, tracker.getSkillingKeptGp());
		assertEquals(SALMON_PRICE, tracker.getSessionSkillingTotalGp());
		assertEquals(SALMON_PRICE, tracker.getSessionSkillingKeptGp());
	}

	/** GE-buy a fish (inventory increase, NO catch signal, NO Fishing XP) → nothing credited. */
	@Test
	public void geBuy_noSignal_notCredited()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.applyInventoryDiff(inv(SALMON, 1), 3);   // bought — no action signal
		tracker.expireStalePending(5);

		assertEquals(0L, keptCount(Skill.FISHING, SALMON));
		assertEquals(0L, grossCount(Skill.FISHING, SALMON));
		assertEquals(0L, tracker.getSkillingTotalGp());
		assertEquals(0L, tracker.getSessionSkillingTotalGp());
	}

	/** GE-buy then drop the same fish → drop of a non-gathered (non-live) item is a no-op on the ledger. */
	@Test
	public void geBuyThenDrop_isNoOpOnLedger()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.applyInventoryDiff(inv(SALMON, 1), 3);   // bought
		tracker.expireStalePending(5);
		drop(SALMON, 1, 5);                              // drop the bought fish — not a live gather

		assertEquals(0L, keptCount(Skill.FISHING, SALMON));
		assertEquals(0L, grossCount(Skill.FISHING, SALMON));
		assertEquals(0L, tracker.getSkillingKeptGp());
		assertEquals(0L, tracker.getSkillingTotalGp());
	}

	/** Catch a fish, then drop it → kept decremented immediately; gross/lifetime unchanged. */
	@Test
	public void catchThenDrop_decrementsKeptOnly()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.FISHING, 1);
		tracker.applyInventoryDiff(inv(SALMON, 1), 1);
		drop(SALMON, 1, 1);
		tracker.applyInventoryDiff(inv(), 2);            // inventory now empty after the drop

		assertEquals(0L, keptCount(Skill.FISHING, SALMON));   // kept decremented
		assertEquals(1L, grossCount(Skill.FISHING, SALMON));  // gross/lifetime unchanged
		assertEquals(0L, tracker.getSkillingKeptGp());
		assertEquals(SALMON_PRICE, tracker.getSkillingTotalGp());
	}

	/**
	 * Catch, drop, then re-pick the dropped fish before it despawns → kept restored; gross unchanged.
	 * Recovery is driven by the ground item's despawn coinciding with an inventory gain (the pickup).
	 */
	@Test
	public void catchDropRepick_restoresKept()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.FISHING, 1);
		tracker.applyInventoryDiff(inv(SALMON, 1), 1);
		drop(SALMON, 1, 2);
		tracker.applyInventoryDiff(inv(), 2);

		tracker.applyInventoryDiff(inv(SALMON, 1), 5);   // pickup: inventory gain, NO catch signal
		despawn(SALMON, 1, 5);                           // the ground item leaves (picked up)
		tracker.reconcileTick(5);                        // classify: gain present → restore

		assertEquals(1L, keptCount(Skill.FISHING, SALMON));   // restored
		assertEquals(1L, grossCount(Skill.FISHING, SALMON));  // still 1 — not recounted as a gather
	}

	/**
	 * Drop a caught fish, let it time out (despawn with no coincident pickup) → the kept loss is final.
	 * A later bank/GE withdrawal of the same id must NOT restore it.
	 */
	@Test
	public void catchDrop_despawn_isFinal_andLaterWithdrawDoesNotRestore()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.FISHING, 1);
		tracker.applyInventoryDiff(inv(SALMON, 1), 1);
		drop(SALMON, 1, 2);
		tracker.applyInventoryDiff(inv(), 2);

		despawn(SALMON, 1, 300);                         // timed out — no inventory gain this tick
		tracker.reconcileTick(300);

		assertEquals(0L, keptCount(Skill.FISHING, SALMON));   // loss is final

		tracker.applyInventoryDiff(inv(SALMON, 1), 305);  // withdraw same id from bank later
		tracker.expireStalePending(307);
		tracker.reconcileTick(307);

		assertEquals(0L, keptCount(Skill.FISHING, SALMON));   // NOT restored — no tracked drop despawned
		assertEquals(1L, grossCount(Skill.FISHING, SALMON));
	}

	/**
	 * Banking finalizes: a gathered resource that leaves the inventory without a drop stays counted as
	 * kept. Withdrawing it again and dropping it must NOT move the kept count — it's no longer "live".
	 */
	@Test
	public void bankedGathered_thenWithdrawAndDrop_doesNotDecrementKept()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.FISHING, 1);
		tracker.applyInventoryDiff(inv(SALMON, 3), 1);    // caught 3 → kept 3, gross 3, live 3
		assertEquals(3L, keptCount(Skill.FISHING, SALMON));

		tracker.applyInventoryDiff(inv(), 2);             // bank all 3 (no drop) → finalized as kept
		assertEquals(3L, keptCount(Skill.FISHING, SALMON));
		assertEquals(3L, grossCount(Skill.FISHING, SALMON));

		tracker.applyInventoryDiff(inv(SALMON, 3), 5);    // withdraw the 3 back
		tracker.expireStalePending(7);
		drop(SALMON, 3, 8);                               // drop them — none are live
		tracker.applyInventoryDiff(inv(), 8);

		assertEquals(3L, keptCount(Skill.FISHING, SALMON));   // unchanged — banking was final
		assertEquals(3L, grossCount(Skill.FISHING, SALMON));
	}

	/** Mining mirror: withdraw banked-then-gathered ore and drop it → kept unchanged. */
	@Test
	public void bankedMinedOre_thenWithdrawAndDrop_doesNotDecrementKept()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.MINING, 1);
		tracker.applyInventoryDiff(inv(IRON, 5), 1);      // mined 5
		tracker.applyInventoryDiff(inv(), 2);             // bank them → finalized as kept

		tracker.applyInventoryDiff(inv(IRON, 5), 5);      // withdraw
		tracker.expireStalePending(7);
		drop(IRON, 5, 8);                                 // drop withdrawn ore
		tracker.applyInventoryDiff(inv(), 8);

		assertEquals(5L, keptCount(Skill.MINING, IRON));
		assertEquals(5L, grossCount(Skill.MINING, IRON));
	}

	/** Only live (still-held) gathered units may be deducted by a drop, even when mixed with bank stock. */
	@Test
	public void dropMixOfGatheredAndBankStock_onlyLiveDeducted()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.applyInventoryDiff(inv(SALMON, 5), 1);    // 5 from the bank, no signal → not gathered
		tracker.expireStalePending(3);
		tracker.recordGatherSignal(Skill.FISHING, 4);
		tracker.applyInventoryDiff(inv(SALMON, 6), 4);    // +1 caught → live 1, kept 1, gross 1

		drop(SALMON, 6, 5);                               // drop all 6 — only the 1 live unit counts
		tracker.applyInventoryDiff(inv(), 5);

		assertEquals(0L, keptCount(Skill.FISHING, SALMON));
		assertEquals(1L, grossCount(Skill.FISHING, SALMON));
	}

	/** Bank-withdraw a fish id matching a tracked fish (no catch signal) → not credited as gathered. */
	@Test
	public void bankWithdraw_noSignal_notCredited()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.applyInventoryDiff(inv(SALMON, 5), 4);   // withdrew 5 — no action signal
		tracker.expireStalePending(6);

		assertEquals(0L, keptCount(Skill.FISHING, SALMON));
		assertEquals(0L, grossCount(Skill.FISHING, SALMON));
	}

	/** Session counter resets on logout; lifetime persists. */
	@Test
	public void session_resetsOnLogout_lifetimePersists()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.FISHING, 1);
		tracker.applyInventoryDiff(inv(SALMON, 1), 1);
		Map<String, SkillTracker.SkillPersistence> saved = tracker.toPersistence();

		tracker.onProfileChanged();                      // logout clears in-memory state
		assertEquals(0L, tracker.getSkillingTotalGp());
		assertEquals(0L, tracker.getSessionSkillingTotalGp());

		tracker.loadFromState(saved);                    // next login restores lifetime only
		assertEquals(SALMON_PRICE, tracker.getSkillingTotalGp());   // lifetime persisted
		assertEquals(0L, tracker.getSessionSkillingTotalGp());      // session stays reset
	}

	/** XP provenance signal fires only on an actual increase, never on the login baseline read. */
	@Test
	public void xpGain_detectsOnlyIncreases()
	{
		assertFalse(tracker.isXpGain(Skill.FISHING, 1000));  // first observation = baseline
		assertTrue(tracker.isXpGain(Skill.FISHING, 1100));   // increase = a real action
		assertFalse(tracker.isXpGain(Skill.FISHING, 1100));  // unchanged = not a gather
	}

	/**
	 * Intra-tick event order isn't guaranteed: the inventory change may arrive before the
	 * catch/XP signal. The provenance gate must reconcile when the signal lands the same tick.
	 */
	@Test
	public void inventoryBeforeSignal_sameTick_stillCredits()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.applyInventoryDiff(inv(SALMON, 1), 1);   // fish first — no signal yet, buffered
		assertEquals(0L, keptCount(Skill.FISHING, SALMON));

		tracker.recordGatherSignal(Skill.FISHING, 1);    // signal lands same tick → flush buffer
		assertEquals(1L, keptCount(Skill.FISHING, SALMON));
		assertEquals(SALMON_PRICE, tracker.getSessionSkillingTotalGp());
	}

	/**
	 * A single action signal must credit only the inventory gain that action produced — never a
	 * second, coincident gain (a bank withdrawal / GE collect) that lands in the same tick.
	 *
	 * <p>Regression: the signal was a 1-tick "any gain counts" flag that was never consumed, so a
	 * withdrawal next to a real catch inflated the gathered (gross) and session totals.
	 */
	@Test
	public void signalCreditsOnlyTheActionGain_coincidentWithdrawIgnored()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.FISHING, 1);     // "You catch a salmon"
		tracker.applyInventoryDiff(inv(SALMON, 1), 1);     // the caught fish → credited
		tracker.applyInventoryDiff(inv(SALMON, 11), 1);    // +10 bank withdrawal, same tick, no new signal
		tracker.expireStalePending(3);

		assertEquals(1L, grossCount(Skill.FISHING, SALMON));   // only the caught fish, not 11
		assertEquals(1L, keptCount(Skill.FISHING, SALMON));
		assertEquals(SALMON_PRICE, tracker.getSkillingTotalGp());
		assertEquals(SALMON_PRICE, tracker.getSessionSkillingTotalGp());
	}

	/**
	 * The signal is consumed once it credits a gain, so a withdrawal landing on the NEXT tick — still
	 * inside the 1-tick tolerance window — is not mistaken for more gathered loot.
	 */
	@Test
	public void signalConsumed_withdrawWithinWindowNextTickIgnored()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.FISHING, 1);
		tracker.applyInventoryDiff(inv(SALMON, 1), 1);     // caught → credited, signal consumed
		tracker.applyInventoryDiff(inv(SALMON, 6), 2);     // +5 withdrawal at tick 2 (within window)
		tracker.expireStalePending(4);

		assertEquals(1L, grossCount(Skill.FISHING, SALMON));
		assertEquals(1L, keptCount(Skill.FISHING, SALMON));
	}

	/** Mining mirror of the leak: one XP-gain signal credits one mined ore, not a coincident withdrawal. */
	@Test
	public void miningSignalCreditsOnlyTheMinedOre()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.MINING, 1);       // Mining XP gain
		tracker.applyInventoryDiff(inv(IRON, 1), 1);        // mined ore → credited
		tracker.applyInventoryDiff(inv(IRON, 28), 1);       // withdrew 27 from bank, same tick
		tracker.expireStalePending(3);

		assertEquals(1L, grossCount(Skill.MINING, IRON));
		assertEquals(1L, keptCount(Skill.MINING, IRON));
	}

	/**
	 * Regression: {@code loadFromState} restoring a non-zero lifetime kept value must NOT feed
	 * {@link SessionTotals}. Today's bucket is independently restored from disk by
	 * {@code StateTracker}, so feeding the whole restored lifetime kept value on top (as the old
	 * unconditional {@code recomputeSkillingGp()} call did) double-counts it.
	 */
	@Test
	public void loadFromState_doesNotFeedSessionTotals()
	{
		SessionTotals totals = new SessionTotals();
		SkillTracker t = new SkillTracker(ResourceData.load(new Gson()), PRICES, totals);

		SkillTracker.SkillPersistence persistence = new SkillTracker.SkillPersistence();
		Map<Integer, Long> counts = new HashMap<>();
		counts.put(SALMON, 5L);
		persistence.resourceCounts = counts;
		persistence.grossResourceCounts = new HashMap<>(counts);

		Map<String, SkillTracker.SkillPersistence> persisted = new HashMap<>();
		persisted.put(Skill.FISHING.name(), persistence);

		t.loadFromState(persisted);

		assertEquals(5 * SALMON_PRICE, t.getSkillingKeptGp());        // lifetime restored
		assertEquals(0L, totals.todayRewards(LocalDate.now()));       // but NOT fed into today's bucket
	}

	/**
	 * {@code getSkillKeptGp} must reflect the per-skill kept-GP snapshot built on the client
	 * thread during {@code recomputeSkillingGp} — the same value {@code SkillingSection} now
	 * reads on the EDT instead of pricing items itself. An untracked skill (no SkillState yet)
	 * must return 0 rather than throwing or defaulting to a stale value.
	 */
	@Test
	public void getSkillKeptGp_matchesExpectedValueAndDefaultsToZeroForUntrackedSkill()
	{
		assertEquals(0L, tracker.getSkillKeptGp(Skill.FISHING));
		assertEquals(0L, tracker.getSkillKeptGp(Skill.MINING));

		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.FISHING, 1);
		tracker.applyInventoryDiff(inv(SALMON, 3), 1);   // 3 salmon caught, kept

		assertEquals(3 * SALMON_PRICE, tracker.getSkillKeptGp(Skill.FISHING));
		assertEquals(0L, tracker.getSkillKeptGp(Skill.MINING));   // untracked skill still 0

		tracker.recordGatherSignal(Skill.MINING, 2);
		tracker.applyInventoryDiff(inv(SALMON, 3, IRON, 2), 2);   // +2 iron ore mined (priced 0 in PRICES)

		assertEquals(3 * SALMON_PRICE, tracker.getSkillKeptGp(Skill.FISHING));
		assertEquals(0L, tracker.getSkillKeptGp(Skill.MINING));   // IRON has no price stub → 0 GP
	}

	/**
	 * Reset all-time skilling zeroes every lifetime total: after a real gather leaves a non-zero
	 * kept/per-skill value, {@code resetAllTimeSkilling()} must drive both back to 0.
	 */
	@Test
	public void resetAllTimeSkilling_zeroesLifetimeAndPerSkill()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.FISHING, 1);
		tracker.applyInventoryDiff(inv(SALMON, 3), 1);   // 3 salmon caught, kept

		assertTrue(tracker.getSkillingKeptGp() > 0);
		assertTrue(tracker.getSkillKeptGp(Skill.FISHING) > 0);

		tracker.resetAllTimeSkilling();

		assertEquals(0L, tracker.getSkillingKeptGp());
		assertEquals(0L, tracker.getSkillKeptGp(Skill.FISHING));
	}

	/**
	 * After a profile load re-baselines {@code prevSummaryGp}, a genuine subsequent gather must
	 * still accrue only its own delta into {@link SessionTotals} — not the whole restored total.
	 */
	@Test
	public void loadFromState_thenRealGather_accruesOnlyTheNewDelta()
	{
		SessionTotals totals = new SessionTotals();
		SkillTracker t = new SkillTracker(ResourceData.load(new Gson()), PRICES, totals);

		SkillTracker.SkillPersistence persistence = new SkillTracker.SkillPersistence();
		Map<Integer, Long> counts = new HashMap<>();
		counts.put(SALMON, 5L);
		persistence.resourceCounts = counts;
		persistence.grossResourceCounts = new HashMap<>(counts);

		Map<String, SkillTracker.SkillPersistence> persisted = new HashMap<>();
		persisted.put(Skill.FISHING.name(), persistence);

		t.loadFromState(persisted);
		assertEquals(0L, totals.todayRewards(LocalDate.now()));

		// A real gather this session: one more salmon caught.
		t.applyInventoryDiff(inv(SALMON, 5), 0);          // baseline matches restored inventory
		t.recordGatherSignal(Skill.FISHING, 1);
		t.applyInventoryDiff(inv(SALMON, 6), 1);          // +1 caught

		assertEquals(SALMON_PRICE, totals.todayRewards(LocalDate.now()));   // only the new delta
	}

	@Test
	public void setActiveObjectRecordsTheEngagedObject()
	{
		ResourceData data = ResourceData.load(new Gson());
		SkillTracker t = new SkillTracker(data, PRICES);
		t.setActiveObject(1511);            // a tree object id
		assertEquals(1511, t.getActiveObjectId());
		assertEquals(-1, t.getActiveFishingSpotId());   // single active node: fishing spot cleared
	}

	@Test
	public void setActiveObjectBumpsGenerationOnChange()
	{
		ResourceData data = ResourceData.load(new Gson());
		SkillTracker t = new SkillTracker(data, PRICES);
		long g0 = t.getGeneration();
		t.setActiveObject(1511);
		assertTrue(t.getGeneration() > g0);   // node changed -> refresh
	}

	@Test
	public void setActiveObjectSameIdDoesNotBumpGeneration()
	{
		ResourceData data = ResourceData.load(new Gson());
		SkillTracker t = new SkillTracker(data, PRICES);
		t.setActiveObject(1511);
		long g1 = t.getGeneration();
		t.setActiveObject(1511);              // same node re-clicked
		assertEquals(g1, t.getGeneration());  // no churn
	}

	@Test
	public void profileChangeClearsActiveObject()
	{
		ResourceData data = ResourceData.load(new Gson());
		SkillTracker t = new SkillTracker(data, PRICES);
		t.setActiveObject(1511);
		t.onProfileChanged();               // logout / profile switch
		assertEquals(-1, t.getActiveObjectId());
	}

	@Test
	public void birdNestCreditedWithCoincidentWoodcuttingSignal()
	{
		// Baseline inventory established first (applyInventoryDiff ignores the very first snapshot).
		tracker.applyInventoryDiff(inv(), 100);
		// A woodcutting action signal on tick 101, then the nest lands the same tick.
		tracker.recordGatherSignal(Skill.WOODCUTTING, 101);
		tracker.applyInventoryDiff(inv(BIRD_NEST, 1), 101);

		SkillTracker.SkillState wc = tracker.getSkillState(Skill.WOODCUTTING);
		assertNotNull(wc);
		assertEquals(1L, wc.getResourceCount(BIRD_NEST));
	}

	@Test
	public void birdNestNotCreditedWithoutSignal()
	{
		tracker.applyInventoryDiff(inv(), 200);
		// Prove recognition is live: a nest WITH a coincident signal IS credited.
		tracker.recordGatherSignal(Skill.WOODCUTTING, 201);
		tracker.applyInventoryDiff(inv(BIRD_NEST, 1), 201);
		SkillTracker.SkillState wc = tracker.getSkillState(Skill.WOODCUTTING);
		assertNotNull(wc);
		assertEquals(1L, wc.getResourceCount(BIRD_NEST));

		// A second nest with NO signal (a bank withdrawal / GE collect of a nest) must NOT be
		// credited even though the item IS recognized — the provenance gate blocks it. If the gate
		// were removed, this unsignaled gain would credit and the count would become 2.
		tracker.applyInventoryDiff(inv(BIRD_NEST, 2), 202);
		tracker.expireStalePending(204);
		assertEquals(1L, wc.getResourceCount(BIRD_NEST));
	}

	@Test
	public void setActiveFishingMethodRecordsAndBumpsOnChange()
	{
		ResourceData data = ResourceData.load(new Gson());
		SkillTracker t = new SkillTracker(data, PRICES);
		long g0 = t.getGeneration();
		t.setActiveFishingMethod("harpoon");
		assertEquals("harpoon", t.getActiveFishingMethod());
		assertTrue(t.getGeneration() > g0);           // changed -> refresh
		long g1 = t.getGeneration();
		t.setActiveFishingMethod("harpoon");           // same method re-clicked
		assertEquals(g1, t.getGeneration());          // no churn
	}

	@Test
	public void setActiveObjectClearsFishingMethod()
	{
		ResourceData data = ResourceData.load(new Gson());
		SkillTracker t = new SkillTracker(data, PRICES);
		t.setActiveFishingMethod("harpoon");
		t.setActiveObject(1511);                       // switch to a tree/rock
		assertNull(t.getActiveFishingMethod());
	}

	@Test
	public void profileChangeClearsFishingMethod()
	{
		ResourceData data = ResourceData.load(new Gson());
		SkillTracker t = new SkillTracker(data, PRICES);
		t.setActiveFishingMethod("harpoon");
		t.onProfileChanged();
		assertNull(t.getActiveFishingMethod());
	}

	@Test
	public void thievingFailRate_nullUntilData_thenReflectsFailsOverAttempts()
	{
		assertNull(tracker.getThievingFailRate());
		tracker.onThievingChat("You fail to pick the man's pocket.");
		tracker.onThievingChat("You pick the man's pocket."); // a success line
		Double rate = tracker.getThievingFailRate();
		assertNotNull(rate);
		assertEquals(0.5, rate, 1e-9);
	}

	private static final int OAK_LEAVES = 6022;
	private static final int WILLOW_LEAVES = 6024;

	private SkillTracker.RewardSample sample(Skill skill, int itemId)
	{
		SkillTracker.SkillState st = tracker.getSkillState(skill);
		return st == null ? null : st.getRewardSample(itemId);
	}

	private long sampleActions(Skill skill, int itemId)
	{
		SkillTracker.RewardSample s = sample(skill, itemId);
		return s == null ? 0L : s.getActions();
	}

	/** Skill-wide reward (bird nest, empty objectIds): every WC action counts, node or not. */
	@Test
	public void skillWideRewardSamplesEveryskillAction()
	{
		tracker.recordActionSample(Skill.WOODCUTTING);          // no node engaged
		tracker.recordActionSample(Skill.WOODCUTTING);
		assertEquals(2L, sampleActions(Skill.WOODCUTTING, BIRD_NEST));
	}

	/** Per-node reward (oak leaves): only actions at one of ITS nodes count. */
	@Test
	public void perNodeRewardSamplesOnlyItsOwnNodeActions()
	{
		ResourceData data = ResourceData.load(new Gson());
		ResourceData.RewardEntry oakLeaves = data.rewardForItemId(OAK_LEAVES);
		ResourceData.RewardEntry willowLeaves = data.rewardForItemId(WILLOW_LEAVES);
		assertNotNull(oakLeaves);
		assertNotNull(willowLeaves);
		int oakNode = oakLeaves.getObjectIds().get(0);
		int willowNode = willowLeaves.getObjectIds().get(0);
		assertFalse(oakLeaves.getObjectIds().contains(willowNode)); // fixture sanity

		tracker.setActiveObject(oakNode);
		tracker.recordActionSample(Skill.WOODCUTTING);
		tracker.setActiveObject(willowNode);
		tracker.recordActionSample(Skill.WOODCUTTING);

		assertEquals(1L, sampleActions(Skill.WOODCUTTING, OAK_LEAVES));
		assertEquals(1L, sampleActions(Skill.WOODCUTTING, WILLOW_LEAVES));
		assertEquals(2L, sampleActions(Skill.WOODCUTTING, BIRD_NEST)); // skill-wide saw both
	}

	/** Another skill's action never touches this skill's samples. */
	@Test
	public void otherSkillActionDoesNotAccrueSamples()
	{
		tracker.recordActionSample(Skill.MINING);
		assertEquals(0L, sampleActions(Skill.WOODCUTTING, BIRD_NEST));
	}

	/** A gated reward credit increments drops and resets the dry streak. */
	@Test
	public void rewardCreditIncrementsDropsAndResetsDryStreak()
	{
		tracker.recordActionSample(Skill.WOODCUTTING);
		tracker.recordActionSample(Skill.WOODCUTTING);
		tracker.recordActionSample(Skill.WOODCUTTING);

		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.WOODCUTTING, 1);
		tracker.applyInventoryDiff(inv(BIRD_NEST, 1), 1);   // nest credits through the gate

		SkillTracker.RewardSample s = sample(Skill.WOODCUTTING, BIRD_NEST);
		assertNotNull(s);
		assertEquals(1L, s.getDrops());
		assertEquals(3L, s.getActions());
		assertEquals(3L, s.getActionsAtLastDrop());        // dry streak = actions - this = 0
	}

	/** A primary (non-reward) credit never creates a sample. */
	@Test
	public void primaryCreditDoesNotTouchSamples()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.FISHING, 1);
		tracker.applyInventoryDiff(inv(SALMON, 1), 1);
		SkillTracker.SkillState st = tracker.getSkillState(Skill.FISHING);
		assertNotNull(st);
		assertNull(st.getRewardSample(SALMON));
	}

	/** Samples survive a persistence round-trip. */
	@Test
	public void rewardSamplesPersistRoundTrip()
	{
		tracker.recordActionSample(Skill.WOODCUTTING);
		tracker.recordActionSample(Skill.WOODCUTTING);
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.WOODCUTTING, 1);
		tracker.applyInventoryDiff(inv(BIRD_NEST, 1), 1);

		Map<String, SkillTracker.SkillPersistence> persisted = tracker.toPersistence();
		SkillTracker restored = new SkillTracker(ResourceData.load(new Gson()), PRICES);
		restored.loadFromState(persisted);

		SkillTracker.RewardSample s =
			restored.getSkillState(Skill.WOODCUTTING).getRewardSample(BIRD_NEST);
		assertNotNull(s);
		assertEquals(2L, s.getActions());
		assertEquals(1L, s.getDrops());
		assertEquals(2L, s.getActionsAtLastDrop());
	}

	/** Old saves without rewardSamples load cleanly with zeroed samples (migration). */
	@Test
	public void loadStateWithoutRewardSamplesStartsAtZero()
	{
		SkillTracker.SkillPersistence p = new SkillTracker.SkillPersistence();
		p.resourceCounts = new HashMap<>();
		p.resourceCounts.put(BIRD_NEST, 4L);               // pre-existing lifetime count
		Map<String, SkillTracker.SkillPersistence> persisted = new HashMap<>();
		persisted.put("woodcutting", p);

		tracker.loadFromState(persisted);

		SkillTracker.SkillState st = tracker.getSkillState(Skill.WOODCUTTING);
		assertEquals(4L, st.getResourceCount(BIRD_NEST));  // display counts untouched
		assertNull(st.getRewardSample(BIRD_NEST));         // sample counting starts now
	}

	/** Reset all-time leaves no samples behind. */
	@Test
	public void resetAllTimeClearsRewardSamples()
	{
		tracker.recordActionSample(Skill.WOODCUTTING);
		tracker.resetAllTimeSkilling();
		assertNull(tracker.getSkillState(Skill.WOODCUTTING));
	}

	private static final int LOCKPICK = 1523; // arbitrary item with no ResourceData mapping

	/** Matcher truth table (spec §2): pocket token required; dodgy save = fail; stall = neither. */
	@Test
	public void thievingChat_matcherTruthTable()
	{
		tracker.thievingChat("You pick the man's pocket.", 1);          // success
		tracker.thievingChat("You fail to pick the man's pocket.", 2);  // fail
		tracker.thievingChat("Your dodgy necklace protects you. It has 9 charges left.", 3); // fail
		tracker.thievingChat("You pick a herb.", 4);                    // neither
		tracker.thievingChat("You pick the lock.", 5);                  // neither
		tracker.thievingChat("You steal a cake.", 6);                   // stall: neither
		assertEquals(2.0 / 3.0, tracker.getThievingFailRate(), 1e-9);
	}

	/** A fresh THIEVING signal credits an arbitrary (non-ResourceData) item. */
	@Test
	public void thievingSignal_creditsArbitraryLoot()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.thievingChat("You pick the man's pocket.", 1);
		tracker.applyInventoryDiff(inv(LOCKPICK, 1), 1);
		assertEquals(1L, keptCount(Skill.THIEVING, LOCKPICK));
	}

	/** The THIEVING signal outranks the item's gathering mapping (fish-stall salmon is stolen). */
	@Test
	public void thievingSignal_winsOverResourceMapping()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.thievingChat("You steal a fish.", 1);
		tracker.applyInventoryDiff(inv(SALMON, 1), 1);
		assertEquals(1L, keptCount(Skill.THIEVING, SALMON));
		assertEquals(0L, keptCount(Skill.FISHING, SALMON));
	}

	/** No signal → arbitrary gains stay uncredited (GE/bank posture unchanged). */
	@Test
	public void noThievingSignal_arbitraryGainNotCredited()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.applyInventoryDiff(inv(LOCKPICK, 1), 5);
		assertEquals(0L, keptCount(Skill.THIEVING, LOCKPICK));
	}

	/** Fails mark the player as actively thieving but gate no loot (spec §2). */
	@Test
	public void thievingFail_refreshesActivityButGatesNothing()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.thievingChat("You fail to pick the man's pocket.", 1);
		tracker.applyInventoryDiff(inv(LOCKPICK, 1), 1);
		assertEquals(0L, keptCount(Skill.THIEVING, LOCKPICK));
		assertEquals(Skill.THIEVING, tracker.getActiveSkill());
	}

	/** Inventory-before-signal ordering: the landing THIEVING signal claims the buffered gain. */
	@Test
	public void bufferedGainFlushedByLateThievingSignal()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.applyInventoryDiff(inv(LOCKPICK, 1), 3);
		tracker.thievingChat("You pick the man's pocket.", 3);
		assertEquals(1L, keptCount(Skill.THIEVING, LOCKPICK));
	}

	/** Regression: the existing recognition path is untouched for other skills. */
	@Test
	public void fishingRecognitionUnchangedByThievingBranch()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.FISHING, 1);
		tracker.applyInventoryDiff(inv(SALMON, 1), 1);
		assertEquals(1L, keptCount(Skill.FISHING, SALMON));
		assertEquals(0L, keptCount(Skill.THIEVING, SALMON));
	}
}
