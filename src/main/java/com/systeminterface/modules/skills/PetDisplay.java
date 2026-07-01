package com.systeminterface.modules.skills;

import com.systeminterface.common.probability.Probability;

/**
 * Formatting helper for the skilling-pet <em>odds</em>, shared by the skilling overlay,
 * the side panel, and the resource-node Appraise window so all three speak the same
 * language.
 *
 * <p>We only present the odds — a known, level-aware formula ({@code 1 / (B - level*25)}).
 * A dry streak / chance-seen would require the player's true lifetime actions and pet
 * ownership, neither of which the plugin can read, so any such figure would be
 * misleading and is deliberately not shown.
 *
 * <p>The level term ({@code level*25}) is tiny next to the base for Woodcutting
 * (B≈317,647) so the odds barely move with level there; it matters more for skills
 * with a smaller base. The formula stays level-aware regardless.
 */
public final class PetDisplay
{
	private PetDisplay()
	{
		// utility class
	}

	/** The pet odds denominator at this level: {@code round(1 / petChance)}. */
	public static long oddsDenominator(int petBaseChance, int level)
	{
		final double p = Probability.petChance(petBaseChance, level);
		return Math.round(1.0 / p);
	}

	/** Odds as "1 / 42,377". The denominator also reads as ~expected actions to the pet. */
	public static String oddsText(int petBaseChance, int level)
	{
		return "1 / " + String.format("%,d", oddsDenominator(petBaseChance, level));
	}
}
