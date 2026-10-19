package com.codex.ledger.ui;

import com.codex.ledger.core.BankCopy;
import com.codex.ledger.core.Ledger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Codex Bank Steward's copy of the bank ({@code codexbanksteward/bankV1}), read for the estimate shown
 * before the ledger has seen the bank. Whatever is saved there, the estimate must neither throw nor
 * claim a worth no bank could have.
 */
public class DamagedBankCopyTest
{
	private static final Gson GSON = new GsonBuilder().create();
	private static final long NOW = 1_790_000_000_000L;

	@Test
	public void anythingUnreadableIsNoCopy()
	{
		String good = "{\"version\":1,\"savedAt\":1789990000000,\"items\":{\"995\":1000,\"4151\":2},\"truncated\":false}";
		String[] saves = {null, "", "  ", "null", "{", "[]", "42", "\"bank\"", "garbage", "{\"items\":[]}", "{\"items\":\"x\"}",
			"{\"items\":null}", "{\"items\":{}}", good.substring(0, good.length() / 2), good.substring(0, good.length() - 1)};
		for (String save : saves)
		{
			BankCopy copy = survives(save);
			assertTrue(save, copy == null || !copy.getItems().isEmpty());
		}
		assertNotNull(survives(good));
	}

	@Test
	public void quantitiesAndDatesNoBankCouldHaveAreShrugged()
	{
		String[] saves = {
			"{\"version\":1,\"savedAt\":1,\"items\":{\"995\":9223372036854775807,\"4151\":9223372036854775807}}",
			"{\"version\":1,\"savedAt\":-5,\"items\":{\"995\":1e30,\"4151\":-3,\"x\":4,\"-7\":2,\"385\":\"many\"}}",
			"{\"version\":99,\"savedAt\":9223372036854775807,\"items\":{\"995\":2147483647},\"truncated\":\"yes\",\"tabs\":[1]}",
			"{\"savedAt\":\"yesterday\",\"items\":{\"995\":1.5,\"4151\":null,\"385\":[],\"2434\":{}}}",
		};
		for (String save : saves)
		{
			BankCopy copy = survives(save);
			if (copy != null)
			{
				BankCopy.Estimate e = copy.estimate(id -> id == 995 ? 1L : 2_147_483_647L);
				assertTrue(save + " " + e.getBank(), e.getBank() >= 0);
			}
		}
		// A coin stack is at most 2,147,483,647 however many are claimed.
		BankCopy max = survives(saves[0]);
		assertEquals(2_147_483_647L, (long) max.getItems().get(995));
		assertNull(survives("{\"items\":{\"0\":5,\"-1\":5}}"));
		// A copy dated in the future says nothing of when it was taken rather than "just now".
		assertEquals("Your bank as Bank Steward last saw it", LedgerPanel.copySeen(Long.MAX_VALUE, NOW));
		assertEquals("Your bank as Bank Steward last saw it", LedgerPanel.copySeen(0, NOW));
		assertEquals("Your bank as Bank Steward last saw it, 2h ago", LedgerPanel.copySeen(NOW - 7_200_000L, NOW));
	}

	private static BankCopy survives(String save)
	{
		try
		{
			BankCopy copy = BankCopy.read(GSON, save);
			LedgerPanel panel = new LedgerPanel((id, cb) -> null);
			panel.setEstimate(copy == null ? null : copy.estimate(id -> id == 995 ? 1L : 1_000_000L));
			panel.update(new Ledger(), Collections.emptyMap(), "Zezima", LedgerPanel.State.WAITING_FOR_BANK, NOW);
			panel.showTab(0);
			DamagedSaveTest.paint(panel);
			return copy;
		}
		catch (RuntimeException e)
		{
			throw new AssertionError("reading " + save + " threw " + e, e);
		}
	}

}
