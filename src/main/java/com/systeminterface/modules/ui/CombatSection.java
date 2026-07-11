package com.systeminterface.modules.ui;

import com.systeminterface.core.SystemInterfaceConfig;
import com.systeminterface.services.drops.DropTable;
import com.systeminterface.services.drops.LootTables;
import com.systeminterface.services.lookup.BossAliases;
import com.systeminterface.services.lookup.ItemMembership;
import com.systeminterface.services.lookup.ItemNameCache;
import com.systeminterface.services.portrait.PortraitService;
import com.systeminterface.common.probability.Probability;
import com.systeminterface.services.state.StateTracker;
import com.systeminterface.services.state.TargetState;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;

/**
 * Side-panel "Combat" section: a collapsible header, a section-level "context" area
 * (portrait + trackable-drops table for whichever target the player is currently
 * interacting with or browsing), and a compact list of collapsed per-source ledger
 * rows (one per tracked/engaged NPC or boss). Clicking a row expands it to show KC,
 * Rewards/Costs/Net, and received loot — the Track grid itself now lives only in the
 * section-level context area, not per-column.
 *
 * <p>Section-level expand/collapse and the context target are driven externally by
 * {@link #applyCombatFocus(boolean, String, String)}, which the coordinator calls with
 * plain values translated from {@code ActivityFocus.Snapshot} — this class intentionally
 * has no dependency on {@code ActivityFocus} itself.
 *
 * <p>The tracked-item config key ({@code trackedItem}, pipe-joined) is written
 * byte-identically to the original {@code SystemInterfacePanel} implementation so the
 * Target Status overlay/luck engine — which reads the same key — is unaffected.
 */
public class CombatSection extends JPanel
{
	private static final Color ACCENT = new Color(120, 200, 255);
	private static final Color HEADER_BG = new Color(30, 30, 36);
	private static final Color SELECTED_BORDER = new Color(255, 220, 50);
	private static final Color RARITY_COMMON = new Color(160, 160, 160);
	private static final Color RARITY_UNCOMMON = new Color(30, 175, 30);
	private static final Color RARITY_RARE = new Color(60, 120, 230);
	private static final Color RARITY_VERY_RARE = new Color(170, 70, 220);
	private static final Color LOOT_COMBAT = new Color(200, 80, 60);

	private static final int ITEMS_PER_ROW = 3;
	private static final Dimension ITEM_SIZE = new Dimension(62, 56);

	/** How long since the last interaction before a source drops off the row list. */
	private static final long PROFIT_STALE_MILLIS = 14L * 24 * 60 * 60 * 1000; // 14 days

	/** Pseudo-mob namespace for combat source-row collapse state (distinct from per-mob rarity sections). */
	private static final String SOURCE_ROW_KEY = "__combat_sources__";

	private static final Map<String, Integer> ITEM_ID_OVERRIDES = new HashMap<>();
	static
	{
		ITEM_ID_OVERRIDES.put("coins", 995);
		ITEM_ID_OVERRIDES.put("bones", 526);
		ITEM_ID_OVERRIDES.put("big bones", 532);
		ITEM_ID_OVERRIDES.put("clue scroll (beginner)", 23182);
		ITEM_ID_OVERRIDES.put("clue scroll (easy)", 2677);
		ITEM_ID_OVERRIDES.put("clue scroll (medium)", 2801);
		ITEM_ID_OVERRIDES.put("clue scroll (hard)", 2722);
		ITEM_ID_OVERRIDES.put("clue scroll (elite)", 12073);
		ITEM_ID_OVERRIDES.put("ensouled goblin head", 13448);
	}

	private final StateTracker stateTracker;
	private final LootTables lootTables;
	private final ItemManager itemManager;
	private final ItemMembership itemMembership;
	private final ItemNameCache itemNameCache;
	private final CollapseStateStore collapseStateStore;
	private final PortraitService portraitService;
	private final ConfigManager configManager;
	private final SystemInterfaceConfig config;
	private final Runnable onManualSectionToggle;
	private final Runnable onContentEngaged;

	/** Whether the player is on a free (non-members) world. Set from the client thread by the plugin. */
	private volatile boolean freeWorld;

	private final JLabel sectionHeaderLabel = new JLabel("Combat", SwingConstants.CENTER);
	private final JLabel sectionArrow = new JLabel();
	private final JPanel sectionBody = new JPanel();
	private final JLabel contextTargetLabel = new JLabel(" ", SwingConstants.CENTER);
	private final JLabel portraitLabel = new JLabel("", SwingConstants.CENTER);
	private final JPanel contextTrackArea = new JPanel();
	private final JPanel sourceListPanel = new JPanel();

	private boolean sectionBodyAdded = true;
	private boolean sectionExpanded = true;

	private String selectedTarget;
	/** True when the current view was opened by explicit search — bypasses the engage gate. */
	private boolean browsing;

	/** The target currently shown in the section-level context area (portrait + Track table), or null. */
	private String contextTarget;
	/** The source-row column name auto-expanded by {@link #applyCombatFocus}, or null. */
	private String autoExpandedSource;
	/**
	 * Sources the user manually collapsed while they were the current {@link #autoExpandedSource}.
	 * Transient (never persisted) — cleared whenever the auto-expand target changes, so it only
	 * overrides the auto-target's default-open behavior for the current auto-expand episode and
	 * never corrupts the persisted {@link CollapseStateStore} bit for that source.
	 */
	private final Set<String> autoCollapsedOverride = new HashSet<>();

	public CombatSection(
		StateTracker stateTracker,
		LootTables lootTables,
		ItemManager itemManager,
		ItemMembership itemMembership,
		ItemNameCache itemNameCache,
		CollapseStateStore collapseStateStore,
		PortraitService portraitService,
		ConfigManager configManager,
		SystemInterfaceConfig config,
		Runnable onManualSectionToggle,
		Runnable onContentEngaged)
	{
		super();
		this.stateTracker = stateTracker;
		this.lootTables = lootTables;
		this.itemManager = itemManager;
		this.itemMembership = itemMembership;
		this.itemNameCache = itemNameCache;
		this.collapseStateStore = collapseStateStore;
		this.portraitService = portraitService;
		this.configManager = configManager;
		this.config = config;
		this.onManualSectionToggle = onManualSectionToggle;
		this.onContentEngaged = onContentEngaged;

		// Refresh the context portrait when an async fetch lands, if it's our current context target.
		portraitService.setLoadListener(target -> SwingUtilities.invokeLater(() ->
		{
			if (target.equals(contextTarget))
			{
				updatePortrait(target);
			}
		}));

		setLayout(new DynamicGridLayout(0, 1, 0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildSectionHeader());

		sectionBody.setLayout(new DynamicGridLayout(0, 1, 0, 0));
		sectionBody.setBackground(ColorScheme.DARK_GRAY_COLOR);

		contextTargetLabel.setForeground(ACCENT);
		contextTargetLabel.setBorder(new EmptyBorder(4, 0, 4, 0));
		contextTargetLabel.setText("<html><div style='text-align:center;'>No current target</div></html>");
		sectionBody.add(contextTargetLabel);

		// Portrait (wiki image), populated when a context target is selected and available.
		portraitLabel.setBorder(new EmptyBorder(4, 0, 4, 0));
		sectionBody.add(portraitLabel);

		contextTrackArea.setLayout(new DynamicGridLayout(0, 1, 0, 0));
		contextTrackArea.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sectionBody.add(contextTrackArea);

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
	 * Expand/collapse the section body, show the section-level context (portrait + Track
	 * table) for {@code contextTarget} (null hides both), and auto-expand the
	 * {@code autoExpandedSource} column (null = none). The auto-target's open/closed state is
	 * tracked transiently for the duration of this auto-expand episode — a click on it while
	 * it is the auto-target never touches the persisted {@link CollapseStateStore} bit, so the
	 * user's real manual collapse intent for that source is preserved once auto-expand moves on.
	 */
	public void applyCombatFocus(boolean expandSection, String contextTarget, String autoExpandedSource)
	{
		SwingUtilities.invokeLater(() ->
		{
			this.sectionExpanded = expandSection;
			this.contextTarget = contextTarget;
			if (!java.util.Objects.equals(this.autoExpandedSource, autoExpandedSource))
			{
				// New auto-expand episode (including auto-expand ending, i.e. becoming null) —
				// any transient override from the previous episode no longer applies.
				autoCollapsedOverride.clear();
			}
			this.autoExpandedSource = autoExpandedSource;
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

	/** Sets the current target (from engage/appraise) and rebuilds. Respects the engage gate. */
	public void setCurrentTarget(String target)
	{
		SwingUtilities.invokeLater(() ->
		{
			selectedTarget = target;
			browsing = false; // came from in-combat / appraise -> respect the engage gate
			refresh();
		});
	}

	/**
	 * Records whether the player is on a free (non-members) world, then re-renders so the
	 * members-only drop filter reflects the current world. Must be safe to call off the EDT.
	 */
	public void setFreeWorld(boolean freeWorld)
	{
		if (this.freeWorld == freeWorld)
		{
			return;
		}
		this.freeWorld = freeWorld;
		SwingUtilities.invokeLater(this::refresh);
	}

	/** Rebuilds the header and the source-row list. Safe to call from any thread. */
	public void refresh()
	{
		SwingUtilities.invokeLater(this::rebuild);
	}

	/** Updates the context portrait for {@code target}, clearing it first (mirrors the original panel). */
	private void updatePortrait(String target)
	{
		portraitLabel.setIcon(null);
		if (target != null)
		{
			DropTable table = lootTables.forTarget(target);
			BufferedImage img = portraitService.get(target, table != null ? table.getImageFile() : null);
			if (img != null)
			{
				portraitLabel.setIcon(new ImageIcon(img));
			}
		}
		portraitLabel.revalidate();
		portraitLabel.repaint();
	}

	private void rebuild()
	{
		contextTargetLabel.setText(contextTarget != null
			? "<html><div style='text-align:center;font-size:13pt;'><b>" + contextTarget + "</b></div></html>"
			: "<html><div style='text-align:center;'>No current target</div></html>");
		updatePortrait(contextTarget);

		contextTrackArea.removeAll();
		if (contextTarget != null)
		{
			TargetState contextState = stateTracker.get(contextTarget);
			contextTrackArea.add(buildTrackSection(contextTarget, contextState));
		}
		contextTrackArea.revalidate();
		contextTrackArea.repaint();

		sourceListPanel.removeAll();

		// One row per source that's either engaged/has KC, or is the currently-browsed target.
		final List<TargetState> sources = stateTracker.getRecentProfitTargets(PROFIT_STALE_MILLIS);
		final Set<String> seen = new HashSet<>();
		for (TargetState t : sources)
		{
			seen.add(t.getName());
			addSourceRow(t.getName(), t);
		}
		// The currently selected target may not have profit history yet (e.g. just engaged,
		// no loot received) but should still show a row so it can be found/expanded.
		if (selectedTarget != null && !seen.contains(selectedTarget))
		{
			TargetState state = stateTracker.get(selectedTarget);
			if (browsing || (state != null && state.isEngaged()))
			{
				addSourceRow(selectedTarget, state);
			}
		}

		sourceListPanel.revalidate();
		sourceListPanel.repaint();
		revalidate();
		repaint();
	}

	// ---------------------------------------------------------------------
	// Collapsed / expanded source rows (ledger only — no portrait, no Track grid)
	// ---------------------------------------------------------------------

	private void addSourceRow(String name, TargetState state)
	{
		final int kc = state != null ? state.getCurrentKc() : 0;
		final boolean isAutoExpanded = name.equals(autoExpandedSource);
		// The auto-target renders from the transient override only (open by default for the
		// episode; closed only if the user toggled it shut this episode) — the persisted store
		// is never consulted for it, so auto-expand can never corrupt the user's real manual
		// collapse intent for this source. Any other row still reads its persisted state as today.
		final boolean collapsed = isAutoExpanded
			? autoCollapsedOverride.contains(name)
			: collapseStateStore.isSectionCollapsed(SOURCE_ROW_KEY, name);

		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, LOOT_COMBAT),
			new EmptyBorder(3, 6, 3, 4)));

		JLabel titleLabel = new JLabel(name);
		titleLabel.setForeground(Color.WHITE);
		header.add(titleLabel, BorderLayout.WEST);

		JPanel right = new JPanel(new BorderLayout(6, 0));
		right.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel kcLabel = new JLabel("KC: " + kc);
		kcLabel.setForeground(new Color(170, 170, 170));
		right.add(kcLabel, BorderLayout.CENTER);
		JLabel arrow = new JLabel(collapsed ? "˄" : "˅");
		arrow.setForeground(new Color(150, 150, 150));
		right.add(arrow, BorderLayout.EAST);
		header.add(right, BorderLayout.EAST);

		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (name.equals(autoExpandedSource))
				{
					// Toggle the transient episode override only — leave the persisted bit
					// untouched so the row reverts to the user's real stored state once this
					// source is no longer the auto-target.
					if (!autoCollapsedOverride.remove(name))
					{
						autoCollapsedOverride.add(name);
					}
				}
				else
				{
					collapseStateStore.toggleSection(SOURCE_ROW_KEY, name);
				}
				selectedTarget = name;
				browsing = true; // clicking a row browses it like a search hit
				onContentEngaged.run(); // user engaged this section's content: pin it open
				refresh();
			}
		});
		sourceListPanel.add(header);

		if (collapsed)
		{
			return;
		}

		sourceListPanel.add(buildExpandedRow(name, state, kc));
	}

	private JPanel buildExpandedRow(String name, TargetState state, int kc)
	{
		JPanel body = new JPanel();
		body.setLayout(new DynamicGridLayout(0, 1, 0, 2));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setBorder(new EmptyBorder(4, 10, 6, 4));

		final long kept = state != null ? state.getKeptValue() : 0L;

		body.add(statRow("KC", String.valueOf(kc)));
		body.add(statRow("Rewards", PanelFormat.gp(kept)));
		body.add(statRow("Costs", PanelFormat.gp(0)));
		body.add(statRow("Net", PanelFormat.gp(kept)));

		// Loot: itemised kept quantities for this source, reusing the shared ledger-row builder.
		JLabel lootHeader = new JLabel("Loot");
		lootHeader.setForeground(ACCENT);
		lootHeader.setFont(lootHeader.getFont().deriveFont(Font.BOLD, 12f));
		lootHeader.setBorder(new EmptyBorder(6, 0, 2, 0));
		body.add(lootHeader);

		final Map<Integer, Integer> keptItems = state != null ? state.getKeptItems() : java.util.Collections.emptyMap();
		if (keptItems.isEmpty())
		{
			JLabel none = new JLabel("None yet");
			none.setForeground(new Color(150, 150, 150));
			body.add(none);
		}
		else
		{
			keptItems.entrySet().stream()
				.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
				.forEach(e -> body.add(ItemRowFactory.buildLedgerRow(itemManager,
					new ItemRowFactory.LedgerRow(e.getKey(), itemName(e.getKey()), e.getValue()))));
		}

		JLabel supplies = new JLabel("Supplies: None yet");
		supplies.setForeground(new Color(150, 150, 150));
		supplies.setBorder(new EmptyBorder(6, 0, 0, 0));
		body.add(supplies);

		return body;
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
	// Section-level Track list (the former per-column "what can drop" rarity grid)
	// ---------------------------------------------------------------------

	private JPanel buildTrackSection(String target, TargetState state)
	{
		JPanel wrap = new JPanel();
		wrap.setLayout(new DynamicGridLayout(0, 1, 0, 0));
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.setBorder(new EmptyBorder(6, 0, 0, 0));

		// This builder is now used solely for the section-level context Track table, which is only
		// rendered (by rebuild()) when contextTarget != null — i.e. a deliberate select OR a live
		// engagement. Both cases must show the pickable trackable grid so the player can pre-pick
		// drops before engaging (spec §"Selection vs. interaction"); live combat already reveals it.
		// So there is intentionally no engage-gate here: contextTarget != null implies "reveal".
		DropTable table = lootTables.forTarget(target);
		if (table == null)
		{
			JLabel fetching = new JLabel("Fetching loot table...");
			fetching.setForeground(new Color(150, 150, 150));
			fetching.setBorder(new EmptyBorder(4, 4, 4, 4));
			wrap.add(fetching);
			return wrap;
		}

		final String currentTracked = configManager.getConfiguration(SystemInterfaceConfig.GROUP, "trackedItem");

		final int combatLvl = state != null ? state.getCombatLevel()
			: (table.getCombatLevel() > 0 ? table.getCombatLevel() : 0);
		final boolean hideMembersDrops = freeWorld
			&& !BossAliases.isBossOrHighTier(target, combatLvl);

		// Dedupe: keep most common rate per name
		int hiddenMembersDrops = 0;
		Map<String, DropTable.Entry> bestByName = new LinkedHashMap<>();
		for (DropTable.Entry entry : table.getDrops())
		{
			if (entry.getName() == null || entry.getRate() <= 0.0) continue;
			if (entry.getName().equalsIgnoreCase("Nothing")) continue;
			if (hideMembersDrops && itemMembership.isMembers(entry.getName()))
			{
				hiddenMembersDrops++;
				continue;
			}
			DropTable.Entry prev = bestByName.get(entry.getName());
			if (prev == null || entry.getRate() > prev.getRate())
			{
				bestByName.put(entry.getName(), entry);
			}
		}

		List<DropTable.Entry> sorted = new ArrayList<>(bestByName.values());
		sorted.sort(Comparator.comparingDouble(DropTable.Entry::getRate));

		List<DropTable.Entry> veryRare = new ArrayList<>();
		List<DropTable.Entry> rare = new ArrayList<>();
		List<DropTable.Entry> uncommon = new ArrayList<>();
		List<DropTable.Entry> common = new ArrayList<>();

		for (DropTable.Entry e : sorted)
		{
			long denom = Math.max(1L, Math.round(1.0 / e.getRate()));
			if (denom >= 256) veryRare.add(e);
			else if (denom >= 64) rare.add(e);
			else if (denom >= 10) uncommon.add(e);
			else common.add(e);
		}

		if (hideMembersDrops && hiddenMembersDrops > 0)
		{
			JLabel f2pNote = new JLabel(
				"Free world — " + hiddenMembersDrops + " members drop" + (hiddenMembersDrops == 1 ? "" : "s") + " hidden",
				SwingConstants.CENTER);
			f2pNote.setForeground(new Color(150, 150, 150));
			f2pNote.setFont(f2pNote.getFont().deriveFont(Font.ITALIC, 10f));
			f2pNote.setBorder(new EmptyBorder(4, 0, 2, 0));
			wrap.add(f2pNote);
		}

		addRarityGrid(wrap, target, "Very Rare", RARITY_VERY_RARE, veryRare, state, currentTracked);
		addRarityGrid(wrap, target, "Rare", RARITY_RARE, rare, state, currentTracked);
		addRarityGrid(wrap, target, "Uncommon", RARITY_UNCOMMON, uncommon, state, currentTracked);
		addRarityGrid(wrap, target, "Common", RARITY_COMMON, common, state, currentTracked);

		// Multi-drop completion line: when 2+ items are tracked on this mob,
		// show expected kills to obtain all of them.
		Set<String> trackedSet = getTrackedItems();
		List<Double> trackedRates = new ArrayList<>();
		for (String item : trackedSet)
		{
			DropTable.Entry e = bestByName.get(item);
			if (e == null)
			{
				for (DropTable.Entry candidate : bestByName.values())
				{
					if (candidate.getName().equalsIgnoreCase(item))
					{
						e = candidate;
						break;
					}
				}
			}
			if (e != null)
			{
				trackedRates.add(e.getRate());
			}
		}
		if (trackedRates.size() >= 2)
		{
			double[] rates = new double[trackedRates.size()];
			for (int i = 0; i < trackedRates.size(); i++)
			{
				rates[i] = trackedRates.get(i);
			}
			double expected = Probability.expectedKillsForAll(rates);
			String countWord = trackedRates.size() == 2 ? "both" : "all " + trackedRates.size();

			JLabel completionHeader = new JLabel("Completion:", SwingConstants.CENTER);
			completionHeader.setForeground(ACCENT);
			completionHeader.setFont(completionHeader.getFont().deriveFont(Font.BOLD, 13f));
			completionHeader.setBorder(new EmptyBorder(8, 0, 0, 0));
			wrap.add(completionHeader);

			JLabel completionValue = new JLabel(
				"~" + String.format("%,d", Math.round(expected)) + " kills for " + countWord,
				SwingConstants.CENTER);
			completionValue.setForeground(new Color(220, 220, 220));
			completionValue.setFont(completionValue.getFont().deriveFont(Font.BOLD, 12f));
			completionValue.setBorder(new EmptyBorder(2, 0, 6, 0));
			wrap.add(completionValue);
		}

		return wrap;
	}

	private void addRarityGrid(JPanel parent, String target, String label, Color color, List<DropTable.Entry> entries,
		TargetState state, String currentTracked)
	{
		if (entries.isEmpty()) return;

		final boolean collapsed = collapseStateStore.isSectionCollapsed(target, label);

		// Clickable header — click to collapse/expand this rarity table. State
		// is remembered per mob (same store/keying as the original panel).
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(HEADER_BG);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setBorder(new EmptyBorder(3, 2, 3, 4));
		JLabel title = new JLabel(label + " (" + entries.size() + ")");
		title.setForeground(color);
		header.add(title, BorderLayout.WEST);
		JLabel arrow = new JLabel(collapsed ? "˄" : "˅");
		arrow.setForeground(color);
		header.add(arrow, BorderLayout.EAST);
		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				collapseStateStore.toggleSection(target, label);
				refresh();
			}
		});
		parent.add(header);

		if (collapsed)
		{
			return;
		}

		int rows = (entries.size() + ITEMS_PER_ROW - 1) / ITEMS_PER_ROW;
		JPanel grid = new JPanel(new GridLayout(rows, ITEMS_PER_ROW, 1, 1));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.setMaximumSize(new Dimension(ITEMS_PER_ROW * (ITEM_SIZE.width + 2), rows * (ITEM_SIZE.height + 2)));

		for (DropTable.Entry entry : entries)
		{
			final String itemName = entry.getName();
			final boolean isTracked = csvContains(currentTracked, itemName);
			final int drops = state != null ? state.countOf(itemName) : 0;
			grid.add(ItemRowFactory.createItemSlot(itemManager, ITEM_SIZE, color, SELECTED_BORDER,
				itemName, resolveItemId(itemName), entry.getRate(), drops, isTracked,
				entry.isRareDropTable(), () ->
				{
					toggleTracked(itemName); // click to track, click again to untrack
					refresh();
				}));
		}
		int remainder = (rows * ITEMS_PER_ROW) - entries.size();
		for (int i = 0; i < remainder; i++)
		{
			grid.add(ItemRowFactory.blankSlot(ITEM_SIZE));
		}

		parent.add(grid);
	}

	// ---------------------------------------------------------------------
	// Tracked-item set (stored as a pipe-separated list in the trackedItem config).
	// Preserved byte-identical to the original SystemInterfacePanel implementation so
	// the Target Status overlay/luck engine, which reads the same config key, is unaffected.
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

	/**
	 * Display name for an item id, falling back to {@code "Item <id>"} until {@link ItemNameCache}
	 * resolves it on the client thread (never calls {@code getItemComposition} on the EDT).
	 */
	private String itemName(int itemId)
	{
		return itemNameCache.name(itemId);
	}

	private int resolveItemId(String name)
	{
		Integer override = ITEM_ID_OVERRIDES.get(name.toLowerCase());
		if (override != null) return override;

		return itemManager.search(name).stream()
			.filter(r -> r.getName().equalsIgnoreCase(name))
			.findFirst()
			.map(r -> r.getId())
			.orElseGet(() -> itemManager.search(name).stream()
				.findFirst()
				.map(r -> r.getId())
				.orElse(-1));
	}
}
