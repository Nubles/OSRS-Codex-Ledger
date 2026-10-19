package com.codex.ledger.ui;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
		// A second press while the first card still waits for its pictures is let go, so one press
		// never becomes two save dialogs.
		if (!BUSY.compareAndSet(false, true))
		{
			return;
		}
		int generation = GENERATION.get();
		Timer[] wait = new Timer[1];
		draw(icons, pictures -> GENERATION.get() == generation ? render.apply(pictures) : null, card ->
		{
			// A card still waiting when the plugin stopped is dropped, not saved by a plugin that is gone.
			if (GENERATION.get() == generation)
			{
				deliver.accept(card);
			}
		}, SwingUtilities::invokeLater, run ->
		{
			Timer timer = new Timer(WAIT_MS, e -> run.run());
			timer.setRepeats(false);
			wait[0] = timer;
			waitTimer = timer;
			timer.start();
		}, () ->
		{
			// The wait is over either way; a card drawn early lets go of its timer.
			if (wait[0] != null)
			{
				wait[0].stop();
			}
			if (waitTimer == wait[0])
			{
				waitTimer = null;
			}
			if (GENERATION.get() == generation)
			{
				BUSY.set(false);
			}
		});
	}

	/**
	 * Lets go of a card still waiting for its pictures, for the plugin's shutdown: it is never handed
	 * over, and the next card (after a restart) is not held back by it.
	 */
	public static void cancel()
	{
		GENERATION.incrementAndGet();
		BUSY.set(false);
		Runnable stop = () ->
		{
			if (waitTimer != null)
			{
				waitTimer.stop();
				waitTimer = null;
			}
		};
		// Plugins stop on the Swing thread; there it is done before any new card can begin.
		if (SwingUtilities.isEventDispatchThread())
		{
			stop.run();
		}
		else
		{
			SwingUtilities.invokeLater(stop);
		}
	}

	/** True while a card waits for its pictures. */
	private static final AtomicBoolean BUSY = new AtomicBoolean();
	/** Moved on by {@link #cancel}, so a card begun before it is dropped. */
	private static final AtomicInteger GENERATION = new AtomicInteger();
	/** The wait of the card waiting now; used on the Swing thread only. */
	private static Timer waitTimer;

	/**
	 * As {@link #draw(ItemIcons, Function, Consumer)}, with how to get back on the Swing thread and how
	 * to wait, for tests.
	 *
	 * @param after runs once the card has been handed over, or failed to draw
	 */
	static void draw(ItemIcons icons, Function<ItemIcons, BufferedImage> render, Consumer<BufferedImage> deliver,
		Consumer<Runnable> onSwing, Consumer<Runnable> afterWait, Runnable after)
	{
		boolean waiting = false;
		try
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
					try
					{
						deliver.accept(render.apply(pictures));
					}
					finally
					{
						after.run();
					}
				}
			});
			pictures.whenAllArrived(finish);
			afterWait.accept(finish);
			waiting = true;
		}
		finally
		{
			if (!waiting)
			{
				after.run();
			}
		}
	}
}
