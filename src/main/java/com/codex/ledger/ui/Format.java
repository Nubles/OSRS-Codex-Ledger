package com.codex.ledger.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Coins, changes, durations and dates in the short forms the panel uses. */
public final class Format
{
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);
	private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
	private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);

	private Format()
	{
	}

	/**
	 * Coins at one precision per unit, so figures side by side read alike: "8,240", "41.2k", "412.3k",
	 * "4.9M", "26.0M", "835.5M", "1.504B". A zero after the point is kept ("26.0M", not "26M").
	 */
	public static String amount(long n)
	{
		if (n < 10_000)
		{
			return String.format(Locale.ENGLISH, "%,d", n);
		}
		// A figure that rounds up to a thousand of one unit is written in the next ("1.0M", not "1000.0k").
		if (n < 999_950)
		{
			return fixed(n / 1_000.0, 1) + "k";
		}
		if (n < 999_950_000)
		{
			return fixed(n / 1_000_000.0, 1) + "M";
		}
		if (n < 9_999_500_000L)
		{
			return fixed(n / 1_000_000_000.0, 3) + "B";
		}
		return fixed(n / 1_000_000_000.0, 2) + "B";
	}

	/**
	 * A round figure, a milestone or a target, by its name: "1M", "750M", "1B", "1.5B". Only these
	 * drop the zeros after the point; any other figure is written by {@link #amount}.
	 */
	public static String mark(long n)
	{
		if (n < 10_000)
		{
			return String.format(Locale.ENGLISH, "%,d", n);
		}
		if (n < 1_000_000)
		{
			return trim(n / 1_000.0, 1) + "k";
		}
		if (n < 1_000_000_000)
		{
			return trim(n / 1_000_000.0, 2) + "M";
		}
		return trim(n / 1_000_000_000.0, 3) + "B";
	}

	/** Three figures always, for tight columns: "8,240", "41.2k", "412k", "4.90M", "26.0M", "628M", "1.53B". */
	public static String compact(long n)
	{
		if (n < 10_000)
		{
			return String.format(Locale.ENGLISH, "%,d", n);
		}
		String[] units = {"k", "M", "B"};
		double v = n / 1_000.0;
		for (int u = 0; ; u++)
		{
			int places = v < 9.995 ? 2 : v < 99.95 ? 1 : 0;
			// Past the last unit, or still under a thousand once rounded: write it in this unit.
			if (u == units.length - 1 || Math.round(v) < 1000)
			{
				return fixed(v, places) + units[u];
			}
			v /= 1_000.0;
		}
	}

	/** A change with its sign, in at most three figures: "+628M". */
	public static String signedCompact(long gp)
	{
		return gp > 0 ? "+" + compact(gp) : gp < 0 ? "-" + compact(-gp) : "0";
	}

	/** Coins in the short form, negative amounts with a minus: "812.4M", "-45.2k". */
	public static String coins(long gp)
	{
		return gp < 0 ? "-" + amount(-gp) : amount(gp);
	}

	/** A change with its sign: "+3.2M", "-412k", "0". */
	public static String signed(long gp)
	{
		return gp > 0 ? "+" + amount(gp) : gp < 0 ? "-" + amount(-gp) : "0";
	}

	/** A whole number written in full with thousands commas, whatever the computer's language: "1,240". */
	public static String whole(long n)
	{
		return String.format(Locale.ENGLISH, "%,d", n);
	}

	/** Hours to one decimal place, with a full stop whatever the computer's language: "1.5 hours". */
	public static String hours(double hours)
	{
		return String.format(Locale.ENGLISH, "%.1f", hours) + " hours";
	}

	/** A quantity change with its sign, written in full: "+1,240", "-6". */
	public static String count(long n)
	{
		return (n > 0 ? "+" : n < 0 ? "-" : "") + String.format(Locale.ENGLISH, "%,d", Math.abs(n));
	}

	/** A share as a whole percent, or "<1%". */
	public static String percent(double share)
	{
		if (share > 0 && share < 0.01)
		{
			return "<1%";
		}
		return Math.round(share * 100) + "%";
	}

	/**
	 * Each value's share of a whole as whole percents that add up as the values do: to 100% when the
	 * values make the whole, never past it. Rounding each share on its own can give 101%; here the
	 * points left over go to the shares that lost the most in rounding down. A share too small for a
	 * point of its own reads "<1%".
	 *
	 * @param whole what the shares are of; when the values add up to more, their sum is used instead
	 */
	public static String[] shares(long[] values, long whole)
	{
		int[] points = sharePoints(values, whole);
		String[] out = new String[values.length];
		for (int i = 0; i < values.length; i++)
		{
			out[i] = points[i] == 0 && values[i] > 0 ? "<1%" : points[i] + "%";
		}
		return out;
	}

	/** The whole percents {@link #shares} writes, as numbers. */
	public static int[] sharePoints(long[] values, long whole)
	{
		long sum = 0;
		for (long v : values)
		{
			sum += Math.max(0, v);
		}
		double denom = Math.max(whole, sum);
		int[] points = new int[values.length];
		if (denom <= 0)
		{
			return points;
		}
		double[] lost = new double[values.length];
		int given = 0;
		for (int i = 0; i < values.length; i++)
		{
			double exact = Math.max(0, values[i]) * 100 / denom;
			points[i] = (int) Math.floor(exact);
			lost[i] = exact - points[i];
			given += points[i];
		}
		int target = (int) Math.round(sum * 100 / denom);
		while (given < target)
		{
			int best = -1;
			for (int i = 0; i < values.length; i++)
			{
				if (values[i] > 0 && (best < 0 || lost[i] > lost[best]))
				{
					best = i;
				}
			}
			points[best]++;
			lost[best] = -1;
			given++;
		}
		return points;
	}

	/** A return as a signed percent: "+26%", "-3.4%", "+0.8%". */
	public static String signedPercent(double share)
	{
		double pct = share * 100;
		String text = Math.abs(pct) < 10 ? trim(Math.abs(pct), 1) : String.valueOf(Math.round(Math.abs(pct)));
		return (pct < 0 ? "-" : "+") + text + "%";
	}

	/** "1 item", "1,240 items". */
	public static String plural(long n, String word)
	{
		return String.format(Locale.ENGLISH, "%,d", n) + " " + word + (n == 1 ? "" : "s");
	}

	/** "Sat 14 Feb 2027", for a day further off than the panel's own dates. */
	public static String longDay(java.time.LocalDate date)
	{
		return LONG_DAY.format(date);
	}

	/** How long between two milestones: "the same day", "5 hours", "16 days", "3 months". */
	public static String gap(long ms)
	{
		long hours = Math.max(0, ms) / 3_600_000L;
		if (hours < 1)
		{
			return "under an hour";
		}
		if (hours < 24)
		{
			return plural(hours, "hour");
		}
		long days = hours / 24;
		if (days < 90)
		{
			return plural(days, "day");
		}
		return plural(days / 30, "month");
	}

	/** "just now", "12m ago", "3h ago", "2d ago". */
	public static String ago(long ms)
	{
		long minutes = Math.max(0, ms) / 60_000;
		if (minutes < 1)
		{
			return "just now";
		}
		if (minutes < 60)
		{
			return minutes + "m ago";
		}
		if (minutes < 48 * 60)
		{
			return minutes / 60 + "h ago";
		}
		return minutes / (24 * 60) + "d ago";
	}

	/** A figure to exactly that many places, with a full stop whatever the computer's language. */
	private static String fixed(double value, int places)
	{
		return String.format(Locale.ENGLISH, "%." + places + "f", value);
	}

	private static String trim(double value, int places)
	{
		String text = String.format(Locale.ENGLISH, "%." + places + "f", value);
		while (text.contains(".") && (text.endsWith("0") || text.endsWith(".")))
		{
			text = text.substring(0, text.length() - 1);
		}
		return text;
	}

	/** "2h 14m", "38m", "under a minute". */
	public static String duration(long ms)
	{
		long minutes = ms / 60_000;
		if (minutes < 1)
		{
			return "under a minute";
		}
		if (minutes < 60)
		{
			return minutes + "m";
		}
		return minutes / 60 + "h " + minutes % 60 + "m";
	}

	/** "14:02" in the player's own time. */
	public static String time(long epochMs)
	{
		return CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()));
	}

	/** "Thu 16 Oct". */
	public static String day(long epochMs)
	{
		return DAY.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()));
	}

	/** "16 Oct". */
	public static String shortDay(long epochMs)
	{
		return SHORT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()));
	}

	/** "Oct 26". */
	public static String month(long epochMs)
	{
		return MONTH.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()));
	}

	/** "Thu 16 Oct" for a day book date. */
	public static String day(java.time.LocalDate date)
	{
		return DAY.format(date);
	}

	/** "Thursday 16 October 2026". */
	public static String date(long epochMs)
	{
		return DATE.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()));
	}

	/** An item stack the way the game writes it: "950", "45K", "12M". */
	public static String stack(long n)
	{
		if (n < 100_000)
		{
			return String.valueOf(n);
		}
		if (n < 10_000_000)
		{
			return n / 1_000 + "K";
		}
		return n / 1_000_000 + "M";
	}

	/** The colour the game writes a stack of that size in. */
	public static java.awt.Color stackColor(long n)
	{
		return n < 100_000 ? Theme.QTY_YELLOW : n < 10_000_000 ? Theme.QTY_WHITE : Theme.QTY_GREEN;
	}
}
