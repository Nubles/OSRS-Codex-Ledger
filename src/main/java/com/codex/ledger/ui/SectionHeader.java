package com.codex.ledger.ui;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.util.Locale;
import javax.swing.JComponent;

/** A small-caps heading with a gold diamond and a line that fades into the dark. */
public class SectionHeader extends JComponent
{
	private final String text;
	private final String trailing;

	public SectionHeader(String text)
	{
		this(text, null);
	}

	/** The heading as drawn, in capitals. */
	String getText()
	{
		return text;
	}

	public SectionHeader(String text, String trailing)
	{
		this.text = text.toUpperCase(Locale.ROOT);
		this.trailing = trailing;
		setFont(Theme.small());
		setPreferredSize(new Dimension(10, 20));
		setMinimumSize(new Dimension(10, 20));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		setAlignmentX(LEFT_ALIGNMENT);
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		g.setFont(getFont());
		paint(g, text, trailing, 0, 0, getWidth(), getHeight());
		g.dispose();
	}

	/** Draws a heading in a box; the card uses the same drawing. The font must already be set. */
	static void paint(Graphics2D g, String text, String trailing, int x, int y, int w, int h)
	{
		int mid = y + h / 2 + 2;
		Path2D diamond = new Path2D.Float();
		diamond.moveTo(x + 4, mid - 4);
		diamond.lineTo(x + 8, mid);
		diamond.lineTo(x + 4, mid + 4);
		diamond.lineTo(x, mid);
		diamond.closePath();
		g.setColor(Theme.GOLD);
		g.fill(diamond);

		FontMetrics fm = g.getFontMetrics();
		int textX = x + 13;
		int baseline = mid + fm.getAscent() / 2 - 1;
		g.setColor(Theme.GOLD);
		g.drawString(text, textX, baseline);

		int lineStart = textX + fm.stringWidth(text) + 6;
		int lineEnd = x + w;
		if (trailing != null && !trailing.isEmpty())
		{
			int tw = fm.stringWidth(trailing);
			lineEnd = x + w - tw - 6;
			g.setColor(Theme.TEXT_FAINT);
			g.drawString(trailing, x + w - tw, baseline);
		}
		if (lineEnd > lineStart)
		{
			g.setPaint(new GradientPaint(lineStart, 0, Theme.alpha(Theme.GOLD, 110), lineEnd, 0, Theme.alpha(Theme.GOLD, 0)));
			g.drawLine(lineStart, mid, lineEnd, mid);
		}
	}
}
