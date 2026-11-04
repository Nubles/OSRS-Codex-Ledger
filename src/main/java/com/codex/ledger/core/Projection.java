package com.codex.ledger.core;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the account's worth is heading, with how sure that is. Worth moves like a walk with a drift:
 * each calendar day adds a change (play and market, days off counting as the nothing they added), and
 * the drift is the average of those changes, so the central line is the same pace {@link Forecast}
 * gives. The band around it is an 80% band for where the worth may stand: it widens with the square
 * root of the days ahead for the day-to-day swings, and in a straight line for not knowing the drift
 * itself exactly, which a short history cannot pin down. With fewer than {@link #MIN_DAYS} days of
 * changes there is no forecast, only how many more days it needs.
 */
public final class Projection
{
	/** Days of changes the trend is taken over, as the goal's pace is. */
	public static final int WINDOW = Forecast.WINDOW;
	/** Fewer days than this and the spread of the changes means too little to draw a band from. */
	public static final int MIN_DAYS = 7;
	/** The band holds this share of where the worth may be. */
	public static final double LEVEL = 0.80;
	/** No date further ahead than this is given. */
	public static final int HORIZON = 3_650;
	/** The standard normal quantile for 0.90, the upper edge of a two-sided 80% band. */
	static final double Z90 = 1.2815515655446004;

	private final LocalDate today;
	private final long worth;
	private final long[] values;
	private final int days;
	private final double drift;
	private final double spread;
	private final double t;

	private Projection(LocalDate today, long worth, long[] values, double drift, double spread)
	{
		this.today = today;
		this.worth = worth;
		this.values = values;
		this.days = values.length - 1;
		this.drift = drift;
		this.spread = spread;
		this.t = days >= 2 ? tQuantile90(days - 1) : 0;
	}

	/** The account's projection from its day book, today included. */
	public static Projection of(Ledger ledger, LocalDate today)
	{
		Map<LocalDate, Long> changes = new HashMap<>();
		LocalDate from = today.minusDays(WINDOW - 1);
		LocalDate first = null;
		for (Day d : ledger.getDays())
		{
			LocalDate date;
			try
			{
				date = LocalDate.parse(d.getDate());
			}
			catch (RuntimeException e)
			{
				continue;
			}
			if (date.isBefore(from) || date.isAfter(today))
			{
				continue;
			}
			changes.merge(date, d.getPlay() + d.getMarket(), Long::sum);
			if (first == null || date.isBefore(first))
			{
				first = date;
			}
		}
		if (first == null)
		{
			return new Projection(today, ledger.getTotal(), new long[]{ledger.getTotal()}, 0, 0);
		}
		int n = (int) ChronoUnit.DAYS.between(first, today) + 1;
		long[] daily = new long[n];
		for (int i = 0; i < n; i++)
		{
			daily[i] = changes.getOrDefault(first.plusDays(i), 0L);
		}
		return fit(today, ledger.getTotal(), daily);
	}

	/**
	 * A projection from each calendar day's change, oldest first, the last being today's, ending at
	 * the worth now.
	 */
	public static Projection fit(LocalDate today, long worth, long[] daily)
	{
		int n = daily.length;
		long[] values = new long[n + 1];
		values[n] = worth;
		for (int i = n - 1; i >= 0; i--)
		{
			values[i] = values[i + 1] - daily[i];
		}
		if (n == 0)
		{
			return new Projection(today, worth, values, 0, 0);
		}
		double sum = 0;
		for (long c : daily)
		{
			sum += c;
		}
		double mean = sum / n;
		double sq = 0;
		for (long c : daily)
		{
			sq += (c - mean) * (c - mean);
		}
		double sd = n > 1 ? Math.sqrt(sq / (n - 1)) : 0;
		return new Projection(today, worth, values, mean, sd);
	}

	/**
	 * Student's t quantile for 0.90 with {@code df} degrees of freedom, by the Cornish-Fisher expansion
	 * about the normal quantile (Abramowitz and Stegun 26.7.5); within a thousandth from 3 degrees on.
	 */
	static double tQuantile90(int df)
	{
		double z = Z90;
		double v = Math.max(1, df);
		double z3 = z * z * z;
		double z5 = z3 * z * z;
		double z7 = z5 * z * z;
		double z9 = z7 * z * z;
		double g1 = (z3 + z) / 4;
		double g2 = (5 * z5 + 16 * z3 + 3 * z) / 96;
		double g3 = (3 * z7 + 19 * z5 + 17 * z3 - 15 * z) / 384;
		double g4 = (79 * z9 + 776 * z7 + 1482 * z5 - 1920 * z3 - 945 * z) / 92160;
		return z + g1 / v + g2 / (v * v) + g3 / (v * v * v) + g4 / (v * v * v * v);
	}

	/** True with enough days of changes for a trend and a band. */
	public boolean hasTrend()
	{
		return days >= MIN_DAYS;
	}

	/** Calendar days of changes the trend is taken over. */
	public int getDays()
	{
		return days;
	}

	public LocalDate getToday()
	{
		return today;
	}

	public long getWorth()
	{
		return worth;
	}

	/** The first day of the history. */
	public LocalDate getFrom()
	{
		return today.minusDays(days - 1L);
	}

	/** Worth at the start of the history, then at the end of each day, today's being the worth now. */
	public long[] getValues()
	{
		return values.clone();
	}

	/** Worth gained a calendar day on average. */
	public double getDrift()
	{
		return drift;
	}

	/** How much a day's change typically strays from the average (the standard deviation). */
	public double getSpread()
	{
		return spread;
	}

	/** How unsure the drift itself is: the standard error of the average day. */
	public double getDriftError()
	{
		return days <= 0 ? 0 : spread / Math.sqrt(days);
	}

	/** The central line, {@code ahead} days from now. */
	public long mean(int ahead)
	{
		return Math.round(worth + drift * ahead);
	}

	/** Half the band's width {@code ahead} days from now. */
	public double halfWidth(int ahead)
	{
		if (ahead <= 0 || days < 2)
		{
			return 0;
		}
		double var = spread * spread * ahead + spread * spread * ahead * (double) ahead / days;
		return t * Math.sqrt(var);
	}

	public long low(int ahead)
	{
		return Math.round(worth + drift * ahead - halfWidth(ahead));
	}

	public long high(int ahead)
	{
		return Math.round(worth + drift * ahead + halfWidth(ahead));
	}

	/** When a target is reached: the central date, and the band's earliest and latest. */
	public static final class Eta
	{
		private final long target;
		private final Integer central;
		private final Integer early;
		private final Integer late;

		Eta(long target, Integer central, Integer early, Integer late)
		{
			this.target = target;
			this.central = central;
			this.early = early;
			this.late = late;
		}

		public long getTarget()
		{
			return target;
		}

		/** Days from today on the central line, or null when it never gets there within ten years. */
		public Integer getCentral()
		{
			return central;
		}

		/** Days until the top of the band reaches it, or null. */
		public Integer getEarly()
		{
			return early;
		}

		/** Days until the bottom of the band reaches it, or null when the band cannot promise it at all. */
		public Integer getLate()
		{
			return late;
		}
	}

	/** When a worth is reached; 0 days when it already is. */
	public Eta eta(long target)
	{
		if (worth >= target)
		{
			return new Eta(target, 0, 0, 0);
		}
		Integer central = null;
		if (drift > 0)
		{
			long ahead = (long) Math.ceil((target - worth) / drift);
			central = ahead <= HORIZON ? (int) ahead : null;
		}
		return new Eta(target, central, first(target, true), first(target, false));
	}

	/** The first day the top (or bottom) of the band reaches a worth, or null within the horizon. */
	private Integer first(long target, boolean top)
	{
		for (int h = 1; h <= HORIZON; h++)
		{
			if ((top ? high(h) : low(h)) >= target)
			{
				return h;
			}
		}
		return null;
	}

	/** The days with the biggest changes either way, biggest first: what most shaped the trend. */
	public List<Integer> biggestDays(int limit)
	{
		List<Integer> idx = new ArrayList<>();
		for (int i = 0; i < days; i++)
		{
			if (values[i + 1] != values[i])
			{
				idx.add(i);
			}
		}
		idx.sort((a, b) -> Long.compare(Math.abs(values[b + 1] - values[b]), Math.abs(values[a + 1] - values[a])));
		return idx.size() <= limit ? idx : Collections.unmodifiableList(new ArrayList<>(idx.subList(0, limit)));
	}

	/** One day's change, by its index in the history (0 is the first day). */
	public long change(int index)
	{
		return values[index + 1] - values[index];
	}
}
