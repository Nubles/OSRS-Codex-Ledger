package com.codex.ledger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class IconWatchTest
{
	/** Stands in for AsyncBufferedImage: once loaded, every callback given runs again. */
	private static final class Picture
	{
		boolean loaded;
		final List<Runnable> waiting = new ArrayList<>();
		final List<Runnable> later = new ArrayList<>();

		void onLoaded(Runnable r)
		{
			if (loaded)
			{
				later.add(r);
			}
			else
			{
				waiting.add(r);
			}
		}

		void load()
		{
			loaded = true;
			waiting.forEach(Runnable::run);
			waiting.clear();
		}

		void runLater()
		{
			List<Runnable> now = new ArrayList<>(later);
			later.clear();
			now.forEach(Runnable::run);
		}
	}

	@Test
	public void aRepaintOnLoadDoesNotLoopOnceThePictureHasArrived()
	{
		IconWatch watch = new IconWatch();
		Picture picture = new Picture();
		int[] paints = {0};
		// A component that asks for its picture while painting and repaints when it loads.
		Runnable[] paint = new Runnable[1];
		paint[0] = () ->
		{
			paints[0]++;
			watch.watch(picture, () -> picture.later.add(paint[0]), picture::onLoaded);
		};
		paint[0].run();
		paint[0].run();
		picture.load();
		for (int i = 0; i < 20; i++)
		{
			picture.runLater();
		}
		assertTrue(watch.hasArrived(picture));
		// Two paints before it arrived, one repaint each from the arrival, then nothing more.
		assertEquals(4, paints[0]);
		assertTrue(picture.later.isEmpty());
	}

	@Test
	public void aPictureAlreadyLoadedRepaintsOnceAtMost()
	{
		IconWatch watch = new IconWatch();
		Picture picture = new Picture();
		picture.loaded = true;
		int[] runs = {0};
		watch.watch(picture, () -> runs[0]++, picture::onLoaded);
		picture.runLater();
		watch.watch(picture, () -> runs[0]++, picture::onLoaded);
		picture.runLater();
		assertEquals(1, runs[0]);
		assertEquals(1, watch.size());
	}

	@Test
	public void nothingToWatchIsSafe()
	{
		IconWatch watch = new IconWatch();
		watch.watch(null, () -> { }, r -> { throw new AssertionError(); });
		Picture picture = new Picture();
		watch.watch(picture, null, picture::onLoaded);
		picture.load();
		assertTrue(watch.hasArrived(picture));
	}
}
