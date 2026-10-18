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
		ledger.offer(7, null);
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

	private static Offer buy(int item, int total, long price, int done, long spent, boolean active)
	{
		return new Offer(item, true, total, price, done, spent, active);
	}

	private static Offer sell(int item, int total, long price, int done, long spent, boolean active)
	{
		return new Offer(item, false, total, price, done, spent, active);
	}

	private long bank(int item)
	{
		return ledger.getStores().get(Store.BANK).getItems().getOrDefault(item, 0L);
	}

	@Test
	public void collectingToTheBankIsNotALoss()
	{
		open();
		// A whip bought for 1M with coins from the bank, then collected while the bank is closed.
		ledger.observe(Store.BANK, items(WHIP, 1));
		ledger.offer(0, buy(WHIP, 1, 1_000_000, 0, 0, true));
		ledger.settle(T0 + 1000, prices, UTC);
		assertEquals(2_508_000, ledger.getTotal());
		ledger.offer(0, buy(WHIP, 1, 1_000_000, 1, 1_000_000, false));
		ledger.settle(T0 + 1500, prices, UTC);
		assertEquals(3_008_000, ledger.getTotal());
		ledger.offer(0, null);
		ledger.settle(T0 + 2000, prices, UTC);
		assertEquals(2, bank(WHIP));
		assertEquals(3_008_000, ledger.getTotal());

		// Collected to the inventory instead, it lands there and nowhere else.
		ledger.observe(Store.INVENTORY, items());
		ledger.offer(1, sell(SHARK, 10, 800, 0, 0, true));
		ledger.settle(T0 + 3000, prices, UTC);
		ledger.offer(1, sell(SHARK, 10, 800, 0, 0, false));
		ledger.settle(T0 + 3500, prices, UTC);
		ledger.offer(1, null);
		ledger.observe(Store.INVENTORY, items(SHARK, 10));
		ledger.settle(T0 + 4000, prices, UTC);
		assertEquals(0, bank(SHARK));
		assertEquals(3_008_000, ledger.getTotal());
		assertEquals(500_000, ledger.getDays().get(0).getPlay());
	}

	@Test
	public void anOfferFillingIsNotACollection()
	{
		open();
		// Bid 1,000 coins each for 1,000 sharks; they trade at 800.
		ledger.observe(Store.BANK, items(WHIP, 1));
		ledger.offer(0, buy(SHARK, 1_000, 1_000, 0, 0, true));
		ledger.settle(T0 + 1000, prices, UTC);
		assertEquals(2_508_000, ledger.getTotal());

		// Half fill at 900 each: 450k coins become 500 sharks worth 400k.
		ledger.offer(0, buy(SHARK, 1_000, 1_000, 500, 450_000, true));
		ledger.settle(T0 + 2000, prices, UTC);
		assertEquals(0, bank(COINS));
		assertEquals(0, bank(SHARK));
		assertEquals(550_000, (long) ledger.getStores().get(Store.GRAND_EXCHANGE).getItems().get(COINS));
		assertEquals(2_508_000 - 50_000, ledger.getTotal());
	}

	@Test
	public void aSaleCountsTheTaxAndCollectingTheCoinsIsNotAGain()
	{
		open();
		ledger.observe(Store.BANK, items(COINS, 1_000_000));
		ledger.offer(2, sell(WHIP, 1, 1_500_000, 0, 0, true));
		ledger.settle(T0 + 1000, prices, UTC);
		assertEquals(2_508_000, ledger.getTotal());

		ledger.offer(2, sell(WHIP, 1, 1_500_000, 1, 1_500_000, false));
		ledger.settle(T0 + 2000, prices, UTC);
		// 2% tax: 30k.
		assertEquals(2_478_000, ledger.getTotal());
		assertEquals(-30_000, ledger.getDays().get(0).getPlay());

		ledger.offer(2, null);
		ledger.observe(Store.INVENTORY, items(SHARK, 10, COINS, 1_470_000));
		ledger.settle(T0 + 3000, prices, UTC);
		assertEquals(2_478_000, ledger.getTotal());
		assertEquals(1_000_000, bank(COINS));
	}

	@Test
	public void taxIsCappedAndSkipsCheapItemsAndBonds()
	{
		assertEquals(5_000_000L, Offer.tax(WHIP, 1, 1_000_000_000L));
		assertEquals(0, Offer.tax(SHARK, 100, 4_900));
		assertEquals(0, Offer.tax(Ledger.BOND, 1, 10_000_000L));
		assertEquals(2 * 16, Offer.tax(SHARK, 2, 1_600));
	}

	@Test
	public void collectingPartOfAnOfferStillTradingIsNotCountedTwice()
	{
		open();
		ledger.observe(Store.BANK, items(WHIP, 1));
		ledger.offer(0, buy(SHARK, 1_000, 800, 400, 320_000, true));
		ledger.settle(T0 + 1000, prices, UTC);
		long worth = ledger.getTotal();

		// Collect the 400 bought so far into the inventory at the exchange.
		ledger.setAtExchange(true);
		ledger.observe(Store.INVENTORY, items(SHARK, 410));
		ledger.settle(T0 + 2000, prices, UTC);
		assertEquals(worth, ledger.getTotal());
		assertEquals(null, ledger.getStores().get(Store.GRAND_EXCHANGE).getItems().get(SHARK));

		// It finishes later and the rest is collected to the bank.
		ledger.setAtExchange(false);
		ledger.offer(0, buy(SHARK, 1_000, 800, 1_000, 800_000, false));
		ledger.settle(T0 + 3000, prices, UTC);
		assertEquals(worth, ledger.getTotal());
		ledger.offer(0, null);
		ledger.settle(T0 + 4000, prices, UTC);
		assertEquals(600, bank(SHARK));
		assertEquals(worth, ledger.getTotal());
	}

	@Test
	public void hugeOffersDoNotOverflow()
	{
		Offer offer = buy(WHIP, 2_000, 2_000_000_000, 0, 0, true);
		assertEquals(4_000_000_000_000L, (long) offer.contents().get(COINS));
	}

	@Test
	public void aLostLootingBagTakesItsContents()
	{
		open();
		ledger.observe(Store.INVENTORY, items(SHARK, 10, Ledger.LOOTING_BAG, 1));
		ledger.observe(Store.LOOTING_BAG, items(WHIP, 1));
		ledger.settle(T0 + 1000, prices, UTC);
		ledger.observe(Store.BANK, items(COINS, 1_000_000));
		ledger.settle(T0 + 2000, prices, UTC);
		assertEquals(2_508_000, ledger.getTotal());

		// Died in the wilderness: the bag and the whip in it are gone.
		ledger.observe(Store.INVENTORY, items());
		ledger.settle(T0 + 3000, prices, UTC);
		assertTrue(ledger.getStores().get(Store.LOOTING_BAG).getItems().isEmpty());
		assertEquals(1_000_000, ledger.getTotal());
	}

	@Test
	public void aBankedLootingBagKeepsItsContents()
	{
		open();
		ledger.observe(Store.INVENTORY, items(Ledger.LOOTING_BAG, 1));
		ledger.observe(Store.LOOTING_BAG, items(SHARK, 5));
		ledger.settle(T0 + 1000, prices, UTC);
		ledger.observe(Store.INVENTORY, items());
		ledger.observe(Store.BANK, items(COINS, 1_000_000, WHIP, 1, Ledger.LOOTING_BAG, 1));
		ledger.settle(T0 + 2000, prices, UTC);
		assertEquals(5L, (long) ledger.getStores().get(Store.LOOTING_BAG).getItems().get(SHARK));
	}

	@Test
	public void aLedgerSavedBeforeSlotsWereKeptStillReads()
	{
		String old = "{\"stores\":{\"BANK\":{\"seen\":1,\"items\":{\"995\":1000}},"
			+ "\"GRAND_EXCHANGE\":{\"seen\":1,\"items\":{\"995\":500}}},\"prices\":{\"995\":1},"
			+ "\"history\":[{\"t\":1,\"v\":1500}],\"days\":[{\"date\":\"2026-10-01\",\"open\":1500,\"close\":1500,"
			+ "\"high\":1500,\"low\":1500,\"items\":{}}],\"total\":1500,\"since\":1}";
		Ledger read = new com.google.gson.GsonBuilder().create().fromJson(old, Ledger.class).repair();
		assertEquals(1500, read.getTotal());
		read.settle(T0, prices, UTC);
		assertEquals(1500, read.getTotal());
		assertEquals(500, read.value(Store.GRAND_EXCHANGE));
		// The first look at the slots replaces the old figure.
		read.offer(0, buy(SHARK, 1, 500, 0, 0, true));
		read.settle(T0 + 1000, prices, UTC);
		assertEquals(1500, read.getTotal());
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
