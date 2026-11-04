package com.codex.ledger.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** The wealth forecast's math, on made-up histories whose truth is known. */
public class ProjectionTest
{
	private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

	private static long[] steady(int days, long each)
	{
		long[] d = new long[days];
		java.util.Arrays.fill(d, each);
		return d;
	}

	@Test
	public void aSteadyClimbIsALineWithNoBand()
	{
		Projection p = Projection.fit(TODAY, 50_000_000, steady(30, 1_000_000));
		assertTrue(p.hasTrend());
		assertEquals(30, p.getDays());
		assertEquals(1_000_000, p.getDrift(), 1e-6);
		assertEquals(0, p.getSpread(), 1e-6);
		assertEquals(20_000_000, p.getValues()[0]);
		assertEquals(50_000_000, p.getValues()[30]);
		assertEquals(60_000_000, p.mean(10));
		assertEquals(p.mean(10), p.low(10));
		assertEquals(p.mean(10), p.high(10));
		Projection.Eta eta = p.eta(75_500_000);
		// 25.5M at a million a day: 26 days, and with no spread the band says the same.
		assertEquals(Integer.valueOf(26), eta.getCentral());
		assertEquals(Integer.valueOf(26), eta.getEarly());
		assertEquals(Integer.valueOf(26), eta.getLate());
	}

	@Test
	public void notEnoughHistoryUnderAWeek()
	{
		assertFalse(Projection.fit(TODAY, 1_000_000, steady(6, 100_000)).hasTrend());
		assertTrue(Projection.fit(TODAY, 1_000_000, steady(7, 100_000)).hasTrend());
		Projection none = Projection.fit(TODAY, 1_000_000, new long[0]);
		assertFalse(none.hasTrend());
		assertEquals(0, none.halfWidth(10), 0);
	}

	@Test
	public void theBandWidensAndHoldsTheCentre()
	{
		Random r = new Random(7);
		long[] d = new long[30];
		for (int i = 0; i < d.length; i++)
		{
			d[i] = Math.round(500_000 + 2_000_000 * r.nextGaussian());
		}
		Projection p = Projection.fit(TODAY, 100_000_000, d);
		double last = 0;
		for (int h = 1; h <= 120; h++)
		{
			double w = p.halfWidth(h);
			assertTrue(w > last);
			// Wider than the day-to-day swing alone, by the drift's own uncertainty.
			assertTrue(w >= Projection.tQuantile90(29) * p.getSpread() * Math.sqrt(h) - 1e-6);
			assertTrue(p.low(h) <= p.mean(h) && p.mean(h) <= p.high(h));
			assertEquals(p.mean(h) - p.low(h), p.high(h) - p.mean(h), 1);
			last = w;
		}
	}

	@Test
	public void theBandHoldsEightyInAHundred()
	{
		// Many made-up accounts whose worth walks with a known drift and swing. Each is forecast from its
		// first 30 days; where it really stood 1, 14 and 45 days later falls in the band about 80% of the time.
		Random r = new Random(20261104L);
		int trials = 4_000;
		int[] aheads = {1, 14, 45};
		int[] inside = new int[aheads.length];
		for (int t = 0; t < trials; t++)
		{
			double drift = 200_000 + r.nextDouble() * 2_000_000;
			double swing = 300_000 + r.nextDouble() * 4_000_000;
			long[] past = new long[30];
			long worth = 50_000_000;
			for (int i = 0; i < past.length; i++)
			{
				past[i] = Math.round(drift + swing * r.nextGaussian());
				worth += past[i];
			}
			Projection p = Projection.fit(TODAY, worth, past);
			long future = worth;
			int k = 0;
			for (int h = 1; h <= 45; h++)
			{
				future += Math.round(drift + swing * r.nextGaussian());
				if (h == aheads[k])
				{
					if (future >= p.low(h) && future <= p.high(h))
					{
						inside[k]++;
					}
					k = Math.min(k + 1, aheads.length - 1);
					if (h == aheads[aheads.length - 1])
					{
						break;
					}
				}
			}
		}
		for (int k = 0; k < aheads.length; k++)
		{
			double share = inside[k] / (double) trials;
			assertTrue(aheads[k] + " days ahead: " + share, share > 0.77 && share < 0.83);
		}
	}

	@Test
	public void datesComeFromTheBand()
	{
		Random r = new Random(3);
		long[] d = new long[30];
		for (int i = 0; i < d.length; i++)
		{
			d[i] = Math.round(1_000_000 + 1_500_000 * r.nextGaussian());
		}
		Projection p = Projection.fit(TODAY, 40_000_000, d);
		Projection.Eta eta = p.eta(100_000_000);
		assertNotNull(eta.getCentral());
		assertNotNull(eta.getEarly());
		assertNotNull(eta.getLate());
		assertTrue(eta.getEarly() <= eta.getCentral() && eta.getCentral() <= eta.getLate());
		assertEquals((int) Math.ceil(60_000_000 / p.getDrift()), (int) eta.getCentral());
		// Reached already.
		assertEquals(Integer.valueOf(0), p.eta(30_000_000).getCentral());
	}

	@Test
	public void aFallingOrFlatWorthNeverGetsThereForSure()
	{
		Random r = new Random(11);
		long[] d = new long[20];
		for (int i = 0; i < d.length; i++)
		{
			d[i] = Math.round(-200_000 + 3_000_000 * r.nextGaussian());
		}
		Projection p = Projection.fit(TODAY, 40_000_000, d);
		Projection.Eta eta = p.eta(60_000_000);
		if (p.getDrift() <= 0)
		{
			assertNull(eta.getCentral());
		}
		assertNull(eta.getLate());
		// The top of the band can still reach it: luck could get there.
		assertNotNull(eta.getEarly());
	}

	@Test
	public void biggestDaysExplainTheTrend()
	{
		long[] d = steady(10, 100_000);
		d[3] = 25_000_000;
		d[7] = -9_000_000;
		Projection p = Projection.fit(TODAY, 100_000_000, d);
		List<Integer> big = p.biggestDays(2);
		assertEquals(Integer.valueOf(3), big.get(0));
		assertEquals(Integer.valueOf(7), big.get(1));
		assertEquals(25_000_000, p.change(3));
		assertEquals(TODAY.minusDays(9), p.getFrom());
	}

	@Test
	public void tQuantileAgreesWithTheDistribution()
	{
		// The t distribution's CDF by Simpson's rule up to the quantile, for a range of degrees of freedom.
		for (int df : new int[]{3, 5, 6, 9, 14, 20, 29, 60, 200})
		{
			double q = Projection.tQuantile90(df);
			assertEquals("df " + df, 0.90, tCdf(q, df), 0.001);
		}
		assertEquals(Projection.Z90, Projection.tQuantile90(1_000_000), 1e-5);
	}

	private static double tCdf(double x, int v)
	{
		double c = Math.exp(lgamma((v + 1) / 2.0) - lgamma(v / 2.0)) / Math.sqrt(v * Math.PI);
		int n = 20_000;
		double h = x / n;
		double sum = 0;
		for (int i = 0; i <= n; i++)
		{
			double y = c * Math.pow(1 + (i * h) * (i * h) / v, -(v + 1) / 2.0);
			sum += y * (i == 0 || i == n ? 1 : i % 2 == 1 ? 4 : 2);
		}
		return 0.5 + sum * h / 3;
	}

	/** Lanczos log-gamma. */
	private static double lgamma(double x)
	{
		double[] g = {676.5203681218851, -1259.1392167224028, 771.32342877765313, -176.61502916214059, 12.507343278686905,
			-0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};
		x -= 1;
		double a = 0.99999999999980993;
		double t = x + 7.5;
		for (int i = 0; i < g.length; i++)
		{
			a += g[i] / (x + i + 1);
		}
		return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
	}

	// ---- From a real ledger ----

	private static final ZoneId UTC = ZoneOffset.UTC;
	private static final long DAY = 86_400_000L;
	private static final LocalDate D0 = LocalDate.of(2026, 10, 1);
	private static final long T0 = D0.atStartOfDay(UTC).toInstant().toEpochMilli() + DAY / 2;

	@Test
	public void fromTheDayBookDaysOffCountAsNothing()
	{
		Map<Integer, Long> price = new HashMap<>();
		price.put(995, 1L);
		Prices prices = id -> price.getOrDefault(id, 0L);
		Ledger ledger = new Ledger();
		ledger.observe(Store.BANK, coins(10_000_000));
		ledger.offer(0, null);
		ledger.settle(T0, prices, UTC);
		ledger.offer(0, null);
		ledger.settle(T0 + 1_000, prices, UTC);
		// Played on days 0..13 every other day, 2M each time; days between are days off.
		long coins = 10_000_000;
		for (int i = 2; i <= 13; i += 2)
		{
			coins += 2_000_000;
			ledger.observe(Store.BANK, coins(coins));
			ledger.settle(T0 + i * DAY, prices, UTC);
		}
		LocalDate today = D0.plusDays(13);
		Projection p = Projection.of(ledger, today);
		assertTrue(p.hasTrend());
		assertEquals(14, p.getDays());
		assertEquals(22_000_000, p.getWorth());
		assertEquals(12_000_000 / 14.0, p.getDrift(), 1);
		// The central line is the goal's own pace, so the two dates agree.
		Forecast f = Forecast.of(ledger, today, 40_000_000);
		assertEquals(f.getPerDay(), p.getDrift(), 1e-6);
		assertEquals(f.getEta(), today.plusDays(p.eta(40_000_000).getCentral()));
		// Half the days added nothing: the spread is about a million a day.
		assertEquals(1_000_000, p.getSpread(), 100_000);
	}

	private static Map<Integer, Long> coins(long n)
	{
		Map<Integer, Long> m = new HashMap<>();
		m.put(995, n);
		return m;
	}
}
