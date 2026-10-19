package com.codex.ledger.core;

import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AlchemyTest
{
	private static final int PLATEBODY = 1127;
	private static final int ARROW_SHAFTS = 52;
	private static final int WHIP = 4151;
	private static final int UNTRADEABLE = 6570;
	private static final int TRINKET = 1704;

	@Test
	public void weighsAlchingAfterTheRuneAgainstSellingAfterTax()
	{
		Map<Integer, Long> held = new HashMap<>();
		held.put(Alchemy.COINS, 5_000_000L);
		held.put(Alchemy.NATURE_RUNE, 2_000L);
		held.put(PLATEBODY, 10L);
		held.put(ARROW_SHAFTS, 1_000L);
		held.put(WHIP, 1L);
		held.put(UNTRADEABLE, 1L);
		held.put(TRINKET, 2L);
		Map<Integer, Long> price = new HashMap<>();
		price.put(Alchemy.NATURE_RUNE, 100L);
		price.put(PLATEBODY, 38_000L);
		price.put(ARROW_SHAFTS, 40L);
		price.put(WHIP, 1_300_000L);
		price.put(TRINKET, 10_000L);
		Map<Integer, Long> alch = new HashMap<>();
		alch.put(PLATEBODY, 39_000L);
		alch.put(ARROW_SHAFTS, 200L);
		alch.put(WHIP, 72_000L);
		alch.put(UNTRADEABLE, 50_000L);
		alch.put(TRINKET, 12_000L);
		Alchemy a = Alchemy.of(held, id -> price.getOrDefault(id, 0L), id -> alch.getOrDefault(id, 0L));

		assertEquals(2, a.getLines().size());
		// Under 50 coins there is no tax: 100 a cast against 40 sold.
		Alchemy.Line shafts = a.getLines().get(0);
		assertEquals(ARROW_SHAFTS, shafts.getItemId());
		assertEquals(100, shafts.getAlch());
		assertEquals(40, shafts.getSale());
		assertEquals(60_000, shafts.gain());
		// 38,900 a cast against 38,000 less 2% tax.
		Alchemy.Line body = a.getLines().get(1);
		assertEquals(38_900, body.getAlch());
		assertEquals(37_240, body.getSale());
		assertEquals(16_600, body.gain());
		assertEquals(76_600, a.getGain());
		assertEquals(1_010, a.getCasts());
		// The whip sells for far more; the untradeable cannot be sold to compare; two trinkets make too
		// little (4,200) to be worth the casts; coins and the runes themselves are never weighed.
		assertTrue(a.getLines().stream().noneMatch(l -> l.getItemId() == WHIP || l.getItemId() == UNTRADEABLE
			|| l.getItemId() == TRINKET || l.getItemId() == Alchemy.COINS));
	}

	@Test
	public void nothingBetterAlchedIsEmpty()
	{
		Map<Integer, Long> held = new HashMap<>();
		held.put(WHIP, 1L);
		Alchemy a = Alchemy.of(held, id -> 1_300_000L, id -> 72_000L);
		assertTrue(a.isEmpty());
		assertEquals(0, a.getGain());
	}
}
