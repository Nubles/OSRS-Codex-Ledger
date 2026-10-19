package com.codex.ledger.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Other Codex plugins (Annals for its year's worth, Gear Roadmap for its savings pace, Session
 * Chronicle for a session's net worth) read the saved ledger, read only: the top level "total",
 * "since" (when the bank was first seen, 0 before) and "history" as a list of {"t": epoch ms, "v":
 * worth}, oldest first. Renaming or reshaping either breaks them without a compile error, so the
 * shape is pinned here.
 */
public class SavedShapeTest
{
	@Test
	public void theWorthOtherPluginsReadKeepsItsShape()
	{
		Map<Integer, Long> price = new HashMap<>();
		price.put(995, 1L);
		price.put(4151, 1_500_000L);
		Prices prices = id -> price.getOrDefault(id, 0L);
		Ledger ledger = new Ledger();
		long t0 = 1_790_000_000_000L;
		ledger.observe(Store.BANK, items(995, 1_000_000L, 4151, 1L));
		ledger.settle(t0, prices, ZoneOffset.UTC);
		ledger.observe(Store.BANK, items(995, 2_000_000L, 4151, 1L));
		ledger.settle(t0 + 3_600_000L, prices, ZoneOffset.UTC);

		Gson gson = new GsonBuilder().create();
		JsonObject saved = new JsonParser().parse(gson.toJson(ledger)).getAsJsonObject();
		assertEquals(3_500_000L, saved.get("total").getAsLong());
		// Session Chronicle takes a worth as whole only once "since" says the bank was seen.
		assertEquals(t0, saved.get("since").getAsLong());
		JsonArray history = saved.getAsJsonArray("history");
		assertTrue(history.size() >= 2);
		long last = Long.MIN_VALUE;
		for (int i = 0; i < history.size(); i++)
		{
			JsonObject p = history.get(i).getAsJsonObject();
			assertTrue(p.get("t").getAsLong() >= last);
			last = p.get("t").getAsLong();
		}
		JsonObject newest = history.get(history.size() - 1).getAsJsonObject();
		assertEquals(t0 + 3_600_000L, newest.get("t").getAsLong());
		assertEquals(3_500_000L, newest.get("v").getAsLong());
	}

	private static Map<Integer, Long> items(long... pairs)
	{
		Map<Integer, Long> m = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			m.put((int) pairs[i], pairs[i + 1]);
		}
		return m;
	}
}
