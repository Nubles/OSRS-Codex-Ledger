package com.codex.ledger;

import com.codex.replay.PluginHost;
import com.codex.replay.Replay;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Plays every script in {@code src/test/resources/replays} against the whole plugin, started
 * as RuneLite starts it; see {@link Replay} for the commands and {@link PluginHost} for the rest.
 */
@RunWith(Parameterized.class)
public class ReplayTest
{
	@Parameterized.Parameters(name = "{0}")
	public static List<String> scripts()
	{
		return Replay.scripts("/replays");
	}

	@Parameterized.Parameter
	public String script;

	@Test
	public void plays()
	{
		Replay.play(script, PluginHost.of(CodexLedgerPlugin.class));
	}
}
