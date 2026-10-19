package com.codex.ledger.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;
import javax.swing.JComponent;

/** A small engraved tablet: a big number, its label, and a quiet line of context. */
class StatTile extends JComponent
{
	private final String value;
	private final String label;
	private final String note;
	private final Color color;

	StatTile(String value, String label, String note, Color color)
	{
		this.value = value;
		this.label = label;
		this.note = note;
		this.color = color;
		setPreferredSize(new Dimension(10, 54));
		setOpaque(false);
		setToolTipText(note == null || note.isEmpty() ? label : label + ": " + note);
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		paint(g, value, label, note, color, 0, 0, getWidth(), getHeight());
		g.dispose();
	}

	/** Draws a tablet in a box; the card uses the same drawing. */
	static void paint(Graphics2D g, String value, String label, String note, Color color, int x, int y, int w, int h)
	{
		RoundRectangle2D shape = new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, Theme.RADIUS, Theme.RADIUS);
		g.setPaint(new GradientPaint(x, y, Theme.CARD_TOP, x, y + h, Theme.CARD));
		g.fill(shape);
		g.setColor(Theme.STROKE_SOFT);
		g.draw(shape);
		g.setColor(Theme.alpha(color, 160));
		g.fillRect(x + Theme.RADIUS, y + 1, w - Theme.RADIUS * 2, 1);

		g.setFont(Theme.small());
		FontMetrics small = g.getFontMetrics();
		g.setColor(Theme.TEXT_MUTED);
		g.drawString(label.toUpperCase(Locale.ROOT), x + 8, y + 6 + small.getAscent());

		g.setFont(Theme.title());
		FontMetrics bold = g.getFontMetrics();
		Ui.shadowed(g, Ui.clip(value, bold, w - 16), x + 8, y + 8 + small.getHeight() + bold.getAscent(), color);
		if (note != null && !note.isEmpty())
		{
			g.setFont(Theme.small());
			g.setColor(Theme.TEXT_FAINT);
			g.drawString(Ui.clip(note, small, w - 16), x + 8, y + h - 6);
		}
	}
}
