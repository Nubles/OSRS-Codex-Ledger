package com.codex.ledger.core;

/**
 * One item's standing on the Grand Exchange since the ledger began watching: the items bought
 * and not yet sold with what they cost, and every trade in it added up.
 */
public final class Position
{
	/** Items bought and not yet sold, used or lost. */
	private long q;
	/** What those items cost, in coins. */
	private long c;
	/** When the item was last traded. */
	private long t;
	private Deal life = new Deal();

	public Position()
	{
	}

	void bought(long quantity, long coins, long now)
	{
		q += quantity;
		c += coins;
		t = now;
		life().bought(quantity, coins);
	}

	/**
	 * Sells against the average cost of what is held.
	 *
	 * @param coins what the sale brought in after tax
	 * @param into the day's deals, which take the same sale
	 */
	void sold(long quantity, long coins, long tax, long now, Deal... into)
	{
		long matched = Math.min(quantity, q);
		long cost = share(c, matched, q);
		long matchedCoins = share(coins, matched, quantity);
		long profit = matchedCoins - cost;
		q -= matched;
		c -= cost;
		t = now;
		life().sold(quantity, coins, tax, profit, quantity - matched, coins - matchedCoins);
		for (Deal deal : into)
		{
			deal.sold(quantity, coins, tax, profit, quantity - matched, coins - matchedCoins);
		}
	}

	/**
	 * Items bought that have since left the account other than by a sale (eaten, used up, lost)
	 * are no longer held, so their cost goes with them.
	 */
	void holdAtMost(long held)
	{
		if (q > held)
		{
			long keep = Math.max(0, held);
			c = share(c, keep, q);
			q = keep;
		}
	}

	/** A part of an amount, rounded, without overflowing on large stacks. */
	static long share(long amount, long part, long whole)
	{
		if (whole <= 0 || part <= 0)
		{
			return 0;
		}
		if (part >= whole)
		{
			return amount;
		}
		return Math.round((double) amount * part / whole);
	}

	public long getHeld()
	{
		return q;
	}

	public long getCost()
	{
		return c;
	}

	/** What each held item cost on average, or 0. */
	public long averageCost()
	{
		return q <= 0 ? 0 : Math.round(c / (double) q);
	}

	public long getTime()
	{
		return t;
	}

	public Deal life()
	{
		if (life == null)
		{
			life = new Deal();
		}
		return life;
	}
}
