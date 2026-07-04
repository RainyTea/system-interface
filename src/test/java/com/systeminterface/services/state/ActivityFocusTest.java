package com.systeminterface.services.state;

import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ActivityFocusTest
{
	private ActivityFocus focus()
	{
		return new ActivityFocus();
	}

	@Test
	public void combatInteractionExpandsCombatAndShowsContext()
	{
		ActivityFocus f = focus();
		f.combatInteraction("Goblin", 0);
		ActivityFocus.Snapshot s = f.snapshot();
		assertEquals(ActivityFocus.Mode.COMBAT, s.activeMode);
		assertTrue(s.combatSectionExpanded);
		assertEquals("Goblin", s.combatContextTarget);
		assertEquals("Goblin", s.autoExpandedCombatSource);
		assertNull(s.skillingTrackingSkill);
	}

	@Test
	public void liveSkillingCollapsesCombatSection()
	{
		ActivityFocus f = focus();
		f.combatInteraction("Goblin", 0);
		f.skillingInteraction(Skill.WOODCUTTING, 1_000);
		ActivityFocus.Snapshot s = f.snapshot();
		assertEquals(ActivityFocus.Mode.SKILLING, s.activeMode);
		assertFalse(s.combatSectionExpanded);        // mutual exclusion
		assertNull(s.combatContextTarget);
		assertTrue(s.skillingSectionExpanded);
		assertEquals(Skill.WOODCUTTING, s.skillingTrackingSkill);
		assertEquals(Skill.WOODCUTTING, s.autoExpandedSkill);
	}

	@Test
	public void combatSelectShowsContextWithoutCollapsingActiveSkilling()
	{
		ActivityFocus f = focus();
		f.skillingInteraction(Skill.FISHING, 0);
		f.combatSelect("Zulrah", 500);               // appraise while skilling
		ActivityFocus.Snapshot s = f.snapshot();
		assertEquals(ActivityFocus.Mode.SKILLING, s.activeMode);   // no takeover
		assertTrue(s.skillingSectionExpanded);
		assertTrue(s.combatSectionExpanded);         // combat opens to show track table
		assertEquals("Zulrah", s.combatContextTarget);
		assertEquals("Zulrah", s.autoExpandedCombatSource);
	}

	@Test
	public void contextPersistsWithNoFurtherInteraction()
	{
		// No idle timer: once live, the column/tracking stays expanded indefinitely until
		// superseded by another interaction/select or cleared by the other activity.
		ActivityFocus f = focus();
		f.combatInteraction("Goblin", 0);
		ActivityFocus.Snapshot s = f.snapshot();
		assertTrue(s.combatSectionExpanded);
		assertEquals("Goblin", s.autoExpandedCombatSource);
		assertEquals("Goblin", s.combatContextTarget);

		// Still no change after "time passing" (there is no tick/idle mechanism anymore).
		ActivityFocus.Snapshot s2 = f.snapshot();
		assertEquals(s, s2);
		assertTrue(s2.combatSectionExpanded);
		assertEquals("Goblin", s2.autoExpandedCombatSource);
		assertEquals("Goblin", s2.combatContextTarget);
	}

	@Test
	public void selectContextPersistsUntilSuperseded()
	{
		ActivityFocus f = focus();
		f.combatSelect("Vorkath", 0);
		ActivityFocus.Snapshot s = f.snapshot();
		assertEquals("Vorkath", s.combatContextTarget);   // select persists until superseded
		assertEquals("Vorkath", s.autoExpandedCombatSource);
	}

	@Test
	public void manualSectionTogglePersistsAcrossInteractions()
	{
		ActivityFocus f = focus();
		f.combatInteraction("Goblin", 0);            // combat auto-expanded
		f.manualSectionToggle(ActivityFocus.Mode.COMBAT);
		assertFalse(f.snapshot().combatSectionExpanded);   // user collapsed it
		f.combatInteraction("Goblin", 2_000);        // sticky: manual pin survives further interactions
		assertFalse(f.snapshot().combatSectionExpanded);
		f.manualSectionToggle(ActivityFocus.Mode.COMBAT);  // only another manual toggle re-opens it
		assertTrue(f.snapshot().combatSectionExpanded);
	}

	@Test
	public void manualPinOnOneSectionSurvivesOtherSectionInteraction()
	{
		ActivityFocus f = focus();
		f.combatInteraction("Goblin", 0);                       // combat auto-expanded
		f.manualSectionToggle(ActivityFocus.Mode.COMBAT);        // collapsed
		f.manualSectionToggle(ActivityFocus.Mode.COMBAT);        // pin combat back OPEN
		f.skillingInteraction(Skill.WOODCUTTING, 1_000);         // would normally collapse combat (mutual exclusion)
		ActivityFocus.Snapshot s = f.snapshot();
		assertTrue(s.combatSectionExpanded);    // manual pin survives the other section's activity
		assertTrue(s.skillingSectionExpanded);
	}

	@Test
	public void pinSectionOpenKeepsSectionThroughOtherActivity()
	{
		ActivityFocus f = focus();
		f.skillingInteraction(Skill.FISHING, 0);
		f.pinSectionOpen(ActivityFocus.Mode.SKILLING);
		f.combatInteraction("Zulrah", 1_000);
		ActivityFocus.Snapshot s = f.snapshot();
		assertTrue(s.skillingSectionExpanded);   // pinned by content engagement
		assertTrue(s.combatSectionExpanded);
	}

	@Test
	public void manualCloseSurvivesContinuousSameSectionInteraction()
	{
		ActivityFocus f = focus();
		f.combatInteraction("Goblin", 0);
		f.manualSectionToggle(ActivityFocus.Mode.COMBAT);   // user closes it
		assertFalse(f.snapshot().combatSectionExpanded);
		f.combatInteraction("Goblin", 600);                 // continuous same-section interaction
		assertFalse(f.snapshot().combatSectionExpanded);    // must not lock/reopen
		f.combatInteraction("Goblin", 1_200);
		assertFalse(f.snapshot().combatSectionExpanded);
	}
}
