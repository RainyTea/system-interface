package com.systeminterface.services.state;

import java.time.LocalDate;
import javax.inject.Singleton;

/**
 * Session Summary "Today" bucket: reward value accrued on the current local calendar day, rolling
 * over to zero when the day changes. All-time totals are aggregated elsewhere from existing tracker
 * state; this class only owns the daily bucket. {@code client}-free and unit-testable — callers pass
 * the current {@link LocalDate}.
 *
 * <p>Thread-safe: the {@code day}/{@code todayRewards} fields are written on the client thread
 * ({@code addReward}), read and written on the EDT ({@code todayRewards}/{@code resetToday}), and
 * read on the executor thread ({@code persistDay}/{@code persistRewards} during disk writes). Every
 * accessor that touches those fields is {@code synchronized} on the instance, which both establishes
 * the memory barrier the cross-thread reads need and makes the non-atomic {@code todayRewards +=}
 * safe against a concurrent {@code resetToday()}.
 */
@Singleton
public final class SessionTotals
{
	private LocalDate day;
	private long todayRewards;

	/** Accrues {@code delta} (may be negative) to today's bucket, rolling over first if the day changed. */
	public synchronized void addReward(long delta, LocalDate today)
	{
		rollIfNeeded(today);
		todayRewards += delta;
	}

	/** Today's accrued reward value, or 0 if the stored bucket is from an earlier day. */
	public synchronized long todayRewards(LocalDate today)
	{
		return today.equals(day) ? todayRewards : 0;
	}

	/** Zeroes today's bucket (right-click "Reset today"). */
	public synchronized void resetToday()
	{
		todayRewards = 0;
	}

	/** Restores persisted state; {@code isoDay} is an ISO date string or null. */
	public synchronized void loadFrom(String isoDay, long rewards)
	{
		this.day = isoDay == null ? null : LocalDate.parse(isoDay);
		this.todayRewards = rewards;
	}

	/** The stored day as an ISO string, or null if none. */
	public synchronized String persistDay()
	{
		return day == null ? null : day.toString();
	}

	/** The stored raw rewards for persistence. */
	public synchronized long persistRewards()
	{
		return todayRewards;
	}

	// Called only under addReward's lock, so it needs no synchronization of its own.
	private void rollIfNeeded(LocalDate today)
	{
		if (!today.equals(day))
		{
			day = today;
			todayRewards = 0;
		}
	}

	synchronized LocalDate day()
	{
		return day;
	}

	synchronized long rawRewards()
	{
		return todayRewards;
	}
}
