package com.codex.ledger.core;

/**
 * Where a day's play came from: the gold made or lost, split by how it happened. Market (prices
 * moving on what was held) is not play and is kept apart.
 */
public enum Channel
{
	/** Bought and sold on the Grand Exchange, against the market price and after tax. */
	EXCHANGE("Exchange"),
	/** Items that came in from anywhere else. */
	LOOT("Loot"),
	/** Items that went out other than by a sale. */
	USED("Supplies"),
	/** Coins that came in from anywhere but the exchange. */
	COINS_IN("Coins in"),
	/** Coins that went out to anything but the exchange. */
	COINS_OUT("Coins out"),
	/** Lost on death, less what was got back. */
	DEATHS("Deaths");

	private final String label;

	Channel(String label)
	{
		this.label = label;
	}

	public String getLabel()
	{
		return label;
	}
}
