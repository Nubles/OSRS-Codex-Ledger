package com.codex.ledger.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.IntToLongFunction;

/**
 * What the account holds that is worth more cast into coins with High Level Alchemy than sold on
 * the Grand Exchange: per item, the high alchemy value less a nature rune, against the price after
 * the exchange's tax. Only items with a price are weighed (anything else cannot be sold to compare).
 */
public final class Alchemy
{
	public static final int COINS = 995;
	public static final int PLATINUM = 13204;
	public static final int NATURE_RUNE = 561;
	/** Lines shown at most. */
	public static final int SHOWN = 8;
	/** A line must make at least this much over selling to be worth the casts. */
	public static final long MIN_GAIN = 10_000;

	/** One item better alched than sold. */
	public static final class Line
	{
		private final int itemId;
		private final long quantity;
		private final long alch;
		private final long sale;

		Line(int itemId, long quantity, long alch, long sale)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.alch = alch;
			this.sale = sale;
		}

		public int getItemId()
		{
			return itemId;
		}

		public long getQuantity()
		{
			return quantity;
		}

		/** Coins one cast leaves, after its nature rune. */
		public long getAlch()
		{
			return alch;
		}

		/** Coins one sells for after tax. */
		public long getSale()
		{
			return sale;
		}

		/** How much more alching them all makes than selling them all. */
		public long gain()
		{
			return quantity * (alch - sale);
		}
	}

	private final List<Line> lines;
	private final long gain;
	private final long casts;

	private Alchemy(List<Line> lines, long gain, long casts)
	{
		this.lines = Collections.unmodifiableList(lines);
		this.gain = gain;
		this.casts = casts;
	}

	/**
	 * @param holdings everything held, by item id
	 * @param price the Grand Exchange price of one, 0 when it has none
	 * @param highAlch the high alchemy value of one, 0 when it cannot be alched
	 */
	public static Alchemy of(Map<Integer, Long> holdings, IntToLongFunction price, IntToLongFunction highAlch)
	{
		long rune = Math.max(0, price.applyAsLong(NATURE_RUNE));
		List<Line> all = new ArrayList<>();
		long gain = 0;
		long casts = 0;
		for (Map.Entry<Integer, Long> e : holdings.entrySet())
		{
			int id = e.getKey();
			long qty = e.getValue() == null ? 0 : e.getValue();
			if (qty <= 0 || id == COINS || id == PLATINUM || id == NATURE_RUNE)
			{
				continue;
			}
			long each = price.applyAsLong(id);
			if (each <= 0)
			{
				continue;
			}
			// Asked only for priced items: each first ask looks the item up in the game's cache.
			long ha = highAlch.applyAsLong(id);
			if (ha <= 0)
			{
				continue;
			}
			long sale = each - Offer.tax(id, 1, each);
			long alch = ha - rune;
			if (alch <= sale)
			{
				continue;
			}
			Line line = new Line(id, qty, alch, sale);
			if (line.gain() < MIN_GAIN)
			{
				continue;
			}
			all.add(line);
			gain += line.gain();
			casts += qty;
		}
		all.sort((a, b) -> Long.compare(b.gain(), a.gain()));
		return new Alchemy(all, gain, casts);
	}

	/** Every item better alched, most gained first. */
	public List<Line> getLines()
	{
		return lines;
	}

	/** How much more alching them all makes than selling. */
	public long getGain()
	{
		return gain;
	}

	/** Casts it takes to alch them all. */
	public long getCasts()
	{
		return casts;
	}

	public boolean isEmpty()
	{
		return lines.isEmpty();
	}
}
