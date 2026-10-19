package com.codex.ledger.core;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Codex Bank Steward's copy of the bank, read for the estimate shown before the ledger sees the bank.
 * {@code bank-steward-bankV1.json} is a byte-for-byte copy of the fixture Bank Steward's own
 * BankSnapshotTest proves its writer produces.
 */
public class BankCopyTest
{
	private static final Gson GSON = new Gson();

	private static String fixture() throws Exception
	{
		try (java.io.InputStream in = BankCopyTest.class.getResourceAsStream("bank-steward-bankV1.json"))
		{
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
	}

	@Test
	public void readsTheBankStewardsOwnCodeWrites() throws Exception
	{
		BankCopy copy = BankCopy.read(GSON, fixture());
		assertEquals(1_760_000_000_000L, copy.getSavedAt());
		assertFalse(copy.isTruncated());
		assertEquals(6, copy.getItems().size());
		assertEquals(1_234_567L, (long) copy.getItems().get(995));
		assertEquals(240L, (long) copy.getItems().get(385));
	}

	@Test
	public void pricesItAsTheLedgerWouldWithUnpricedItemsAsNothing() throws Exception
	{
		Map<Integer, Long> prices = new HashMap<>();
		prices.put(995, 1L);
		prices.put(385, 300L);
		prices.put(2434, 9_000L);
		prices.put(26950, 1_000_000L);
		BankCopy.Estimate e = BankCopy.read(GSON, fixture()).estimate(id -> prices.getOrDefault(id, 0L));
		assertEquals(1_234_567 + 240 * 300 + 30 * 9_000 + 1_000_000, e.getBank());
		assertEquals(6, e.getKinds());
		assertEquals(1_760_000_000_000L, e.getSavedAt());
	}

	@Test
	public void aShortCopySaysSo()
	{
		BankCopy copy = BankCopy.read(GSON, "{\"version\":1,\"savedAt\":5,\"items\":{\"995\":10},\"truncated\":true}");
		assertTrue(copy.isTruncated());
	}

	@Test
	public void anythingElseIsNoCopy()
	{
		assertNull(BankCopy.read(GSON, null));
		assertNull(BankCopy.read(GSON, ""));
		assertNull(BankCopy.read(GSON, "[1,2]"));
		assertNull(BankCopy.read(GSON, "{\"items\":[1]}"));
		assertNull(BankCopy.read(GSON, "{\"items\":{}}"));
		assertNull(BankCopy.read(GSON, "not json at all {"));
		// Odd entries are skipped, the rest still read.
		BankCopy copy = BankCopy.read(GSON, "{\"items\":{\"x\":3,\"-4\":2,\"995\":\"many\",\"4151\":1,\"11802\":0}}");
		assertEquals(1, copy.getItems().size());
		assertEquals(0, copy.getSavedAt());
	}
}
