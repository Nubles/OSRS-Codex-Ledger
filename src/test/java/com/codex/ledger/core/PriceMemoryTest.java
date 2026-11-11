package com.codex.ledger.core;

import com.google.gson.Gson;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PriceMemoryTest
{
	private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);

	@Test
	public void aDayIsRewrittenNotAddedTwice()
	{
		PriceMemory m = new PriceMemory();
		List<Integer> ranked = Arrays.asList(4151);
		assertTrue(m.record(TODAY, ranked, map(4151, 1_500_000L)));
		assertFalse("the same prices again change nothing", m.record(TODAY, ranked, map(4151, 1_500_000L)));
		assertTrue(m.record(TODAY, ranked, map(4151, 1_510_000L)));
		assertEquals(1, m.getDays().size());
		assertEquals(1_510_000L, m.price(4151, TODAY));
	}

	@Test
	public void itemsWithoutAPriceAreLeftOut()
	{
		PriceMemory m = new PriceMemory();
		m.record(TODAY, Arrays.asList(4151, 1, 2), map(4151, 1_500_000L, 1, 0L));
		assertEquals(1, m.getDays().get(0).getPrices().size());
	}

	@Test
	public void theNewestDayHoldsAtMost600Items()
	{
		List<Integer> ranked = new ArrayList<>();
		Map<Integer, Long> p = new HashMap<>();
		for (int id = 1; id <= 900; id++)
		{
			ranked.add(id);
			p.put(id, 1000L + id);
		}
		PriceMemory m = new PriceMemory();
		m.record(TODAY, ranked, p);
		Map<Integer, Long> kept = m.getDays().get(0).getPrices();
		assertEquals(PriceMemory.MAX_IDS, kept.size());
		assertTrue("the most important come first", kept.containsKey(1) && !kept.containsKey(601));
	}

	@Test
	public void ninetyDaysOfSixHundredItemsFitTheBudget()
	{
		List<Integer> ranked = new ArrayList<>();
		for (int id = 20_000; id < 20_600; id++)
		{
			ranked.add(id);
		}
		PriceMemory m = new PriceMemory();
		for (int age = 120; age >= 0; age--)
		{
			Map<Integer, Long> p = new HashMap<>();
			for (int id : ranked)
			{
				// Long prices, the worst case for size.
				p.put(id, 1_000_000_000L + id + age);
			}
			LocalDate day = TODAY.minusDays(age);
			m.record(day, ranked, p);
			m.trim(day, ranked);
		}
		String json = m.write(new Gson(), 1L);
		assertTrue("saved " + json.length() + " characters", json.length() <= PriceMemory.BUDGET);
		assertTrue("the estimate keeps to the real size", Math.abs(json.length() - m.size()) < 200);
		// The newest day is whole; the oldest kept is no more than 90 days back.
		assertEquals(PriceMemory.MAX_IDS, m.getDays().get(m.getDays().size() - 1).getPrices().size());
		LocalDate oldest = LocalDate.parse(m.getDays().get(0).getDate());
		assertFalse(oldest.isBefore(TODAY.minusDays(PriceMemory.DAYS - 1)));
		assertTrue("history reaches back weeks", oldest.isBefore(TODAY.minusDays(60)));
		// The goal item at the head of the ranking keeps its whole history.
		assertEquals(m.getDays().size(), m.history(20_000).size());
	}

	@Test
	public void olderDaysAreKeptOneAWeek()
	{
		List<Integer> ranked = Arrays.asList(4151);
		PriceMemory m = new PriceMemory();
		for (int age = 89; age >= 0; age--)
		{
			m.record(TODAY.minusDays(age), ranked, map(4151, 1_500_000L + age));
		}
		m.trim(TODAY, ranked);
		int daily = 0;
		for (PriceMemory.Entry e : m.getDays())
		{
			if (!LocalDate.parse(e.getDate()).isBefore(TODAY.minusDays(14)))
			{
				daily++;
			}
		}
		assertEquals(15, daily);
		// 75 older days, about one a week.
		int weekly = m.getDays().size() - daily;
		assertTrue("weekly " + weekly, weekly >= 10 && weekly <= 12);
	}

	@Test
	public void daysInTheFutureOrPastTheWindowGo()
	{
		List<Integer> ranked = Arrays.asList(4151);
		PriceMemory m = new PriceMemory();
		m.record(TODAY.plusDays(3), ranked, map(4151, 1L));
		m.record(TODAY.minusDays(200), ranked, map(4151, 2L));
		m.record(TODAY, ranked, map(4151, 3L));
		m.trim(TODAY, ranked);
		assertEquals(1, m.getDays().size());
	}

	@Test
	public void damagedOrLaterSavesReadAsNothingNeverAThrow()
	{
		Gson gson = new Gson();
		for (String bad : new String[]{null, "", "  ", "null", "[]", "{", "{\"version\":2,\"days\":[]}", "{\"version\":\"1\"}",
			"{\"days\":[]}", "42", "\"text\""})
		{
			assertNull(bad, PriceMemory.read(gson, bad));
		}
		PriceMemory m = PriceMemory.read(gson, "{\"version\":1,\"days\":[null,{\"d\":\"nope\",\"p\":{\"1\":2}},{\"d\":\"2026-11-11\",\"p\":{\"x\":1,\"4151\":\"a\",\"385\":640,\"-3\":5}},7]}");
		assertEquals(1, m.getDays().size());
		assertEquals(1, m.getDays().get(0).getPrices().size());
		assertEquals(640L, m.price(385, TODAY));
	}

	@Test
	public void kitsAreReadFromQuartermastersSave() throws IOException
	{
		Set<Integer> kits = Interest.kitItems(new Gson(), PricesV1ShapeTest.resource("quartermaster-kitsV1.json"));
		// The player's own Konar kit counts: sharks, prayer potions, death runes and the spade.
		assertTrue(kits.contains(385));
		assertTrue(kits.contains(2434));
		assertTrue(kits.contains(560));
		assertTrue(kits.contains(952));
		// The Vorkath preset was never chosen, so its ranging potions do not.
		assertFalse(kits.contains(22461));
		for (String bad : new String[]{null, "", "{", "[]", "{\"kits\":7}", "{\"kits\":[{\"needs\":[{\"items\":{\"x\":1}}]}]}"})
		{
			assertTrue(bad, Interest.kitItems(new Gson(), bad).isEmpty());
		}
	}

	@Test
	public void theRankingPutsGoalsAndKitsBeforeWhatIsHeld()
	{
		Map<Integer, Long> held = new HashMap<>();
		held.put(995, 10_000_000L);
		held.put(385, 100L);
		held.put(4151, 1L);
		Map<Integer, Long> price = map(385, 640L, 4151, 1_500_000L);
		List<Integer> ranked = Interest.rank(Arrays.asList(20997), Arrays.asList(2434), Arrays.asList(12934), held,
			id -> price.getOrDefault(id, 0L), Arrays.asList(5295, 385));
		assertEquals(Arrays.asList(20997, 2434, 12934, 4151, 385, 5295), ranked);
	}

	private static Map<Integer, Long> map(long... pairs)
	{
		Map<Integer, Long> m = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			m.put((int) pairs[i], pairs[i + 1]);
		}
		return m;
	}
}
