package com.codex.ledger.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Quartermaster, Money Pilot, Bank Steward, Provisioner and Gear Roadmap read
 * {@code codexledger/pricesV1}, read only:
 * {@code {"version":1,"savedAt":ms,"days":[{"d":"YYYY-MM-DD","p":{"itemId":gp}}]}}, days oldest
 * first. Renaming or reshaping it breaks them without a compile error, so the shape is pinned
 * here, and {@code ledger-pricesV1.json} is the writer's exact output for readers to keep a byte
 * copy of. The test fails when that output changes; a reader's copy must then change with it.
 */
public class PricesV1ShapeTest
{
	static final int WHIP = 4151;
	static final int SHARK = 385;
	static final int SCALES = 12934;

	/** The memory the fixture holds: three days of a whip, sharks and Zulrah's scales. */
	static PriceMemory sample()
	{
		PriceMemory m = new PriceMemory();
		List<Integer> ranked = Arrays.asList(WHIP, SHARK, SCALES);
		m.record(LocalDate.of(2026, 11, 9), ranked, prices(1_520_000L, 640L, 172L));
		m.record(LocalDate.of(2026, 11, 10), ranked, prices(1_498_000L, 655L, 170L));
		m.record(LocalDate.of(2026, 11, 11), ranked, prices(1_505_500L, 661L, 168L));
		m.trim(LocalDate.of(2026, 11, 11), ranked);
		return m;
	}

	private static Map<Integer, Long> prices(long whip, long shark, long scales)
	{
		Map<Integer, Long> p = new HashMap<>();
		p.put(WHIP, whip);
		p.put(SHARK, shark);
		p.put(SCALES, scales);
		return p;
	}

	@Test
	public void theSavedPricesKeepTheirShape()
	{
		String json = sample().write(new Gson(), 1_794_400_000_000L);
		JsonObject root = new JsonParser().parse(json).getAsJsonObject();
		assertEquals(1, root.get("version").getAsInt());
		assertEquals(1_794_400_000_000L, root.get("savedAt").getAsLong());
		JsonArray days = root.getAsJsonArray("days");
		assertEquals(3, days.size());
		String last = "";
		for (JsonElement e : days)
		{
			JsonObject day = e.getAsJsonObject();
			String d = day.get("d").getAsString();
			assertTrue("days run oldest first", d.compareTo(last) > 0);
			last = d;
			JsonObject p = day.getAsJsonObject("p");
			for (Map.Entry<String, JsonElement> x : p.entrySet())
			{
				// Keys are item ids written as strings; values whole coins.
				Integer.parseInt(x.getKey());
				assertTrue(x.getValue().getAsJsonPrimitive().isNumber());
			}
		}
		JsonObject newest = days.get(2).getAsJsonObject();
		assertEquals("2026-11-11", newest.get("d").getAsString());
		assertEquals(1_505_500L, newest.getAsJsonObject("p").get("4151").getAsLong());
		assertEquals(168L, newest.getAsJsonObject("p").get("12934").getAsLong());
	}

	@Test
	public void theFixtureIsTheWritersExactOutput() throws IOException
	{
		String json = sample().write(new Gson(), 1_794_400_000_000L);
		assertEquals("ledger-pricesV1.json is what readers copy: regenerate it and tell them when this changes",
			resource("ledger-pricesV1.json"), json);
	}

	@Test
	public void theFixtureReadsBack() throws IOException
	{
		PriceMemory m = PriceMemory.read(new Gson(), resource("ledger-pricesV1.json"));
		assertNotNull(m);
		assertEquals(3, m.getDays().size());
		assertEquals(655L, m.price(SHARK, LocalDate.of(2026, 11, 10)));
		assertEquals(3, m.history(WHIP).size());
	}

	static String resource(String name) throws IOException
	{
		try (InputStream in = PricesV1ShapeTest.class.getResourceAsStream(name))
		{
			assertNotNull("missing test resource " + name, in);
			byte[] b = new byte[1 << 20];
			int n = 0;
			int r;
			while ((r = in.read(b, n, b.length - n)) > 0)
			{
				n += r;
			}
			return new String(b, 0, n, StandardCharsets.UTF_8).trim();
		}
	}
}
