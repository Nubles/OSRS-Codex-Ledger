package com.codex.replay;

import java.util.List;
import java.util.Map;

/**
 * Wires one plugin to a replay: it builds the plugin's parts on the {@link FakeGame}, takes the
 * plugin's own script commands (its settings, say), and on each tick runs the plugin and reports
 * what the player would be shown, as named readings a script can check.
 *
 * <p>Shared by every Codex plugin: keep this file identical across repositories.
 */
public interface Rig extends FakeGame.Listener
{
	/**
	 * A script line the replay itself does not know.
	 *
	 * @return false when this rig does not know it either, which fails the replay
	 */
	default boolean command(String verb, List<String> args)
	{
		return false;
	}

	/**
	 * Runs one game tick of the plugin.
	 *
	 * @param now the wall clock, which moves on 600ms a tick
	 * @return what the plugin shows this tick, by name: a step's title, the object it lights up
	 */
	Map<String, String> tick(long now);
}
