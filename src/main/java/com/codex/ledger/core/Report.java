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
	private final long[] channels;
	private long playedMs;
	private long timedPlay;
	private long[] kindsNow;
	private long[] kindsBefore;
	private long[] kindsMoved;

	private Report(LocalDate from, int days, long start, long end, long play, long market, List<Line> gains, List<Line> losses, List<Line> movers,
		long[] channels)
	{
		this.channels = channels;
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
		long[] channels = new long[Channel.values().length];
		long playedMs = 0;
		long timedPlay = 0;
		long[] kindsBefore = null;
		long[] kindsNow = null;
		long[] kindsMoved = null;
		for (Day day : ledger.getDays())
		{
			LocalDate date = LocalDate.parse(day.getDate());
			if (from != null && date.isBefore(from))
			{
				kindsBefore = day.getKinds() != null ? day.getKinds() : kindsBefore;
				continue;
			}
			if (day.getKinds() != null)
			{
				kindsNow = day.getKinds();
			}
			kindsMoved = add(kindsMoved, day.getMoved());
			if (day.getPlayedMs() > 0)
			{
				playedMs += day.getPlayedMs();
				timedPlay += day.getPlay();
			}
			if (first == null)
			{
				first = date;
			}
			covered++;
			play += day.getPlay();
			market += day.getMarket();
			for (Channel c : Channel.values())
			{
				channels[c.ordinal()] += day.channel(c);
			}
			for (Map.Entry<Integer, Flow> e : day.getItems().entrySet())
			{
				merged.computeIfAbsent(e.getKey(), k -> new Flow()).add(e.getValue());
			}
		}
		Report report = build(first != null ? first : today, covered, ledger.getTotal(), play, market, merged, limit, channels);
		report.playedMs = playedMs;
		report.timedPlay = timedPlay;
		report.kindsNow = kindsNow;
		report.kindsBefore = kindsBefore;
		report.kindsMoved = kindsMoved;
		return report;
	}

	/** One page of the day book on its own, from the worth it opened on to the worth it closed on. */
	public static Report page(Day day, int limit)
	{
		long[] channels = new long[Channel.values().length];
		for (Channel c : Channel.values())
		{
			channels[c.ordinal()] = day.channel(c);
		}
		Report report = build(LocalDate.parse(day.getDate()), 1, day.getClose(), day.getPlay(), day.getMarket(), day.getItems(), limit, channels);
		report.playedMs = day.getPlayedMs();
		report.timedPlay = day.getPlayedMs() > 0 ? day.getPlay() : 0;
		report.kindsNow = day.getKinds();
		report.kindsMoved = day.getMoved();
		return report;
	}

	/** Below this much logged-in time an hourly figure is mostly noise and is not given. */
	public static final long MIN_TIMED_MS = 10 * 60_000L;

	/** Time logged in over the pages covered; pages written before it was kept add nothing. */
	public long getPlayedMs()
	{
		return playedMs;
	}

	/** Whether there is enough logged-in time for an hourly figure. */
	public boolean hasHourly()
	{
		return playedMs >= MIN_TIMED_MS;
	}

	/**
	 * Play an hour logged in: the gold made or spent by playing, over the pages that kept their time,
	 * so a page from before time was kept never dilutes it. Market moves are not play and stay out.
	 */
	public long perHour()
	{
		return hasHourly() ? Math.round(timedPlay * 3_600_000.0 / playedMs) : 0;
	}

	/** Worth by kind as the span ends, by {@link Kind} ordinal, or null when kinds were not kept. */
	public long[] getKinds()
	{
		return kindsNow == null ? null : kindsNow.clone();
	}

	/**
	 * How each kind's worth changed over the span, by {@link Kind} ordinal: from the last page before the
	 * span to the end. Null when there is no earlier page with kinds to measure from.
	 */
	public long[] kindChange()
	{
		if (kindsNow == null || kindsBefore == null)
		{
			return null;
		}
		long[] change = new long[kindsNow.length];
		for (int i = 0; i < change.length; i++)
		{
			change[i] = kindsNow[i] - kindsBefore[i];
		}
		return change;
	}

	/**
	 * What each kind gained or lost over the span from its items coming and going, by {@link Kind} ordinal,
	 * leaving price moves out: coins that turned into gear show here, a price rise does not. Null when no
	 * page in the span told kinds apart.
	 */
	public long[] kindFlow()
	{
		return kindsMoved == null ? null : kindsMoved.clone();
	}

	private static long[] add(long[] sum, long[] more)
	{
		if (more == null)
		{
			return sum;
		}
		long[] next = sum == null ? new long[more.length] : sum;
		for (int i = 0; i < more.length; i++)
		{
			next[i] += more[i];
		}
		return next;
	}

	private static Report build(LocalDate from, int covered, long end, long play, long market, Map<Integer, Flow> merged, int limit,
		long[] channels)
	{
		List<Line> gains = new ArrayList<>();
		List<Line> losses = new ArrayList<>();
		List<Line> movers = new ArrayList<>();
		for (Map.Entry<Integer, Flow> e : merged.entrySet())
		{
			// What was bought and sold on the exchange is told on its own; these lists are the rest.
			Flow f = e.getValue();
			Line line = new Line(e.getKey(), f.getOwnQuantity(), f.getOwnValue(), f.getDrift());
			if (f.getOwnValue() > 0)
			{
				gains.add(line);
			}
			else if (f.getOwnValue() < 0)
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
			head(gains, limit), head(losses, limit), head(movers, limit), channels);
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

	/** Part of the play by where it came from; pages written before this was kept count as nothing. */
	public long channel(Channel channel)
	{
		return channels[channel.ordinal()];
	}

	/** Play the pages do not say where it came from, as on pages written before that was kept. */
	public long unexplained()
	{
		long sum = 0;
		for (long c : channels)
		{
			sum += c;
		}
		return play - sum;
	}

	public List<Line> getMovers()
	{
		return movers;
	}
}
