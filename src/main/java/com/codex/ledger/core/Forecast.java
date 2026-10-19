package com.codex.ledger.core;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Where the account's worth is heading: how fast it has grown over the last month, counted over
 * every calendar day (days off included, so the pace is an honest one), and when a goal falls at
 * that pace.
 */
public final class Forecast
{
	/** Days the pace is taken over. */
	public static final int WINDOW = 30;
	/** Days of pages needed before a pace means anything. */
	public static final int MIN_DAYS = 3;
	/** Further than this and the date says nothing useful. */
	static final long HORIZON_DAYS = 3_650;
	/** Round figures to aim at when no goal is set. */
	private static final long[] MARKS = {
		1_000_000L, 5_000_000L, 10_000_000L, 25_000_000L, 50_000_000L, 100_000_000L, 250_000_000L, 500_000_000L,
		750_000_000L, 1_000_000_000L, 1_500_000_000L, 2_000_000_000L
	};

	private final long worth;
	private final long target;
	private final boolean goalSet;
	private final int days;
	private final double perDay;
	private final LocalDate eta;

	private Forecast(long worth, long target, boolean goalSet, int days, double perDay, LocalDate eta)
	{
		this.worth = worth;
		this.target = target;
		this.goalSet = goalSet;
		this.days = days;
		this.perDay = perDay;
		this.eta = eta;
	}

	/**
	 * @param goal the worth aimed at, or 0 or less for the next round figure
	 */
	public static Forecast of(Ledger ledger, LocalDate today, long goal)
	{
		long worth = ledger.getTotal();
		boolean goalSet = goal > 0;
		long target = goalSet ? goal : nextMark(worth);
		Report report = Report.of(ledger, WINDOW, today, 0);
		int days = report.getDays() == 0 ? 0 : (int) ChronoUnit.DAYS.between(report.getFrom(), today) + 1;
		if (days < MIN_DAYS)
		{
			return new Forecast(worth, target, goalSet, days, 0, null);
		}
		double perDay = report.change() / (double) days;
		LocalDate eta = null;
		if (worth < target && perDay > 0)
		{
			long ahead = (long) Math.ceil((target - worth) / perDay);
			eta = ahead <= HORIZON_DAYS ? today.plusDays(ahead) : null;
		}
		return new Forecast(worth, target, goalSet, days, perDay, eta);
	}

	/** The next round figure above a worth. */
	static long nextMark(long worth)
	{
		for (long mark : MARKS)
		{
			if (worth < mark)
			{
				return mark;
			}
		}
		long billion = 1_000_000_000L;
		return (worth / billion + 1) * billion;
	}

	public long getTarget()
	{
		return target;
	}

	/** True when the target is the player's own goal rather than the next round figure. */
	public boolean isGoalSet()
	{
		return goalSet;
	}

	/** Calendar days the pace was taken over, or fewer than needed when it is too early to say. */
	public int getDays()
	{
		return days;
	}

	public boolean hasPace()
	{
		return days >= MIN_DAYS;
	}

	/** Worth gained a day on average over the window, or 0 when too early to say. */
	public double getPerDay()
	{
		return perDay;
	}

	public boolean isReached()
	{
		return worth >= target;
	}

	/** How far along the way to the target the worth is, 0 to 1. */
	public double progress()
	{
		return target <= 0 ? 1 : Math.max(0, Math.min(1, worth / (double) target));
	}

	public long remaining()
	{
		return Math.max(0, target - worth);
	}

	/** The day the target falls at this pace, or null when it is reached, falling away or years off. */
	public LocalDate getEta()
	{
		return eta;
	}
}
