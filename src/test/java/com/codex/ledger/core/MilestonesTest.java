package com.codex.ledger.core;

import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Round figures passed while the ledger watched, and the next one ahead. */
public class MilestonesTest
{
	private static final long DAY = 86_400_000L;

	private static Ledger.Point at(long day, long worth)
	{
		return new Ledger.Point(day * DAY, worth);
	}

	@Test
	public void theLadderIsTheOneHeadingForAimsAt()
	{
		assertEquals(1_000_000L, Milestones.above(0));
		assertEquals(100_000_000L, Milestones.above(73_000_000L));
		assertEquals(1_000_000_000L, Milestones.above(835_500_000L));
		assertEquals(Forecast.nextMark(1_000_000_000L), Milestones.above(1_000_000_000L));
		assertEquals(4_000_000_000L, Milestones.above(3_200_000_000L));
	}

	@Test
	public void marksEachFigureWhenFirstPassedNewestFirst()
	{
		Milestones m = Milestones.of(Arrays.asList(at(1, 180_000_000L), at(4, 260_000_000L), at(9, 240_000_000L), at(20, 510_000_000L)),
			700_000_000L, 30 * DAY);
		assertEquals(2, m.getPassed().size());
		assertEquals(500_000_000L, m.getPassed().get(0).getAmount());
		assertEquals(20 * DAY, m.getPassed().get(0).getAt());
		assertEquals(16 * DAY, m.getPassed().get(0).getAfter());
		assertEquals(250_000_000L, m.getPassed().get(1).getAmount());
		assertEquals(-1, m.getPassed().get(1).getAfter());
		assertEquals(750_000_000L, m.getNext());
		assertEquals(50_000_000L, m.getToGo());
		assertFalse(m.isRegain());
	}

	@Test
	public void aFigureHeldWhenTheLedgerBeganIsNoMilestone()
	{
		Milestones m = Milestones.of(Collections.singletonList(at(1, 600_000_000L)), 700_000_000L, 2 * DAY);
		assertTrue(m.getPassed().isEmpty());
		assertEquals(750_000_000L, m.getNext());
	}

	@Test
	public void oneBigDayCanPassSeveralFigures()
	{
		Milestones m = Milestones.of(Arrays.asList(at(1, 40_000_000L), at(2, 1_200_000_000L)), 1_200_000_000L, 3 * DAY);
		assertEquals(1_000_000_000L, m.getPassed().get(0).getAmount());
		assertEquals(0, m.getPassed().get(0).getAfter());
		assertEquals(Milestones.KEPT, m.getPassed().size());
	}

	@Test
	public void fallingBackUnderAFigureMakesItTheNextToRegain()
	{
		Milestones m = Milestones.of(Arrays.asList(at(1, 90_000_000L), at(2, 110_000_000L)), 95_000_000L, 3 * DAY);
		assertEquals(100_000_000L, m.getNext());
		assertTrue(m.isRegain());
		assertEquals(1, m.getPassed().size());
	}

	@Test
	public void anEmptyHistoryStillNamesTheNextFigure()
	{
		Milestones m = Milestones.of(Collections.emptyList(), 0, DAY);
		assertTrue(m.getPassed().isEmpty());
		assertEquals(1_000_000L, m.getNext());
		assertEquals(1_000_000L, m.getToGo());
	}

	@Test
	public void aPlaceSeenForTheFirstTimePassesNoFigure()
	{
		// 90M in the bank, then a seed vault opened for the first time holding 20M: opening balance, as the
		// day book counts it, so the worth did not pass 100M while the ledger watched.
		java.util.Map<Integer, Long> price = new java.util.HashMap<>();
		price.put(995, 1L);
		price.put(5295, 20_000L);
		Prices prices = id -> price.getOrDefault(id, 0L);
		java.time.ZoneId utc = java.time.ZoneOffset.UTC;
		Ledger ledger = new Ledger();
		ledger.observe(Store.BANK, Collections.singletonMap(995, 90_000_000L));
		ledger.settle(DAY + DAY / 2, prices, utc);
		ledger.observe(Store.SEED_VAULT, Collections.singletonMap(5295, 1_000L));
		ledger.settle(DAY + DAY / 2 + 3 * 3_600_000L, prices, utc);
		assertEquals(110_000_000L, ledger.getTotal());
		Milestones m = Milestones.of(ledger.getHistory(), ledger.getTotal(), DAY + DAY / 2 + 4 * 3_600_000L);
		assertTrue(m.getPassed().isEmpty());
		assertEquals(250_000_000L, m.getNext());
		assertFalse(m.isRegain());
	}
}
