package com.codex.ledger.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class MonthsTest
{
	private static final ZoneId UTC = ZoneOffset.UTC;
	private static final long DAY = 86_400_000L;

	/** Coins in the bank each day from 28 September, one settle at noon. */
	private static Ledger ledger(long... coins)
	{
		long start = LocalDate.of(2026, 9, 28).atStartOfDay(UTC).toInstant().toEpochMilli() + DAY / 2;
		Ledger ledger = new Ledger();
		for (int d = 0; d < coins.length; d++)
		{
			Map<Integer, Long> bank = new HashMap<>();
			bank.put(995, coins[d]);
			ledger.observe(Store.BANK, bank);
			ledger.settle(start + d * DAY, id -> 1L, UTC);
		}
		return ledger;
	}

	@Test
	public void eachMonthIsItsOwnStatementNewestFirst()
	{
		// 28, 29, 30 September, then 1 to 4 October.
		Ledger ledger = ledger(100_000_000L, 110_000_000L, 105_000_000L, 125_000_000L, 125_000_000L, 120_000_000L, 160_000_000L);
		List<Months.Month> months = Months.of(ledger.getDays(), LocalDate.of(2026, 10, 4), 6);
		assertEquals(2, months.size());

		Months.Month october = months.get(0);
		assertEquals("2026-10", october.getMonth().toString());
		assertTrue(october.isCurrent());
		assertEquals(4, october.getPages());
		// From September's close of 105M to 160M now.
		assertEquals(55_000_000L, october.change());
		assertEquals(160_000_000L, october.getClose());
		assertEquals(LocalDate.of(2026, 10, 4), october.getBest());
		assertEquals(40_000_000L, october.getBestChange());

		Months.Month september = months.get(1);
		assertFalse(september.isCurrent());
		assertEquals(3, september.getPages());
		// The first page opens on the bank as first seen, so September made 5M.
		assertEquals(5_000_000L, september.change());
		assertEquals(105_000_000L, september.getClose());
		assertEquals(LocalDate.of(2026, 9, 29), september.getBest());
	}

	@Test
	public void theMonthsAddUpToTheDayBook()
	{
		Ledger ledger = ledger(100_000_000L, 90_000_000L, 95_000_000L, 70_000_000L);
		long sum = 0;
		for (Months.Month m : Months.of(ledger.getDays(), LocalDate.of(2026, 10, 1), 6))
		{
			sum += m.change();
		}
		long pages = 0;
		for (Day d : ledger.getDays())
		{
			pages += d.change();
		}
		assertEquals(pages, sum);
	}

	@Test
	public void aMonthWithNoGainHasNoBestDay()
	{
		Ledger ledger = ledger(100_000_000L, 90_000_000L, 80_000_000L);
		Months.Month september = Months.of(ledger.getDays(), LocalDate.of(2026, 9, 30), 6).get(0);
		assertNull(september.getBest());
		assertEquals(-20_000_000L, september.change());
	}

	@Test
	public void outOfOrderPagesAndOddDatesStillMakeOneMonthEach()
	{
		Ledger ledger = ledger(100_000_000L, 110_000_000L, 120_000_000L, 130_000_000L);
		List<Day> days = ledger.getDays();
		List<Day> shuffled = Arrays.asList(days.get(3), days.get(0), days.get(2), new Day("not a date", 5), days.get(1));
		List<Months.Month> months = Months.of(shuffled, LocalDate.of(2026, 10, 1), 6);
		assertEquals(2, months.size());
		assertEquals(130_000_000L, months.get(0).getClose());
		// The last page of September closes it, whatever order the pages came in.
		assertEquals(120_000_000L, months.get(1).getClose());
		assertEquals(1, Months.of(shuffled, LocalDate.of(2026, 10, 1), 1).size());
		assertTrue(Months.of(Collections.emptyList(), LocalDate.of(2026, 10, 1), 6).isEmpty());
		assertTrue(Months.of(null, LocalDate.of(2026, 10, 1), 6).isEmpty());
	}
}
