package com.systeminterface.modules.ui;

import com.google.gson.Gson;
import com.systeminterface.modules.skills.ResourceData;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.time.LocalDate;
import net.runelite.api.Skill;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SystemInterfacePanelTest
{
	@Test
	public void visibleSectionOrder_startsWithSearchThenSessionSummary()
	{
		assertEquals(Arrays.asList(
			"Search / Lookup",
			"Session Summary",
			"Combat",
			"Skilling"),
			SystemInterfacePanel.visibleSectionOrder(true));
	}

	@Test
	public void visibleSectionOrder_isStableAcrossLegacyLootToggle()
	{
		assertEquals(Arrays.asList(
			"Search / Lookup",
			"Session Summary",
			"Combat",
			"Skilling"),
			SystemInterfacePanel.visibleSectionOrder(false));
	}

	@Test
	public void searchAndDebug_areNotNormalCollapsibleSections()
	{
		assertFalse(SystemInterfacePanel.searchLookupIsCollapsibleSection());
		assertFalse(SystemInterfacePanel.debugDiagnosticsIsNormalSection());
	}

	@Test
	public void sessionSummaryLabels_includeTodayAndAllTimeRowsWithLocalDateMarker()
	{
		SystemInterfacePanel.SessionTotals totals = new SystemInterfacePanel.SessionTotals(
			412_000L, 58_000L, 12_400_000L, 2_100_000L);

		assertEquals(Arrays.asList(
			"Today Rewards|412K",
			"Today Costs|58K",
			"Today Net|354K",
			"All-time Rewards|12.4M",
			"All-time Costs|2.1M",
			"All-time Net|10.3M",
			"Day|2026-06-30"),
			SystemInterfacePanel.sessionSummaryLabels(totals, LocalDate.of(2026, 6, 30)));
	}

	@Test
	public void skillingOutputLabels_showWoodcuttingGatheredOutput()
	{
		ResourceData data = ResourceData.load(new Gson());
		Map<Integer, Long> counts = new HashMap<>();
		counts.put(1521, 2L);

		assertEquals(Collections.singletonList("Oak logs|2"),
			SystemInterfacePanel.skillingOutputLabels(data, Skill.WOODCUTTING, counts));
	}

	@Test
	public void skillingOutputLabels_showFishingGatheredOutput()
	{
		ResourceData data = ResourceData.load(new Gson());
		Map<Integer, Long> counts = new HashMap<>();
		counts.put(331, 3L);

		assertEquals(Collections.singletonList("Raw salmon|3"),
			SystemInterfacePanel.skillingOutputLabels(data, Skill.FISHING, counts));
	}

	@Test
	public void skillingOutputLabels_showThievingSpiceOutput()
	{
		ResourceData data = ResourceData.load(new Gson());
		Map<Integer, Long> counts = new HashMap<>();
		counts.put(2007, 1L);

		assertEquals(Collections.singletonList("Spice|1"),
			SystemInterfacePanel.skillingOutputLabels(data, Skill.THIEVING, counts));
	}

	@Test
	public void skillingOutputLabels_showThievingGemOutput()
	{
		ResourceData data = ResourceData.load(new Gson());
		Map<Integer, Long> counts = new HashMap<>();
		counts.put(1623, 2L);

		assertEquals(Collections.singletonList("Uncut sapphire|2"),
			SystemInterfacePanel.skillingOutputLabels(data, Skill.THIEVING, counts));
	}

	@Test
	public void unassignedSkillCounts_keepsAggregateGatheredOutputWithoutNamedSource()
	{
		Map<Integer, Long> skillCounts = new HashMap<>();
		skillCounts.put(1521, 4L);
		skillCounts.put(22798, 1L);
		Map<Integer, Long> sourceTotals = new HashMap<>();
		sourceTotals.put(22798, 1L);

		Map<Integer, Long> unassigned =
			SystemInterfacePanel.unassignedSkillCounts(skillCounts, sourceTotals);

		assertEquals(1, unassigned.size());
		assertEquals(Long.valueOf(4L), unassigned.get(1521));
	}

	@Test
	public void skillingTrackingRows_startCollapsed()
	{
		assertTrue(SystemInterfacePanel.skillingTrackingRowsCollapsedByDefault());
	}

	@Test
	public void autoCollapseOnlyCollapsesAutoOwnedSections()
	{
		assertTrue(SystemInterfacePanel.shouldCollapseAutoOpenedSection(true));
		assertFalse(SystemInterfacePanel.shouldCollapseAutoOpenedSection(false));
	}

	@Test
	public void expandedSkillingRows_includeExpectedSections()
	{
		assertEquals(Arrays.asList("Actions", "Rewards", "Costs", "Net", "Output", "Supplies"),
			SystemInterfacePanel.expandedSkillingDetailLabels());
	}

	@Test
	public void selectedSkillingOutputDetails_areItemSpecificWithoutLiveStats()
	{
		assertEquals(Arrays.asList("Observed", "Chance seen", "Progress", "Deviation", "Luck"),
			SystemInterfacePanel.selectedSkillingOutputDetailLabels());
		assertFalse(SystemInterfacePanel.selectedSkillingOutputDetailLabels().contains("Actions"));
		assertFalse(SystemInterfacePanel.selectedSkillingOutputDetailLabels().contains("XP/hour"));
		assertFalse(SystemInterfacePanel.selectedSkillingOutputDetailLabels().contains("XP gained"));
		assertFalse(SystemInterfacePanel.selectedSkillingOutputDetailLabels().contains("Fail rate"));
	}

	@Test
	public void sectionResetMenus_includeCombatAndSkillingActions()
	{
		assertEquals(Arrays.asList("Reset all combat logs", "Reset all skilling logs"),
			SystemInterfacePanel.sectionResetMenuLabels());
	}

	@Test
	public void thievingXpHour_usesElapsedSessionTime()
	{
		assertEquals(42_480L, SystemInterfacePanel.xpPerHour(1_062L, 90_000L));
		assertEquals(0L, SystemInterfacePanel.xpPerHour(1_062L, 0L));
	}

	@Test
	public void expandedCombatRows_includeLootSection()
	{
		assertEquals(Arrays.asList("KC", "Rewards", "Costs", "Net", "Loot", "Supplies"),
			SystemInterfacePanel.expandedCombatDetailLabels());
	}

	@Test
	public void emptySkillingOutput_usesNoneEmptyState()
	{
		assertEquals("None", SystemInterfacePanel.emptyOutputText(Collections.emptyList()));
	}

	@Test
	public void thievingSourceTitles_includeActivityType()
	{
		assertEquals("Pickpocket: Warrior",
			SystemInterfacePanel.skillingSourceTitle(Skill.THIEVING, "Warrior", "Pickpocket"));
		assertEquals("Stall: Silk stall",
			SystemInterfacePanel.skillingSourceTitle(Skill.THIEVING, "Silk stall", "Stall"));
		assertEquals("Oak tree",
			SystemInterfacePanel.skillingSourceTitle(Skill.WOODCUTTING, "Oak tree", "Pickpocket"));
	}
}
