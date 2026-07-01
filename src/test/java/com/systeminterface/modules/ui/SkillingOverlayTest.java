package com.systeminterface.modules.ui;

import java.util.Arrays;
import java.util.List;
import net.runelite.api.Skill;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkillingOverlayTest
{
	@Test
	public void activeLabels_showThievingCurrentContext()
	{
		List<String> labels = SkillingOverlay.activeSkillingLabels(Skill.THIEVING,
			"Pickpocketing: Warrior", 42_480, 1_092L, 42L, 8L, 0, 50, 5);

		assertTrue(labels.contains("Pickpocketing: Warrior|"));
		assertTrue(labels.contains("XP/hour|42.5k"));
		assertTrue(labels.contains("XP gained|1,092"));
		assertTrue(labels.contains("Actions|42"));
		assertTrue(labels.contains("Fail rate|19%"));
		assertTrue(labels.contains("Rogue outfit|5/5, 100%"));
		assertFalse(labels.stream().anyMatch(label -> label.startsWith("Tracking:")));
	}

	@Test
	public void activeLabels_showWoodcuttingAndFishingContext()
	{
		List<String> woodcutting = SkillingOverlay.activeSkillingLabels(Skill.WOODCUTTING,
			"Woodcutting: Yew tree", 55_000, 3_240L, 36L, 0L, 72_321, 80, 0);
		List<String> fishing = SkillingOverlay.activeSkillingLabels(Skill.FISHING,
			"Fishing: Lobster spot", 12_400, 640L, 9L, 0L, 0, 63, 0);

		assertTrue(woodcutting.contains("Woodcutting: Yew tree|"));
		assertTrue(woodcutting.contains("XP gained|3,240"));
		assertTrue(fishing.contains("Fishing: Lobster spot|"));
		assertTrue(fishing.contains("XP gained|640"));
	}

	@Test
	public void activeLabels_omitUnknownFieldsCleanly()
	{
		List<String> labels = SkillingOverlay.activeSkillingLabels(Skill.FISHING,
			"Fishing", 0, 0L, 0L, 0L, 0, 0, 0);

		assertTrue(labels.contains("Fishing|"));
		assertFalse(labels.stream().anyMatch(label -> label.startsWith("XP/hour|")));
		assertFalse(labels.stream().anyMatch(label -> label.startsWith("XP gained|")));
		assertFalse(labels.stream().anyMatch(label -> label.startsWith("Actions|")));
		assertFalse(labels.stream().anyMatch(label -> label.startsWith("Fail rate|")));
		assertFalse(labels.stream().anyMatch(label -> label.startsWith("Pet chance|")));
	}

	@Test
	public void activeLabels_petChanceAppearsOnlyWithMetadata()
	{
		List<String> withPet = SkillingOverlay.activeSkillingLabels(Skill.WOODCUTTING,
			"Woodcutting", 0, 0L, 1L, 0L, 72_321, 80, 0);
		List<String> withoutPet = SkillingOverlay.activeSkillingLabels(Skill.WOODCUTTING,
			"Woodcutting", 0, 0L, 1L, 0L, 0, 80, 0);

		assertTrue(withPet.stream().anyMatch(label -> label.startsWith("Beaver chance|")));
		assertFalse(withoutPet.stream().anyMatch(label -> label.startsWith("Beaver chance|")));
	}

	@Test
	public void activeLabels_doNotRenderFullHistorySections()
	{
		List<String> labels = SkillingOverlay.activeSkillingLabels(Skill.THIEVING,
			"Pickpocketing: Warrior", 1, 1L, 1L, 0L, 0, 1, 0);

		for (String forbidden : Arrays.asList("Rewards|", "Costs|", "Net|", "Output|", "Supplies|"))
		{
			assertFalse(labels.contains(forbidden));
		}
	}
}
