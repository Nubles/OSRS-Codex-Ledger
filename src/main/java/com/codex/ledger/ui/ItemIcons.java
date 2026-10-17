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
}
