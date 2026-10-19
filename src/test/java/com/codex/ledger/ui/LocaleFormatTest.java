package com.codex.ledger.ui;

import java.util.Locale;
import static org.junit.Assert.assertEquals;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Numbers read the same on a German or French computer as on an English one, as the rest of the panel does. */
public class LocaleFormatTest
{
	private Locale was;

	@Before
	public void german()
	{
		was = Locale.getDefault();
		Locale.setDefault(Locale.GERMANY);
	}

	@After
	public void restore()
	{
		Locale.setDefault(was);
	}

	@Test
	public void hoursAndCountsUseTheEnglishMarks()
	{
		assertEquals("1.5 hours", Format.hours(1.5));
		assertEquals("12,480", Format.whole(12_480));
		assertEquals("41.2M", Format.coins(41_200_000));
		assertEquals("+1,240", Format.count(1_240));
	}

	@Test
	public void headingsUpperCaseAlikeEverywhere()
	{
		Locale.setDefault(new Locale("tr", "TR"));
		assertEquals("MINING", new SectionHeader("Mining", "").getText());
	}
}
