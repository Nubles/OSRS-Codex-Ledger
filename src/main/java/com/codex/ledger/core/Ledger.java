package com.codex.ledger.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An account's ledger: what it holds in each place, what each item was last worth, the worth
 * over time and a day book of where it came from and went.
 *
 * <p>Places are seen one at a time (the bank only while it is open), so the ledger remembers each
 * as last seen. Changes are gathered and settled together once a tick, so an item moving from the
 * bank to the inventory is a move, not a loss and a gain. Every settle splits the change in worth
 * into play (items gained or spent, at today's price) and market (prices moving on what was held).
 *
 * <p>The ledger begins when the bank is first seen; until then the total is only part of the story.
 */
public final class Ledger
{
	/** Pages kept in the day book. */
	public static final int DAYS = 120;

	private Map<Store, Holding> stores = new LinkedHashMap<>();
	private Map<Integer, Long> prices = new HashMap<>();
	private List<Point> history = new ArrayList<>();
	private List<Day> days = new ArrayList<>();
	private long total;
	private long since;

	private transient Map<Store, Map<Integer, Long>> pending;

	/** One place as last seen. */
	public static final class Holding
	{
		private long seen;
		private Map<Integer, Long> items;

		public Holding()
		{
		}

		Holding(long seen, Map<Integer, Long> items)
		{
			this.seen = seen;
			this.items = items;
		}

		public long getSeen()
		{
			return seen;
		}

		public Map<Integer, Long> getItems()
		{
			return items == null ? Collections.emptyMap() : items;
		}
	}

	/** The worth at a moment. */
	public static final class Point
	{
		private long t;
		private long v;

		public Point()
		{
		}

		public Point(long time, long value)
		{
			this.t = time;
			this.v = value;
		}

		public long getTime()
		{
			return t;
		}

		public long getValue()
		{
			return v;
		}
	}

	/** Records what a place holds now; it counts at the next settle. Quantities of zero or less are dropped. */
	public void observe(Store store, Map<Integer, Long> items)
	{
		Map<Integer, Long> copy = new HashMap<>();
		for (Map.Entry<Integer, Long> e : items.entrySet())
		{
			if (e.getValue() != null && e.getValue() > 0)
			{
				copy.merge(e.getKey(), e.getValue(), Long::sum);
			}
		}
		if (pending == null)
		{
			pending = new EnumMap<>(Store.class);
		}
		pending.put(store, copy);
	}

	public boolean hasPending()
	{
		return pending != null && !pending.isEmpty();
	}

	/**
	 * Counts everything observed since the last settle and revalues what is held at today's prices.
	 *
	 * @return true when the worth or the day book changed
	 */
	public boolean settle(long now, Prices market, ZoneId zone)
	{
		Map<Integer, Long> before = aggregate();
		Map<Integer, Long> found = new HashMap<>();
		boolean wasReady = isReady();
		if (pending != null)
		{
			collectToBank();
			for (Map.Entry<Store, Map<Integer, Long>> e : pending.entrySet())
			{
				if (!stores.containsKey(e.getKey()))
				{
					for (Map.Entry<Integer, Long> item : e.getValue().entrySet())
					{
						found.merge(item.getKey(), item.getValue(), Long::sum);
					}
				}
				stores.put(e.getKey(), new Holding(now, e.getValue()));
			}
			pending.clear();
		}
		for (Map.Entry<Integer, Long> e : found.entrySet())
		{
			before.merge(e.getKey(), e.getValue(), Long::sum);
		}
		Map<Integer, Long> after = aggregate();

		Set<Integer> ids = new HashSet<>(before.keySet());
		ids.addAll(after.keySet());
		boolean ready = isReady();
		boolean opening = ready && !wasReady;
		Day day = ready && !opening ? today(now, zone) : null;
		long play = 0;
		long drift = 0;
		long foundValue = 0;
		long worth = 0;
		for (int id : ids)
		{
			long oldQ = before.getOrDefault(id, 0L);
			long newQ = after.getOrDefault(id, 0L);
			Long known = prices.get(id);
			long price = market.price(id);
			if (price <= 0)
			{
				price = known != null ? known : 0;
			}
			long oldPrice = known != null ? known : price;
			long value = (newQ - oldQ) * price;
			long moved = oldQ * (price - oldPrice);
			worth += newQ * price;
			foundValue += found.getOrDefault(id, 0L) * oldPrice;
			play += value;
			drift += moved;
			if (day != null && (newQ != oldQ || moved != 0))
			{
				day.flow(id, newQ - oldQ, value, moved);
			}
			if (newQ > 0)
			{
				prices.put(id, price);
			}
			else
			{
				prices.remove(id);
			}
		}

		if (opening)
		{
			since = now;
			history.clear();
			days.clear();
			total = worth;
			today(now, zone);
			history.add(new Point(now, worth));
			return true;
		}
		boolean changed = worth != total || play != 0 || drift != 0;
		total = worth;
		if (day != null)
		{
			if (foundValue != 0)
			{
				day.found(foundValue);
			}
			day.totals(play, drift, worth);
			if (changed || history.isEmpty())
			{
				record(now, worth);
			}
		}
		return changed;
	}

	/**
	 * Items that leave the Grand Exchange without reaching the inventory were collected to the
	 * bank, so they are put there now rather than counted lost until the bank is next opened.
	 * Opening the bank replaces this guess with what is really there.
	 */
	private void collectToBank()
	{
		Map<Integer, Long> exchange = pending.get(Store.GRAND_EXCHANGE);
		Holding bank = stores.get(Store.BANK);
		Holding oldExchange = stores.get(Store.GRAND_EXCHANGE);
		if (exchange == null || bank == null || oldExchange == null || pending.containsKey(Store.BANK))
		{
			return;
		}
		Map<Integer, Long> invNow = pending.get(Store.INVENTORY);
		Holding invThen = stores.get(Store.INVENTORY);
		Map<Integer, Long> banked = null;
		for (Map.Entry<Integer, Long> e : oldExchange.getItems().entrySet())
		{
			long left = e.getValue() - exchange.getOrDefault(e.getKey(), 0L);
			if (left <= 0)
			{
				continue;
			}
			if (invNow != null)
			{
				long before = invThen == null ? 0 : invThen.getItems().getOrDefault(e.getKey(), 0L);
				left -= Math.max(0, invNow.getOrDefault(e.getKey(), 0L) - before);
			}
			if (left > 0)
			{
				if (banked == null)
				{
					banked = new HashMap<>(bank.getItems());
				}
				banked.merge(e.getKey(), left, Long::sum);
			}
		}
		if (banked != null)
		{
			stores.put(Store.BANK, new Holding(bank.getSeen(), banked));
		}
	}

	/** Today's page, opening a new one on the worth carried over when the date has turned. */
	private Day today(long now, ZoneId zone)
	{
		String date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString();
		Day last = days.isEmpty() ? null : days.get(days.size() - 1);
		if (last != null && date.equals(last.getDate()))
		{
			return last;
		}
		if (last != null)
		{
			last.trim(Day.KEPT_ITEMS);
		}
		Day day = new Day(date, total);
		days.add(day);
		while (days.size() > DAYS)
		{
			days.remove(0);
		}
		return day;
	}

	private void record(long now, long worth)
	{
		history.add(new Point(now, worth));
		history = History.compact(history, now);
	}

	private Map<Integer, Long> aggregate()
	{
		Map<Integer, Long> all = new HashMap<>();
		for (Holding h : stores.values())
		{
			for (Map.Entry<Integer, Long> e : h.getItems().entrySet())
			{
				all.merge(e.getKey(), e.getValue(), Long::sum);
			}
		}
		return all;
	}

	/** True once the bank has been seen, which is when the ledger begins. */
	public boolean isReady()
	{
		return stores.containsKey(Store.BANK);
	}

	public long getTotal()
	{
		return total;
	}

	public long getSince()
	{
		return since;
	}

	public Map<Store, Holding> getStores()
	{
		return Collections.unmodifiableMap(stores);
	}

	/** What a place is worth at the last settle's prices. */
	public long value(Store store)
	{
		Holding h = stores.get(store);
		if (h == null)
		{
			return 0;
		}
		long sum = 0;
		for (Map.Entry<Integer, Long> e : h.getItems().entrySet())
		{
			sum += e.getValue() * prices.getOrDefault(e.getKey(), 0L);
		}
		return sum;
	}

	public long price(int itemId)
	{
		return prices.getOrDefault(itemId, 0L);
	}

	/** Every item held, across every place, with its quantity. */
	public Map<Integer, Long> holdings()
	{
		return aggregate();
	}

	public List<Point> getHistory()
	{
		return Collections.unmodifiableList(history);
	}

	/** Pages of the day book, oldest first. */
	public List<Day> getDays()
	{
		return Collections.unmodifiableList(days);
	}

	/** The day book's page for a date, or null. */
	public Day day(LocalDate date)
	{
		String key = date.toString();
		for (int i = days.size() - 1; i >= 0; i--)
		{
			if (key.equals(days.get(i).getDate()))
			{
				return days.get(i);
			}
		}
		return null;
	}

	/** Mends a ledger read back from storage, where any part may be missing. */
	public Ledger repair()
	{
		if (stores == null)
		{
			stores = new LinkedHashMap<>();
		}
		stores.values().removeIf(h -> h == null);
		stores.keySet().removeIf(s -> s == null);
		if (prices == null)
		{
			prices = new HashMap<>();
		}
		if (history == null)
		{
			history = new ArrayList<>();
		}
		history.removeIf(p -> p == null);
		if (days == null)
		{
			days = new ArrayList<>();
		}
		days.removeIf(d -> d == null || d.getDate() == null);
		return this;
	}
}
