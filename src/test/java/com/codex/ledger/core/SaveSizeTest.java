package com.codex.ledger.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The ledger is one value in the RuneLite profile, so a busy account must not let it grow without
 * end. This drives a heavy flipper and PvMer through 130 days: a 1,000-item bank, a churning
 * inventory and a trade every few minutes across thousands of different items.
 */
public class SaveSizeTest
{
	private static final Gson GSON = new Gson();
	private static String busyYear;

	@BeforeClass
	public static void drive()
	{
		busyYear = GSON.toJson(busy(130));
	}

	private static Ledger busyYear()
	{
		return GSON.fromJson(busyYear, Ledger.class).repair();
	}

	@Test
	public void aBusyYearStaysBounded()
	{
		Ledger ledger = busyYear();
		int natural = GSON.toJson(ledger).length();
		System.out.println("Busy ledger saves " + natural + " characters");
		assertTrue("saved " + natural, natural < 260_000);
		assertEquals(Ledger.DAYS, ledger.getDays().size());
		// Old pages keep their totals and only their biggest movers.
		assertTrue(ledger.getDays().get(0).getItems().size() <= Day.OLD_ITEMS);
		assertTrue(ledger.getDays().get(0).getDeals().isEmpty());
	}

	@Test
	public void fittingTrimsDetailButKeepsTheWorthOthersRead()
	{
		Ledger ledger = busyYear();
		long total = ledger.getTotal();
		int points = ledger.getHistory().size();
		int fitted = ledger.fit(120_000, l -> GSON.toJson(l).length());
		assertTrue("fitted " + fitted, fitted <= 120_000);
		assertEquals(fitted, GSON.toJson(ledger).length());
		JsonObject saved = new JsonParser().parse(GSON.toJson(ledger)).getAsJsonObject();
		assertEquals(total, saved.get("total").getAsLong());
		assertTrue(saved.getAsJsonArray("history").size() > 0);
		assertTrue(ledger.getHistory().size() <= points);
		// Day totals survive trimming.
		Day oldest = ledger.getDays().get(0);
		assertTrue(oldest.getDate() != null);

		// A ledger already inside its budget is left alone.
		Ledger small = busy(3);
		String before = GSON.toJson(small);
		small.fit(1_000_000, l -> GSON.toJson(l).length());
		assertEquals(before, GSON.toJson(small));
	}

	static Ledger busy(int days)
	{
		Random r = new Random(7);
		Prices prices = id -> 1_000L + id;
		Ledger ledger = new Ledger();
		long t = 1_790_000_000_000L;
		Map<Integer, Long> bank = new HashMap<>();
		for (int i = 0; i < 1_000; i++)
		{
			bank.put(2_000 + i * 3, 1L + r.nextInt(5_000));
		}
		ledger.observe(Store.BANK, bank);
		ledger.settle(t, prices, ZoneOffset.UTC);
		for (int day = 0; day < days; day++)
		{
			for (int tick = 0; tick < 48; tick++)
			{
				t += 20 * 60_000L;
				Map<Integer, Long> inventory = new HashMap<>();
				for (int k = 0; k < 28; k++)
				{
					inventory.put(10_000 + r.nextInt(2_000), 1L + r.nextInt(50));
				}
				ledger.observe(Store.INVENTORY, inventory);
				if (tick % 2 == 0)
				{
					int slot = r.nextInt(8);
					int item = 20_000 + r.nextInt(5_000);
					boolean buy = r.nextBoolean();
					long price = 1_000L + item;
					ledger.offer(slot, new Offer(item, buy, 100, price, 0, 0, true));
					ledger.settle(t, prices, ZoneOffset.UTC);
					t += 600;
					ledger.offer(slot, new Offer(item, buy, 100, price, 100, 100 * price, false));
					ledger.settle(t, prices, ZoneOffset.UTC);
					t += 600;
					ledger.offer(slot, null);
				}
				ledger.settle(t, prices, ZoneOffset.UTC);
			}
			// The bank is opened once a day, replacing the guess of what was collected to it.
			ledger.observe(Store.BANK, bank);
			ledger.settle(t, prices, ZoneOffset.UTC);
			t += 8 * 3_600_000L;
		}
		return ledger;
	}
}
