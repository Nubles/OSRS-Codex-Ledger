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
	/** Items a page may hold while its day is still being written. */
	static final int OPEN_ITEMS = 600;

	private String date;
	private long open;
	private long close;
	private long high;
	private long low;
	private long play;
	private long market;
	private Map<Integer, Flow> items = new HashMap<>();

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

	void flow(int itemId, long quantity, long value, long drift)
	{
		if (value == 0 && drift == 0)
		{
			return;
		}
		items.computeIfAbsent(itemId, k -> new Flow()).add(quantity, value, drift);
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
