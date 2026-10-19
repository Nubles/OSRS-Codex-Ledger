package com.codex.ledger.ui;

import com.codex.ledger.core.Ledger;
import com.codex.ledger.core.Prices;
import com.codex.ledger.core.Store;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LedgerCardTest
{
	private static final ZoneId UTC = ZoneOffset.UTC;
	private static final long T0 = 1_790_000_000_000L;
	private static final long DAY = 86_400_000L;

	/** A ledger that saw its bank, then grew from 400M past 500M over two weeks. */
	private static Ledger rising()
	{
		Map<Integer, Long> price = new HashMap<>();
		price.put(995, 1L);
		price.put(4151, 1_500_000L);
		Prices prices = id -> price.getOrDefault(id, 0L);
		Ledger ledger = new Ledger();
		for (int d = 0; d <= 14; d++)
		{
			Map<Integer, Long> bank = new HashMap<>();
			bank.put(995, 400_000_000L + d * 10_000_000L);
			bank.put(4151, 2L);
			ledger.observe(Store.BANK, bank);
			ledger.settle(T0 + d * DAY, prices, UTC);
		}
		return ledger;
	}

	private static LocalDate day(long t)
	{
		return Instant.ofEpochMilli(t).atZone(UTC).toLocalDate();
	}

	@Test
	public void nothingToShareUntilTheBankIsSeen()
	{
		assertFalse(LedgerCard.canDraw(null));
		Ledger unseen = new Ledger();
		Map<Integer, Long> inv = new HashMap<>();
		inv.put(995, 25_000L);
		unseen.observe(Store.INVENTORY, inv);
		unseen.settle(T0, id -> 1L, UTC);
		assertFalse(LedgerCard.canDraw(unseen));
		assertNull(LedgerCard.Statement.of(unseen, Collections.emptyMap(), "Zezima", day(T0), T0, UTC, 0, "", null));
	}

	@Test
	public void theStatementTakesTheMonthAndTheTopHoldings()
	{
		Ledger ledger = rising();
		long now = T0 + 14 * DAY;
		Map<Integer, String> names = new HashMap<>();
		names.put(995, "Coins");
		LedgerCard.Statement s = LedgerCard.Statement.of(ledger, names, "", day(now), now, UTC, 0, null, null);
		assertNotNull(s);
		// An empty name prints as "My ledger".
		assertNull(s.player);
		assertEquals(ledger.getTotal(), s.total);
		// The chart starts where the ledger began when that is under thirty days ago, and runs to now.
		assertEquals(T0, s.from);
		assertEquals(now, s.points.get(s.points.size() - 1).getTime());
		assertEquals("Coins", s.held.get(0).name);
		// An item with no name known still has a line.
		assertEquals("Item 4151", s.held.get(1).name);
		assertEquals(500_000_000L, s.milestones.getPassed().get(0).getAmount());
		assertTrue(s.pays.isEmpty());
	}

	@Test
	public void titlesAndFileNames()
	{
		assertEquals("My ledger", LedgerCard.title(null));
		assertEquals("Zezima's ledger", LedgerCard.title("Zezima"));
		assertEquals("Ledger 19 October 2026.png", LedgerCard.fileName(Instant.parse("2026-10-19T12:00:00Z").toEpochMilli(), UTC));
	}

	@Test
	public void rendersAWholePageAtTwiceScaleAndAsksForEachPicture()
	{
		Ledger ledger = rising();
		long now = T0 + 14 * DAY;
		LedgerCard.Statement s = LedgerCard.Statement.of(ledger, Collections.emptyMap(), "A very long player name", day(now), now, UTC,
			2_000_000_000L, "Twisted bow and then some more", null);
		List<Integer> asked = new ArrayList<>();
		BufferedImage card = LedgerCard.render(s, (id, cb) ->
		{
			asked.add(id);
			return new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
		}, null);
		assertEquals(LedgerCard.WIDTH * LedgerCard.SCALE, card.getWidth());
		assertTrue(card.getHeight() > 600);
		// Measuring the card asks for no pictures; drawing it asks once for each holding.
		assertEquals(2, asked.size());
		// The corners are clear and the middle is drawn.
		assertEquals(0, card.getRGB(0, 0) >>> 24);
		assertEquals(255, card.getRGB(card.getWidth() / 2, card.getHeight() / 2) >>> 24);
	}

	@Test
	public void wrappedLinesEndInAnEllipsisWhenTheyRunOn()
	{
		FontMetrics fm = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().getFontMetrics(new Font(Font.MONOSPACED, Font.PLAIN, 10));
		int width = fm.stringWidth("aaaa bbbb");
		List<String> lines = LedgerCard.lines("aaaa bbbb cccc dddd eeee ffff", fm, width, 2);
		assertEquals(2, lines.size());
		assertEquals("aaaa bbbb", lines.get(0));
		assertTrue(lines.get(1).endsWith("..."));
		assertEquals(Collections.singletonList("aaaa"), LedgerCard.lines("aaaa", fm, width, 2));
	}

	@Test
	public void gapsBetweenMilestonesReadPlainly()
	{
		assertEquals("under an hour", Format.gap(20 * 60_000L));
		assertEquals("5 hours", Format.gap(5 * 3_600_000L));
		assertEquals("1 day", Format.gap(DAY + 1));
		assertEquals("89 days", Format.gap(89 * DAY));
		assertEquals("4 months", Format.gap(120 * DAY));
		assertEquals("under an hour", Format.gap(-5));
	}
}
