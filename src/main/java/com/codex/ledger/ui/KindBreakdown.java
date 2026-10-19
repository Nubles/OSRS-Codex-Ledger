package com.codex.ledger.ui;

import com.codex.ledger.core.Kind;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JComponent;

/**
 * What the worth is made of: one bar split into coins, gear, supplies and materials, then a line for
 * each with its worth, its share, and how it moved over the span when an earlier page says.
 */
class KindBreakdown extends JComponent
{
	private static final int BAR = 10;
	private static final int ROW = 19;
	private static final Kind[] ORDER = Kind.values();

	private final long[] worth;
	private final long[] change;
	private final long total;

	/**
	 * @param worth by {@link Kind} ordinal
	 * @param change by {@link Kind} ordinal, or null when there is nothing to compare with
	 */
	KindBreakdown(long[] worth, long[] change)
	{
		this.worth = worth;
		this.change = change;
		long sum = 0;
		for (long v : worth)
		{
			sum += Math.max(0, v);
		}
		total = Math.max(1, sum);
		setOpaque(false);
		setPreferredSize(new Dimension(10, BAR + 8 + ORDER.length * ROW));
		StringBuilder tip = new StringBuilder("<html>Coins and platinum; gear is anything worn, bar ammunition;<br>"
			+ "supplies are food, potions, runes, ammunition and teleports;<br>materials are everything else.");
		if (change != null)
		{
			tip.append("<br>The change is since the close of the day before the span.");
		}
		setToolTipText(tip.append("</html>").toString());
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		int w = getWidth();
		RoundRectangle2D track = new RoundRectangle2D.Float(0, 0, w, BAR, BAR, BAR);
		g.setColor(Theme.INK_DEEP);
		g.fill(track);
		g.setClip(track);
		float x = 0;
		for (Kind k : ORDER)
		{
			float pw = w * Math.max(0, worth[k.ordinal()]) / (float) total;
			if (pw <= 0)
			{
				continue;
			}
			Color c = Theme.kind(k);
			g.setPaint(new GradientPaint(0, 0, Theme.mix(c, Theme.GOLD_BRIGHT, 0.2), 0, BAR, Theme.mix(c, Theme.INK, 0.35)));
			g.fill(new Rectangle2D.Float(x, 0, pw + 0.5f, BAR));
			g.setColor(Theme.alpha(Theme.INK_DEEP, 200));
			g.drawLine(Math.round(x + pw), 0, Math.round(x + pw), BAR);
			x += pw;
		}
		g.setClip(null);
		g.setColor(Theme.alpha(Theme.GOLD, 90));
		g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, BAR - 1, BAR, BAR));

		g.setFont(Theme.small());
		FontMetrics fm = g.getFontMetrics();
		int y = BAR + 8;
		int pctW = 30;
		for (Kind k : ORDER)
		{
			long v = worth[k.ordinal()];
			Color c = Theme.kind(k);
			int mid = y + ROW / 2 - 1;
			g.setColor(c);
			g.fill(new Ellipse2D.Float(1, mid - 3.5f, 7, 7));
			int base = mid + fm.getAscent() / 2 - 1;
			Ui.shadowed(g, k.getLabel(), 14, base, Theme.TEXT);
			String value = Format.coins(v);
			String pct = Format.percent(Math.max(0, v) / (double) total);
			int vw = fm.stringWidth(value);
			Ui.shadowed(g, value, w - pctW - vw - 4, base, Theme.mix(c, Theme.TEXT, 0.35));
			g.setColor(Theme.TEXT_FAINT);
			g.drawString(pct, w - fm.stringWidth(pct), base);
			long moved = change == null ? 0 : change[k.ordinal()];
			if (moved != 0)
			{
				String text = Format.signedCompact(moved);
				int sx = 14 + fm.stringWidth(k.getLabel()) + 6;
				if (sx + fm.stringWidth(text) < w - pctW - vw - 10)
				{
					g.setColor(Theme.alpha(Theme.signed(moved), 210));
					g.drawString(text, sx, base);
				}
			}
			y += ROW;
		}
		g.dispose();
	}
}
