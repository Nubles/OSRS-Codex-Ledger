package com.codex.ledger.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Where dying costs nothing: the raids, and the minigames and arenas where the game keeps
 * everything. A death there takes nothing, so what is eaten or used straight after it is supplies
 * and not a loss to the death.
 */
public final class SafeDeath
{
	/**
	 * Map regions of the safe minigames and raids: Barbarian Assault, Castle Wars, Emir's Arena,
	 * Fortis Colosseum, the Gauntlet, the Inferno, Last Man Standing, Nightmare Zone, Pest Control,
	 * Soul Wars, the TzHaar Fight Caves and Fight Pits, Trouble Brewing, Chambers of Xeric, Theatre
	 * of Blood and Tombs of Amascut.
	 */
	static final Set<Integer> REGIONS = new HashSet<>(Arrays.asList(
		7508, 7509, 10322,
		9520, 9620,
		13362, 13363,
		7216,
		12127, 7512, 7768,
		9043,
		13658, 13659, 13660, 13914, 13915, 13916, 13918, 13919, 13920, 14174, 14175, 14176, 14430, 14431, 14432,
		9033,
		10536,
		8493, 8748, 8749, 9005,
		9551, 9552,
		15150,
		12889, 13136, 13137, 13138, 13139, 13140, 13141, 13145, 13393, 13394, 13395, 13396, 13397, 13401,
		12611, 12612, 12613, 12867, 12869, 13122, 13123, 13125, 13379,
		14160, 14162, 14164, 14674, 14676, 15184, 15186, 15188, 15696, 15698, 15700, 13454, 14672));

	private SafeDeath()
	{
	}

	/**
	 * @param region the map region the player died in, as the instance's template for an instance
	 * @param inChambers whether the game says the player is in the Chambers of Xeric
	 * @param theatre the game's Theatre of Blood state: 2 or more is inside
	 */
	public static boolean isSafe(int region, boolean inChambers, int theatre)
	{
		return inChambers || theatre >= 2 || REGIONS.contains(region);
	}
}
