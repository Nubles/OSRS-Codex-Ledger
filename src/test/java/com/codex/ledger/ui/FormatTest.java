package com.codex.ledger.ui;

import com.codex.ledger.core.Offer;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FormatTest
{
	@Test
	public void amountsFollowTheSuiteRule()
	{
		// In full below 10,000; whole k below a million; M to one decimal; B to two.
		assertEquals("8,240", Format.amount(8_240));
		assertEquals("10k", Format.amount(10_000));
		assertEquals("41k", Format.amount(41_200));
		assertEquals("96k", Format.amount(95_700));
		assertEquals("412k", Format.amount(412_000));
		assertEquals("1.2M", Format.amount(1_240_000));
		assertEquals("2.4M", Format.amount(2_370_000));
		assertEquals("26.0M", Format.amount(26_000_000));
		assertEquals("98.7M", Format.amount(98_700_000));
		assertEquals("812.4M", Format.amount(812_400_000));
		assertEquals("2.10B", Format.amount(2_100_000_000L));
		// Rounding that reaches the next unit is written in it.
		assertEquals("100k", Format.amount(99_960));
		assertEquals("1.0M", Format.amount(999_600));
		assertEquals("999k", Format.amount(999_499));
		assertEquals("1.00B", Format.amount(999_960_000));
		assertEquals("-45k", Format.coins(-45_200));
		assertEquals("+880k", Format.signed(880_000));
		assertEquals("+880k", Format.signedCompact(880_000));
		// Round goal and milestone figures stay plain.
		assertEquals("1B", Format.mark(1_000_000_000L));
	}

	@Test
	public void columnsUseTheSameRuleAsEverywhereElse()
	{
		// The crest once read "+4.90M" over a page reading "+4.9M".
		assertEquals("+628.1M", Format.signedCompact(628_140_000L));
		assertEquals("-41k", Format.signedCompact(-41_249));
		assertEquals("1.53B", Format.compact(1_534_000_000L));
		assertEquals("8,240", Format.compact(8_240));
		assertEquals(Format.signed(4_900_000), Format.signedCompact(4_900_000));
	}

	@Test
	public void stacksUseTheSuitesLowercaseK()
	{
		assertEquals("450k", Format.stack(450_000));
		assertEquals("99999", Format.stack(99_999));
		assertEquals("12M", Format.stack(12_000_000));
	}

	@Test
	public void billionsCarryTwoPlaces()
	{
		assertEquals("1.50B", Format.coins(1_504_200_000L));
		assertEquals("676.9M", Format.coins(676_900_000L));
		assertEquals("12.40B", Format.coins(12_400_000_000L));
	}

	@Test
	public void figuresSideBySideCarryTheSamePlaces()
	{
		// The card's today, week and month once read "+4.9M", "+26M", "+98.7M".
		assertEquals("+4.9M", Format.signed(4_900_000));
		assertEquals("+26.0M", Format.signed(26_000_000));
		assertEquals("+98.7M", Format.signed(98_700_000));
		assertEquals("+3.2M", Format.signed(3_150_001));
		// Thousands are whole, as in Gear Roadmap and Session Chronicle.
		assertEquals("41k", Format.coins(41_000));
		assertEquals("60k", Format.coins(60_000));
		assertEquals("300k", Format.coins(300_000));
		assertEquals("412k", Format.coins(412_300));
		assertEquals("10k", Format.coins(10_000));
		assertEquals("8,240", Format.coins(8_240));
		// Rounding up to a thousand writes the next unit.
		assertEquals("1.0M", Format.coins(999_500));
		assertEquals("999k", Format.coins(999_499));
		assertEquals("1.00B", Format.coins(999_960_000L));
	}

	@Test
	public void roundFiguresGoByTheirNames()
	{
		assertEquals("1B", Format.mark(1_000_000_000L));
		assertEquals("1.5B", Format.mark(1_500_000_000L));
		assertEquals("750M", Format.mark(750_000_000L));
		assertEquals("10M", Format.mark(10_000_000L));
		assertEquals("1.23B", Format.mark(1_234_567_890L));
		assertEquals("300k", Format.mark(300_000L));
		assertEquals("2.25M", Format.mark(2_250_000L));
	}

	@Test
	public void sharesNeverAddUpPastTheWhole()
	{
		// Rounded one by one these read 51% and 50%.
		assertArrayEquals(new String[]{"51%", "49%"}, Format.shares(new long[]{505, 495}, 1000));
		// Three thirds make 100%, not 99%.
		int[] thirds = Format.sharePoints(new long[]{1, 1, 1}, 3);
		assertEquals(100, thirds[0] + thirds[1] + thirds[2]);
		// Part of a whole adds up to its part; a sliver still shows.
		assertArrayEquals(new String[]{"53%", "19%", "<1%"}, Format.shares(new long[]{530, 190, 2}, 1000));
		// Values over the whole are shared out of their own sum.
		assertArrayEquals(new String[]{"60%", "40%"}, Format.shares(new long[]{600, 400}, 500));
		assertArrayEquals(new String[]{"0%", "0%"}, Format.shares(new long[]{0, 0}, 0));
	}

	@Test
	public void aSliverBesideAlmostAllReadsAsBoth()
	{
		// 99.6% and 0.4% once read "100%" beside "<1%": the two must read as parts of one whole.
		assertArrayEquals(new String[]{">99%", "<1%"}, Format.shares(new long[]{996, 4}, 1000));
		assertArrayEquals(new String[]{">99%", "<1%", "<1%"}, Format.shares(new long[]{9_990, 4, 6}, 10_000));
		// All of it really is 100%, and nothing alongside is hidden by the fix.
		assertArrayEquals(new String[]{"100%", "0%"}, Format.shares(new long[]{1000, 0}, 1000));
		assertArrayEquals(new String[]{"99%", "1%"}, Format.shares(new long[]{990, 10}, 1000));
		// The rows listed may not be all of the whole (the hoard lists the dearest few): one item that is
		// 99.6% of the worth, the rest unlisted, still is not all of it.
		assertArrayEquals(new String[]{">99%"}, Format.shares(new long[]{996}, 1000));
		assertArrayEquals(new String[]{">99%", "0%"}, Format.shares(new long[]{996, 0}, 1000));
		assertArrayEquals(new String[]{"100%"}, Format.shares(new long[]{1000}, 1000));
		// One share on its own, such as a goal's progress, never reads full while short of it.
		assertEquals(">99%", Format.percent(0.996));
		assertEquals("99%", Format.percent(0.994));
		assertEquals("100%", Format.percent(1.0));
		assertEquals("<1%", Format.percent(0.004));
	}

	@Test
	public void offersSayHowFarTheyHaveGot()
	{
		assertEquals("Bought 21,000 of 50,000", LedgerPanel.offerNote(new Offer(1, true, 50_000, 160, 21_000, 3_340_000, true)));
		assertEquals("Sold 1, to collect", LedgerPanel.offerNote(new Offer(1, false, 1, 160, 1, 160, false)));
		assertEquals("Cancelled at 3 of 10", LedgerPanel.offerNote(new Offer(1, true, 10, 160, 3, 480, false)));
	}
}
