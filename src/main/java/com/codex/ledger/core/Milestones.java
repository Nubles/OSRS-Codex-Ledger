package com.codex.ledger.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The round figures the worth has passed while the ledger watched: 10M, 25M, 50M, 100M and on
 * through 1B and each billion after, each with when it was first passed, and the next one ahead. A figure the worth
 * already stood above when the ledger began is no milestone of its own. Times come from the
 * worth history, which keeps a point every few hours for the last six weeks and one a day
 * before that, so a date is exact and an hour is near.
 */
public final class Milestones
{
	/** Milestones kept, the newest. */
	public static final int KEPT = 3;
	private static final long BILLION = 1_000_000_000L;

	/** One figure passed. */
	public static final class Mark
	{
		private final long amount;
		private final long at;
		private final long after;
		private final long previous;

		Mark(long amount, long at, long after, long previous)
		{
			this.amount = amount;
			this.at = at;
			this.after = after;
			this.previous = previous;
		}

		/** The milestone before it, or 0 for the first one the ledger saw. */
		public long getPrevious()
		{
			return previous;
		}

		/** The round figure. */
		public long getAmount()
		{
			return amount;
		}

		/** When the worth first stood at or above it. */
		public long getAt()
		{
			return at;
		}

		/** How long after the milestone before it, or -1 for the first one the ledger saw. */
		public long getAfter()
		{
			return after;
		}
	}

	private final List<Mark> passed;
	private final long next;
	private final long toGo;
	private final boolean regain;

	private Milestones(List<Mark> passed, long next, long toGo, boolean regain)
	{
		this.passed = passed;
		this.next = next;
		this.toGo = toGo;
		this.regain = regain;
	}

	/** The round figure above a worth, on the same ladder the Worth page's "Heading for" aims at. */
	static long above(long worth)
	{
		return worth >= Long.MAX_VALUE - 1_000_000_000L ? Long.MAX_VALUE : Forecast.nextMark(worth);
	}

	/**
	 * @param history the worth over time, oldest first
	 * @param total the worth now
	 */
	public static Milestones of(List<Ledger.Point> history, long total, long now)
	{
		List<Ledger.Point> points = new ArrayList<>();
		if (history != null)
		{
			for (Ledger.Point p : history)
			{
				if (p != null && p.getTime() <= now)
				{
					points.add(p);
				}
			}
		}
		points.add(new Ledger.Point(now, total));
		long start = points.get(0).getValue();
		long peak = start;
		List<Mark> marks = new ArrayList<>();
		long target = above(start);
		for (Ledger.Point p : points)
		{
			peak = Math.max(peak, p.getValue());
			if (p.getValue() >= target && p.getValue() / BILLION - target / BILLION > KEPT)
			{
				// Past a billion the figures come every billion; a leap over thousands of them (only ever
				// a damaged save) marks just the last few, which are all that is kept.
				target = Math.max(target, (p.getValue() / BILLION - KEPT + 1) * BILLION);
			}
			// One point can pass several figures at once, a big drop or a sale, say: each is marked then.
			while (p.getValue() >= target && target != Long.MAX_VALUE)
			{
				Mark last = marks.isEmpty() ? null : marks.get(marks.size() - 1);
				marks.add(new Mark(target, p.getTime(), last == null ? -1 : p.getTime() - last.getAt(), last == null ? 0 : last.getAmount()));
				target = above(target);
			}
		}
		Collections.reverse(marks);
		List<Mark> kept = new ArrayList<>(marks.subList(0, Math.min(KEPT, marks.size())));
		long next = above(Math.max(0, total));
		return new Milestones(kept, next, next - Math.max(0, total), next <= peak);
	}

	/** The newest figures passed, newest first; at most {@link #KEPT}. */
	public List<Mark> getPassed()
	{
		return passed;
	}

	/** The next round figure above the worth now. */
	public long getNext()
	{
		return next;
	}

	/** How far the worth now is from it. */
	public long getToGo()
	{
		return toGo;
	}

	/** True when the worth has stood above the next figure before and fallen back under it. */
	public boolean isRegain()
	{
		return regain;
	}
}
