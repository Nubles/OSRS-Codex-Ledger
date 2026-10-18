package com.codex.ledger.core;

import java.util.HashMap;
import java.util.Map;

/**
 * One Grand Exchange slot as last seen, and what is sitting in it for the player to collect.
 *
 * <p>The game reports an offer's progress as running totals (bought so far, coins spent so far)
 * that do not drop when part of it is collected, so the ledger keeps what it has watched being
 * collected from a slot that is still trading and takes that off.
 */
public final class Offer
{
	/** Grand Exchange tax: 2% of each item's price, rounded down, at most 5M an item. */
	static final long TAX_PERCENT = 2;
	static final long TAX_CAP = 5_000_000L;
	/** Items sold for less than this pay no tax. */
	static final long TAX_FREE_BELOW = 50;

	private int item;
	private boolean buy;
	private int total;
	private long price;
	private int done;
	private long spent;
	private boolean active;
	/** Items already collected from a buy that is still trading. */
	private long taken;
	/** Coins already collected from a sale that is still trading. */
	private long takenCoins;

	public Offer()
	{
	}

	/**
	 * @param done items bought or sold so far
	 * @param spent coins paid so far for a buy, or received before tax for a sale
	 * @param active still trading, as opposed to finished or cancelled and waiting to be collected
	 */
	public Offer(int item, boolean buy, int total, long price, int done, long spent, boolean active)
	{
		this.item = item;
		this.buy = buy;
		this.total = total;
		this.price = price;
		this.done = done;
		this.spent = spent;
		this.active = active;
	}

	/** True when both are the same offer, perhaps at different points in its progress. */
	boolean same(Offer other)
	{
		return other != null && item == other.item && buy == other.buy && total == other.total && price == other.price;
	}

	/** Carries over what was already collected from the earlier sighting of the same offer. */
	void carry(Offer earlier)
	{
		taken = Math.min(earlier.taken, done);
		takenCoins = earlier.takenCoins;
	}

	/** The tax the exchange keeps from a sale of so many items for so many coins. */
	static long tax(int itemId, long sold, long gross)
	{
		if (sold <= 0 || gross <= 0 || itemId == Ledger.BOND)
		{
			return 0;
		}
		long each = gross / sold;
		if (each < TAX_FREE_BELOW)
		{
			return 0;
		}
		return sold * Math.min(each * TAX_PERCENT / 100, TAX_CAP);
	}

	/** What is in the slot now: the items bought or still for sale, and the coins left over or received. */
	public Map<Integer, Long> contents()
	{
		Map<Integer, Long> items = new HashMap<>();
		long held;
		long coins;
		if (buy)
		{
			held = done - taken;
			coins = price * total - spent;
		}
		else
		{
			held = (long) total - done;
			coins = spent - tax(item, done, spent) - takenCoins;
		}
		if (held > 0)
		{
			items.put(item, held);
		}
		if (coins > 0)
		{
			items.merge(Ledger.COINS, coins, Long::sum);
		}
		return items;
	}

	/**
	 * Takes what was just collected into the inventory from a slot that is still trading: bought
	 * items from a buy, coins from a sale. The quantities taken are removed from {@code gains}.
	 *
	 * @return true when anything was taken
	 */
	boolean collect(Map<Integer, Long> gains)
	{
		if (!active)
		{
			return false;
		}
		int id = buy ? item : Ledger.COINS;
		long gain = gains.getOrDefault(id, 0L);
		long here = contents().getOrDefault(id, 0L);
		if (buy)
		{
			// The coins in a buy are still bidding and cannot be collected yet.
			here = done - taken;
		}
		long take = Math.min(gain, here);
		if (take <= 0)
		{
			return false;
		}
		if (buy)
		{
			taken += take;
		}
		else
		{
			takenCoins += take;
		}
		gains.put(id, gain - take);
		return true;
	}

	public int getItem()
	{
		return item;
	}

	public boolean isBuy()
	{
		return buy;
	}

	public boolean isActive()
	{
		return active;
	}

	public int getDone()
	{
		return done;
	}

	public int getTotal()
	{
		return total;
	}

	public long getPrice()
	{
		return price;
	}
}
