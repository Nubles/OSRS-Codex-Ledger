package com.codex.ledger.ui;

import com.codex.ledger.core.Earnings;
import com.codex.ledger.core.Ledger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Codex Session Chronicle's journal ({@code codexsessionchronicle/journal}) is another plugin's save,
 * read for "what pays": whatever it holds, a later version's shape, a broken session or numbers no
 * session could have, the ledger must neither throw nor show figures that are not true, and one bad
 * session must not hide the good ones.
 */
public class DamagedJournalTest
{
	private static final Gson GSON = new GsonBuilder().create();
	private static final JsonParser PARSER = new JsonParser();
	private static final long DAY = 86_400_000L;
	private static final long HOUR = 3_600_000L;
	private static final long NOW = 1_790_000_000_000L + 20 * DAY;

	/** Three sessions of an hour or two, each gaining worth. */
	private static JsonArray good()
	{
		JsonArray list = new JsonArray();
		list.add(session(NOW - 3 * DAY, 2 * HOUR, 1_000_000_000L, 1_004_000_000L, "Vorkath"));
		list.add(session(NOW - 2 * DAY, HOUR, 1_004_000_000L, 1_005_000_000L, "Mining"));
		list.add(session(NOW - DAY, 3 * HOUR, 1_005_000_000L, 1_011_000_000L, "Vorkath"));
		return list;
	}

	private static JsonObject session(long startedAt, long playedMs, long worthStart, long worthNow, String activity)
	{
		JsonObject s = new JsonObject();
		s.addProperty("startedAt", startedAt);
		s.addProperty("lastSeenAt", startedAt + playedMs);
		s.addProperty("playedMs", playedMs);
		s.addProperty("worthStart", worthStart);
		s.addProperty("worthNow", worthNow);
		s.addProperty("activity", activity);
		return s;
	}

	@Test
	public void aJournalThatCannotBeReadIsNoJournal()
	{
		String whole = good().toString();
		List<String> saves = new ArrayList<>();
		Collections.addAll(saves, null, "", " ", "null", "[", "]", "{}", "{\"version\":2,\"sessions\":[]}", "42", "\"x\"",
			"[[[]]]", "garbage", whole.substring(0, whole.length() / 2), whole.substring(0, whole.length() - 1));
		for (String save : saves)
		{
			survives(save);
		}
	}

	@Test
	public void oneBrokenSessionLeavesTheOthers()
	{
		String[] bad = {"null", "\"x\"", "[]", "{}", "1e30", "-1", "true", "9223372036854775807", "-9223372036854775808"};
		for (String field : new String[]{"startedAt", "playedMs", "worthStart", "worthNow", "activity"})
		{
			for (String value : bad)
			{
				JsonArray list = good();
				list.get(1).getAsJsonObject().add(field, PARSER.parse(value));
				Earnings e = survives(list.toString());
				// Vorkath's two sessions are still counted whatever became of Mining's.
				assertEquals(field + " = " + value, "Vorkath", e.getLines().get(0).getName());
				assertEquals(field + " = " + value, 2, e.getLines().get(0).getSessions());
			}
		}
		for (String value : bad)
		{
			JsonArray list = good();
			list.set(1, PARSER.parse(value));
			Earnings e = survives(list.toString());
			assertEquals(value, 2, e.getLines().get(0).getSessions());
		}
	}

	@Test
	public void numbersNoSessionCouldHaveAreLeftOut()
	{
		long[][] sessions = {
			// Played for longer than the time since it began, or for ever.
			{NOW - HOUR, 50 * HOUR, 1_000_000L, 2_000_000L},
			{NOW - DAY, Long.MAX_VALUE, 1_000_000L, 2_000_000L},
			// Worth below nothing, or more than every coin in the game.
			{NOW - DAY, HOUR, -5_000_000L, 2_000_000L},
			{NOW - DAY, HOUR, 0, Long.MAX_VALUE},
			{NOW - DAY, HOUR, Long.MIN_VALUE, Long.MAX_VALUE},
		};
		for (long[] s : sessions)
		{
			JsonArray list = good();
			list.add(session(s[0], s[1], s[2], s[3], "Zulrah"));
			Earnings e = survives(list.toString());
			for (Earnings.Line line : e.getLines())
			{
				assertTrue(line.getName() + " " + line.perHour(), !line.getName().equals("Zulrah"));
				assertTrue(line.getPlayedMs() > 0);
			}
		}
	}

	@Test
	public void aLaterVersionsFieldsAreIgnored()
	{
		JsonArray list = good();
		for (JsonElement s : list)
		{
			s.getAsJsonObject().addProperty("version", 7);
			s.getAsJsonObject().add("sailing", PARSER.parse("{\"knots\":[1,2]}"));
		}
		Earnings e = survives(list.toString());
		assertEquals(2, e.getLines().size());
	}

	private static Earnings survives(String save)
	{
		try
		{
			List<Earnings.Logged> read = Earnings.read(GSON, save);
			Earnings earnings = Earnings.of(read, NOW);
			LedgerPanel panel = new LedgerPanel((id, cb) -> null);
			panel.setEarnings(earnings);
			Ledger ledger = new Ledger();
			ledger.observe(com.codex.ledger.core.Store.BANK, DamagedSaveTest.items(995, 1_000_000_000L));
			ledger.settle(NOW, id -> 1L, java.time.ZoneOffset.UTC);
			panel.update(ledger, Collections.emptyMap(), "Zezima", LedgerPanel.State.READY, NOW);
			panel.showTab(0);
			DamagedSaveTest.paint(panel);
			return earnings;
		}
		catch (RuntimeException e)
		{
			throw new AssertionError("reading " + save + " threw " + e, e);
		}
	}
}
