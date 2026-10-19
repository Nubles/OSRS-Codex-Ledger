package com.codex.ledger.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class TradesTest
{
	private static final ZoneId UTC = ZoneOffset.UTC;
	private static final long DAY = 86_400_000L;
	/** Noon on 1 October 2026, UTC. */
	private static final long T0 = LocalDate.of(2026, 10, 1).atStartOfDay(UTC).toInstant().toEpochMilli() + DAY / 2;
	private static final LocalDate D0 = LocalDate.of(2026, 10, 1);
	private static final int COINS = 995;
	private static final int SHARK = 385;
	private static final int WHIP = 4151;

	private final Map<Integer, Long> price = new HashMap<>();
	private final Prices prices = id -> price.getOrDefault(id, 0L);
	private Ledger ledger;
	private long now = T0;

	@Before
	public void setUp()
	{
		price.put(COINS, 1L);
		price.put(SHARK, 800L);
		price.put(WHIP, 1_500_000L);
		ledger = new Ledger();
		ledger.observe(Store.INVENTORY, items(SHARK, 10));
		ledger.observe(Store.BANK, items(COINS, 1_000_000, WHIP, 1));
		ledger.offer(7, null);
		settle();
		// The slots seen whole once the ledger is open, as the plugin does after login.
		ledger.offer(7, null);
		settle();
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

	private void settle()
	{
		now += 1_000;
		ledger.settle(now, prices, UTC);
	}

	private Day today()
	{
		return ledger.getDays().get(ledger.getDays().size() - 1);
	}

	private long channels(Day day)
	{
		long sum = 0;
		for (Channel c : Channel.values())
		{
			sum += day.channel(c);
		}
		return sum;
	}

	/** Buys 1,000 sharks at 700 each with bank coins and collects them into the inventory. */
	private void buySharks()
	{
		ledger.observe(Store.BANK, items(COINS, 300_000, WHIP, 1));
		ledger.offer(0, new Offer(SHARK, true, 1_000, 700, 0, 0, true));
		settle();
		ledger.offer(0, new Offer(SHARK, true, 1_000, 700, 1_000, 700_000, false));
		settle();
		ledger.offer(0, null);
		ledger.observe(Store.INVENTORY, items(SHARK, 1_010));
		settle();
	}

	@Test
	public void aFlipBooksItsProfitAfterTax()
	{
		buySharks();
		Position held = ledger.getBook().get(SHARK);
		assertEquals(1_000, held.getHeld());
		assertEquals(700_000, held.getCost());

		// Sold for 900 each: 18 tax an item, so 882k for what cost 700k.
		ledger.observe(Store.INVENTORY, items(SHARK, 10));
		ledger.offer(1, new Offer(SHARK, false, 1_000, 900, 0, 0, true));
		settle();
		ledger.offer(1, new Offer(SHARK, false, 1_000, 900, 600, 540_000, true));
		settle();
		ledger.offer(1, new Offer(SHARK, false, 1_000, 900, 1_000, 900_000, false));
		settle();

		Deal day = today().getTrades();
		assertEquals(1_000, day.getBought());
		assertEquals(700_000, day.getBuyCoins());
		assertEquals(1_000, day.getSold());
		assertEquals(882_000, day.getSaleCoins());
		assertEquals(18_000, day.getTax());
		assertEquals(182_000, day.getProfit());
		assertEquals(0, day.getUnmatched());
		assertEquals(0, ledger.getBook().get(SHARK).getHeld());
		assertEquals(182_000, today().getDeals().get(SHARK).getProfit());
		assertEquals(0.26, day.roi(), 0.001);

		// The price never moved, so what trading made is all the play there was.
		assertEquals(182_000, today().channel(Channel.EXCHANGE));
		assertEquals(today().getPlay(), channels(today()));
		// And the day book's lists leave the trading to the exchange.
		Report report = Report.of(ledger, 1, D0, 10);
		assertTrue(report.getGains().isEmpty());
		assertTrue(report.getLosses().isEmpty());
		assertEquals(182_000, report.channel(Channel.EXCHANGE));
		assertEquals(0, report.unexplained());
	}

	@Test
	public void sellingWhatWasNeverBoughtMakesNoProfit()
	{
		ledger.observe(Store.BANK, items(COINS, 1_000_000));
		ledger.offer(2, new Offer(WHIP, false, 1, 1_500_000, 0, 0, true));
		settle();
		ledger.offer(2, new Offer(WHIP, false, 1, 1_500_000, 1, 1_500_000, false));
		settle();
		Deal day = today().getTrades();
		assertEquals(1, day.getSold());
		assertEquals(1, day.getUnmatched());
		assertEquals(1_470_000, day.getUnmatchedCoins());
		assertEquals(0, day.getProfit());
		assertEquals(0, day.roi(), 0);
		// Selling at the market price costs only the tax.
		assertEquals(-30_000, today().channel(Channel.EXCHANGE));
	}

	@Test
	public void boughtItemsUsedUpLeaveThePosition()
	{
		buySharks();
		ledger.observe(Store.INVENTORY, items(SHARK, 410));
		settle();
		Position p = ledger.getBook().get(SHARK);
		assertEquals(410, p.getHeld());
		assertEquals(287_000, p.getCost());
		assertEquals(-600 * 800, today().channel(Channel.USED));
	}

	@Test
	public void anOfferPlacedWhileAwayCountsWhatItTraded()
	{
		// Placed on the mobile app and half filled before the client saw it.
		ledger.observe(Store.BANK, items(COINS, 300_000, WHIP, 1));
		ledger.offer(3, new Offer(SHARK, true, 1_000, 700, 500, 350_000, true));
		settle();
		assertEquals(500, ledger.getBook().get(SHARK).getHeld());
		assertEquals(500, today().getTrades().getBought());
	}

	@Test
	public void offersAtTheVeryFirstLookAreNotTrades()
	{
		Ledger fresh = new Ledger();
		fresh.observe(Store.BANK, items(COINS, 650_000));
		fresh.offer(0, new Offer(SHARK, true, 1_000, 700, 500, 350_000, true));
		fresh.settle(T0, prices, UTC);
		fresh.offer(0, new Offer(SHARK, true, 1_000, 700, 500, 350_000, true));
		fresh.settle(T0 + 1_000, prices, UTC);
		assertTrue(fresh.getBook().isEmpty());
		// It goes on filling: that part is a trade.
		fresh.offer(0, new Offer(SHARK, true, 1_000, 700, 800, 560_000, true));
		fresh.settle(T0 + 2_000, prices, UTC);
		assertEquals(300, fresh.getBook().get(SHARK).getHeld());
		assertEquals(210_000, fresh.getBook().get(SHARK).getCost());
	}

	@Test
	public void deathLossesAreSetAgainstWhatComesBack()
	{
		ledger.observe(Store.EQUIPMENT, items());
		settle();
		ledger.observe(Store.EQUIPMENT, items(WHIP, 1));
		ledger.observe(Store.BANK, items(COINS, 1_000_000));
		settle();
		assertEquals(0, today().getPlay());
		ledger.died(now);
		ledger.observe(Store.INVENTORY, items());
		ledger.observe(Store.EQUIPMENT, items());
		settle();
		assertEquals(-1_508_000, today().channel(Channel.DEATHS));

		// Back from the gravestone: the whip and sharks return, and five more sharks are loot.
		now += 300_000;
		ledger.observe(Store.INVENTORY, items(SHARK, 15));
		ledger.observe(Store.EQUIPMENT, items(WHIP, 1));
		settle();
		assertEquals(0, today().channel(Channel.DEATHS));
		assertEquals(4_000, today().channel(Channel.LOOT));
		// Eating them later is supplies, not the death.
		ledger.observe(Store.INVENTORY, items(SHARK, 12));
		settle();
		assertEquals(-2_400, today().channel(Channel.USED));
		assertEquals(today().getPlay(), channels(today()));
	}

	@Test
	public void aSafeDeathEndsWithWhatItTookAndDoesNotTakeTheSuppliesAfter()
	{
		ledger.died(now);
		ledger.observe(Store.INVENTORY, items(SHARK, 4));
		settle();
		assertEquals(-4_800, today().channel(Channel.DEATHS));
		// Eating a shark a moment later is supplies.
		ledger.observe(Store.INVENTORY, items(SHARK, 3));
		settle();
		assertEquals(-800, today().channel(Channel.USED));
		assertEquals(-4_800, today().channel(Channel.DEATHS));
	}

	@Test
	public void pagesBeyondAMonthKeepOnlyTheirTradeTotals()
	{
		buySharks();
		for (int d = 1; d <= Ledger.DEAL_DAYS; d++)
		{
			now = T0 + d * DAY;
			settle();
		}
		Day first = ledger.getDays().get(0);
		assertTrue(first.getDeals().isEmpty());
		assertEquals(700_000, first.getTrades().getBuyCoins());
		assertEquals(700_000, Trades.of(ledger, 0, D0.plusDays(Ledger.DEAL_DAYS)).getTotal().getBuyCoins());
	}

	@Test
	public void coinsComingAndGoingAreTheirOwnChannels()
	{
		// A shop: coins out, sharks in; then an alch: a whip out, coins in.
		ledger.observe(Store.INVENTORY, items(SHARK, 20, COINS, 1_000));
		ledger.observe(Store.BANK, items(COINS, 990_000, WHIP, 1));
		settle();
		assertEquals(-9_000, today().channel(Channel.COINS_OUT));
		assertEquals(8_000, today().channel(Channel.LOOT));
		ledger.observe(Store.INVENTORY, items(SHARK, 20, COINS, 73_000));
		ledger.observe(Store.BANK, items(COINS, 990_000));
		settle();
		assertEquals(72_000, today().channel(Channel.COINS_IN));
		assertEquals(-1_500_000, today().channel(Channel.USED));
		assertEquals(today().getPlay(), channels(today()));
	}

	@Test
	public void spansAndOpenPositions()
	{
		buySharks();
		// The next day the price rises to 1,000: 980 after tax on 1,000 that cost 700.
		now += DAY;
		price.put(SHARK, 1_000L);
		settle();
		Trades today = Trades.of(ledger, 1, D0.plusDays(1));
		assertEquals(0, today.getTotal().volume());
		Trades week = Trades.of(ledger, 7, D0.plusDays(1));
		assertEquals(700_000, week.getTotal().getBuyCoins());
		assertEquals(D0, week.getFrom());
		Trades all = Trades.of(ledger, 0, D0.plusDays(1));
		assertEquals(1, all.getItems().size());
		assertNull(all.getFrom());
		assertEquals(1, all.getOpen().size());
		Trades.Open open = all.getOpen().get(0);
		assertEquals(1_000, open.getQuantity());
		assertEquals(700_000, open.getCost());
		assertEquals(980_000, open.getWorth());
		assertEquals(280_000, all.openGain());
	}

	@Test
	public void aClosedPageKeepsItsTradeTotalsWhole()
	{
		for (int i = 0; i < Day.KEPT_DEALS + 6; i++)
		{
			int item = 2_000 + i;
			price.put(item, 100L);
			ledger.offer(4, new Offer(item, true, 10, 100, 10, 1_000, false));
			settle();
			ledger.offer(4, null);
			settle();
		}
		now += DAY;
		settle();
		Day closed = ledger.getDays().get(0);
		assertEquals(Day.KEPT_DEALS, closed.getDeals().size());
		assertEquals((Day.KEPT_DEALS + 6) * 1_000L, closed.getTrades().getBuyCoins());
	}

	@Test
	public void tradesSurviveBeingSavedAndRead()
	{
		buySharks();
		ledger.died(now);
		com.google.gson.Gson gson = new com.google.gson.GsonBuilder().create();
		Ledger read = gson.fromJson(gson.toJson(ledger), Ledger.class).repair();
		assertEquals(1_000, read.getBook().get(SHARK).getHeld());
		assertEquals(700_000, read.getDays().get(0).getTrades().getBuyCoins());
		assertEquals(100_000, read.getDays().get(0).channel(Channel.EXCHANGE));
		// A page written before any of this was kept reads as nothing traded.
		String old = "{\"date\":\"2026-10-01\",\"open\":1,\"close\":1,\"high\":1,\"low\":1,\"items\":{}}";
		Day page = gson.fromJson(old, Day.class);
		assertEquals(0, page.getTrades().volume());
		assertTrue(page.getDeals().isEmpty());
	}

	@Test
	public void forecastsTheGoalAtTheMonthsPace()
	{
		// A million a day for ten days.
		for (int i = 1; i < 10; i++)
		{
			now = T0 + i * DAY;
			ledger.observe(Store.BANK, items(COINS, 1_000_000 + i * 1_000_000L, WHIP, 1));
			settle();
		}
		LocalDate today = D0.plusDays(9);
		Forecast f = Forecast.of(ledger, today, 20_000_000L);
		assertEquals(10, f.getDays());
		assertEquals(900_000, f.getPerDay(), 1);
		// 8.49M to go at 900k a day: ten more days.
		assertEquals(today.plusDays(10), f.getEta());
		assertTrue(f.isGoalSet());

		Forecast round = Forecast.of(ledger, today, 0);
		assertEquals(25_000_000L, round.getTarget());
		assertEquals(3_000_000_000L, Forecast.nextMark(2_100_000_000L));

		// Falling worth never gets there.
		ledger.observe(Store.BANK, items(WHIP, 1));
		settle();
		assertNull(Forecast.of(ledger, today, 20_000_000L).getEta());
	}

	@Test
	public void tooEarlyForAPace()
	{
		Forecast f = Forecast.of(ledger, D0, 0);
		assertEquals(1, f.getDays());
		assertNull(f.getEta());
		assertEquals(5_000_000L, f.getTarget());
	}
}
