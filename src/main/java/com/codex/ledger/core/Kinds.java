package com.codex.ledger.core;

import java.util.Locale;

/** Tells what a held item is from what the game says about it: its name, its menu and whether it is worn. */
public final class Kinds
{
	static final int PLATINUM = 13204;
	/** The equipment slot ammunition goes in. */
	static final int AMMO_SLOT = 13;

	private Kinds()
	{
	}

	/**
	 * @param actions the item's inventory menu, any entry of which may be null
	 * @param slot the equipment slot it is worn in, when equipable
	 * @param stackable whether it stacks in the inventory, as darts, knives and chinchompas do
	 */
	public static Kind of(int itemId, String name, String[] actions, boolean equipable, int slot, boolean stackable)
	{
		if (itemId == Ledger.COINS || itemId == PLATINUM)
		{
			return Kind.CASH;
		}
		if (equipable)
		{
			// Ammunition and thrown weapons are used up like supplies; everything else worn is gear.
			return slot == AMMO_SLOT || stackable ? Kind.SUPPLIES : Kind.GEAR;
		}
		if (actions != null)
		{
			for (String action : actions)
			{
				if ("Eat".equals(action) || "Drink".equals(action) || "Break".equals(action))
				{
					return Kind.SUPPLIES;
				}
			}
		}
		String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
		if (n.endsWith(" rune") || n.endsWith(" teleport") || n.contains("teleport scroll"))
		{
			return Kind.SUPPLIES;
		}
		return Kind.MATERIALS;
	}
}
