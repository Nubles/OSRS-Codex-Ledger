package com.codex.ledger.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * The saved {@code codexledger/ledger} byte for byte, written by the plugin's own Saver, for the
 * plugins that read it (Gear Roadmap, Annals, Session Chronicle, Guardian) to copy as their test
 * fixtures. {@code ledger-seen.json} is an account whose bank has been seen; {@code
 * ledger-unseen.json} one that has only shown its inventory, so "since" is still 0. If either test
 * fails the saved shape has changed: every reader's copy must be checked before the files are.
 */
public class SavedFixtureTest
{
	private static final Gson GSON = new GsonBuilder().create();
	private static final long T0 = 1_790_000_000_000L;

	@Test
	public void aSeenLedgerSavesExactlyAsPinned() throws Exception
	{
		Prices prices = prices(995, 1L, 4151, 1_500_000L);
		Ledger ledger = new Ledger();
		ledger.observe(Store.BANK, items(995, 1_000_000L, 4151, 1L));
		ledger.settle(T0, prices, ZoneOffset.UTC);
		ledger.observe(Store.BANK, items(995, 2_000_000L, 4151, 1L));
		ledger.settle(T0 + 3_600_000L, prices, ZoneOffset.UTC);
		assertEquals(fixture("ledger-seen.json"), new Saver(200_000).save(ledger, GSON::toJson));
	}

	@Test
	public void anUnseenLedgerSavesExactlyAsPinned() throws Exception
	{
		Ledger ledger = new Ledger();
		ledger.observe(Store.INVENTORY, items(995, 25_000L));
		ledger.settle(T0, prices(995, 1L), ZoneOffset.UTC);
		assertEquals(fixture("ledger-unseen.json"), new Saver(200_000).save(ledger, GSON::toJson));
	}

	private static String fixture(String name) throws Exception
	{
		try (InputStream in = SavedFixtureTest.class.getResourceAsStream(name))
		{
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		}
	}

	private static Prices prices(long... pairs)
	{
		Map<Integer, Long> m = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			m.put((int) pairs[i], pairs[i + 1]);
		}
		return id -> m.getOrDefault(id, 0L);
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
}
