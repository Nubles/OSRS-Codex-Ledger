package com.codex.ledger.ui;

import com.codex.ledger.core.Ledger;
import com.codex.ledger.core.Months;
import com.codex.ledger.core.Store;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class MonthRowTest
{
	@Test
	public void aMonthReadsLikeALineOfAStatement()
	{
		long start = LocalDate.of(2026, 9, 29).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 43_200_000L;
		Ledger ledger = new Ledger();
		long[] coins = {100_000_000L, 104_000_000L, 130_000_000L};
		for (int d = 0; d < coins.length; d++)
		{
			Map<Integer, Long> bank = new HashMap<>();
			bank.put(995, coins[d]);
			ledger.observe(Store.BANK, bank);
			ledger.settle(start + d * 86_400_000L, id -> 1L, ZoneOffset.UTC);
		}
		List<Months.Month> months = Months.of(ledger.getDays(), LocalDate.of(2026, 10, 1), 5);
		assertEquals("October 2026", MonthRow.title(months.get(0)));
		assertEquals("best 1 Oct +26.0M", MonthRow.best(months.get(0)));
		String tip = MonthRow.tip(months.get(1));
		assertTrue(tip, tip.startsWith("September 2026: play +4.0M, market 0. Closed on 104.0M, over 2 days in the day book."));
		assertTrue(tip, tip.endsWith("Best day Wed 30 Sep, +4.0M."));
		assertTrue(MonthRow.tip(months.get(0)).startsWith("October 2026 so far: "));
	}
}
