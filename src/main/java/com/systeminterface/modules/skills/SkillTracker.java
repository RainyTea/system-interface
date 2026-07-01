package com.systeminterface.modules.skills;

import com.systeminterface.services.acquisition.AcquisitionLedger;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
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
			ItemID.FISHINGROD_PEARL, ItemID.FISHINGROD_PEARL_OILY));
		m.put("barbarian", toolSet(ItemID.BRUT_FISHING_ROD, ItemID.FISHINGROD_PEARL_BRUT));
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
		EnumSet.of(Skill.WOODCUTTING, Skill.MINING, Skill.FISHING,
			Skill.HUNTER, Skill.FARMING, Skill.THIEVING)
	);

	private static final Set<Integer> ROGUE_OUTFIT_ITEMS = toolSet(
		ItemID.ROGUESDEN_HELM,
		ItemID.ROGUESDEN_BODY,
		ItemID.ROGUESDEN_LEGS,
		ItemID.ROGUESDEN_GLOVES,
		ItemID.ROGUESDEN_BOOTS);

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
	private final AcquisitionLedger<GatherSource> acquisitionLedger;
	// Per-unit GP price source. In production this reads the GE price via ItemManager (only safe
	// on the client thread); tests inject a stub so the ledger logic needs no live client.
	private final IntToLongFunction priceFn;

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
	private volatile Set<Integer> heldItemIds = Collections.emptySet();
	private volatile int rogueOutfitPieces;

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
	private final Map<Skill, ActionSource> recentActionSources = new HashMap<>();
	private final Map<Skill, Long> lastActionSignalTicks = new HashMap<>();
	private final Map<GatherSource, Long> lastSourceActionSignalTicks = new HashMap<>();
	private static final long SOURCE_CONTEXT_WINDOW_TICKS = 50;

	// Shared provenance / kept-vs-finalized ledger. It tracks the live item accounting; this
	// class applies emitted changes to skill state and UI generation counters.
	// Last observed XP per tracked skill, to tell a real XP gain from the login baseline read.
	private final Map<Skill, Integer> lastXp = new HashMap<>();

	private final AtomicLong generation = new AtomicLong(0);

	private final Map<Skill, SkillState> skillStates = new HashMap<>();

	@Inject
	public SkillTracker(Client client, ResourceData resourceData, ItemManager itemManager)
	{
		// Value items via the GE price. Only call this on the client thread (see recomputeSkillingGp).
		this(client, resourceData, itemManager, itemManager::getItemPrice);
	}

	/**
	 * Test seam: supply the resource data and a price function directly, with no live
	 * {@code Client}/{@code ItemManager}. The provenance/ledger logic is driven through the
	 * package-private {@code applyInventoryDiff}/{@code recordGatherSignal}/{@code onItemDropped}
	 * methods, none of which touch {@code client}.
	 */
	SkillTracker(ResourceData resourceData, IntToLongFunction priceFn)
	{
		this(null, resourceData, null, priceFn);
	}

	private SkillTracker(Client client, ResourceData resourceData, ItemManager itemManager,
		IntToLongFunction priceFn)
	{
		this.client = client;
		this.resourceData = resourceData;
		this.itemManager = itemManager;
		this.priceFn = priceFn;
		this.acquisitionLedger = new AcquisitionLedger<>(this::sourceForResourceItem,
			this::resourceItemMatchesSkill, SIGNAL_WINDOW_TICKS);
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

	/** Clears this-session skilling resource counters while preserving lifetime counts. */
	public void resetSessionProgress()
	{
		for (SkillState state : skillStates.values())
		{
			state.resetSessionCounts();
		}
		sessionSkillingKeptGp = 0;
		sessionSkillingTotalGp = 0;
		generation.incrementAndGet();
	}

	/**
	 * Re-values all gathered resources into the lifetime and this-session GP totals.
	 * MUST be called on the client thread in production — {@link #priceFn} reads GE prices via
	 * {@link ItemManager}.
	 */
	private void recomputeSkillingGp()
	{
		long kept = 0;
		long total = 0;
		long sessionKept = 0;
		long sessionTotal = 0;
		for (SkillState st : skillStates.values())
		{
			kept += ResourceValuer.totalValue(st.getResourceCounts(), priceFn);
			total += ResourceValuer.totalValue(st.getGrossResourceCounts(), priceFn);
			sessionKept += ResourceValuer.totalValue(st.getSessionKeptCounts(), priceFn);
			sessionTotal += ResourceValuer.totalValue(st.getSessionGrossCounts(), priceFn);
		}
		skillingKeptGp = kept;
		skillingTotalGp = total;
		sessionSkillingKeptGp = sessionKept;
		sessionSkillingTotalGp = sessionTotal;
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
			activeFishingSpotId = npcId;
			setActiveSkill(Skill.FISHING);
			markActivity();
		}
		return true;
	}

	private void markActivity()
	{
		lastActivityTick = client == null ? 0L : client.getTickCount();
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
		return hasAnyHeldItem(tools);
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
		return hasAnyHeldItem(secondaries);
	}

	public boolean hasAnyItem(java.util.List<Integer> itemIds)
	{
		if (itemIds == null || itemIds.isEmpty())
		{
			return true;
		}
		return hasAnyHeldItem(itemIds);
	}

	/**
	 * Refreshes inventory/equipment requirement state on the client thread. UI render paths read
	 * only {@link #heldItemIds}, because RuneLite client containers are not safe to query from the
	 * Swing panel or overlay filtering code.
	 */
	public void refreshHeldItems()
	{
		if (client == null)
		{
			heldItemIds = Collections.emptySet();
			rogueOutfitPieces = 0;
			return;
		}
		final Set<Integer> ids = new HashSet<>();
		addHeldItems(ids, client.getItemContainer(InventoryID.INV));
		final ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		addHeldItems(ids, worn);
		rogueOutfitPieces = countRogueOutfitPieces(worn);
		heldItemIds = Collections.unmodifiableSet(ids);
	}

	void setHeldItemIdsForTest(int... itemIds)
	{
		final Set<Integer> ids = new HashSet<>();
		for (int itemId : itemIds)
		{
			ids.add(itemId);
		}
		heldItemIds = Collections.unmodifiableSet(ids);
	}

	void setRogueOutfitPiecesForTest(int pieces)
	{
		rogueOutfitPieces = Math.max(0, Math.min(5, pieces));
	}

	public int getRogueOutfitPieces()
	{
		return rogueOutfitPieces;
	}

	public static int rogueOutfitActivationChancePercent(int pieces)
	{
		if (pieces <= 0)
		{
			return 0;
		}
		if (pieces >= 5)
		{
			return 100;
		}
		return pieces * 15;
	}

	public void onResourceAction(Skill skill)
	{
		if (skill == null)
		{
			return;
		}
		setActiveSkill(skill);
		markActivity();
	}

	private boolean hasFishingTool()
	{
		return hasAnyHeldItem(FISHING_TOOLS);
	}

	private boolean hasAnyHeldItem(Iterable<Integer> itemIds)
	{
		final Set<Integer> held = heldItemIds;
		for (Integer itemId : itemIds)
		{
			if (itemId != null && held.contains(itemId))
			{
				return true;
			}
		}
		return false;
	}

	private void addHeldItems(Set<Integer> ids, ItemContainer container)
	{
		if (container == null)
		{
			return;
		}
		final Item[] items = container.getItems();
		if (items == null)
		{
			return;
		}
		for (Item item : items)
		{
			if (item != null && item.getId() >= 0 && item.getQuantity() > 0)
			{
				ids.add(canonicalItemId(item.getId()));
			}
		}
	}

	private int countRogueOutfitPieces(ItemContainer worn)
	{
		if (worn == null)
		{
			return 0;
		}
		int count = 0;
		final Item[] items = worn.getItems();
		if (items == null)
		{
			return 0;
		}
		for (Item item : items)
		{
			if (item != null && item.getQuantity() > 0
				&& ROGUE_OUTFIT_ITEMS.contains(canonicalItemId(item.getId())))
			{
				count++;
			}
		}
		return Math.min(5, count);
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
		activeFishingSpotId = npcId;
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
	 *       action signal exists in {@link AcquisitionLedger}. Without one the gain is buffered and
	 *       discarded next tick, and also recorded as an "unexplained" gain so {@link #reconcileTick}
	 *       can recognise a re-pickup of one of my drops (GE collects / bank withdrawals / trades
	 *       look identical here and so never count on their own).</li>
	 *   <li><b>Bank/use finalization</b> — after the diff, the ledger clamps live acquired units to the
	 *       current inventory. Gathered units that left without a drop (banked, used, cooked) drop out
	 *       of the live set: they stay counted as kept but can no longer be deducted by a later drop.</li>
	 * </ul>
	 *
	 * <p>Package-private and free of {@code client} so the ledger is unit-testable.
	 */
	void applyInventoryDiff(Map<Integer, Integer> current, long tick)
	{
		applyLedgerChanges(acquisitionLedger.applyInventoryDiff(current, tick));
	}

	/** Records {@code qty} of {@code itemId} as gathered for {@code skill} (kept + gross + session). */
	private void creditGather(Skill skill, String sourceName, String sourceAction, int itemId, int qty)
	{
		skillStates.computeIfAbsent(skill, SkillState::new).addResourceCount(itemId, qty, sourceName, sourceAction);
		log.debug("Gathered {}x itemId={} ({})", qty, itemId, skill);
	}

	private GatherSource sourceForResourceItem(int itemId)
	{
		final ResourceData.ResourceEntry entry = resourceData.forItemId(itemId);
		return entry == null ? null : new GatherSource(entry.getSkill(), null, null);
	}

	private boolean resourceItemMatchesSkill(GatherSource source, int itemId)
	{
		return source != null && resourceData.itemMatchesSource(source.skill, source.name, source.action, itemId);
	}

	private void applyLedgerChanges(java.util.List<AcquisitionLedger.Change<GatherSource>> changes)
	{
		if (changes.isEmpty())
		{
			return;
		}
		for (AcquisitionLedger.Change<GatherSource> change : changes)
		{
			final GatherSource source = change.getSource();
			if (source == null || source.skill == null)
			{
				continue;
			}
			final Skill skill = source.skill;
			final int itemId = change.getItemId();
				final int qty = change.getQty();
				switch (change.getType())
				{
				case ACQUIRED:
					creditGather(skill, source.name, source.action, itemId, qty);
					break;
				case DROPPED:
					skillStates.computeIfAbsent(skill, SkillState::new).subtractResourceCount(itemId, qty, source.name);
					log.debug("Dropped {}x itemId={} ({}) - kept -{}, awaiting despawn", qty, itemId, skill, qty);
					break;
				case RESTORED:
					skillStates.computeIfAbsent(skill, SkillState::new).addKeptOnly(itemId, qty, source.name);
					break;
				default:
					break;
			}
		}
		recomputeSkillingGp();
		generation.incrementAndGet();
	}

	/**
	 * Records a gathering action signal for {@code skill} at {@code tick} (a "You catch ..." chat
	 * or a tracked-skill XP gain), then flushes any inventory gains that were buffered awaiting it.
	 * Package-private: the public {@link #onChatMessage}/{@link #onStatChanged} handlers supply the
	 * live tick.
	 */
	void recordGatherSignal(Skill skill, long tick)
	{
		recordGatherSignal(skill, tick, 0);
	}

	void recordGatherSignal(Skill skill, long tick, int xpDelta)
	{
		GatherSource source = sourceForSignal(skill, tick);
		boolean changed = false;
		if (skill != null)
		{
			final SkillState state = skillStates.computeIfAbsent(skill, SkillState::new);
			if (recordActionSignal(lastActionSignalTicks, skill, tick))
			{
				state.recordAction(xpDelta);
				changed = true;
			}
			else if (xpDelta > 0)
			{
				state.addXp(xpDelta);
				changed = true;
			}
		}
		if (source.name != null)
		{
			final SkillState state = skillStates.computeIfAbsent(skill, SkillState::new);
			if (recordActionSignal(lastSourceActionSignalTicks, source, tick))
			{
				state.recordSourceAction(source.name, source.action, xpDelta);
				changed = true;
			}
			else if (xpDelta > 0)
			{
				state.addSourceXp(source.name, xpDelta);
				changed = true;
			}
		}
		applyLedgerChanges(acquisitionLedger.recordSignal(source, tick));
		if (changed)
		{
			generation.incrementAndGet();
		}
	}

	private static <K> boolean recordActionSignal(Map<K, Long> lastTicks, K key, long tick)
	{
		final Long previous = lastTicks.put(key, tick);
		return previous == null || previous.longValue() != tick;
	}

	void recordTrackedXpSignal(Skill skill, long tick)
	{
		recordTrackedXpSignal(skill, tick, 0);
	}

	void recordTrackedXpSignal(Skill skill, long tick, int xpDelta)
	{
		setActiveSkill(skill);
		lastActivityTick = tick;
		lastActivityMillis = System.currentTimeMillis();
		recordGatherSignal(skill, tick, xpDelta);
	}

	/** Discards buffered gains that never got a coincident signal (GE collect, bank withdraw, trade). */
	void expireStalePending(long tick)
	{
		acquisitionLedger.expireStalePending(tick);
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
		applyLedgerChanges(acquisitionLedger.drop(itemId, qty, location, tick));
	}

	public void onItemRemovedFinalized(int itemId, int qty)
	{
		applyLedgerChanges(acquisitionLedger.dropFinalized(itemId, qty));
	}

	public void onSourceAction(Skill skill, String sourceName)
	{
		onSourceAction(skill, sourceName, null);
	}

	public void onSourceAction(Skill skill, String sourceName, String action)
	{
		if (skill == null || sourceName == null)
		{
			return;
		}
		final String clean = sourceName.trim();
		if (clean.isEmpty())
		{
			return;
		}
		final long tick = client == null ? 0L : client.getTickCount();
		recentActionSources.put(skill, new ActionSource(clean, normalizeActionLabel(action), tick));
	}

	public void resetSkillSource(Skill skill, String sourceName)
	{
		final SkillState state = skillStates.get(skill);
		if (state == null)
		{
			return;
		}
		state.resetSource(sourceName);
		lastSourceActionSignalTicks.keySet().removeIf(source ->
			source.skill == skill && Objects.equals(source.name, sourceName));
		recomputeSkillingGp();
		generation.incrementAndGet();
	}

	public void resetSkill(Skill skill)
	{
		if (skill == null || skillStates.remove(skill) == null)
		{
			return;
		}
		lastActionSignalTicks.remove(skill);
		lastSourceActionSignalTicks.keySet().removeIf(source -> source.skill == skill);
		recomputeSkillingGp();
		generation.incrementAndGet();
	}

	/**
	 * One of my dropped ground items of {@code itemId} (qty {@code qty}) despawned at {@code location}
	 * — either picked up or timed out. Buffered for end-of-tick classification in {@link #reconcileTick}.
	 *
	 * <p>Package-private and free of {@code client} so the ledger is unit-testable.
	 */
	void onGroundResourceDespawned(int itemId, int qty, Object location, long tick)
	{
		acquisitionLedger.groundDespawned(itemId, qty, location, tick);
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
		applyLedgerChanges(acquisitionLedger.reconcileTick(tick));
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
		recordThievingFailure(message, client == null ? 0L : client.getTickCount());
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
		final int previous = lastXp.getOrDefault(skill, -1);
		if (isXpGain(skill, event.getXp()))
		{
			recordTrackedXpSignal(skill, client.getTickCount(), Math.max(0, event.getXp() - previous));
		}
	}

	void recordThievingFailure(String message, long tick)
	{
		if (!isPickpocketFailureMessage(message))
		{
			return;
		}
		final GatherSource source = sourceForSignal(Skill.THIEVING, tick);
		if (source.name == null || !"Pickpocket".equalsIgnoreCase(source.action))
		{
			return;
		}
		skillStates.computeIfAbsent(Skill.THIEVING, SkillState::new)
			.recordSourceFailure(source.name, source.action);
		generation.incrementAndGet();
	}

	static boolean isPickpocketFailureMessage(String message)
	{
		return message != null && message.matches("(?i)^You fail to pick .* pocket\\.$");
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
		activeSkill = null;
		lastActiveSkill = null;
		activeFishingSpotId = -1;
		acquisitionLedger.reset();
		// Clear the XP baseline so the next session starts fresh.
		lastXp.clear();
		recentActionSources.clear();
		lastActionSignalTicks.clear();
		lastSourceActionSignalTicks.clear();
		// Reset the activity clock so the in-game overlay doesn't linger / reappear on the
		// next login. The panel log re-surfaces the persisted skill via loadFromState, but
		// the overlay only shows after real activity this session.
		lastActivityTick = 0;
		lastActivityMillis = 0;
		skillingKeptGp = 0;
		skillingTotalGp = 0;
		sessionSkillingKeptGp = 0;
		sessionSkillingTotalGp = 0;
		heldItemIds = Collections.emptySet();
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
				state.successfulActions = Math.max(0L, p.successfulActions);
				state.xpGained = Math.max(0L, p.xpGained);
				state.firstActionMillis = Math.max(0L, p.firstActionMillis);
				if (p.sources != null)
				{
					for (Map.Entry<String, SkillSourcePersistence> sourceEntry : p.sources.entrySet())
					{
						SourceState sourceState = new SourceState(sourceEntry.getKey());
						SkillSourcePersistence sp = sourceEntry.getValue();
						if (sp.resourceCounts != null)
						{
							sourceState.resourceCounts.putAll(sp.resourceCounts);
						}
						if (sp.grossResourceCounts != null)
						{
							sourceState.grossResourceCounts.putAll(sp.grossResourceCounts);
						}
						else
						{
							sourceState.grossResourceCounts.putAll(sourceState.resourceCounts);
						}
						sourceState.lastSeen = sp.lastSeen;
						sourceState.sourceAction = normalizeActionLabel(sp.sourceAction);
						sourceState.activityType = normalizeActivityLabel(sp.activityType);
						sourceState.successfulActions = Math.max(0L, sp.successfulActions);
						sourceState.failedActions = Math.max(0L, sp.failedActions);
						sourceState.xpGained = Math.max(0L, sp.xpGained);
						sourceState.firstActionMillis = Math.max(0L, sp.firstActionMillis);
						state.sourceStates.put(sourceEntry.getKey(), sourceState);
					}
				}
				state.lastSeen = p.lastSeen;
				skillStates.put(skill, state);
				// Surface the persisted skill in the panel log immediately on load.
				lastActiveSkill = skill;
			}
			catch (IllegalArgumentException ignored)
			{
			}
		}
		recomputeSkillingGp();
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
			p.successfulActions = s.successfulActions;
			p.xpGained = s.xpGained;
			p.firstActionMillis = s.firstActionMillis;
			if (!s.sourceStates.isEmpty())
			{
				p.sources = new HashMap<>();
				for (Map.Entry<String, SourceState> sourceEntry : s.sourceStates.entrySet())
				{
					SourceState source = sourceEntry.getValue();
					SkillSourcePersistence sp = new SkillSourcePersistence();
					sp.resourceCounts = new HashMap<>(source.resourceCounts);
					sp.grossResourceCounts = new HashMap<>(source.grossResourceCounts);
					sp.sourceAction = source.sourceAction;
					sp.activityType = source.activityType;
					sp.successfulActions = source.successfulActions;
					sp.failedActions = source.failedActions;
					sp.xpGained = source.xpGained;
					sp.firstActionMillis = source.firstActionMillis;
					sp.lastSeen = source.lastSeen;
					p.sources.put(sourceEntry.getKey(), sp);
				}
			}
			p.lastSeen = s.lastSeen;
			result.put(entry.getKey().name().toLowerCase(), p);
		}
		return result;
	}

	private GatherSource sourceForSignal(Skill skill, long tick)
	{
		ActionSource source = recentActionSources.get(skill);
		if (source == null)
		{
			return new GatherSource(skill, null, null);
		}
		if (tick >= source.tick && tick - source.tick <= SOURCE_CONTEXT_WINDOW_TICKS)
		{
			return new GatherSource(skill, source.name, source.action);
		}
		return new GatherSource(skill, null, null);
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
				snapshot.merge(canonicalItemId(item.getId()), item.getQuantity(), Integer::sum);
			}
		}
		return snapshot;
	}

	private int canonicalItemId(int itemId)
	{
		return itemManager == null ? itemId : itemManager.canonicalize(itemId);
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
		private final Map<String, SourceState> sourceStates = new HashMap<>();
		private long successfulActions;
		private long xpGained;
		private long firstActionMillis;
		private long lastSeen;

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

		public long getLastSeen()
		{
			return lastSeen;
		}

		public long getSuccessfulActions()
		{
			return successfulActions;
		}

		public long getXpGained()
		{
			return xpGained;
		}

		public long getFirstActionMillis()
		{
			return firstActionMillis;
		}

		void recordAction(int xpDelta)
		{
			touch();
			if (firstActionMillis == 0L)
			{
				firstActionMillis = System.currentTimeMillis();
			}
			successfulActions++;
			if (xpDelta > 0)
			{
				xpGained += xpDelta;
			}
		}

		void addXp(int xpDelta)
		{
			if (xpDelta <= 0)
			{
				return;
			}
			touch();
			if (firstActionMillis == 0L)
			{
				firstActionMillis = System.currentTimeMillis();
			}
			xpGained += xpDelta;
		}

		void addResourceCount(int itemId, int amount, String sourceName, String sourceAction)
		{
			touch();
			resourceCounts.merge(itemId, (long) amount, Long::sum);
			grossResourceCounts.merge(itemId, (long) amount, Long::sum);
			sessionKeptCounts.merge(itemId, (long) amount, Long::sum);
			sessionGrossCounts.merge(itemId, (long) amount, Long::sum);
			sourceState(sourceName).addResourceCount(itemId, amount, sourceAction);
		}

		/** Restore kept (net) count only — for re-picking a dropped resource already in the gross total. */
		void addKeptOnly(int itemId, long amount, String sourceName)
		{
			touch();
			resourceCounts.merge(itemId, amount, Long::sum);
			sessionKeptCounts.merge(itemId, amount, Long::sum);
			sourceState(sourceName).addKeptOnly(itemId, amount);
		}

		/** Reduce a kept resource count (on drop). Floors at 0 and removes empty entries. */
		void subtractResourceCount(int itemId, int amount, String sourceName)
		{
			touch();
			subtract(resourceCounts, itemId, amount);
			subtract(sessionKeptCounts, itemId, amount);
			sourceState(sourceName).subtractResourceCount(itemId, amount);
		}

		public Map<String, SourceState> getSourceStates()
		{
			return Collections.unmodifiableMap(sourceStates);
		}

		void recordSourceAction(String sourceName, String sourceAction, int xpDelta)
		{
			sourceState(sourceName).recordAction(sourceAction, xpDelta);
		}

		void addSourceXp(String sourceName, int xpDelta)
		{
			sourceState(sourceName).addXp(xpDelta);
		}

		void recordSourceFailure(String sourceName, String sourceAction)
		{
			sourceState(sourceName).recordFailure(sourceAction);
		}

		private SourceState sourceState(String sourceName)
		{
			if (sourceName == null || sourceName.isEmpty())
			{
				return SourceState.NOOP;
			}
			return sourceStates.computeIfAbsent(sourceName, SourceState::new);
		}

		private void touch()
		{
			lastSeen = System.currentTimeMillis();
		}

		void resetSource(String sourceName)
		{
			final SourceState source = sourceStates.remove(sourceName);
			if (source == null)
			{
				return;
			}
			for (Map.Entry<Integer, Long> entry : source.resourceCounts.entrySet())
			{
				subtract(resourceCounts, entry.getKey(), entry.getValue());
				subtract(sessionKeptCounts, entry.getKey(), entry.getValue());
			}
			for (Map.Entry<Integer, Long> entry : source.grossResourceCounts.entrySet())
			{
				subtract(grossResourceCounts, entry.getKey(), entry.getValue());
				subtract(sessionGrossCounts, entry.getKey(), entry.getValue());
			}
		}

		void resetSessionCounts()
		{
			sessionKeptCounts.clear();
			sessionGrossCounts.clear();
		}

		private static void subtract(Map<Integer, Long> counts, int itemId, int amount)
		{
			subtract(counts, itemId, (long) amount);
		}

		private static void subtract(Map<Integer, Long> counts, int itemId, long amount)
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
		public Map<String, SkillSourcePersistence> sources;
		public long successfulActions;
		public long xpGained;
		public long firstActionMillis;
		public long lastSeen;
	}

	public static final class SkillSourcePersistence
	{
		public Map<Integer, Long> resourceCounts;
		public Map<Integer, Long> grossResourceCounts;
		public String sourceAction;
		public String activityType;
		public long successfulActions;
		public long failedActions;
		public long xpGained;
		public long firstActionMillis;
		public long lastSeen;
	}

	private static String normalizeActionLabel(String action)
	{
		if (action == null)
		{
			return null;
		}
		final String trimmed = action.trim();
		return trimmed.isEmpty() ? null : trimmed.replace(' ', '-');
	}

	private static String normalizeActivityLabel(String activityType)
	{
		if (activityType == null)
		{
			return null;
		}
		switch (activityType)
		{
			case "Pickpocket":
			case "Stall":
				return activityType;
			default:
				return null;
		}
	}

	private static String activityTypeForAction(String action)
	{
		final String normalized = normalizeActionLabel(action);
		if ("Pickpocket".equalsIgnoreCase(normalized))
		{
			return "Pickpocket";
		}
		if ("Steal-from".equalsIgnoreCase(normalized))
		{
			return "Stall";
		}
		return null;
	}

	private static final class ActionSource
	{
		private final String name;
		private final String action;
		private final long tick;

		private ActionSource(String name, String action, long tick)
		{
			this.name = name;
			this.action = action;
			this.tick = tick;
		}
	}

	private static final class GatherSource
	{
		private final Skill skill;
		private final String name;
		private final String action;

		private GatherSource(Skill skill, String name, String action)
		{
			this.skill = skill;
			this.name = name;
			this.action = action;
		}

		@Override
		public boolean equals(Object other)
		{
			if (this == other)
			{
				return true;
			}
			if (!(other instanceof GatherSource))
			{
				return false;
			}
			GatherSource that = (GatherSource) other;
			return skill == that.skill
				&& Objects.equals(name, that.name)
				&& Objects.equals(action, that.action);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(skill, name, action);
		}
	}

	public static class SourceState
	{
		private static final SourceState NOOP = new SourceState(null)
		{
			@Override void recordAction(String sourceAction, int xpDelta) {}
			@Override void addXp(int xpDelta) {}
			@Override void recordFailure(String sourceAction) {}
			@Override void addResourceCount(int itemId, int amount, String sourceAction) {}
			@Override void addKeptOnly(int itemId, long amount) {}
			@Override void subtractResourceCount(int itemId, int amount) {}
		};

		private final String sourceName;
		private final Map<Integer, Long> resourceCounts = new HashMap<>();
		private final Map<Integer, Long> grossResourceCounts = new HashMap<>();
		private String sourceAction;
		private String activityType;
		private long successfulActions;
		private long failedActions;
		private long xpGained;
		private long firstActionMillis;
		private long lastSeen;

		SourceState(String sourceName)
		{
			this.sourceName = sourceName;
		}

		public String getSourceName() { return sourceName; }
		public Map<Integer, Long> getResourceCounts() { return Collections.unmodifiableMap(resourceCounts); }
		public Map<Integer, Long> getGrossResourceCounts() { return Collections.unmodifiableMap(grossResourceCounts); }
		public String getSourceAction() { return sourceAction; }
		public String getActivityType() { return activityType; }
		public long getSuccessfulActions() { return successfulActions; }
		public long getFailedActions() { return failedActions; }
		public long getAttemptedActions() { return successfulActions + failedActions; }
		public long getXpGained() { return xpGained; }
		public long getFirstActionMillis() { return firstActionMillis; }
		public long getLastSeen() { return lastSeen; }

		void recordAction(String sourceAction, int xpDelta)
		{
			touch();
			markStarted();
			rememberAction(sourceAction);
			successfulActions++;
			if (xpDelta > 0)
			{
				xpGained += xpDelta;
			}
		}

		void addXp(int xpDelta)
		{
			if (xpDelta <= 0)
			{
				return;
			}
			touch();
			markStarted();
			xpGained += xpDelta;
		}

		void recordFailure(String sourceAction)
		{
			touch();
			markStarted();
			rememberAction(sourceAction);
			failedActions++;
		}

		void addResourceCount(int itemId, int amount, String sourceAction)
		{
			touch();
			rememberAction(sourceAction);
			resourceCounts.merge(itemId, (long) amount, Long::sum);
			grossResourceCounts.merge(itemId, (long) amount, Long::sum);
		}

		void addKeptOnly(int itemId, long amount)
		{
			touch();
			resourceCounts.merge(itemId, amount, Long::sum);
		}

		void subtractResourceCount(int itemId, int amount)
		{
			touch();
			SkillState.subtract(resourceCounts, itemId, amount);
		}

		private void touch()
		{
			lastSeen = System.currentTimeMillis();
		}

		private void markStarted()
		{
			if (firstActionMillis == 0L)
			{
				firstActionMillis = System.currentTimeMillis();
			}
		}

		private void rememberAction(String sourceAction)
		{
			final String normalized = normalizeActionLabel(sourceAction);
			if (normalized == null)
			{
				return;
			}
			this.sourceAction = normalized;
			final String activity = activityTypeForAction(normalized);
			if (activity != null)
			{
				this.activityType = activity;
			}
		}
	}
}
