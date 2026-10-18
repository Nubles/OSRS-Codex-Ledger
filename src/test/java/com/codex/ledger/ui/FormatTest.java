package com.codex.ledger.ui;

import com.codex.ledger.core.Offer;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FormatTest
{
	@Test
	public void compactKeepsToThreeFigures()
	{
		assertEquals("+628M", Format.signedCompact(628_140_000L));
		assertEquals("-41.2k", Format.signedCompact(-41_249));
		assertEquals("1.53B", Format.compact(1_534_000_000L));
		assertEquals("8,240", Format.compact(8_240));
		assertEquals("12.4M", Format.compact(12_400_000));
	}

	@Test
	public void billionsCarryAnotherFigure()
	{
		assertEquals("1.504B", Format.coins(1_504_200_000L));
		assertEquals("676.9M", Format.coins(676_900_000L));
		assertEquals("12.4B", Format.coins(12_400_000_000L));
	}

	@Test
	public void offersSayHowFarTheyHaveGot()
	{
		assertEquals("Bought 21,000 of 50,000", LedgerPanel.offerNote(new Offer(1, true, 50_000, 160, 21_000, 3_340_000, true)));
		assertEquals("Sold 1, to collect", LedgerPanel.offerNote(new Offer(1, false, 1, 160, 1, 160, false)));
		assertEquals("Cancelled at 3 of 10", LedgerPanel.offerNote(new Offer(1, true, 10, 160, 3, 480, false)));
	}
}
