package com.systeminterface.common.probability;

/**
 * Closed-form probability helpers for OSRS drop-rate analysis.
 *
 * <p>All methods are static and side-effect free. {@code p} is a per-kill (or
 * per-trial) success probability in the range {@code [0, 1]} (e.g. {@code 1.0 / 5000}
 * for a {@code 1/5000} drop). {@code n} is a non-negative kill count, {@code k} a
 * non-negative drop count.
 *
 * <p>Formulas mirror the OSRS Wiki "Drop rate" article so behaviour is verifiable
 * against the worked examples there (Elysian sigil 50/50, KBD visage at 5000 kc,
 * Bandos two-item completion, etc.).
 *
 * <p>Numerical stability: for typical OSRS rates like {@code 1/5000}, the naive
 * {@code Math.pow(1 - p, n)} loses precision. We use {@code Math.log1p(-p)} and
 * {@code Math.exp} / {@code Math.expm1} instead, which keep full {@code double}
 * precision down to rates as small as {@code 1e-15}.
 *
 * <p>Bad-luck-mitigation mechanics (per the wiki, "an exception to this is if
 * there is a bad luck mitigation mechanic") are NOT modelled here — that's a
 * per-target concern owned by {@link com.systeminterface.services.drops}. This class
 * assumes independent rolls.
 */
public final class Probability
{
	private Probability()
	{
		// utility class
	}

	/**
	 * Probability of seeing the drop at least once across {@code n} independent rolls.
	 *
	 * <p>Wiki: {@code 1 - (1 - p)^n}.
	 *
	 * <p>For {@code p = 1/n} and large {@code n}, the value tends to {@code 1 - 1/e ≈ 0.6321}.
	 * This is why a "1 in N" drop yields roughly a 63.2% chance to see it in N kills.
	 *
	 * @param p drop probability per kill, in {@code [0, 1]}
	 * @param n non-negative kill count
	 * @return P(X &ge; 1) in {@code [0, 1]}
	 */
	public static double atLeastOne(double p, long n)
	{
		validate(p, n);
		if (p == 0.0 || n == 0L)
		{
			return 0.0;
		}
		if (p == 1.0)
		{
			return 1.0;
		}
		// 1 - (1-p)^n, computed as -expm1(n * ln(1-p)) for precision.
		return -Math.expm1(n * Math.log1p(-p));
	}

	/**
	 * Probability of still being dry after {@code n} kills.
	 *
	 * <p>Wiki: {@code (1 - p)^n}. Also equals {@code 1 - atLeastOne(p, n)}.
	 *
	 * <p>When the player has hit a drop at kill {@code n}, this same value is the
	 * fraction of the simulated population that would still be dry at that kill —
	 * i.e. "you are luckier than {@code stillDry(p, n) * 100}% of players".
	 *
	 * @param p drop probability per kill, in {@code [0, 1]}
	 * @param n non-negative kill count
	 * @return P(X = 0) in {@code [0, 1]}
	 */
	public static double stillDry(double p, long n)
	{
		validate(p, n);
		if (n == 0L || p == 0.0)
		{
			return 1.0;
		}
		if (p == 1.0)
		{
			return 0.0;
		}
		// (1-p)^n, computed as exp(n * ln(1-p)) for precision.
		return Math.exp(n * Math.log1p(-p));
	}

	/**
	 * Probability of receiving exactly {@code k} drops in {@code n} kills (binomial PMF).
	 *
	 * <p>Wiki: {@code C(n, k) · p^k · (1 - p)^(n - k)}.
	 *
	 * <p>Computed in log space to avoid factorial overflow:
	 * {@code ln C(n, k) = Σ ln((n - i + 1) / i)} for {@code i = 1..min(k, n-k)}.
	 *
	 * @param p drop probability per kill, in {@code [0, 1]}
	 * @param n non-negative kill count
	 * @param k non-negative drop count; returns 0 if {@code k > n}
	 * @return P(X = k) in {@code [0, 1]}
	 */
	public static double exactly(double p, long n, long k)
	{
		validate(p, n);
		if (k < 0L || k > n)
		{
			return 0.0;
		}
		if (p == 0.0)
		{
			return k == 0L ? 1.0 : 0.0;
		}
		if (p == 1.0)
		{
			return k == n ? 1.0 : 0.0;
		}

		// Iterate over the smaller side of Pascal's triangle for stability.
		final long m = Math.min(k, n - k);
		double lnBinom = 0.0;
		for (long i = 1L; i <= m; i++)
		{
			lnBinom += Math.log((double) (n - i + 1L) / (double) i);
		}
		final double lnP = lnBinom + k * Math.log(p) + (n - k) * Math.log1p(-p);
		return Math.exp(lnP);
	}

	/**
	 * Expected (mean) number of drops in {@code n} kills.
	 *
	 * <p>The mean of a binomial distribution: {@code n · p}.
	 *
	 * <p>Useful for "Deviation from Expected" displays — compare against the
	 * player's actual drop count.
	 *
	 * @param p drop probability per kill, in {@code [0, 1]}
	 * @param n non-negative kill count
	 * @return expected drop count, &ge; 0
	 */
	public static double expectedDrops(double p, long n)
	{
		validate(p, n);
		return p * (double) n;
	}

	/**
	 * Standard deviation of the drop count in {@code n} kills.
	 *
	 * <p>The std-dev of a binomial distribution: {@code σ = √(n · p · (1 − p))}.
	 *
	 * <p>Used by the Luck Status feature to classify a player's outcome:
	 * <ul>
	 *   <li>within ±1σ of {@link #expectedDrops}: <em>Average</em></li>
	 *   <li>1–2σ deviation: <em>Lucky / Unlucky</em></li>
	 *   <li>&gt;2σ deviation: <em>Very Lucky / Very Unlucky</em></li>
	 * </ul>
	 * Exact thresholds are a UI concern; this method only supplies σ.
	 *
	 * @param p drop probability per kill, in {@code [0, 1]}
	 * @param n non-negative kill count
	 * @return standard deviation of the drop count, &ge; 0
	 */
	public static double stdDev(double p, long n)
	{
		validate(p, n);
		return Math.sqrt((double) n * p * (1.0 - p));
	}

	/**
	 * Probability of receiving at most {@code k} drops in {@code n} kills (binomial CDF).
	 *
	 * <p>{@code P(X ≤ k) = Σ_{i=0..k} C(n, i) · p^i · (1 − p)^(n − i)}.
	 *
	 * <p>This is the third member of the binomial trinity alongside
	 * {@link #atLeastOne(double, long)} ({@code P(X ≥ 1)}) and
	 * {@link #exactly(double, long, long)} ({@code P(X = k)}). Used by the
	 * Luck Analysis System to express "you are luckier than X% of players"
	 * when the player has actually received drops (not just dry):
	 * <pre>
	 *     luckierThanFraction = 1 − atMost(p, n, drops − 1)
	 * </pre>
	 *
	 * @param p drop probability per kill, in {@code [0, 1]}
	 * @param n non-negative kill count
	 * @param k drop-count threshold; clamped to {@code [0, n]}
	 * @return P(X &le; k) in {@code [0, 1]}
	 */
	public static double atMost(double p, long n, long k)
	{
		validate(p, n);
		if (k < 0L)
		{
			return 0.0;
		}
		if (k >= n)
		{
			return 1.0;
		}
		// Sum P(X = i) iteratively, reusing the previous term to avoid recomputing C(n, i):
		//     P(X = i+1) / P(X = i) = ((n - i) / (i + 1)) * (p / (1 - p))
		if (p == 0.0)
		{
			return 1.0;
		}
		if (p == 1.0)
		{
			return 0.0;
		}
		final double ratio = p / (1.0 - p);
		double term = Math.exp(n * Math.log1p(-p)); // P(X = 0) = (1-p)^n
		double sum = term;
		for (long i = 0L; i < k; i++)
		{
			term *= ((double) (n - i) / (double) (i + 1L)) * ratio;
			sum += term;
		}
		return Math.min(sum, 1.0);
	}

	/**
	 * Expected number of kills until the first drop (geometric mean).
	 *
	 * <p>{@code E[T] = 1 / p}. Returns {@code Double.POSITIVE_INFINITY} when {@code p = 0}.
	 *
	 * @param p drop probability per kill, in {@code [0, 1]}
	 * @return expected kills to first drop, &ge; 1
	 */
	public static double expectedKills(double p)
	{
		validateProbability(p, "p");
		if (p == 0.0)
		{
			return Double.POSITIVE_INFINITY;
		}
		return 1.0 / p;
	}

	/**
	 * Number of kills needed to reach a target chance of at least one drop.
	 *
	 * <p>Wiki: {@code x = log(1 - target) / log(1 - p)}. Generalises the
	 * "50/50 chance" (Elysian sigil: ~2,838 KC) and "90% chance" (KBD visage:
	 * ~11,512 KC) examples on the wiki.
	 *
	 * <p>The result is a real number — callers may ceil it to a whole-kill count.
	 *
	 * @param p                 drop probability per kill, in {@code [0, 1]}
	 * @param targetProbability desired P(X &ge; 1), in {@code [0, 1]}
	 * @return kill count required, or {@code Double.POSITIVE_INFINITY} if unreachable
	 */
	public static double killsForPercentile(double p, double targetProbability)
	{
		validateProbability(p, "p");
		validateProbability(targetProbability, "targetProbability");
		if (targetProbability == 0.0)
		{
			return 0.0;
		}
		if (p == 1.0)
		{
			// One kill always satisfies any reachable target.
			return 1.0;
		}
		if (p == 0.0 || targetProbability == 1.0)
		{
			return Double.POSITIVE_INFINITY;
		}
		return Math.log1p(-targetProbability) / Math.log1p(-p);
	}

	/**
	 * Expected number of kills to receive both of two independent drops, in any order.
	 *
	 * <p>Coupon-collector's problem with unequal probabilities, solved by
	 * H. von Schelling (1954): {@code 1/p + 1/q - 1/(p + q)}.
	 *
	 * <p>Wiki worked example: Bandos hilt ({@code 1/508}) + tassets ({@code 1/381})
	 * → ~671 kills to finish both.
	 *
	 * @param p drop probability of the first item, in {@code [0, 1]}
	 * @param q drop probability of the second item, in {@code [0, 1]}
	 * @return expected kills to receive both, or {@code Double.POSITIVE_INFINITY} if either is 0
	 */
	public static double expectedKillsForBoth(double p, double q)
	{
		validateProbability(p, "p");
		validateProbability(q, "q");
		if (p == 0.0 || q == 0.0)
		{
			return Double.POSITIVE_INFINITY;
		}
		return 1.0 / p + 1.0 / q - 1.0 / (p + q);
	}

	/**
	 * Expected kills to receive all N independent drops (coupon collector, unequal probabilities).
	 *
	 * <p>Inclusion-exclusion: {@code E = Σ_{S⊂{1..n}, S≠∅} (-1)^(|S|+1) / Σ_{i∈S} p_i}.
	 * Exact for any number of items. Exponential in N, but N is small in practice (≤~10 tracked items).
	 *
	 * @param rates drop probabilities of each item, each in {@code (0, 1]}
	 * @return expected kills to complete all items, or {@code Double.POSITIVE_INFINITY} if any rate is 0
	 */
	public static double expectedKillsForAll(double[] rates)
	{
		if (rates == null || rates.length == 0)
		{
			return 0.0;
		}
		for (double p : rates)
		{
			validateProbability(p, "rate");
			if (p == 0.0)
			{
				return Double.POSITIVE_INFINITY;
			}
		}
		if (rates.length == 1)
		{
			return 1.0 / rates[0];
		}
		if (rates.length == 2)
		{
			return expectedKillsForBoth(rates[0], rates[1]);
		}

		final int n = rates.length;
		double total = 0.0;
		// Iterate over all non-empty subsets of {0..n-1} via bitmask
		for (int mask = 1; mask < (1 << n); mask++)
		{
			double sumP = 0.0;
			int bits = 0;
			for (int i = 0; i < n; i++)
			{
				if ((mask & (1 << i)) != 0)
				{
					sumP += rates[i];
					bits++;
				}
			}
			double sign = (bits % 2 == 1) ? 1.0 : -1.0;
			total += sign / sumP;
		}
		return total;
	}

	/**
	 * Pet chance per action for a skilling pet.
	 *
	 * <p>OSRS formula: {@code 1 / (baseChance - level * 25)}.
	 * At level 99, this yields the maximum chance; at level 1 it's nearly the base chance.
	 *
	 * @param baseChance the skill's base chance denominator (e.g. 317647 for WC)
	 * @param level      the player's current level in the skill (1–99)
	 * @return pet chance per action, in {@code (0, 1]}
	 */
	public static double petChance(int baseChance, int level)
	{
		if (baseChance <= 0)
		{
			throw new IllegalArgumentException("baseChance must be > 0: " + baseChance);
		}
		if (level < 1 || level > 99)
		{
			throw new IllegalArgumentException("level must be in [1, 99]: " + level);
		}
		int denom = baseChance - level * 25;
		if (denom <= 0)
		{
			return 1.0;
		}
		return 1.0 / denom;
	}

	private static void validate(double p, long n)
	{
		validateProbability(p, "p");
		if (n < 0L)
		{
			throw new IllegalArgumentException("n must be >= 0: " + n);
		}
	}

	private static void validateProbability(double value, String name)
	{
		if (Double.isNaN(value) || value < 0.0 || value > 1.0)
		{
			throw new IllegalArgumentException(name + " must be in [0, 1]: " + value);
		}
	}
}

