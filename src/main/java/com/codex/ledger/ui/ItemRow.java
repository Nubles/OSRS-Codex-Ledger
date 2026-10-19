package com.codex.ledger.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * One item's line: the item in a dark well like a bank slot, its name and a quiet note under it,
 * the amount on the right in its colour, and optionally a bar for its share of the whole.
 */
class ItemRow extends JComponent
{
	private static final int WELL = 32;

	private final int itemId;
	private final String name;
	private final String note;
	private final String amount;
	private final Color color;
	private final double share;
	private final ItemIcons icons;
	/** The share written as a percent; rounded with the rows around it when they share one whole. */
	private final String shareText;

	/** @param share 0 to 1 for a share bar, or below 0 for none */
	ItemRow(int itemId, String name, String note, String amount, Color color, double share, ItemIcons icons, String tip)
	{
		this(itemId, name, note, amount, color, share, share >= 0 ? Format.percent(share) : null, icons, tip);
	}

	/** @param shareText the share as the list rounded it with the others, "19%" */
	ItemRow(int itemId, String name, String note, String amount, Color color, double share, String shareText, ItemIcons icons, String tip)
	{
		this.shareText = shareText;
		this.itemId = itemId;
		this.name = name;
		this.note = note;
		this.amount = amount;
		this.color = color;
		this.share = share;
		this.icons = icons;
		setOpaque(false);
		setPreferredSize(new Dimension(10, share >= 0 ? WELL + 10 : WELL + 4));
		setToolTipText(tip);
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		int w = getWidth();
		int y = share >= 0 ? 5 : 2;
		RoundRectangle2D well = new RoundRectangle2D.Float(0.5f, y + 0.5f, WELL + 4, WELL - 1, 7, 7);
		g.setPaint(new GradientPaint(0, y, Theme.INK_DEEP, 0, y + WELL, Theme.mix(Theme.INK_DEEP, Theme.CARD, 0.6)));
		g.fill(well);
		g.setColor(Theme.STROKE_SOFT);
		g.draw(well);
		BufferedImage icon = icons == null ? null : icons.icon(itemId, this::repaint);
		if (icon != null)
		{
			g.drawImage(icon, 2 + (WELL - icon.getWidth()) / 2, y + (WELL - icon.getHeight()) / 2 + 1, null);
		}

		int tx = WELL + 12;
		g.setFont(Theme.body());
		FontMetrics fm = g.getFontMetrics();
		int aw = fm.stringWidth(amount);
		int base = (share >= 0 ? 0 : y) + fm.getAscent() + 1;
		Ui.shadowed(g, amount, w - aw, base, color);
		Ui.shadowed(g, Ui.clip(name, fm, w - tx - aw - 8), tx, base, Theme.TEXT);

		g.setFont(Theme.small());
		FontMetrics small = g.getFontMetrics();
		if (share >= 0)
		{
			int by = getHeight() - 6;
			int bw = w - tx;
			String pct = shareText;
			int pw = small.stringWidth(pct);
			g.setColor(Theme.TEXT_FAINT);
			g.drawString(Ui.clip(note, small, bw - pw - 8), tx, by - 3);
			g.drawString(pct, w - pw, by - 3);
			RoundRectangle2D track = new RoundRectangle2D.Float(tx, by, bw, 4, 4, 4);
			g.setColor(Theme.INK_DEEP);
			g.fill(track);
			float fill = (float) Math.max(3, bw * Math.max(0, Math.min(1, share)));
			g.setPaint(new GradientPaint(tx, 0, Theme.mix(color, Theme.INK, 0.4), tx + bw, 0, color));
			g.fill(new RoundRectangle2D.Float(tx, by, fill, 4, 4, 4));
		}
		else if (note != null)
		{
			g.setColor(Theme.TEXT_FAINT);
			g.drawString(Ui.clip(note, small, w - tx), tx, y + WELL - 6);
		}
		g.dispose();
	}
}
