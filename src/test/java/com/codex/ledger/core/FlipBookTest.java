package com.codex.ledger.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class FlipBookTest
{
	private static final Gson GSON = new GsonBuilder().create();
	private static final int SHARK = 385;
	private static final int WHIP = 4151;
	private static final int TWISTED_BOW = 20997;
	private static final int BOND = 13190;
	private static final int FEATHER = 314;
	private static final long T0 = 1_790_000_000_000L;
	private static final long MIN = 60_000L;

	private FlipBook book;
	private long now = T0;

	@Before
	public void setUp()
	{
		book = new FlipBook();
		// Logged in with nothing on the exchange: the slots are read whole once.
		book.read(new Offer[8], now);
	}

	private void tick(long ms)
	{
		now += ms;
		book.watching(now);
	}

	private static Offer buy(int item, int total, long price, int done, long spent)
	{
		return new Offer(item, true, total, price, done, spent, done < total);
	}

	private static Offer sell(int item, int total, long price, int done, long spent)
	{
		return new Offer(item, false, total, price, done, spent, done < total);
	}

	private Offer[] slots(Object... pairs)
	{
		Offer[] offers = new Offer[8];
		for (int i = 0; i < pairs.length; i += 2)
		{
			offers[(Integer) pairs[i]] = (Offer) pairs[i + 1];
		}
		return offers;
	}

	/** Buys 1,000 sharks at 700 in slot 0 and collects them. */
	private void buySharks()
	{
		book.offer(0, buy(SHARK, 1_000, 700, 0, 0), now);
		tick(MIN);
		book.offer(0, buy(SHARK, 1_000, 700, 1_000, 700_000), now);
		tick(MIN);
		book.offer(0, null, now);
	}

	@Test
	public void aSaleMatchedToItsBuyIsAFlipAfterTax()
	{
		buySharks();
		tick(10 * MIN);
		book.offer(1, sell(SHARK, 1_000, 800, 0, 0), now);
		tick(MIN);
		book.offer(1, sell(SHARK, 1_000, 800, 1_000, 800_000), now);

		List<FlipBook.Flip> flips = book.getFlips();
		assertEquals(1, flips.size());
		FlipBook.Flip f = flips.get(0);
		assertEquals(1_000, f.getQuantity());
		assertEquals(700_000, f.getCost());
		// 2% of 800 is 16 an item.
		assertEquals(16_000, f.getTax());
		assertEquals(784_000, f.getRevenue());
		assertEquals(84_000, f.getProfit());
		assertEquals(84, Math.round(f.margin()));
		assertFalse(f.isSeenLate());
		assertTrue("held from the buy to the sale", f.held() >= 12 * MIN);
		assertTrue(book.getLots().isEmpty());
	}

	@Test
	public void partialFillsOfOneSaleMakeOneFlipAndMatchTheOldestBuysFirst()
	{
		// Two buys at different prices.
		book.offer(0, buy(SHARK, 600, 700, 600, 420_000), now);
		tick(MIN);
		book.offer(2, buy(SHARK, 400, 750, 400, 300_000), now);
		tick(MIN);
		// One sale that fills in three parts.
		book.offer(1, sell(SHARK, 1_000, 800, 0, 0), now);
		tick(MIN);
		book.offer(1, sell(SHARK, 1_000, 800, 300, 240_000), now);
		tick(MIN);
		book.offer(1, sell(SHARK, 1_000, 800, 800, 640_000), now);

		assertEquals(1, book.getFlips().size());
		FlipBook.Flip f = book.getFlips().get(0);
		assertEquals(800, f.getQuantity());
		// The oldest buy first: 600 at 700, then 200 at 750.
		assertEquals(600 * 700 + 200 * 750, f.getCost());
		assertEquals(800 * (800 - 16), f.getRevenue());
		assertEquals(200, book.getLots().get(SHARK).get(0).getQuantity());
		assertEquals(150_000, book.getLots().get(SHARK).get(0).getCost());

		tick(MIN);
		book.offer(1, sell(SHARK, 1_000, 800, 1_000, 800_000), now);
		assertEquals(1, book.getFlips().size());
		assertEquals(1_000, book.getFlips().get(0).getQuantity());
		assertEquals(720_000, book.getFlips().get(0).getCost());
		assertTrue(book.getLots().isEmpty());
	}

	@Test
	public void aSaleNeverSeenBoughtMakesNoProfit()
	{
		book.offer(3, sell(WHIP, 2, 1_500_000, 2, 3_000_000), now);
		FlipBook.Flip f = book.getFlips().get(0);
		assertEquals(0, f.getQuantity());
		assertEquals(0, f.getProfit());
		assertEquals(2, f.getUnmatched());
		assertEquals(3_000_000 - 60_000, f.getUnmatchedRevenue());
		assertEquals(0, book.summary(0).getTotal().getFlips());
	}

	@Test
	public void taxIsTwoPercentRoundedDownCappedAtFiveMillionAndSparesTheExemptAndCheap()
	{
		assertEquals(0, FlipBook.taxEach(FEATHER, 49));
		assertEquals(1, FlipBook.taxEach(FEATHER, 50));
		assertEquals(1, FlipBook.taxEach(FEATHER, 99));
		assertEquals(2, FlipBook.taxEach(FEATHER, 100));
		assertEquals(4_999_999, FlipBook.taxEach(TWISTED_BOW, 249_999_999));
		assertEquals(5_000_000, FlipBook.taxEach(TWISTED_BOW, 250_000_000));
		assertEquals(5_000_000, FlipBook.taxEach(TWISTED_BOW, 1_600_000_000L));
		assertEquals(0, FlipBook.taxEach(BOND, 12_000_000));
		// Lobster and a hammer are among the wiki's tax-free items.
		assertEquals(0, FlipBook.taxEach(379, 200));
		assertEquals(0, FlipBook.taxEach(2347, 300));

		// A capped sale: bought at 1.5B, sold at 1.6B, the tax is 5M, not 32M.
		book.offer(0, buy(TWISTED_BOW, 1, 1_500_000_000L, 1, 1_500_000_000L), now);
		book.offer(1, sell(TWISTED_BOW, 1, 1_600_000_000L, 1, 1_600_000_000L), now);
		FlipBook.Flip f = book.getFlips().get(0);
		assertEquals(5_000_000, f.getTax());
		assertEquals(95_000_000, f.getProfit());
	}

	@Test
	public void theFirstReadEverTakesTheSlotsAsTheyStandWithoutBookingThem()
	{
		FlipBook fresh = new FlipBook();
		fresh.read(slots(0, sell(SHARK, 1_000, 800, 500, 400_000)), now);
		assertTrue(fresh.getFlips().isEmpty());
		FlipBook.Slot s = fresh.getSlots().get(0);
		assertTrue(s.isSeenLate());
		// How long it has stood is not known: at least since now, at most unknown.
		assertEquals(-1, s.idle(now + 5 * MIN)[1]);
		// What it trades from here on is counted.
		fresh.offer(0, sell(SHARK, 1_000, 800, 600, 480_000), now + MIN);
		assertEquals(100, fresh.getFlips().get(0).getUnmatched());
	}

	@Test
	public void tradesWhileLoggedOutAreBookedExactlyButMarkedSeenLate()
	{
		buySharks();
		book.offer(1, sell(SHARK, 1_000, 800, 0, 0), now);
		long logout = now;
		// Logged out for three hours; the sale went through meanwhile.
		now += 180 * MIN;
		assertTrue(book.read(slots(1, sell(SHARK, 1_000, 800, 1_000, 800_000)), now));

		FlipBook.Flip f = book.getFlips().get(0);
		assertTrue(f.isSeenLate());
		assertEquals(84_000, f.getProfit());
		assertEquals(logout, f.getSoldFrom());
		assertEquals(now, f.getSoldAt());
		FlipBook.Slot s = book.getSlots().get(1);
		assertTrue(s.isSeenLate());
		assertEquals(logout, s.getFilledFrom());
		assertEquals(now, s.getFilledTo());
		long[] idle = s.idle(now + 10 * MIN);
		assertEquals(10 * MIN, idle[0]);
		assertEquals(190 * MIN, idle[1]);
		FlipBook.Summary sum = book.summary(0);
		assertEquals(1, sum.getSeenLate());
		assertEquals(84_000, sum.getSeenLateProfit());
	}

	@Test
	public void aWorldHopIsNotTimeAway()
	{
		buySharks();
		book.offer(1, sell(SHARK, 1_000, 800, 0, 0), now);
		tick(MIN / 2);
		now += 20_000;
		book.read(slots(1, sell(SHARK, 1_000, 800, 1_000, 800_000)), now);
		assertFalse(book.getFlips().get(0).isSeenLate());
		assertFalse(book.getSlots().get(1).isSeenLate());
	}

	@Test
	public void anOfferPlacedOnAnotherDeviceIsSeenLateWithItsPlacingBetweenLogoutAndLogin()
	{
		long logout = now;
		now += 60 * MIN;
		book.read(slots(4, buy(SHARK, 500, 690, 200, 138_000)), now);
		FlipBook.Slot s = book.getSlots().get(4);
		assertTrue(s.isSeenLate());
		assertEquals(logout, s.getPlacedFrom());
		assertEquals(now, s.getPlacedTo());
		List<FlipBook.Lot> lots = book.getLots().get(SHARK);
		assertEquals(200, lots.get(0).getQuantity());
		assertTrue(lots.get(0).isSeenLate());
		// Sold later in sight: the flip carries the late buy's doubt.
		book.offer(5, sell(SHARK, 200, 800, 200, 160_000), now + MIN);
		assertTrue(book.getFlips().get(0).isSeenLate());
	}

	@Test
	public void anOfferGoneMidwayWhileAwayIsToldAndNotGuessed()
	{
		book.offer(2, buy(SHARK, 1_000, 700, 400, 280_000), now);
		now += 120 * MIN;
		// Collected and cancelled on the phone: the slot is empty at login.
		book.read(new Offer[8], now);
		List<FlipBook.Unseen> unseen = book.getUnseen();
		assertEquals(1, unseen.size());
		assertEquals(400, unseen.get(0).getDone());
		assertEquals(1_000, unseen.get(0).getTotal());
		// Only what was seen is counted.
		assertEquals(400, book.getLots().get(SHARK).get(0).getQuantity());
	}

	@Test
	public void anOfferHiddenForAWhileCarriesOnWithoutCountingTwice()
	{
		book.offer(6, buy(SHARK, 1_000, 700, 400, 280_000), now);
		now += 120 * MIN;
		// Logged into a free world: members' slots show empty.
		book.read(new Offer[8], now);
		assertEquals(1, book.getUnseen().size());
		tick(MIN);
		// Back on a members' world, the offer is there again, a little further on.
		now += 20_000;
		book.read(slots(6, buy(SHARK, 1_000, 700, 500, 350_000)), now);
		assertTrue("it came back, so it did not end out of sight", book.getUnseen().isEmpty());
		long lots = 0;
		for (FlipBook.Lot l : book.getLots().get(SHARK))
		{
			lots += l.getQuantity();
		}
		assertEquals(500, lots);
	}

	@Test
	public void aNewOfferJustLikeACollectedOneIsNew()
	{
		buySharks();
		book.offer(0, buy(SHARK, 1_000, 700, 0, 0), now);
		// The old one's slot was emptied; the new one is a separate offer even with the same terms.
		book.offer(0, buy(SHARK, 1_000, 700, 300, 210_000), now);
		long lots = 0;
		for (FlipBook.Lot l : book.getLots().get(SHARK))
		{
			lots += l.getQuantity();
		}
		assertEquals(1_300, lots);
	}

	@Test
	public void itemsUsedOrLostLeaveTheOldestLots()
	{
		book.offer(0, buy(SHARK, 100, 700, 100, 70_000), now);
		tick(MIN);
		book.offer(1, buy(SHARK, 100, 900, 100, 90_000), now);
		Map<Integer, Long> held = new HashMap<>();
		held.put(SHARK, 120L);
		assertTrue(book.holdAtMost(held));
		List<FlipBook.Lot> lots = book.getLots().get(SHARK);
		assertEquals(2, lots.size());
		assertEquals(20, lots.get(0).getQuantity());
		assertEquals(14_000, lots.get(0).getCost());
		assertFalse(book.holdAtMost(held));
		held.put(SHARK, 0L);
		book.holdAtMost(held);
		assertNull(book.getLots().get(SHARK));
	}

	@Test
	public void slotClocksCountFromTheLastTrade()
	{
		book.offer(0, buy(SHARK, 1_000, 700, 0, 0), now);
		long placed = now;
		tick(20 * MIN);
		assertEquals(20 * MIN, book.getSlots().get(0).idle(now)[0]);
		assertEquals(20 * MIN, book.getSlots().get(0).idle(now)[1]);
		book.offer(0, buy(SHARK, 1_000, 700, 10, 7_000), now);
		tick(5 * MIN);
		assertEquals(5 * MIN, book.getSlots().get(0).idle(now)[0]);
		assertEquals(placed, book.getSlots().get(0).getPlacedFrom());
	}

	@Test
	public void summariesCoverOnlyTheirSpanAndRankByProfit()
	{
		book.offer(0, buy(SHARK, 10, 700, 10, 7_000), now);
		book.offer(1, sell(SHARK, 10, 800, 10, 8_000), now);
		tick(86_400_000L);
		book.offer(0, null, now);
		book.offer(1, null, now);
		book.offer(0, buy(WHIP, 1, 1_400_000, 1, 1_400_000), now);
		book.offer(1, sell(WHIP, 1, 1_500_000, 1, 1_500_000), now);
		FlipBook.Summary all = book.summary(0);
		assertEquals(2, all.getTotal().getFlips());
		assertEquals(WHIP, all.getItems().get(0).getItemId());
		assertEquals(WHIP, all.getFlips().get(0).getItem());
		FlipBook.Summary today = book.summary(now - 1_000);
		assertEquals(1, today.getTotal().getFlips());
		assertEquals(100_000 - 30_000, today.getTotal().getProfit());
	}

	@Test
	public void marksKeepEachItemsRecentPrices()
	{
		for (int i = 0; i < FlipBook.MARKS + 5; i++)
		{
			book.offer(0, buy(SHARK, 1, 700 + i, 1, 700 + i), now);
			book.offer(0, null, now);
			tick(MIN);
		}
		List<FlipBook.Mark> marks = book.marks(SHARK);
		assertEquals(FlipBook.MARKS, marks.size());
		assertEquals(700 + FlipBook.MARKS + 4, marks.get(marks.size() - 1).getPrice());
	}

	@Test
	public void theBookSavesAndReadsBackWhole()
	{
		buySharks();
		book.offer(1, sell(SHARK, 1_000, 800, 600, 480_000), now);
		book.offer(2, buy(WHIP, 1, 1_400_000, 0, 0), now);
		String json = GSON.toJson(book);
		FlipBook read = FlipBook.read(GSON, json);
		assertNotNull(read);
		assertEquals(json, GSON.toJson(read));
		// Carries on from the save.
		read.offer(1, sell(SHARK, 1_000, 800, 1_000, 800_000), now + MIN);
		assertEquals(1, read.getFlips().size());
		assertEquals(1_000, read.getFlips().get(0).getQuantity());
	}

	@Test
	public void aDamagedOrEmptySaveReadsAsNothingOrMends()
	{
		assertNull(FlipBook.read(GSON, null));
		assertNull(FlipBook.read(GSON, ""));
		assertNull(FlipBook.read(GSON, "{\"flips\":[{\"q\":"));
		FlipBook partial = FlipBook.read(GSON, "{\"v\":1,\"flips\":[null],\"lots\":{\"385\":[{\"q\":0}]}}");
		assertNotNull(partial);
		assertTrue(partial.getFlips().isEmpty());
		assertTrue(partial.getLots().isEmpty());
		assertTrue(partial.getSlots().isEmpty());
	}

	@Test
	public void aLedgerSavedBeforeFlipsReadsAsItDid()
	{
		// The flips book is its own key; the ledger's saved shape does not change.
		String json = GSON.toJson(new Ledger());
		assertFalse(json.contains("flip"));
	}
}
