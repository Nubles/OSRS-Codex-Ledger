package com.codex.ledger.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JComponent;

/**
 * The change in worth taken apart: play (what was gained or spent) and market (what prices did),
 * each a bar growing right for gold gained and left for gold lost from a gilt centre line.
 */
class SplitRows extends JComponent
{
	private static final int ROW = 20;
	private static final int LABEL = 46;
	private static final int VALUE = 46;

	private final long play;
	private final long market;

	SplitRows(long play, long market)
	{
		this.play = play;
		this.market = market;
		setOpaque(false);
		setPreferredSize(new Dimension(10, ROW * 2));
		setToolTipText("Play is items gained or spent at the day's prices. Market is prices moving on what you held.");
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		long scale = Math.max(1, Math.max(Math.abs(play), Math.abs(market)));
		row(g, 0, "Play", play, scale, play >= 0 ? Theme.GAIN : Theme.LOSS);
		row(g, ROW, "Market", market, scale, market >= 0 ? Theme.MARKET : Theme.EMBER);
		g.dispose();
	}

	private void row(Graphics2D g, int y, String label, long value, long scale, Color color)
	{
		int w = getWidth();
		g.setFont(Theme.small());
		FontMetrics fm = g.getFontMetrics();
		int mid = y + ROW / 2;
		int base = mid + fm.getAscent() / 2 - 1;
		g.setColor(Theme.TEXT_MUTED);
		g.drawString(label, 0, base);
		String text = Format.signed(value);
		Ui.shadowed(g, text, w - fm.stringWidth(text), base, Theme.mix(color, Theme.TEXT, 0.2));

		int left = LABEL;
		int right = w - VALUE;
		int centre = (left + right) / 2;
		int half = (right - left) / 2;
		g.setColor(Theme.INK_DEEP);
		g.fill(new RoundRectangle2D.Float(left, mid - 3, right - left, 6, 6, 6));
		float len = (float) (half * Math.min(1, Math.abs(value) / (double) scale));
		if (value != 0)
		{
			len = Math.max(2, len);
			float x = value > 0 ? centre : centre - len;
			g.setPaint(new GradientPaint(centre, 0, Theme.mix(color, Theme.INK, 0.35), value > 0 ? centre + half : centre - half, 0, color));
			g.fill(new RoundRectangle2D.Float(x, mid - 3, len, 6, 6, 6));
		}
		g.setColor(Theme.GOLD);
		g.fillRect(centre, mid - 5, 1, 10);
	}
}
