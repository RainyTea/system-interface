package com.systeminterface.modules.skills;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntToLongFunction;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
	private static final int RAW_LOBSTER = 377;
	private static final int LEAPING_TROUT = 11328;
	private static final int LOGS = 1511;
	private static final int SEED_NEST = 22798;
	private static final int IRON = 440;    // iron ore, Skill.MINING
	private static final int SILVER = 442;
	private static final int SILVER_BAR = 2355;
	private static final int CHINCHOMPA = 10033;
	private static final int BLACK_WARLOCK = 10014;
	private static final int BLACK_BUTTERFLY_WING = 29230;
	private static final int POTATO = 1942;
	private static final int COIN_POUCH = 22521;
	private static final int WEALTHY_CITIZEN_POUCH = 28822;
	private static final int HOUSE_KEYS = 29325;
	private static final int EASY_CLUE = 2677;
	private static final int SILK = 950;
	private static final int SPICE = 2007;
	private static final int UNCUT_SAPPHIRE = 1623;
	private static final long SALMON_PRICE = 100L;

	private static final IntToLongFunction PRICES = id ->
	{
		switch (id)
		{
			case SALMON: return SALMON_PRICE;
			case RAW_LOBSTER: return 250L;
			case LEAPING_TROUT: return 2L;
			case LOGS: return 20L;
			case SEED_NEST: return 1000L;
			case SILVER: return 25L;
			case SILVER_BAR: return 100L;
			case 317: return 10L;  // raw shrimps
			case CHINCHOMPA: return 1200L;
			case BLACK_WARLOCK: return 250L;
			case BLACK_BUTTERFLY_WING: return 50L;
			case POTATO: return 5L;
			case COIN_POUCH: return 1L;
			case WEALTHY_CITIZEN_POUCH: return 1L;
			case HOUSE_KEYS: return 500L;
			case EASY_CLUE: return 0L;
			case SILK: return 30L;
			case SPICE: return 100L;
			case UNCUT_SAPPHIRE: return 250L;
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

	@Test
	public void requirementChecksUseHeldItemCache()
	{
		ResourceData.ResourceEntry oakLogs = tracker.getResourceData().forObjectName("Oak tree").get(0);
		List<ResourceData.ResourceEntry> rewards =
			tracker.getResourceData().statisticalRewardsForResource(oakLogs);
		ResourceData.ResourceEntry conditionalReward = null;
		for (ResourceData.ResourceEntry reward : rewards)
		{
			if (!reward.getRequiredItemsAny().isEmpty())
			{
				conditionalReward = reward;
				break;
			}
		}

		assertTrue("Oak should have a conditional forestry reward", conditionalReward != null);
		assertFalse(tracker.hasAnyItem(conditionalReward.getRequiredItemsAny()));

		tracker.setHeldItemIdsForTest(conditionalReward.getRequiredItemsAny().get(0));

		assertTrue(tracker.hasAnyItem(conditionalReward.getRequiredItemsAny()));
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

	private long sourceKeptCount(Skill skill, String source, int itemId)
	{
		SkillTracker.SkillState st = tracker.getSkillState(skill);
		if (st == null || st.getSourceStates().get(source) == null)
		{
			return 0L;
		}
		return st.getSourceStates().get(source).getResourceCounts().getOrDefault(itemId, 0L);
	}

	private SkillTracker.SourceState sourceState(Skill skill, String source)
	{
		SkillTracker.SkillState st = tracker.getSkillState(skill);
		return st == null ? null : st.getSourceStates().get(source);
	}

	/** Resource-object clicks should also work in the null-client test constructor. */
	@Test
	public void resourceAction_marksActivityWithoutLiveClient()
	{
		tracker.onResourceAction(Skill.WOODCUTTING);

		assertEquals(Skill.WOODCUTTING, tracker.getActiveSkill());
		assertTrue(tracker.getMillisSinceActivity() != Long.MAX_VALUE);
	}

	/** Catch a fish (catch signal + fish in inventory) -> gathered +1, session +1, value credited. */
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

	@Test
	public void fishingChatThenXpSameTick_countsOneActionAndKeepsXpAndOutput()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.FISHING, 1);
		tracker.recordTrackedXpSignal(Skill.FISHING, 1, 70);
		tracker.applyInventoryDiff(inv(SALMON, 1), 1);

		SkillTracker.SkillState state = tracker.getSkillState(Skill.FISHING);
		assertTrue(state != null);
		assertEquals(1L, state.getSuccessfulActions());
		assertEquals(70L, state.getXpGained());
		assertEquals(1L, grossCount(Skill.FISHING, SALMON));
		assertEquals(1L, keptCount(Skill.FISHING, SALMON));
	}

	@Test
	public void fishingXpThenChatSameTick_countsOneActionAndKeepsXpAndOutput()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordTrackedXpSignal(Skill.FISHING, 1, 70);
		tracker.recordGatherSignal(Skill.FISHING, 1);
		tracker.applyInventoryDiff(inv(SALMON, 1), 1);

		SkillTracker.SkillState state = tracker.getSkillState(Skill.FISHING);
		assertTrue(state != null);
		assertEquals(1L, state.getSuccessfulActions());
		assertEquals(70L, state.getXpGained());
		assertEquals(1L, grossCount(Skill.FISHING, SALMON));
		assertEquals(1L, keptCount(Skill.FISHING, SALMON));
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

	@Test
	public void resetSessionProgress_clearsSessionOnly()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordGatherSignal(Skill.FISHING, 1);
		tracker.applyInventoryDiff(inv(SALMON, 1), 1);

		tracker.resetSessionProgress();

		assertEquals(SALMON_PRICE, tracker.getSkillingTotalGp());
		assertEquals(SALMON_PRICE, tracker.getSkillingKeptGp());
		assertEquals(0L, tracker.getSessionSkillingTotalGp());
		assertEquals(0L, tracker.getSessionSkillingKeptGp());
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

	@Test
	public void fishingXpSignal_creditsBarbarianFish()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordTrackedXpSignal(Skill.FISHING, 1);
		tracker.applyInventoryDiff(inv(LEAPING_TROUT, 1), 1);

		assertEquals(1L, grossCount(Skill.FISHING, LEAPING_TROUT));
		assertEquals(1L, keptCount(Skill.FISHING, LEAPING_TROUT));
	}

	@Test
	public void woodcuttingXpSignal_creditsLogAndBirdNestSecondary()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordTrackedXpSignal(Skill.WOODCUTTING, 1);
		tracker.applyInventoryDiff(inv(LOGS, 1, SEED_NEST, 1), 1);

		assertEquals(1L, grossCount(Skill.WOODCUTTING, LOGS));
		assertEquals(1L, grossCount(Skill.WOODCUTTING, SEED_NEST));
		assertEquals(1020L, tracker.getSessionSkillingTotalGp());
		assertEquals(1020L, tracker.getSessionSkillingKeptGp());
	}

	@Test
	public void hunterXpSignal_creditsHunterReward()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordTrackedXpSignal(Skill.HUNTER, 1);
		tracker.applyInventoryDiff(inv(CHINCHOMPA, 1), 1);

		assertEquals(1L, grossCount(Skill.HUNTER, CHINCHOMPA));
		assertEquals(1L, keptCount(Skill.HUNTER, CHINCHOMPA));
		assertEquals(1200L, tracker.getSessionSkillingTotalGp());
	}

	@Test
	public void hunterXpSignal_creditsBlackWarlockJar()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordTrackedXpSignal(Skill.HUNTER, 1);
		tracker.applyInventoryDiff(inv(BLACK_WARLOCK, 1), 1);

		assertEquals(1L, grossCount(Skill.HUNTER, BLACK_WARLOCK));
		assertEquals(1L, keptCount(Skill.HUNTER, BLACK_WARLOCK));
	}

	@Test
	public void hunterXpSignal_creditsBarehandedRumourWing()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordTrackedXpSignal(Skill.HUNTER, 1);
		tracker.applyInventoryDiff(inv(BLACK_BUTTERFLY_WING, 1), 1);

		assertEquals(1L, grossCount(Skill.HUNTER, BLACK_BUTTERFLY_WING));
		assertEquals(1L, keptCount(Skill.HUNTER, BLACK_BUTTERFLY_WING));
	}

	@Test
	public void farmingXpSignal_creditsHarvestedCrop()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.applyInventoryDiff(inv(POTATO, 1), 1);
		tracker.recordTrackedXpSignal(Skill.FARMING, 1);

		assertEquals(1L, grossCount(Skill.FARMING, POTATO));
		assertEquals(1L, keptCount(Skill.FARMING, POTATO));
	}

	@Test
	public void thievingXpSignal_creditsCoinPouch()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);
		tracker.applyInventoryDiff(inv(COIN_POUCH, 1), 1);

		assertEquals(1L, grossCount(Skill.THIEVING, COIN_POUCH));
		assertEquals(1L, keptCount(Skill.THIEVING, COIN_POUCH));
	}

	@Test
	public void thievingXpSignal_creditsWealthyCitizenCoinPouch()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);
		tracker.applyInventoryDiff(inv(WEALTHY_CITIZEN_POUCH, 1), 1);

		assertEquals(1L, grossCount(Skill.THIEVING, WEALTHY_CITIZEN_POUCH));
		assertEquals(1L, keptCount(Skill.THIEVING, WEALTHY_CITIZEN_POUCH));
	}

	@Test
	public void thievingPickpocketSourceAction_tracksGenericCoinPouchOutput()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.onSourceAction(Skill.THIEVING, "Warrior", "Pickpocket");
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1, 26);
		tracker.applyInventoryDiff(inv(COIN_POUCH, 1), 1);

		SkillTracker.SourceState source = sourceState(Skill.THIEVING, "Warrior");
		assertTrue(source != null);
		assertEquals(1L, keptCount(Skill.THIEVING, COIN_POUCH));
		assertEquals(1L, sourceKeptCount(Skill.THIEVING, "Warrior", COIN_POUCH));
		assertEquals(1L, source.getSuccessfulActions());
		assertEquals(26L, source.getXpGained());
		assertEquals("Pickpocket", source.getActivityType());
		assertEquals("Pickpocket", source.getSourceAction());
	}

	@Test
	public void aggregateTrackedXpSignal_recordsCoreActiveStats()
	{
		long before = tracker.getGeneration();

		tracker.recordTrackedXpSignal(Skill.WOODCUTTING, 1, 175);

		SkillTracker.SkillState state = tracker.getSkillState(Skill.WOODCUTTING);
		assertTrue(state != null);
		assertEquals(1L, state.getSuccessfulActions());
		assertEquals(175L, state.getXpGained());
		assertTrue(tracker.getGeneration() > before);
	}

	@Test
	public void sourceTrackedXpSignal_recordsAggregateAndSourceCoreStats()
	{
		tracker.onSourceAction(Skill.THIEVING, "Warrior", "Pickpocket");

		tracker.recordTrackedXpSignal(Skill.THIEVING, 1, 26);

		SkillTracker.SkillState state = tracker.getSkillState(Skill.THIEVING);
		SkillTracker.SourceState source = sourceState(Skill.THIEVING, "Warrior");
		assertTrue(state != null);
		assertTrue(source != null);
		assertEquals(1L, state.getSuccessfulActions());
		assertEquals(26L, state.getXpGained());
		assertEquals(1L, source.getSuccessfulActions());
		assertEquals(26L, source.getXpGained());
	}

	@Test
	public void thievingPickpocketFailureMessage_countsConservativeFailure()
	{
		tracker.onSourceAction(Skill.THIEVING, "Warrior", "Pickpocket");
		tracker.recordThievingFailure("You fail to pick the warrior's pocket.", 1);

		SkillTracker.SourceState source = sourceState(Skill.THIEVING, "Warrior");
		assertTrue(source != null);
		assertEquals(0L, source.getSuccessfulActions());
		assertEquals(1L, source.getFailedActions());
		assertEquals(1L, source.getAttemptedActions());
	}

	@Test
	public void thievingStunMessageAlone_doesNotCountFailure()
	{
		tracker.onSourceAction(Skill.THIEVING, "Warrior", "Pickpocket");
		tracker.recordThievingFailure("You've been stunned!", 1);

		assertTrue(sourceState(Skill.THIEVING, "Warrior") == null);
	}

	@Test
	public void rogueOutfitChanceMapsPiecesToActivationChance()
	{
		assertEquals(0, SkillTracker.rogueOutfitActivationChancePercent(0));
		assertEquals(15, SkillTracker.rogueOutfitActivationChancePercent(1));
		assertEquals(30, SkillTracker.rogueOutfitActivationChancePercent(2));
		assertEquals(45, SkillTracker.rogueOutfitActivationChancePercent(3));
		assertEquals(60, SkillTracker.rogueOutfitActivationChancePercent(4));
		assertEquals(100, SkillTracker.rogueOutfitActivationChancePercent(5));
		assertEquals(100, SkillTracker.rogueOutfitActivationChancePercent(6));
	}

	@Test
	public void rogueOutfitDoesNotSynthesizeSecondPouch()
	{
		tracker.setRogueOutfitPiecesForTest(5);
		tracker.applyInventoryDiff(inv(), 0);
		tracker.onSourceAction(Skill.THIEVING, "Warrior", "Pickpocket");
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);
		tracker.applyInventoryDiff(inv(COIN_POUCH, 1), 1);

		assertEquals(1L, sourceKeptCount(Skill.THIEVING, "Warrior", COIN_POUCH));
		assertEquals(5, tracker.getRogueOutfitPieces());
	}

	@Test
	public void thievingPickpocketSourceAction_countsOneSuccessWithMultiplePouchOutput()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.onSourceAction(Skill.THIEVING, "Warrior", "Pickpocket");
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);
		tracker.applyInventoryDiff(inv(COIN_POUCH, 2), 1);

		SkillTracker.SourceState source = sourceState(Skill.THIEVING, "Warrior");
		assertTrue(source != null);
		assertEquals(2L, sourceKeptCount(Skill.THIEVING, "Warrior", COIN_POUCH));
		assertEquals(1L, source.getSuccessfulActions());
	}

	@Test
	public void thievingStallSourceAction_tracksOutputAndSourceType()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.onSourceAction(Skill.THIEVING, "Silk stall", "Steal-from");
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);
		tracker.applyInventoryDiff(inv(SILK, 1), 1);

		SkillTracker.SourceState source = sourceState(Skill.THIEVING, "Silk stall");
		assertTrue(source != null);
		assertEquals(1L, keptCount(Skill.THIEVING, SILK));
		assertEquals(1L, sourceKeptCount(Skill.THIEVING, "Silk stall", SILK));
		assertEquals(1L, source.getSuccessfulActions());
		assertEquals("Stall", source.getActivityType());
		assertEquals("Steal-from", source.getSourceAction());
	}

	@Test
	public void thievingSpiceStall_tracksSpiceOutput()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.onSourceAction(Skill.THIEVING, "Spice stall", "Steal-from");
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);
		tracker.applyInventoryDiff(inv(SPICE, 1), 1);

		SkillTracker.SourceState source = sourceState(Skill.THIEVING, "Spice stall");
		assertTrue(source != null);
		assertEquals(1L, keptCount(Skill.THIEVING, SPICE));
		assertEquals(1L, sourceKeptCount(Skill.THIEVING, "Spice stall", SPICE));
		assertEquals(1L, source.getSuccessfulActions());
		assertEquals("Stall", source.getActivityType());
	}

	@Test
	public void thievingGemStall_stillTracksGemOutput()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.onSourceAction(Skill.THIEVING, "Gem stall", "Steal-from");
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);
		tracker.applyInventoryDiff(inv(UNCUT_SAPPHIRE, 1), 1);

		assertEquals(1L, keptCount(Skill.THIEVING, UNCUT_SAPPHIRE));
		assertEquals(1L, sourceKeptCount(Skill.THIEVING, "Gem stall", UNCUT_SAPPHIRE));
	}

	@Test
	public void thievingSourceActionWithoutXp_doesNotCreditInventoryGain()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.onSourceAction(Skill.THIEVING, "Warrior", "Pickpocket");
		tracker.applyInventoryDiff(inv(COIN_POUCH, 1), 1);
		tracker.expireStalePending(3);

		assertEquals(0L, keptCount(Skill.THIEVING, COIN_POUCH));
		assertTrue(sourceState(Skill.THIEVING, "Warrior") == null);
	}

	@Test
	public void genericPickpocket_doesNotClaimSourceSpecificReward()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.onSourceAction(Skill.THIEVING, "Warrior", "Pickpocket");
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);
		tracker.applyInventoryDiff(inv(HOUSE_KEYS, 1), 1);
		tracker.expireStalePending(3);

		SkillTracker.SourceState source = sourceState(Skill.THIEVING, "Warrior");
		assertTrue(source != null);
		assertEquals(0L, keptCount(Skill.THIEVING, HOUSE_KEYS));
		assertEquals(0L, sourceKeptCount(Skill.THIEVING, "Warrior", HOUSE_KEYS));
		assertEquals(1L, source.getSuccessfulActions());
		assertTrue(source.getResourceCounts().isEmpty());
	}

	@Test
	public void thievingSourceAction_groupsLootUnderSource()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.onSourceAction(Skill.THIEVING, "Wealthy citizen");
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);
		tracker.applyInventoryDiff(inv(HOUSE_KEYS, 1), 1);

		assertEquals(1L, keptCount(Skill.THIEVING, HOUSE_KEYS));
		assertEquals(1L, sourceKeptCount(Skill.THIEVING, "Wealthy citizen", HOUSE_KEYS));
	}

	@Test
	public void thievingStallSourceAction_creditsSharedFishUnderStallNotFishing()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.onSourceAction(Skill.THIEVING, "Fish stall");
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);
		tracker.applyInventoryDiff(inv(RAW_LOBSTER, 1), 1);

		assertEquals(1L, keptCount(Skill.THIEVING, RAW_LOBSTER));
		assertEquals(1L, sourceKeptCount(Skill.THIEVING, "Fish stall", RAW_LOBSTER));
		assertEquals(0L, grossCount(Skill.FISHING, RAW_LOBSTER));
	}

	@Test
	public void thievingStallSourceAction_creditsSharedOreUnderStallNotMining()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.onSourceAction(Skill.THIEVING, "Silver stall");
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);
		tracker.applyInventoryDiff(inv(SILVER, 1), 1);

		assertEquals(1L, keptCount(Skill.THIEVING, SILVER));
		assertEquals(1L, sourceKeptCount(Skill.THIEVING, "Silver stall", SILVER));
		assertEquals(0L, grossCount(Skill.MINING, SILVER));
	}

	@Test
	public void sourceSpecificSignal_doesNotClaimAnotherSourceReward()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.onSourceAction(Skill.THIEVING, "Fish stall");
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);
		tracker.applyInventoryDiff(inv(SILVER_BAR, 1), 1);
		tracker.expireStalePending(3);

		assertEquals(0L, keptCount(Skill.THIEVING, SILVER_BAR));
		assertEquals(0L, sourceKeptCount(Skill.THIEVING, "Fish stall", SILVER_BAR));
	}

	@Test
	public void resetSkillSource_removesSourceAndAggregateCounts()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.onSourceAction(Skill.THIEVING, "Wealthy citizen");
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);
		tracker.applyInventoryDiff(inv(HOUSE_KEYS, 1), 1);

		tracker.resetSkillSource(Skill.THIEVING, "Wealthy citizen");

		assertEquals(0L, keptCount(Skill.THIEVING, HOUSE_KEYS));
		assertEquals(0L, grossCount(Skill.THIEVING, HOUSE_KEYS));
		assertEquals(0L, sourceKeptCount(Skill.THIEVING, "Wealthy citizen", HOUSE_KEYS));
	}

	@Test
	public void finalizedRemoval_decrementsKeptButNotGross()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);
		tracker.applyInventoryDiff(inv(COIN_POUCH, 1), 1);

		tracker.onItemRemovedFinalized(COIN_POUCH, 1);
		tracker.applyInventoryDiff(inv(), 2);

		assertEquals(0L, keptCount(Skill.THIEVING, COIN_POUCH));
		assertEquals(1L, grossCount(Skill.THIEVING, COIN_POUCH));
	}

	@Test
	public void mismatchedXpSignal_doesNotCreditOtherSkillReward()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);
		tracker.applyInventoryDiff(inv(CHINCHOMPA, 1), 1);
		tracker.expireStalePending(3);

		assertEquals(0L, grossCount(Skill.HUNTER, CHINCHOMPA));
		assertEquals(0L, grossCount(Skill.THIEVING, CHINCHOMPA));
	}

	@Test
	public void sharedItemId_usesTheMatchingXpSignal()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);
		tracker.applyInventoryDiff(inv(SILVER, 1), 1);

		assertEquals(1L, grossCount(Skill.THIEVING, SILVER));
		assertEquals(0L, grossCount(Skill.MINING, SILVER));
	}

	@Test
	public void sharedItemId_inventoryBeforeSignal_usesTheMatchingXpSignal()
	{
		tracker.applyInventoryDiff(inv(), 0);
		tracker.applyInventoryDiff(inv(SILVER, 1), 1);
		tracker.recordTrackedXpSignal(Skill.THIEVING, 1);

		assertEquals(1L, grossCount(Skill.THIEVING, SILVER));
		assertEquals(0L, grossCount(Skill.MINING, SILVER));
	}
}
