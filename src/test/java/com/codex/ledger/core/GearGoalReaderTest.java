package com.codex.ledger.core;

import com.google.gson.Gson;
import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

/**
 * Pins what Ledger reads from Codex Gear Roadmap: its Savings.Goal, saved as codexgearroadmap.goal
 * by gson as {"name":..., "ids":[...]}. If Gear Roadmap changes the shape, this breaks first.
 */
public class GearGoalReaderTest
{
	private final Gson gson = new Gson();

	@Test
	public void readsTheGoalAsGearRoadmapSavesIt()
	{
		GearGoalReader.Goal g = GearGoalReader.read(gson, "{\"name\":\"Twisted bow\",\"ids\":[20997]}");
		assertEquals("Twisted bow", g.getName());
		assertEquals(Arrays.asList(20997), g.getIds());
		assertEquals(Arrays.asList(1, 2), GearGoalReader.read(gson, "{\"name\":\" Set \",\"ids\":[1,2,2,-4,\"x\",2.5]}").getIds());
	}

	@Test
	public void anythingElseIsNoGoal()
	{
		assertNull(GearGoalReader.read(gson, null));
		assertNull(GearGoalReader.read(gson, "  "));
		assertNull(GearGoalReader.read(gson, "{oops"));
		assertNull(GearGoalReader.read(gson, "[]"));
		assertNull(GearGoalReader.read(gson, "{\"name\":\"Bow\"}"));
		assertNull(GearGoalReader.read(gson, "{\"name\":\"\",\"ids\":[1]}"));
		assertNull(GearGoalReader.read(gson, "{\"name\":3,\"ids\":[1]}"));
		assertNull(GearGoalReader.read(gson, "{\"name\":\"Bow\",\"ids\":[0,-1]}"));
	}
}
