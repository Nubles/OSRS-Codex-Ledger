package com.codex.ledger.core;

import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class HistoryTest
{
	@Test
	public void keepsDetailRecentlyAndDailyPointsLongAgo()
	{
		long now = 400L * History.DAY;
		List<Ledger.Point> points = new ArrayList<>();
		for (long t = now - 90 * History.DAY; t <= now; t += 5 * History.MINUTE)
		{
			points.add(new Ledger.Point(t, t));
		}
		List<Ledger.Point> kept = History.compact(points, now);
		assertEquals(now, kept.get(kept.size() - 1).getTime());
		assertTrue(kept.size() < 1_000);
		// Two days at 15 minutes is 192 points; forty-three more at 3 hours is 344; the rest daily.
		assertTrue(kept.size() > 192 + 344);
		for (int i = 1; i < kept.size(); i++)
		{
			assertTrue(kept.get(i).getTime() > kept.get(i - 1).getTime());
		}
	}
}
