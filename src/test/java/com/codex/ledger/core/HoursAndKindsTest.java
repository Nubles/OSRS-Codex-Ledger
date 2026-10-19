package com.codex.ledger.core;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class HoursAndKindsTest
{
	private static final long T0 = 1_790_000_000_000L - 1_790_000_000_000L % 86_400_000L + 8 * 3_600_000L;
	private static final int WHIP = 4151;
	private static final int SHARK = 385;
	private static final int LOGS = 1513;
	private static final Map<Integer, Long> PRICE = new HashMap<>();

	static
	{
		PRICE.put(995, 1L);
		PRICE.put(WHIP, 1_500_000L);
		PRICE.put(SHARK, 700L);
		PRICE.put(LOGS, 300L);
	}

	private static final Prices PRICES = id -> PRICE.getOrDefault(id, 0L);

	private static Kind kind(int id)
	{
		return id == WHIP ? Kind.GEAR : id == SHARK ? Kind.SUPPLIES : Kind.MATERIALS;
	}

	@Test
	public void timeLoggedInCountsTicksAndSkipsGaps()
	{
		Ledger ledger = opened();
		long t = T0;
		assertFalse("the first tick only starts the clock", ledger.played(t, ZoneOffset.UTC));
		for (int i = 0; i < 100; i++)
		{
			t += 600;
			assertTrue(ledger.played(t, ZoneOffset.UTC));
		}
		// A loading screen or a logout of a minute is not play.
		t += 60_000;
		assertFalse(ledger.played(t, ZoneOffset.UTC));
		t += 600;
		ledger.played(t, ZoneOffset.UTC);
		ledger.pause();
		t += 600;
		assertFalse("after a pause the clock starts again", ledger.played(t, ZoneOffset.UTC));
		assertEquals(101 * 600, ledger.day(date(T0)).getPlayedMs());
	}

	@Test
	public void hourlyPlayLeavesMarketAndUntimedPagesOut()
	{
		Ledger ledger = opened();
		long t = T0;
		ledger.played(t, ZoneOffset.UTC);
		// Half an hour of play that brings in 600 logs (+180k), while the whip's price rises 100k.
		for (int i = 1; i <= 3_000; i++)
		{
			t += 600;
			ledger.played(t, ZoneOffset.UTC);
		}
		PRICE.put(WHIP, 1_600_000L);
		try
		{
			ledger.observe(Store.INVENTORY, items(LOGS, 600L));
			ledger.settle(t, PRICES, ZoneOffset.UTC);
			Report today = Report.of(ledger, 1, date(T0), 5);
			assertEquals(30 * 60_000L, today.getPlayedMs());
			assertTrue(today.hasHourly());
			assertEquals(180_000, today.getPlay());
			assertEquals(360_000, today.perHour());
			Report page = Report.page(ledger.day(date(T0)), 5);
			assertEquals(360_000, page.perHour());

			// A page with no time kept (written before the time was) never dilutes the hourly figure.
			Report week = Report.of(ledger, 7, date(T0), 5);
			assertEquals(360_000, week.perHour());
			Ledger brief = opened();
			brief.played(T0, ZoneOffset.UTC);
			brief.played(T0 + 600, ZoneOffset.UTC);
			assertFalse("a few seconds give no hourly figure", Report.of(brief, 1, date(T0), 5).hasHourly());
			assertEquals(0, Report.of(brief, 1, date(T0), 5).perHour());
		}
		finally
		{
			PRICE.put(WHIP, 1_500_000L);
		}
	}

	@Test
	public void worthIsToldApartByKindAndItsChangeOverTheSpan()
	{
		Ledger ledger = new Ledger();
		ledger.setKinds(HoursAndKindsTest::kind);
		ledger.observe(Store.BANK, items(995, 2_000_000L, SHARK, 100L, LOGS, 1_000L));
		ledger.settle(T0, PRICES, ZoneOffset.UTC);
		assertArrayEquals(new long[]{2_000_000, 0, 70_000, 300_000}, ledger.getDays().get(0).getKinds());

		// Next day the coins buy a whip: cash turns into gear.
		long t1 = T0 + 86_400_000L;
		ledger.observe(Store.BANK, items(995, 500_000L, WHIP, 1L, SHARK, 100L, LOGS, 1_000L));
		ledger.settle(t1, PRICES, ZoneOffset.UTC);
		Report today = Report.of(ledger, 1, date(t1), 5);
		assertArrayEquals(new long[]{500_000, 1_500_000, 70_000, 300_000}, today.getKinds());
		assertArrayEquals(new long[]{-1_500_000, 1_500_000, 0, 0}, today.kindChange());
		assertNull("nothing before the ledger began to measure from", Report.of(ledger, 0, date(t1), 5).kindChange());

		// Without a way to tell kinds apart, pages carry none, and nothing breaks.
		Ledger plain = opened();
		assertNull(plain.getDays().get(0).getKinds());
		assertNull(Report.of(plain, 1, date(T0), 5).getKinds());
	}

	private static Ledger opened()
	{
		Ledger ledger = new Ledger();
		ledger.observe(Store.BANK, items(995, 1_000_000L, WHIP, 1L));
		ledger.observe(Store.INVENTORY, items());
		ledger.settle(T0, PRICES, ZoneOffset.UTC);
		return ledger;
	}

	private static LocalDate date(long t)
	{
		return java.time.Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate();
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

	@Test
	public void itemsAreToldApartFromWhatTheGameSays()
	{
		assertEquals(Kind.CASH, Kinds.of(995, "Coins", null, false, -1, true));
		assertEquals(Kind.CASH, Kinds.of(13204, "Platinum token", null, false, -1, true));
		assertEquals(Kind.GEAR, Kinds.of(WHIP, "Abyssal whip", new String[]{null, "Wield", null, null, "Drop"}, true, 3, false));
		assertEquals(Kind.SUPPLIES, Kinds.of(11212, "Dragon arrow", new String[]{null, "Wield"}, true, 13, true));
		assertEquals(Kind.SUPPLIES, Kinds.of(11230, "Dragon dart", new String[]{null, "Wield"}, true, 3, true));
		assertEquals(Kind.SUPPLIES, Kinds.of(SHARK, "Shark", new String[]{"Eat", null, null, null, "Drop"}, false, -1, false));
		assertEquals(Kind.SUPPLIES, Kinds.of(2434, "Prayer potion(4)", new String[]{"Drink"}, false, -1, false));
		assertEquals(Kind.SUPPLIES, Kinds.of(8013, "Teleport to house", new String[]{"Break"}, false, -1, false));
		assertEquals(Kind.SUPPLIES, Kinds.of(560, "Death rune", null, false, -1, true));
		assertEquals(Kind.MATERIALS, Kinds.of(LOGS, "Magic logs", null, false, -1, false));
		assertEquals(Kind.MATERIALS, Kinds.of(536, "Dragon bones", new String[]{"Bury"}, false, -1, false));
	}
}
