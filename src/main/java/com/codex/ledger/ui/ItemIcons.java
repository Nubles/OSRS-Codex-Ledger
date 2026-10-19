package com.codex.ledger.ui;

import java.awt.image.BufferedImage;

/** Where the panel gets item pictures; the plugin answers from RuneLite's item manager. */
public interface ItemIcons
{
	/**
	 * @param onLoaded run once if the picture arrives later than this call
	 * @return the picture, possibly still blank, or null
	 */
	BufferedImage icon(int itemId, Runnable onLoaded);

	/**
	 * True when a picture this gave out is known to have arrived; a card waits for one that has not.
	 * Pictures that are never late (previews, tests) have always arrived.
	 */
	default boolean hasArrived(BufferedImage image)
	{
		return true;
	}
}
