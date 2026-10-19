package com.codex.ledger.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class EarningsTest
{
	private static final long HOUR = 3_600_000L;
	private static final long DAY = 24 * HOUR;
	private static final long NOW = 1_790_000_000_000L;

	@Test
	public void readsChroniclesJournalAsItSavesIt()
	{
		// Codex Session Chronicle's journal as its own code writes it (pinned by its ActivitiesTest): a list
		// of filed sessions with "startedAt", "playedMs", "activity", and the net worth this ledger had
		// as each began ("worthStart") and ended ("worthNow"), among much else that is ignored here.
		String journal = "[{\"startedAt\":" + (NOW - DAY) + ",\"lastSeenAt\":" + (NOW - DAY + HOUR) + ",\"endedAt\":" + (NOW - DAY + HOUR)
			+ ",\"playedMs\":" + HOUR + ",\"player\":\"Zezima\",\"skills\":{\"Ranged\":{\"skill\":\"Ranged\",\"startXp\":0,\"xp\":140000}},"
			+ "\"loot\":{},\"kills\":{\"vorkath\":{\"name\":\"Vorkath\",\"looted\":20,\"firstKc\":0,\"lastKc\":0,\"event\":false,"
			+ "\"tasks\":{\"\":20}}},\"moments\":[],\"deaths\":0,\"otherLoot\":0,\"otherDrops\":0,\"activity\":\"Vorkath\","
			+ "\"worthStart\":1000000000,\"worthNow\":1002500000,\"tripCount\":0,\"tripMs\":0,\"bestTripMs\":0},"
			+ "{\"startedAt\":" + (NOW - 2 * DAY) + ",\"playedMs\":" + 2 * HOUR + ",\"activity\":\"Mining\",\"worthStart\":900000000,"
			+ "\"worthNow\":900300000},"
			+ "{\"startedAt\":" + (NOW - 3 * DAY) + ",\"playedMs\":" + HOUR + ",\"activity\":\"vorkath\",\"worthStart\":1000000000,"
			+ "\"worthNow\":1001500000},"
			+ "{\"startedAt\":" + (NOW - 4 * DAY) + ",\"playedMs\":" + HOUR + ",\"activity\":\"Fishing\"},"
			+ "{\"startedAt\":" + (NOW - 5 * DAY) + ",\"playedMs\":600000,\"activity\":\"Agility\",\"worthStart\":1,\"worthNow\":2},"
			+ "{\"startedAt\":" + (NOW - 40 * DAY) + ",\"playedMs\":" + HOUR + ",\"activity\":\"Zulrah\",\"worthStart\":1,\"worthNow\":9}]";
		List<Earnings.Logged> sessions = new Gson().fromJson(journal, new TypeToken<List<Earnings.Logged>>()
		{
		}.getType());
		Earnings e = Earnings.of(sessions, NOW);
		assertEquals(2, e.getLines().size());
		Earnings.Line vork = e.getLines().get(0);
		assertEquals("Vorkath", vork.getName());
		assertEquals("names are matched whatever their case", 2, vork.getSessions());
		assertEquals(4_000_000, vork.getChange());
		assertEquals(2_000_000, vork.perHour());
		Earnings.Line mining = e.getLines().get(1);
		assertEquals(150_000, mining.perHour());
		// Fishing had no worth from the ledger, Agility was too short, Zulrah too long ago.
		assertEquals(3, e.getDays());
	}

	@Test
	public void aSessionInTheJournalTwiceCountsOnce()
	{
		Earnings.Logged one = new Earnings.Logged(NOW - DAY, HOUR, 0L, 1_000_000L, "Vorkath");
		Earnings.Logged again = new Earnings.Logged(NOW - DAY, HOUR, 0L, 1_000_000L, "Vorkath");
		Earnings e = Earnings.of(Arrays.asList(one, again), NOW);
		assertEquals(1, e.getLines().get(0).getSessions());
		assertTrue(Earnings.of(Arrays.asList(new Earnings.Logged(NOW, HOUR, null, null, "Vorkath")), NOW).isEmpty());
	}
}
