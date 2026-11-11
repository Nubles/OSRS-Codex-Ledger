package com.codex.ledger.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The items some Codex plugin cares about, most important first, for the price memory: Gear
 * Roadmap's goal, Quartermaster's kits, what charged gear is filled with, then everything held
 * by value and what was traded. Coins and platinum tokens are left out, their price never moves.
 */
public final class Interest
{
	/** Codex Quartermaster's group and kits key, read and never written. */
	public static final String QUARTERMASTER = "codexquartermaster";
	public static final String KITS_KEY = "kitsV1";
	private static final int PLATINUM = 13204;
	/** Kit items read at most, so a damaged save cannot crowd out the rest. */
	static final int MAX_KIT_ITEMS = 300;

	private Interest()
	{
	}

	/**
	 * Every item any of Quartermaster's kits counts: the keys of each {@code kits[].needs[].items}
	 * object, in the order saved, for the player's own kits and the preset they chose. Anything
	 * absent or unreadable is no kit, never a throw.
	 */
	public static Set<Integer> kitItems(Gson gson, String kitsV1)
	{
		Set<Integer> out = new LinkedHashSet<>();
		if (kitsV1 == null || kitsV1.trim().isEmpty())
		{
			return out;
		}
		try
		{
			JsonElement root = gson.fromJson(kitsV1, JsonElement.class);
			if (root == null || !root.isJsonObject())
			{
				return out;
			}
			JsonElement active = root.getAsJsonObject().get("active");
			String chosen = active != null && active.isJsonPrimitive() ? active.getAsString() : null;
			for (JsonObject kit : objects(root.getAsJsonObject().get("kits")))
			{
				JsonElement preset = kit.get("preset");
				JsonElement kitId = kit.get("id");
				if (preset != null && preset.isJsonPrimitive() && preset.getAsBoolean()
					&& (chosen == null || kitId == null || !kitId.isJsonPrimitive() || !chosen.equals(kitId.getAsString())))
				{
					// A preset counts only once the player has chosen it.
					continue;
				}
				for (JsonObject need : objects(kit.get("needs")))
				{
					JsonElement items = need.get("items");
					if (items == null || !items.isJsonObject())
					{
						continue;
					}
					for (String key : items.getAsJsonObject().keySet())
					{
						try
						{
							int id = Integer.parseInt(key.trim());
							if (id > 0 && out.size() < MAX_KIT_ITEMS)
							{
								out.add(id);
							}
						}
						catch (NumberFormatException e)
						{
							// Not an item id: skipped.
						}
					}
				}
			}
		}
		catch (RuntimeException e)
		{
			out.clear();
		}
		return out;
	}

	private static List<JsonObject> objects(JsonElement array)
	{
		List<JsonObject> out = new ArrayList<>();
		if (array != null && array.isJsonArray())
		{
			for (JsonElement e : array.getAsJsonArray())
			{
				if (e != null && e.isJsonObject())
				{
					out.add(e.getAsJsonObject());
				}
			}
		}
		return out;
	}

	/**
	 * The items to remember prices for, most important first, each once.
	 *
	 * @param goals Gear Roadmap's goal pieces
	 * @param kits Quartermaster's kit items
	 * @param charged what charged gear is filled with (scales, darts, runes)
	 * @param held everything held, by item; ranked by its value at {@code prices}
	 * @param traded items traded on the exchange, most recent first
	 */
	public static List<Integer> rank(Collection<Integer> goals, Collection<Integer> kits, Collection<Integer> charged,
		Map<Integer, Long> held, Prices prices, Collection<Integer> traded)
	{
		Set<Integer> out = new LinkedHashSet<>();
		addAll(out, goals);
		addAll(out, kits);
		addAll(out, charged);
		List<Map.Entry<Integer, Long>> byValue = new ArrayList<>(held.entrySet());
		byValue.sort(Comparator.comparingLong((Map.Entry<Integer, Long> e) -> -value(e, prices)).thenComparing(Map.Entry::getKey));
		for (Map.Entry<Integer, Long> e : byValue)
		{
			add(out, e.getKey());
		}
		addAll(out, traded);
		return new ArrayList<>(out);
	}

	private static long value(Map.Entry<Integer, Long> e, Prices prices)
	{
		long q = e.getValue() == null ? 0 : e.getValue();
		long p = prices.price(e.getKey());
		return p <= 0 || q <= 0 ? 0 : q > Long.MAX_VALUE / p ? Long.MAX_VALUE : q * p;
	}

	private static void addAll(Set<Integer> out, Collection<Integer> ids)
	{
		if (ids != null)
		{
			for (Integer id : ids)
			{
				add(out, id);
			}
		}
	}

	private static void add(Set<Integer> out, Integer id)
	{
		if (id != null && id > 0 && id != Ledger.COINS && id != PLATINUM)
		{
			out.add(id);
		}
	}
}
