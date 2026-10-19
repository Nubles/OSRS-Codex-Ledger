package com.codex.ledger.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Codex Bank Steward's saved copy of the bank, read (never written) so the ledger can say roughly
 * what the account is worth before it has seen the bank itself. It is only ever an estimate shown
 * while the ledger waits: nothing in it is booked, and the ledger still begins at the real bank.
 *
 * <p>The saved form is Bank Steward's {@code bankV1}:
 * {@code {"version":1,"savedAt":ms,"items":{"id":qty},"truncated":bool}}, ids already folded to
 * the item itself the way {@code ItemManager.canonicalize} folds them.
 */
public final class BankCopy
{
	public static final String GROUP = "codexbanksteward";
	public static final String KEY = "bankV1";
	/** Readers take no more kinds of item than Bank Steward ever writes. */
	static final int MAX_ITEMS = 2000;

	private final Map<Integer, Long> items;
	private final long savedAt;
	private final boolean truncated;

	BankCopy(Map<Integer, Long> items, long savedAt, boolean truncated)
	{
		this.items = Collections.unmodifiableMap(items);
		this.savedAt = savedAt;
		this.truncated = truncated;
	}

	/** Reads the saved copy, or null when there is none or it cannot be read. */
	public static BankCopy read(Gson gson, String json)
	{
		if (json == null || json.trim().isEmpty())
		{
			return null;
		}
		try
		{
			JsonElement root = gson.fromJson(json, JsonElement.class);
			if (root == null || !root.isJsonObject())
			{
				return null;
			}
			JsonObject o = root.getAsJsonObject();
			JsonElement list = o.get("items");
			if (list == null || !list.isJsonObject())
			{
				return null;
			}
			Map<Integer, Long> items = new HashMap<>();
			for (Map.Entry<String, JsonElement> e : list.getAsJsonObject().entrySet())
			{
				Integer id = id(e.getKey());
				Long qty = number(e.getValue());
				if (id != null && id > 0 && qty != null && qty > 0 && items.size() < MAX_ITEMS)
				{
					items.put(id, qty);
				}
			}
			Long savedAt = number(o.get("savedAt"));
			JsonElement cut = o.get("truncated");
			boolean truncated = cut != null && cut.isJsonPrimitive() && cut.getAsJsonPrimitive().isBoolean() && cut.getAsBoolean();
			return items.isEmpty() ? null : new BankCopy(items, savedAt == null ? 0 : savedAt, truncated);
		}
		catch (JsonParseException | IllegalStateException | UnsupportedOperationException | NumberFormatException e)
		{
			return null;
		}
	}

	private static Integer id(String key)
	{
		try
		{
			return Integer.parseInt(key.trim());
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private static Long number(JsonElement e)
	{
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
		{
			return null;
		}
		return e.getAsLong();
	}

	/** What the copy is worth at these prices; items with no price count as nothing, as in the ledger. */
	public long value(Prices prices)
	{
		long total = 0;
		for (Map.Entry<Integer, Long> e : items.entrySet())
		{
			long price = prices.price(e.getKey());
			if (price > 0)
			{
				total += price * e.getValue();
			}
		}
		return total;
	}

	public Map<Integer, Long> getItems()
	{
		return items;
	}

	/** When Bank Steward last saw the bank, epoch ms; 0 when it did not say. */
	public long getSavedAt()
	{
		return savedAt;
	}

	/** True when the bank held more kinds of item than Bank Steward keeps, so the copy is short. */
	public boolean isTruncated()
	{
		return truncated;
	}

	/** The estimate the panel shows while the ledger waits for the bank. */
	public static final class Estimate
	{
		private final long bank;
		private final long savedAt;
		private final int kinds;
		private final boolean truncated;

		public Estimate(long bank, long savedAt, int kinds, boolean truncated)
		{
			this.bank = bank;
			this.savedAt = savedAt;
			this.kinds = kinds;
			this.truncated = truncated;
		}

		/** The copied bank's worth at today's prices. */
		public long getBank()
		{
			return bank;
		}

		public long getSavedAt()
		{
			return savedAt;
		}

		/** Kinds of item in the copy. */
		public int getKinds()
		{
			return kinds;
		}

		public boolean isTruncated()
		{
			return truncated;
		}
	}

	/** The estimate at these prices. */
	public Estimate estimate(Prices prices)
	{
		return new Estimate(value(prices), savedAt, items.size(), truncated);
	}
}
