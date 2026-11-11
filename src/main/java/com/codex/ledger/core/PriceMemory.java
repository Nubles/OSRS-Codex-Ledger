package com.codex.ledger.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One price memory for the Codex suite: one price per item per day, for the items some Codex
 * plugin cares about, kept 90 days and saved per account as {@code codexledger/pricesV1}:
 *
 * <pre>
 * {"version":1, "savedAt":epochMs, "days":[{"d":"YYYY-MM-DD", "p":{"itemId":gp, ...}}, ...]}
 * </pre>
 *
 * Days run oldest first. It is built only from prices RuneLite already has (its item manager),
 * on the days the client runs, so a day the game was not played has no entry: readers take the
 * days list as it comes and never assume one entry a day.
 *
 * <p>The payload is kept under {@link #BUDGET} characters so every plugin can read it cheaply.
 * The newest day holds up to {@link #MAX_IDS} items. The 14 days before it are kept daily and
 * older days one a week (the last seen in each week), back 90 days, for as many of the items that
 * matter most (goals, kits, then what is held by value) as the budget allows.
 */
public final class PriceMemory
{
	/** RS-profile config key it is saved under in Ledger's group. */
	public static final String KEY = "pricesV1";
	public static final int VERSION = 1;
	/** Days of history kept. */
	public static final int DAYS = 90;
	/** Items priced on the newest day at most. */
	public static final int MAX_IDS = 600;
	/** Days before the newest kept one by one; older ones are kept one a week. */
	static final int DAILY_DAYS = 14;
	/** Characters the saved JSON may take. */
	public static final int BUDGET = 60_000;
	/** Characters a day costs besides its prices: {"d":"2026-11-11","p":{}}, */
	private static final int DAY_COST = 28;
	/** Characters the top level costs besides its days. */
	private static final int TOP_COST = 60;

	private int version = VERSION;
	private long savedAt;
	private List<Entry> days = new ArrayList<>();

	/** One day's prices. Saved as JSON: never rename a field. */
	public static final class Entry
	{
		private String d;
		private Map<Integer, Long> p;

		public Entry()
		{
		}

		Entry(String date, Map<Integer, Long> prices)
		{
			this.d = date;
			this.p = prices;
		}

		public String getDate()
		{
			return d;
		}

		public Map<Integer, Long> getPrices()
		{
			return p == null ? Collections.emptyMap() : Collections.unmodifiableMap(p);
		}
	}

	public long getSavedAt()
	{
		return savedAt;
	}

	/** The days kept, oldest first. */
	public List<Entry> getDays()
	{
		return Collections.unmodifiableList(days);
	}

	/**
	 * Sets a day's prices: the first {@link #MAX_IDS} items of {@code ranked} that have a price,
	 * replacing what that day held. The items are kept in id order, so the saved form does not
	 * churn when only the ranking moves.
	 *
	 * @param ranked item ids, most important first; ids without a price above 0 are skipped
	 * @return true when the day's prices changed
	 */
	public boolean record(LocalDate day, List<Integer> ranked, Map<Integer, Long> priced)
	{
		Map<Integer, Long> p = new TreeMap<>();
		for (Integer id : ranked)
		{
			if (p.size() >= MAX_IDS)
			{
				break;
			}
			Long price = id == null ? null : priced.get(id);
			if (price != null && price > 0 && id > 0)
			{
				p.put(id, price);
			}
		}
		String date = day.toString();
		for (int i = days.size() - 1; i >= 0; i--)
		{
			Entry e = days.get(i);
			if (date.equals(e.d))
			{
				if (p.equals(e.p))
				{
					return false;
				}
				days.set(i, new Entry(date, p));
				return true;
			}
		}
		if (p.isEmpty())
		{
			return false;
		}
		days.add(new Entry(date, p));
		days.sort((a, b) -> a.d.compareTo(b.d));
		return true;
	}

	/**
	 * Thins the memory to what is kept and saved: 90 days back from {@code today}, the 14 days
	 * before the newest kept daily and older ones a week apart, and older days cut to the most
	 * important items until the whole fits {@link #BUDGET}.
	 *
	 * @param ranked the items that matter most, first; older days keep only a head of this list
	 */
	public void trim(LocalDate today, List<Integer> ranked)
	{
		long oldest = today.toEpochDay() - (DAYS - 1);
		List<Entry> kept = new ArrayList<>();
		Map<Long, Integer> weekAt = new HashMap<>();
		Entry newest = null;
		for (Entry e : days)
		{
			LocalDate d = date(e);
			if (d == null || d.toEpochDay() < oldest || d.isAfter(today) || e.p == null || e.p.isEmpty())
			{
				continue;
			}
			newest = e;
		}
		if (newest == null)
		{
			days = new ArrayList<>();
			return;
		}
		long newestDay = date(newest).toEpochDay();
		for (Entry e : days)
		{
			LocalDate d = date(e);
			if (d == null || d.toEpochDay() < oldest || d.isAfter(today) || e.p == null || e.p.isEmpty())
			{
				continue;
			}
			long age = newestDay - d.toEpochDay();
			if (age > DAILY_DAYS)
			{
				// One a week: a later day of the same week takes the earlier one's place.
				long week = Math.floorDiv(d.toEpochDay(), 7);
				Integer at = weekAt.get(week);
				if (at != null)
				{
					kept.set(at, e);
					continue;
				}
				weekAt.put(week, kept.size());
			}
			kept.add(e);
		}
		kept.removeIf(e -> e == null);
		days = kept;
		fit(ranked);
	}

	/** Cuts every day but the newest to the head of the ranking that fits the budget. */
	private void fit(List<Integer> ranked)
	{
		if (size() <= BUDGET || days.isEmpty())
		{
			return;
		}
		Entry newest = days.get(days.size() - 1);
		int lo = 0;
		int hi = Math.min(MAX_IDS, ranked.size());
		// The newest day alone over budget cannot happen at 600 ids (at most about 12k), but is cut too if it does.
		while (lo < hi)
		{
			int mid = (lo + hi + 1) / 2;
			if (sizeWith(head(ranked, mid), newest) <= BUDGET)
			{
				lo = mid;
			}
			else
			{
				hi = mid - 1;
			}
		}
		Set<Integer> keep = head(ranked, lo);
		List<Entry> cut = new ArrayList<>();
		for (Entry e : days)
		{
			if (e == newest)
			{
				cut.add(e);
				continue;
			}
			Map<Integer, Long> p = new TreeMap<>();
			for (Map.Entry<Integer, Long> x : e.p.entrySet())
			{
				if (keep.contains(x.getKey()))
				{
					p.put(x.getKey(), x.getValue());
				}
			}
			if (!p.isEmpty())
			{
				cut.add(new Entry(e.d, p));
			}
		}
		days = cut;
	}

	private static Set<Integer> head(List<Integer> ranked, int n)
	{
		Set<Integer> out = new HashSet<>();
		for (int i = 0; i < n && i < ranked.size(); i++)
		{
			out.add(ranked.get(i));
		}
		return out;
	}

	/** The saved size if every day but the newest kept only these items. */
	private int sizeWith(Set<Integer> keep, Entry newest)
	{
		int size = TOP_COST;
		for (Entry e : days)
		{
			size += DAY_COST;
			for (Map.Entry<Integer, Long> x : e.p.entrySet())
			{
				if (e == newest || keep.contains(x.getKey()))
				{
					size += cost(x.getKey(), x.getValue());
				}
			}
		}
		return size;
	}

	/** The saved size, in characters, about to the character. */
	int size()
	{
		int size = TOP_COST;
		for (Entry e : days)
		{
			size += DAY_COST;
			for (Map.Entry<Integer, Long> x : e.p.entrySet())
			{
				size += cost(x.getKey(), x.getValue());
			}
		}
		return size;
	}

	/** "12934":172, */
	private static int cost(int id, long price)
	{
		return digits(id) + digits(price) + 4;
	}

	private static int digits(long n)
	{
		return Long.toString(n).length();
	}

	/** A price an item had on a day, or 0 when that day is not kept or did not price it. */
	public long price(int itemId, LocalDate day)
	{
		String d = day.toString();
		for (Entry e : days)
		{
			if (d.equals(e.d))
			{
				Long p = e.p == null ? null : e.p.get(itemId);
				return p == null ? 0 : p;
			}
		}
		return 0;
	}

	/** An item's kept prices by day, oldest first. */
	public Map<LocalDate, Long> history(int itemId)
	{
		Map<LocalDate, Long> out = new TreeMap<>();
		for (Entry e : days)
		{
			LocalDate d = date(e);
			Long p = e.p == null ? null : e.p.get(itemId);
			if (d != null && p != null && p > 0)
			{
				out.put(d, p);
			}
		}
		return out;
	}

	/** The saved form, stamped with when it was written. */
	public String write(Gson gson, long now)
	{
		savedAt = now;
		version = VERSION;
		return gson.toJson(this);
	}

	/**
	 * Reads a saved memory. Anything absent, damaged, or from a later version (a breaking change
	 * would come under a new key) reads as null, never as a throw; damaged days are dropped.
	 */
	public static PriceMemory read(Gson gson, String json)
	{
		if (json == null || json.trim().isEmpty())
		{
			return null;
		}
		try
		{
			JsonElement root = gson.fromJson(json, JsonElement.class);
			if (root == null || !root.isJsonObject())
			{
				return null;
			}
			JsonObject o = root.getAsJsonObject();
			JsonElement v = o.get("version");
			if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber() || v.getAsInt() != VERSION)
			{
				return null;
			}
			PriceMemory m = new PriceMemory();
			JsonElement at = o.get("savedAt");
			m.savedAt = at != null && at.isJsonPrimitive() && at.getAsJsonPrimitive().isNumber() ? at.getAsLong() : 0;
			JsonElement list = o.get("days");
			if (list == null || !list.isJsonArray())
			{
				return m;
			}
			for (JsonElement d : list.getAsJsonArray())
			{
				Entry e = entry(d);
				if (e != null)
				{
					m.days.add(e);
				}
			}
			m.days.sort((a, b) -> a.d.compareTo(b.d));
			return m;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	private static Entry entry(JsonElement d)
	{
		if (d == null || !d.isJsonObject())
		{
			return null;
		}
		JsonObject o = d.getAsJsonObject();
		JsonElement date = o.get("d");
		JsonElement prices = o.get("p");
		if (date == null || !date.isJsonPrimitive() || !date.getAsJsonPrimitive().isString() || prices == null || !prices.isJsonObject())
		{
			return null;
		}
		try
		{
			LocalDate.parse(date.getAsString());
		}
		catch (DateTimeParseException e)
		{
			return null;
		}
		Map<Integer, Long> p = new TreeMap<>();
		for (Map.Entry<String, JsonElement> x : prices.getAsJsonObject().entrySet())
		{
			try
			{
				int id = Integer.parseInt(x.getKey());
				JsonElement value = x.getValue();
				if (id > 0 && value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
				{
					long price = value.getAsLong();
					if (price > 0)
					{
						p.put(id, price);
					}
				}
			}
			catch (NumberFormatException e)
			{
				// One bad entry is skipped; the rest of the day stands.
			}
		}
		return p.isEmpty() ? null : new Entry(date.getAsString(), p);
	}

	private static LocalDate date(Entry e)
	{
		try
		{
			return e == null || e.d == null ? null : LocalDate.parse(e.d);
		}
		catch (DateTimeParseException x)
		{
			return null;
		}
	}
}
