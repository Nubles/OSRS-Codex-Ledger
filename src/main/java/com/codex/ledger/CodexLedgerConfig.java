package com.codex.ledger;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

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

	@ConfigItem(
		keyName = "goal",
		name = "Worth goal (millions)",
		description = "A worth to aim for, in millions of coins: 1500 is 1.5B. The Worth page shows how far along you are and when you get there at your pace. 0 aims at the next round figure",
		position = 2
	)
	@Range(min = 0, max = 2_000_000)
	default int goal()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "goalName",
		name = "Goal name",
		description = "What the goal is for, such as Twisted bow, shown above it on the Worth page",
		position = 3
	)
	default String goalName()
	{
		return "";
	}

	@ConfigItem(
		keyName = "nameOnCard",
		name = "Name on cards",
		description = "Write your character's name on the ledger card; turn off to share it without it",
		position = 4
	)
	default boolean nameOnCard()
	{
		return true;
	}
}
