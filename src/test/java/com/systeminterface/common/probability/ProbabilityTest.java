package com.systeminterface.common.probability;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link Probability}. Each "wiki_*" test is a worked example
 * copied directly from the OSRS Wiki "Drop rate" article, so any deviation
 * from those numbers is a real regression.
 */
public class ProbabilityTest
{
	private static final double EPS = 1e-9;

	// ---------------------------------------------------------------------
	// Wiki worked examples
	// ---------------------------------------------------------------------

	/** Wiki: KBD visage, p=1/5000, n=5000 → ≈ 0.632 (≈ 1 - 1/e). */
	@Test
	public void wiki_kbdVisage_atLeastOneIn5000Kills()
	{
		double chance = Probability.atLeastOne(1.0 / 5000.0, 5000L);
		assertEquals(0.6321, chance, 1e-4);
	}

	/** Wiki: KBD visage single kill, p=1/5000 → 0.0002. */
	@Test
	public void wiki_kbdVisage_singleKill()
	{
		double chance = Probability.atLeastOne(1.0 / 5000.0, 1L);
		assertEquals(0.0002, chance, 1e-9);
	}

	/** Wiki: skeletal wyverns task, p=0.0001, n=234 → ≈ 0.023129. */
	@Test
	public void wiki_skeletalWyverns_taskOf234()
	{
		double chance = Probability.atLeastOne(0.0001, 234L);
		assertEquals(0.023129, chance, 1e-5);
	}

	/** Wiki: Elysian sigil 50/50, p=1/4095 → ≈ 2838 kills. */
	@Test
	public void wiki_elysianSigil_fiftyFifty()
	{
		double kills = Probability.killsForPercentile(1.0 / 4095.0, 0.5);
		assertEquals(2838.0, kills, 1.0);
	}

	/** Wiki: KBD visage 90% chance, p=1/5000 → ≈ 11512 kills. */
	@Test
	public void wiki_kbdVisage_ninetyPercent()
	{
		double kills = Probability.killsForPercentile(1.0 / 5000.0, 0.9);
		assertEquals(11512.0, kills, 1.0);
	}

	/** Wiki: KBD visage back-to-back (p^2). */
	@Test
	public void wiki_kbdVisage_backToBack()
	{
		double chance = Probability.exactly(1.0 / 5000.0, 2L, 2L);
		assertEquals(1.0 / 25_000_000.0, chance, 1e-15);
	}

	/** Wiki: Bandos hilt (1/508) + tassets (1/381) → ≈ 671.3 kills to finish both. */
	@Test
	public void wiki_bandos_twoItemCompletion()
	{
		double kills = Probability.expectedKillsForBoth(1.0 / 508.0, 1.0 / 381.0);
		assertEquals(671.3, kills, 0.1);
	}

	// ---------------------------------------------------------------------
	// atLeastOne / stillDry duality (P(X≥1) + P(X=0) = 1)
	// ---------------------------------------------------------------------

	@Test
	public void atLeastOnePlusStillDry_sumsToOne()
	{
		double[] rates = {1.0 / 128.0, 1.0 / 1000.0, 1.0 / 5000.0, 1.0 / 4_095.0};
		long[] kills = {1L, 50L, 1_000L, 8_421L};
		for (double p : rates)
		{
			for (long n : kills)
			{
				double sum = Probability.atLeastOne(p, n) + Probability.stillDry(p, n);
				assertEquals("p=" + p + " n=" + n, 1.0, sum, EPS);
			}
		}
	}

	// ---------------------------------------------------------------------
	// Binomial PMF (exactly)
	// ---------------------------------------------------------------------

	/** Two fair coin flips → P(0H)=0.25, P(1H)=0.5, P(2H)=0.25. */
	@Test
	public void exactly_fairCoinTwoFlips()
	{
		assertEquals(0.25, Probability.exactly(0.5, 2L, 0L), EPS);
		assertEquals(0.50, Probability.exactly(0.5, 2L, 1L), EPS);
		assertEquals(0.25, Probability.exactly(0.5, 2L, 2L), EPS);
	}

	/** PMF must sum to 1 across all k for any (p, n). */
	@Test
	public void exactly_pmfSumsToOne()
	{
		double p = 1.0 / 100.0;
		long n = 500L;
		double sum = 0.0;
		for (long k = 0; k <= n; k++)
		{
			sum += Probability.exactly(p, n, k);
		}
		assertEquals(1.0, sum, 1e-9);
	}

	/** exactly(p, n, 0) must agree with stillDry(p, n). */
	@Test
	public void exactly_zeroDrops_matchesStillDry()
	{
		double p = 1.0 / 512.0;
		long n = 2_000L;
		assertEquals(Probability.stillDry(p, n), Probability.exactly(p, n, 0L), EPS);
	}

	@Test
	public void exactly_kGreaterThanN_returnsZero()
	{
		assertEquals(0.0, Probability.exactly(0.5, 5L, 6L), 0.0);
	}

	// ---------------------------------------------------------------------
	// expectedDrops, expectedKills, killsForPercentile
	// ---------------------------------------------------------------------

	@Test
	public void expectedDrops_isNTimesP()
	{
		assertEquals(10.0, Probability.expectedDrops(0.1, 100L), EPS);
		assertEquals(1.0, Probability.expectedDrops(1.0 / 5000.0, 5000L), EPS);
	}

	// ---------------------------------------------------------------------
	// stdDev — binomial standard deviation (Luck Status)
	// ---------------------------------------------------------------------

	@Test
	public void stdDev_fairCoinNTosses()
	{
		// σ = sqrt(n * p * (1-p)) = sqrt(100 * 0.5 * 0.5) = 5
		assertEquals(5.0, Probability.stdDev(0.5, 100L), EPS);
	}

	@Test
	public void stdDev_rareDrop()
	{
		// p=1/5000, n=5000: σ = sqrt(5000 * 1/5000 * 4999/5000) ≈ 0.99990
		double sigma = Probability.stdDev(1.0 / 5000.0, 5000L);
		assertEquals(0.99990, sigma, 1e-4);
	}

	@Test
	public void stdDev_zeroKills_isZero()
	{
		assertEquals(0.0, Probability.stdDev(0.5, 0L), 0.0);
	}

	@Test
	public void stdDev_certainDrop_isZero()
	{
		assertEquals(0.0, Probability.stdDev(1.0, 100L), 0.0);
	}

	@Test
	public void stdDev_zeroProbability_isZero()
	{
		assertEquals(0.0, Probability.stdDev(0.0, 100L), 0.0);
	}

	// ---------------------------------------------------------------------
	// atMost — binomial CDF
	// ---------------------------------------------------------------------

	/** Fair coin two flips: P(X ≤ 0)=0.25, P(X ≤ 1)=0.75, P(X ≤ 2)=1.0. */
	@Test
	public void atMost_fairCoinTwoFlips()
	{
		assertEquals(0.25, Probability.atMost(0.5, 2L, 0L), EPS);
		assertEquals(0.75, Probability.atMost(0.5, 2L, 1L), EPS);
		assertEquals(1.00, Probability.atMost(0.5, 2L, 2L), EPS);
	}

	/** atMost(p, n, 0) must equal stillDry(p, n). */
	@Test
	public void atMost_zero_matchesStillDry()
	{
		double p = 1.0 / 512.0;
		long n = 2_000L;
		assertEquals(Probability.stillDry(p, n), Probability.atMost(p, n, 0L), EPS);
	}

	/** atMost(p, n, n) must equal 1.0 (P(X ≤ n) covers the whole sample space). */
	@Test
	public void atMost_full_isOne()
	{
		assertEquals(1.0, Probability.atMost(1.0 / 5000.0, 10_000L, 10_000L), EPS);
	}

	/** atMost(p, n, k) + (1 - atMost(p, n, k)) duality with exact PMF. */
	@Test
	public void atMost_consistentWithExactly()
	{
		double p = 0.01;
		long n = 200L;
		// Sum of exactly(0) + ... + exactly(k) must match atMost(k).
		double pmfSum = 0.0;
		for (long k = 0; k <= 10; k++)
		{
			pmfSum += Probability.exactly(p, n, k);
			assertEquals("k=" + k, pmfSum, Probability.atMost(p, n, k), 1e-9);
		}
	}

	/** Wiki dry-streak example: at 5000 KC for a 1/5000 drop, P(X=0)=~0.368, so
	 *  atMost(0) should equal stillDry, and atMost(1) ≈ stillDry + n·p·stillDry/(1-p). */
	@Test
	public void atMost_kbdAt5000_matchesAtLeastOneComplement()
	{
		double p = 1.0 / 5000.0;
		long n = 5000L;
		// P(X >= 1) = 1 - P(X = 0) = 1 - atMost(p, n, 0)
		double atLeastOne = 1.0 - Probability.atMost(p, n, 0L);
		assertEquals(Probability.atLeastOne(p, n), atLeastOne, EPS);
	}

	@Test
	public void atMost_kNegative_isZero()
	{
		assertEquals(0.0, Probability.atMost(0.5, 10L, -1L), 0.0);
	}

	@Test
	public void expectedKills_isOneOverP()
	{
		assertEquals(2.0, Probability.expectedKills(0.5), EPS);
		assertEquals(5000.0, Probability.expectedKills(1.0 / 5000.0), EPS);
	}

	@Test
	public void expectedKills_zeroProbability_isInfinity()
	{
		assertTrue(Double.isInfinite(Probability.expectedKills(0.0)));
	}

	@Test
	public void killsForPercentile_targetZero_isZero()
	{
		assertEquals(0.0, Probability.killsForPercentile(1.0 / 100.0, 0.0), EPS);
	}

	@Test
	public void killsForPercentile_targetOne_isInfinity()
	{
		assertTrue(Double.isInfinite(Probability.killsForPercentile(1.0 / 100.0, 1.0)));
	}

	@Test
	public void killsForPercentile_zeroProbability_isInfinity()
	{
		assertTrue(Double.isInfinite(Probability.killsForPercentile(0.0, 0.5)));
	}

	@Test
	public void killsForPercentile_certainDrop_isOne()
	{
		assertEquals(1.0, Probability.killsForPercentile(1.0, 0.5), EPS);
	}

	// ---------------------------------------------------------------------
	// expectedKillsForBoth
	// ---------------------------------------------------------------------

	@Test
	public void expectedKillsForBoth_zeroProbability_isInfinity()
	{
		assertTrue(Double.isInfinite(Probability.expectedKillsForBoth(0.0, 0.5)));
		assertTrue(Double.isInfinite(Probability.expectedKillsForBoth(0.5, 0.0)));
	}

	@Test
	public void expectedKillsForBoth_equalRates_isOnePointFiveOverP()
	{
		// For p = q: 1/p + 1/p - 1/(2p) = 2/p - 1/(2p) = 3/(2p)
		double p = 1.0 / 100.0;
		assertEquals(150.0, Probability.expectedKillsForBoth(p, p), EPS);
	}

	// ---------------------------------------------------------------------
	// expectedKillsForAll
	// ---------------------------------------------------------------------

	@Test
	public void expectedKillsForAll_twoItems_matchesForBoth()
	{
		double p = 1.0 / 508.0;
		double q = 1.0 / 381.0;
		double expected = Probability.expectedKillsForBoth(p, q);
		double actual = Probability.expectedKillsForAll(new double[]{p, q});
		assertEquals(expected, actual, 1e-6);
	}

	@Test
	public void expectedKillsForAll_singleItem_isExpectedKills()
	{
		double p = 1.0 / 100.0;
		assertEquals(100.0, Probability.expectedKillsForAll(new double[]{p}), EPS);
	}

	@Test
	public void expectedKillsForAll_threeEqualRates()
	{
		// For 3 items with equal rate p:
		// E = 3/p - 3/(2p) + 1/(3p) = (18 - 9 + 2) / (6p) = 11/(6p)
		double p = 1.0 / 100.0;
		double expected = 11.0 / (6.0 * p);
		assertEquals(expected, Probability.expectedKillsForAll(new double[]{p, p, p}), 1e-6);
	}

	@Test
	public void expectedKillsForAll_zeroProbability_isInfinity()
	{
		assertTrue(Double.isInfinite(Probability.expectedKillsForAll(new double[]{0.0, 0.5})));
	}

	@Test
	public void expectedKillsForAll_empty_isZero()
	{
		assertEquals(0.0, Probability.expectedKillsForAll(new double[]{}), 0.0);
	}

	// ---------------------------------------------------------------------
	// Boundary / validation
	// ---------------------------------------------------------------------

	@Test
	public void atLeastOne_zeroKills_isZero()
	{
		assertEquals(0.0, Probability.atLeastOne(0.5, 0L), 0.0);
	}

	@Test
	public void atLeastOne_zeroProbability_isZero()
	{
		assertEquals(0.0, Probability.atLeastOne(0.0, 1_000L), 0.0);
	}

	@Test
	public void atLeastOne_certainDrop_isOne()
	{
		assertEquals(1.0, Probability.atLeastOne(1.0, 1L), 0.0);
	}

	@Test
	public void stillDry_zeroKills_isOne()
	{
		assertEquals(1.0, Probability.stillDry(1.0 / 5000.0, 0L), 0.0);
	}

	@Test
	public void stillDry_zeroProbability_isOne()
	{
		assertEquals(1.0, Probability.stillDry(0.0, 1_000L), 0.0);
	}

	@Test
	public void numericalStability_ratesNearZero()
	{
		// 1e-12 probability * 1e6 kills — naive Math.pow loses precision here.
		double p = 1e-12;
		long n = 1_000_000L;
		double atLeast = Probability.atLeastOne(p, n);
		double stillDry = Probability.stillDry(p, n);
		assertEquals(1.0, atLeast + stillDry, 1e-15);
		// Expected drops = 1e-6, atLeastOne should also be ~1e-6 for these scales.
		assertEquals(1e-6, atLeast, 1e-9);
	}

	@Test
	public void invalidProbability_negative_throws()
	{
		expectIllegalArgument(() -> Probability.atLeastOne(-0.1, 10L));
	}

	@Test
	public void invalidProbability_aboveOne_throws()
	{
		expectIllegalArgument(() -> Probability.stillDry(1.5, 10L));
	}

	@Test
	public void invalidProbability_NaN_throws()
	{
		expectIllegalArgument(() -> Probability.expectedKills(Double.NaN));
	}

	@Test
	public void invalidKills_negative_throws()
	{
		expectIllegalArgument(() -> Probability.atLeastOne(0.5, -1L));
	}

	// ---------------------------------------------------------------------
	// petChance — skilling pet rate formula
	// ---------------------------------------------------------------------

	@Test
	public void petChance_woodcutting_level99()
	{
		// 1 / (317647 - 99*25) = 1 / 315172
		double chance = Probability.petChance(317647, 99);
		assertEquals(1.0 / 315172.0, chance, EPS);
	}

	@Test
	public void petChance_woodcutting_level1()
	{
		// 1 / (317647 - 1*25) = 1 / 317622
		double chance = Probability.petChance(317647, 1);
		assertEquals(1.0 / 317622.0, chance, EPS);
	}

	@Test
	public void petChance_oakOverride_level99()
	{
		// Oak uses 361146 base: 1 / (361146 - 99*25) = 1 / 358671
		double chance = Probability.petChance(361146, 99);
		assertEquals(1.0 / 358671.0, chance, EPS);
	}

	@Test
	public void petChance_oakOverride_level85()
	{
		// Oak uses 361146 base: 1 / (361146 - 85*25) = 1 / 359021
		double chance = Probability.petChance(361146, 85);
		assertEquals(1.0 / 359021.0, chance, EPS);
	}

	@Test
	public void petChance_denomReachesZero_clampsToOne()
	{
		// Contrived: baseChance = 100, level = 4 → denom = 100 - 100 = 0 → 1.0
		assertEquals(1.0, Probability.petChance(100, 4), 0.0);
	}

	@Test
	public void petChance_invalidBaseChance_throws()
	{
		expectIllegalArgument(() -> Probability.petChance(0, 50));
		expectIllegalArgument(() -> Probability.petChance(-1, 50));
	}

	@Test
	public void petChance_invalidLevel_throws()
	{
		expectIllegalArgument(() -> Probability.petChance(317647, 0));
		expectIllegalArgument(() -> Probability.petChance(317647, 100));
	}

	private static void expectIllegalArgument(Runnable r)
	{
		try
		{
			r.run();
			fail("Expected IllegalArgumentException");
		}
		catch (IllegalArgumentException expected)
		{
			// pass
		}
	}
}

