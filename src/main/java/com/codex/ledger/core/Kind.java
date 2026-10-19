package com.codex.ledger.core;

/**
 * What a held item is, so the worth can be told apart by kind: coins to spend, gear worn into
 * fights, supplies that get used up, and the materials and everything else waiting to be used or sold.
 */
public enum Kind
{
	/** Coins and platinum tokens. */
	CASH("Coins"),
	/** Anything worn, bar ammunition and thrown weapons. */
	GEAR("Gear"),
	/** Food, potions, runes, ammunition and teleports: what fights and trips use up. */
	SUPPLIES("Supplies"),
	/** Logs, ores, bones, herbs, seeds and the rest. */
	MATERIALS("Materials");

	private final String label;

	Kind(String label)
	{
		this.label = label;
	}

	public String getLabel()
	{
		return label;
	}
}
