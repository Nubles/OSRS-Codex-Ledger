package com.codex.ledger.core;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

/**
 * The day book gathered by calendar month, a statement for each: what the month made, split into
 * play and market the way each page is, the worth it closed on, how many days it has pages for and
 * its best day. Read from the pages alone, so it reaches as far back as the day book does.
 */
public final class Months
{
	/** One calendar month of pages. */
	public static final class Month
	{
		private final YearMonth month;
		private final boolean current;
		private long play;
		private long market;
		private long close;
		private int pages;
		private long playedMs;
		private LocalDate best;
		private LocalDate last;
		private long bestChange;

		Month(YearMonth month, boolean current)
		{
			this.month = month;
			this.current = current;
		}

		public YearMonth getMonth()
		{
			return month;
		}

		/** True for the month still running, whose figures are so far. */
		public boolean isCurrent()
		{
			return current;
		}

		/** What the month made: play and market together, as the day book counts a day's change. */
		public long change()
		{
			return play + market;
		}

		public long getPlay()
		{
			return play;
		}

		public long getMarket()
		{
			return market;
		}

		/** The worth the month's last page closed on. */
		public long getClose()
		{
			return close;
		}

		/** Days in the month with a page in the day book. */
		public int getPages()
		{
			return pages;
		}

		public long getPlayedMs()
		{
			return playedMs;
		}

		/** The day that gained the most, or null when no day gained. */
		public LocalDate getBest()
		{
			return best;
		}

		public long getBestChange()
		{
			return bestChange;
		}
	}

	private Months()
	{
	}

	/**
	 * @param days the day book's pages, oldest first
	 * @param today the player's date, to know which month is still running
	 * @param max the most months to give
	 * @return the newest months first
	 */
	public static List<Month> of(List<Day> days, LocalDate today, int max)
	{
		List<Month> out = new ArrayList<>();
		if (days == null || today == null || max <= 0)
		{
			return out;
		}
		YearMonth now = YearMonth.from(today);
		// Keyed by month, so pages out of order still land in one statement each.
		TreeMap<YearMonth, Month> months = new TreeMap<>();
		for (Day d : days)
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
			YearMonth ym = YearMonth.from(date);
			Month month = months.computeIfAbsent(ym, k -> new Month(k, k.equals(now)));
			month.play += d.getPlay();
			month.market += d.getMarket();
			if (month.last == null || !date.isBefore(month.last))
			{
				month.last = date;
				month.close = d.getClose();
			}
			month.pages++;
			month.playedMs += d.getPlayedMs();
			long change = d.change();
			if (change > 0 && (month.best == null || change > month.bestChange))
			{
				month.best = date;
				month.bestChange = change;
			}
		}
		out.addAll(months.values());
		Collections.reverse(out);
		return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
	}
}
