package com.codex.ledger.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the gold went over a stretch of days: the change in worth split into play and market,
 * the items that brought the most in, the ones that took the most out, and the prices that moved.
 */
public final class Report
{
	/** One item's part in a report, or in the holdings. */
	public static final class Line
	{
		private final int itemId;
		private final long quantity;
		private final long value;
		private final long drift;

		public Line(int itemId, long quantity, long value, long drift)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.value = value;
			this.drift = drift;
		}

		public int getItemId()
		{
			return itemId;
		}

		public long getQuantity()
		{
			return quantity;
		}

		public long getValue()
		{
			return value;
		}

		public long getDrift()
		{
			return drift;
		}
	}

	private final LocalDate from;
	private final int days;
	private final long start;
	private final long end;
	private final long play;
	private final long market;
	private final List<Line> gains;
	private final List<Line> losses;
	private final List<Line> movers;

	private Report(LocalDate from, int days, long start, long end, long play, long market, List<Line> gains, List<Line> losses, List<Line> movers)
	{
		this.from = from;
		this.days = days;
		this.start = start;
		this.end = end;
		this.play = play;
		this.market = market;
		this.gains = gains;
		this.losses = losses;
		this.movers = movers;
	}

	/**
	 * @param span days to cover, today included; 0 or less covers the whole day book
	 * @param limit lines kept in each list
	 */
	public static Report of(Ledger ledger, int span, LocalDate today, int limit)
	{
		LocalDate from = span <= 0 ? null : today.minusDays(span - 1);
		long play = 0;
		long market = 0;
		int covered = 0;
		LocalDate first = null;
		Map<Integer, Flow> merged = new HashMap<>();
		for (Day day : ledger.getDays())
		{
			LocalDate date = LocalDate.parse(day.getDate());
			if (from != null && date.isBefore(from))
			{
				continue;
			}
			if (first == null)
			{
				first = date;
			}
			covered++;
			play += day.getPlay();
			market += day.getMarket();
			for (Map.Entry<Integer, Flow> e : day.getItems().entrySet())
			{
				merged.computeIfAbsent(e.getKey(), k -> new Flow()).add(e.getValue());
			}
		}
		return build(first != null ? first : today, covered, ledger.getTotal(), play, market, merged, limit);
	}

	/** One page of the day book on its own, from the worth it opened on to the worth it closed on. */
	public static Report page(Day day, int limit)
	{
		return build(LocalDate.parse(day.getDate()), 1, day.getClose(), day.getPlay(), day.getMarket(), day.getItems(), limit);
	}

	private static Report build(LocalDate from, int covered, long end, long play, long market, Map<Integer, Flow> merged, int limit)
	{
		List<Line> gains = new ArrayList<>();
		List<Line> losses = new ArrayList<>();
		List<Line> movers = new ArrayList<>();
		for (Map.Entry<Integer, Flow> e : merged.entrySet())
		{
			Flow f = e.getValue();
			Line line = new Line(e.getKey(), f.getQuantity(), f.getValue(), f.getDrift());
			if (f.getValue() > 0)
			{
				gains.add(line);
			}
			else if (f.getValue() < 0)
			{
				losses.add(line);
			}
			if (f.getDrift() != 0)
			{
				movers.add(line);
			}
		}
		gains.sort(Comparator.comparingLong(Line::getValue).reversed());
		losses.sort(Comparator.comparingLong(Line::getValue));
		movers.sort(Comparator.comparingLong((Line l) -> Math.abs(l.getDrift())).reversed());
		return new Report(from, covered, end - play - market, end, play, market,
			head(gains, limit), head(losses, limit), head(movers, limit));
	}

	/** Every item held, most valuable first. */
	public static List<Line> holdings(Ledger ledger, int limit)
	{
		List<Line> lines = new ArrayList<>();
		for (Map.Entry<Integer, Long> e : ledger.holdings().entrySet())
		{
			long value = e.getValue() * ledger.price(e.getKey());
			if (value > 0)
			{
				lines.add(new Line(e.getKey(), e.getValue(), value, 0));
			}
		}
		lines.sort(Comparator.comparingLong(Line::getValue).reversed());
		return head(lines, limit);
	}

	private static List<Line> head(List<Line> list, int limit)
	{
		return Collections.unmodifiableList(new ArrayList<>(list.subList(0, Math.min(limit, list.size()))));
	}

	public LocalDate getFrom()
	{
		return from;
	}

	/** Pages of the day book the report covers. */
	public int getDays()
	{
		return days;
	}

	public long getStart()
	{
		return start;
	}

	public long getEnd()
	{
		return end;
	}

	public long change()
	{
		return end - start;
	}

	public long getPlay()
	{
		return play;
	}

	public long getMarket()
	{
		return market;
	}

	public List<Line> getGains()
	{
		return gains;
	}

	public List<Line> getLosses()
	{
		return losses;
	}

	public List<Line> getMovers()
	{
		return movers;
	}
}
