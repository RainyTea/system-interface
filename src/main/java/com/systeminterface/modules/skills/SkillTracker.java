package com.systeminterface.modules.skills;

import com.systeminterface.services.state.SessionTotals;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntToLongFunction;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.xptracker.XpTrackerService;

/**
 * Tracks skilling activity and owns the <b>skilling provenance ledger</b>: XP/session stats plus
 * signal-gated gathered-resource accounting (kept vs gross, drop → ground-drop → re-pickup restore,
 * and bank/use finalization).
 *
 * <p>This is deliberately a <em>separate</em> ledger from {@link
 * com.systeminterface.services.acquisition.AcquisitionLedger} (which backs combat). Both enforce the
 * same provenance invariant — a gain only counts with credible source evidence — but the evidence
 * differs: combat matches pre-registered expected loot lots, while skilling gates on a coincident
 * {@code (Skill, tick)} action signal (catch chat / tracked-skill XP) with pre-signal buffering.
 * Merging the two into one generic ledger was rejected: it recreates bloat without removing real
 * duplication. The provenance methods here ({@link #applyInventoryDiff}, {@link #recordGatherSignal},
 * {@link #onItemDropped}, {@link #onGroundResourceDespawned}, {@code reconcileTick}) are
 * package-private and {@code client}-free so they stay unit-testable.
 */
@Slf4j
@Singleton
public final class SkillTracker
{
	private static final Set<Integer> WOODCUTTING_ANIMATIONS;
	static
	{
		Set<Integer> wc = new java.util.HashSet<>();
		wc.add(AnimationID.HUMAN_WOODCUTTING_BRONZE_AXE);
		wc.add(AnimationID.HUMAN_WOODCUTTING_IRON_AXE);
		wc.add(AnimationID.HUMAN_WOODCUTTING_STEEL_AXE);
		wc.add(AnimationID.HUMAN_WOODCUTTING_BLACK_AXE);
		wc.add(AnimationID.HUMAN_WOODCUTTING_MITHRIL_AXE);
		wc.add(AnimationID.HUMAN_WOODCUTTING_ADAMANT_AXE);
		wc.add(AnimationID.HUMAN_WOODCUTTING_RUNE_AXE);
		wc.add(AnimationID.HUMAN_WOODCUTTING_GILDED_AXE);
		wc.add(AnimationID.HUMAN_WOODCUTTING_DRAGON_AXE);
		wc.add(AnimationID.HUMAN_WOODCUTTING_INFERNAL_AXE);
		wc.add(AnimationID.HUMAN_WOODCUTTING_3A_AXE);
		wc.add(AnimationID.HUMAN_WOODCUTTING_CRYSTAL_AXE);
		wc.add(AnimationID.HUMAN_WOODCUTTING_TRAILBLAZER_AXE);
		wc.add(AnimationID.HUMAN_WOODCUTTING_TRAILBLAZER_RELOADED_AXE);
		wc.add(AnimationID.HUMAN_WOODCUTTING_BLESSED_AXE);
		wc.add(AnimationID.HUMAN_WOODCUTTING_LEAGUE_AXE);
		wc.add(AnimationID.HUMAN_WOODCUTTING_DEMONIC_PACT);
		WOODCUTTING_ANIMATIONS = Collections.unmodifiableSet(wc);
	}

	private static final Set<Integer> MINING_ANIMATIONS;
	static
	{
		Set<Integer> mining = new java.util.HashSet<>();
		mining.add(AnimationID.HUMAN_MINING_BRONZE_PICKAXE);
		mining.add(AnimationID.HUMAN_MINING_IRON_PICKAXE);
		mining.add(AnimationID.HUMAN_MINING_STEEL_PICKAXE);
		mining.add(AnimationID.HUMAN_MINING_BLACK_PICKAXE);
		mining.add(AnimationID.HUMAN_MINING_MITHRIL_PICKAXE);
		mining.add(AnimationID.HUMAN_MINING_ADAMANT_PICKAXE);
		mining.add(AnimationID.HUMAN_MINING_RUNE_PICKAXE);
		mining.add(AnimationID.HUMAN_MINING_GILDED_PICKAXE);
		mining.add(AnimationID.HUMAN_MINING_DRAGON_PICKAXE);
		mining.add(AnimationID.HUMAN_MINING_INFERNAL_PICKAXE);
		mining.add(AnimationID.HUMAN_MINING_3A_PICKAXE);
		mining.add(AnimationID.HUMAN_MINING_CRYSTAL_PICKAXE);
		MINING_ANIMATIONS = Collections.unmodifiableSet(mining);
	}

	// Fishing is detected by interaction + tool possession (not animation), the way RuneLite
	// core's Fishing plugin does. Each method maps to the tools that perform it, so Appraise
	// can light up exactly the fish you can catch with what you're holding (a fly rod enables
	// lure fish but not bait fish). FISHING_TOOLS (any-fishing) is the union of all methods.
	private static final Map<String, Set<Integer>> METHOD_TOOLS;
	private static final Set<Integer> FISHING_TOOLS;
	static
	{
		Map<String, Set<Integer>> m = new HashMap<>();
		m.put("net", toolSet(ItemID.NET));
		m.put("bignet", toolSet(ItemID.BIG_NET));
		m.put("bait", toolSet(ItemID.FISHING_ROD, ItemID.OILY_FISHING_ROD,
			ItemID.BRUT_FISHING_ROD, ItemID.FISHINGROD_PEARL,
			ItemID.FISHINGROD_PEARL_BRUT, ItemID.FISHINGROD_PEARL_OILY));
		m.put("lure", toolSet(ItemID.FLY_FISHING_ROD, ItemID.FISHINGROD_PEARL_FLY));
		m.put("cage", toolSet(ItemID.LOBSTER_POT));
		m.put("harpoon", toolSet(ItemID.HARPOON, ItemID.DRAGON_HARPOON,
			ItemID.INFERNAL_HARPOON, ItemID.CRYSTAL_HARPOON, ItemID.TRAILBLAZER_HARPOON,
			ItemID.HUNTING_BARBED_HARPOON));
		m.put("vessel", toolSet(ItemID.TBWT_KARAMBWAN_VESSEL));
		METHOD_TOOLS = Collections.unmodifiableMap(m);

		Set<Integer> all = new java.util.HashSet<>();
		for (Set<Integer> s : m.values())
		{
			all.addAll(s);
		}
		FISHING_TOOLS = Collections.unmodifiableSet(all);
	}

	private static Set<Integer> toolSet(int... ids)
	{
		Set<Integer> s = new java.util.HashSet<>();
		for (int id : ids)
		{
			s.add(id);
		}
		return Collections.unmodifiableSet(s);
	}

	private static final Set<Skill> TRACKED_SKILLS = Collections.unmodifiableSet(
		EnumSet.of(Skill.WOODCUTTING, Skill.MINING, Skill.FISHING)
	);

	// How many idle ticks (no gathering animation) before we consider the skill
	// inactive and hide the overlay. ~3 seconds covers walking between trees.
	private static final int INACTIVITY_TIMEOUT_TICKS = 5;

	// How many ticks an action signal (catch chat / skill XP gain) stays valid for crediting a
	// coincident inventory gain. Event order within a tick isn't guaranteed, so we tolerate the
	// signal and the inventory change landing one tick apart in either direction.
	private static final int SIGNAL_WINDOW_TICKS = 1;

	private final Client client;
	private final ResourceData resourceData;
	private final ItemManager itemManager;
	// Per-unit GP price source. In production this reads the GE price via ItemManager (only safe
	// on the client thread); tests inject a stub so the ledger logic needs no live client.
	private final IntToLongFunction priceFn;
	private final SessionTotals sessionTotals;

	private XpTrackerService xpTrackerService;

	// Skilling resource value in GP, valued on the client thread (where item-price lookups
	// are safe) and read by the side panel. Never value items on the EDT — getItemPrice ->
	// client.getItemDefinition asserts the client thread and would throw under -ea.
	private volatile long skillingKeptGp;
	private volatile long skillingTotalGp;
	// This-session counterparts, mirroring combat's TargetState.sessionKept/TotalValue. Reset on
	// logout (onProfileChanged) and never restored by loadFromState, so they reflect only gathers
	// made this session while lifetime totals persist.
	private volatile long sessionSkillingKeptGp;
	private volatile long sessionSkillingTotalGp;
	// Tracks the last kept-value fed into SessionTotals, so recomputeSkillingGp can feed only
	// the delta (mirrors combat's StateTracker.recordKeptDelta semantics).
	private long prevSummaryGp;
	// Per-skill kept GP, valued on the client thread each recompute and read by the EDT-bound
	// side panel (SkillingSection). A fresh immutable map is published via this volatile
	// reference on every recompute — never mutate a map after assigning it here — so an EDT
	// read always sees a consistent snapshot instead of a torn map (see SystemInterfacePanel's
	// documented "never call itemManager.getItemPrice from the panel's EDT" rule).
	private volatile Map<Skill, Long> keptGpBySkill = Collections.emptyMap();

	private volatile Skill activeSkill;
	// The last skill the player was actively training. Unlike activeSkill, this is
	// NOT cleared on inactivity — the side panel uses it as a persistent log so you
	// can still see what you were doing after the in-game overlay auto-hides.
	private volatile Skill lastActiveSkill;
	private volatile long lastActivityTick;
	/** Wall-clock time of the last gathering activity, so overlays can honour the auto-hide setting. */
	private volatile long lastActivityMillis;

	// Fishing context. Fishing spots are NPCs offering several fish; we track which spot is
	// being fished (for Appraise).
	private volatile int activeFishingSpotId = -1;

	// Gathering context. The gathering object (tree/rock) the player last engaged (for Appraise).
	private volatile int activeObjectId = -1;

	// The fishing method the player last engaged (harpoon/cage/net/bait/lure/bignet/...), or null.
	private volatile String activeFishingMethod = null;

	// --- Kept-vs-finalized ledger ---
	// Kept deliberately cohesive: a later step extracts this into a shared AcquisitionLedger.
	//
	// Units of each resource gathered this session and STILL in the inventory. Only these may be
	// deducted from "kept" by a drop. Items re-introduced from the bank/GE share the item id but
	// were never live, so dropping them must not move the count. When a gathered item leaves the
	// inventory without a drop (banked, used, cooked) it is clamped out of this map — finalized as
	// kept (the kept total is untouched, but it can no longer be deducted by a later drop).
	private final Map<Integer, Integer> liveGathered = new HashMap<>();

	// My own dropped resource items currently on the ground: location -> (item id -> count). A drop
	// decrements kept/live and records here; the ground item's despawn resolves it. If a coincident
	// inventory gain occurred (you picked it back up) kept/live are restored, otherwise it timed out
	// and the decrement is final. This makes the drop->re-pickup window exactly the item's lifetime
	// on the ground, and means a later bank/GE withdrawal of the same id can never restore kept.
	private final Map<Object, Map<Integer, Integer>> groundDrops = new HashMap<>();

	// Resource inventory gains seen this tick that were NOT credited as a fresh gather (re-pickups and
	// bank/GE acquisitions). Read at end of tick to tell a re-pickup of my drop from a timeout despawn.
	private final Map<Integer, Integer> resourceGainsThisTick = new HashMap<>();

	// My ground drops that despawned this tick, awaiting pickup-vs-timeout classification in reconcileTick.
	private final Map<Integer, Integer> despawnedMineThisTick = new HashMap<>();

	// --- Provenance gate (the "did this item come from a gathering action?" ledger) ---
	// Kept deliberately cohesive: a later step extracts this into a shared AcquisitionLedger.
	//
	// The most recent action signal — a "You catch ..." chat or a tracked-skill XP gain — as a
	// (skill, tick) pair. A fresh inventory gain of that skill's resource within the window is a
	// real gather; a gain without a matching fresh signal (GE collect, bank withdraw, trade) is not.
	private Skill lastGatherSignalSkill;
	private long lastGatherSignalTick = Long.MIN_VALUE;
	// Resource gains seen before their signal arrived this tick, buffered until the signal lands
	// (handles inventory-before-signal ordering) and discarded if no signal comes (the GE-buy case).
	private final Map<Integer, Integer> pendingGains = new HashMap<>();
	private long pendingGainsTick = Long.MIN_VALUE;
	// Last observed XP per tracked skill, to tell a real XP gain from the login baseline read.
	private final Map<Skill, Integer> lastXp = new HashMap<>();

	// Per-skill XP at the first StatChanged this session (the login baseline read), so
	// getSessionXp can report session-gained XP = currentXp - baseline. Reset on logout.
	private final Map<Skill, Integer> sessionStartXp = new HashMap<>();

	private final AtomicLong generation = new AtomicLong(0);

	private final Map<Skill, SkillState> skillStates = new HashMap<>();

	private Map<Integer, Integer> lastInventory;

	@Inject
	public SkillTracker(Client client, ResourceData resourceData, ItemManager itemManager,
		SessionTotals sessionTotals)
	{
		// Value items via the GE price. Only call this on the client thread (see recomputeSkillingGp).
		this(client, resourceData, itemManager, itemManager::getItemPrice, sessionTotals);
	}

	/**
	 * Test seam: supply the resource data and a price function directly, with no live
	 * {@code Client}/{@code ItemManager}. The provenance/ledger logic is driven through the
	 * package-private {@code applyInventoryDiff}/{@code recordGatherSignal}/{@code onItemDropped}
	 * methods, none of which touch {@code client}.
	 */
	SkillTracker(ResourceData resourceData, IntToLongFunction priceFn)
	{
		this(null, resourceData, null, priceFn, new SessionTotals());
	}

	/**
	 * Test seam: as above, but lets a test supply its own {@link SessionTotals} so it can observe
	 * what gets fed into it (e.g. asserting a profile load does NOT inflate today's bucket).
	 */
	SkillTracker(ResourceData resourceData, IntToLongFunction priceFn, SessionTotals sessionTotals)
	{
		this(null, resourceData, null, priceFn, sessionTotals);
	}

	private SkillTracker(Client client, ResourceData resourceData, ItemManager itemManager,
		IntToLongFunction priceFn, SessionTotals sessionTotals)
	{
		this.client = client;
		this.resourceData = resourceData;
		this.itemManager = itemManager;
		this.priceFn = priceFn;
		this.sessionTotals = sessionTotals;
	}

	/** Total GP of currently-kept (net) skilling resources. Valued on the client thread. */
	public long getSkillingKeptGp()
	{
		return skillingKeptGp;
	}

	/** Total GP of all lifetime-gathered (gross) skilling resources. Valued on the client thread. */
	public long getSkillingTotalGp()
	{
		return skillingTotalGp;
	}

	/** GP of currently-kept resources gathered <em>this session</em>. Resets on logout. */
	public long getSessionSkillingKeptGp()
	{
		return sessionSkillingKeptGp;
	}

	/** GP of all resources gathered <em>this session</em> (gross). Resets on logout. */
	public long getSessionSkillingTotalGp()
	{
		return sessionSkillingTotalGp;
	}

	/**
	 * Total GP of {@code skill}'s currently-kept output, valued on the client thread during the
	 * last {@link #recomputeSkillingGp}. Safe to call from the EDT — this reads a pre-computed,
	 * immutable snapshot rather than pricing items in place (see {@link #keptGpBySkill}).
	 */
	public long getSkillKeptGp(Skill skill)
	{
		return keptGpBySkill.getOrDefault(skill, 0L);
	}

	/**
	 * Re-values all gathered resources into the lifetime and this-session GP totals, feeding the
	 * delta into {@link SessionTotals}. MUST be called on the client thread in production —
	 * {@link #priceFn} reads GE prices via {@link ItemManager}.
	 */
	private void recomputeSkillingGp()
	{
		recomputeSkillingGp(true);
	}

	/**
	 * Re-values all gathered resources into the lifetime and this-session GP totals. When
	 * {@code feedSessionTotals} is false, {@link #prevSummaryGp} is still re-baselined to the
	 * freshly-computed kept value, but the delta is NOT fed into {@link SessionTotals} — used by
	 * {@link #loadFromState} so a profile load doesn't dump the whole restored lifetime kept value
	 * into today's bucket (today's bucket is independently restored from disk).
	 */
	private void recomputeSkillingGp(boolean feedSessionTotals)
	{
		long kept = 0;
		long total = 0;
		long sessionKept = 0;
		long sessionTotal = 0;
		final Map<Skill, Long> newKeptGpBySkill = new HashMap<>();
		for (SkillState st : skillStates.values())
		{
			final long skillKeptGp = ResourceValuer.totalValue(st.getResourceCounts(), priceFn);
			kept += skillKeptGp;
			total += ResourceValuer.totalValue(st.getGrossResourceCounts(), priceFn);
			sessionKept += ResourceValuer.totalValue(st.getSessionKeptCounts(), priceFn);
			sessionTotal += ResourceValuer.totalValue(st.getSessionGrossCounts(), priceFn);
			newKeptGpBySkill.put(st.getSkill(), skillKeptGp);
		}
		skillingKeptGp = kept;
		skillingTotalGp = total;
		sessionSkillingKeptGp = sessionKept;
		sessionSkillingTotalGp = sessionTotal;
		// Publish the freshly-built map as an immutable snapshot — the EDT-side reader
		// (getSkillKeptGp) only ever sees a fully-populated map, never a partially-built one.
		keptGpBySkill = Collections.unmodifiableMap(newKeptGpBySkill);
		if (feedSessionTotals)
		{
			// Known limitation: this re-values the entire held stock at the current GE price, so a
			// price change on already-held resources shifts (kept - prevSummaryGp) and credits/debits
			// the "Today" bucket even though no new resource was gathered. Combat is immune because it
			// feeds SessionTotals point-in-time kept deltas rather than re-pricing a running total.
			// Accepted as an inherent cost of aggregating Today from the repriced kept total — do not
			// try to net the price-only movement out here; it can't be told apart from a real regather
			// without a separate quantity-delta path.
			sessionTotals.addReward(kept - prevSummaryGp, java.time.LocalDate.now());
		}
		prevSummaryGp = kept;
	}

	public void setXpTrackerService(XpTrackerService xpTrackerService)
	{
		this.xpTrackerService = xpTrackerService;
	}

	public Skill getActiveSkill()
	{
		return activeSkill;
	}

	/**
	 * The skill to display in the persistent side-panel log: the one currently being
	 * trained, or the most recent one if the player has gone idle. Null only if no
	 * skill has been tracked this profile.
	 */
	public Skill getDisplaySkill()
	{
		return lastActiveSkill;
	}

	public boolean isActive()
	{
		return activeSkill != null;
	}

	/** Milliseconds since the last gathering action, or {@link Long#MAX_VALUE} if none this profile. */
	public long getMillisSinceActivity()
	{
		return lastActivityMillis == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - lastActivityMillis;
	}

	/** The NPC id of the fishing spot currently being fished, or -1. */
	public int getActiveFishingSpotId()
	{
		return activeFishingSpotId;
	}

	/** The gathering object (tree/rock) the player last engaged, or -1 if none. */
	public int getActiveObjectId()
	{
		return activeObjectId;
	}

	/** Records the engaged gathering object; clears any active fishing spot (single active node). */
	public void setActiveObject(int objectId)
	{
		if (this.activeObjectId != objectId || this.activeFishingSpotId != -1 || this.activeFishingMethod != null)
		{
			generation.incrementAndGet();
		}
		this.activeObjectId = objectId;
		this.activeFishingSpotId = -1;
		this.activeFishingMethod = null;
	}

	/** The fishing method the player last engaged (harpoon/cage/net/bait/lure/bignet/...), or null. */
	public String getActiveFishingMethod()
	{
		return activeFishingMethod;
	}

	/** Records the active fishing method; bumps the generation only on an actual change. */
	public void setActiveFishingMethod(String method)
	{
		if (!Objects.equals(this.activeFishingMethod, method))
		{
			generation.incrementAndGet();
		}
		this.activeFishingMethod = method;
	}

	/** Whether the given NPC id is a known fishing spot in the curated data. */
	public boolean isFishingSpot(int npcId)
	{
		return !resourceData.forNpcId(npcId).isEmpty();
	}

	/**
	 * Called when the local player starts interacting with an NPC. Returns {@code true} if it
	 * is a fishing spot — so the caller can skip combat handling. The active fishing context
	 * is only set when the player is also holding a fishing tool, confirming actual fishing.
	 */
	public boolean onFishingSpotInteract(int npcId)
	{
		if (!isFishingSpot(npcId))
		{
			return false;
		}
		if (hasFishingTool())
		{
			if (activeFishingSpotId != npcId || activeObjectId != -1)
			{
				generation.incrementAndGet();
			}
			activeFishingSpotId = npcId;
			activeObjectId = -1;
			setActiveSkill(Skill.FISHING);
			markActivity();
		}
		return true;
	}

	private void markActivity()
	{
		lastActivityTick = client.getTickCount();
		lastActivityMillis = System.currentTimeMillis();
	}

	/**
	 * Whether the player holds (inventory or worn) a tool for the given fishing method
	 * (net/bait/lure/cage/harpoon/bignet/vessel). Used by Appraise to mark the fish you can
	 * actually catch right now. Note: consumables (feathers/bait) are not checked.
	 */
	public boolean hasToolForMethod(String method)
	{
		final Set<Integer> tools = method == null ? null : METHOD_TOOLS.get(method);
		if (tools == null || tools.isEmpty())
		{
			return false;
		}
		return containsAny(client.getItemContainer(InventoryID.INV), tools)
			|| containsAny(client.getItemContainer(InventoryID.WORN), tools);
	}

	/**
	 * Whether the player can catch this resource right now: holds the method's tool AND, if the
	 * resource needs a secondary (feathers for lure, bait, sandworms, karambwanji), holds one.
	 */
	public boolean canCatch(ResourceData.ResourceEntry entry)
	{
		if (entry == null || !hasToolForMethod(entry.getMethod()))
		{
			return false;
		}
		final java.util.List<Integer> secondaries = entry.getSecondaries();
		if (secondaries.isEmpty())
		{
			return true;
		}
		final java.util.Set<Integer> needed = new java.util.HashSet<>(secondaries);
		return containsAny(client.getItemContainer(InventoryID.INV), needed)
			|| containsAny(client.getItemContainer(InventoryID.WORN), needed);
	}

	private boolean hasFishingTool()
	{
		return containsAny(client.getItemContainer(InventoryID.INV), FISHING_TOOLS)
			|| containsAny(client.getItemContainer(InventoryID.WORN), FISHING_TOOLS);
	}

	private boolean containsAny(ItemContainer container, Set<Integer> ids)
	{
		if (container == null)
		{
			return false;
		}
		final Item[] items = container.getItems();
		if (items == null)
		{
			return false;
		}
		for (Item item : items)
		{
			if (ids.contains(item.getId()))
			{
				return true;
			}
		}
		return false;
	}

	public long getGeneration()
	{
		return generation.get();
	}

	public ResourceData getResourceData()
	{
		return resourceData;
	}

	public int getXpHr(Skill skill)
	{
		return xpTrackerService != null ? xpTrackerService.getXpHr(skill) : 0;
	}

	public int getActionsHr(Skill skill)
	{
		return xpTrackerService != null ? xpTrackerService.getActionsHr(skill) : 0;
	}

	public int getActions(Skill skill)
	{
		return xpTrackerService != null ? xpTrackerService.getActions(skill) : 0;
	}

	public SkillState getSkillState(Skill skill)
	{
		return skillStates.get(skill);
	}

	/** Skills that have accumulated any tracked state this profile (for the loot log). */
	public java.util.List<Skill> getTrackedSkills()
	{
		return new java.util.ArrayList<>(skillStates.keySet());
	}

	public int getCurrentLevel(Skill skill)
	{
		return client.getRealSkillLevel(skill);
	}

	public void onAnimationChanged(int animationId)
	{
		Skill detected = detectSkill(animationId);
		if (detected != null)
		{
			setActiveSkill(detected);
			markActivity();
		}
	}

	/** Sets the active skill, bumping the generation on a transition so the UI refreshes. */
	private void setActiveSkill(Skill skill)
	{
		if (skill != null)
		{
			lastActiveSkill = skill;
		}
		if (activeSkill != skill)
		{
			activeSkill = skill;
			generation.incrementAndGet();
		}
	}

	/**
	 * Estimated time until the next level in {@code skill}, formatted H:MM:SS, based on the
	 * current XP/hr from RuneLite's XP tracker. Returns null when the skill is maxed or the
	 * rate is unknown (e.g. you just started) — callers should hide the row rather than show
	 * an "infinite" duration.
	 */
	public String getTimeToNextLevel(Skill skill)
	{
		if (skill == null)
		{
			return null;
		}
		final int level = client.getRealSkillLevel(skill);
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return null;
		}
		final int remaining = Experience.getXpForLevel(level + 1) - client.getSkillExperience(skill);
		final int xpHr = getXpHr(skill);
		if (remaining <= 0 || xpHr <= 0)
		{
			return null;
		}
		final long seconds = Math.round((remaining / (double) xpHr) * 3600.0);
		final long h = seconds / 3600;
		final long m = (seconds % 3600) / 60;
		final long s = seconds % 60;
		return String.format("%d:%02d:%02d", h, m, s);
	}

	public void onGameTick()
	{
		// Resolve my ground drops that despawned this tick (re-picked vs timed out) before the
		// unexplained-gain bookkeeping for the tick is cleared.
		reconcileTick(client.getTickCount());

		// Discard any resource gain still buffered without a coincident action signal — it was a
		// GE collect / bank withdrawal / trade, not a gather.
		expireStalePending(client.getTickCount());

		// Woodcutting (and most gathering skills) use a continuous looping animation.
		// AnimationChanged only fires once when the action starts, so we poll the
		// local player's current animation every tick to keep the active skill alive
		// while the player is still gathering.
		final Player local = client.getLocalPlayer();
		if (local != null)
		{
			final Skill detected = detectSkill(local.getAnimation());
			if (detected != null)
			{
				setActiveSkill(detected);
				markActivity();
			}
			else if (detectFishing(local))
			{
				// Interaction with a fishing spot persists each tick while fishing, so this
				// keeps the skill alive between catches without relying on the animation.
				setActiveSkill(Skill.FISHING);
				markActivity();
			}
		}

		if (activeSkill != null)
		{
			long elapsed = client.getTickCount() - lastActivityTick;
			if (elapsed > INACTIVITY_TIMEOUT_TICKS)
			{
				setActiveSkill(null);
				activeFishingSpotId = -1;
				activeObjectId = -1;
				activeFishingMethod = null;
			}
		}
	}

	/** True when the player is interacting with a fishing-spot NPC and holding a tool. */
	private boolean detectFishing(Player local)
	{
		final Actor target = local.getInteracting();
		if (!(target instanceof NPC))
		{
			return false;
		}
		final int npcId = ((NPC) target).getId();
		if (!isFishingSpot(npcId) || !hasFishingTool())
		{
			return false;
		}
		if (activeFishingSpotId != npcId || activeObjectId != -1)
		{
			generation.incrementAndGet();
		}
		activeFishingSpotId = npcId;
		activeObjectId = -1;
		return true;
	}

	public void onInventoryChanged(ItemContainer inventory)
	{
		if (inventory == null)
		{
			return; // can't snapshot — keep the existing baseline
		}
		applyInventoryDiff(snapshotInventory(inventory), client.getTickCount());
	}

	/**
	 * Reconciles an inventory snapshot against the last one:
	 *
	 * <ul>
	 *   <li><b>Fresh gather</b> — a gain of a known resource is credited only when a coincident
	 *       action signal exists ({@link #signalFreshFor}). Without one the gain is buffered and
	 *       discarded next tick, and also recorded as an "unexplained" gain so {@link #reconcileTick}
	 *       can recognise a re-pickup of one of my drops (GE collects / bank withdrawals / trades
	 *       look identical here and so never count on their own).</li>
	 *   <li><b>Bank/use finalization</b> — after the diff, {@link #liveGathered} is clamped to the
	 *       current inventory. Gathered units that left without a drop (banked, used, cooked) drop out
	 *       of the live set: they stay counted as kept but can no longer be deducted by a later drop.</li>
	 * </ul>
	 *
	 * <p>Package-private and free of {@code client} so the ledger is unit-testable.
	 */
	void applyInventoryDiff(Map<Integer, Integer> current, long tick)
	{
		if (lastInventory == null)
		{
			lastInventory = current;
			return;
		}

		boolean changed = false;
		boolean creditedFreshGather = false;
		for (Map.Entry<Integer, Integer> entry : current.entrySet())
		{
			final int itemId = entry.getKey();
			int gained = entry.getValue() - lastInventory.getOrDefault(itemId, 0);
			if (gained <= 0)
			{
				continue;
			}

			final Skill gatherSkill = gatherableSkillFor(itemId);
			if (gatherSkill == null)
			{
				continue;
			}

			// A fresh gather — credited only with a coincident action signal (provenance gate).
			if (signalFreshFor(gatherSkill, tick))
			{
				creditGather(gatherSkill, itemId, gained);
				changed = true;
				creditedFreshGather = true;
			}
			else
			{
				// No signal: buffer in case the catch/XP lands later this tick (discarded by
				// expireStalePending otherwise), and record it as an unexplained gain so a
				// coincident despawn of my drop this tick can be recognised as a re-pickup.
				pendingGains.merge(itemId, gained, Integer::sum);
				pendingGainsTick = tick;
				resourceGainsThisTick.merge(itemId, gained, Integer::sum);
			}
		}

		// One action signal credits one inventory event (the action's yield, possibly several stacks
		// in this single diff) and is then consumed. Without this, a bank withdrawal / GE collect
		// landing in a separate event within the signal window would be miscredited as gathered.
		if (creditedFreshGather)
		{
			consumeGatherSignal();
		}

		// Finalize gathered units that left the inventory without a drop (banked / used / cooked):
		// they remain kept but are no longer live, so a later drop of a same-id bank/GE item can't
		// deduct them.
		clampLiveToInventory(current);

		if (changed)
		{
			recomputeSkillingGp();
			generation.incrementAndGet();
		}

		lastInventory = current;
	}

	/** Clamps each live-gathered count down to what's actually in the inventory now. */
	private void clampLiveToInventory(Map<Integer, Integer> current)
	{
		final java.util.Iterator<Map.Entry<Integer, Integer>> it = liveGathered.entrySet().iterator();
		while (it.hasNext())
		{
			final Map.Entry<Integer, Integer> e = it.next();
			final int inv = current.getOrDefault(e.getKey(), 0);
			if (e.getValue() > inv)
			{
				e.setValue(inv);
			}
			if (e.getValue() <= 0)
			{
				it.remove();
			}
		}
	}

	/** The skill that owns {@code itemId} as a primary resource or a curated reward, or null. */
	private Skill gatherableSkillFor(int itemId)
	{
		ResourceData.ResourceEntry re = resourceData.forItemId(itemId);
		if (re != null)
		{
			return re.getSkill();
		}
		ResourceData.RewardEntry rw = resourceData.rewardForItemId(itemId);
		return rw != null ? rw.getSkill() : null;
	}

	/** Records {@code qty} of {@code itemId} as gathered for {@code skill} (kept + gross + session + live). */
	private void creditGather(Skill skill, int itemId, int qty)
	{
		skillStates.computeIfAbsent(skill, SkillState::new).addResourceCount(itemId, qty);
		liveGathered.merge(itemId, qty, Integer::sum);
		log.debug("Gathered {}x itemId={} ({})", qty, itemId, skill);
	}

	/** True when the most recent action signal is for {@code skill} and within the tick window. */
	private boolean signalFreshFor(Skill skill, long tick)
	{
		return skill != null
			&& skill == lastGatherSignalSkill
			&& lastGatherSignalTick != Long.MIN_VALUE
			&& tick >= lastGatherSignalTick
			&& tick - lastGatherSignalTick <= SIGNAL_WINDOW_TICKS;
	}

	/**
	 * Records a gathering action signal for {@code skill} at {@code tick} (a "You catch ..." chat
	 * or a tracked-skill XP gain), then flushes any inventory gains that were buffered awaiting it.
	 * Package-private: the public {@link #onChatMessage}/{@link #onStatChanged} handlers supply the
	 * live tick.
	 */
	void recordGatherSignal(Skill skill, long tick)
	{
		lastGatherSignalSkill = skill;
		lastGatherSignalTick = tick;
		flushPendingGains(skill, tick);
	}

	/** Credits buffered gains of {@code signalSkill}'s resources once the matching signal lands. */
	private void flushPendingGains(Skill signalSkill, long tick)
	{
		if (pendingGains.isEmpty() || tick - pendingGainsTick > SIGNAL_WINDOW_TICKS)
		{
			return;
		}
		boolean credited = false;
		final java.util.Iterator<Map.Entry<Integer, Integer>> it = pendingGains.entrySet().iterator();
		while (it.hasNext())
		{
			final Map.Entry<Integer, Integer> e = it.next();
			final Skill gatherSkill = gatherableSkillFor(e.getKey());
			if (gatherSkill != null && gatherSkill == signalSkill)
			{
				creditGather(gatherSkill, e.getKey(), e.getValue());
				it.remove();
				credited = true;
			}
		}
		if (credited)
		{
			// The signal has now done its job (credited the gain that arrived before it). Consume it
			// so a later, unrelated gain in the same window isn't also miscredited as gathered.
			consumeGatherSignal();
			recomputeSkillingGp();
			generation.incrementAndGet();
		}
	}

	/** Clears the action signal so it credits at most one inventory event (one gather action). */
	private void consumeGatherSignal()
	{
		lastGatherSignalSkill = null;
		lastGatherSignalTick = Long.MIN_VALUE;
	}

	/** Discards buffered gains that never got a coincident signal (GE collect, bank withdraw, trade). */
	void expireStalePending(long tick)
	{
		if (!pendingGains.isEmpty() && tick - pendingGainsTick > SIGNAL_WINDOW_TICKS)
		{
			pendingGains.clear();
		}
	}

	/**
	 * Whether a {@code StatChanged} for {@code skill} reaching {@code xp} is a real gain (action
	 * happened) versus the login baseline read. Updates the per-skill baseline as a side effect.
	 */
	boolean isXpGain(Skill skill, int xp)
	{
		final Integer prev = lastXp.put(skill, xp);
		return prev != null && xp > prev;
	}

	/**
	 * The player dropped {@code qty} of {@code itemId} (from a Drop/Bury/etc menu action). Uses the
	 * player's current tile as the ground-drop key and the live tick.
	 */
	public void onItemDropped(int itemId, int qty)
	{
		onItemDropped(itemId, qty, playerLocationKey(), client.getTickCount());
	}

	/**
	 * Core drop handler. Deducts only <em>live</em> gathered units (resources gathered this session
	 * and still in the inventory) from the kept count — items re-introduced from the bank/GE share
	 * the id but were never live, so dropping them is a no-op. Deducted units are recorded as a
	 * ground drop at {@code location}; their despawn ({@link #onGroundResourceDespawned}) later
	 * resolves whether they were re-picked (restore) or timed out (final).
	 *
	 * <p>Package-private and free of {@code client} so the ledger is unit-testable.
	 */
	void onItemDropped(int itemId, int qty, Object location, long tick)
	{
		if (qty <= 0)
		{
			return;
		}
		ResourceData.ResourceEntry entry = resourceData.forItemId(itemId);
		if (entry == null)
		{
			return;
		}
		SkillState state = skillStates.get(entry.getSkill());
		if (state == null)
		{
			return;
		}
		final int live = liveGathered.getOrDefault(itemId, 0);
		final long drop = Math.min(qty, live);
		if (drop > 0)
		{
			state.subtractResourceCount(itemId, (int) drop);
			adjustLive(itemId, (int) -drop);
			groundDrops.computeIfAbsent(location, k -> new HashMap<>()).merge(itemId, (int) drop, Integer::sum);
			recomputeSkillingGp();
			generation.incrementAndGet();
			log.debug("Dropped {}x itemId={} ({}) — kept -{}, awaiting despawn", qty, itemId, entry.getSkill(), drop);
		}
	}

	/**
	 * One of my dropped ground items of {@code itemId} (qty {@code qty}) despawned at {@code location}
	 * — either picked up or timed out. Buffered for end-of-tick classification in {@link #reconcileTick}.
	 *
	 * <p>Package-private and free of {@code client} so the ledger is unit-testable.
	 */
	void onGroundResourceDespawned(int itemId, int qty, Object location, long tick)
	{
		final Map<Integer, Integer> at = groundDrops.get(location);
		if (at == null)
		{
			return;
		}
		final int tracked = at.getOrDefault(itemId, 0);
		if (tracked <= 0)
		{
			return;
		}
		final int gone = Math.min(qty <= 0 ? tracked : qty, tracked);
		final int remaining = tracked - gone;
		if (remaining <= 0)
		{
			at.remove(itemId);
		}
		else
		{
			at.put(itemId, remaining);
		}
		if (at.isEmpty())
		{
			groundDrops.remove(location);
		}
		despawnedMineThisTick.merge(itemId, gone, Integer::sum);
	}

	/**
	 * End-of-tick reconciliation of my ground drops that despawned this tick. A despawn coinciding
	 * with an unexplained inventory gain of the same resource was a re-pickup — restore kept/live;
	 * any remaining despawns timed out and the earlier kept deduction stands as final. Called once
	 * per game tick (after that tick's inventory/despawn events).
	 *
	 * <p>Package-private and free of {@code client} so the ledger is unit-testable.
	 */
	void reconcileTick(long tick)
	{
		boolean restored = false;
		for (Map.Entry<Integer, Integer> e : despawnedMineThisTick.entrySet())
		{
			final int itemId = e.getKey();
			final int despawned = e.getValue();
			final int gains = resourceGainsThisTick.getOrDefault(itemId, 0);
			final int pickedUp = Math.min(despawned, gains);
			if (pickedUp > 0)
			{
				final ResourceData.ResourceEntry re = resourceData.forItemId(itemId);
				if (re != null)
				{
					skillStates.computeIfAbsent(re.getSkill(), SkillState::new).addKeptOnly(itemId, pickedUp);
					adjustLive(itemId, pickedUp);
					resourceGainsThisTick.put(itemId, gains - pickedUp);
					restored = true;
				}
			}
		}
		despawnedMineThisTick.clear();
		resourceGainsThisTick.clear();
		if (restored)
		{
			recomputeSkillingGp();
			generation.incrementAndGet();
		}
	}

	/** Adjusts the live-gathered count for {@code itemId} by {@code delta}, dropping empty entries. */
	private void adjustLive(int itemId, int delta)
	{
		final int updated = liveGathered.getOrDefault(itemId, 0) + delta;
		if (updated > 0)
		{
			liveGathered.put(itemId, updated);
		}
		else
		{
			liveGathered.remove(itemId);
		}
	}

	/** The player's current tile, used to key ground drops so despawns can be matched back. */
	private Object playerLocationKey()
	{
		final Player local = client == null ? null : client.getLocalPlayer();
		return local == null ? null : local.getWorldLocation();
	}

	/**
	 * A "You catch ..." message confirms active fishing: it refreshes the activity clock (so a
	 * slow spot doesn't time out between catches) and ensures the active skill is Fishing. Only
	 * acts when a fishing spot is the current interaction context, to avoid false positives.
	 */
	public void onChatMessage(String message)
	{
		if (message != null && message.startsWith("You catch"))
		{
			// The catch message is a fishing action signal: it gates inventory gains as gathered
			// (provenance) and, when at a spot, keeps the skill/overlay alive between catches.
			if (activeFishingSpotId != -1)
			{
				setActiveSkill(Skill.FISHING);
				markActivity();
			}
			recordGatherSignal(Skill.FISHING, client.getTickCount());
		}
		if (message != null && message.contains("bird's nest"))
		{
			// Bird nests fall while gathering (chat-first provenance, spec §3). Credit under the
			// active tracked skill so a coincident inventory gain of the nest is a real gather.
			final Skill active = activeSkill;
			if (active != null && TRACKED_SKILLS.contains(active))
			{
				recordGatherSignal(active, client.getTickCount());
			}
		}
	}

	private int thievingFails;
	private int thievingSuccesses;

	// KNOWN LIMITATION (record per AGENTS.md bug discipline — do NOT rely on this until tightened):
	// startsWith("You pick") also matches non-Thieving lines (herb/flower/allotment/fruit picking,
	// lock-picking), and "You fail to pick" can match lock-picking failures — both would skew the
	// fail-rate. This is currently INERT because Thieving is never an active skill in this build
	// (not in TRACKED_SKILLS, no activity detection), so the fail-rate row is unreachable. Tighten
	// the match (e.g. require a "pocket" token) BEFORE a future slice makes Thieving an active skill.
	/** Feeds the conservative Thieving fail-rate from explicit pickpocket chat lines. */
	public void onThievingChat(String message)
	{
		if (message == null) { return; }
		if (message.startsWith("You fail to pick")) { thievingFails++; }
		else if (message.startsWith("You pick")) { thievingSuccesses++; }
	}

	/** Fails / (fails + successes) so far, or null with no attempts recorded. */
	public Double getThievingFailRate()
	{
		final int total = thievingFails + thievingSuccesses;
		return total == 0 ? null : (double) thievingFails / total;
	}

	/**
	 * A tracked-skill XP gain is an action signal for that skill (the supplies-tracker pattern:
	 * only count when the matching skill XP dropped). Gates inventory gains as gathered.
	 */
	public void onStatChanged(StatChanged event)
	{
		final Skill skill = event.getSkill();
		if (skill == null || !TRACKED_SKILLS.contains(skill))
		{
			return;
		}
		sessionStartXp.putIfAbsent(skill, event.getXp());
		if (isXpGain(skill, event.getXp()))
		{
			recordGatherSignal(skill, client.getTickCount());
		}
	}

	/** Session XP gained for {@code skill}: current total minus the session-start baseline (0 if unknown). */
	public int getSessionXp(Skill skill)
	{
		final Integer start = sessionStartXp.get(skill);
		if (start == null || client == null)
		{
			return 0;
		}
		return Math.max(0, client.getSkillExperience(skill) - start);
	}

	/**
	 * A ground item despawned. If it's a resource at one of my drop tiles, route it to the
	 * kept-vs-finalized ledger so end-of-tick reconciliation can tell a re-pickup from a timeout.
	 */
	public void onItemDespawned(net.runelite.api.events.ItemDespawned event)
	{
		final net.runelite.api.TileItem item = event.getItem();
		if (item == null || resourceData.forItemId(item.getId()) == null)
		{
			return;
		}
		final net.runelite.api.coords.WorldPoint loc =
			event.getTile() == null ? null : event.getTile().getWorldLocation();
		onGroundResourceDespawned(item.getId(), item.getQuantity(), loc, client.getTickCount());
	}

	public void onProfileChanged()
	{
		skillStates.clear();
		lastInventory = null;
		activeSkill = null;
		lastActiveSkill = null;
		activeFishingSpotId = -1;
		activeObjectId = -1;
		activeFishingMethod = null;
		// Kept-vs-finalized ledger: live set + outstanding ground drops + per-tick reconciliation
		// buffers are all session-scoped, so clear them for a fresh login.
		liveGathered.clear();
		groundDrops.clear();
		resourceGainsThisTick.clear();
		despawnedMineThisTick.clear();
		// Provenance gate: clear the signal/buffer/XP baseline so the next session starts fresh.
		pendingGains.clear();
		pendingGainsTick = Long.MIN_VALUE;
		lastGatherSignalSkill = null;
		lastGatherSignalTick = Long.MIN_VALUE;
		lastXp.clear();
		sessionStartXp.clear();
		// Reset the activity clock so the in-game overlay doesn't linger / reappear on the
		// next login. The panel log re-surfaces the persisted skill via loadFromState, but
		// the overlay only shows after real activity this session.
		lastActivityTick = 0;
		lastActivityMillis = 0;
		skillingKeptGp = 0;
		skillingTotalGp = 0;
		sessionSkillingKeptGp = 0;
		sessionSkillingTotalGp = 0;
		keptGpBySkill = Collections.emptyMap();
		prevSummaryGp = 0;
		thievingFails = 0;
		thievingSuccesses = 0;
		generation.incrementAndGet();
	}

	/**
	 * Zeroes ALL lifetime skilling state — the destructive "Reset all-time" from the side panel.
	 * Clears every per-skill kept/gross resource count (which drive {@link #getSkillingKeptGp()} and
	 * the Skilling section) and zeroes the cached GP fields directly, so the aggregate All-time figure
	 * (combat all-time + skilling kept) truly goes to zero.
	 *
	 * <p>MUST be called on the client thread: it mutates {@code skillStates}, which the client thread
	 * also mutates from inventory/tick handlers. It does NOT reprice anything — every total is zero
	 * after the clear — so it never touches {@link ItemManager}/{@code getItemPrice} and is safe off
	 * the price path.
	 */
	public void resetAllTimeSkilling()
	{
		skillStates.clear();
		skillingKeptGp = 0;
		skillingTotalGp = 0;
		sessionSkillingKeptGp = 0;
		sessionSkillingTotalGp = 0;
		prevSummaryGp = 0;
		keptGpBySkill = java.util.Collections.emptyMap();
		// Mirror onProfileChanged: with no lifetime state left there is nothing to display in the log.
		activeSkill = null;
		lastActiveSkill = null;
		generation.incrementAndGet();
	}

	public void loadFromState(Map<String, SkillPersistence> persisted)
	{
		if (persisted == null)
		{
			return;
		}
		for (Map.Entry<String, SkillPersistence> entry : persisted.entrySet())
		{
			try
			{
				Skill skill = Skill.valueOf(entry.getKey().toUpperCase());
				SkillState state = new SkillState(skill);
				SkillPersistence p = entry.getValue();
				if (p.resourceCounts != null)
				{
					state.resourceCounts.putAll(p.resourceCounts);
				}
				if (p.grossResourceCounts != null)
				{
					state.grossResourceCounts.putAll(p.grossResourceCounts);
				}
				else
				{
					// Legacy save predating gross tracking: assume nothing was dropped, so the
					// lifetime-gathered total equals the kept count. New gathers track both.
					state.grossResourceCounts.putAll(state.resourceCounts);
				}
				skillStates.put(skill, state);
				// Surface the persisted skill in the panel log immediately on load.
				lastActiveSkill = skill;
			}
			catch (IllegalArgumentException ignored)
			{
			}
		}
		// Recompute totals without feeding SessionTotals: this restores lifetime kept/total from
		// disk, but today's SessionTotals bucket is independently restored via StateTracker and
		// must not be double-counted with the entire restored lifetime kept value.
		recomputeSkillingGp(false);
		generation.incrementAndGet();
	}

	public Map<String, SkillPersistence> toPersistence()
	{
		Map<String, SkillPersistence> result = new HashMap<>();
		for (Map.Entry<Skill, SkillState> entry : skillStates.entrySet())
		{
			SkillState s = entry.getValue();
			SkillPersistence p = new SkillPersistence();
			p.resourceCounts = new HashMap<>(s.resourceCounts);
			p.grossResourceCounts = new HashMap<>(s.grossResourceCounts);
			result.put(entry.getKey().name().toLowerCase(), p);
		}
		return result;
	}

	private Skill detectSkill(int animationId)
	{
		if (WOODCUTTING_ANIMATIONS.contains(animationId))
		{
			return Skill.WOODCUTTING;
		}
		if (MINING_ANIMATIONS.contains(animationId))
		{
			return Skill.MINING;
		}
		return null;
	}

	private Map<Integer, Integer> snapshotInventory(ItemContainer container)
	{
		Map<Integer, Integer> snapshot = new HashMap<>();
		if (container == null)
		{
			return snapshot;
		}
		Item[] items = container.getItems();
		if (items == null)
		{
			return snapshot;
		}
		for (Item item : items)
		{
			if (item.getId() >= 0 && item.getQuantity() > 0)
			{
				snapshot.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		return snapshot;
	}

	public static final class SkillState
	{
		private final Skill skill;
		/** Currently-kept counts: incremented on gather, decremented on drop. */
		private final Map<Integer, Long> resourceCounts = new HashMap<>();
		/** Lifetime-gathered counts: incremented on gather, never decremented (the "total"). */
		private final Map<Integer, Long> grossResourceCounts = new HashMap<>();
		/**
		 * This-session counterparts of {@link #resourceCounts}/{@link #grossResourceCounts}. Never
		 * persisted and never restored by {@code loadFromState}, so they naturally reset on logout
		 * while the lifetime maps carry across sessions.
		 */
		private final Map<Integer, Long> sessionKeptCounts = new HashMap<>();
		private final Map<Integer, Long> sessionGrossCounts = new HashMap<>();

		SkillState(Skill skill)
		{
			this.skill = skill;
		}

		public Skill getSkill() { return skill; }

		public long getResourceCount(int itemId)
		{
			return resourceCounts.getOrDefault(itemId, 0L);
		}

		/** Read-only view of currently-kept resource counts by item id (for the loot log). */
		public Map<Integer, Long> getResourceCounts()
		{
			return Collections.unmodifiableMap(resourceCounts);
		}

		/** Read-only view of lifetime-gathered (gross) resource counts — the "total". */
		public Map<Integer, Long> getGrossResourceCounts()
		{
			return Collections.unmodifiableMap(grossResourceCounts);
		}

		/** This-session currently-kept counts (for the session GP tally). */
		Map<Integer, Long> getSessionKeptCounts()
		{
			return Collections.unmodifiableMap(sessionKeptCounts);
		}

		/** This-session gross-gathered counts (for the session GP tally). */
		Map<Integer, Long> getSessionGrossCounts()
		{
			return Collections.unmodifiableMap(sessionGrossCounts);
		}

		public long getTotalResources()
		{
			long total = 0;
			for (long v : resourceCounts.values())
			{
				total += v;
			}
			return total;
		}

		void addResourceCount(int itemId, int amount)
		{
			resourceCounts.merge(itemId, (long) amount, Long::sum);
			grossResourceCounts.merge(itemId, (long) amount, Long::sum);
			sessionKeptCounts.merge(itemId, (long) amount, Long::sum);
			sessionGrossCounts.merge(itemId, (long) amount, Long::sum);
		}

		/** Restore kept (net) count only — for re-picking a dropped resource already in the gross total. */
		void addKeptOnly(int itemId, long amount)
		{
			resourceCounts.merge(itemId, amount, Long::sum);
			sessionKeptCounts.merge(itemId, amount, Long::sum);
		}

		/** Reduce a kept resource count (on drop). Floors at 0 and removes empty entries. */
		void subtractResourceCount(int itemId, int amount)
		{
			subtract(resourceCounts, itemId, amount);
			subtract(sessionKeptCounts, itemId, amount);
		}

		private static void subtract(Map<Integer, Long> counts, int itemId, int amount)
		{
			Long current = counts.get(itemId);
			if (current == null)
			{
				return;
			}
			long updated = current - amount;
			if (updated > 0)
			{
				counts.put(itemId, updated);
			}
			else
			{
				counts.remove(itemId);
			}
		}
	}

	public static final class SkillPersistence
	{
		public Map<Integer, Long> resourceCounts;
		/** Lifetime-gathered totals. Absent in pre-Phase-3.1 saves — see {@code loadFromState}. */
		public Map<Integer, Long> grossResourceCounts;
	}
}
