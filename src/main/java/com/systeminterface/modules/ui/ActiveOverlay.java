package com.systeminterface.modules.ui;

import com.systeminterface.core.SystemInterfaceConfig;
import com.systeminterface.core.SystemInterfacePlugin;
import com.systeminterface.services.drops.DropTable;
import com.systeminterface.services.drops.LootTables;
import com.systeminterface.services.lookup.BossAliases;
import com.systeminterface.services.lookup.HeldItemCache;
import com.systeminterface.services.lookup.ItemMembership;
import com.systeminterface.common.probability.LuckStatus;
import com.systeminterface.common.probability.Probability;
import com.systeminterface.common.model.BestiaryRank;
import com.systeminterface.modules.skills.PetDisplay;
import com.systeminterface.modules.skills.ResourceData;
import com.systeminterface.modules.skills.SkillTracker;
import com.systeminterface.services.state.StateTracker;
import com.systeminterface.services.state.TargetState;

import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import static net.runelite.api.MenuAction.RUNELITE_OVERLAY_CONFIG;
import static net.runelite.client.ui.overlay.OverlayManager.OPTION_CONFIGURE;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The System Panel — MMORPG-style overlay rendered to the game viewport.
 *
 * <p>Layout: a {@link SplitComponent} divides the panel into two columns —
 * <b>left</b> shows the target's identity (name, HP, examine — HP/examine
 * pending wiki fetch), <b>right</b> shows our metrics (KC, session count, rare
 * drop dry streaks). Drops with the same rate are grouped under a single
 * "1/N" header since {@code P(seen)} only depends on the rate.
 *
 * <p>Auto-hide: the panel stays visible while the player is actively
 * interacting (refreshed every {@code GameTick} from the plugin) or recently
 * received loot, then fades out after {@code config.hideAfterSeconds()} of
 * no activity. Set to 0 to keep the panel up indefinitely.
 *
 * <p>Modeled after {@code AttackStylesOverlay}: extends {@link OverlayPanel},
 * passes the owning plugin to {@code super(plugin)} for the right-click
 * config menu, and rebuilds {@code panelComponent}'s children on every
 * {@link #render} call because {@link OverlayPanel#render} clears them after
 * each frame.
 */
@Singleton
public class ActiveOverlay extends OverlayPanel
{
	private static final Color ACCENT = new Color(255, 152, 31);
	private static final Color ACCENT_SKILL = new Color(120, 200, 255);
	private static final Color DIM = new Color(170, 170, 180);
	private static final Color OSRS_BG = new Color(45, 40, 31, 235);
	private static final Color OSRS_GOLD = new Color(255, 200, 50);
	private static final int PANEL_WIDTH = 250;
	private static final int PANEL_WIDTH_COMPACT = 190;

	private final StateTracker stateTracker;
	private final LootTables lootTables;
	private final SystemInterfaceConfig config;
	private final ItemMembership itemMembership;
	private final Client client;
	private final SkillTracker skillTracker;
	private final HeldItemCache heldItemCache;

	private volatile String currentTarget;
	private volatile NPC currentNpc;
	private volatile int currentMaxHp;
	private volatile boolean freeWorld;
	/** Milliseconds since epoch of the last activity (interaction or loot). */
	private volatile long lastActivityAtMs;

	private boolean defaultLocationSeeded;

	@Inject
	public ActiveOverlay(
		SystemInterfacePlugin plugin,
		StateTracker stateTracker,
		LootTables lootTables,
		SystemInterfaceConfig config,
		ItemMembership itemMembership,
		Client client,
		SkillTracker skillTracker,
		HeldItemCache heldItemCache)
	{
		super(plugin);
		this.stateTracker = stateTracker;
		this.lootTables = lootTables;
		this.config = config;
		this.itemMembership = itemMembership;
		this.client = client;
		this.skillTracker = skillTracker;
		this.heldItemCache = heldItemCache;
		setPosition(OverlayPosition.DETACHED);
		addMenuEntry(RUNELITE_OVERLAY_CONFIG, OPTION_CONFIGURE, "System Interface overlay");
	}

	/**
	 * Set the currently-tracked target. Also refreshes the auto-hide timer.
	 * Pass {@code null} name to immediately hide.
	 *
	 * @param target display name
	 * @param npc    live NPC reference for reading HP each frame (may be null)
	 * @param maxHp  max hitpoints from NPCManager, or 0 if unknown
	 */
	public void setCurrentTarget(String target, NPC npc, int maxHp)
	{
		this.currentTarget = target;
		this.currentNpc = npc;
		this.currentMaxHp = maxHp;
		if (target != null)
		{
			refreshActivity();
		}
	}

	/**
	 * Refresh the auto-hide timer without changing the target. Called from
	 * the plugin's {@code GameTick} subscription while the local player is
	 * still interacting, and from the listener on loot received.
	 */
	public void refreshActivity()
	{
		this.lastActivityAtMs = System.currentTimeMillis();
	}

	public void setFreeWorld(boolean freeWorld)
	{
		this.freeWorld = freeWorld;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showActiveOverlay())
		{
			return null;
		}
		seedDefaultLocation();

		// One context at a time. ACTIVE skilling takes precedence (fishing spots are NPCs, so an
		// actively-fishing player must not read as combat). A merely LINGERING skill display, though,
		// must yield to a live combat target — and vice versa: the more-recent activity wins.
		final Skill active = skillTracker.getActiveSkill();
		if (active != null)
		{
			return renderSkilling(graphics, active);
		}
		final boolean combatLive = hasCombatContext();
		final Skill lingering = resolveLingeringSkill();
		if (combatLive && lingering != null)
		{
			return skillingMoreRecentThanCombat()
				? renderSkilling(graphics, lingering)
				: renderCombat(graphics);
		}
		if (combatLive)
		{
			return renderCombat(graphics);
		}
		if (lingering != null)
		{
			return renderSkilling(graphics, lingering);
		}
		return renderCombat(graphics); // no target → renderCombat returns null (overlay hidden)
	}

	/** True when a combat target is set and still within its auto-hide window. Read-only (does not clear). */
	private boolean hasCombatContext()
	{
		if (currentTarget == null)
		{
			return false;
		}
		final int timeoutSecs = config.hideAfterSeconds();
		if (timeoutSecs <= 0)
		{
			return true; // 0 = stay until the target changes
		}
		return System.currentTimeMillis() - lastActivityAtMs <= timeoutSecs * 1000L;
	}

	/** The lingering display skill within the auto-hide window (when NOT actively skilling), or null. */
	private Skill resolveLingeringSkill()
	{
		final long sinceActivity = skillTracker.getMillisSinceActivity();
		if (sinceActivity == Long.MAX_VALUE)
		{
			return null;
		}
		final int hideSeconds = config.hideAfterSeconds();
		if (hideSeconds == 0 || sinceActivity <= hideSeconds * 1000L)
		{
			return skillTracker.getDisplaySkill();
		}
		return null;
	}

	/** Whether the last skilling action is at least as recent as the last combat activity. */
	private boolean skillingMoreRecentThanCombat()
	{
		final long combatAgeMs = System.currentTimeMillis() - lastActivityAtMs;
		return skillTracker.getMillisSinceActivity() <= combatAgeMs;
	}

	/** Seeds the middle-left default once bounds are known, unless the user has dragged/saved a location. */
	private void seedDefaultLocation()
	{
		if (defaultLocationSeeded || getPreferredLocation() != null)
		{
			return;
		}
		final int vpH = client.getViewportHeight();
		final int overlayH = getBounds().height;
		if (vpH <= 0 || overlayH <= 0)
		{
			return; // wait until the viewport + overlay have real dimensions, then center exactly next frame
		}
		defaultLocationSeeded = true;
		final int y = Math.max(0, (vpH - overlayH) / 2);
		setPreferredLocation(new java.awt.Point(8, y));
	}

	private Dimension renderCombat(Graphics2D graphics)
	{
		final String target = currentTarget;
		if (target == null)
		{
			return null;
		}

		// Auto-hide check. A timeout of 0 means "stay visible until target changes".
		final int timeoutSecs = config.hideAfterSeconds();
		if (timeoutSecs > 0)
		{
			final long elapsed = System.currentTimeMillis() - lastActivityAtMs;
			if (elapsed > timeoutSecs * 1000L)
			{
				currentTarget = null;
				return null;
			}
		}

		final DropTable table = lootTables.forTarget(target);
		final TargetState state = stateTracker.get(target);
		final int kc = state != null ? state.getCurrentKc() : 0;
		final int sessionKc = state != null ? state.getSessionKc() : 0;

		final boolean compact = config.compactOverlay();
		final int width = compact ? PANEL_WIDTH_COMPACT : PANEL_WIDTH;
		panelComponent.setPreferredSize(new Dimension(width, 0));
		panelComponent.setBackgroundColor(OSRS_BG);

		// Top title — full-width.
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Target Status")
			.color(OSRS_GOLD)
			.build());

		// Enemy identity: name, level + rank, flavor, HP bar
		addSplitRow(target, ACCENT, null, DIM);

		final TargetState targetState = stateTracker.get(target);
		int combatLvl = targetState != null ? targetState.getCombatLevel() : 0;
		if (combatLvl <= 0 && table != null && table.getCombatLevel() > 0)
		{
			combatLvl = table.getCombatLevel();
			stateTracker.setCombatLevel(target, combatLvl);
		}
		final int effectiveMaxHp = currentMaxHp > 0 ? currentMaxHp
			: (table != null && table.getMaxHp() > 0 ? table.getMaxHp() : 0);

		if (combatLvl > 0)
		{
			final BestiaryRank rank = BestiaryRank.fromCombatLevel(combatLvl);
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Level " + combatLvl)
				.right("Rank(" + rank.getLabel() + ")")
				.rightColor(rank.getColor())
				.build());

			panelComponent.getChildren().add(LineComponent.builder()
				.left(compact ? "" : rank.getFlavor())
				.leftColor(DIM)
				.build());

			buildHpBar(effectiveMaxHp, width);
		}
		else
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Level ??")
				.right("Rank(??)")
				.rightColor(DIM)
				.build());

			addSplitRow("HP", null, "??", DIM);
		}

		panelComponent.getChildren().add(LineComponent.builder().left(" ").build());

		// Player metrics + grouped rare drops.
		buildPlayerRows(target, combatLvl, table, state, kc, sessionKc, compact);

		return super.render(graphics);
	}

	// ---------------------------------------------------------------------
	// Skilling context
	// ---------------------------------------------------------------------

	private Dimension renderSkilling(Graphics2D graphics, Skill active)
	{
		final boolean compact = config.compactOverlay();
		final int width = compact ? PANEL_WIDTH_COMPACT : PANEL_WIDTH;
		panelComponent.setPreferredSize(new Dimension(width, 0));
		panelComponent.setBackgroundColor(OSRS_BG);

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Active")
			.color(OSRS_GOLD)
			.build());

		final ResourceData.SkillData skillData = skillTracker.getResourceData().getSkillData(active);
		final int level = client.getRealSkillLevel(active);

		// activity → source  (e.g. "Chopping → Yew tree", "Fishing → Lobster spot")
		if (config.showActivitySource())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(capitalize(active.getName()))
				.right(sourceLabel(active))
				.rightColor(ACCENT_SKILL)
				.build());
		}
		if (config.showLevelRow())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Level").right(String.valueOf(level)).build());
		}
		if (config.showXpHrRow())
		{
			final int xpHr = skillTracker.getXpHr(active);
			if (xpHr > 0)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(compact ? "XP/hr" : "XP / hour").right(String.format("%,d", xpHr)).build());
			}
		}
		if (config.showXpGainedRow())
		{
			final int xpGained = skillTracker.getSessionXp(active);
			if (xpGained > 0)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(compact ? "XP" : "XP gained").right(String.format("%,d", xpGained)).build());
			}
		}
		if (config.showPetOddsRow() && skillData != null && level > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Pet odds")
				.right(PetDisplay.oddsText(skillData.getPetBaseChance(), level))
				.rightColor(ACCENT_SKILL)
				.build());
		}
		if (config.showActionsRow())
		{
			final int actions = skillTracker.getActions(active);
			if (actions > 0)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(compact ? "Actions" : "Actions").right(String.format("%,d", actions)).build());
			}
		}
		if (config.showGpHrRow())
		{
			final long keptGp = skillTracker.getSkillKeptGp(active);
			panelComponent.getChildren().add(LineComponent.builder()
				.left("GP / hr").right(com.systeminterface.modules.ui.PanelFormat.gp(keptGp)).build());
		}
		if (config.showTimeToLevel())
		{
			final String timeToNext = skillTracker.getTimeToNextLevel(active);
			if (timeToNext != null)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(compact ? "Next lvl" : "Time to next lvl").right(timeToNext).rightColor(DIM).build());
			}
		}
		if (config.showXpBar())
		{
			buildXpBar(active, level, width, compact);
		}

		appendSkillSpecificRows(active);

		// Output chips (text rows): tracked primary node output(s) + applicable rewards.
		buildOutputChips(active);

		return super.render(graphics);
	}

	/** One optional skill-specific row per skill (extension point; Thieving fail-rate for now). */
	private void appendSkillSpecificRows(Skill active)
	{
		if (active == Skill.THIEVING)
		{
			final Double fail = skillTracker.getThievingFailRate();
			if (fail != null)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("Fail rate").right(formatPercent(fail)).rightColor(DIM).build());
			}
		}
	}

	/** The current gathering source label: engaged object/spot resource name, or "—". */
	private String sourceLabel(Skill active)
	{
		final int objId = skillTracker.getActiveObjectId();
		if (objId != -1)
		{
			java.util.List<ResourceData.ResourceEntry> es = skillTracker.getResourceData().forObjectId(objId);
			if (!es.isEmpty()) { return es.get(0).getName(); }
		}
		final int spotId = skillTracker.getActiveFishingSpotId();
		if (spotId != -1)
		{
			return "Fishing spot";
		}
		return "—";
	}

	/** A thin XP progress bar toward the next level (reuses the combat bar glyphs). */
	private void buildXpBar(Skill active, int level, int width, boolean compact)
	{
		if (level <= 0 || level >= net.runelite.api.Experience.MAX_REAL_LEVEL)
		{
			return;
		}
		final int lo = net.runelite.api.Experience.getXpForLevel(level);
		final int hi = net.runelite.api.Experience.getXpForLevel(level + 1);
		final int xp = client.getSkillExperience(active);
		final double frac = Math.max(0.0, Math.min(1.0, (double) (xp - lo) / Math.max(1, hi - lo)));
		final int barChars = compact ? HP_BAR_CHARS_COMPACT : HP_BAR_CHARS;
		final int filled = (int) Math.round(frac * barChars);
		final StringBuilder bar = new StringBuilder(barChars);
		for (int i = 0; i < barChars; i++) { bar.append(i < filled ? BAR_FILLED : BAR_EMPTY); }
		panelComponent.getChildren().add(LineComponent.builder().left(" ").build()); // spacer so text above doesn't crowd the bar
		panelComponent.getChildren().add(LineComponent.builder()
			.left(bar.toString()).leftColor(ACCENT_SKILL).right(formatPercent(frac)).build());
		panelComponent.getChildren().add(LineComponent.builder().left(" ").build()); // space below the XP bar
	}

	/**
	 * Output for tracked, rate-based rewards only (bird nest, leaves) — the combat overlay's rich block
	 * (rate, chance-seen, progress bar, deviation, luck), driven by the XP-tracker session action count
	 * as the sample size. Guaranteed primaries are NOT shown here (the gathered node is already named in
	 * the Activity row). Track-driven and text-only (no ItemManager on the render thread).
	 */
	private void buildOutputChips(Skill active)
	{
		final java.util.Set<String> tracked = parseTracked(config.trackedItem());
		if (tracked.isEmpty())
		{
			return;
		}
		final SkillTracker.SkillState state = skillTracker.getSkillState(active);
		final boolean compact = config.compactOverlay();
		final int actions = skillTracker.getActions(active); // session actions ≈ sample size
		final int objId = skillTracker.getActiveObjectId();
		final Integer engagedNode = objId != -1 ? objId : null;

		for (ResourceData.RewardEntry rw : skillTracker.getResourceData()
			.getApplicableRewards(active, heldItemCache.heldIds(), engagedNode))
		{
			final Double rate = rw.getRate();
			if (rate == null || rate <= 0.0 || !containsIgnoreCase(tracked, rw.getName()))
			{
				continue;
			}
			final long count = state != null ? state.getResourceCount(rw.getItemId()) : 0;
			final long denom = Math.max(1L, Math.round(1.0 / rate));
			buildProgressSection(rw.getName(), rate, denom, actions,
				(int) Math.min(count, Integer.MAX_VALUE), actions, compact);
		}
	}

	private static boolean containsIgnoreCase(java.util.Set<String> set, String s)
	{
		for (String x : set) { if (x.equalsIgnoreCase(s)) { return true; } }
		return false;
	}

	private static String capitalize(String s)
	{
		if (s == null || s.isEmpty()) { return s; }
		return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
	}

	// ---------------------------------------------------------------------
	// Row helpers
	// ---------------------------------------------------------------------

	private void addSplitRow(String left, Color leftColor, String right, Color rightColor)
	{
		LineComponent.LineComponentBuilder b = LineComponent.builder().left(left);
		if (leftColor != null)
		{
			b.leftColor(leftColor);
		}
		if (right != null)
		{
			b.right(right);
		}
		if (rightColor != null)
		{
			b.rightColor(rightColor);
		}
		panelComponent.getChildren().add(b.build());
	}

	private static final int HP_BAR_CHARS = 22;
	private static final int HP_BAR_CHARS_COMPACT = 16;
	private static final char BAR_FILLED = '█';  // █
	private static final char BAR_EMPTY = '░';    // ░
	private static final Color HP_GREEN = new Color(0, 200, 0);
	private static final Color HP_RED = new Color(200, 0, 0);

	private void buildHpBar(int maxHp, int panelWidth)
	{
		if (maxHp <= 0)
		{
			addSplitRow("HP", null, "—", DIM);
			return;
		}

		final NPC npc = currentNpc;
		double fraction = 1.0;
		int currentHp = maxHp;
		boolean dead = false;

		if (npc != null)
		{
			dead = npc.isDead();
			int ratio = npc.getHealthRatio();
			int scale = npc.getHealthScale();
			if (ratio >= 0 && scale > 0)
			{
				fraction = (double) ratio / scale;
				currentHp = (int) Math.round(fraction * maxHp);
			}
			if (dead)
			{
				fraction = 0.0;
				currentHp = 0;
			}
		}



		addSplitRow("HP", null, currentHp + " / " + maxHp, null);

		final int barChars = config.compactOverlay() ? HP_BAR_CHARS_COMPACT : HP_BAR_CHARS;
		final int filled = (int) Math.round(fraction * barChars);
		final StringBuilder bar = new StringBuilder(barChars);
		for (int i = 0; i < barChars; i++)
		{
			bar.append(i < filled ? BAR_FILLED : BAR_EMPTY);
		}
		final Color barColor = dead ? HP_RED : HP_GREEN;

		panelComponent.getChildren().add(LineComponent.builder().left(" ").build()); // space above the HP bar
		panelComponent.getChildren().add(LineComponent.builder()
			//.left(formatPercent(fraction) + " " + bar.toString())
            .left(bar.toString())
			.leftColor(barColor)
			.build());
	}

	private void buildPlayerRows(String targetName, int targetCombatLvl,
		DropTable table, TargetState state, int kc, int sessionKc, boolean compact)
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left("KC")
			.right(sessionKc > 0 ? formatInt(kc) + " (+" + formatInt(sessionKc) + ")" : formatInt(kc))
			.build());

		if (table == null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Status")
				.right(config.enableWikiLookup() ? "Fetching wiki…" : "Enable wiki lookup")
				.rightColor(DIM)
				.build());
			return;
		}

		// Dedupe: keep most common rate per name (matches panel behavior).
		final boolean hideMembersDrops = freeWorld
			&& !BossAliases.isBossOrHighTier(targetName, targetCombatLvl);
		final Map<String, DropTable.Entry> byName = new LinkedHashMap<>();
		for (DropTable.Entry entry : table.getDrops())
		{
			if (entry.getName() == null || entry.getRate() <= 0.0) continue;
			if ("Nothing".equalsIgnoreCase(entry.getName())) continue;
			if (hideMembersDrops && itemMembership.isMembers(entry.getName())) continue;
			DropTable.Entry prev = byName.get(entry.getName());
			if (prev == null || entry.getRate() > prev.getRate())
			{
				byName.put(entry.getName(), entry);
			}
		}

		if (byName.isEmpty())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Status").right("No drops").rightColor(DIM).build());
			return;
		}

		// The watchlist: tracked items that THIS mob drops, in tracked order.
		final java.util.Set<String> tracked = parseTracked(config.trackedItem());
		final java.util.List<DropTable.Entry> show = new java.util.ArrayList<>();
		for (String name : tracked)
		{
			for (DropTable.Entry e : byName.values())
			{
				if (e.getName().equalsIgnoreCase(name))
				{
					show.add(e);
					break;
				}
			}
		}

		if (show.isEmpty())
		{
			return;
		}

		if (show.size() == 2)
		{
			buildCombinedSection(show, state, kc, compact);
		}
		else
		{
			for (DropTable.Entry e : show)
			{
				final String name = e.getName();
				final long denom = Math.max(1L, Math.round(1.0 / e.getRate()));
				final int drops = state != null ? state.countOf(name) : 0;
				final int streak = drops > 0 ? state.dryStreakFor(name) : kc;
				buildProgressSection(name, e.getRate(), denom, streak, drops, kc, compact);
			}
		}
	}

	/** Parses the pipe-separated tracked-item list stored in config. */
	private static java.util.Set<String> parseTracked(String csv)
	{
		java.util.Set<String> set = new java.util.LinkedHashSet<>();
		if (csv != null)
		{
			for (String s : csv.split("\\|"))
			{
				if (!s.trim().isEmpty()) set.add(s.trim());
			}
		}
		return set;
	}

	// ---------------------------------------------------------------------
	// Progress + Luck section
	// ---------------------------------------------------------------------

	private static final int PROGRESS_BAR_CHARS = 16;
	private static final char PROG_FILLED = '=';
	private static final char PROG_EMPTY = '-';

	// Milestone colors: 1x purple, 2x gold, 3x blue, 4x gray, 5x red, 6x+ dark red
	private static final Color[] MILESTONE_COLORS = {
		new Color(180, 140, 255),  // 1x — purple
		new Color(255, 200, 50),   // 2x — gold
		new Color(80, 160, 255),   // 3x — blue
		new Color(170, 170, 170),  // 4x — gray
		new Color(220, 60, 60),    // 5x — red
	};
	private static final Color MILESTONE_CURSED = new Color(140, 0, 0); // 6x+ — dark red

	private static final Color LUCK_AVERAGE = LuckStatus.AVERAGE.getColor();

	private void buildCombinedSection(java.util.List<DropTable.Entry> entries,
		TargetState state, int totalKc, boolean compact)
	{
		final DropTable.Entry e1 = entries.get(0);
		final DropTable.Entry e2 = entries.get(1);
		final long d1 = Math.max(1L, Math.round(1.0 / e1.getRate()));
		final long d2 = Math.max(1L, Math.round(1.0 / e2.getRate()));
		final int drops1 = state != null ? state.countOf(e1.getName()) : 0;
		final int drops2 = state != null ? state.countOf(e2.getName()) : 0;
		final int streak1 = drops1 > 0 ? state.dryStreakFor(e1.getName()) : totalKc;
		final int streak2 = drops2 > 0 ? state.dryStreakFor(e2.getName()) : totalKc;

		panelComponent.getChildren().add(LineComponent.builder().left(" ").build());

		// Combined header — both item names
		panelComponent.getChildren().add(LineComponent.builder()
			.left(shortenName(e1.getName()))
			.leftColor(ACCENT)
			.right("1/" + formatInt(d1))
			.rightColor(DIM)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(shortenName(e2.getName()))
			.leftColor(ACCENT)
			.right("1/" + formatInt(d2))
			.rightColor(DIM)
			.build());

		// Individual chance-seen lines
		final double pSeen1 = Probability.atLeastOne(e1.getRate(), streak1);
		final double pSeen2 = Probability.atLeastOne(e2.getRate(), streak2);
		panelComponent.getChildren().add(LineComponent.builder()
			.left(compact ? "Seen" : "Chance seen")
			.right(formatPercent(pSeen1) + " | " + formatPercent(pSeen2))
			.rightColor(DIM)
			.build());

		// Combined progress bar — use the longer dry streak
		final int maxStreak = Math.max(streak1, streak2);
		final double expectedBoth = Probability.expectedKillsForBoth(e1.getRate(), e2.getRate());
		final int expectedInt = (int) Math.round(expectedBoth);

		int multiplier = Math.max(1, (int) Math.ceil((double) maxStreak / Math.max(1, expectedInt)));
		if (maxStreak == 0)
		{
			multiplier = 1;
		}
		if (expectedInt > 0 && maxStreak > 0 && maxStreak % expectedInt == 0)
		{
			multiplier++;
		}
		addProgressBar(multiplier + "x", maxStreak, expectedInt * multiplier, compact, multiplier);

		// Combined deviation — sum of individual deviations from expected
		final int exp1 = (int) Math.round(Probability.expectedKills(e1.getRate()));
		final int exp2 = (int) Math.round(Probability.expectedKills(e2.getRate()));
		final int dev1 = streak1 - exp1;
		final int dev2 = streak2 - exp2;
		final int combinedDev = dev1 + dev2;
		final String devStr = (combinedDev >= 0 ? "+" : "") + formatInt(combinedDev);
		final Color devColor = combinedDev > 0 ? LuckStatus.UNLUCKY.getColor()
			: combinedDev < 0 ? LuckStatus.LUCKY.getColor() : LUCK_AVERAGE;
		panelComponent.getChildren().add(LineComponent.builder()
			.left(compact ? "Dev" : "Deviation")
			.right(devStr)
			.rightColor(devColor)
			.build());

		// Combined luck — weighted z-score across both items
		final double expDrops1 = Probability.expectedDrops(e1.getRate(), totalKc);
		final double expDrops2 = Probability.expectedDrops(e2.getRate(), totalKc);
		final double sd1 = Probability.stdDev(e1.getRate(), totalKc);
		final double sd2 = Probability.stdDev(e2.getRate(), totalKc);
		final String luckLabel;
		final Color luckColor;

		if ((sd1 <= 0 && sd2 <= 0) || totalKc == 0)
		{
			luckLabel = "—";
			luckColor = LUCK_AVERAGE;
		}
		else
		{
			double weightedZ = 0;
			double totalWeight = 0;
			if (sd1 > 0)
			{
				weightedZ += (drops1 - expDrops1) / sd1;
				totalWeight += 1;
			}
			if (sd2 > 0)
			{
				weightedZ += (drops2 - expDrops2) / sd2;
				totalWeight += 1;
			}
			final double z = weightedZ / totalWeight;
			final LuckStatus luck = LuckStatus.fromZScore(z);
			luckLabel = luck.getLabel();
			luckColor = luck.getColor();
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Luck")
			.right(luckLabel)
			.rightColor(luckColor)
			.build());
	}

	private void buildProgressSection(String itemName, double rate, long denom, int dryStreak,
		int actualDrops, int totalKc, boolean compact)
	{
		panelComponent.getChildren().add(LineComponent.builder().left(" ").build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left(shortenName(itemName))
			.leftColor(ACCENT)
			.right("1/" + formatInt(denom))
			.rightColor(DIM)
			.build());

		final double pSeen = Probability.atLeastOne(rate, dryStreak);
		panelComponent.getChildren().add(LineComponent.builder()
			.left(compact ? "Seen" : "Chance seen")
			.right(formatPercent(pSeen))
			.rightColor(DIM)
			.build());

		final int expected = (int) Math.round(Probability.expectedKills(rate));

		// Progress bar based on dry streak for this item
		int multiplier = Math.max(1, (int) Math.ceil((double) dryStreak / Math.max(1, expected)));
		if (dryStreak == 0)
		{
			multiplier = 1;
		}
		if (expected > 0 && dryStreak > 0 && dryStreak % expected == 0)
		{
			multiplier++;
		}

		addProgressBar(multiplier + "x", dryStreak, expected * multiplier, compact, multiplier);

		// Deviation from expected
		final int deviation = dryStreak - expected;
		final String devStr = (deviation >= 0 ? "+" : "") + formatInt(deviation);
		final Color devColor = deviation > 0 ? LuckStatus.UNLUCKY.getColor()
			: deviation < 0 ? LuckStatus.LUCKY.getColor() : LUCK_AVERAGE;
		panelComponent.getChildren().add(LineComponent.builder()
			.left(compact ? "Dev" : "Deviation")
			.right(devStr)
			.rightColor(devColor)
			.build());

		final double expectedDrops = Probability.expectedDrops(rate, totalKc);
		final double sd = Probability.stdDev(rate, totalKc);
		final String luckLabel;
		final Color luckColor;

		if (sd <= 0 || totalKc == 0)
		{
			luckLabel = "—";
			luckColor = LUCK_AVERAGE;
		}
		else
		{
			final double z = (actualDrops - expectedDrops) / sd;
			final LuckStatus luck = LuckStatus.fromZScore(z);
			luckLabel = luck.getLabel();
			luckColor = luck.getColor();
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Luck")
			.right(luckLabel)
			.rightColor(luckColor)
			.build());
	}

	private void addProgressBar(String label, int kc, int target, boolean compact, int multiplier)
	{
		final int barChars = compact ? 8 : PROGRESS_BAR_CHARS;
		final double frac = Math.min(1.0, (double) kc / Math.max(1, target));
		final int filled = (int) Math.round(frac * barChars);
		final StringBuilder bar = new StringBuilder(barChars);
		for (int i = 0; i < barChars; i++)
		{
			bar.append(i < filled ? PROG_FILLED : PROG_EMPTY);
		}

		final Color color;
		if (multiplier <= MILESTONE_COLORS.length)
		{
			color = MILESTONE_COLORS[multiplier - 1];
		}
		else
		{
			color = MILESTONE_CURSED;
		}

		final String pct = formatPercent(frac);
		panelComponent.getChildren().add(LineComponent.builder()
			.left(label + " " + bar.toString())
			.leftColor(color)
			.right(pct)
			.build());
	}

	// ---------------------------------------------------------------------
	// Formatting helpers
	// ---------------------------------------------------------------------

	private static String shortenName(String name)
	{
		if (name.startsWith("Clue scroll ("))
		{
			String type = name.substring("Clue scroll (".length());
			if (type.endsWith(")"))
			{
				type = type.substring(0, type.length() - 1);
			}
			switch (type.toLowerCase())
			{
				case "beginner": return "Clue (B)";
				case "easy":     return "Clue (E)";
				case "medium":   return "Clue (M)";
				case "hard":     return "Clue (H)";
				case "elite":    return "Clue (X)";
				default:         return "Clue (" + type.substring(0, 1).toUpperCase() + ")";
			}
		}
		if (name.startsWith("Ensouled ") && name.endsWith(" head"))
		{
			return name.replace("Ensouled ", "E. ").replace(" head", "");
		}
		return name;
	}

	private static String formatPercent(double v)
	{
		return String.format("%.1f%%", v * 100.0);
	}

	private static String formatInt(long v)
	{
		return String.format("%,d", v);
	}
}

