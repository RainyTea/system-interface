package com.systeminterface.services.state;

import java.time.LocalDate;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SessionTotalsTest
{
	private static final LocalDate MON = LocalDate.of(2026, 7, 6);
	private static final LocalDate TUE = LocalDate.of(2026, 7, 7);

	@Test
	public void accruesRewardsForTheCurrentDay()
	{
		SessionTotals t = new SessionTotals();
		t.addReward(100, MON);
		t.addReward(50, MON);
		assertEquals(150, t.todayRewards(MON));
	}

	@Test
	public void rollsOverWhenTheLocalDayChanges()
	{
		SessionTotals t = new SessionTotals();
		t.addReward(100, MON);
		assertEquals(0, t.todayRewards(TUE));   // reading on a new day shows a fresh bucket
		t.addReward(30, TUE);
		assertEquals(30, t.todayRewards(TUE));  // new day accrues from zero
	}

	@Test
	public void negativeDeltaReducesTodayWithinTheDay()
	{
		SessionTotals t = new SessionTotals();
		t.addReward(100, MON);
		t.addReward(-40, MON);                  // dropped/re-banked loot reduces kept
		assertEquals(60, t.todayRewards(MON));
	}

	@Test
	public void resetTodayZeroesTheBucket()
	{
		SessionTotals t = new SessionTotals();
		t.addReward(100, MON);
		t.resetToday();
		assertEquals(0, t.todayRewards(MON));
	}

	@Test
	public void loadRestoresTodayForTheSameDay()
	{
		SessionTotals t = new SessionTotals();
		t.loadFrom(MON.toString(), 500);
		assertEquals(500, t.todayRewards(MON));   // same day: restored
		assertEquals(0, t.todayRewards(TUE));     // later day: rolled over
	}

	@Test
	public void snapshotRoundTripsThroughStrings()
	{
		SessionTotals t = new SessionTotals();
		t.addReward(250, MON);
		SessionTotals t2 = new SessionTotals();
		t2.loadFrom(t.persistDay(), t.persistRewards());
		assertEquals(250, t2.todayRewards(MON));
	}
}
