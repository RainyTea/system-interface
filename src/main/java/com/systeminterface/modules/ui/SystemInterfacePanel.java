package com.systeminterface.modules.ui;

import com.systeminterface.core.SystemInterfaceConfig;
import com.systeminterface.services.drops.LootTables;
import com.systeminterface.services.lookup.ItemMembership;
import com.systeminterface.services.lookup.ItemNameCache;
import com.systeminterface.services.portrait.PortraitService;
import com.systeminterface.modules.skills.ResourceData;
import com.systeminterface.modules.skills.SkillTracker;
import com.systeminterface.services.state.ActivityFocus;
import com.systeminterface.services.state.SessionTotals;
import com.systeminterface.services.state.StateTracker;
import java.awt.BorderLayout;
import java.awt.Color;
import javax.swing.JTextField;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.PluginPanel;

/**
 * Thin side-panel coordinator. It builds and lays out (top→bottom) a compact search bar,
 * the {@link SessionSummaryPanel}, the {@link CombatSection}, and the {@link SkillingSection},
 * then routes its public methods to those components. All per-section rendering (combat rows,
 * loot log, rarity/track grids, skilling live stats) now lives inside the components — this class
 * owns none of it.
 *
 * <p>The plugin constructs this panel manually (not via Guice) and calls the seven public methods
 * below; their signatures are load-bearing and must not change without updating the plugin.
 */
public class SystemInterfacePanel extends PluginPanel
{
	private static final Color ACCENT = new Color(120, 200, 255);
	private static final long SKILLING_RECENCY_MS = 1800; // ~3 game ticks of slack

	private final StateTracker stateTracker;
	private final LootTables lootTables;
	private final SkillTracker skillTracker;
	private final SessionTotals sessionTotals;
	private final ClientThread clientThread;
	private final SystemInterfaceConfig config;

	private final ActivityFocus activityFocus;
	private ActivityFocus.Snapshot lastSnapshot;

	private final SessionSummaryPanel sessionSummary;
	private final CombatSection combat;
	private final SkillingSection skilling;

	private final JTextField searchField = new JTextField();

	public SystemInterfacePanel(
		StateTracker stateTracker,
		LootTables lootTables,
		ItemManager itemManager,
		ConfigManager configManager,
		PortraitService portraitService,
		ItemMembership itemMembership,
		ItemNameCache itemNameCache,
		CollapseStateStore collapseStateStore,
		SkillTracker skillTracker,
		ResourceData resourceData,
		SessionTotals sessionTotals,
		ClientThread clientThread,
		SystemInterfaceConfig config)
	{
		super();
		this.stateTracker = stateTracker;
		this.lootTables = lootTables;
		this.skillTracker = skillTracker;
		this.sessionTotals = sessionTotals;
		this.clientThread = clientThread;
		this.config = config;

		// Constructed BEFORE the sections so their manual-toggle Runnables can reference it.
		this.activityFocus = new ActivityFocus();

		this.sessionSummary = new SessionSummaryPanel(sessionTotals, this::onResetToday, this::onResetAllTime);
		this.combat = new CombatSection(stateTracker, lootTables, itemManager, itemMembership, itemNameCache,
			collapseStateStore, portraitService, configManager, config,
			() -> { activityFocus.manualSectionToggle(ActivityFocus.Mode.COMBAT); applyFocus(); },
			() -> { activityFocus.pinSectionOpen(ActivityFocus.Mode.COMBAT); applyFocus(); });
		this.skilling = new SkillingSection(skillTracker, resourceData, itemManager,
			collapseStateStore, configManager, config,
			() -> { activityFocus.manualSectionToggle(ActivityFocus.Mode.SKILLING); applyFocus(); },
			() -> { activityFocus.pinSectionOpen(ActivityFocus.Mode.SKILLING); applyFocus(); });

		final JPanel content = new JPanel();
		content.setLayout(new DynamicGridLayout(0, 1, 0, 0));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);

		content.add(buildSearchBar());
		content.add(sessionSummary);
		content.add(combat);
		content.add(skilling);

		// Top-align content; RuneLite's PluginPanel provides the outer scroll.
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.add(content, BorderLayout.NORTH);

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(wrapper, BorderLayout.CENTER);

		sessionSummary.update(aggregateAllTimeRewards());
		applyFocus(); // set initial expansion state

		revalidate();
		repaint();
	}

	/** Compact, non-collapsible search field at the very top; typing surfaces a matching combat row. */
	private JPanel buildSearchBar()
	{
		JPanel bar = new JPanel(new BorderLayout());
		bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bar.setBorder(new EmptyBorder(6, 6, 4, 6));

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
		bar.add(searchField, BorderLayout.CENTER);
		return bar;
	}

	/** Looks up a registered target by substring and surfaces/opens its row in the Combat section. */
	private void onSearchChanged()
	{
		String query = searchField.getText().trim().toLowerCase();
		if (query.equals("search...") || query.isEmpty())
		{
			return;
		}
		for (String t : lootTables.registeredTargets())
		{
			if (t.toLowerCase().contains(query))
			{
				combat.setCurrentTarget(t);
				return;
			}
		}
	}

	// ---------------------------------------------------------------------
	// Public API — routed to the components (signatures load-bearing; plugin calls these).
	// ---------------------------------------------------------------------

	public void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			sessionSummary.update(aggregateAllTimeRewards());
			combat.refresh();
			skilling.refresh();
		});
	}

	public void refreshProfitOnly()
	{
		SwingUtilities.invokeLater(() ->
		{
			sessionSummary.update(aggregateAllTimeRewards());
			combat.refresh();
		});
	}

	public void refreshSkillingOnly()
	{
		skilling.refresh();
	}

	public void setCurrentTarget(String target)
	{
		combat.setCurrentTarget(target);
		// Feed the focus as a pre-engage SELECT (not live combat): shows the combat tracking
		// table without asserting COMBAT mode. Confined to the EDT like all activityFocus access.
		SwingUtilities.invokeLater(() ->
		{
			activityFocus.combatSelect(target, System.currentTimeMillis());
			applyFocus();
		});
	}

	public void setFreeWorld(boolean freeWorld)
	{
		combat.setFreeWorld(freeWorld);
	}

	/**
	 * Re-applies feature toggles that affect panel layout. The former {@code showLootLog} toggle is
	 * retired — the Loot Log now always lives inside expanded combat rows — so this is currently a
	 * no-op. Kept public (the plugin calls it on config change) and the {@code showLootLog} config
	 * key is intentionally left in place so users' saved settings aren't reset.
	 */
	public void applyConfigToggles()
	{
		// No layout-affecting toggles remain.
	}

	public void updateSkillingLiveStats()
	{
		skilling.updateLiveStats();
	}

	/**
	 * Per-tick focus driver, called by the plugin on the client thread each {@link net.runelite.api.events.GameTick}.
	 * Reads {@link SkillTracker} state on the client thread (where it is safe), then marshals every
	 * {@link ActivityFocus} mutation + {@link #applyFocus()} onto the EDT — {@code activityFocus} is
	 * single-thread-confined to the EDT. No {@code client}/{@code SkillTracker} game state is read
	 * inside the {@code invokeLater}; only the already-read primitives/refs are passed in.
	 *
	 * <p><b>Skilling precedence:</b> fishing spots are NPCs, so an actively-fishing player would
	 * otherwise be misread as in combat. When actively skilling, the combat signal is ignored.
	 *
	 * @param nowMs            the clock supplied by the caller
	 * @param liveCombatTarget the NPC the local player is fighting (fishing spots excluded), or null
	 */
	public void onGameTick(long nowMs, String liveCombatTarget)
	{
		final Skill active = skillTracker.getActiveSkill();
		final boolean skillingNow = active != null && skillTracker.getMillisSinceActivity() <= SKILLING_RECENCY_MS;
		SwingUtilities.invokeLater(() ->
		{
			if (skillingNow)
			{
				activityFocus.skillingInteraction(active, nowMs); // SKILLING PRECEDENCE
			}
			else if (liveCombatTarget != null)
			{
				activityFocus.combatInteraction(liveCombatTarget, nowMs);
			}
			applyFocus();
		});
	}

	/**
	 * EDT-only. Diffs the current {@link ActivityFocus.Snapshot} against the last applied one and, on
	 * change, pushes expansion state into the two sections. The {@code equals} guard prevents per-tick
	 * rebuild churn; content refresh is handled separately by {@code refresh*}/{@code updateSkillingLiveStats}.
	 */
	private void applyFocus()
	{
		ActivityFocus.Snapshot s = activityFocus.snapshot();
		if (s.equals(lastSnapshot))
		{
			return;
		}
		lastSnapshot = s;
		combat.applyCombatFocus(s.combatSectionExpanded, s.combatContextTarget, s.autoExpandedCombatSource);
		skilling.applySkillingFocus(s.skillingSectionExpanded, s.skillingTrackingSkill, s.autoExpandedSkill);
	}

	// ---------------------------------------------------------------------
	// All-time aggregate + Session Summary reset callbacks
	// ---------------------------------------------------------------------

	/**
	 * All-time reward value = combat lifetime kept (from {@link StateTracker}) + skilling lifetime
	 * kept GP. Both reads are EDT-safe: {@code accountProfit()} was already read from the EDT by the
	 * old panel, and {@code getSkillingKeptGp()} is a client-thread snapshot (no pricing on the EDT).
	 */
	private long aggregateAllTimeRewards()
	{
		return stateTracker.accountProfit().allTimeKept + skillTracker.getSkillingKeptGp();
	}

	/** Reset today's Session Summary bucket, then re-render. Persists on the next profile save. */
	private void onResetToday()
	{
		sessionTotals.resetToday();
		sessionSummary.update(aggregateAllTimeRewards());
	}

	/**
	 * Destructive all-time reset (confirmed by SessionSummaryPanel before this runs): zeroes BOTH the
	 * combat all-time aggregate and the skilling lifetime kept, so the displayed All-time figure
	 * (combat all-time + skilling kept) truly goes to zero.
	 *
	 * <p>Both resets are marshalled onto the client thread in order: skilling first (mutating
	 * {@code skillStates}, which only the client thread may touch), then combat — {@code
	 * resetAllTimeProfit()} schedules the profile flush, and {@code onProfileSaving} pulls skilling via
	 * {@code skillTracker.toPersistence()}, which by then returns an empty map. Doing them in this
	 * order guarantees the persisted snapshot captures the cleared skilling state rather than racing
	 * it. The re-render is posted back to the EDT after the resets so the zeroed aggregate is drawn.
	 */
	private void onResetAllTime()
	{
		clientThread.invoke(() ->
		{
			skillTracker.resetAllTimeSkilling();      // clear skilling first
			stateTracker.resetAllTimeProfit();        // then combat — schedules the profile flush that persists the now-empty skilling state
			SwingUtilities.invokeLater(() -> sessionSummary.update(aggregateAllTimeRewards()));
		});
	}
}
