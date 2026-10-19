package com.codex.ledger.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One page of the day book: the worth the day opened and closed on, its high and low, and how
 * the change splits into what the player gained or spent (play) and what prices did (market).
 */
public final class Day
{
	/** Items kept on a page once its day is over. */
	static final int KEPT_ITEMS = 16;
	/** Items kept on a page older than the Trades page reaches, where only the biggest movers matter. */
	static final int OLD_ITEMS = 6;
	/** Items a page may hold while its day is still being written. */
	static final int OPEN_ITEMS = 300;
	/** Items traded on the exchange kept on a page once its day is over; the day's trade totals stay whole. */
	static final int KEPT_DEALS = 24;

	private String date;
	private long open;
	private long close;
	private long high;
	private long low;
	private long play;
	private long market;
	private Map<Integer, Flow> items = new HashMap<>();

	// Where the day's play came from. Together they make up the play figure; pages written before
	// they were kept read them as nothing. See Channel.
	/** Bought and sold on the exchange: what was paid or received against the market price, after tax. */
	private long ex;
	/** Items that came in from anywhere else: loot, gathering, shops, rewards. */
	private long loot;
	/** Items that went out other than by a sale: eaten, used up, alched, dropped. */
	private long used;
	/** Coins that came in from anywhere but the exchange: alchemy, shops, rewards. */
	private long cin;
	/** Coins that went out to anything but the exchange: shops, fees, upkeep. */
	private long cout;
	/** Lost on death, less what was got back from the gravestone or Death. */
	private long dead;

	/** The day's trades on the exchange, by item; trimmed to the biggest once the day is over. */
	private Map<Integer, Deal> deals = new HashMap<>();
	/** The whole day's trading, which trimming leaves alone. */
	private Deal trades = new Deal();
	/** Milliseconds logged in on this day; nothing on pages written before it was kept. */
	private long ms;
	/** Worth by kind when the page last settled, by {@link Kind} ordinal; null before kinds were kept. */
	private long[] kinds;
	/** Worth each kind gained or lost by its items coming and going, prices held, by {@link Kind} ordinal. */
	private long[] moved;

	public Day()
	{
	}

	Day(String date, long open)
	{
		this.date = date;
		this.open = open;
		this.close = open;
		this.high = open;
		this.low = open;
	}

	void flow(int itemId, long quantity, long value, long drift, long tradedQuantity, long tradedValue)
	{
		if (value == 0 && drift == 0 && tradedValue == 0)
		{
			return;
		}
		Flow flow = items.computeIfAbsent(itemId, k -> new Flow());
		flow.add(quantity, value, drift);
		flow.traded(tradedQuantity, tradedValue);
		if (items.size() > OPEN_ITEMS)
		{
			trim(OPEN_ITEMS / 2);
		}
	}

	void totals(long play, long market, long close)
	{
		this.play += play;
		this.market += market;
		this.close = close;
		high = Math.max(high, close);
		low = Math.min(low, close);
	}

	/** Books part of the day's play to where it came from. */
	void channel(Channel channel, long value)
	{
		switch (channel)
		{
			case EXCHANGE:
				ex += value;
				break;
			case LOOT:
				loot += value;
				break;
			case USED:
				used += value;
				break;
			case COINS_IN:
				cin += value;
				break;
			case COINS_OUT:
				cout += value;
				break;
			default:
				dead += value;
				break;
		}
	}

	/** The day's deal in an item, opened if this is its first trade of the day. */
	Deal deal(int itemId)
	{
		if (deals == null)
		{
			deals = new HashMap<>();
		}
		return deals.computeIfAbsent(itemId, k -> new Deal());
	}

	/** What the day's trading came to, every item together. */
	public Deal getTrades()
	{
		if (trades == null)
		{
			trades = new Deal();
		}
		return trades;
	}

	public Map<Integer, Deal> getDeals()
	{
		return deals == null ? new HashMap<>() : deals;
	}

	/** Part of the day's play by where it came from. */
	public long channel(Channel channel)
	{
		switch (channel)
		{
			case EXCHANGE:
				return ex;
			case LOOT:
				return loot;
			case USED:
				return used;
			case COINS_IN:
				return cin;
			case COINS_OUT:
				return cout;
			default:
				return dead;
		}
	}

	void played(long elapsed)
	{
		ms += elapsed;
	}

	/** Time logged in on this day, or 0 on pages written before it was kept. */
	public long getPlayedMs()
	{
		return ms;
	}

	void kinds(long[] byKind)
	{
		kinds = byKind;
	}

	void moved(long[] byKind)
	{
		if (moved == null || moved.length != byKind.length)
		{
			moved = new long[byKind.length];
		}
		for (int i = 0; i < byKind.length; i++)
		{
			moved[i] += byKind[i];
		}
	}

	/**
	 * What each kind gained or lost on this day from its items coming and going, never from prices moving,
	 * or null when it was not told apart.
	 */
	public long[] getMoved()
	{
		return moved == null || moved.length != Kind.values().length ? null : moved.clone();
	}

	/** Worth by kind when the day last settled, or null when it was not told apart. */
	public long[] getKinds()
	{
		return kinds == null || kinds.length != Kind.values().length ? null : kinds.clone();
	}

	/** Worth found in a place seen for the first time is opening balance, not a gain. */
	void found(long value)
	{
		open += value;
		high = Math.max(high, open);
		low = Math.min(low, open);
	}

	/** Keeps only the items that mattered most. */
	void trim(int keep)
	{
		if (items.size() <= keep)
		{
			return;
		}
		List<Map.Entry<Integer, Flow>> list = new ArrayList<>(items.entrySet());
		list.sort(Comparator.comparingLong((Map.Entry<Integer, Flow> e) -> e.getValue().weight()).reversed());
		Map<Integer, Flow> kept = new HashMap<>();
		for (int i = 0; i < keep; i++)
		{
			kept.put(list.get(i).getKey(), list.get(i).getValue());
		}
		items = kept;
	}

	/** Keeps only the items traded the most. The day's trade totals are kept whole. */
	void trimDeals(int keep)
	{
		if (deals == null || deals.size() <= keep)
		{
			return;
		}
		if (keep <= 0)
		{
			deals = new HashMap<>();
			return;
		}
		List<Map.Entry<Integer, Deal>> list = new ArrayList<>(deals.entrySet());
		list.sort(Comparator.comparingLong((Map.Entry<Integer, Deal> e) -> e.getValue().weight()).reversed());
		Map<Integer, Deal> kept = new HashMap<>();
		for (int i = 0; i < keep; i++)
		{
			kept.put(list.get(i).getKey(), list.get(i).getValue());
		}
		deals = kept;
	}

	public String getDate()
	{
		return date;
	}

	public long getOpen()
	{
		return open;
	}

	public long getClose()
	{
		return close;
	}

	public long getHigh()
	{
		return high;
	}

	public long getLow()
	{
		return low;
	}

	public long getPlay()
	{
		return play;
	}

	public long getMarket()
	{
		return market;
	}

	public long change()
	{
		return close - open;
	}

	public Map<Integer, Flow> getItems()
	{
		return items == null ? new HashMap<>() : items;
	}
}
