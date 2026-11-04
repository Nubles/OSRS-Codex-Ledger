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
 * One Grand Exchange slot on the Flips page: the item in its well with the slot's number, what the
 * offer is doing, how far it has got as a bar in buy teal or sell gold, and on the right how long it
 * has stood still. A slot idle past the player's mark has its clock lit in ember.
 */
class SlotRow extends JComponent
{
	private static final int WELL = 32;

	private final int slot;
	private final int itemId;
	private final String name;
	private final String note;
	private final String clock;
	private final Color clockColor;
	private final Color color;
	private final double progress;
	private final boolean seenLate;
	private final ItemIcons icons;

	/**
	 * @param slot the slot's number from 1
	 * @param clock how long it has stood still, "14m", "idle 47m" or "collect"
	 * @param seenLate writes the note in the colour of what was seen only at login
	 */
	SlotRow(int slot, int itemId, String name, String note, String clock, Color clockColor, Color color,
		double progress, boolean seenLate, ItemIcons icons, String tip)
	{
		this.slot = slot;
		this.itemId = itemId;
		this.name = name;
		this.note = note;
		this.clock = clock;
		this.clockColor = clockColor;
		this.color = color;
		this.progress = progress;
		this.seenLate = seenLate;
		this.icons = icons;
		setOpaque(false);
		setPreferredSize(new Dimension(10, WELL + 12));
		setToolTipText(tip);
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		int w = getWidth();
		int y = 3;
		RoundRectangle2D well = new RoundRectangle2D.Float(0.5f, y + 0.5f, WELL + 4, WELL - 1, 7, 7);
		g.setPaint(new GradientPaint(0, y, Theme.INK_DEEP, 0, y + WELL, Theme.mix(Theme.INK_DEEP, Theme.CARD, 0.6)));
		g.fill(well);
		g.setColor(Theme.alpha(color, 110));
		g.draw(well);
		BufferedImage icon = icons == null ? null : icons.icon(itemId, this::repaint);
		if (icon != null)
		{
			g.drawImage(icon, 2 + (WELL - icon.getWidth()) / 2, y + (WELL - icon.getHeight()) / 2 + 1, null);
		}
		g.setFont(Theme.small());
		FontMetrics small = g.getFontMetrics();
		Ui.shadowed(g, String.valueOf(slot), 3, y + small.getAscent() + 1, Theme.GOLD_DIM);

		int tx = WELL + 12;
		g.setFont(Theme.body());
		FontMetrics fm = g.getFontMetrics();
		int cw = fm.stringWidth(clock);
		int base = y + fm.getAscent() + 1;
		Ui.shadowed(g, clock, w - cw, base, clockColor);
		Ui.shadowed(g, Ui.clip(name, fm, w - tx - cw - 8), tx, base, Theme.TEXT);

		g.setFont(Theme.small());
		int line = y + WELL - 4;
		g.setColor(seenLate ? Theme.VIOLET : Theme.TEXT_FAINT);
		g.drawString(Ui.clip(note, small, w - tx), tx, line);

		int by = getHeight() - 6;
		int bw = w - tx;
		RoundRectangle2D track = new RoundRectangle2D.Float(tx, by, bw, 4, 4, 4);
		g.setColor(Theme.INK_DEEP);
		g.fill(track);
		if (progress > 0)
		{
			float fill = (float) Math.max(3, bw * Math.min(1, progress));
			g.setPaint(new GradientPaint(tx, 0, Theme.mix(color, Theme.INK, 0.45), tx + bw, 0, color));
			g.fill(new RoundRectangle2D.Float(tx, by, fill, 4, 4, 4));
		}
		g.setColor(Theme.STROKE_SOFT);
		g.draw(track);
		g.dispose();
	}
}
