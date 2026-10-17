package com.codex.ledger;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(CodexLedgerConfig.GROUP)
public interface CodexLedgerConfig extends Config
{
	String GROUP = "codexledger";

	@ConfigItem(
		keyName = "loginSummary",
		name = "Summary at login",
		description = "When you log in, write your worth and what prices did while you were away in the chat box",
		position = 0
	)
	default boolean loginSummary()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showPlaque",
		name = "Worth plaque",
		description = "Show a small plaque in the game view with your worth and today's change",
		position = 1
	)
	default boolean showPlaque()
	{
		return false;
	}
}
