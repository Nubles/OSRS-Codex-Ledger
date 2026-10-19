package com.codex.ledger.ui;

import com.codex.ledger.core.Forecast;
import com.codex.ledger.core.Ledger;
import com.codex.ledger.core.Milestones;
import com.codex.ledger.core.Prices;
import com.codex.ledger.core.Report;
import com.codex.ledger.core.Saver;
import com.codex.ledger.core.Store;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The accounts at the ends: a maxed one with a bank of 1,500 kinds of item and four months of pages,
 * a save holding 10,000 points of history, and one brand new. Each must work, and what runs on the
 * client thread (settling a tick, saving, the copy the panel is sent, the plaque's figure) must stay
 * within a few milliseconds even for them. Times are the best of several runs, so a busy test machine
 * does not fail it, and printed for the record.
 */
public class ExtremeAccountTest
{
	private static final Gson GSON = new GsonBuilder().create();
	private static final ZoneId UTC = ZoneOffset.UTC;
	private static final long T0 = 1_780_000_000_000L;
	private static final long DAY = 86_400_000L;
	/** What a tick's work may take on the client thread. */
	private static final double TICK_MS = 8;
	/** What the work done once a login (reading the save) or once a few minutes (saving) may take. */
	private static final double ONCE_MS = 40;
	private static final int ITEMS = 1_500;

	/** Every kind of item a price; ids from 1,000 so coins and the bond keep their meaning. */
	private static final Prices PRICES = id -> id == 995 ? 1L : 1_000L + (id * 7919L) % 2_000_000L;

	/** A maxed account: 1,500 kinds of item in the bank, played every day for the 120 days the book keeps. */
	static Ledger maxed()
	{
		Ledger ledger = new Ledger();
		for (int d = 0; d < 120; d++)
		{
			Map<Integer, Long> bank = new HashMap<>();
			bank.put(995, 2_000_000_000L + d * 3_000_000L);
			for (int i = 0; i < ITEMS; i++)
			{
				bank.put(1_000 + i, 1L + (i + d) % 50);
			}
			ledger.observe(Store.BANK, bank);
			for (int tick = 0; tick < 4; tick++)
			{
				Map<Integer, Long> inv = new HashMap<>();
				for (int i = 0; i < 28; i++)
				{
					inv.put(5_000 + (i + tick) % 40, 1L);
				}
				ledger.observe(Store.INVENTORY, inv);
				ledger.settle(T0 + d * DAY + tick * 900_000L, PRICES, UTC);
			}
		}
		return ledger;
	}

	@Test
	public void aMaxedAccountWithAHugeBankStaysQuickOnTheClientThread()
	{
		Ledger ledger = maxed();
		assertTrue(ledger.isReady());
		assertTrue(ledger.holdings().size() > ITEMS);
		long now = T0 + 120 * DAY;
		LocalDate today = Instant.ofEpochMilli(now).atZone(UTC).toLocalDate();
		int[] tick = {0};
		double settle = best(() ->
		{
			// A tick on which the inventory changed: the common case while playing.
			Map<Integer, Long> inv = new HashMap<>();
			inv.put(385, 1L + tick[0]++ % 28);
			ledger.observe(Store.INVENTORY, inv);
			ledger.settle(now + tick[0] * 600L, PRICES, UTC);
		});
		double revalue = best(() -> ledger.settle(now + 60_000L + tick[0]++ * 600L, PRICES, UTC));
		Saver saver = new Saver(200_000);
		String[] saved = new String[1];
		double save = best(() -> saved[0] = saver.save(ledger, GSON::toJson));
		assertTrue(saved[0].length() <= 220_000);
		double copy = best(() -> Ledger.read(GSON, GSON.toJson(ledger)));
		double plaque = best(() -> Report.of(ledger, 1, today, 0));
		double read = best(() -> assertNotNull(Ledger.read(GSON, saved[0])));
		print("maxed, " + ledger.holdings().size() + " kinds held", settle, revalue, save, copy, plaque, read);
		assertTrue("settle " + settle, settle < TICK_MS);
		assertTrue("revalue " + revalue, revalue < TICK_MS);
		assertTrue("plaque " + plaque, plaque < TICK_MS);
		assertTrue("save " + save, save < ONCE_MS);
		assertTrue("copy " + copy, copy < ONCE_MS);
		assertTrue("read " + read, read < ONCE_MS);
		// And the panel draws every tab of it.
		DamagedSaveTest.use(Ledger.read(GSON, saved[0]));
	}

	@Test
	public void aSaveWithTenThousandPointsOfHistoryReadsAndShrinks()
	{
		Ledger base = maxed();
		JsonObject tree = new JsonParser().parse(GSON.toJson(base)).getAsJsonObject();
		JsonArray history = new JsonArray();
		long start = T0 - 400 * DAY;
		for (int i = 0; i < 12_000; i++)
		{
			JsonObject p = new JsonObject();
			p.addProperty("t", start + i * 3_000_000L);
			p.addProperty("v", 1_000_000_000L + i * 100_000L);
			history.add(p);
		}
		tree.add("history", history);
		String json = tree.toString();
		Ledger[] read = new Ledger[1];
		double reading = best(() -> read[0] = Ledger.read(GSON, json));
		// Read back thinned as this version keeps it, so no copy or save after carries all 12,000.
		assertTrue(read[0].getHistory().size() <= 1_200);
		assertEquals(1_000_000_000L + 11_999 * 100_000L, read[0].getHistory().get(read[0].getHistory().size() - 1).getValue());
		long now = start + 12_000 * 3_000_000L;
		Map<Integer, Long> inv = new HashMap<>();
		inv.put(385, 5L);
		read[0].observe(Store.INVENTORY, inv);
		double first = once(() -> read[0].settle(now, PRICES, UTC));
		assertTrue(read[0].getHistory().size() <= 1_200);
		double later = best(() -> read[0].settle(now + 600L, PRICES, UTC));
		double marks = best(() -> Milestones.of(read[0].getHistory(), read[0].getTotal(), now));
		double pace = best(() -> Forecast.of(read[0], Instant.ofEpochMilli(now).atZone(UTC).toLocalDate(), 0));
		print("12,000 points", reading, first, later, marks, pace);
		// Half a megabyte, four times what this version ever saves: read once, at login.
		assertTrue("read " + reading, reading < 2.5 * ONCE_MS);
		assertTrue("first settle " + first, first < ONCE_MS);
		assertTrue("settle " + later, later < TICK_MS);
		assertTrue("milestones " + marks, marks < TICK_MS);
		DamagedSaveTest.use(read[0]);
	}

	@Test
	public void aBrandNewAccountWaitsForItsBank()
	{
		Ledger ledger = Ledger.read(GSON, GSON.toJson(new Ledger()));
		assertNotNull(ledger);
		assertFalse(ledger.isReady());
		assertEquals(0, ledger.getTotal());
		// Nothing seen, nothing to say: no milestones, a pace not yet known, an empty report.
		long now = T0;
		LocalDate today = Instant.ofEpochMilli(now).atZone(UTC).toLocalDate();
		assertTrue(Milestones.of(ledger.getHistory(), 0, now).getPassed().isEmpty());
		assertEquals(0, Report.of(ledger, 1, today, 0).change());
		assertFalse(LedgerCard.canDraw(ledger));
		// A new player's first look at the inventory, still before the bank.
		Map<Integer, Long> inv = new HashMap<>();
		inv.put(995, 25L);
		ledger.observe(Store.INVENTORY, inv);
		ledger.settle(now, PRICES, UTC);
		assertFalse(ledger.isReady());
		DamagedSaveTest.use(ledger);
	}

	/** The best of a few runs of a piece of work, in milliseconds, after warming it up. */
	private static double best(Runnable work)
	{
		for (int i = 0; i < 5; i++)
		{
			work.run();
		}
		double best = Double.MAX_VALUE;
		for (int i = 0; i < 7; i++)
		{
			best = Math.min(best, once(work));
		}
		return best;
	}

	private static double once(Runnable work)
	{
		long t = System.nanoTime();
		work.run();
		return (System.nanoTime() - t) / 1e6;
	}

	private static void print(String what, double... ms)
	{
		StringBuilder line = new StringBuilder(what).append(":");
		for (double m : ms)
		{
			line.append(String.format(Locale.ENGLISH, " %.2f ms", m));
		}
		System.out.println(line);
	}
}
