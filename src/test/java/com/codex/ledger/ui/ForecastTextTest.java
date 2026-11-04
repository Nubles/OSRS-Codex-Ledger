package com.codex.ledger.ui;

import com.codex.ledger.core.Projection;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Random;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** What the forecast says in words. */
public class ForecastTextTest
{
	private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

	private static long[] steady(int n, long each)
	{
		long[] d = new long[n];
		Arrays.fill(d, each);
		return d;
	}

	@Test
	public void aSteadyClimbHasOneDate()
	{
		Projection p = Projection.fit(TODAY, 50_000_000, steady(30, 1_000_000));
		// 10M at a million a day, with no spread: 10 days, Saturday 14 November.
		assertEquals("Around Sat 14 Nov.", LedgerPanel.etaText(p, p.eta(60_000_000)));
		assertEquals("Reached.", LedgerPanel.etaText(p, p.eta(10_000_000)));
		assertEquals("Trend +1.0M a day over 30 days, give or take 0. In 30 days: 80.0M to 80.0M, most likely 80.0M.",
			LedgerPanel.trendText(p, 30));
	}

	@Test
	public void aNoisyClimbGivesARange()
	{
		Random r = new Random(5);
		long[] d = new long[30];
		for (int i = 0; i < d.length; i++)
		{
			d[i] = Math.round(1_000_000 + 1_000_000 * r.nextGaussian());
		}
		Projection p = Projection.fit(TODAY, 50_000_000, d);
		String text = LedgerPanel.etaText(p, p.eta(100_000_000));
		assertTrue(text, text.startsWith("Around ") && text.contains("; likely between "));
		// A year later is written with its year.
		String far = LedgerPanel.etaText(p, p.eta(200_000_000));
		assertTrue(far, far.contains("2027"));
	}

	@Test
	public void aFallingWorthIsSaidToFall()
	{
		Random r = new Random(9);
		long[] d = new long[20];
		for (int i = 0; i < d.length; i++)
		{
			d[i] = Math.round(-1_000_000 + 2_000_000 * r.nextGaussian());
		}
		Projection p = Projection.fit(TODAY, 50_000_000, d);
		String text = LedgerPanel.etaText(p, p.eta(60_000_000));
		assertTrue(text, text.startsWith("Not getting closer") || text.startsWith("Not within"));
	}

	@Test
	public void oneLuckyDayIsCalledOut()
	{
		long[] d = steady(10, 100_000);
		d[4] = 30_000_000;
		Projection p = Projection.fit(TODAY, 80_000_000, d);
		// Without its 30M day, nine days of 100k: 100k a day.
		assertEquals("One day carries much of this: without " + Format.day(TODAY.minusDays(5)) + " the trend is +100k a day.",
			LedgerPanel.leanText(p));
		assertNull(LedgerPanel.leanText(Projection.fit(TODAY, 80_000_000, steady(10, 100_000))));
	}
}
