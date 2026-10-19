package com.codex.ledger.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SafeDeathTest
{
	@Test
	public void raidsAndSafeMinigamesAreSafe()
	{
		// The Inferno, the Colosseum, the Fight Caves, Tombs of Amascut's Wardens, Castle Wars.
		for (int region : new int[]{9043, 7216, 9551, 15184, 9520})
		{
			assertTrue(String.valueOf(region), SafeDeath.isSafe(region, false, 0));
		}
		// Inside the Chambers or the Theatre, whatever the region reads.
		assertTrue(SafeDeath.isSafe(-1, true, 0));
		assertTrue(SafeDeath.isSafe(-1, false, 2));
		assertTrue(SafeDeath.isSafe(-1, false, 3));
	}

	@Test
	public void bossesAndTheWildernessAreNot()
	{
		// Vorkath's island, Zulrah's shrine, the Wilderness by Callisto, merely in a Theatre party.
		assertFalse(SafeDeath.isSafe(9023, false, 0));
		assertFalse(SafeDeath.isSafe(9007, false, 0));
		assertFalse(SafeDeath.isSafe(13372, false, 0));
		assertFalse(SafeDeath.isSafe(12345, false, 1));
	}
}
