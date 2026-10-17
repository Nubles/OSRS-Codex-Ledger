package com.codex.ledger.core;

/** The places the Ledger counts wealth in. Each is seen on its own and remembered until seen again. */
public enum Store
{
	BANK("Bank"),
	INVENTORY("Inventory"),
	EQUIPMENT("Worn"),
	GRAND_EXCHANGE("Grand Exchange"),
	SEED_VAULT("Seed vault"),
	LOOTING_BAG("Looting bag");

	private final String label;

	Store(String label)
	{
		this.label = label;
	}

	public String getLabel()
	{
		return label;
	}
}
