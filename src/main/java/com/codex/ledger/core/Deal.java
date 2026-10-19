package com.codex.ledger.core;

/**
 * What the player did with one item on the Grand Exchange over a stretch of time: how many were
 * bought and for how much, how many were sold and for how much after tax, and the profit the
 * sales made over what those same items had cost.
 *
 * <p>Sales are matched against the average cost of the items bought while the ledger was
 * watching. Items sold that were never bought on the ledger's watch (loot, or bought before it
 * began) have no cost to match, so their sale is kept apart and makes no profit or loss.
 */
public final class Deal
{
	/** Bought: items and the coins paid. */
	private long bq;
	private long bc;
	/** Sold: items and the coins received after tax. */
	private long sq;
	private long sc;
	/** Tax the exchange kept from the sales. */
	private long tx;
	/** Profit on the sales that were matched to a cost. */
	private long p;
	/** Sold with no cost to match: items and the coins they brought. */
	private long uq;
	private long uc;

	public Deal()
	{
	}

	void bought(long quantity, long coins)
	{
		bq += quantity;
		bc += coins;
	}

	void sold(long quantity, long coins, long tax, long profit, long unmatched, long unmatchedCoins)
	{
		sq += quantity;
		sc += coins;
		tx += tax;
		p += profit;
		uq += unmatched;
		uc += unmatchedCoins;
	}

	void add(Deal other)
	{
		bought(other.bq, other.bc);
		sold(other.sq, other.sc, other.tx, other.p, other.uq, other.uc);
	}

	public long getBought()
	{
		return bq;
	}

	public long getBuyCoins()
	{
		return bc;
	}

	public long getSold()
	{
		return sq;
	}

	/** Coins the sales brought in, after tax. */
	public long getSaleCoins()
	{
		return sc;
	}

	public long getTax()
	{
		return tx;
	}

	/** Profit on the sales matched to what the items had cost, after tax. */
	public long getProfit()
	{
		return p;
	}

	/** Items sold with no cost to match, whose sale makes no profit or loss here. */
	public long getUnmatched()
	{
		return uq;
	}

	public long getUnmatchedCoins()
	{
		return uc;
	}

	/** Coins that changed hands either way. */
	public long volume()
	{
		return bc + sc + tx;
	}

	/** The profit against what the matched sales had cost, or 0 when nothing matched. */
	public double roi()
	{
		long matchedCoins = sc - uc;
		long cost = matchedCoins - p;
		return cost <= 0 ? 0 : p / (double) cost;
	}

	/** How much this deal mattered, for ranking and trimming. */
	long weight()
	{
		return Math.abs(p) * 4 + volume();
	}
}
