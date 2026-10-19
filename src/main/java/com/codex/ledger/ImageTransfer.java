package com.codex.ledger;

import com.codex.ledger.ui.Theme;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Hands the ledger card to the system clipboard as a picture. */
final class ImageTransfer implements Transferable
{
	private final BufferedImage image;

	ImageTransfer(BufferedImage image)
	{
		this.image = image;
	}

	/**
	 * The card on a solid backing. Some clipboards (Windows among them) drop transparency and
	 * would paint its rounded corners black, so they are filled with the card's own ink.
	 */
	static BufferedImage opaque(BufferedImage card)
	{
		BufferedImage out = new BufferedImage(card.getWidth(), card.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setColor(Theme.INK_DEEP);
		g.fillRect(0, 0, out.getWidth(), out.getHeight());
		g.drawImage(card, 0, 0, null);
		g.dispose();
		return out;
	}

	@Override
	public DataFlavor[] getTransferDataFlavors()
	{
		return new DataFlavor[]{DataFlavor.imageFlavor};
	}

	@Override
	public boolean isDataFlavorSupported(DataFlavor flavor)
	{
		return DataFlavor.imageFlavor.equals(flavor);
	}

	@Override
	public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException
	{
		if (!isDataFlavorSupported(flavor))
		{
			throw new UnsupportedFlavorException(flavor);
		}
		return image;
	}
}
