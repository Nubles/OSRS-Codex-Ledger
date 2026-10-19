package com.codex.ledger.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the worth history small without losing its shape: fine detail for the last two days,
 * a point every few hours for the last six weeks, and one a day beyond that.
 */
public final class History
{
	static final long MINUTE = 60_000L;
	static final long HOUR = 60 * MINUTE;
	static final long DAY = 24 * HOUR;
	/** Points kept at most, whatever their age. */
	static final int MAX_POINTS = 1_200;

	private History()
	{
	}

	/** The width of the time bucket a point of this age shares with its neighbours. */
	static long bucket(long age)
	{
		if (age < 2 * DAY)
		{
			return 15 * MINUTE;
		}
		if (age < 45 * DAY)
		{
			return 3 * HOUR;
		}
		return DAY;
	}

	/** Keeps the last point in every bucket, oldest first. The newest point is always kept. */
	public static List<Ledger.Point> compact(List<Ledger.Point> points, long now)
	{
		List<Ledger.Point> kept = new ArrayList<>();
		for (int i = 0; i < points.size(); i++)
		{
			Ledger.Point p = points.get(i);
			if (i == points.size() - 1)
			{
				kept.add(p);
				break;
			}
			Ledger.Point next = points.get(i + 1);
			long size = bucket(now - p.getTime());
			long nextSize = bucket(now - next.getTime());
			if (size != nextSize || p.getTime() / size != next.getTime() / size)
			{
				kept.add(p);
			}
		}
		if (kept.size() > MAX_POINTS)
		{
			kept.subList(0, kept.size() - MAX_POINTS).clear();
		}
		return kept;
	}
}
