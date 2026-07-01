package com.systeminterface.modules.ui;

import com.systeminterface.core.SystemInterfaceConfig;
import com.systeminterface.common.probability.LuckStatus;
import com.systeminterface.services.drops.DropTable;
import com.systeminterface.services.drops.LootTables;
import com.systeminterface.services.lookup.BossAliases;
import com.systeminterface.services.lookup.ItemMembership;
import com.systeminterface.services.portrait.PortraitService;
import com.systeminterface.common.probability.Probability;
import com.systeminterface.modules.skills.PetDisplay;
import com.systeminterface.modules.skills.ResourceData;
import com.systeminterface.modules.skills.SkillTracker;
import com.systeminterface.services.state.StateTracker;
import com.systeminterface.services.state.TargetState;
import net.runelite.api.Skill;
import java.awt.BorderLayout;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

public class SystemInterfacePanel extends PluginPanel
{
	private static final Color ACCENT = new Color(120, 200, 255);
	private static final Color HEADER_BG = new Color(30, 30, 36);
	private static final Color HEADER_HOVER = new Color(50, 50, 58);
	private static final Color SELECTED_BORDER = new Color(255, 220, 50);
	private static final Color RARITY_COMMON = new Color(160, 160, 160);
	private static final Color RARITY_UNCOMMON = new Color(30, 175, 30);
	private static final Color RARITY_RARE = new Color(60, 120, 230);
	private static final Color RARITY_VERY_RARE = new Color(170, 70, 220);

	private static final int ITEMS_PER_ROW = 3;
	private static final Dimension ITEM_SIZE = new Dimension(62, 56);
	private static final Color RECEIVED_BADGE = new Color(0, 180, 0);
	static final String SECTION_SESSION_SUMMARY = "Session Summary";
	static final String SECTION_COMBAT = "Combat";
	static final String SECTION_SKILLING = "Skilling";
	static final String SECTION_SEARCH_LOOKUP = "Search / Lookup";
	private static final int FIRST_SECTION_INDEX = 1; // Keep the title fixed at the top.

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
	private final ConfigManager configManager;
	private final PortraitService portraitService;
	private final ItemMembership itemMembership;
	private final CollapseStateStore collapseStateStore;
	private final SkillTracker skillTracker;
	private final ResourceData resourceData;
	private final SystemInterfaceConfig config;

	/** Historical namespace for per-source Loot / Rewards collapse state in {@link CollapseStateStore}. */
	private static final String LOOT_LOG_KEY = "__lootlog__";

	/** Whether the player is on a free (non-members) world. Set from the client thread by the plugin. */
	private volatile boolean freeWorld;

	private final JTextField searchField = new JTextField();
	private final JPanel combatBody = new JPanel();
	private final JLabel combatArrow = new JLabel("-");
	private final JLabel targetNameLabel = new JLabel(" ");
	private final JLabel portraitLabel = new JLabel("", SwingConstants.CENTER);
	private final JLabel trackedLabel = new JLabel("Tracked: None");
	private final JPanel sessionSummaryBody = new JPanel();
	private final JPanel combatTrackingPanel = new JPanel();
	private final JLabel profitSessionLabel = new JLabel(" ", SwingConstants.CENTER);
	private final JLabel profitAllTimeLabel = new JLabel(" ", SwingConstants.CENTER);
	private final JTextField lootSearchField = new JTextField();
	private final JPanel profitMobsPanel = new JPanel();
	private final JPanel skillingBody = new JPanel();
	private final Clock summaryClock = Clock.systemDefaultZone();
	private LocalDate currentSummaryDay = LocalDate.now(summaryClock);
	private String selectedTarget;
	private Skill selectedSourceSkill;
	private String selectedSourceAction;
	private String selectedSourceName;
	private List<ResourceData.ResourceEntry> selectedSourceEntries = Collections.emptyList();
	private String selectedResourceName;
	private ResourceData.ResourceEntry selectedResourceEntry;
	private List<ResourceData.ResourceEntry> selectedResourceEntries = Collections.emptyList();
	/** True when the current view was opened by explicit search — bypasses the engage gate. */
	private boolean browsing;
	private boolean combatExpanded = true;
	private String autoOpenedCombatSourceKey;
	private String autoOpenedSkillingSourceKey;
	private boolean autoOpenedCombatSection;
	private boolean autoOpenedSkillingSection;

	/** Top-level container holding all sections; kept as a field so the loot-log
	 *  section can be added/removed when its feature toggle changes. */
	private JPanel content;
	private JPanel combatSection;
	/** Historical section field retained so config toggles can remain harmless. */
	private JPanel lootLogSection;
	private boolean lootLogAttached;
	private JPanel skillingSection;
	private JLabel skillingArrow;
	private boolean skillingExpanded = true;
	private JPanel lookupSection;

	// Live skilling-stat labels — updated in place each tick (see updateSkillingLiveStats)
	// rather than by rebuilding the section, so the figures track XpTrackerService live.
	private JLabel liveXpHrValue;
	private JLabel liveResHrValue;
	private JLabel liveTimeValue;
	private String cachedXpHr = "—";
	private String cachedResHr = "—";
	private String cachedTime = "—";
	/** True when the live stats are frozen (skill idle) — used to skip work while idle. */
	private boolean liveStale = true;
	private static final Color LIVE_COLOR = ACCENT;
	private static final Color STALE_COLOR = new Color(120, 120, 120);

	static List<String> visibleSectionOrder(boolean showLootRewards)
	{
		List<String> order = new ArrayList<>();
		order.add(SECTION_SEARCH_LOOKUP);
		order.add(SECTION_SESSION_SUMMARY);
		order.add(SECTION_COMBAT);
		order.add(SECTION_SKILLING);
		return Collections.unmodifiableList(order);
	}

	public SystemInterfacePanel(
		StateTracker stateTracker,
		LootTables lootTables,
		ItemManager itemManager,
		ConfigManager configManager,
		PortraitService portraitService,
		ItemMembership itemMembership,
		CollapseStateStore collapseStateStore,
		SkillTracker skillTracker,
		ResourceData resourceData,
		SystemInterfaceConfig config)
	{
		super();
		this.stateTracker = stateTracker;
		this.lootTables = lootTables;
		this.itemManager = itemManager;
		this.configManager = configManager;
		this.portraitService = portraitService;
		this.itemMembership = itemMembership;
		this.collapseStateStore = collapseStateStore;
		this.skillTracker = skillTracker;
		this.resourceData = resourceData;
		this.config = config;

		// Refresh the portrait when an async fetch lands, if it's our current target.
		portraitService.setLoadListener(target -> SwingUtilities.invokeLater(() ->
		{
			if (target.equals(selectedTarget))
			{
				updatePortrait(target);
			}
		}));

		content = new JPanel();
		content.setLayout(new DynamicGridLayout(0, 1, 0, 0));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Title
		JLabel title = new JLabel("System Interface");
		title.setForeground(ACCENT);
		title.setBorder(new EmptyBorder(6, 6, 6, 0));
		content.add(title);

		// Each top-level section lives in its own wrapper so that
		// add/remove of the body never affects ordering in `content`.
		combatSection = new JPanel(new DynamicGridLayout(0, 1, 0, 0));
		combatSection.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Combat header with arrow on right
		JPanel headerPanel = new JPanel(new BorderLayout());
		headerPanel.setBackground(HEADER_BG);
		headerPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		headerPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(6, 8, 6, 8)));

		JLabel combatLabel = new JLabel(SECTION_COMBAT);
		combatLabel.setForeground(Color.WHITE);
		headerPanel.add(combatLabel, BorderLayout.WEST);

		combatArrow.setForeground(Color.WHITE);
		headerPanel.add(combatArrow, BorderLayout.EAST);

		headerPanel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShowResetMenu(e);
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger())
				{
					return;
				}
				setCombatExpandedManually(!combatExpanded);
			}
			@Override
			public void mouseEntered(MouseEvent e) { headerPanel.setBackground(HEADER_HOVER); }
			@Override
			public void mouseExited(MouseEvent e) { headerPanel.setBackground(HEADER_BG); }

			private void maybeShowResetMenu(MouseEvent e)
			{
				if (!SwingUtilities.isRightMouseButton(e) && !e.isPopupTrigger())
				{
					return;
				}
				e.consume();
				JPopupMenu menu = new JPopupMenu();
				JMenuItem reset = new JMenuItem("Reset all combat logs");
				reset.addActionListener(evt -> resetAllCombatLogs());
				menu.add(reset);
				menu.show(headerPanel, e.getX(), e.getY());
			}
		});
		combatSection.add(headerPanel);

		// Combat body
		combatBody.setLayout(new DynamicGridLayout(0, 1, 0, 0));
		combatBody.setBackground(ColorScheme.DARK_GRAY_COLOR);
		combatBody.setBorder(new EmptyBorder(4, 4, 4, 4));

		final JPanel lookupBody = new JPanel();
		lookupBody.setLayout(new DynamicGridLayout(0, 1, 0, 2));
		lookupBody.setBackground(ColorScheme.DARK_GRAY_COLOR);
		lookupBody.setBorder(new EmptyBorder(4, 4, 4, 4));

		// Target/source lookup. It is its own panel section so Combat stays focused on
		// current target tracking and watched drops rather than becoming a duplicate Appraise view.
		searchField.setText("Search...");
		searchField.setForeground(Color.GRAY);
		searchField.addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override
			public void focusGained(java.awt.event.FocusEvent e)
			{
				if (searchField.getText().equals("Search..."))
				{
					searchField.setText("");
					searchField.setForeground(Color.WHITE);
				}
			}
			@Override
			public void focusLost(java.awt.event.FocusEvent e)
			{
				if (searchField.getText().isEmpty())
				{
					searchField.setText("Search...");
					searchField.setForeground(Color.GRAY);
				}
			}
		});
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override public void insertUpdate(DocumentEvent e) { onSearchChanged(); }
			@Override public void removeUpdate(DocumentEvent e) { onSearchChanged(); }
			@Override public void changedUpdate(DocumentEvent e) { onSearchChanged(); }
		});
		lookupBody.add(searchField);

		JPanel searchBar = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
		searchBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		searchBar.setBorder(new EmptyBorder(4, 4, 6, 4));
		JLabel lookupTitle = new JLabel(SECTION_SEARCH_LOOKUP);
		lookupTitle.setForeground(Color.WHITE);
		lookupTitle.setBorder(new EmptyBorder(0, 4, 1, 0));
		searchBar.add(lookupTitle);
		searchBar.add(lookupBody);
		content.add(searchBar);

		// NPC name - use HTML for guaranteed large font
		targetNameLabel.setForeground(ACCENT);
		targetNameLabel.setHorizontalAlignment(SwingConstants.CENTER);
		targetNameLabel.setBorder(new EmptyBorder(6, 0, 0, 0));
		combatBody.add(targetNameLabel);

		// Portrait (wiki image), populated when a target is selected and available.
		portraitLabel.setBorder(new EmptyBorder(4, 0, 4, 0));
		combatBody.add(portraitLabel);

		// Tracked label
		trackedLabel.setForeground(new Color(200, 200, 200));
		trackedLabel.setHorizontalAlignment(SwingConstants.CENTER);
		trackedLabel.setBorder(new EmptyBorder(0, 0, 4, 0));
		combatBody.add(trackedLabel);

		combatTrackingPanel.setLayout(new DynamicGridLayout(0, 1, 0, 2));
		combatTrackingPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		combatTrackingPanel.setBorder(new EmptyBorder(6, 0, 4, 0));
		combatBody.add(combatTrackingPanel);

		combatSection.add(combatBody);

		// --- Session Summary ---
		sessionSummaryBody.setLayout(new DynamicGridLayout(0, 1, 0, 2));
		sessionSummaryBody.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sessionSummaryBody.setBorder(new EmptyBorder(4, 4, 6, 4));

		lootLogSection = new JPanel(new DynamicGridLayout(0, 1, 0, 0));
		lootLogSection.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel profitHeaderPanel = new JPanel(new BorderLayout());
		profitHeaderPanel.setBackground(HEADER_BG);
		profitHeaderPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(6, 8, 6, 8)));
		JLabel profitTitle = new JLabel(SECTION_SESSION_SUMMARY);
		profitTitle.setForeground(Color.WHITE);
		profitHeaderPanel.add(profitTitle, BorderLayout.WEST);
		lootLogSection.add(profitHeaderPanel);
		lootLogSection.add(sessionSummaryBody);
		content.add(lootLogSection);
		lootLogAttached = true;
		content.add(combatSection);

		// --- Skilling ---
		skillingBody.setLayout(new DynamicGridLayout(0, 1, 0, 2));
		skillingBody.setBackground(ColorScheme.DARK_GRAY_COLOR);
		skillingBody.setBorder(new EmptyBorder(4, 4, 4, 4));

		skillingSection = new JPanel(new DynamicGridLayout(0, 1, 0, 0));
		skillingSection.setBackground(ColorScheme.DARK_GRAY_COLOR);

		skillingArrow = new JLabel("-");
		skillingArrow.setForeground(Color.WHITE);
		JPanel skillingHeaderPanel = new JPanel(new BorderLayout());
		skillingHeaderPanel.setBackground(HEADER_BG);
		skillingHeaderPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		skillingHeaderPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(6, 8, 6, 8)));
		JLabel skillingTitle = new JLabel(SECTION_SKILLING);
		skillingTitle.setForeground(Color.WHITE);
		skillingHeaderPanel.add(skillingTitle, BorderLayout.WEST);
		skillingHeaderPanel.add(skillingArrow, BorderLayout.EAST);
		skillingHeaderPanel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShowResetMenu(e);
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger())
				{
					return;
				}
				setSkillingExpandedManually(!skillingExpanded);
			}
			@Override
			public void mouseEntered(MouseEvent e) { skillingHeaderPanel.setBackground(HEADER_HOVER); }
			@Override
			public void mouseExited(MouseEvent e) { skillingHeaderPanel.setBackground(HEADER_BG); }

			private void maybeShowResetMenu(MouseEvent e)
			{
				if (!SwingUtilities.isRightMouseButton(e) && !e.isPopupTrigger())
				{
					return;
				}
				e.consume();
				JPopupMenu menu = new JPopupMenu();
				JMenuItem reset = new JMenuItem("Reset all skilling logs");
				reset.addActionListener(evt -> resetAllSkillingLogs());
				menu.add(reset);
				menu.show(skillingHeaderPanel, e.getX(), e.getY());
			}
		});
		skillingSection.add(skillingHeaderPanel);
		skillingSection.add(skillingBody);
		content.add(skillingSection);

		// Top-align content; RuneLite's PluginPanel provides the outer scroll.
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.add(content, BorderLayout.NORTH);

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(wrapper, BorderLayout.CENTER);

		updateTrackedLabel();
		refreshProfit();
		refreshSkilling();

		revalidate();
		repaint();
	}

	/**
	 * Adds or removes the Loot / Rewards section from the panel to match the
	 * {@link SystemInterfaceConfig#showLootLog()} toggle. It is inserted immediately after
	 * the title so ordering stays stable across toggles. Must run on the EDT.
	 */
	private void applyLootLogVisibility()
	{
		final boolean show = true;
		if (show && !lootLogAttached)
		{
			final int idx = Math.min(FIRST_SECTION_INDEX + 1, content.getComponentCount());
			content.add(lootLogSection, idx);
			lootLogAttached = true;
		}
		else if (!show && lootLogAttached)
		{
			content.remove(lootLogSection);
			lootLogAttached = false;
		}
		else
		{
			return;
		}
		content.revalidate();
		content.repaint();
		revalidate();
		repaint();
	}

	/** Re-applies feature toggles that affect panel layout. Called by the plugin on config change. */
	public void applyConfigToggles()
	{
		SwingUtilities.invokeLater(this::applyLootLogVisibility);
	}

	public void setCurrentTarget(String target)
	{
		SwingUtilities.invokeLater(() ->
		{
			selectedTarget = target;
			clearSelectedSkillSource();
			clearSelectedSkillResource();
			browsing = false; // came from in-combat / appraise → respect the engage gate
			collapseAutoOpenedSkillingSource(null);
			collapseAutoOpenedSkillingSection();
			showDropsForTarget(target);
			updatePortrait(target);
			autoOpenCombatSource("combat:" + target);
			refreshProfit();
			refreshSkilling();
		});
	}

	public void setCurrentSkillSourceTarget(Skill skill, String action, String sourceName,
		List<ResourceData.ResourceEntry> entries)
	{
		if (skill == null || action == null || sourceName == null || entries == null || entries.isEmpty())
		{
			return;
		}
		SwingUtilities.invokeLater(() ->
		{
			selectedSourceSkill = skill;
			selectedSourceAction = action;
			selectedSourceName = sourceName;
			selectedSourceEntries = new ArrayList<>(entries);
			clearSelectedSkillResource();
			browsing = false;
			collapseAutoOpenedCombatSource(null);
			collapseAutoOpenedCombatSection();
			autoOpenSkillingSource("skilling:" + skill.name() + ":" + sourceName);
			refreshSkilling();
			refreshProfit();
		});
	}

	public void setCurrentSkillResourceTarget(String resourceName, ResourceData.ResourceEntry resource,
		List<ResourceData.ResourceEntry> entries)
	{
		if (resourceName == null || resource == null)
		{
			return;
		}
		SwingUtilities.invokeLater(() ->
		{
			selectedResourceName = resourceName;
			selectedResourceEntry = resource;
			selectedResourceEntries = entries == null ? Collections.emptyList() : new ArrayList<>(entries);
			clearSelectedSkillSource();
			browsing = false;
			collapseAutoOpenedCombatSource(null);
			collapseAutoOpenedCombatSection();
			autoOpenSkillingSource("skilling:" + resource.getSkill().name());
			refreshSkilling();
			refreshProfit();
		});
	}

	private void setCombatExpandedManually(boolean expanded)
	{
		autoOpenedCombatSection = false;
		setCombatExpanded(expanded);
	}

	private void setSkillingExpandedManually(boolean expanded)
	{
		autoOpenedSkillingSection = false;
		setSkillingExpanded(expanded);
	}

	private void setCombatExpanded(boolean expanded)
	{
		if (combatSection == null)
		{
			return;
		}
		combatExpanded = expanded;
		combatArrow.setText(combatExpanded ? "-" : "+");
		if (combatExpanded)
		{
			if (combatBody.getParent() != combatSection)
			{
				combatSection.add(combatBody);
			}
		}
		else
		{
			combatSection.remove(combatBody);
		}
		combatSection.revalidate();
		content.revalidate();
		revalidate();
		repaint();
	}

	private void setSkillingExpanded(boolean expanded)
	{
		if (skillingSection == null || skillingArrow == null)
		{
			return;
		}
		skillingExpanded = expanded;
		skillingArrow.setText(skillingExpanded ? "-" : "+");
		if (skillingExpanded)
		{
			if (skillingBody.getParent() != skillingSection)
			{
				skillingSection.add(skillingBody);
			}
		}
		else
		{
			skillingSection.remove(skillingBody);
		}
		skillingSection.revalidate();
		content.revalidate();
		revalidate();
		repaint();
	}

	private void autoOpenCombatSource(String sourceKey)
	{
		if (sourceKey == null)
		{
			return;
		}
		collapseAutoOpenedCombatSource(sourceKey);
		autoExpandCombatSection();
		if (collapseStateStore.isSectionCollapsed(LOOT_LOG_KEY, sourceKey))
		{
			collapseStateStore.setSectionExpanded(LOOT_LOG_KEY, sourceKey, true);
			autoOpenedCombatSourceKey = sourceKey;
		}
		else
		{
			autoOpenedCombatSourceKey = null;
		}
	}

	private void autoOpenSkillingSource(String sourceKey)
	{
		if (sourceKey == null)
		{
			return;
		}
		collapseAutoOpenedSkillingSource(sourceKey);
		autoExpandSkillingSection();
		if (collapseStateStore.isSectionCollapsed(LOOT_LOG_KEY, sourceKey))
		{
			collapseStateStore.setSectionExpanded(LOOT_LOG_KEY, sourceKey, true);
			autoOpenedSkillingSourceKey = sourceKey;
		}
		else
		{
			autoOpenedSkillingSourceKey = null;
		}
	}

	private void autoExpandCombatSection()
	{
		if (!combatExpanded)
		{
			setCombatExpanded(true);
			autoOpenedCombatSection = true;
		}
	}

	private void autoExpandSkillingSection()
	{
		if (!skillingExpanded)
		{
			setSkillingExpanded(true);
			autoOpenedSkillingSection = true;
		}
	}

	private void collapseAutoOpenedCombatSource(String nextSourceKey)
	{
		if (autoOpenedCombatSourceKey != null && !autoOpenedCombatSourceKey.equals(nextSourceKey))
		{
			collapseStateStore.setSectionExpanded(LOOT_LOG_KEY, autoOpenedCombatSourceKey, false);
			autoOpenedCombatSourceKey = null;
		}
	}

	private void collapseAutoOpenedSkillingSource(String nextSourceKey)
	{
		if (autoOpenedSkillingSourceKey != null && !autoOpenedSkillingSourceKey.equals(nextSourceKey))
		{
			collapseStateStore.setSectionExpanded(LOOT_LOG_KEY, autoOpenedSkillingSourceKey, false);
			autoOpenedSkillingSourceKey = null;
		}
	}

	private void collapseAutoOpenedCombatSection()
	{
		if (shouldCollapseAutoOpenedSection(autoOpenedCombatSection))
		{
			autoOpenedCombatSection = false;
			setCombatExpanded(false);
		}
	}

	private void collapseAutoOpenedSkillingSection()
	{
		if (shouldCollapseAutoOpenedSection(autoOpenedSkillingSection))
		{
			autoOpenedSkillingSection = false;
			setSkillingExpanded(false);
		}
	}

	private void resetAllCombatLogs()
	{
		stateTracker.resetAllTimeProfit();
		autoOpenedCombatSourceKey = null;
		autoOpenedCombatSection = false;
		refreshAfterLootReset();
	}

	private void resetAllSkillingLogs()
	{
		for (Skill skill : new ArrayList<>(skillTracker.getTrackedSkills()))
		{
			skillTracker.resetSkill(skill);
		}
		autoOpenedSkillingSourceKey = null;
		autoOpenedSkillingSection = false;
		refreshAfterLootReset();
	}

	private void clearSelectedSkillSource()
	{
		selectedSourceSkill = null;
		selectedSourceAction = null;
		selectedSourceName = null;
		selectedSourceEntries = Collections.emptyList();
	}

	private void clearSelectedSkillResource()
	{
		selectedResourceName = null;
		selectedResourceEntry = null;
		selectedResourceEntries = Collections.emptyList();
	}

	public void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (selectedTarget != null) showDropsForTarget(selectedTarget);
			refreshProfit();
			refreshSkilling();
		});
	}

	public void refreshProfitOnly()
	{
		SwingUtilities.invokeLater(this::refreshProfit);
	}

	public void refreshSkillingOnly()
	{
		SwingUtilities.invokeLater(this::refreshSkilling);
	}

	public void collapseAutoOpenedCombatSource()
	{
		if (autoOpenedCombatSourceKey == null && !autoOpenedCombatSection)
		{
			return;
		}
		SwingUtilities.invokeLater(() ->
		{
			collapseAutoOpenedCombatSource(null);
			collapseAutoOpenedCombatSection();
			refreshProfit();
		});
	}

	public void collapseAutoOpenedSkillingSource()
	{
		if (autoOpenedSkillingSourceKey == null && !autoOpenedSkillingSection)
		{
			return;
		}
		SwingUtilities.invokeLater(() ->
		{
			collapseAutoOpenedSkillingSource(null);
			collapseAutoOpenedSkillingSection();
			refreshSkilling();
		});
	}

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

	/** How long since the last interaction before a mob drops off the per-mob profit list. */
	private static final long PROFIT_STALE_MILLIS = 14L * 24 * 60 * 60 * 1000; // 14 days
	private static final int LOOT_ACTIVITY_LIMIT = 3;

	// Loot / Rewards palette - deliberately distinct from the rarity-coloured tracking grid.
	private static final Color LOOT_COMBAT = new Color(200, 80, 60);
	private static final Color LOOT_SKILL = new Color(120, 200, 255);
	private static final Color LOOT_QTY = new Color(0, 190, 0);

	/**
	 * Rebuilds Loot / Rewards: an overall GP summary plus a collapsible, itemised ledger
	 * of what actually dropped - combat loot per mob (from {@link com.systeminterface.services.state.DropOccurrence}
	 * history) and skilling resources per skill (from {@link SkillTracker} counts). This
	 * is the "what did drop" view; it is intentionally styled as a row-based ledger so it
	 * never gets confused with the "what can drop" rarity grid in the Combat section.
	 */
	private void refreshProfit()
	{
		if (sessionSummaryBody != null)
		{
			rollTodayIfNeeded();
			renderSessionSummary();
			renderCombatTrackingSources();
			return;
		}

		StateTracker.ProfitSummary s = stateTracker.accountProfit();

		// Skilling resources have GE value too — value them (gross = total, net = kept) and
		// fold them into the all-time headline so it reflects combat + skilling.
		final long[] skill = skillingProfit();
		final long skillKept = skill[0];
		final long skillTotal = skill[1];
		final long allKept = s.allTimeKept + skillKept;
		final long allTotal = s.allTimeTotal + skillTotal;

		// Session = combat + skilling, matching the all-time headline. Skilling session GP is
		// tracked separately from lifetime by SkillTracker and resets on logout like combat's.
		final long skillSessionKept = skillTracker.getSessionSkillingKeptGp();
		final long skillSessionTotal = skillTracker.getSessionSkillingTotalGp();
		final long sessionKept = s.sessionKept + skillSessionKept;
		final long sessionTotal = s.sessionTotal + skillSessionTotal;
		profitSessionLabel.setText("<html><div style='text-align:center;'>"
			+ "<b>Session</b><br>" + gp(sessionKept) + " / " + gp(sessionTotal) + "</div></html>");
		profitSessionLabel.setToolTipText("Combat: " + gp(s.sessionKept) + " / " + gp(s.sessionTotal)
			+ "  ·  Skilling: " + gp(skillSessionKept) + " / " + gp(skillSessionTotal) + "  (kept / total)");
		profitAllTimeLabel.setText("<html><div style='text-align:center;'>"
			+ "<b>All time</b><br>" + gp(allKept) + " / " + gp(allTotal) + "</div></html>");
		profitAllTimeLabel.setToolTipText("Combat: " + gp(s.allTimeKept) + " / " + gp(s.allTimeTotal)
			+ "  ·  Skilling: " + gp(skillKept) + " / " + gp(skillTotal) + "  (kept / total)");

		profitMobsPanel.removeAll();

		final String lootQuery = lootSearchQuery();
		final boolean searchingLoot = !lootQuery.isEmpty();
		final List<LootActivity> activities = new ArrayList<>();

		for (TargetState t : stateTracker.getTrackedTargets())
		{
			if (t.getTotalDropValue() <= 0)
			{
				continue;
			}
			if (searchingLoot && !containsIgnoreCase(t.getName(), lootQuery))
			{
				continue;
			}
			activities.add(new LootActivity(t.getName(), t.getLastSeen(), () -> renderCombatLootActivity(t)));
		}

		for (Skill sk : skillTracker.getTrackedSkills())
		{
			final SkillTracker.SkillState st = skillTracker.getSkillState(sk);
			if (st == null || st.getResourceCounts().isEmpty())
			{
				continue;
			}
			final String title = capitalize(sk.getName());
			if (searchingLoot && !skillActivityMatchesSearch(title, st, lootQuery))
			{
				continue;
			}
			activities.add(new LootActivity(title, st.getLastSeen(), () -> renderSkillLootActivity(sk, st)));
		}

		activities.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
		int shown = 0;
		for (LootActivity activity : activities)
		{
			if (!searchingLoot && shown >= LOOT_ACTIVITY_LIMIT)
			{
				break;
			}
			activity.render.run();
			shown++;
		}

		if (activities.isEmpty())
		{
			JLabel empty = new JLabel(searchingLoot ? "No matching loot activity." : "No recent loot activity.",
				SwingConstants.CENTER);
			empty.setForeground(new Color(150, 150, 150));
			empty.setBorder(new EmptyBorder(4, 0, 2, 0));
			profitMobsPanel.add(empty);
		}

		profitMobsPanel.revalidate();
		profitMobsPanel.repaint();
		if (activities != null)
		{
			return;
		}

		// Combat sources: itemised loot you actually KEPT per mob (picked up and not
		// later dropped/eaten) — not the full drop history.
		for (TargetState t : stateTracker.getRecentProfitTargets(PROFIT_STALE_MILLIS))
		{
			final String mob = t.getName();
			final List<LootRow> rows = new ArrayList<>();
			t.getKeptItems().entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
				.forEach(e -> rows.add(new LootRow(e.getKey(), itemName(e.getKey()), e.getValue())));

			final String summary = gp(t.getKeptValue()) + " / " + gp(t.getTotalDropValue());
			addLootSource("mob:" + mob, LOOT_COMBAT, mob, summary, rows, () ->
			{
				stateTracker.resetProfit(mob);
				refreshProfit();
			});
		}

		// Skilling sources: itemised resource counts per skill.
		for (Skill sk : skillTracker.getTrackedSkills())
		{
			final SkillTracker.SkillState st = skillTracker.getSkillState(sk);
			if (st == null || st.getResourceCounts().isEmpty())
			{
				continue;
			}
			final List<LootRow> rows = new ArrayList<>();
			final Map<Integer, Long> sourceTotals = new HashMap<>();
			for (SkillTracker.SourceState source : st.getSourceStates().values())
			{
				for (Map.Entry<Integer, Long> entry : source.getResourceCounts().entrySet())
				{
					sourceTotals.merge(entry.getKey(), entry.getValue(), Long::sum);
				}
			}
			st.getResourceCounts().entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
				.forEach(e ->
				{
					final long unassigned = e.getValue() - sourceTotals.getOrDefault(e.getKey(), 0L);
					if (unassigned <= 0)
					{
						return;
					}
					ResourceData.ResourceEntry re = resourceData.forItemId(e.getKey());
					String nm = re != null ? re.getName() : ("Item " + e.getKey());
					rows.add(new LootRow(e.getKey(), nm, unassigned));
				});
			final String summary = String.format("%,d", st.getTotalResources()) + " gathered";
			final boolean skillExpanded =
				addLootSource("skill:" + sk.name(), LOOT_SKILL, capitalize(sk.getName()), summary, rows, null);

			if (skillExpanded)
			{
				for (SkillTracker.SourceState source : st.getSourceStates().values())
				{
					if (source.getResourceCounts().isEmpty())
					{
						continue;
					}
					final List<LootRow> sourceRows = new ArrayList<>();
					source.getResourceCounts().entrySet().stream()
						.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
						.forEach(e ->
						{
							ResourceData.ResourceEntry re = resourceData.forItemId(e.getKey(), sk);
							String nm = re != null ? re.getName() : ("Item " + e.getKey());
							sourceRows.add(new LootRow(e.getKey(), nm, e.getValue()));
						});
					long total = 0L;
					for (long qty : source.getResourceCounts().values())
					{
						total += qty;
					}
					final String sourceName = source.getSourceName();
					final String sourceSummary = String.format("%,d", total) + " gathered";
					addLootSource("skill:" + sk.name() + ":" + sourceName, LOOT_SKILL,
						sourceName, sourceSummary, sourceRows, () ->
						{
							skillTracker.resetSkillSource(sk, sourceName);
							refreshAfterLootReset();
						}, 12);
				}
			}
		}

		profitMobsPanel.revalidate();
		profitMobsPanel.repaint();
	}

	private void rollTodayIfNeeded()
	{
		final LocalDate today = LocalDate.now(summaryClock);
		if (today.equals(currentSummaryDay))
		{
			return;
		}
		currentSummaryDay = today;
		resetTodayTotals();
	}

	private void resetTodayTotals()
	{
		stateTracker.resetSessionProfit();
		skillTracker.resetSessionProgress();
	}

	private SessionTotals sessionTotals()
	{
		StateTracker.ProfitSummary combat = stateTracker.accountProfit();
		final long[] skill = skillingProfit();
		return new SessionTotals(
			combat.sessionTotal + skillTracker.getSessionSkillingTotalGp(),
			0L,
			combat.allTimeTotal + skill[1],
			0L);
	}

	static List<String> sessionSummaryLabels(SessionTotals totals, LocalDate localDay)
	{
		List<String> rows = new ArrayList<>();
		rows.add("Today Rewards|" + gp(totals.todayRewards));
		rows.add("Today Costs|" + gp(totals.todayCosts));
		rows.add("Today Net|" + gp(totals.todayNet()));
		rows.add("All-time Rewards|" + gp(totals.allTimeRewards));
		rows.add("All-time Costs|" + gp(totals.allTimeCosts));
		rows.add("All-time Net|" + gp(totals.allTimeNet()));
		rows.add("Day|" + localDay);
		return Collections.unmodifiableList(rows);
	}

	private void renderSessionSummary()
	{
		sessionSummaryBody.removeAll();
		final SessionTotals totals = sessionTotals();
		addSummaryRow("Today Rewards", gp(totals.todayRewards), this::resetTodayTotals);
		addSummaryRow("Today Costs", gp(totals.todayCosts), this::resetTodayTotals);
		addSummaryRow("Today Net", gp(totals.todayNet()), this::resetTodayTotals);
		sessionSummaryBody.add(Box.createVerticalStrut(2));
		addSummaryRow("All-time Rewards", gp(totals.allTimeRewards), this::resetAllTimeTotals);
		addSummaryRow("All-time Costs", gp(totals.allTimeCosts), this::resetAllTimeTotals);
		addSummaryRow("All-time Net", gp(totals.allTimeNet()), this::resetAllTimeTotals);
		sessionSummaryBody.revalidate();
		sessionSummaryBody.repaint();
	}

	private void resetAllTimeTotals()
	{
		stateTracker.resetAllTimeProfit();
		for (Skill skill : new ArrayList<>(skillTracker.getTrackedSkills()))
		{
			skillTracker.resetSkill(skill);
		}
		refreshAfterLootReset();
	}

	private void addSummaryRow(String label, String value, Runnable resetAction)
	{
		JPanel row = buildTextRow(label, value);
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShowMenu(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShowMenu(e);
			}

			private void maybeShowMenu(MouseEvent e)
			{
				if (!e.isPopupTrigger())
				{
					return;
				}
				JPopupMenu menu = new JPopupMenu();
				JMenuItem reset = new JMenuItem(label.startsWith("All-time") ? "Reset all-time totals" : "Reset today totals");
				reset.addActionListener(evt ->
				{
					resetAction.run();
					refreshAfterLootReset();
				});
				menu.add(reset);
				menu.show(row, e.getX(), e.getY());
			}
		});
		sessionSummaryBody.add(row);
	}

	private void renderCombatTrackingSources()
	{
		combatTrackingPanel.removeAll();
		JLabel header = trackingHeader("Tracking");
		combatTrackingPanel.add(header);
		List<TargetState> targets = new ArrayList<>(stateTracker.getRecentProfitTargets(PROFIT_STALE_MILLIS));
		if (targets.isEmpty())
		{
			addEmptyTrackingRow(combatTrackingPanel, "No combat rewards tracked yet.");
		}
		for (TargetState target : targets)
		{
			final List<LootRow> rows = new ArrayList<>();
			target.getKeptItems().entrySet().stream()
				.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
				.forEach(e -> rows.add(new LootRow(e.getKey(), itemName(e.getKey()), e.getValue())));
			List<DetailRow> details = new ArrayList<>();
			details.add(new DetailRow("KC", String.format("%,d", target.getCurrentKc())));
			details.add(new DetailRow("Rewards", gp(target.getTotalDropValue())));
			details.add(new DetailRow("Costs", gp(0)));
			details.add(new DetailRow("Net", gp(target.getTotalDropValue())));
			addTrackingSource(combatTrackingPanel, "combat:" + target.getName(), LOOT_COMBAT,
				target.getName(), String.format("%,d KC", target.getCurrentKc()), details,
				"Loot", rows, "Supplies", "None", () ->
				{
					stateTracker.resetProfit(target.getName());
					refreshAfterLootReset();
				}, 0);
		}
		combatTrackingPanel.revalidate();
		combatTrackingPanel.repaint();
	}

	private void renderSkillingTrackingSources(JPanel parent)
	{
		JLabel header = trackingHeader("Tracking");
		parent.add(header);
		List<SkillingTrackingRow> rows = skillingTrackingRows();
		if (rows.isEmpty())
		{
			addEmptyTrackingRow(parent, "No skilling rewards tracked yet.");
			return;
		}
		for (SkillingTrackingRow row : rows)
		{
			List<DetailRow> details = new ArrayList<>();
			details.add(new DetailRow("Actions", String.format("%,d", row.actions)));
			details.addAll(row.extraDetails);
			details.add(new DetailRow("Rewards", gp(row.rewards)));
			details.add(new DetailRow("Costs", gp(0)));
			details.add(new DetailRow("Net", gp(row.rewards)));
			addTrackingSource(parent, row.key, LOOT_SKILL,
				row.title, String.format("%,d acts", row.actions), details,
				"Output", row.outputRows, "Supplies", "None", row.onReset, 0);
		}
	}

	private List<SkillingTrackingRow> skillingTrackingRows()
	{
		List<SkillingTrackingRow> rows = new ArrayList<>();
		for (Skill skill : skillTracker.getTrackedSkills())
		{
			SkillTracker.SkillState state = skillTracker.getSkillState(skill);
			if (state == null)
			{
				continue;
			}
			addAggregateSkillingTrackingRow(rows, skill, state);
			for (SkillTracker.SourceState source : state.getSourceStates().values())
			{
				if (source.getResourceCounts().isEmpty()
					&& !(skill == Skill.THIEVING && source.getAttemptedActions() > 0L))
				{
					continue;
				}
				final String sourceName = source.getSourceName();
				final List<DetailRow> extraDetails = thievingSourceDetailRows(skill, source);
				rows.add(new SkillingTrackingRow(
					"skilling:" + skill.name() + ":" + sourceName,
					skillingSourceTitle(skill, source),
					source.getLastSeen(),
					sourceObservedActions(source),
					sourceRewardValue(source.getResourceCounts()),
					lootRowsForCounts(skill, source.getResourceCounts()),
					extraDetails,
					() ->
					{
						skillTracker.resetSkillSource(skill, sourceName);
						refreshAfterLootReset();
					}));
			}
		}
		rows.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
		return rows;
	}

	private void addAggregateSkillingTrackingRow(List<SkillingTrackingRow> rows, Skill skill,
		SkillTracker.SkillState state)
	{
		Map<Integer, Long> unassigned = unassignedSkillCounts(state);
		if (unassigned.isEmpty())
		{
			return;
		}
		rows.add(new SkillingTrackingRow(
			"skilling:" + skill.name(),
			capitalize(skill.getName()),
			state.getLastSeen(),
			skillObservedActions(state),
			sourceRewardValue(unassigned),
			lootRowsForCounts(skill, unassigned),
			Collections.emptyList(),
			() ->
			{
				skillTracker.resetSkill(skill);
				refreshAfterLootReset();
			}));
	}

	private static Map<Integer, Long> unassignedSkillCounts(SkillTracker.SkillState state)
	{
		final Map<Integer, Long> sourceTotals = new HashMap<>();
		for (SkillTracker.SourceState source : state.getSourceStates().values())
		{
			for (Map.Entry<Integer, Long> entry : source.getResourceCounts().entrySet())
			{
				sourceTotals.merge(entry.getKey(), entry.getValue(), Long::sum);
			}
		}
		return unassignedSkillCounts(state.getResourceCounts(), sourceTotals);
	}

	static Map<Integer, Long> unassignedSkillCounts(Map<Integer, Long> skillCounts,
		Map<Integer, Long> sourceTotals)
	{
		final Map<Integer, Long> unassigned = new HashMap<>();
		for (Map.Entry<Integer, Long> entry : skillCounts.entrySet())
		{
			final long qty = entry.getValue() - sourceTotals.getOrDefault(entry.getKey(), 0L);
			if (qty > 0)
			{
				unassigned.put(entry.getKey(), qty);
			}
		}
		return unassigned;
	}

	private List<LootRow> lootRowsForCounts(Skill skill, Map<Integer, Long> counts)
	{
		final List<LootRow> rows = new ArrayList<>();
		counts.entrySet().stream()
			.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
			.forEach(e ->
			{
				ResourceData.ResourceEntry re = resourceData.forItemId(e.getKey(), skill);
				String nm = re != null ? displayResourceName(re) : ("Item " + e.getKey());
				rows.add(new LootRow(e.getKey(), nm, e.getValue()));
			});
		return rows;
	}

	static List<String> skillingOutputLabels(ResourceData resourceData, Skill skill,
		Map<Integer, Long> counts)
	{
		final List<String> labels = new ArrayList<>();
		counts.entrySet().stream()
			.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
			.forEach(e ->
			{
				ResourceData.ResourceEntry re = resourceData.forItemId(e.getKey(), skill);
				String name = re != null ? re.getName() : ("Item " + e.getKey());
				labels.add(name + "|" + e.getValue());
			});
		return Collections.unmodifiableList(labels);
	}

	static List<String> expandedSkillingDetailLabels()
	{
		List<String> labels = new ArrayList<>();
		labels.add("Actions");
		labels.add("Rewards");
		labels.add("Costs");
		labels.add("Net");
		labels.add("Output");
		labels.add("Supplies");
		return Collections.unmodifiableList(labels);
	}

	static List<String> expandedCombatDetailLabels()
	{
		List<String> labels = new ArrayList<>();
		labels.add("KC");
		labels.add("Rewards");
		labels.add("Costs");
		labels.add("Net");
		labels.add("Loot");
		labels.add("Supplies");
		return Collections.unmodifiableList(labels);
	}

	static List<String> selectedSkillingOutputDetailLabels()
	{
		List<String> labels = new ArrayList<>();
		labels.add("Observed");
		labels.add("Chance seen");
		labels.add("Progress");
		labels.add("Deviation");
		labels.add("Luck");
		return Collections.unmodifiableList(labels);
	}

	static List<String> sectionResetMenuLabels()
	{
		List<String> labels = new ArrayList<>();
		labels.add("Reset all combat logs");
		labels.add("Reset all skilling logs");
		return Collections.unmodifiableList(labels);
	}

	static boolean shouldCollapseAutoOpenedSection(boolean autoOpenedSection)
	{
		return autoOpenedSection;
	}

	static String emptyOutputText(List<String> outputLabels)
	{
		return outputLabels.isEmpty() ? "None" : "";
	}

	static String skillingSourceTitle(Skill skill, SkillTracker.SourceState source)
	{
		return skillingSourceTitle(skill, source.getSourceName(), source.getActivityType());
	}

	static String skillingSourceTitle(Skill skill, String sourceName, String activityType)
	{
		if (skill == Skill.THIEVING)
		{
			if ("Pickpocket".equals(activityType) || "Stall".equals(activityType))
			{
				return activityType + ": " + sourceName;
			}
		}
		return sourceName;
	}

	private List<DetailRow> thievingSourceDetailRows(Skill skill, SkillTracker.SourceState source)
	{
		if (skill != Skill.THIEVING || source == null)
		{
			return Collections.emptyList();
		}
		final List<DetailRow> details = new ArrayList<>();
		for (String label : thievingSourceDetailLabels(source,
			resourceData.getSkillData(Skill.THIEVING),
			skillTracker.getCurrentLevel(Skill.THIEVING),
			skillTracker.getRogueOutfitPieces()))
		{
			final int split = label.indexOf('|');
			if (split > 0)
			{
				details.add(new DetailRow(label.substring(0, split), label.substring(split + 1)));
			}
		}
		return Collections.unmodifiableList(details);
	}

	static List<String> thievingSourceDetailLabels(SkillTracker.SourceState source,
		ResourceData.SkillData skillData, int level, int roguePieces)
	{
		if (source == null)
		{
			return Collections.emptyList();
		}
		final List<String> labels = new ArrayList<>();
		if (source.getXpGained() > 0)
		{
			labels.add("XP gained|" + formatInt(source.getXpGained()));
			final long xpHour = sourceXpPerHour(source, System.currentTimeMillis());
			if (xpHour > 0)
			{
				labels.add("XP/hour|" + compactNumber(xpHour));
			}
		}
		if (source.getFailedActions() > 0 && source.getAttemptedActions() > 0)
		{
			labels.add("Fail rate|" + Math.round(source.getFailedActions() * 100.0 / source.getAttemptedActions()) + "%");
		}
		if ("Pickpocket".equals(source.getActivityType()) && skillData != null
			&& skillData.getPetBaseChance() > 0 && level > 0)
		{
			labels.add("Rocky chance|" + PetDisplay.oddsText(skillData.getPetBaseChance(), level));
		}
		if ("Pickpocket".equals(source.getActivityType()) && roguePieces > 0)
		{
			final int chance = SkillTracker.rogueOutfitActivationChancePercent(roguePieces);
			final String label = roguePieces >= 5 ? "Full Rogue outfit" : "Rogue outfit";
			labels.add(label + "|" + roguePieces + "/5 pieces, " + chance + "% double-loot chance");
		}
		return Collections.unmodifiableList(labels);
	}

	static long sourceXpPerHour(SkillTracker.SourceState source, long nowMillis)
	{
		if (source == null || source.getXpGained() <= 0 || source.getFirstActionMillis() <= 0)
		{
			return 0L;
		}
		final long end = Math.max(nowMillis, source.getLastSeen());
		final long elapsed = Math.max(0L, end - source.getFirstActionMillis());
		return xpPerHour(source.getXpGained(), elapsed);
	}

	static long xpPerHour(long xpGained, long elapsedMillis)
	{
		if (xpGained <= 0 || elapsedMillis <= 0L)
		{
			return 0L;
		}
		return Math.round(xpGained * 3_600_000.0 / elapsedMillis);
	}

	private static String compactNumber(long value)
	{
		if (Math.abs(value) >= 1_000_000)
		{
			return gpTrim(value / 1_000_000.0) + "m";
		}
		if (Math.abs(value) >= 1_000)
		{
			return gpTrim(value / 1_000.0) + "k";
		}
		return formatInt(value);
	}

	private long sourceRewardValue(Map<Integer, Long> counts)
	{
		return 0L;
	}

	/**
	 * Skilling-resource GP as {@code [kept, total]}. The values are computed on the client
	 * thread inside {@link SkillTracker} (item pricing requires the client thread) and only
	 * read here — we must never call {@code itemManager.getItemPrice} from the panel's EDT.
	 */
	private long[] skillingProfit()
	{
		return new long[] { skillTracker.getSkillingKeptGp(), skillTracker.getSkillingTotalGp() };
	}

	private void refreshAfterLootReset()
	{
		refreshProfit();
		refreshSkilling();
		revalidate();
		repaint();
	}

	private void renderCombatLootActivity(TargetState target)
	{
		final String mob = target.getName();
		final List<LootRow> rows = new ArrayList<>();
		target.getKeptItems().entrySet().stream()
			.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
			.forEach(e -> rows.add(new LootRow(e.getKey(), itemName(e.getKey()), e.getValue())));

		final String summary = gp(target.getKeptValue()) + " / " + gp(target.getTotalDropValue());
		addLootSource("mob:" + mob, LOOT_COMBAT, mob, summary, rows, () ->
		{
			stateTracker.resetProfit(mob);
			refreshProfit();
		});
	}

	private void renderSkillLootActivity(Skill skill, SkillTracker.SkillState state)
	{
		final List<LootRow> rows = new ArrayList<>();
		final Map<Integer, Long> sourceTotals = new HashMap<>();
		for (SkillTracker.SourceState source : state.getSourceStates().values())
		{
			for (Map.Entry<Integer, Long> entry : source.getResourceCounts().entrySet())
			{
				sourceTotals.merge(entry.getKey(), entry.getValue(), Long::sum);
			}
		}
		state.getResourceCounts().entrySet().stream()
			.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
			.forEach(e ->
			{
				final long unassigned = e.getValue() - sourceTotals.getOrDefault(e.getKey(), 0L);
				if (unassigned <= 0)
				{
					return;
				}
				ResourceData.ResourceEntry re = resourceData.forItemId(e.getKey());
				String nm = re != null ? displayResourceName(re) : ("Item " + e.getKey());
				rows.add(new LootRow(e.getKey(), nm, unassigned));
			});
		final String summary = String.format("%,d", state.getTotalResources()) + " gathered";
		final boolean skillExpanded =
			addLootSource("skill:" + skill.name(), LOOT_SKILL, capitalize(skill.getName()), summary, rows, () ->
			{
				skillTracker.resetSkill(skill);
				refreshAfterLootReset();
			});

		if (!skillExpanded)
		{
			return;
		}

		List<SkillTracker.SourceState> sources = new ArrayList<>(state.getSourceStates().values());
		sources.sort((a, b) -> Long.compare(b.getLastSeen(), a.getLastSeen()));
		for (SkillTracker.SourceState source : sources)
		{
			if (source.getResourceCounts().isEmpty())
			{
				continue;
			}
			final List<LootRow> sourceRows = new ArrayList<>();
			source.getResourceCounts().entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
				.forEach(e ->
				{
					ResourceData.ResourceEntry re = resourceData.forItemId(e.getKey(), skill);
					String nm = re != null ? displayResourceName(re) : ("Item " + e.getKey());
					sourceRows.add(new LootRow(e.getKey(), nm, e.getValue()));
				});
			long total = 0L;
			for (long qty : source.getResourceCounts().values())
			{
				total += qty;
			}
			final String sourceName = source.getSourceName();
			final String sourceSummary = String.format("%,d", total) + " gathered";
			addLootSource("skill:" + skill.name() + ":" + sourceName, LOOT_SKILL,
				sourceName, sourceSummary, sourceRows, () ->
				{
					skillTracker.resetSkillSource(skill, sourceName);
					refreshAfterLootReset();
				}, 12);
		}
	}

	private String lootSearchQuery()
	{
		final String text = lootSearchField.getText();
		if (text == null || text.trim().isEmpty() || "Search loot...".equals(text))
		{
			return "";
		}
		return text.trim().toLowerCase();
	}

	private boolean skillActivityMatchesSearch(String title, SkillTracker.SkillState state, String query)
	{
		if (containsIgnoreCase(title, query))
		{
			return true;
		}
		for (SkillTracker.SourceState source : state.getSourceStates().values())
		{
			if (containsIgnoreCase(source.getSourceName(), query))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean containsIgnoreCase(String value, String query)
	{
		return value != null && query != null && value.toLowerCase().contains(query);
	}

	static boolean sourceRowsCollapsedByDefault(CollapseStateStore store, String sourceKey)
	{
		return store.isSectionCollapsed(LOOT_LOG_KEY, sourceKey);
	}

	static boolean skillingTrackingRowsCollapsedByDefault()
	{
		return true;
	}

	static boolean searchLookupIsCollapsibleSection()
	{
		return false;
	}

	static boolean debugDiagnosticsIsNormalSection()
	{
		return false;
	}

	private JLabel trackingHeader(String text)
	{
		JLabel header = new JLabel(text);
		header.setForeground(ACCENT);
		header.setFont(header.getFont().deriveFont(java.awt.Font.BOLD, 12f));
		header.setBorder(new EmptyBorder(4, 2, 2, 0));
		return header;
	}

	private void addEmptyTrackingRow(JPanel parent, String text)
	{
		JLabel empty = new JLabel(text, SwingConstants.CENTER);
		empty.setForeground(new Color(150, 150, 150));
		empty.setBorder(new EmptyBorder(3, 0, 3, 0));
		parent.add(empty);
	}

	private JPanel buildTextRow(String label, String value)
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

	private boolean addTrackingSource(JPanel parent, String sourceKey, Color categoryColor, String title,
		String summary, List<DetailRow> details, String itemHeader, List<LootRow> items,
		String supplyHeader, String supplyText, Runnable onReset, int indent)
	{
		final boolean collapsed = collapseStateStore.isSectionCollapsed(LOOT_LOG_KEY, sourceKey);

		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, categoryColor),
			new EmptyBorder(3, 6 + indent, 3, 4)));

		JLabel titleLabel = new JLabel((collapsed ? "+ " : "- ") + title);
		titleLabel.setForeground(Color.WHITE);
		header.add(titleLabel, BorderLayout.WEST);

		JLabel summaryLabel = new JLabel(summary);
		summaryLabel.setForeground(new Color(170, 170, 170));
		header.add(summaryLabel, BorderLayout.EAST);

		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShowResetMenu(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShowResetMenu(e);
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger())
				{
					return;
				}
				collapseStateStore.toggleSection(LOOT_LOG_KEY, sourceKey);
				refreshAfterLootReset();
			}

			private void maybeShowResetMenu(MouseEvent e)
			{
				if (onReset == null || !e.isPopupTrigger())
				{
					return;
				}
				JPopupMenu menu = new JPopupMenu();
				JMenuItem reset = new JMenuItem("Reset " + title);
				reset.addActionListener(evt -> onReset.run());
				menu.add(reset);
				menu.show(header, e.getX(), e.getY());
			}
		});
		parent.add(header);

		if (collapsed)
		{
			return false;
		}

		for (DetailRow detail : details)
		{
			parent.add(buildTextRow(detail.label, detail.value));
		}
		addTrackingSubheader(parent, itemHeader);
		if (items.isEmpty())
		{
			addEmptyTrackingRow(parent, "None");
		}
		for (LootRow row : items)
		{
			parent.add(buildLootItemRow(row, 12 + indent));
		}
		addTrackingSubheader(parent, supplyHeader);
		addEmptyTrackingRow(parent, supplyText);
		return true;
	}

	private void addTrackingSubheader(JPanel parent, String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(new Color(190, 190, 190));
		label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD, 11f));
		label.setBorder(new EmptyBorder(5, 12, 1, 0));
		parent.add(label);
	}

	/** One itemised line in Loot / Rewards. */
	private static final class LootRow
	{
		final int itemId;
		final String name;
		final long qty;

		LootRow(int itemId, String name, long qty)
		{
			this.itemId = itemId;
			this.name = name;
			this.qty = qty;
		}
	}

	static final class DetailRow
	{
		final String label;
		final String value;

		DetailRow(String label, String value)
		{
			this.label = label;
			this.value = value;
		}
	}

	private static final class SkillingTrackingRow
	{
		final String key;
		final String title;
		final long lastSeen;
		final long actions;
		final long rewards;
		final List<LootRow> outputRows;
		final List<DetailRow> extraDetails;
		final Runnable onReset;

		SkillingTrackingRow(String key, String title, long lastSeen, long actions, long rewards,
			List<LootRow> outputRows, List<DetailRow> extraDetails, Runnable onReset)
		{
			this.key = key;
			this.title = title;
			this.lastSeen = lastSeen;
			this.actions = actions;
			this.rewards = rewards;
			this.outputRows = outputRows;
			this.extraDetails = extraDetails == null ? Collections.emptyList() : extraDetails;
			this.onReset = onReset;
		}
	}

	static final class SessionTotals
	{
		final long todayRewards;
		final long todayCosts;
		final long allTimeRewards;
		final long allTimeCosts;

		SessionTotals(long todayRewards, long todayCosts, long allTimeRewards, long allTimeCosts)
		{
			this.todayRewards = todayRewards;
			this.todayCosts = todayCosts;
			this.allTimeRewards = allTimeRewards;
			this.allTimeCosts = allTimeCosts;
		}

		long todayNet()
		{
			return todayRewards - todayCosts;
		}

		long allTimeNet()
		{
			return allTimeRewards - allTimeCosts;
		}
	}

	private static final class LootActivity
	{
		final String title;
		final long lastSeen;
		final Runnable render;

		LootActivity(String title, long lastSeen, Runnable render)
		{
			this.title = title;
			this.lastSeen = lastSeen;
			this.render = render;
		}
	}

	/**
	 * Adds one collapsible loot source to the ledger. Collapsed shows just the header
	 * (name + summary); expanded reveals the itemised rows and, for combat sources, a
	 * reset button. A coloured left bar distinguishes combat (red) from skilling (blue).
	 */
	private boolean addLootSource(String sourceKey, Color categoryColor, String title, String summary,
		List<LootRow> items, Runnable onReset)
	{
		return addLootSource(sourceKey, categoryColor, title, summary, items, onReset, 0);
	}

	private boolean addLootSource(String sourceKey, Color categoryColor, String title, String summary,
		List<LootRow> items, Runnable onReset, int indent)
	{
		final boolean collapsed = collapseStateStore.isSectionCollapsed(LOOT_LOG_KEY, sourceKey);

		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, categoryColor),
			new EmptyBorder(3, 6 + indent, 3, 4)));

		JLabel titleLabel = new JLabel(title);
		titleLabel.setForeground(Color.WHITE);
		header.add(titleLabel, BorderLayout.WEST);

		JPanel right = new JPanel(new BorderLayout(6, 0));
		right.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel summaryLabel = new JLabel(summary);
		summaryLabel.setForeground(new Color(170, 170, 170));
		right.add(summaryLabel, BorderLayout.CENTER);
		JLabel arrow = new JLabel(collapsed ? "+" : "-");
		arrow.setForeground(new Color(150, 150, 150));
		right.add(arrow, BorderLayout.EAST);
		header.add(right, BorderLayout.EAST);

		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShowResetMenu(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShowResetMenu(e);
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger())
				{
					return;
				}
				collapseStateStore.toggleSection(LOOT_LOG_KEY, sourceKey);
				refreshProfit();
			}

			private void maybeShowResetMenu(MouseEvent e)
			{
				if (onReset == null || !e.isPopupTrigger())
				{
					return;
				}
				JPopupMenu menu = new JPopupMenu();
				JMenuItem reset = new JMenuItem("Reset " + title);
				reset.addActionListener(evt -> onReset.run());
				menu.add(reset);
				menu.show(header, e.getX(), e.getY());
			}
		});
		profitMobsPanel.add(header);

		if (collapsed)
		{
			return false;
		}

		for (LootRow r : items)
		{
			profitMobsPanel.add(buildLootItemRow(r, 12 + indent));
		}

		return true;
	}

	/** A single ledger row: small item icon, name, and an "xN" quantity badge. */
	private JPanel buildLootItemRow(LootRow r)
	{
		return buildLootItemRow(r, 12);
	}

	private JPanel buildLootItemRow(LootRow r, int leftIndent)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(new EmptyBorder(1, leftIndent, 1, 4));

		JLabel icon = new JLabel();
		icon.setHorizontalAlignment(SwingConstants.LEFT);
		if (r.itemId >= 0)
		{
			AsyncBufferedImage img = itemManager.getImage(r.itemId);
			img.onLoaded(() -> SwingUtilities.invokeLater(() ->
			{
				icon.setIcon(new ImageIcon(img));
				row.repaint();
			}));
			icon.setIcon(new ImageIcon(img));
		}
		row.add(icon, BorderLayout.WEST);

		JLabel nameLabel = new JLabel(r.name);
		nameLabel.setForeground(new Color(210, 210, 210));
		nameLabel.setBorder(new EmptyBorder(0, 4, 0, 0));
		row.add(nameLabel, BorderLayout.CENTER);

		JLabel qtyLabel = new JLabel("x" + String.format("%,d", r.qty));
		qtyLabel.setForeground(LOOT_QTY);
		row.add(qtyLabel, BorderLayout.EAST);

		return row;
	}

	private void refreshSkilling()
	{
		skillingBody.removeAll();
		final boolean showLiveStatus = config.showLiveSkillingStatusInSidePanel();
		liveXpHrValue = null;
		liveResHrValue = null;
		liveTimeValue = null;

		// Persistent log: show the current OR most-recent skill, so the panel keeps
		// displaying what you were doing after the in-game overlay auto-hides.
		Skill skill = skillTracker.getDisplaySkill();
		if (skill == null && selectedSourceSkill != null)
		{
			skill = selectedSourceSkill;
		}
		if (skill == null && selectedResourceEntry != null)
		{
			skill = selectedResourceEntry.getSkill();
		}
		if (skill == null)
		{
			clearSelectedSkillSource();
			clearSelectedSkillResource();
			if (showLiveStatus)
			{
				JLabel idle = new JLabel("No skill tracked yet", SwingConstants.CENTER);
				idle.setForeground(new Color(150, 150, 150));
				idle.setBorder(new EmptyBorder(4, 0, 4, 0));
				skillingBody.add(idle);
			}
			renderSkillingTrackingSources(skillingBody);
			skillingBody.revalidate();
			skillingBody.repaint();
			return;
		}
		if (selectedSourceSkill != null && selectedSourceSkill != skill)
		{
			clearSelectedSkillSource();
		}
		if (selectedResourceEntry != null && selectedResourceEntry.getSkill() != skill)
		{
			clearSelectedSkillResource();
		}

		if (showLiveStatus)
		{
			final boolean active = skillTracker.isActive();

		JLabel skillLabel = new JLabel(capitalize(skill.getName())
			+ (active ? "" : "  (idle)"), SwingConstants.CENTER);
		skillLabel.setForeground(active ? ACCENT : new Color(150, 150, 150));
		skillLabel.setFont(skillLabel.getFont().deriveFont(java.awt.Font.BOLD, 13f));
		skillLabel.setBorder(new EmptyBorder(2, 0, 4, 0));
		skillingBody.add(skillLabel);

		ResourceData.SkillData skillData = resourceData.getSkillData(skill);
		int level = skillTracker.getCurrentLevel(skill);

		addSkillingRow("Level", String.valueOf(level));

		// Live rates from RuneLite's XP tracker. These rows are always present (showing
		// the cached value) so their labels are stable references that updateSkillingLiveStats
		// can refresh in place each tick — rather than rebuilding the whole section on a timer.
		liveXpHrValue = addSkillingRow("XP / hr", cachedXpHr);
		liveResHrValue = addSkillingRow("Resources / hr", cachedResHr);
		liveTimeValue = addSkillingRow("Time to next lvl", cachedTime);
		refreshLiveValues(skill, active);

		SkillTracker.SkillState state = skillTracker.getSkillState(skill);
		long totalResources = state != null ? state.getTotalResources() : 0;

		addSkillingRow("Lifetime resources", String.format("%,d", totalResources));

		// Pet odds only — the accurate, formula-based rate. We don't show a dry streak or
		// chance-seen: the plugin can't read true lifetime actions or pet ownership, so any
		// such figure would just be misleading.
			if (skillData != null && skillData.getPetBaseChance() > 0 && level > 0)
			{
				addSkillingRow("Pet odds", PetDisplay.oddsText(skillData.getPetBaseChance(), level));
			}
		}

		if (selectedSourceSkill == skill)
		{
			addSkillingSourceTrackingTable();
		}
		else if (selectedResourceEntry != null && selectedResourceEntry.getSkill() == skill)
		{
			addSkillingResourceTrackingTable();
		}
		else
		{
			addSkillingSkillTrackingTable(skill);
		}

		renderSkillingTrackingSources(skillingBody);

		skillingBody.revalidate();
		skillingBody.repaint();
	}

	private void addSkillingSourceTrackingTable()
	{
		if (selectedSourceSkill == null || selectedSourceAction == null || selectedSourceName == null
			|| selectedSourceEntries.isEmpty())
		{
			return;
		}

		JLabel header = new JLabel(selectedSourceAction + ": " + selectedSourceName, SwingConstants.CENTER);
		header.setForeground(ACCENT);
		header.setFont(header.getFont().deriveFont(java.awt.Font.BOLD, 12f));
		header.setBorder(new EmptyBorder(8, 0, 3, 0));
		skillingBody.add(header);

		String currentTracked = configManager.getConfiguration(SystemInterfaceConfig.GROUP, "trackedSkillingItem");
		java.util.Set<String> tracked = getTrackedSkillingItems();
		long trackedCount = selectedSourceEntries.stream()
			.filter(entry -> isStatisticalReward(entry) && trackedResourceMatches(tracked, entry))
			.count();

		JLabel status = new JLabel(trackedCount > 0
			? "Tracking " + trackedCount + " reward" + (trackedCount == 1 ? "" : "s")
			: "Click rewards to track", SwingConstants.CENTER);
		status.setForeground(trackedCount > 0 ? RECEIVED_BADGE : new Color(150, 150, 150));
		status.setFont(status.getFont().deriveFont(10f));
		status.setBorder(new EmptyBorder(0, 0, 4, 0));
		skillingBody.add(status);

		if (config.skillingTrackingDisplay() == SystemInterfaceConfig.SkillingTrackingDisplay.SIDE_PANEL)
		{
			addSkillSourceTrackedStats(skillingBody);
		}

		int rows = (selectedSourceEntries.size() + ITEMS_PER_ROW - 1) / ITEMS_PER_ROW;
		JPanel grid = new JPanel(new GridLayout(rows, ITEMS_PER_ROW, 1, 1));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.setMaximumSize(new Dimension(ITEMS_PER_ROW * (ITEM_SIZE.width + 2), rows * (ITEM_SIZE.height + 2)));
		for (ResourceData.ResourceEntry entry : selectedSourceEntries)
		{
			grid.add(createResourceItemSlot(entry, currentTracked, RARITY_COMMON,
				selectedSourceSkill, selectedSourceName, selectedSourceAction + " " + selectedSourceName));
		}
		int remainder = (rows * ITEMS_PER_ROW) - selectedSourceEntries.size();
		for (int i = 0; i < remainder; i++)
		{
			JPanel blank = new JPanel();
			blank.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			blank.setPreferredSize(ITEM_SIZE);
			grid.add(blank);
		}
		skillingBody.add(grid);
	}

	private void addSkillingResourceTrackingTable()
	{
		if (selectedResourceName == null || selectedResourceEntry == null)
		{
			return;
		}

		final List<ResourceData.ResourceEntry> entries = availableResourceRewards();

		JLabel header = new JLabel(selectedResourceName, SwingConstants.CENTER);
		header.setForeground(ACCENT);
		header.setFont(header.getFont().deriveFont(java.awt.Font.BOLD, 12f));
		header.setBorder(new EmptyBorder(8, 0, 3, 0));
		skillingBody.add(header);

		String currentTracked = configManager.getConfiguration(SystemInterfaceConfig.GROUP, "trackedSkillingItem");
		java.util.Set<String> tracked = getTrackedSkillingItems();
		long trackedCount = entries.stream()
			.filter(entry -> isStatisticalReward(entry) && trackedResourceMatches(tracked, entry))
			.count();

		JLabel status = new JLabel(trackedCount > 0
			? "Tracking " + trackedCount + " reward" + (trackedCount == 1 ? "" : "s")
			: entries.isEmpty() ? "No fixed-rate rewards" : "Click rewards to track", SwingConstants.CENTER);
		status.setForeground(trackedCount > 0 ? RECEIVED_BADGE : new Color(150, 150, 150));
		status.setFont(status.getFont().deriveFont(10f));
		status.setBorder(new EmptyBorder(0, 0, 4, 0));
		skillingBody.add(status);

		if (entries.isEmpty())
		{
			return;
		}

		if (config.skillingTrackingDisplay() == SystemInterfaceConfig.SkillingTrackingDisplay.SIDE_PANEL)
		{
			addSkillResourceTrackedStats(skillingBody, entries);
		}

		int rows = (entries.size() + ITEMS_PER_ROW - 1) / ITEMS_PER_ROW;
		JPanel grid = new JPanel(new GridLayout(rows, ITEMS_PER_ROW, 1, 1));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.setMaximumSize(new Dimension(ITEMS_PER_ROW * (ITEM_SIZE.width + 2), rows * (ITEM_SIZE.height + 2)));
		for (ResourceData.ResourceEntry entry : entries)
		{
			grid.add(createResourceItemSlot(entry, currentTracked, RARITY_COMMON,
				selectedResourceEntry.getSkill(), selectedResourceName, selectedResourceName));
		}
		int remainder = (rows * ITEMS_PER_ROW) - entries.size();
		for (int i = 0; i < remainder; i++)
		{
			JPanel blank = new JPanel();
			blank.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			blank.setPreferredSize(ITEM_SIZE);
			grid.add(blank);
		}
		skillingBody.add(grid);
	}

	private void addSkillingSkillTrackingTable(Skill skill)
	{
		final List<ResourceData.ResourceEntry> entries = statisticalEntriesForSkill(skill);
		if (entries.isEmpty())
		{
			return;
		}

		JLabel header = new JLabel("Tracked rewards", SwingConstants.CENTER);
		header.setForeground(ACCENT);
		header.setFont(header.getFont().deriveFont(java.awt.Font.BOLD, 12f));
		header.setBorder(new EmptyBorder(8, 0, 3, 0));
		skillingBody.add(header);

		String currentTracked = configManager.getConfiguration(SystemInterfaceConfig.GROUP, "trackedSkillingItem");
		java.util.Set<String> tracked = getTrackedSkillingItems();
		long trackedCount = entries.stream()
			.filter(entry -> trackedResourceMatches(tracked, entry))
			.count();

		JLabel status = new JLabel(trackedCount > 0
			? "Tracking " + trackedCount + " reward" + (trackedCount == 1 ? "" : "s")
			: "Click rewards to track", SwingConstants.CENTER);
		status.setForeground(trackedCount > 0 ? RECEIVED_BADGE : new Color(150, 150, 150));
		status.setFont(status.getFont().deriveFont(10f));
		status.setBorder(new EmptyBorder(0, 0, 4, 0));
		skillingBody.add(status);

		if (config.skillingTrackingDisplay() == SystemInterfaceConfig.SkillingTrackingDisplay.SIDE_PANEL)
		{
			addSkillTrackedStats(skillingBody, skill, entries);
		}

		int rows = (entries.size() + ITEMS_PER_ROW - 1) / ITEMS_PER_ROW;
		JPanel grid = new JPanel(new GridLayout(rows, ITEMS_PER_ROW, 1, 1));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.setMaximumSize(new Dimension(ITEMS_PER_ROW * (ITEM_SIZE.width + 2), rows * (ITEM_SIZE.height + 2)));
		for (ResourceData.ResourceEntry entry : entries)
		{
			grid.add(createResourceItemSlot(entry, currentTracked, RARITY_COMMON, skill, null, capitalize(skill.getName())));
		}
		int remainder = (rows * ITEMS_PER_ROW) - entries.size();
		for (int i = 0; i < remainder; i++)
		{
			JPanel blank = new JPanel();
			blank.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			blank.setPreferredSize(ITEM_SIZE);
			grid.add(blank);
		}
		skillingBody.add(grid);
	}

	private List<ResourceData.ResourceEntry> statisticalEntriesForSkill(Skill skill)
	{
		return resourceData.skillWideStatisticalRewards(skill);
	}

	private List<ResourceData.ResourceEntry> availableResourceRewards()
	{
		List<ResourceData.ResourceEntry> entries = new ArrayList<>();
		for (ResourceData.ResourceEntry entry : selectedResourceEntries)
		{
			if (skillTracker.hasAnyItem(entry.getRequiredItemsAny()))
			{
				entries.add(entry);
			}
		}
		return entries;
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

	/**
	 * Called once per game tick by the plugin to keep the skilling rates live while a
	 * session is active. Updates only the specific value labels (no section rebuild) on the
	 * EDT, and does nothing once the session is idle and already frozen — no idle work.
	 */
	public void updateSkillingLiveStats()
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

	/** Adds a label/value row to the skilling section and returns the value label. */
	private JLabel addSkillingRow(String label, String value)
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

		skillingBody.add(row);
		return right;
	}

	private static String capitalize(String s)
	{
		if (s == null || s.isEmpty())
		{
			return s;
		}
		return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
	}

	private JPanel profitButton(String text, java.awt.event.ActionListener onClick)
	{
		JButton btn = new JButton(text);
		btn.setMargin(new java.awt.Insets(2, 2, 2, 2));
		btn.addActionListener(onClick);
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.setBorder(new EmptyBorder(2, 0, 0, 0));
		wrap.add(btn, BorderLayout.CENTER);
		return wrap;
	}

	private static String gp(long v)
	{
		if (v >= 1_000_000_000L) return gpTrim(v / 1_000_000_000.0) + "B";
		if (v >= 1_000_000L) return gpTrim(v / 1_000_000.0) + "M";
		if (v >= 1_000L) return gpTrim(v / 1_000.0) + "K";
		return Long.toString(v);
	}

	private static String gpTrim(double d)
	{
		return d == Math.floor(d) ? Long.toString((long) d) : String.format("%.1f", d);
	}

	private void onSearchChanged()
	{
		String query = searchField.getText().trim().toLowerCase();
		if (query.equals("search...") || query.isEmpty()) return;

		for (String t : lootTables.registeredTargets())
		{
			if (t.toLowerCase().contains(query))
			{
				selectedTarget = t;
				clearSelectedSkillSource();
				browsing = true; // explicit search → show the table even if not engaged
				setCombatExpanded(true);
				showDropsForTarget(t);
				updatePortrait(t);
				return;
			}
		}
	}

	private void clearDropGrids()
	{
		List<java.awt.Component> toRemove = new ArrayList<>();
		boolean pastTracking = false;
		for (java.awt.Component c : combatBody.getComponents())
		{
			if (pastTracking) toRemove.add(c);
			if (c == combatTrackingPanel) pastTracking = true;
		}
		for (java.awt.Component c : toRemove) combatBody.remove(c);
		combatBody.revalidate();
		combatBody.repaint();
	}

	private void showDropsForTarget(String target)
	{
		clearDropGrids();

		if (target == null) return;

		targetNameLabel.setText("<html><div style='text-align:center;font-size:14pt;'><b>" + target + "</b></div></html>");

		// In-combat reveals stay a mystery until engaged; explicit browsing
		// (dropdown / search) always shows the table so you can build a watchlist.
		final TargetState state = stateTracker.get(target);
		if (!browsing && (state == null || !state.isEngaged()))
		{
			trackedLabel.setText("Engage this target to reveal its loot.");
			return;
		}

		DropTable table = lootTables.forTarget(target);
		if (table == null)
		{
			trackedLabel.setText("Fetching loot table...");
			return;
		}

		String currentTracked = configManager.getConfiguration(SystemInterfaceConfig.GROUP, "trackedItem");
		updateTrackedLabel();

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
			if (hideMembersDrops && isMembersItem(entry.getName()))
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
			f2pNote.setFont(f2pNote.getFont().deriveFont(java.awt.Font.ITALIC, 10f));
			f2pNote.setBorder(new EmptyBorder(4, 0, 2, 0));
			combatBody.add(f2pNote);
		}

		addRarityGrid("Very Rare", RARITY_VERY_RARE, veryRare, state, currentTracked);
		addRarityGrid("Rare", RARITY_RARE, rare, state, currentTracked);
		addRarityGrid("Uncommon", RARITY_UNCOMMON, uncommon, state, currentTracked);
		addRarityGrid("Common", RARITY_COMMON, common, state, currentTracked);

		// Multi-drop completion line: when 2+ items are tracked on this mob,
		// show expected kills to obtain all of them.
		java.util.Set<String> trackedSet = getTrackedItems();
		List<Double> trackedRates = new ArrayList<>();
		for (String name : trackedSet)
		{
			DropTable.Entry e = bestByName.get(name);
			if (e == null)
			{
				for (DropTable.Entry candidate : bestByName.values())
				{
					if (candidate.getName().equalsIgnoreCase(name))
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
			completionHeader.setFont(completionHeader.getFont().deriveFont(java.awt.Font.BOLD, 13f));
			completionHeader.setBorder(new EmptyBorder(8, 0, 0, 0));
			combatBody.add(completionHeader);

			JLabel completionValue = new JLabel(
				"~" + String.format("%,d", Math.round(expected)) + " kills for " + countWord,
				SwingConstants.CENTER);
			completionValue.setForeground(new Color(220, 220, 220));
			completionValue.setFont(completionValue.getFont().deriveFont(java.awt.Font.BOLD, 12f));
			completionValue.setBorder(new EmptyBorder(2, 0, 6, 0));
			combatBody.add(completionValue);
		}

		combatBody.revalidate();
		combatBody.repaint();
	}

	private void showSkillSourceTarget()
	{
		clearDropGrids();
		if (selectedSourceSkill == null || selectedSourceAction == null || selectedSourceName == null)
		{
			return;
		}

		targetNameLabel.setText("<html><div style='text-align:center;font-size:14pt;'><b>"
			+ selectedSourceName + "</b></div></html>");
		updateTrackedLabel();

		JLabel actionLabel = new JLabel(selectedSourceAction + " rewards", SwingConstants.CENTER);
		actionLabel.setForeground(ACCENT);
		actionLabel.setFont(actionLabel.getFont().deriveFont(java.awt.Font.BOLD, 12f));
		actionLabel.setBorder(new EmptyBorder(2, 0, 6, 0));
		combatBody.add(actionLabel);

		List<ResourceData.ResourceEntry> knownRate = new ArrayList<>();
		List<ResourceData.ResourceEntry> unknownRate = new ArrayList<>();
		for (ResourceData.ResourceEntry entry : selectedSourceEntries)
		{
			if (entry.getRate() != null && entry.getRate() > 0.0)
			{
				knownRate.add(entry);
			}
			else
			{
				unknownRate.add(entry);
			}
		}
		knownRate.sort(Comparator.comparingDouble(ResourceData.ResourceEntry::getRate));
		unknownRate.sort(Comparator.comparing(ResourceData.ResourceEntry::getName, String.CASE_INSENSITIVE_ORDER));

		List<ResourceData.ResourceEntry> veryRare = new ArrayList<>();
		List<ResourceData.ResourceEntry> rare = new ArrayList<>();
		List<ResourceData.ResourceEntry> uncommon = new ArrayList<>();
		List<ResourceData.ResourceEntry> common = new ArrayList<>();
		for (ResourceData.ResourceEntry e : knownRate)
		{
			long denom = Math.max(1L, Math.round(1.0 / e.getRate()));
			if (denom >= 256) veryRare.add(e);
			else if (denom >= 64) rare.add(e);
			else if (denom >= 10) uncommon.add(e);
			else common.add(e);
		}

		String currentTracked = configManager.getConfiguration(SystemInterfaceConfig.GROUP, "trackedItem");
		addResourceRarityGrid("Very Rare", RARITY_VERY_RARE, veryRare, currentTracked);
		addResourceRarityGrid("Rare", RARITY_RARE, rare, currentTracked);
		addResourceRarityGrid("Uncommon", RARITY_UNCOMMON, uncommon, currentTracked);
		addResourceRarityGrid("Common", RARITY_COMMON, common, currentTracked);
		addResourceRarityGrid("Rewards", RARITY_COMMON, unknownRate, currentTracked);

		combatBody.revalidate();
		combatBody.repaint();
	}

	private void addSkillSourceTrackedStats(JPanel parent)
	{
		final SkillTracker.SourceState source = selectedSkillSourceState();
		if (source == null)
		{
			return;
		}
		final long actions = sourceObservedActions(source);
		if (actions <= 0)
		{
			return;
		}
		final java.util.Set<String> tracked = getTrackedSkillingItems();
		for (ResourceData.ResourceEntry entry : selectedSourceEntries)
		{
			if (!isStatisticalReward(entry) || !trackedResourceMatches(tracked, entry))
			{
				continue;
			}
			addSkillSourceTrackedStat(parent, entry, source, actions);
		}
	}

	private void addSkillTrackedStats(JPanel parent, Skill skill, List<ResourceData.ResourceEntry> entries)
	{
		final SkillTracker.SkillState state = skillTracker.getSkillState(skill);
		if (state == null)
		{
			return;
		}
		final long actions = skillObservedActions(state);
		if (actions <= 0)
		{
			return;
		}
		final java.util.Set<String> tracked = getTrackedSkillingItems();
		for (ResourceData.ResourceEntry entry : entries)
		{
			if (!trackedResourceMatches(tracked, entry))
			{
				continue;
			}
			addSkillTrackedStat(parent, entry, state, actions);
		}
	}

	private void addSkillResourceTrackedStats(JPanel parent, List<ResourceData.ResourceEntry> entries)
	{
		final ResourceData.ResourceEntry resource = selectedResourceEntry;
		if (resource == null)
		{
			return;
		}
		final SkillTracker.SkillState state = skillTracker.getSkillState(resource.getSkill());
		if (state == null)
		{
			return;
		}
		final long skillActions = skillObservedActions(state);
		final java.util.Set<String> tracked = getTrackedSkillingItems();
		for (ResourceData.ResourceEntry entry : entries)
		{
			if (!trackedResourceMatches(tracked, entry))
			{
				continue;
			}
			final long actions = resourceRewardActions(resource, entry, state, skillActions);
			if (actions > 0)
			{
				addSkillTrackedStat(parent, entry, state, actions);
			}
		}
	}

	private void addSkillSourceTrackedStat(JPanel parent, ResourceData.ResourceEntry entry,
		SkillTracker.SourceState source, long actions)
	{
		final long received = sourceGrossCount(source, entry);
		final double rate = entry.getRate();
		final long dryActions = sourceDryActions(actions, received, rate);
		final long denom = Math.max(1L, Math.round(1.0 / rate));
		final double chanceSeen = Probability.atLeastOne(rate, dryActions);
		final int expectedActions = (int) Math.round(Probability.expectedKills(rate));
		int multiplier = Math.max(1, (int) Math.ceil((double) dryActions / Math.max(1, expectedActions)));
		if (expectedActions > 0 && dryActions > 0 && dryActions % expectedActions == 0)
		{
			multiplier++;
		}
		final double expectedDrops = Probability.expectedDrops(rate, actions);
		final double sd = Probability.stdDev(rate, actions);
		final double z = sd <= 0.0 ? 0.0 : ((double) received - expectedDrops) / sd;
		final LuckStatus luck = LuckStatus.fromZScore(z);
		final double deviation = received - expectedDrops;

		JPanel block = new JPanel(new DynamicGridLayout(0, 1, 0, 1));
		block.setBackground(ColorScheme.DARK_GRAY_COLOR);
		block.setBorder(new EmptyBorder(4, 0, 6, 0));

		addMiniRow(block, displayResourceName(entry), "1/" + formatInt(denom), ACCENT);
		addMiniRow(block, "Observed", String.format("%,d", received), received > 0 ? RECEIVED_BADGE : new Color(160, 160, 160));
		addMiniRow(block, "Chance seen", formatPercent(chanceSeen), new Color(210, 210, 210));
		addMiniRow(block, "Progress", multiplier + "x: " + String.format("%,d", dryActions)
			+ " / " + String.format("%,d", (long) expectedActions * multiplier), new Color(210, 210, 210));
		addMiniRow(block, "Deviation", signedOneDecimal(deviation), deviationColor(deviation));
		addMiniRow(block, "Luck", luck.getLabel(), luck.getColor());
		parent.add(block);
	}

	private void addSkillTrackedStat(JPanel parent, ResourceData.ResourceEntry entry,
		SkillTracker.SkillState state, long actions)
	{
		final long received = skillGrossCount(state, entry);
		final double rate = entry.getRate();
		final long dryActions = sourceDryActions(actions, received, rate);
		final long denom = Math.max(1L, Math.round(1.0 / rate));
		final double chanceSeen = Probability.atLeastOne(rate, dryActions);
		final int expectedActions = (int) Math.round(Probability.expectedKills(rate));
		int multiplier = Math.max(1, (int) Math.ceil((double) dryActions / Math.max(1, expectedActions)));
		if (expectedActions > 0 && dryActions > 0 && dryActions % expectedActions == 0)
		{
			multiplier++;
		}
		final double expectedDrops = Probability.expectedDrops(rate, actions);
		final double sd = Probability.stdDev(rate, actions);
		final double z = sd <= 0.0 ? 0.0 : ((double) received - expectedDrops) / sd;
		final LuckStatus luck = LuckStatus.fromZScore(z);
		final double deviation = received - expectedDrops;

		JPanel block = new JPanel(new DynamicGridLayout(0, 1, 0, 1));
		block.setBackground(ColorScheme.DARK_GRAY_COLOR);
		block.setBorder(new EmptyBorder(4, 0, 6, 0));

		addMiniRow(block, displayResourceName(entry), "1/" + formatInt(denom), ACCENT);
		addMiniRow(block, "Observed", String.format("%,d", received), received > 0 ? RECEIVED_BADGE : new Color(160, 160, 160));
		addMiniRow(block, "Chance seen", formatPercent(chanceSeen), new Color(210, 210, 210));
		addMiniRow(block, "Progress", multiplier + "x: " + String.format("%,d", dryActions)
			+ " / " + String.format("%,d", (long) expectedActions * multiplier), new Color(210, 210, 210));
		addMiniRow(block, "Deviation", signedOneDecimal(deviation), deviationColor(deviation));
		addMiniRow(block, "Luck", luck.getLabel(), luck.getColor());
		parent.add(block);
	}

	private void addMiniRow(JPanel parent, String leftText, String rightText, Color rightColor)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(2, 6, 2, 6));
		JLabel left = new JLabel(leftText);
		left.setForeground(new Color(200, 200, 200));
		row.add(left, BorderLayout.WEST);
		JLabel right = new JLabel(rightText);
		right.setForeground(rightColor);
		row.add(right, BorderLayout.EAST);
		parent.add(row);
	}

	private SkillTracker.SourceState selectedSkillSourceState()
	{
		if (selectedSourceSkill == null || selectedSourceName == null)
		{
			return null;
		}
		final SkillTracker.SkillState state = skillTracker.getSkillState(selectedSourceSkill);
		return state == null ? null : state.getSourceStates().get(selectedSourceName);
	}

	private static long sourceObservedActions(SkillTracker.SourceState source)
	{
		if (source.getSuccessfulActions() > 0L)
		{
			return source.getSuccessfulActions();
		}
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
		for (long qty : state.getGrossResourceCounts().values())
		{
			actions = Math.max(actions, qty);
		}
		return actions;
	}

	private static long sourceGrossCount(SkillTracker.SourceState source, ResourceData.ResourceEntry entry)
	{
		return entry.countIn(source.getGrossResourceCounts());
	}

	private static long skillGrossCount(SkillTracker.SkillState state, ResourceData.ResourceEntry entry)
	{
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

	private static boolean isStatisticalReward(ResourceData.ResourceEntry entry)
	{
		return entry.isStatisticalReward();
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

	private static Color deviationColor(double deviation)
	{
		if (deviation > 0.0)
		{
			return LuckStatus.LUCKY.getColor();
		}
		if (deviation < 0.0)
		{
			return LuckStatus.UNLUCKY.getColor();
		}
		return LuckStatus.AVERAGE.getColor();
	}

	private static String signedOneDecimal(double value)
	{
		return (value >= 0.0 ? "+" : "") + String.format("%.1f", value);
	}

	private String displayResourceName(ResourceData.ResourceEntry entry)
	{
		return displayItemName(entry.getName());
	}

	private String displayItemName(String name)
	{
		return config.compactOverlay() ? shortenName(name) : name;
	}

	private static String shortenName(String name)
	{
		if (name == null)
		{
			return null;
		}
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

	private static String formatPercent(double value)
	{
		return String.format("%.1f%%", value * 100.0);
	}

	private static String formatInt(long value)
	{
		return String.format("%,d", value);
	}

	private void addRarityGrid(String label, Color color, List<DropTable.Entry> entries,
		TargetState state, String currentTracked)
	{
		if (entries.isEmpty()) return;

		final String mob = selectedTarget;
		final boolean collapsed = isSectionCollapsed(mob, label);

		// Clickable header — click to collapse/expand this rarity table. State
		// is remembered per mob.
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(HEADER_BG);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setBorder(new EmptyBorder(3, 2, 3, 4));
		JLabel title = new JLabel(label + " (" + entries.size() + ")");
		title.setForeground(color);
		header.add(title, BorderLayout.WEST);
		JLabel arrow = new JLabel(collapsed ? "+" : "-");
		arrow.setForeground(color);
		header.add(arrow, BorderLayout.EAST);
		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggleSection(mob, label);
				if (selectedTarget != null) showDropsForTarget(selectedTarget);
			}
		});
		combatBody.add(header);

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
			grid.add(createItemSlot(entry, state, currentTracked, color));
		}
		int remainder = (rows * ITEMS_PER_ROW) - entries.size();
		for (int i = 0; i < remainder; i++)
		{
			JPanel blank = new JPanel();
			blank.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			blank.setPreferredSize(ITEM_SIZE);
			grid.add(blank);
		}

		combatBody.add(grid);
	}

	private void addResourceRarityGrid(String label, Color color, List<ResourceData.ResourceEntry> entries,
		String currentTracked)
	{
		if (entries.isEmpty()) return;

		final String key = selectedSourceSkill + ":" + selectedSourceAction + ":" + selectedSourceName;
		final boolean collapsed = isSectionCollapsed(key, label);

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(HEADER_BG);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setBorder(new EmptyBorder(3, 2, 3, 4));
		JLabel title = new JLabel(label + " (" + entries.size() + ")");
		title.setForeground(color);
		header.add(title, BorderLayout.WEST);
		JLabel arrow = new JLabel(collapsed ? "+" : "-");
		arrow.setForeground(color);
		header.add(arrow, BorderLayout.EAST);
		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggleSection(key, label);
				showSkillSourceTarget();
			}
		});
		combatBody.add(header);

		if (collapsed)
		{
			return;
		}

		int rows = (entries.size() + ITEMS_PER_ROW - 1) / ITEMS_PER_ROW;
		JPanel grid = new JPanel(new GridLayout(rows, ITEMS_PER_ROW, 1, 1));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.setMaximumSize(new Dimension(ITEMS_PER_ROW * (ITEM_SIZE.width + 2), rows * (ITEM_SIZE.height + 2)));

		for (ResourceData.ResourceEntry entry : entries)
		{
			grid.add(createResourceItemSlot(entry, currentTracked, color,
				selectedSourceSkill, selectedSourceName, selectedSourceAction + " " + selectedSourceName));
		}
		int remainder = (rows * ITEMS_PER_ROW) - entries.size();
		for (int i = 0; i < remainder; i++)
		{
			JPanel blank = new JPanel();
			blank.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			blank.setPreferredSize(ITEM_SIZE);
			grid.add(blank);
		}

		combatBody.add(grid);
	}

	private JPanel createItemSlot(DropTable.Entry entry, TargetState state,
		String currentTracked, Color tierColor)
	{
		final String itemName = entry.getName();
		final boolean isTracked = csvContains(currentTracked, itemName);

		JPanel slot = new JPanel(new BorderLayout());
		slot.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		slot.setPreferredSize(ITEM_SIZE);
		slot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		if (isTracked)
		{
			slot.setBorder(BorderFactory.createLineBorder(SELECTED_BORDER, 2));
		}
		else
		{
			slot.setBorder(BorderFactory.createLineBorder(tierColor, 1));
		}

		JLabel iconLabel = new JLabel();
		iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
		iconLabel.setVerticalAlignment(SwingConstants.CENTER);

		int itemId = resolveItemId(itemName);
		if (itemId >= 0)
		{
			AsyncBufferedImage img = itemManager.getImage(itemId);
			img.onLoaded(() -> SwingUtilities.invokeLater(() ->
			{
				iconLabel.setIcon(new ImageIcon(img));
				slot.repaint();
			}));
			iconLabel.setIcon(new ImageIcon(img));
		}
		slot.add(iconLabel, BorderLayout.CENTER);

		// Received badge at top-right
		int drops = state != null ? state.countOf(itemName) : 0;
		if (drops > 0)
		{
			JLabel badge = new JLabel("x" + drops, SwingConstants.RIGHT);
			badge.setForeground(RECEIVED_BADGE);
			badge.setFont(badge.getFont().deriveFont(java.awt.Font.BOLD, 9f));
			badge.setBorder(new EmptyBorder(0, 0, 0, 1));
			slot.add(badge, BorderLayout.NORTH);
		}

		// Rate label at bottom
		long denom = Math.max(1L, Math.round(1.0 / entry.getRate()));
		String rateStr = denom <= 1 ? "Always" : "1/" + denom;
		JLabel rateLabel = new JLabel(rateStr, SwingConstants.CENTER);
		rateLabel.setForeground(Color.WHITE);
		rateLabel.setFont(rateLabel.getFont().deriveFont(java.awt.Font.BOLD, 11f));
		rateLabel.setOpaque(true);
		rateLabel.setBackground(new Color(0, 0, 0, 200));
		slot.add(rateLabel, BorderLayout.SOUTH);

		String tip = "<html><b>" + itemName + "</b><br>" + rateStr;
		if (drops > 0) tip += "<br>Received: " + drops + "x";
		tip += isTracked ? "<br>Tracked — click to untrack" : "<br>Click to track";
		tip += "</html>";
		slot.setToolTipText(tip);

		slot.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggleTracked(itemName); // click to track, click again to untrack
				updateTrackedLabel();
				if (selectedTarget != null) showDropsForTarget(selectedTarget);
			}
		});

		return slot;
	}

	private JPanel createResourceItemSlot(ResourceData.ResourceEntry entry, String currentTracked, Color tierColor,
		Skill contextSkill, String contextSourceName, String contextLabel)
	{
		final String itemName = entry.getName();
		final String displayName = displayResourceName(entry);
		final boolean isTracked = csvContains(currentTracked, itemName);

		JPanel slot = new JPanel(new BorderLayout());
		slot.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		slot.setPreferredSize(ITEM_SIZE);
		slot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		slot.setBorder(BorderFactory.createLineBorder(isTracked ? SELECTED_BORDER : tierColor, isTracked ? 2 : 1));

		JLabel iconLabel = new JLabel();
		iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
		iconLabel.setVerticalAlignment(SwingConstants.CENTER);

		int itemId = entry.getItemId();
		if (itemId >= 0)
		{
			AsyncBufferedImage img = itemManager.getImage(itemId);
			img.onLoaded(() -> SwingUtilities.invokeLater(() ->
			{
				iconLabel.setIcon(new ImageIcon(img));
				slot.repaint();
			}));
			iconLabel.setIcon(new ImageIcon(img));
		}
		slot.add(iconLabel, BorderLayout.CENTER);

		long received = resourceCount(entry, contextSkill, contextSourceName);
		if (received > 0)
		{
			JLabel badge = new JLabel("x" + received, SwingConstants.RIGHT);
			badge.setForeground(RECEIVED_BADGE);
			badge.setFont(badge.getFont().deriveFont(java.awt.Font.BOLD, 9f));
			badge.setBorder(new EmptyBorder(0, 0, 0, 1));
			slot.add(badge, BorderLayout.NORTH);
		}

		String rateStr = resourceRateLabel(entry);
		JLabel rateLabel = new JLabel(rateStr, SwingConstants.CENTER);
		rateLabel.setForeground(Color.WHITE);
		rateLabel.setFont(rateLabel.getFont().deriveFont(java.awt.Font.BOLD, 11f));
		rateLabel.setOpaque(true);
		rateLabel.setBackground(new Color(0, 0, 0, 200));
		slot.add(rateLabel, BorderLayout.SOUTH);

		String tip = "<html><b>" + displayName + "</b>";
		if (!displayName.equals(itemName))
		{
			tip += "<br>" + itemName;
		}
		if (contextLabel != null && !contextLabel.isEmpty())
		{
			tip += "<br>" + contextLabel;
		}
		if (entry.getRate() != null && entry.getRate() > 0.0) tip += "<br>" + rateStr;
		if (received > 0) tip += "<br>Received: " + received + "x";
		tip += isTracked ? "<br>Tracked - click to untrack" : "<br>Click to track";
		tip += "</html>";
		slot.setToolTipText(tip);

		slot.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggleTrackedSkilling(itemName);
				refreshSkilling();
			}
		});

		return slot;
	}

	private long resourceCount(ResourceData.ResourceEntry entry, Skill contextSkill, String contextSourceName)
	{
		if (contextSkill == null)
		{
			return 0L;
		}
		final SkillTracker.SkillState state = skillTracker.getSkillState(contextSkill);
		if (state == null)
		{
			return 0L;
		}
		if (contextSourceName == null)
		{
			long total = 0L;
			for (int itemId : entry.getItemIds())
			{
				total += state.getResourceCounts().getOrDefault(itemId, 0L);
			}
			return total;
		}
		final SkillTracker.SourceState source = state.getSourceStates().get(contextSourceName);
		if (source == null)
		{
			return 0L;
		}
		long total = 0L;
		for (int itemId : entry.getItemIds())
		{
			total += source.getResourceCounts().getOrDefault(itemId, 0L);
		}
		return total;
	}

	private static String resourceRateLabel(ResourceData.ResourceEntry entry)
	{
		if (entry.getRate() == null || entry.getRate() <= 0.0)
		{
			return "Known";
		}
		long denom = Math.max(1L, Math.round(1.0 / entry.getRate()));
		return denom <= 1 ? "Always" : "1/" + denom;
	}

	// --- Tracked-item set (stored as a pipe-separated list in the trackedItem config) ---

	private java.util.Set<String> getTrackedItems()
	{
		java.util.Set<String> set = new java.util.LinkedHashSet<>();
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
		java.util.Set<String> set = getTrackedItems();
		if (!set.removeIf(s -> s.equalsIgnoreCase(item)))
		{
			set.add(item);
		}
		configManager.setConfiguration(SystemInterfaceConfig.GROUP, "trackedItem", String.join("|", set));
	}

	private java.util.Set<String> getTrackedSkillingItems()
	{
		java.util.Set<String> set = new java.util.LinkedHashSet<>();
		String csv = configManager.getConfiguration(SystemInterfaceConfig.GROUP, "trackedSkillingItem");
		if (csv != null)
		{
			for (String s : csv.split("\\|"))
			{
				if (!s.trim().isEmpty()) set.add(s.trim());
			}
		}
		return set;
	}

	private void toggleTrackedSkilling(String item)
	{
		java.util.Set<String> set = getTrackedSkillingItems();
		if (!set.removeIf(s -> s.equalsIgnoreCase(item)))
		{
			set.add(item);
		}
		configManager.setConfiguration(SystemInterfaceConfig.GROUP, "trackedSkillingItem", String.join("|", set));
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

	private boolean isSectionCollapsed(String mob, String section)
	{
		return collapseStateStore.isSectionCollapsed(mob, section);
	}

	private void toggleSection(String mob, String section)
	{
		collapseStateStore.toggleSection(mob, section);
	}

	/**
	 * Records whether the player is on a free (non-members) world. Called from the
	 * client thread by the plugin on login/world-hop, then re-renders so the
	 * members-only filter reflects the current world.
	 */
	public void setFreeWorld(boolean freeWorld)
	{
		if (this.freeWorld == freeWorld)
		{
			return;
		}
		this.freeWorld = freeWorld;
		SwingUtilities.invokeLater(() ->
		{
			if (selectedTarget != null)
			{
				showDropsForTarget(selectedTarget);
			}
			refreshSkilling();
		});
	}

	/** True when the named drop is a members-only item (covers untradeables too). */
	private boolean isMembersItem(String itemName)
	{
		return itemMembership.isMembers(itemName);
	}

	/** Display name for an item id, falling back to the raw id if the definition is unavailable. */
	private String itemName(int itemId)
	{
		String cached = stateTracker.itemName(itemId);
		if (cached != null)
		{
			return cached;
		}
		// getItemComposition -> client.getItemDefinition asserts the client thread; under -ea
		// (dev client) that throws an AssertionError off the EDT. Catch Throwable so a combat
		// loot row can never freeze the whole log — it degrades to the id instead.
		try
		{
			String name = itemManager.getItemComposition(itemId).getName();
			if (name != null && !name.isEmpty() && !"null".equalsIgnoreCase(name))
			{
				return name;
			}
		}
		catch (Throwable ignored)
		{
			// Definition unavailable on this thread — fall through to the id.
		}
		return "Item " + itemId;
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

	private void updateTrackedLabel()
	{
		// Tracking is stored globally, but the label should reflect only what the
		// currently-viewed target actually drops — otherwise items tracked on a
		// previous mob carry over (e.g. Vorkath's visages while viewing a Goblin).
		java.util.Set<String> tracked = selectedSourceSkill != null
			? getTrackedSkillingItems()
			: getTrackedItems();
		java.util.Set<String> relevant = selectedSourceSkill != null
			? trackedForSkillSource(tracked)
			: trackedForTarget(selectedTarget, tracked);
		trackedLabel.setText(relevant.isEmpty()
			? "Tracked: None"
			: "Tracked: " + displayTrackedItems(relevant));
	}

	private String displayTrackedItems(java.util.Set<String> tracked)
	{
		java.util.List<String> names = new ArrayList<>();
		for (String item : tracked)
		{
			names.add(displayItemName(item));
		}
		return String.join(", ", names);
	}

	private java.util.Set<String> trackedForSkillSource(java.util.Set<String> tracked)
	{
		if (tracked.isEmpty() || selectedSourceEntries.isEmpty())
		{
			return java.util.Collections.emptySet();
		}
		java.util.Set<String> rewardNames = new java.util.HashSet<>();
		for (ResourceData.ResourceEntry entry : selectedSourceEntries)
		{
			rewardNames.add(entry.getName().toLowerCase());
		}
		java.util.Set<String> out = new java.util.LinkedHashSet<>();
		for (String item : tracked)
		{
			if (rewardNames.contains(item.toLowerCase()))
			{
				out.add(item);
			}
		}
		return out;
	}

	/** The subset of {@code tracked} items that {@code target} actually drops, in tracked order. */
	private java.util.Set<String> trackedForTarget(String target, java.util.Set<String> tracked)
	{
		if (target == null || tracked.isEmpty())
		{
			return java.util.Collections.emptySet();
		}
		DropTable table = lootTables.forTarget(target);
		if (table == null)
		{
			return java.util.Collections.emptySet();
		}
		java.util.Set<String> dropNames = new java.util.HashSet<>();
		for (DropTable.Entry entry : table.getDrops())
		{
			if (entry.getName() != null)
			{
				dropNames.add(entry.getName().toLowerCase());
			}
		}
		java.util.Set<String> out = new java.util.LinkedHashSet<>();
		for (String item : tracked)
		{
			if (dropNames.contains(item.toLowerCase()))
			{
				out.add(item);
			}
		}
		return out;
	}

}
