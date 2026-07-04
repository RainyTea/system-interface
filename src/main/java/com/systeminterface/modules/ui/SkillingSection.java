package com.systeminterface.modules.ui;

import com.systeminterface.core.SystemInterfaceConfig;
import com.systeminterface.modules.skills.PetDisplay;
import com.systeminterface.modules.skills.ResourceData;
import com.systeminterface.modules.skills.SkillTracker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;

/**
 * Side-panel "Skilling" section: a collapsible header showing the current/most-recent
 * tracked activity (with the live XP/hr display), a section-level "Track" table shown
 * only while a skill is actively being interacted with, and a compact list of collapsed
 * per-skill ledger rows. Clicking a row expands it to show Actions/XP, Rewards/Costs/Net,
 * and the gathered-item Output rows — the Track grid itself now lives only in the
 * section-level area, not per-column.
 *
 * <p>Section-level expand/collapse and the section-level Track skill are driven externally
 * by {@link #applySkillingFocus(boolean, Skill, Skill)}, which the coordinator calls with
 * plain values translated from {@code ActivityFocus.Snapshot} — this class intentionally
 * has no dependency on {@code ActivityFocus} itself.
 *
 * <p>The tracked-item config key ({@code trackedItem}, pipe-joined) is written byte-identical
 * to {@link CombatSection}'s implementation so the Target Status overlay — which reads
 * the same key — is unaffected.
 */
public class SkillingSection extends JPanel
{
	private static final Color ACCENT = new Color(120, 200, 255);
	private static final Color HEADER_BG = new Color(30, 30, 36);
	private static final Color LOOT_SKILL = new Color(120, 200, 255);
	private static final Color SELECTED_BORDER = new Color(255, 220, 50);
	private static final Color LIVE_COLOR = ACCENT;
	private static final Color STALE_COLOR = new Color(120, 120, 120);

	private static final int ITEMS_PER_ROW = 3;
	private static final Dimension ITEM_SIZE = new Dimension(62, 56);

	/** Pseudo-mob namespace for skilling source-row collapse state (distinct from CombatSection's). */
	private static final String SOURCE_ROW_KEY = "__skill_sources__";

	private final SkillTracker skillTracker;
	private final ResourceData resourceData;
	private final ItemManager itemManager;
	private final CollapseStateStore collapseStateStore;
	private final ConfigManager configManager;
	private final SystemInterfaceConfig config;
	private final Runnable onManualSectionToggle;
	private final Runnable onContentEngaged;

	private final JLabel sectionHeaderLabel = new JLabel("Skilling", SwingConstants.CENTER);
	private final JLabel sectionArrow = new JLabel();
	private final JPanel sectionBody = new JPanel();
	private final JPanel headerPanel = new JPanel();
	private final JPanel trackArea = new JPanel();
	private final JPanel sourceListPanel = new JPanel();

	private boolean sectionBodyAdded = true;
	private boolean sectionExpanded = true;

	/** The skill currently shown in the section-level Track area, or null (interaction-only). */
	private Skill trackingSkill;
	/** The source-row column skill auto-expanded by {@link #applySkillingFocus}, or null. */
	private Skill autoExpandedSkill;
	/**
	 * Skills the user manually collapsed while they were the current {@link #autoExpandedSkill}.
	 * Transient (never persisted) — cleared whenever the auto-expand target changes, so it only
	 * overrides the auto-target's default-open behavior for the current auto-expand episode and
	 * never corrupts the persisted {@link CollapseStateStore} bit for that skill.
	 */
	private final Set<Skill> autoCollapsedOverride = new HashSet<>();

	// Live skilling-stat labels — updated in place each tick (see updateLiveStats) rather
	// than by rebuilding the header, so the figures track XpTrackerService live.
	private JLabel liveXpHrValue;
	private JLabel liveResHrValue;
	private JLabel liveTimeValue;
	private String cachedXpHr = "—";
	private String cachedResHr = "—";
	private String cachedTime = "—";
	/** True when the live stats are frozen (skill idle) — used to skip work while idle. */
	private boolean liveStale = true;

	public SkillingSection(
		SkillTracker skillTracker,
		ResourceData resourceData,
		ItemManager itemManager,
		CollapseStateStore collapseStateStore,
		ConfigManager configManager,
		SystemInterfaceConfig config,
		Runnable onManualSectionToggle,
		Runnable onContentEngaged)
	{
		super();
		this.skillTracker = skillTracker;
		this.resourceData = resourceData;
		this.itemManager = itemManager;
		this.collapseStateStore = collapseStateStore;
		this.configManager = configManager;
		this.config = config;
		this.onManualSectionToggle = onManualSectionToggle;
		this.onContentEngaged = onContentEngaged;

		setLayout(new DynamicGridLayout(0, 1, 0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildSectionHeader());

		sectionBody.setLayout(new DynamicGridLayout(0, 1, 0, 0));
		sectionBody.setBackground(ColorScheme.DARK_GRAY_COLOR);

		headerPanel.setLayout(new DynamicGridLayout(0, 1, 0, 2));
		headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(4, 4, 4, 4));
		sectionBody.add(headerPanel);

		trackArea.setLayout(new DynamicGridLayout(0, 1, 0, 0));
		trackArea.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sectionBody.add(trackArea);

		sourceListPanel.setLayout(new DynamicGridLayout(0, 1, 0, 0));
		sourceListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sectionBody.add(sourceListPanel);

		add(sectionBody);

		refresh();
	}

	private JPanel buildSectionHeader()
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(HEADER_BG);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setBorder(new EmptyBorder(4, 6, 4, 6));

		sectionHeaderLabel.setForeground(ACCENT);
		sectionHeaderLabel.setFont(sectionHeaderLabel.getFont().deriveFont(Font.BOLD, 13f));
		header.add(sectionHeaderLabel, BorderLayout.CENTER);

		sectionArrow.setForeground(ACCENT);
		updateSectionArrow();
		header.add(sectionArrow, BorderLayout.EAST);

		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onManualSectionToggle.run();
			}
		});
		return header;
	}

	private void updateSectionArrow()
	{
		sectionArrow.setText(sectionExpanded ? "˄" : "˅");
	}

	/**
	 * Expand/collapse the section body, show the section-level Track table for
	 * {@code trackingSkill} (null hides it — interaction-only, no portrait/pre-engage select
	 * for skilling), and auto-expand the {@code autoExpandedSkill} column (null = none). The
	 * auto-target's open/closed state is tracked transiently for the duration of this
	 * auto-expand episode — a click on it while it is the auto-target never touches the
	 * persisted {@link CollapseStateStore} bit, so the user's real manual collapse intent for
	 * that skill is preserved once auto-expand moves on.
	 */
	public void applySkillingFocus(boolean expandSection, Skill trackingSkill, Skill autoExpandedSkill)
	{
		SwingUtilities.invokeLater(() ->
		{
			this.sectionExpanded = expandSection;
			this.trackingSkill = trackingSkill;
			if (!Objects.equals(this.autoExpandedSkill, autoExpandedSkill))
			{
				// New auto-expand episode (including auto-expand ending, i.e. becoming null) —
				// any transient override from the previous episode no longer applies.
				autoCollapsedOverride.clear();
			}
			this.autoExpandedSkill = autoExpandedSkill;
			updateSectionArrow();

			if (expandSection && !sectionBodyAdded)
			{
				add(sectionBody);
				sectionBodyAdded = true;
			}
			else if (!expandSection && sectionBodyAdded)
			{
				remove(sectionBody);
				sectionBodyAdded = false;
			}
			revalidate();
			repaint();

			rebuild();
		});
	}

	/** Rebuilds the header and the source-row list. Safe to call from any thread. */
	public void refresh()
	{
		SwingUtilities.invokeLater(this::rebuild);
	}

	/**
	 * Called once per game tick by the plugin to keep the skilling rates live while a
	 * session is active. Updates only the specific value labels (no section rebuild) on the
	 * EDT, and does nothing once the session is idle and already frozen — no idle work.
	 */
	public void updateLiveStats()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (liveXpHrValue == null)
			{
				return;
			}
			final Skill skill = skillTracker.getDisplaySkill();
			if (skill == null)
			{
				return;
			}
			final boolean active = skillTracker.isActive();
			if (!active && liveStale)
			{
				return; // already frozen + dimmed
			}
			refreshLiveValues(skill, active);
		});
	}

	// ---------------------------------------------------------------------
	// Header: current activity + live XP/hr
	// ---------------------------------------------------------------------

	private void rebuild()
	{
		headerPanel.removeAll();

		// Persistent log: show the current OR most-recent skill, so the panel keeps
		// displaying what you were doing after the in-game overlay auto-hides.
		Skill displaySkill = skillTracker.getDisplaySkill();
		if (displaySkill == null)
		{
			liveXpHrValue = null;
			liveResHrValue = null;
			liveTimeValue = null;
			JLabel idle = new JLabel("No skill tracked yet", SwingConstants.CENTER);
			idle.setForeground(new Color(150, 150, 150));
			idle.setBorder(new EmptyBorder(4, 0, 4, 0));
			headerPanel.add(idle);
		}
		else
		{
			final boolean active = skillTracker.isActive();

			JLabel skillLabel = new JLabel(capitalize(displaySkill.getName())
				+ (active ? "" : "  (idle)"), SwingConstants.CENTER);
			skillLabel.setForeground(active ? ACCENT : new Color(150, 150, 150));
			skillLabel.setFont(skillLabel.getFont().deriveFont(Font.BOLD, 13f));
			skillLabel.setBorder(new EmptyBorder(2, 0, 4, 0));
			headerPanel.add(skillLabel);

			ResourceData.SkillData skillData = resourceData.getSkillData(displaySkill);
			int level = skillTracker.getCurrentLevel(displaySkill);

			headerPanel.add(statRow("Level", String.valueOf(level)));

			// Live rates from RuneLite's XP tracker. These rows are always present (showing
			// the cached value) so their labels are stable references that updateLiveStats
			// can refresh in place each tick — rather than rebuilding the whole header on a timer.
			liveXpHrValue = addHeaderRow("XP / hr", cachedXpHr);
			liveResHrValue = addHeaderRow("Resources / hr", cachedResHr);
			liveTimeValue = addHeaderRow("Time to next lvl", cachedTime);
			refreshLiveValues(displaySkill, active);

			SkillTracker.SkillState displayState = skillTracker.getSkillState(displaySkill);
			long totalResources = displayState != null ? displayState.getTotalResources() : 0;
			headerPanel.add(statRow("Lifetime resources", String.format("%,d", totalResources)));

			// Pet odds only — the accurate, formula-based rate. We don't show a dry streak or
			// chance-seen: the plugin can't read true lifetime actions or pet ownership, so any
			// such figure would just be misleading.
			if (skillData != null && level > 0)
			{
				headerPanel.add(statRow("Pet odds", PetDisplay.oddsText(skillData.getPetBaseChance(), level)));
			}
		}

		headerPanel.revalidate();
		headerPanel.repaint();

		// Section-level Track table: interaction-only, shown only while trackingSkill is set
		// AND a gathering node is actually engaged (no node -> nothing to show, no fallback
		// to the full per-skill resource list).
		trackArea.removeAll();
		if (trackingSkill != null && !currentNodeItemIds().isEmpty())
		{
			trackArea.add(buildTrackSection(trackingSkill));
		}
		trackArea.revalidate();
		trackArea.repaint();

		// Collapsed source rows: one per skill with any tracked state.
		sourceListPanel.removeAll();
		List<Skill> tracked = skillTracker.getTrackedSkills();
		if (tracked.isEmpty())
		{
			JLabel none = new JLabel("No skilling tracked yet", SwingConstants.CENTER);
			none.setForeground(new Color(150, 150, 150));
			none.setBorder(new EmptyBorder(4, 0, 4, 0));
			sourceListPanel.add(none);
		}
		else
		{
			for (Skill sk : tracked)
			{
				addSourceRow(sk);
			}
		}

		sourceListPanel.revalidate();
		sourceListPanel.repaint();
		revalidate();
		repaint();
	}

	/**
	 * Updates the live XP/hr, resources/hr and time-to-next labels in place from
	 * XpTrackerService. When the skill is actively training the values are re-pulled and
	 * shown bright; when idle they are frozen as a record of the last session and dimmed.
	 * Must run on the EDT.
	 */
	private void refreshLiveValues(Skill skill, boolean active)
	{
		if (liveXpHrValue == null)
		{
			return;
		}
		if (active)
		{
			final int xpHr = skillTracker.getXpHr(skill);
			cachedXpHr = xpHr > 0 ? String.format("%,d", xpHr) : "—";
			final int resHr = skillTracker.getActionsHr(skill);
			cachedResHr = resHr > 0 ? String.format("%,d", resHr) : "—";
			final String t = skillTracker.getTimeToNextLevel(skill);
			cachedTime = t != null ? t : "—";
		}
		final Color c = active ? LIVE_COLOR : STALE_COLOR;
		liveXpHrValue.setText(cachedXpHr);
		liveXpHrValue.setForeground(c);
		liveResHrValue.setText(cachedResHr);
		liveResHrValue.setForeground(c);
		liveTimeValue.setText(cachedTime);
		liveTimeValue.setForeground(c);
		liveStale = !active;
	}

	/** Adds a label/value row to the header and returns the value label. */
	private JLabel addHeaderRow(String label, String value)
	{
		JPanel row = statRow(label, value);
		headerPanel.add(row);
		return (JLabel) row.getComponent(1);
	}

	private JPanel statRow(String label, String value)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(2, 6, 2, 6));

		JLabel left = new JLabel(label);
		left.setForeground(new Color(200, 200, 200));
		row.add(left, BorderLayout.WEST);

		JLabel right = new JLabel(value);
		right.setForeground(ACCENT);
		row.add(right, BorderLayout.EAST);

		return row;
	}

	// ---------------------------------------------------------------------
	// Collapsed / expanded source rows (ledger only — no Track grid per column)
	// ---------------------------------------------------------------------

	private void addSourceRow(Skill skill)
	{
		final String name = capitalize(skill.getName());
		final SkillTracker.SkillState state = skillTracker.getSkillState(skill);
		final boolean isAutoExpanded = skill.equals(autoExpandedSkill);
		// The auto-target renders from the transient override only (open by default for the
		// episode; closed only if the user toggled it shut this episode) — the persisted store
		// is never consulted for it, so auto-expand can never corrupt the user's real manual
		// collapse intent for this skill. Any other row still reads its persisted state as today.
		final boolean collapsed = isAutoExpanded
			? autoCollapsedOverride.contains(skill)
			: collapseStateStore.isSectionCollapsed(SOURCE_ROW_KEY, skill.name());

		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, LOOT_SKILL),
			new EmptyBorder(3, 6, 3, 4)));

		JLabel titleLabel = new JLabel(name);
		titleLabel.setForeground(Color.WHITE);
		header.add(titleLabel, BorderLayout.WEST);

		JPanel right = new JPanel(new BorderLayout(6, 0));
		right.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		long totalResources = state != null ? state.getTotalResources() : 0;
		JLabel summaryLabel = new JLabel(String.format("%,d", totalResources) + " gathered");
		summaryLabel.setForeground(new Color(170, 170, 170));
		right.add(summaryLabel, BorderLayout.CENTER);
		JLabel arrow = new JLabel(collapsed ? "˄" : "˅");
		arrow.setForeground(new Color(150, 150, 150));
		right.add(arrow, BorderLayout.EAST);
		header.add(right, BorderLayout.EAST);

		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (skill.equals(autoExpandedSkill))
				{
					// Toggle the transient episode override only — leave the persisted bit
					// untouched so the row reverts to the user's real stored state once this
					// skill is no longer the auto-target.
					if (!autoCollapsedOverride.remove(skill))
					{
						autoCollapsedOverride.add(skill);
					}
				}
				else
				{
					collapseStateStore.toggleSection(SOURCE_ROW_KEY, skill.name());
				}
				onContentEngaged.run(); // user engaged this section's content: pin it open
				refresh();
			}
		});
		sourceListPanel.add(header);

		if (collapsed)
		{
			return;
		}

		sourceListPanel.add(buildExpandedRow(skill, state));
	}

	private JPanel buildExpandedRow(Skill skill, SkillTracker.SkillState state)
	{
		JPanel body = new JPanel();
		body.setLayout(new DynamicGridLayout(0, 1, 0, 2));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setBorder(new EmptyBorder(4, 10, 6, 4));

		final int actions = skillTracker.getActions(skill);
		final int xpHr = skillTracker.getXpHr(skill);
		final long rewards = skillTracker.getSkillKeptGp(skill);

		body.add(statRow("Actions", String.format("%,d", actions)));
		body.add(statRow("XP / hr", xpHr > 0 ? String.format("%,d", xpHr) : "—"));
		body.add(statRow("Rewards", PanelFormat.gp(rewards)));
		body.add(statRow("Costs", PanelFormat.gp(0)));
		body.add(statRow("Net", PanelFormat.gp(rewards)));

		// Output: itemised kept quantities for this skill, reusing the shared ledger-row builder.
		JLabel outputHeader = new JLabel("Output");
		outputHeader.setForeground(ACCENT);
		outputHeader.setFont(outputHeader.getFont().deriveFont(Font.BOLD, 12f));
		outputHeader.setBorder(new EmptyBorder(6, 0, 2, 0));
		body.add(outputHeader);

		final Map<Integer, Long> keptItems = state != null ? state.getResourceCounts() : java.util.Collections.emptyMap();
		if (keptItems.isEmpty())
		{
			JLabel none = new JLabel("None yet");
			none.setForeground(new Color(150, 150, 150));
			body.add(none);
		}
		else
		{
			keptItems.entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
				.forEach(e -> body.add(ItemRowFactory.buildLedgerRow(itemManager,
					new ItemRowFactory.LedgerRow(e.getKey(), itemName(e.getKey()), e.getValue()))));
		}

		JLabel supplies = new JLabel("Supplies: None yet");
		supplies.setForeground(new Color(150, 150, 150));
		supplies.setBorder(new EmptyBorder(6, 0, 0, 0));
		body.add(supplies);

		return body;
	}

	// ---------------------------------------------------------------------
	// Section-level Track list (the former per-column output-items grid). Interaction-only:
	// only rendered by rebuild() while trackingSkill != null (see applySkillingFocus).
	// ---------------------------------------------------------------------

	/**
	 * Output item ids of the currently engaged gathering node (tree/rock object, or fishing-spot
	 * NPC — at most one is active at a time per {@link SkillTracker}). Empty when no node is
	 * engaged. A {@link LinkedHashSet} keeps a stable, deduped display order.
	 */
	private Set<Integer> currentNodeItemIds()
	{
		Set<Integer> ids = new LinkedHashSet<>();
		int objId = skillTracker.getActiveObjectId();
		int spotId = skillTracker.getActiveFishingSpotId();
		if (objId != -1)
		{
			for (ResourceData.ResourceEntry e : resourceData.forObjectId(objId))
			{
				ids.add(e.getItemId());
			}
		}
		else if (spotId != -1)
		{
			for (ResourceData.ResourceEntry e : resourceData.forNpcIdAndMethod(spotId, skillTracker.getActiveFishingMethod()))
			{
				ids.add(e.getItemId());
			}
		}
		return ids;
	}

	private JPanel buildTrackSection(Skill skill)
	{
		JPanel wrap = new JPanel();
		wrap.setLayout(new DynamicGridLayout(0, 1, 0, 0));
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.setBorder(new EmptyBorder(6, 0, 0, 0));

		// Outputs of the CURRENTLY ENGAGED node only (tree/rock or fishing spot) — not every
		// resource the skill can ever gather — so the table reflects what's actually in front
		// of the player right now. Hidden entirely (see rebuild()) when no node is engaged.
		Set<Integer> nodeItemIds = currentNodeItemIds();
		if (nodeItemIds.isEmpty())
		{
			JLabel none = new JLabel("Nothing to track yet");
			none.setForeground(new Color(150, 150, 150));
			none.setBorder(new EmptyBorder(4, 4, 4, 4));
			wrap.add(none);
			return wrap;
		}

		final String currentTracked = configManager.getConfiguration(SystemInterfaceConfig.GROUP, "trackedItem");

		SkillTracker.SkillState state = skillTracker.getSkillState(skill);
		final Map<Integer, Long> keptItems = state != null ? state.getResourceCounts() : java.util.Collections.emptyMap();

		List<Integer> sortedIds = new java.util.ArrayList<>(nodeItemIds);
		sortedIds.sort(java.util.Comparator.comparing(this::itemName, String.CASE_INSENSITIVE_ORDER));

		// Grid of item slots (reusing the shared Combat-section item-slot builder), one per
		// gatherable resource for this skill. Gathering has no drop "rate" the way combat loot
		// does — every successful action yields the resource — so each slot shows "Always".
		int rows = (sortedIds.size() + ITEMS_PER_ROW - 1) / ITEMS_PER_ROW;
		JPanel grid = new JPanel(new GridLayout(rows, ITEMS_PER_ROW, 1, 1));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.setMaximumSize(new Dimension(ITEMS_PER_ROW * (ITEM_SIZE.width + 2), rows * (ITEM_SIZE.height + 2)));

		for (int itemId : sortedIds)
		{
			final String name = itemName(itemId);
			final boolean isTracked = csvContains(currentTracked, name);
			final long received = keptItems.getOrDefault(itemId, 0L);
			grid.add(ItemRowFactory.createItemSlot(itemManager, ITEM_SIZE, LOOT_SKILL, SELECTED_BORDER,
				name, itemId, 1.0, (int) Math.min(received, Integer.MAX_VALUE), isTracked, () ->
				{
					toggleTracked(name); // click to track, click again to untrack
					refresh();
				}));
		}
		int remainder = (rows * ITEMS_PER_ROW) - sortedIds.size();
		for (int i = 0; i < remainder; i++)
		{
			grid.add(ItemRowFactory.blankSlot(ITEM_SIZE));
		}

		wrap.add(grid);
		return wrap;
	}

	// ---------------------------------------------------------------------
	// Tracked-item set (stored as a pipe-separated list in the trackedItem config).
	// Mirrored from CombatSection (not shared: CombatSection's helpers are private
	// instance methods, not a reusable static utility) so this stays byte-identical to
	// the original SystemInterfacePanel implementation — the Target Status overlay/luck
	// engine, which reads the same config key, is unaffected.
	// ---------------------------------------------------------------------

	private Set<String> getTrackedItems()
	{
		Set<String> set = new LinkedHashSet<>();
		String csv = configManager.getConfiguration(SystemInterfaceConfig.GROUP, "trackedItem");
		if (csv != null)
		{
			for (String s : csv.split("\\|"))
			{
				if (!s.trim().isEmpty()) set.add(s.trim());
			}
		}
		return set;
	}

	private void toggleTracked(String item)
	{
		Set<String> set = getTrackedItems();
		if (!set.removeIf(s -> s.equalsIgnoreCase(item)))
		{
			set.add(item);
		}
		configManager.setConfiguration(SystemInterfaceConfig.GROUP, "trackedItem", String.join("|", set));
	}

	private static boolean csvContains(String csv, String item)
	{
		if (csv == null) return false;
		for (String s : csv.split("\\|"))
		{
			if (s.trim().equalsIgnoreCase(item)) return true;
		}
		return false;
	}

	// ---------------------------------------------------------------------
	// Item id / name resolution
	// ---------------------------------------------------------------------

	/** Display name for an item id via the curated ResourceData, falling back to the raw id. */
	private String itemName(int itemId)
	{
		ResourceData.ResourceEntry entry = resourceData.forItemId(itemId);
		if (entry != null && entry.getName() != null && !entry.getName().isEmpty())
		{
			return entry.getName();
		}
		return "Item " + itemId;
	}

	private static String capitalize(String s)
	{
		if (s == null || s.isEmpty())
		{
			return s;
		}
		return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
	}
}
