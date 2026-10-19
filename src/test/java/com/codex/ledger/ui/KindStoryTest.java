package com.codex.ledger.ui;

import com.codex.ledger.core.Kind;
import com.codex.ledger.core.Ledger;
import com.codex.ledger.core.Prices;
import com.codex.ledger.core.Report;
import com.codex.ledger.core.Store;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class KindStoryTest
{
	private static final long T0 = 1_790_000_000_000L - 1_790_000_000_000L % 86_400_000L + 8 * 3_600_000L;
	private static final long DAY = 86_400_000L;
	private static final int WHIP = 4151;
	private static final int BOW = 20997;

	private static Kind kind(int id)
	{
		return id == WHIP || id == BOW ? Kind.GEAR : Kind.MATERIALS;
	}

	@Test
	public void coinsSpentWhileGearRisesInPriceAreNotSaidToHaveBoughtGear()
	{
		Map<Integer, Long> price = new HashMap<>();
		price.put(995, 1L);
		price.put(BOW, 1_500_000_000L);
		Prices prices = id -> price.getOrDefault(id, 0L);
		Ledger ledger = new Ledger();
		ledger.setKinds(KindStoryTest::kind);
		ledger.observe(Store.BANK, items(995, 100_000_000L, BOW, 1L));
		ledger.settle(T0, prices, ZoneOffset.UTC);

		// Next day 20M in coins goes on things used up, while the bow already owned rises 30M in price.
		price.put(BOW, 1_530_000_000L);
		ledger.observe(Store.BANK, items(995, 80_000_000L, BOW, 1L));
		ledger.settle(T0 + DAY, prices, ZoneOffset.UTC);
		Report day = Report.of(ledger, 1, date(T0 + DAY), 5);
		assertNull(LedgerPanel.kindStory(day.getKinds(), day.kindFlow()));
	}

	@Test
	public void coinsThatBecomeGearAreNamed()
	{
		Map<Integer, Long> price = new HashMap<>();
		price.put(995, 1L);
		price.put(WHIP, 1_500_000L);
		Prices prices = id -> price.getOrDefault(id, 0L);
		Ledger ledger = new Ledger();
		ledger.setKinds(KindStoryTest::kind);
		ledger.observe(Store.BANK, items(995, 5_000_000L));
		ledger.settle(T0, prices, ZoneOffset.UTC);
		ledger.observe(Store.BANK, items(995, 3_500_000L, WHIP, 1L));
		ledger.settle(T0 + 60_000, prices, ZoneOffset.UTC);
		// Measured within the ledger's first day, with no earlier page to compare against.
		Report day = Report.of(ledger, 1, date(T0), 5);
		assertEquals("About 1.5M in coins went into gear.", LedgerPanel.kindStory(day.getKinds(), day.kindFlow()));
	}

	private static LocalDate date(long t)
	{
		return Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate();
	}

	private static Map<Integer, Long> items(Object... pairs)
	{
		Map<Integer, Long> m = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			m.put(((Number) pairs[i]).intValue(), ((Number) pairs[i + 1]).longValue());
		}
		return m;
	}
}
