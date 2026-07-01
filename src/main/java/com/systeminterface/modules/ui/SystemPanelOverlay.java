package com.systeminterface.modules.ui;

import com.systeminterface.core.SystemInterfaceConfig;
import com.systeminterface.core.SystemInterfacePlugin;
import com.systeminterface.modules.skills.PetDisplay;
import com.systeminterface.modules.skills.ResourceData;
import com.systeminterface.modules.skills.SkillTracker;
import com.systeminterface.services.drops.DropTable;
import com.systeminterface.services.drops.LootTables;
import com.systeminterface.services.lookup.BossAliases;
import com.systeminterface.services.lookup.ItemMembership;
import com.systeminterface.common.probability.LuckStatus;
import com.systeminterface.common.probability.Probability;
import com.systeminterface.common.model.BestiaryRank;
import com.systeminterface.services.state.StateTracker;
import com.systeminterface.services.state.TargetState;

import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
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
public class SystemPanelOverlay extends OverlayPanel
{
	private static final Color ACCENT = new Color(255, 152, 31);
	private static final Color DIM = new Color(170, 170, 180);
	private static final Color OSRS_BG = new Color(45, 40, 31, 235);
	private static final Color OSRS_GOLD = new Color(255, 200, 50);
	private static final int PANEL_WIDTH = 250;
	private static final int PANEL_WIDTH_COMPACT = 190;

	private final StateTracker stateTracker;
	private final SkillTracker skillTracker;
	private final LootTables lootTables;
	private final SystemInterfaceConfig config;
	private final ItemMembership itemMembership;
	private final ResourceData resourceData;
	private final SystemPopupBuffer popupBuffer = new SystemPopupBuffer();

	private volatile String currentTarget;
	private volatile NPC currentNpc;
	private volatile int currentMaxHp;
	private volatile Skill currentSourceSkill;
	private volatile String currentSourceAction;
	private volatile String currentSourceName;
	private volatile List<ResourceData.ResourceEntry> currentSourceEntries;
	private volatile String currentResourceName;
	private volatile ResourceData.ResourceEntry currentResourceEntry;
	private volatile List<ResourceData.ResourceEntry> currentResourceEntries;
	private volatile boolean freeWorld;
	/** Milliseconds since epoch of the last activity (interaction or loot). */
	private volatile long lastActivityAtMs;

	@Inject
	public SystemPanelOverlay(
		SystemInterfacePlugin plugin,
		StateTracker stateTracker,
		SkillTracker skillTracker,
		LootTables lootTables,
		SystemInterfaceConfig config,
		ItemMembership itemMembership,
		ResourceData resourceData)
	{
		super(plugin);
		this.stateTracker = stateTracker;
		this.skillTracker = skillTracker;
		this.lootTables = lootTables;
		this.config = config;
		this.itemMembership = itemMembership;
		this.resourceData = resourceData;
		setPosition(OverlayPosition.TOP_LEFT);
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
		this.currentSourceSkill = null;
		this.currentSourceAction = null;
		this.currentSourceName = null;
		this.currentSourceEntries = null;
		clearSkillResourceTarget();
		if (target != null)
		{
			refreshActivity();
		}
	}

	public void setCurrentSkillSourceTarget(Skill skill, String action, String sourceName,
		List<ResourceData.ResourceEntry> entries)
	{
		this.currentSourceSkill = skill;
		this.currentSourceAction = action;
		this.currentSourceName = sourceName;
		this.currentSourceEntries = entries;
		this.currentTarget = null;
		this.currentNpc = null;
		this.currentMaxHp = 0;
		clearSkillResourceTarget();
		if (skill != null && action != null && sourceName != null && entries != null && !entries.isEmpty())
		{
			refreshActivity();
		}
	}

	public void setCurrentSkillResourceTarget(String resourceName, ResourceData.ResourceEntry resource,
		List<ResourceData.ResourceEntry> entries)
	{
		this.currentResourceName = resourceName;
		this.currentResourceEntry = resource;
		this.currentResourceEntries = entries;
		this.currentTarget = null;
		this.currentNpc = null;
		this.currentMaxHp = 0;
		clearSkillSourceTarget();
		if (resourceName != null && resource != null)
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

	public void showSystemMessage(String message)
	{
		popupBuffer.push(message, System.currentTimeMillis());
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showSystemPanel())
		{
			return null;
		}

		final String popup = popupBuffer.activeMessage(System.currentTimeMillis());
		if (popup != null)
		{
			return renderSystemPopup(graphics, popup);
		}

		if (config.skillingTrackingDisplay() == SystemInterfaceConfig.SkillingTrackingDisplay.TARGET_STATUS_OVERLAY
			&& currentResourceName != null && currentResourceEntry != null)
		{
			if (isSkillingExpired(currentResourceEntry.getSkill()))
			{
				clearSkillResourceTarget();
				return null;
			}
			return renderSkillResource(graphics);
		}

		if (config.skillingTrackingDisplay() == SystemInterfaceConfig.SkillingTrackingDisplay.TARGET_STATUS_OVERLAY
			&& currentSourceSkill != null && currentSourceAction != null && currentSourceName != null)
		{
			if (isSkillingExpired(currentSourceSkill))
			{
				setCurrentSkillSourceTarget(null, null, null, null);
				return null;
			}
			return renderSkillSource(graphics);
		}

		if (config.skillingTrackingDisplay() == SystemInterfaceConfig.SkillingTrackingDisplay.TARGET_STATUS_OVERLAY)
		{
			final Skill displaySkill = recentSkillToRender();
			final List<ResourceData.ResourceEntry> entries = resourceData.skillWideStatisticalRewards(displaySkill);
			if (displaySkill != null && !entries.isEmpty())
			{
				return renderSkill(graphics, displaySkill, entries);
			}
		}

		final String target = currentTarget;
		if (target == null)
		{
			return null;
		}

		// Auto-hide check. A timeout of 0 means "stay visible until target changes".
		if (isExpired())
		{
			currentTarget = null;
			return null;
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

	private Dimension renderSystemPopup(Graphics2D graphics, String message)
	{
		final boolean compact = config.compactOverlay();
		final int width = compact ? PANEL_WIDTH_COMPACT : PANEL_WIDTH;
		panelComponent.setPreferredSize(new Dimension(width, 0));
		panelComponent.setBackgroundColor(OSRS_BG);
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("System Message")
			.color(OSRS_GOLD)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(message)
			.leftColor(ACCENT)
			.build());
		return super.render(graphics);
	}

	private void clearSkillSourceTarget()
	{
		this.currentSourceSkill = null;
		this.currentSourceAction = null;
		this.currentSourceName = null;
		this.currentSourceEntries = null;
	}

	private void clearSkillResourceTarget()
	{
		this.currentResourceName = null;
		this.currentResourceEntry = null;
		this.currentResourceEntries = null;
	}

	private boolean isSkillingExpired(Skill skill)
	{
		final int timeoutSecs = config.hideAfterSeconds();
		if (timeoutSecs <= 0)
		{
			return false;
		}
		final Skill active = skillTracker.getActiveSkill();
		if (active == skill)
		{
			return false;
		}
		final long sinceActivity = skillTracker.getMillisSinceActivity();
		if (skillTracker.getDisplaySkill() == skill
			&& sinceActivity != Long.MAX_VALUE
			&& sinceActivity <= timeoutSecs * 1000L)
		{
			return false;
		}
		return isExpired();
	}

	private Skill recentSkillToRender()
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

	private Dimension renderSkill(Graphics2D graphics, Skill skill, List<ResourceData.ResourceEntry> entries)
	{
		final boolean compact = config.compactOverlay();
		final int width = compact ? PANEL_WIDTH_COMPACT : PANEL_WIDTH;
		panelComponent.setPreferredSize(new Dimension(width, 0));
		panelComponent.setBackgroundColor(OSRS_BG);

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Active")
			.color(OSRS_GOLD)
			.build());
		addSplitRow(skill.getName(), ACCENT, "Active", DIM);

		final SkillTracker.SkillState state = skillTracker.getSkillState(skill);
		final long actions = state == null ? 0L : skillObservedActions(state);
		addSplitRow("Actions", null, formatInt(actions), null);

		final ResourceData.SkillData skillData = resourceData.getSkillData(skill);
		if (skillData != null && skillData.getPetBaseChance() > 0)
		{
			final int level = skillTracker.getCurrentLevel(skill);
			if (level > 0)
			{
				addSplitRow("Pet odds", null, PetDisplay.oddsText(skillData.getPetBaseChance(), level), ACCENT);
			}
		}

		final java.util.Set<String> tracked = parseTracked(config.trackedSkillingItem());
		for (ResourceData.ResourceEntry entry : entries)
		{
			if (trackedResourceMatches(tracked, entry))
			{
				buildSkillProgressSection(entry, state, actions, compact);
			}
		}
		return super.render(graphics);
	}

	private Dimension renderSkillResource(Graphics2D graphics)
	{
		final String resourceName = currentResourceName;
		final ResourceData.ResourceEntry resource = currentResourceEntry;
		final List<ResourceData.ResourceEntry> entries = currentResourceEntries;
		if (resourceName == null || resource == null)
		{
			return null;
		}

		final Skill skill = resource.getSkill();
		final boolean compact = config.compactOverlay();
		final int width = compact ? PANEL_WIDTH_COMPACT : PANEL_WIDTH;
		panelComponent.setPreferredSize(new Dimension(width, 0));
		panelComponent.setBackgroundColor(OSRS_BG);

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Active")
			.color(OSRS_GOLD)
			.build());
		addSplitRow(resourceName, ACCENT, skill.getName(), DIM);

		final SkillTracker.SkillState state = skillTracker.getSkillState(skill);
		final long actions = state == null ? 0L : skillObservedActions(state);
		addSplitRow("Actions", null, formatInt(actions), null);

		final ResourceData.SkillData skillData = resourceData.getSkillData(skill);
		if (skillData != null && skillData.getPetBaseChance() > 0)
		{
			final int level = skillTracker.getCurrentLevel(skill);
			if (level > 0)
			{
				final int petBase = resource.getEffectivePetBaseChance(skillData.getPetBaseChance());
				addSplitRow("Pet odds", null, PetDisplay.oddsText(petBase, level), ACCENT);
			}
		}

		final java.util.Set<String> tracked = parseTracked(config.trackedSkillingItem());
		if (entries != null)
		{
			for (ResourceData.ResourceEntry entry : entries)
			{
				if (entry.isStatisticalReward() && requirementsMet(entry) && trackedResourceMatches(tracked, entry))
				{
					buildSkillProgressSection(entry, state,
						resourceRewardActions(resource, entry, state, actions), compact);
				}
			}
		}
		return super.render(graphics);
	}

	private boolean requirementsMet(ResourceData.ResourceEntry entry)
	{
		return skillTracker.hasAnyItem(entry.getRequiredItemsAny());
	}

	private boolean isExpired()
	{
		final int timeoutSecs = config.hideAfterSeconds();
		return timeoutSecs > 0 && System.currentTimeMillis() - lastActivityAtMs > timeoutSecs * 1000L;
	}

	private Dimension renderSkillSource(Graphics2D graphics)
	{
		final Skill skill = currentSourceSkill;
		final String action = currentSourceAction;
		final String sourceName = currentSourceName;
		final List<ResourceData.ResourceEntry> entries = currentSourceEntries;
		if (skill == null || action == null || sourceName == null || entries == null || entries.isEmpty())
		{
			return null;
		}

		final boolean compact = config.compactOverlay();
		final int width = compact ? PANEL_WIDTH_COMPACT : PANEL_WIDTH;
		panelComponent.setPreferredSize(new Dimension(width, 0));
		panelComponent.setBackgroundColor(OSRS_BG);

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Active")
			.color(OSRS_GOLD)
			.build());
		addSplitRow(sourceName, ACCENT, skill.getName(), DIM);
		addSplitRow("Action", null, action, DIM);

		final SkillTracker.SkillState state = skillTracker.getSkillState(skill);
		final SkillTracker.SourceState source = state == null ? null : state.getSourceStates().get(sourceName);
		final long actions = source == null ? 0L : sourceObservedActions(source);
		addSplitRow("Actions", null, formatInt(actions), null);

		final java.util.Set<String> tracked = parseTracked(config.trackedSkillingItem());
		final java.util.List<ResourceData.ResourceEntry> show = new java.util.ArrayList<>();
		for (ResourceData.ResourceEntry entry : entries)
		{
			if (entry.isStatisticalReward() && trackedResourceMatches(tracked, entry))
			{
				show.add(entry);
			}
		}

		if (show.isEmpty())
		{
			return super.render(graphics);
		}

		for (ResourceData.ResourceEntry entry : show)
		{
			buildSkillSourceProgressSection(entry, source, actions, compact);
		}
		return super.render(graphics);
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
			.right(sessionKc > 0 ? formatInt(kc) + "  (+" + formatInt(sessionKc) + ")" : formatInt(kc))
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

	private static boolean trackedResourceMatches(java.util.Set<String> tracked, ResourceData.ResourceEntry entry)
	{
		for (String item : tracked)
		{
			if (item.equalsIgnoreCase(entry.getName()))
			{
				return true;
			}
		}
		return false;
	}

	private static long sourceObservedActions(SkillTracker.SourceState source)
	{
		long actions = 0L;
		for (long qty : source.getGrossResourceCounts().values())
		{
			actions = Math.max(actions, qty);
		}
		return actions;
	}

	private static long skillObservedActions(SkillTracker.SkillState state)
	{
		long actions = 0L;
		if (state == null)
		{
			return actions;
		}
		for (long qty : state.getGrossResourceCounts().values())
		{
			actions = Math.max(actions, qty);
		}
		return actions;
	}

	private static long sourceGrossCount(SkillTracker.SourceState source, ResourceData.ResourceEntry entry)
	{
		if (source == null)
		{
			return 0L;
		}
		return entry.countIn(source.getGrossResourceCounts());
	}

	private static long skillGrossCount(SkillTracker.SkillState state, ResourceData.ResourceEntry entry)
	{
		if (state == null)
		{
			return 0L;
		}
		return entry.countIn(state.getGrossResourceCounts());
	}

	private static long resourceRewardActions(ResourceData.ResourceEntry resource,
		ResourceData.ResourceEntry reward, SkillTracker.SkillState state, long skillActions)
	{
		if (state == null || resource == null || reward.getObjectIds().isEmpty())
		{
			return skillActions;
		}
		return resource.countIn(state.getGrossResourceCounts());
	}

	private static long sourceDryActions(long actions, long received, double rate)
	{
		if (received <= 0L)
		{
			return actions;
		}
		long expectedPerDrop = Math.max(1L, Math.round(Probability.expectedKills(rate)));
		return Math.max(0L, actions - received * expectedPerDrop);
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

	private void buildSkillSourceProgressSection(ResourceData.ResourceEntry entry,
		SkillTracker.SourceState source, long actions, boolean compact)
	{
		final double rate = entry.getRate();
		final long denom = Math.max(1L, Math.round(1.0 / rate));
		final long received = sourceGrossCount(source, entry);
		final long dryActions = sourceDryActions(actions, received, rate);
		final double pSeen = Probability.atLeastOne(rate, dryActions);
		final int expectedActions = (int) Math.round(Probability.expectedKills(rate));

		panelComponent.getChildren().add(LineComponent.builder().left(" ").build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(shortenName(entry.getName()))
			.leftColor(ACCENT)
			.right("1/" + formatInt(denom))
			.rightColor(DIM)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Observed")
			.right(formatInt(received))
			.rightColor(received > 0 ? OSRS_GOLD : DIM)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(compact ? "Seen" : "Chance seen")
			.right(formatPercent(pSeen))
			.rightColor(DIM)
			.build());

		int multiplier = Math.max(1, (int) Math.ceil((double) dryActions / Math.max(1, expectedActions)));
		if (expectedActions > 0 && dryActions > 0 && dryActions % expectedActions == 0)
		{
			multiplier++;
		}
		addProgressBar(multiplier + "x", (int) Math.min(dryActions, Integer.MAX_VALUE),
			expectedActions * multiplier, compact, multiplier);

		final double expectedDrops = Probability.expectedDrops(rate, actions);
		final double deviation = received - expectedDrops;
		final String devStr = (deviation >= 0.0 ? "+" : "") + String.format("%.1f", deviation);
		final Color devColor = deviation > 0.0 ? LuckStatus.LUCKY.getColor()
			: deviation < 0.0 ? LuckStatus.UNLUCKY.getColor() : LUCK_AVERAGE;
		panelComponent.getChildren().add(LineComponent.builder()
			.left(compact ? "Dev" : "Deviation")
			.right(devStr)
			.rightColor(devColor)
			.build());

		final double sd = Probability.stdDev(rate, actions);
		final String luckLabel;
		final Color luckColor;
		if (sd <= 0.0 || actions == 0L)
		{
			luckLabel = "--";
			luckColor = LUCK_AVERAGE;
		}
		else
		{
			final double z = ((double) received - expectedDrops) / sd;
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

	private void buildSkillProgressSection(ResourceData.ResourceEntry entry,
		SkillTracker.SkillState state, long actions, boolean compact)
	{
		final double rate = entry.getRate();
		final long denom = Math.max(1L, Math.round(1.0 / rate));
		final long received = skillGrossCount(state, entry);
		final long dryActions = sourceDryActions(actions, received, rate);
		final double pSeen = Probability.atLeastOne(rate, dryActions);
		final int expectedActions = (int) Math.round(Probability.expectedKills(rate));

		panelComponent.getChildren().add(LineComponent.builder().left(" ").build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(shortenName(entry.getName()))
			.leftColor(ACCENT)
			.right("1/" + formatInt(denom))
			.rightColor(DIM)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Observed")
			.right(formatInt(received))
			.rightColor(received > 0 ? OSRS_GOLD : DIM)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(compact ? "Seen" : "Chance seen")
			.right(formatPercent(pSeen))
			.rightColor(DIM)
			.build());

		int multiplier = Math.max(1, (int) Math.ceil((double) dryActions / Math.max(1, expectedActions)));
		if (expectedActions > 0 && dryActions > 0 && dryActions % expectedActions == 0)
		{
			multiplier++;
		}
		addProgressBar(multiplier + "x", (int) Math.min(dryActions, Integer.MAX_VALUE),
			expectedActions * multiplier, compact, multiplier);

		final double expectedDrops = Probability.expectedDrops(rate, actions);
		final double deviation = received - expectedDrops;
		final String devStr = (deviation >= 0.0 ? "+" : "") + String.format("%.1f", deviation);
		final Color devColor = deviation > 0.0 ? LuckStatus.LUCKY.getColor()
			: deviation < 0.0 ? LuckStatus.UNLUCKY.getColor() : LUCK_AVERAGE;
		panelComponent.getChildren().add(LineComponent.builder()
			.left(compact ? "Dev" : "Deviation")
			.right(devStr)
			.rightColor(devColor)
			.build());

		final double sd = Probability.stdDev(rate, actions);
		final String luckLabel;
		final Color luckColor;
		if (sd <= 0.0 || actions == 0L)
		{
			luckLabel = "--";
			luckColor = LUCK_AVERAGE;
		}
		else
		{
			final double z = ((double) received - expectedDrops) / sd;
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

