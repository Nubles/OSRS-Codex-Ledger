package com.codex.ledger.ui;

import com.codex.ledger.core.Earnings;
import com.codex.ledger.core.Forecast;
import com.codex.ledger.core.Ledger;
import com.codex.ledger.core.Milestones;
import com.codex.ledger.core.Months;
import com.codex.ledger.core.Offer;
import com.codex.ledger.core.Prices;
import com.codex.ledger.core.Report;
import com.codex.ledger.core.Saver;
import com.codex.ledger.core.Store;
import com.codex.ledger.core.Trades;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

/**
 * The saved ledger ({@code codexledger/ledger}) is read back at every login, so it must survive
 * whatever a crash, a hand edit or a future version leaves in it: empty, cut short, the wrong type,
 * numbers too big or below zero, fields this version does not know, or plain garbage. None of it may
 * throw, freeze the panel or the card, or cost the parts of the ledger that are still good.
 */
public class DamagedSaveTest
{
	private static final Gson GSON = new GsonBuilder().create();
	private static final JsonParser PARSER = new JsonParser();
	private static final ZoneId UTC = ZoneOffset.UTC;
	private static final long T0 = 1_790_000_000_000L;
	private static final long DAY = 86_400_000L;
	private static final long NOW = T0 + 20 * DAY;
	private static final LedgerPanel panel = new LedgerPanel((id, cb) -> null);
	private static final Prices PRICES = id -> id == 995 ? 1L : id == 4151 ? 1_500_000L : id == 385 ? 900L : 0L;

	/** Values of every wrong kind, put in place of a good one. */
	private static final String[] BAD = {
		"null", "\"\"", "\"garbage\"", "true", "0", "-1", "-9223372036854775808", "9223372036854775807",
		"1e30", "1.5", "[]", "[1,2,3]", "{}", "{\"x\":1}", "[null]", "[{}]", "{\"BANK\":null}",
	};

	/** A ledger that saw its bank three weeks ago and has a page a day, trades and an open offer since. */
	private static Ledger good()
	{
		Ledger ledger = new Ledger();
		for (int d = 0; d <= 20; d++)
		{
			ledger.observe(Store.BANK, items(995, 400_000_000L + d * 9_000_000L, 4151, 2 + d % 3));
			ledger.observe(Store.INVENTORY, items(385, 20 - d % 5));
			ledger.offer(0, d == 20 ? new Offer(4151, true, 3, 1_500_000, 1, 1_500_000, true) : null);
			ledger.settle(T0 + d * DAY, PRICES, UTC);
		}
		return ledger;
	}

	@Test
	public void wholeSavesThatCannotBeReadStartAfresh()
	{
		String good = GSON.toJson(good());
		List<String> saves = new ArrayList<>();
		Collections.addAll(saves, null, "", " ", "null", "{", "}", "[]", "[{}]", "42", "\"ledger\"", "true", "garbage{{",
			"{\"total\":", "\u0000\u0001", "{\"total\":1}{\"total\":2}");
		for (int cut : new int[]{1, 10, good.length() / 3, good.length() / 2, good.length() - 2})
		{
			saves.add(good.substring(0, cut));
		}
		for (String save : saves)
		{
			survives(save);
		}
	}

	@Test
	public void anyOneFieldGoneWrongCostsOnlyThatField()
	{
		Ledger before = good();
		JsonObject good = tree(GSON.toJson(before));
		int points = before.getHistory().size();
		for (String key : new ArrayList<>(good.keySet()))
		{
			for (String bad : BAD)
			{
				JsonObject damaged = good.deepCopy();
				damaged.add(key, PARSER.parse(bad));
				Ledger read = survives(damaged.toString());
				if (!key.equals("history") && !key.equals("since") && !key.equals("total"))
				{
					// The worth over time is what the player would miss most; a broken other field must not take it.
					assertNotNull(key + " = " + bad, read);
					assertEquals(key + " = " + bad, points, read.getHistory().size());
				}
				if (!key.equals("days") && read != null)
				{
					assertTrue(key + " = " + bad, read.getDays().size() >= before.getDays().size() - 1);
				}
			}
		}
	}

	@Test
	public void aBrokenEntryCostsOnlyThatEntry()
	{
		Ledger before = good();
		JsonObject good = tree(GSON.toJson(before));
		int points = before.getHistory().size();
		int days = before.getDays().size();
		for (String bad : BAD)
		{
			JsonObject damaged = good.deepCopy();
			damaged.getAsJsonArray("history").set(1, PARSER.parse(bad));
			Ledger read = survives(damaged.toString());
			assertNotNull(bad, read);
			assertTrue("history[1] = " + bad, read.getHistory().size() >= points - 1);

			damaged = good.deepCopy();
			damaged.getAsJsonArray("days").set(2, PARSER.parse(bad));
			read = survives(damaged.toString());
			assertNotNull(bad, read);
			assertTrue("days[2] = " + bad, read.getDays().size() >= days - 1);
			assertEquals("days[2] = " + bad, points, read.getHistory().size());

			for (String field : new String[]{"items", "deals", "trades", "kinds", "moved", "date", "open", "ms"})
			{
				damaged = good.deepCopy();
				damaged.getAsJsonArray("days").get(3).getAsJsonObject().add(field, PARSER.parse(bad));
				read = survives(damaged.toString());
				assertNotNull(field + " = " + bad, read);
				assertTrue(field + " = " + bad, read.getDays().size() >= days - 1);
			}

			damaged = good.deepCopy();
			damaged.getAsJsonObject("stores").getAsJsonObject("BANK").add("items", PARSER.parse(bad));
			read = survives(damaged.toString());
			assertNotNull(bad, read);
			assertEquals(points, read.getHistory().size());

			damaged = good.deepCopy();
			damaged.getAsJsonObject("prices").add("4151", PARSER.parse(bad));
			damaged.getAsJsonObject("prices").add("not a number", PARSER.parse(bad));
			read = survives(damaged.toString());
			assertNotNull(bad, read);
			assertEquals(points, read.getHistory().size());

			damaged = good.deepCopy();
			damaged.getAsJsonObject("exchange").add("0", PARSER.parse(bad));
			damaged.getAsJsonObject("book").add("4151", PARSER.parse(bad));
			read = survives(damaged.toString());
			assertNotNull(bad, read);
			assertEquals(points, read.getHistory().size());
		}
	}

	@Test
	public void numbersNoAccountCouldHaveAreShrugged()
	{
		JsonObject good = tree(GSON.toJson(good()));
		long[][] points = {
			{T0, Long.MAX_VALUE}, {T0, -5}, {-1, 100}, {0, 0}, {Long.MAX_VALUE, 1}, {NOW + 400 * DAY, 2_000_000_000L},
			{T0 + DAY, Long.MIN_VALUE}, {T0 - DAY, 3}, {Long.MIN_VALUE, Long.MAX_VALUE},
		};
		for (long[] p : points)
		{
			JsonObject damaged = good.deepCopy();
			JsonObject point = new JsonObject();
			point.addProperty("t", p[0]);
			point.addProperty("v", p[1]);
			damaged.getAsJsonArray("history").add(point);
			survives(damaged.toString());
		}
		for (String key : new String[]{"total", "since", "lostUntil"})
		{
			for (long n : new long[]{Long.MAX_VALUE, Long.MIN_VALUE, -1, NOW + 1000 * DAY})
			{
				JsonObject damaged = good.deepCopy();
				damaged.addProperty(key, n);
				survives(damaged.toString());
			}
		}
		for (String field : new String[]{"open", "close", "high", "low", "play", "market", "ex", "loot", "used", "ms", "dead"})
		{
			for (long n : new long[]{Long.MAX_VALUE, Long.MIN_VALUE, -1})
			{
				JsonObject damaged = good.deepCopy();
				for (JsonElement day : damaged.getAsJsonArray("days"))
				{
					day.getAsJsonObject().addProperty(field, n);
				}
				survives(damaged.toString());
			}
		}
		JsonObject damaged = good.deepCopy();
		damaged.getAsJsonObject("stores").getAsJsonObject("BANK").getAsJsonObject("items").addProperty("995", Long.MAX_VALUE);
		damaged.getAsJsonObject("stores").getAsJsonObject("BANK").getAsJsonObject("items").addProperty("4151", -40);
		damaged.getAsJsonObject("prices").addProperty("4151", Long.MAX_VALUE);
		survives(damaged.toString());
		// A date a later version might write, or none that parses.
		for (String date : new String[]{"2026-13-45", "yesterday", "", "+999999999-12-31"})
		{
			damaged = good.deepCopy();
			damaged.getAsJsonArray("days").get(0).getAsJsonObject().addProperty("date", date);
			survives(damaged.toString());
		}
	}

	@Test
	public void aLaterVersionsFieldsAndPlacesAreIgnored()
	{
		Ledger before = good();
		JsonObject damaged = tree(GSON.toJson(before));
		damaged.addProperty("version", 99);
		damaged.add("vaults", PARSER.parse("{\"a\":[1,2]}"));
		damaged.getAsJsonObject("stores").add("SAILING_HOLD", PARSER.parse("{\"seen\":1,\"items\":{\"995\":5}}"));
		damaged.getAsJsonObject("stores").add("FUTURE_VAULT", PARSER.parse("{\"seen\":1,\"items\":{\"995\":7}}"));
		damaged.getAsJsonArray("days").get(0).getAsJsonObject().addProperty("sailed", 4);
		Ledger read = survives(damaged.toString());
		assertNotNull(read);
		assertEquals(before.getHistory().size(), read.getHistory().size());
		assertEquals(before.value(Store.BANK), read.value(Store.BANK));
	}

	@Test
	public void whateverIsLeftOutIsSetAsideBeforeItIsWrittenOver()
	{
		Ledger before = good();
		String whole = GSON.toJson(before);
		assertFalse(Ledger.read(GSON, whole).isMended());
		// A broken day page is left out, and the next save writes over it: the read says so, so the plugin
		// keeps the save aside (it once did only when nothing at all could be read).
		JsonObject damaged = tree(whole);
		damaged.getAsJsonArray("days").set(0, PARSER.parse("\"garbage\""));
		Ledger read = Ledger.read(GSON, damaged.toString());
		assertNotNull(read);
		assertTrue(read.isMended());
		// A place only a later version knows is left out too, even when the rest reads whole.
		damaged = tree(whole);
		damaged.getAsJsonObject("stores").add("SAILING_HOLD", PARSER.parse("{\"seen\":1,\"items\":{\"995\":5}}"));
		assertTrue(Ledger.read(GSON, damaged.toString()).isMended());
		// So are a later version's fields, which read without an error and would be gone at the next save.
		damaged = tree(whole);
		damaged.addProperty("version", 9);
		assertTrue(Ledger.read(GSON, damaged.toString()).isMended());
		damaged = tree(whole);
		damaged.getAsJsonArray("days").get(0).getAsJsonObject().addProperty("sailed", 4);
		assertTrue(Ledger.read(GSON, damaged.toString()).isMended());
		// Every save the plugin itself writes, trimmed to its budget or not, reads as whole.
		assertFalse(Ledger.read(GSON, new Saver(200_000).save(before, GSON::toJson)).isMended());
		assertFalse(Ledger.read(GSON, GSON.toJson(new Ledger())).isMended());
		for (String fixture : new String[]{"ledger-seen.json", "ledger-unseen.json"})
		{
			try (java.io.InputStream in = Ledger.class.getResourceAsStream(fixture))
			{
				String saved = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				assertFalse(fixture, Ledger.read(GSON, saved).isMended());
			}
			catch (java.io.IOException e)
			{
				throw new AssertionError(e);
			}
		}
		// One copy is kept: the first, then only a save at least as full, never a stub over it.
		assertTrue(Ledger.setAside(null, whole));
		assertFalse(Ledger.setAside(whole, whole));
		assertFalse(Ledger.setAside(whole, "{\"total\":1"));
		assertTrue(Ledger.setAside("{\"total\":1", whole));
		assertFalse(Ledger.setAside(null, " "));
		StringBuilder huge = new StringBuilder();
		while (huge.length() <= Ledger.MAX_ASIDE)
		{
			huge.append(whole);
		}
		assertFalse(Ledger.setAside(null, huge.toString()));
	}

	/** Reads the save as the plugin does at login, then plays, saves, shows and shares it; fails on any exception. */
	private static Ledger survives(String save)
	{
		Ledger read;
		try
		{
			read = Ledger.read(GSON, save);
		}
		catch (RuntimeException e)
		{
			throw new AssertionError("reading " + clip(save) + " threw " + e, e);
		}
		try
		{
			// Played on a second reading, so what was read stays as it was read for the caller to check.
			Ledger played = read == null ? null : Ledger.read(GSON, save);
			use(played == null ? new Ledger() : played);
		}
		catch (RuntimeException | StackOverflowError e)
		{
			throw new AssertionError("using " + clip(save) + " threw " + e, e);
		}
		return read;
	}

	static void use(Ledger ledger)
	{
		LocalDate today = Instant.ofEpochMilli(NOW).atZone(UTC).toLocalDate();
		ledger.observe(Store.BANK, items(995, 500_000_000L, 4151, 3));
		ledger.settle(NOW, PRICES, UTC);
		Report.of(ledger, 1, today, 0);
		Report.of(ledger, 30, today, 10);
		Months.of(ledger.getDays(), today, 12);
		Milestones.of(ledger.getHistory(), ledger.getTotal(), NOW);
		Forecast.of(ledger, today, 2_000_000_000L);
		Trades.of(ledger, 30, today);
		String saved = new Saver(200_000).save(ledger, GSON::toJson);
		if (Ledger.read(GSON, saved) == null)
		{
			fail("a ledger saved after damage does not read back");
		}
		Map<Integer, String> names = new HashMap<>();
		names.put(995, "Coins");
		if (LedgerCard.canDraw(ledger))
		{
			LedgerCard.Statement s = LedgerCard.Statement.of(ledger, names, "Zezima", today, NOW, UTC, 2_000_000_000L, "Twisted bow", null);
			if (s != null)
			{
				LedgerCard.render(s, (id, cb) -> null, () -> { });
			}
		}
		// One panel for every save, as the plugin keeps one for every account.
		panel.setGoal(new LedgerPanel.Goal(2_000_000_000L, "Twisted bow"));
		panel.update(ledger, names, "Zezima", ledger.isReady() ? LedgerPanel.State.READY : LedgerPanel.State.WAITING_FOR_BANK, NOW);
		for (int tab = 3; tab >= 0; tab--)
		{
			panel.showTab(tab);
			paint(panel);
		}
		for (int range = 4; range >= 0; range--)
		{
			// The worth chart over each range; paint draws the one on show.
			panel.showRange(range);
		}
		paint(panel);
	}

	static void paint(Component c)
	{
		c.setSize(225, 1200);
		layout(c);
		BufferedImage img = new BufferedImage(225, 1200, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		c.paint(g);
		g.dispose();
	}

	private static void layout(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				layout(k);
			}
		}
	}

	private static JsonObject tree(String json)
	{
		return PARSER.parse(json).getAsJsonObject();
	}

	private static String clip(String s)
	{
		return s == null ? "null" : s.length() > 120 ? s.substring(0, 120) + "..." : s;
	}

	static Map<Integer, Long> items(long... pairs)
	{
		Map<Integer, Long> m = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			m.put((int) pairs[i], pairs[i + 1]);
		}
		return m;
	}
}
