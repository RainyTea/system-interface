package com.systeminterface.services.state;

import com.google.gson.Gson;
import java.time.LocalDate;
import java.util.concurrent.Executors;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class StateTrackerSessionTotalsTest
{
	@Test
	public void combatKeptValueFeedsTodayRewards()
	{
		SessionTotals totals = new SessionTotals();
		StateTracker st = new StateTracker(new Gson(), Executors.newSingleThreadScheduledExecutor(), totals);
		st.recordKeptDelta("Goblin", 400);
		assertEquals(400, totals.todayRewards(LocalDate.now()));
	}
}
