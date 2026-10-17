package com.codex.ledger.core;

/** Where the Ledger learns what an item is worth today. */
public interface Prices
{
	/** @return the item's price in coins, or 0 or less when it is not known */
	long price(int itemId);
}
