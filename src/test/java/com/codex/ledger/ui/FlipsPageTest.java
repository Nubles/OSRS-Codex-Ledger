package com.codex.ledger.ui;

import com.codex.ledger.core.FlipBook;
import com.codex.ledger.core.Ledger;
import com.codex.ledger.core.Offer;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class FlipsPageTest
{
	private static final long MIN = 60_000L;
	private static final long T0 = 1_790_000_000_000L;

	@Test
	public void slotClocksReadAsOneTimeOrAnHonestRange()
	{
		assertEquals("<1m", FlipsPage.since(new long[]{20_000, 20_000}));
		assertEquals("14m", FlipsPage.since(new long[]{14 * MIN, 14 * MIN}));
		assertEquals("2h 5m", FlipsPage.since(new long[]{125 * MIN, 125 * MIN}));
		assertEquals("12h", FlipsPage.since(new long[]{12 * 60 * MIN, 12 * 60 * MIN}));
		assertEquals("3d", FlipsPage.since(new long[]{3 * 1440 * MIN, 3 * 1440 * MIN}));
		// Traded while away: somewhere between.
		assertEquals("10m-3h", FlipsPage.since(new long[]{10 * MIN, 190 * MIN}));
		// There before the book began: at least this long.
		assertEquals("5m+", FlipsPage.since(new long[]{5 * MIN, -1}));
	}

	@Test
	public void theFlipsPageBuildsForEveryStateOfTheBook()
	{
		List<FlipBook> books = new ArrayList<>();
		books.add(null);
		books.add(new FlipBook());
		FlipBook busy = new FlipBook();
		busy.read(new Offer[8], T0);
		busy.offer(0, new Offer(385, true, 100, 700, 100, 70_000, false), T0 + MIN);
		busy.offer(1, new Offer(385, false, 100, 800, 60, 48_000, true), T0 + 2 * MIN);
		busy.offer(2, new Offer(4151, false, 1, 1_500_000, 1, 1_500_000, false), T0 + 3 * MIN);
		Offer[] login = new Offer[8];
		login[1] = new Offer(385, false, 100, 800, 100, 80_000, false);
		busy.read(login, T0 + 300 * MIN);
		books.add(busy);
		for (FlipBook book : books)
		{
			for (long from : new long[]{0, T0 + 200 * MIN})
			{
				StackPanel stack = new StackPanel(8, 8);
				FlipsPage.build(stack, book, new Ledger(), Collections.emptyMap(), null, T0 + 301 * MIN, from, "Flips", 30);
				assertTrue(stack.getComponentCount() > 0);
				DamagedSaveTest.paint(stack);
			}
		}
		StackPanel stack = new StackPanel(8, 8);
		FlipsPage.build(stack, busy, null, Collections.emptyMap(), null, T0 + 301 * MIN, 0, "Flips", 30);
		// Slots 0 and 2 were collected while away; only the sale in slot 1 is left.
		assertEquals(1, count(stack, SlotRow.class));
		assertEquals(1, count(stack, MarginRow.class));
	}

	@Test
	public void aFlipSaysWhenItWasSeenLate()
	{
		FlipBook book = new FlipBook();
		book.read(new Offer[8], T0);
		book.offer(0, new Offer(385, true, 100, 700, 100, 70_000, false), T0);
		book.offer(1, new Offer(385, false, 100, 800, 0, 0, true), T0 + MIN);
		Offer[] login = new Offer[8];
		login[1] = new Offer(385, false, 100, 800, 100, 80_000, false);
		book.read(login, T0 + 100 * MIN);
		assertEquals("Seen late, 100 at +84 ea, held 1h 40m", FlipsPage.flipNote(book.getFlips().get(0)));
	}

	private static int count(Container c, Class<?> type)
	{
		int n = 0;
		for (Component k : c.getComponents())
		{
			n += type.isInstance(k) ? 1 : 0;
			if (k instanceof Container)
			{
				n += count((Container) k, type);
			}
		}
		return n;
	}
}
