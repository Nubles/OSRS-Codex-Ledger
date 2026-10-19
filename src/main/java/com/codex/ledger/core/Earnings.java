package com.codex.ledger.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What pays: net worth gained an hour played, by what the play was spent on. The activities come
 * from Codex Session Chronicle's journal (group "codexsessionchronicle", key "journal"), read and
 * never written: each filed session's "startedAt", "playedMs", "activity" (its main monster or
 * skill) and the net worth this ledger had as it began and ended ("worthStart", "worthNow"). So the
 * figure counts everything: loot, supplies used, deaths, trades and prices moving.
 */
public final class Earnings
{
	/** Days of sessions counted. */
	public static final int DAYS = 30;
	/** A session must be at least this long for its rate to count, as Chronicle's own records do. */
	static final long MIN_PLAYED_MS = 15 * 60_000L;
	/** Activities shown at most. */
	public static final int SHOWN = 6;

	/** One of Chronicle's filed sessions, as far as the ledger needs it. */
	public static final class Logged
	{
		long startedAt;
		long playedMs;
		Long worthStart;
		Long worthNow;
		String activity;

		Logged()
		{
		}

		Logged(long startedAt, long playedMs, Long worthStart, Long worthNow, String activity)
		{
			this.startedAt = startedAt;
			this.playedMs = playedMs;
			this.worthStart = worthStart;
			this.worthNow = worthNow;
			this.activity = activity;
		}
	}

	/** One activity's earnings over the window. */
	public static final class Line
	{
		private final String name;
		private int sessions;
		private long playedMs;
		private long change;

		Line(String name)
		{
			this.name = name;
		}

		public String getName()
		{
			return name;
		}

		public int getSessions()
		{
			return sessions;
		}

		public long getPlayedMs()
		{
			return playedMs;
		}

		/** Net worth gained over its sessions. */
		public long getChange()
		{
			return change;
		}

		/** Net worth gained an hour played. */
		public long perHour()
		{
			return playedMs <= 0 ? 0 : Math.round(change * 3_600_000.0 / playedMs);
		}
	}

	private final List<Line> lines;
	private final int days;

	private Earnings(List<Line> lines, int days)
	{
		this.lines = Collections.unmodifiableList(lines);
		this.days = days;
	}

	/**
	 * Earnings by activity over the last {@link #DAYS} days, best paying first. Sessions without both
	 * worth figures (Ledger had not seen the bank), without an activity or shorter than a quarter of
	 * an hour are left out; so is a second session begun at the same moment.
	 */
	public static Earnings of(Collection<Logged> journal, long now)
	{
		long from = now - DAYS * 86_400_000L;
		Map<String, Line> by = new LinkedHashMap<>();
		Set<Long> seen = new HashSet<>();
		long earliest = now;
		for (Logged s : journal)
		{
			if (s == null || s.activity == null || s.activity.trim().isEmpty() || s.worthStart == null || s.worthNow == null
				|| s.playedMs < MIN_PLAYED_MS || s.startedAt < from || s.startedAt > now || !seen.add(s.startedAt))
			{
				continue;
			}
			String name = s.activity.trim();
			Line line = by.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new Line(name));
			line.sessions++;
			line.playedMs += s.playedMs;
			line.change += s.worthNow - s.worthStart;
			earliest = Math.min(earliest, s.startedAt);
		}
		List<Line> lines = new ArrayList<>(by.values());
		lines.sort((a, b) -> Long.compare(b.perHour(), a.perHour()));
		int days = (int) Math.max(1, Math.min(DAYS, (now - earliest + 86_399_999L) / 86_400_000L));
		return new Earnings(lines, days);
	}

	/** Activities, best paying an hour first. */
	public List<Line> getLines()
	{
		return lines;
	}

	/** Days the sessions were counted over: fewer than {@link #DAYS} while the journal is younger. */
	public int getDays()
	{
		return days;
	}

	public boolean isEmpty()
	{
		return lines.isEmpty();
	}
}
