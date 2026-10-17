package com.codex.ledger.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class LedgerTest
{
	private static final ZoneId UTC = ZoneOffset.UTC;
	private static final long DAY = 86_400_000L;
	/** Noon on 1 October 2026, UTC. */
	private static final long T0 = LocalDate.of(2026, 10, 1).atStartOfDay(UTC).toInstant().toEpochMilli() + DAY / 2;
	private static final int COINS = 995;
	private static final int SHARK = 385;
	private static final int WHIP = 4151;

	private final Map<Integer, Long> price = new HashMap<>();
	private final Prices prices = id -> price.getOrDefault(id, 0L);
	private Ledger ledger;

	@Before
	public void setUp()
	{
		price.put(COINS, 1L);
		price.put(SHARK, 800L);
		price.put(WHIP, 1_500_000L);
		ledger = new Ledger();
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

	private void open()
	{
		ledger.observe(Store.INVENTORY, items(SHARK, 10));
		ledger.observe(Store.BANK, items(COINS, 1_000_000, WHIP, 1));
		ledger.settle(T0, prices, UTC);
	}

	@Test
	public void beginsWhenTheBankIsSeen()
	{
		ledger.observe(Store.INVENTORY, items(SHARK, 10));
		ledger.settle(T0, prices, UTC);
		assertFalse(ledger.isReady());
		assertTrue(ledger.getDays().isEmpty());
		assertEquals(8_000, ledger.getTotal());

		ledger.observe(Store.BANK, items(COINS, 1_000_000, WHIP, 1));
		ledger.settle(T0 + 1000, prices, UTC);
		assertTrue(ledger.isReady());
		assertEquals(2_508_000, ledger.getTotal());
		assertEquals(1, ledger.getDays().size());
		Day day = ledger.getDays().get(0);
		assertEquals(2_508_000, day.getOpen());
		assertEquals(0, day.change());
		assertEquals(1, ledger.getHistory().size());
	}

	@Test
	public void movingItemsBetweenPlacesIsNotAGain()
	{
		open();
		ledger.observe(Store.INVENTORY, items(SHARK, 10, WHIP, 1));
		ledger.observe(Store.BANK, items(COINS, 1_000_000));
		ledger.settle(T0 + 60_000, prices, UTC);
		Day day = ledger.getDays().get(0);
		assertEquals(0, day.getPlay());
		assertEquals(0, day.change());
		assertTrue(day.getItems().isEmpty());
	}

	@Test
	public void eatingSharksIsPlayAndPricesAreMarket()
	{
		open();
		ledger.observe(Store.INVENTORY, items(SHARK, 4));
		ledger.settle(T0 + 60_000, prices, UTC);
		price.put(WHIP, 1_600_000L);
		ledger.settle(T0 + 120_000, prices, UTC);

		Day day = ledger.getDays().get(0);
		assertEquals(-4_800, day.getPlay());
		assertEquals(100_000, day.getMarket());
		assertEquals(95_200, day.change());
		assertEquals(2_603_200, ledger.getTotal());
		assertEquals(-6, day.getItems().get(SHARK).getQuantity());
		assertEquals(100_000, day.getItems().get(WHIP).getDrift());
	}

	@Test
	public void aPlaceSeenForTheFirstTimeIsOpeningBalance()
	{
		open();
		ledger.observe(Store.SEED_VAULT, items(SHARK, 100));
		ledger.settle(T0 + 60_000, prices, UTC);
		Day day = ledger.getDays().get(0);
		assertEquals(0, day.getPlay());
		assertEquals(0, day.change());
		assertEquals(2_588_000, day.getOpen());
		assertEquals(2_588_000, ledger.getTotal());
	}

	@Test
	public void unknownPricesKeepTheLastOne()
	{
		open();
		price.remove(WHIP);
		ledger.settle(T0 + 60_000, prices, UTC);
		assertEquals(2_508_000, ledger.getTotal());
		assertEquals(0, ledger.getDays().get(0).getMarket());
	}

	@Test
	public void aNewDayOpensOnYesterdaysClose()
	{
		open();
		ledger.observe(Store.BANK, items(COINS, 2_000_000, WHIP, 1));
		ledger.settle(T0 + 60_000, prices, UTC);
		ledger.observe(Store.BANK, items(COINS, 1_500_000, WHIP, 1));
		ledger.settle(T0 + DAY, prices, UTC);

		assertEquals(2, ledger.getDays().size());
		Day second = ledger.getDays().get(1);
		assertEquals(3_508_000, second.getOpen());
		assertEquals(-500_000, second.change());

		Report week = Report.of(ledger, 7, LocalDate.of(2026, 10, 2), 5);
		assertEquals(2, week.getDays());
		assertEquals(500_000, week.change());
		assertEquals(2_508_000, week.getStart());
		assertEquals(COINS, week.getGains().get(0).getItemId());
		assertEquals(500_000, week.getGains().get(0).getValue());

		Report today = Report.of(ledger, 1, LocalDate.of(2026, 10, 2), 5);
		assertEquals(-500_000, today.change());
		assertEquals(COINS, today.getLosses().get(0).getItemId());
	}

	@Test
	public void nothingChangedIsQuiet()
	{
		open();
		assertFalse(ledger.settle(T0 + 60_000, prices, UTC));
		assertEquals(1, ledger.getHistory().size());
	}

	@Test
	public void holdingsRankByValue()
	{
		open();
		assertEquals(WHIP, Report.holdings(ledger, 10).get(0).getItemId());
		assertEquals(3, Report.holdings(ledger, 10).size());
		assertEquals(8_000, ledger.value(Store.INVENTORY));
	}

	@Test
	public void collectingToTheBankIsNotALoss()
	{
		open();
		ledger.observe(Store.GRAND_EXCHANGE, items(WHIP, 1));
		ledger.observe(Store.BANK, items(COINS, 0));
		ledger.settle(T0 + 1000, prices, UTC);
		// The whip bought on the exchange is collected while the bank is closed.
		ledger.observe(Store.GRAND_EXCHANGE, items());
		ledger.settle(T0 + 2000, prices, UTC);
		assertEquals(1L, (long) ledger.getStores().get(Store.BANK).getItems().get(WHIP));
		assertEquals(1_508_000, ledger.getTotal());

		// Collected to the inventory instead, it lands there and nowhere else.
		ledger.observe(Store.GRAND_EXCHANGE, items(SHARK, 5));
		ledger.settle(T0 + 3000, prices, UTC);
		ledger.observe(Store.GRAND_EXCHANGE, items());
		ledger.observe(Store.INVENTORY, items(SHARK, 15));
		ledger.settle(T0 + 4000, prices, UTC);
		assertEquals(null, ledger.getStores().get(Store.BANK).getItems().get(SHARK));
		assertEquals(1_512_000, ledger.getTotal());
	}

	@Test
	public void aPageReportsItsOwnDay()
	{
		open();
		ledger.observe(Store.INVENTORY, items(SHARK, 4));
		ledger.settle(T0 + 60_000, prices, UTC);
		Report page = Report.page(ledger.getDays().get(0), 5);
		assertEquals(2_508_000, page.getStart());
		assertEquals(-4_800, page.change());
		assertEquals(SHARK, page.getLosses().get(0).getItemId());
	}

	@Test
	public void survivesBeingSavedAndRead()
	{
		open();
		ledger.observe(Store.INVENTORY, items(SHARK, 4));
		ledger.settle(T0 + 60_000, prices, UTC);
		com.google.gson.Gson gson = new com.google.gson.GsonBuilder().create();
		Ledger read = gson.fromJson(gson.toJson(ledger), Ledger.class).repair();
		assertEquals(ledger.getTotal(), read.getTotal());
		assertEquals(4L, (long) read.getStores().get(Store.INVENTORY).getItems().get(SHARK));
		assertEquals(-4_800, read.getDays().get(0).getPlay());
		assertFalse(read.hasPending());
		read.observe(Store.INVENTORY, items(SHARK, 2));
		assertTrue(read.settle(T0 + 120_000, prices, UTC));
		assertEquals(-6_400, read.getDays().get(0).getPlay());
	}
}
