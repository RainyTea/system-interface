package com.systeminterface.services.state;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record of a single drop: <em>"you got {@code dropName} from
 * {@code targetName} at kill {@code kc} on {@code timestamp}, according to
 * {@code source}"</em>.
 *
 * <p>The list of {@code DropOccurrence}s for a target powers dry-streak
 * calculation, "luckier than X%" classification, and Collection Log
 * "✓ at KC N" annotations.
 *
 * <p>Designed to round-trip through Gson via field-name reflection — see
 * {@link StateTracker} for persistence details.
 */
public final class DropOccurrence
{
	private final String targetName;
	private final String dropName;
	private final int kc;
	private final long timestampEpochSeconds;
	private final StateTracker.KcSource source;
	private final boolean kcEstimated;

	public DropOccurrence(
		String targetName,
		String dropName,
		int kc,
		long timestampEpochSeconds,
		StateTracker.KcSource source,
		boolean kcEstimated)
	{
		this.targetName = Objects.requireNonNull(targetName, "targetName");
		this.dropName = Objects.requireNonNull(dropName, "dropName");
		this.kc = Math.max(0, kc);
		this.timestampEpochSeconds = timestampEpochSeconds;
		this.source = Objects.requireNonNull(source, "source");
		this.kcEstimated = kcEstimated;
	}

	/** Convenience: now-stamped occurrence for live events. */
	public static DropOccurrence now(String targetName, String dropName, int kc, StateTracker.KcSource source)
	{
		return new DropOccurrence(targetName, dropName, kc, Instant.now().getEpochSecond(), source,
			source == StateTracker.KcSource.WISE_OLD_MAN_ESTIMATE);
	}

	public String getTargetName()
	{
		return targetName;
	}

	public String getDropName()
	{
		return dropName;
	}

	public int getKc()
	{
		return kc;
	}

	public long getTimestampEpochSeconds()
	{
		return timestampEpochSeconds;
	}

	public StateTracker.KcSource getSource()
	{
		return source;
	}

	public boolean isKcEstimated()
	{
		return kcEstimated;
	}

	@Override
	public String toString()
	{
		return "DropOccurrence{" + dropName + " @ kc=" + kc + (kcEstimated ? " (est.)" : "")
			+ " src=" + source + "}";
	}
}


