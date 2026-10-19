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

	/** "8,240", "41.2k", "412k", "1.24M". */
	public static String amount(long n)
	{
		if (n < 10_000)
		{
			return String.format(Locale.ENGLISH, "%,d", n);
		}
		if (n < 100_000)
		{
			return trim(n / 1_000.0, 1) + "k";
		}
		if (n < 1_000_000)
		{
			return Math.round(n / 1_000.0) + "k";
		}
		if (n < 10_000_000)
		{
			return trim(n / 1_000_000.0, 2) + "M";
		}
		if (n < 1_000_000_000)
		{
			return trim(n / 1_000_000.0, 1) + "M";
		}
		if (n < 10_000_000_000L)
		{
			return trim(n / 1_000_000_000.0, 3) + "B";
		}
		return trim(n / 1_000_000_000.0, 2) + "B";
	}

	/** At most three figures, for tight columns: "8,240", "41.2k", "628M", "1.53B". */
	public static String compact(long n)
	{
		if (n < 10_000)
		{
			return String.format(Locale.ENGLISH, "%,d", n);
		}
		if (n < 1_000_000)
		{
			return n < 100_000 ? trim(n / 1_000.0, 1) + "k" : Math.round(n / 1_000.0) + "k";
		}
		if (n < 1_000_000_000)
		{
			return n < 10_000_000 ? trim(n / 1_000_000.0, 2) + "M" : n < 100_000_000 ? trim(n / 1_000_000.0, 1) + "M" : Math.round(n / 1_000_000.0) + "M";
		}
		return n < 10_000_000_000L ? trim(n / 1_000_000_000.0, 2) + "B" : trim(n / 1_000_000_000.0, 1) + "B";
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
