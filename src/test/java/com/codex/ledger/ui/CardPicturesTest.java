package com.codex.ledger.ui;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CardPicturesTest
{
	/** Pictures that arrive only when told to, the way RuneLite's item pictures load. */
	private static final class Late implements ItemIcons
	{
		final Map<Integer, Runnable> callbacks = new HashMap<>();
		final Set<BufferedImage> in = new HashSet<>();
		int asked;

		@Override
		public BufferedImage icon(int itemId, Runnable onLoaded)
		{
			asked++;
			BufferedImage image = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
			callbacks.put(itemId, () ->
			{
				in.add(image);
				onLoaded.run();
			});
			return image;
		}

		@Override
		public boolean hasArrived(BufferedImage image)
		{
			return in.contains(image);
		}
	}

	/** A card that is "complete" only when every picture it drew had arrived. */
	private static BufferedImage card(ItemIcons icons, Late late, List<Boolean> complete)
	{
		boolean all = true;
		for (int id : new int[]{995, 4151})
		{
			all &= late.hasArrived(icons.icon(id, null));
		}
		complete.add(all);
		return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
	}

	@Test
	public void aCardWaitsForItsPicturesThenDrawsWithThem()
	{
		Late late = new Late();
		List<Boolean> drawn = new ArrayList<>();
		List<BufferedImage> delivered = new ArrayList<>();
		List<Runnable> waits = new ArrayList<>();
		int[] ended = {0};
		CardPictures.draw(late, icons -> card(icons, late, drawn), delivered::add, Runnable::run, waits::add, () -> ended[0]++);
		// Drawn once to learn what it needs, and nothing handed over yet.
		assertEquals(1, drawn.size());
		assertTrue(delivered.isEmpty());
		assertEquals(0, ended[0]);
		late.callbacks.get(995).run();
		assertTrue(delivered.isEmpty());
		late.callbacks.get(4151).run();
		// The last picture in draws it again, complete, with the same pictures (none asked for twice).
		assertEquals(1, delivered.size());
		assertEquals(Boolean.TRUE, drawn.get(1));
		assertEquals(2, late.asked);
		// The wait running out afterwards hands nothing over a second time, and the card ended once.
		waits.get(0).run();
		assertEquals(1, delivered.size());
		assertEquals(1, ended[0]);
	}

	@Test
	public void aPictureThatNeverComesHoldsTheCardOnlyUntilTheWaitRunsOut()
	{
		Late late = new Late();
		List<Boolean> drawn = new ArrayList<>();
		List<BufferedImage> delivered = new ArrayList<>();
		List<Runnable> waits = new ArrayList<>();
		int[] ended = {0};
		CardPictures.draw(late, icons -> card(icons, late, drawn), delivered::add, Runnable::run, waits::add, () -> ended[0]++);
		late.callbacks.get(995).run();
		waits.get(0).run();
		assertEquals(1, delivered.size());
		// A straggler after that is ignored.
		late.callbacks.get(4151).run();
		assertEquals(1, delivered.size());
	}

	@Test
	public void aCardThatFailsToDrawStillLetsTheNextPressThrough()
	{
		Late late = new Late();
		int[] ended = {0};
		List<Runnable> waits = new ArrayList<>();
		int[] calls = {0};
		CardPictures.draw(late, icons ->
		{
			icons.icon(995, null);
			if (calls[0]++ > 0)
			{
				throw new IllegalStateException("broken");
			}
			return null;
		}, c -> { }, Runnable::run, waits::add, () -> ended[0]++);
		try
		{
			late.callbacks.get(995).run();
		}
		catch (IllegalStateException expected)
		{
			// The failure reaches the caller as before.
		}
		assertEquals(1, ended[0]);
	}

	@Test
	public void picturesAlreadyInAreDrawnAtOnce()
	{
		List<BufferedImage> delivered = new ArrayList<>();
		int[] ended = {0};
		BufferedImage card = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
		ItemIcons ready = (id, cb) -> new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
		CardPictures.draw(ready, icons ->
		{
			icons.icon(995, null);
			return card;
		}, delivered::add, r -> { throw new AssertionError(); }, r -> { }, () -> ended[0]++);
		assertSame(card, delivered.get(0));
		assertEquals(1, ended[0]);
		CardPictures.draw(null, icons -> card, delivered::add);
		assertEquals(2, delivered.size());
	}

	@Test
	public void aCardStillWaitingWhenThePluginStopsIsNeverHandedOver() throws Exception
	{
		Late late = new Late();
		List<BufferedImage> delivered = new ArrayList<>();
		javax.swing.SwingUtilities.invokeAndWait(() ->
			CardPictures.draw(late, icons -> card(icons, late, new ArrayList<>()), delivered::add));
		// The plugin stops while the card waits; its pictures turn up afterwards.
		javax.swing.SwingUtilities.invokeAndWait(CardPictures::cancel);
		late.callbacks.get(995).run();
		late.callbacks.get(4151).run();
		javax.swing.SwingUtilities.invokeAndWait(() -> { });
		assertTrue(delivered.isEmpty());
		// And the next card, after a restart, is not held back by the dropped one.
		ItemIcons ready = (id, cb) -> new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
		javax.swing.SwingUtilities.invokeAndWait(() ->
			CardPictures.draw(ready, icons -> new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), delivered::add));
		assertEquals(1, delivered.size());
	}
}
