package com.codex.ledger;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import net.runelite.client.config.ConfigItem;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Settings keep the keys their values are saved under. */
public class SavedSettingsTest
{
	/** Every key the released plugin (main) saves settings under. */
	private static final String[] ON_MAIN = {"loginSummary", "showPlaque"};
	/** Keys added since, which players will have saved by the time this ships. */
	private static final String[] ADDED = {"goal", "goalName", "nameOnCard"};

	@Test
	public void savedSettingsKeepTheirKeys()
	{
		// A setting's key is where its value is saved: renaming one quietly resets it for every player who
		// changed it. A display name may change freely; a key may not.
		Set<String> keys = new HashSet<>();
		for (Method m : CodexLedgerConfig.class.getMethods())
		{
			ConfigItem item = m.getAnnotation(ConfigItem.class);
			if (item != null)
			{
				keys.add(item.keyName());
			}
		}
		for (String key : ON_MAIN)
		{
			assertTrue(key, keys.contains(key));
		}
		for (String key : ADDED)
		{
			assertTrue(key, keys.contains(key));
		}
	}
}
