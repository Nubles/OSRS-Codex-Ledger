package com.codex.ledger.ui;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Item pictures for one card, held until the card is drawn with all of them in.
 *
 * <p>A card is drawn once, when its button is pressed, so a picture still loading would be saved as
 * an empty well. The card is drawn a first time to learn which pictures it needs; if any has not
 * arrived, it is drawn again as soon as the last one does (or after a short wait, so a picture that
 * never comes cannot hold the card back). The second drawing is handed the very same pictures, so
 * one evicted from RuneLite's small picture cache in between cannot come back blank.
 */
public final class CardPictures implements ItemIcons
{
	/** The longest a card waits for its pictures before it is drawn with what has come. */
	static final int WAIT_MS = 2_500;

	private final ItemIcons source;
	private final Map<Integer, BufferedImage> taken = new HashMap<>();
	private final Set<Integer> waiting = new HashSet<>();
	private final Set<Integer> arrived = new HashSet<>();
	private Runnable whenAllArrived;

	CardPictures(ItemIcons source)
	{
		this.source = source;
	}

	@Override
	public BufferedImage icon(int itemId, Runnable ignored)
	{
		synchronized (this)
		{
			if (taken.containsKey(itemId))
			{
				return taken.get(itemId);
			}
		}
		BufferedImage image = source.icon(itemId, () -> arrived(itemId));
		synchronized (this)
		{
			taken.put(itemId, image);
			if (image != null && !source.hasArrived(image) && !arrived.contains(itemId))
			{
				waiting.add(itemId);
			}
		}
		return image;
	}

	@Override
	public boolean hasArrived(BufferedImage image)
	{
		return source.hasArrived(image);
	}

	private void arrived(int itemId)
	{
		Runnable done = null;
		synchronized (this)
		{
			arrived.add(itemId);
			if (waiting.remove(itemId) && waiting.isEmpty())
			{
				done = whenAllArrived;
			}
		}
		if (done != null)
		{
			done.run();
		}
	}

	/** True when no picture the card asked for is still on its way. */
	synchronized boolean allArrived()
	{
		return waiting.isEmpty();
	}

	/** Runs once the last picture still on its way arrives; at once if none is. */
	void whenAllArrived(Runnable run)
	{
		boolean now;
		synchronized (this)
		{
			whenAllArrived = run;
			now = waiting.isEmpty();
		}
		if (now)
		{
			run.run();
		}
	}

	/**
	 * Draws a card with all its pictures in and hands it over on the Swing thread, as Save and Copy need.
	 *
	 * @param icons where pictures come from, or null to draw without them
	 * @param render draws the card with the pictures given
	 * @param deliver takes the finished card
	 */
	public static void draw(ItemIcons icons, Function<ItemIcons, BufferedImage> render, Consumer<BufferedImage> deliver)
	{
		draw(icons, render, deliver, SwingUtilities::invokeLater, run ->
		{
			Timer timer = new Timer(WAIT_MS, e -> run.run());
			timer.setRepeats(false);
			timer.start();
		});
	}

	/** As {@link #draw(ItemIcons, Function, Consumer)}, with how to get back on the Swing thread and how to wait, for tests. */
	static void draw(ItemIcons icons, Function<ItemIcons, BufferedImage> render, Consumer<BufferedImage> deliver,
		Consumer<Runnable> onSwing, Consumer<Runnable> afterWait)
	{
		if (icons == null)
		{
			deliver.accept(render.apply(null));
			return;
		}
		CardPictures pictures = new CardPictures(icons);
		BufferedImage first = render.apply(pictures);
		if (pictures.allArrived())
		{
			deliver.accept(first);
			return;
		}
		AtomicBoolean done = new AtomicBoolean();
		Runnable finish = () -> onSwing.accept(() ->
		{
			if (done.compareAndSet(false, true))
			{
				deliver.accept(render.apply(pictures));
			}
		});
		pictures.whenAllArrived(finish);
		afterWait.accept(finish);
	}
}
