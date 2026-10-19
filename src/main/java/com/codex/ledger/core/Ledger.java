package com.codex.ledger.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

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
	static final int COINS = 995;
	static final int BOND = 13190;
	static final int LOOTING_BAG = 11941;
	static final int LOOTING_BAG_OPEN = 22586;
	/** Items whose trades are remembered at most; those no longer held and traded longest ago go first. */
	static final int BOOK = 300;
	/**
	 * How long after dying items leaving the account are lost to the death rather than used. The
	 * game takes them when the player is sent home, a few ticks after the death itself.
	 */
	static final long DYING_MS = 15_000;
	/** Pages whose deals by item are kept: the Trades page reaches back 30 days, older pages keep their day's totals. */
	static final int DEAL_DAYS = 30;
	/** How long after dying getting the same items back counts against that death. */
	static final long REGAIN_MS = 3_600_000L;

	private Map<Store, Holding> stores = new LinkedHashMap<>();
	private Map<Integer, Long> prices = new HashMap<>();
	private List<Point> history = new ArrayList<>();
	private List<Day> days = new ArrayList<>();
	private long total;
	private long since;
	/** Each Grand Exchange slot as last seen; missing from ledgers saved before slots were kept. */
	private Map<Integer, Offer> exchange = new HashMap<>();
	/** Each item's standing on the exchange since the ledger began watching trades. */
	private Map<Integer, Position> book = new HashMap<>();
	/**
	 * True once the slots have been seen whole while the ledger was open, so an offer seen for the
	 * first time after that was placed since and its progress is trading done since.
	 */
	private boolean slotsKnown;
	/** What the last death took, by item, while getting it back still counts against it. */
	private Map<Integer, Long> lost = new HashMap<>();
	private long lostUntil;

	private transient Map<Store, Map<Integer, Long>> pending;
	/** Slots seen since the last settle; a null offer is an empty slot. */
	private transient Map<Integer, Offer> pendingOffers;
	private transient boolean atExchange;
	private transient long dyingUntil;
	/** Set when the death took something at this settle; the death is then over and what goes after is used, not lost. */
	private transient boolean tookByDeath;
	/** Trades seen at the last settle of the exchange. */
	private transient List<Fill> fills;

	/** Part of an offer that traded between two sightings of its slot. */
	private static final class Fill
	{
		final int item;
		final boolean buy;
		final long quantity;
		/** Coins paid for a buy, or received before tax for a sale. */
		final long coins;
		final long tax;

		Fill(int item, boolean buy, long quantity, long coins, long tax)
		{
			this.item = item;
			this.buy = buy;
			this.quantity = quantity;
			this.coins = coins;
			this.tax = tax;
		}
	}

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

	/** Records what a Grand Exchange slot holds now, or null when it is empty; it counts at the next settle. */
	public void offer(int slot, Offer offer)
	{
		if (pendingOffers == null)
		{
			pendingOffers = new HashMap<>();
		}
		pendingOffers.put(slot, offer);
	}

	/**
	 * Whether the player has the Grand Exchange or a collection box open, where items arriving in
	 * the inventory may be collected from an offer that is still trading.
	 */
	public void setAtExchange(boolean atExchange)
	{
		this.atExchange = atExchange;
	}

	/**
	 * The player died: what leaves the account in the next moments is lost to the death, and what
	 * comes back from the gravestone or Death in the hour after is set against it.
	 */
	public void died(long now)
	{
		dyingUntil = now + DYING_MS;
		lost = new HashMap<>();
		lostUntil = now + REGAIN_MS;
	}

	public boolean hasPending()
	{
		return pending != null && !pending.isEmpty() || pendingOffers != null && !pendingOffers.isEmpty();
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
		if (pending == null)
		{
			pending = new EnumMap<>(Store.class);
		}
		Map<Integer, Long> collected = settleExchange(wasReady);
		emptyLostLootingBag();
		if (!pending.isEmpty())
		{
			collectToBank(collected);
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
		// What trading moved, by item, so it can be told apart from everything else that came and went.
		Map<Integer, Long> traded = new HashMap<>();
		if (day != null)
		{
			for (Fill f : fills)
			{
				traded.merge(f.item, f.buy ? f.quantity : -f.quantity, Long::sum);
				traded.merge(COINS, f.buy ? -f.coins : f.coins - f.tax, Long::sum);
			}
			ids.addAll(traded.keySet());
		}
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
			if (day != null)
			{
				long tq = traded.getOrDefault(id, 0L);
				day.flow(id, newQ - oldQ, value, moved, tq, tq * price);
				day.channel(Channel.EXCHANGE, tq * price);
				channel(day, id, newQ - oldQ - tq, price, now);
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
			changed |= book(day, after, now);
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
	 * Works out what the exchange holds from its slots, and what left it. An offer that is
	 * collected leaves its slot empty, and what was in it went to the inventory or the bank; one
	 * still trading may have part of it collected while the exchange is open. Progress on an
	 * offer (coins turning into items, items into coins) is not a collection.
	 *
	 * @return what was collected and did not reach the inventory
	 */
	private Map<Integer, Long> settleExchange(boolean ready)
	{
		boolean changed = false;
		Map<Integer, Long> collected = new HashMap<>();
		fills = new ArrayList<>();
		if (pendingOffers != null && !pendingOffers.isEmpty())
		{
			for (Map.Entry<Integer, Offer> e : pendingOffers.entrySet())
			{
				Offer old = exchange.get(e.getKey());
				Offer next = e.getValue();
				if (next != null && next.same(old))
				{
					fill(next, old);
					next.carry(old);
				}
				else
				{
					if (old != null)
					{
						// Collected, or collected elsewhere and the slot used again since.
						addAll(collected, old.contents());
					}
					if (next != null && slotsKnown)
					{
						// Placed since the slots were last seen; whatever it has done was traded since.
						fill(next, null);
					}
				}
				if (next == null)
				{
					exchange.remove(e.getKey());
				}
				else
				{
					exchange.put(e.getKey(), next);
				}
				changed = true;
			}
			pendingOffers.clear();
			slotsKnown |= ready;
		}

		Map<Integer, Long> gains = inventoryGains();
		for (Map.Entry<Integer, Long> e : collected.entrySet())
		{
			long found = Math.min(e.getValue(), gains.getOrDefault(e.getKey(), 0L));
			if (found > 0)
			{
				e.setValue(e.getValue() - found);
				gains.put(e.getKey(), gains.get(e.getKey()) - found);
			}
		}
		collected.values().removeIf(q -> q <= 0);
		if (atExchange && !gains.isEmpty())
		{
			for (Offer offer : exchange.values())
			{
				changed |= offer.collect(gains);
			}
		}
		if (changed)
		{
			Map<Integer, Long> held = new HashMap<>();
			for (Offer offer : exchange.values())
			{
				addAll(held, offer.contents());
			}
			pending.put(Store.GRAND_EXCHANGE, held);
		}
		return collected;
	}

	/** Notes what an offer traded since it was last seen, if anything. */
	private void fill(Offer next, Offer old)
	{
		long quantity = next.getDone() - (old == null ? 0 : old.getDone());
		if (quantity <= 0)
		{
			return;
		}
		long coins = next.getSpent() - (old == null ? 0 : old.getSpent());
		long tax = next.isBuy() ? 0 : next.tax() - (old == null ? 0 : old.tax());
		fills.add(new Fill(next.getItem(), next.isBuy(), quantity, Math.max(0, coins), Math.max(0, tax)));
	}

	/**
	 * Books what an item did to the day, other than by trading, to where it came from: loot or
	 * supplies, coins in or out, or a death.
	 *
	 * @param own the quantity gained or lost other than by trading
	 */
	private void channel(Day day, int id, long own, long price, long now)
	{
		if (own == 0 || price <= 0)
		{
			return;
		}
		if (own < 0 && now <= dyingUntil)
		{
			day.channel(Channel.DEATHS, own * price);
			lost.merge(id, -own, Long::sum);
			tookByDeath = true;
			return;
		}
		if (own > 0 && now <= lostUntil && lost.containsKey(id))
		{
			long back = Math.min(own, lost.get(id));
			day.channel(Channel.DEATHS, back * price);
			lost.put(id, lost.get(id) - back);
			lost.values().removeIf(q -> q <= 0);
			own -= back;
			if (own == 0)
			{
				return;
			}
		}
		if (id == COINS)
		{
			day.channel(own > 0 ? Channel.COINS_IN : Channel.COINS_OUT, own * price);
		}
		else
		{
			day.channel(own > 0 ? Channel.LOOT : Channel.USED, own * price);
		}
	}

	/**
	 * Books the trades of the last settle: each item's position on the exchange and the day's
	 * deals. Items bought that have since left the account other than by a sale leave the position.
	 *
	 * @return true when anything was traded
	 */
	private boolean book(Day day, Map<Integer, Long> held, long now)
	{
		if (now > lostUntil && !lost.isEmpty())
		{
			lost.clear();
		}
		if (tookByDeath)
		{
			tookByDeath = false;
			dyingUntil = 0;
		}
		for (Fill f : fills)
		{
			Position position = book.computeIfAbsent(f.item, k -> new Position());
			Deal deal = day.deal(f.item);
			if (f.buy)
			{
				position.bought(f.quantity, f.coins, now);
				deal.bought(f.quantity, f.coins);
				day.getTrades().bought(f.quantity, f.coins);
			}
			else
			{
				position.sold(f.quantity, f.coins - f.tax, f.tax, now, deal, day.getTrades());
			}
		}
		for (Map.Entry<Integer, Position> e : book.entrySet())
		{
			e.getValue().holdAtMost(held.getOrDefault(e.getKey(), 0L));
		}
		trimBook(BOOK);
		boolean traded = !fills.isEmpty();
		fills.clear();
		return traded;
	}

	/** Items the inventory gained since it was last seen, when it is being seen now. */
	private Map<Integer, Long> inventoryGains()
	{
		Map<Integer, Long> gains = new HashMap<>();
		Map<Integer, Long> now = pending.get(Store.INVENTORY);
		Holding then = stores.get(Store.INVENTORY);
		if (now == null || then == null)
		{
			return gains;
		}
		for (Map.Entry<Integer, Long> e : now.entrySet())
		{
			long gain = e.getValue() - then.getItems().getOrDefault(e.getKey(), 0L);
			if (gain > 0)
			{
				gains.put(e.getKey(), gain);
			}
		}
		return gains;
	}

	/**
	 * What was collected from the exchange and did not reach the inventory went to the bank, so it
	 * is put there now rather than counted lost until the bank is next opened. Opening the bank
	 * replaces this guess with what is really there.
	 */
	private void collectToBank(Map<Integer, Long> collected)
	{
		Holding bank = stores.get(Store.BANK);
		if (collected == null || collected.isEmpty() || bank == null || pending.containsKey(Store.BANK))
		{
			return;
		}
		Map<Integer, Long> banked = new HashMap<>(bank.getItems());
		addAll(banked, collected);
		stores.put(Store.BANK, new Holding(bank.getSeen(), banked));
	}

	/**
	 * A looting bag that is in neither the inventory nor the bank was lost with what was in it
	 * (it is only ever opened in the wilderness, where a death keeps nothing), so its contents go.
	 */
	private void emptyLostLootingBag()
	{
		Map<Integer, Long> inv = pending.get(Store.INVENTORY);
		Holding bag = stores.get(Store.LOOTING_BAG);
		if (inv == null || bag == null || bag.getItems().isEmpty() || pending.containsKey(Store.LOOTING_BAG)
			|| inv.containsKey(LOOTING_BAG) || inv.containsKey(LOOTING_BAG_OPEN))
		{
			return;
		}
		Map<Integer, Long> bank = pending.containsKey(Store.BANK) ? pending.get(Store.BANK)
			: stores.containsKey(Store.BANK) ? stores.get(Store.BANK).getItems() : Collections.emptyMap();
		if (bank.containsKey(LOOTING_BAG) || bank.containsKey(LOOTING_BAG_OPEN))
		{
			return;
		}
		pending.put(Store.LOOTING_BAG, new HashMap<>());
	}

	private static void addAll(Map<Integer, Long> into, Map<Integer, Long> items)
	{
		for (Map.Entry<Integer, Long> e : items.entrySet())
		{
			into.merge(e.getKey(), e.getValue(), Long::sum);
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
			last.trimDeals(Day.KEPT_DEALS);
		}
		Day day = new Day(date, total);
		days.add(day);
		if (days.size() > DEAL_DAYS)
		{
			Day old = days.get(days.size() - 1 - DEAL_DAYS);
			old.trimDeals(0);
			old.trim(Day.OLD_ITEMS);
		}
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
		return h == null ? 0 : value(h.getItems());
	}

	/** The Grand Exchange slots that hold an offer, by slot. */
	public Map<Integer, Offer> getOffers()
	{
		return Collections.unmodifiableMap(new java.util.TreeMap<>(exchange));
	}

	/** Each item's standing on the exchange since the ledger began watching trades. */
	public Map<Integer, Position> getBook()
	{
		return Collections.unmodifiableMap(book);
	}

	/** What a set of items is worth at the last settle's prices. */
	public long value(Map<Integer, Long> items)
	{
		long sum = 0;
		for (Map.Entry<Integer, Long> e : items.entrySet())
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

	/**
	 * Trims the ledger until its saved form fits the budget, giving up the least useful detail first:
	 * items on old pages, then deals on recent ones, then trades of items long gone, then fine worth
	 * history. What is held, the day totals and the worth curve's shape are never given up, so a
	 * ledger whose holdings alone pass the budget stays over it.
	 *
	 * @param size the saved size of the ledger as it stands, in characters
	 * @return the saved size after trimming
	 */
	public int fit(int budget, ToIntFunction<Ledger> size)
	{
		int now = size.applyAsInt(this);
		for (int step = 0; now > budget && step < SHRINK_STEPS; step++)
		{
			if (shrink(step))
			{
				now = size.applyAsInt(this);
			}
		}
		return now;
	}

	private static final int SHRINK_STEPS = 6;

	/** One step of trimming; true when it took anything away. */
	private boolean shrink(int step)
	{
		int before = detail();
		int closed = days.size() - 1;
		switch (step)
		{
			case 0:
				// Pages before the Trades page's reach keep their totals and nothing by item.
				for (int i = 0; i < closed - 7; i++)
				{
					days.get(i).trim(0);
					days.get(i).trimDeals(0);
				}
				break;
			case 1:
				for (int i = Math.max(0, closed - 7); i < closed; i++)
				{
					days.get(i).trim(Day.OLD_ITEMS);
					days.get(i).trimDeals(Day.KEPT_DEALS / 3);
				}
				break;
			case 2:
				trimBook(BOOK / 3);
				break;
			case 3:
				if (closed >= 0)
				{
					days.get(closed).trim(Day.KEPT_ITEMS * 2);
				}
				break;
			case 4:
				// Every other point of the worth history, keeping its first and newest.
				List<Point> thinned = new ArrayList<>();
				for (int i = 0; i < history.size(); i++)
				{
					if (i % 2 == 0 || i == history.size() - 1)
					{
						thinned.add(history.get(i));
					}
				}
				history = thinned;
				break;
			default:
				while (days.size() > DEAL_DAYS)
				{
					days.remove(0);
				}
				break;
		}
		return detail() != before;
	}

	/** A rough count of the trimmable detail, to tell whether a step took anything. */
	private int detail()
	{
		int n = history.size() + book.size() + days.size();
		for (Day d : days)
		{
			n += d.getItems().size() + d.getDeals().size();
		}
		return n;
	}

	/** Keeps the trades of at most so many items: those still held first, then the most recently traded. */
	private void trimBook(int keep)
	{
		if (book.size() <= keep)
		{
			return;
		}
		List<Map.Entry<Integer, Position>> old = new ArrayList<>(book.entrySet());
		old.sort(Comparator.comparing((Map.Entry<Integer, Position> e) -> e.getValue().getHeld() > 0)
			.thenComparingLong(e -> e.getValue().getTime()));
		for (int i = 0; i < old.size() - keep; i++)
		{
			book.remove(old.get(i).getKey());
		}
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
		if (exchange == null)
		{
			exchange = new HashMap<>();
		}
		exchange.values().removeIf(o -> o == null);
		if (book == null)
		{
			book = new HashMap<>();
		}
		book.values().removeIf(p -> p == null);
		if (lost == null)
		{
			lost = new HashMap<>();
		}
		return this;
	}
}
