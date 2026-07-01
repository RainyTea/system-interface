package com.systeminterface.modules.skills;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link PetDisplay} — the pet-odds formatting shared by the skilling
 * overlay, side panel, and resource Appraise window.
 */
public class PetDisplayTest
{
	/** Rock Golem base 741,600 at level 50: denominator = 741600 - 50*25 = 740,350. */
	@Test
	public void oddsDenominator_appliesLevelTerm()
	{
		assertEquals(740_350L, PetDisplay.oddsDenominator(741_600, 50));
	}

	/** The level term shifts the denominator down as level rises. */
	@Test
	public void oddsDenominator_higherLevelLowerDenominator()
	{
		long low = PetDisplay.oddsDenominator(741_600, 1);
		long high = PetDisplay.oddsDenominator(741_600, 99);
		assertTrue("higher level should reduce the denominator", high < low);
		assertEquals(741_600L - 25L, low);
		assertEquals(741_600L - 99L * 25L, high);
	}

	@Test
	public void oddsText_isGroupedFraction()
	{
		assertEquals("1 / 740,350", PetDisplay.oddsText(741_600, 50));
	}
}
