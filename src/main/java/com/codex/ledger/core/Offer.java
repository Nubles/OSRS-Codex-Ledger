package com.codex.ledger.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
	/**
	 * Items the exchange never taxes, whatever their price: bonds, and the everyday supplies, teleports
	 * and tools Jagex exempted to help newer players.
	 */
	static final Set<Integer> TAX_FREE = new HashSet<>(Arrays.asList(
		Ledger.BOND,
		// Energy potions (4) to (1).
		3008, 3010, 3012, 3014,
		// Bronze, iron and steel arrows and darts; mind runes.
		882, 884, 886, 806, 807, 808, 558,
		// Bass, bread, cake, cooked chicken, cooked meat, herring, lobster, mackerel, meat pie, pike,
		// salmon, shrimps, tuna.
		365, 2309, 1891, 2140, 2142, 347, 379, 355, 2327, 351, 329, 315, 361,
		// Ardougne, Camelot, Civitas illa fortis, Falador, Kourend castle, Lumbridge and Varrock
		// teleports, teleport to house, games necklace (8), ring of dueling (8).
		8011, 8010, 28824, 8009, 28790, 8008, 8007, 8013, 3853, 2552,
		// Chisel, gardening trowel, glassblowing pipe, hammer, needle, pestle and mortar, rake, saw,
		// secateurs, seed dibber, shears, spade, watering can.
		1755, 5325, 1785, 2347, 1733, 233, 5341, 8794, 5329, 5343, 1735, 952, 5331));

	private int item;
	private boolean buy;
	private int total;
	private long price;
	private int done;
	private long spent;
	private boolean active;
	/** Items already collected from the slot while it still holds the offer. */
	private long taken;
	/** Coins already collected from the slot while it still holds the offer. */
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
		taken = Math.min(earlier.taken, buy ? done : (long) total - done);
		takenCoins = earlier.takenCoins;
	}

	/** The tax the exchange keeps from a sale of so many items for so many coins. */
	static long tax(int itemId, long sold, long gross)
	{
		if (sold <= 0 || gross <= 0 || TAX_FREE.contains(itemId))
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
			coins = price * total - spent - takenCoins;
		}
		else
		{
			held = (long) total - done - taken;
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
	 * Takes what was just collected into the inventory from a slot that still holds its offer:
	 * bought items and the change from a buy (and, once it has stopped, the coins it never spent),
	 * coins from a sale (and, once it has stopped, the items it never sold). The quantities taken
	 * are removed from {@code gains}.
	 *
	 * @return true when anything was taken
	 */
	boolean collect(Map<Integer, Long> gains)
	{
		long items;
		long coins;
		if (buy)
		{
			items = done - taken;
			// While it is still bidding only the change on what was bought can be collected.
			coins = (active ? price * done : price * total) - spent - takenCoins;
		}
		else
		{
			items = active ? 0 : (long) total - done - taken;
			coins = spent - tax(item, done, spent) - takenCoins;
		}
		long tookItems = take(gains, item, items);
		taken += tookItems;
		long tookCoins = take(gains, Ledger.COINS, coins);
		takenCoins += tookCoins;
		return tookItems > 0 || tookCoins > 0;
	}

	private static long take(Map<Integer, Long> gains, int id, long available)
	{
		long gain = gains.getOrDefault(id, 0L);
		long take = Math.min(gain, available);
		if (take <= 0)
		{
			return 0;
		}
		gains.put(id, gain - take);
		return take;
	}

	/** Coins paid so far for a buy, or received before tax for a sale. */
	public long getSpent()
	{
		return spent;
	}

	/** The tax kept so far from a sale; nothing for a buy. */
	long tax()
	{
		return buy ? 0 : tax(item, done, spent);
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
