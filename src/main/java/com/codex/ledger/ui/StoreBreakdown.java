package com.codex.ledger.ui;

import com.codex.ledger.core.Store;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import javax.swing.JComponent;

/** Where the worth is kept: one bar split by place, then a line for each with when it was last seen. */
class StoreBreakdown extends JComponent
{
	private static final int BAR = 10;
	private static final int ROW = 19;
	/** A place's age when it has never been seen. */
	static final long NEVER = -2;

	/** One place's share. */
	static final class Part
	{
		final Store store;
		final long value;
		final long seenAgo;

		Part(Store store, long value, long seenAgo)
		{
			this.store = store;
			this.value = value;
			this.seenAgo = seenAgo;
		}
	}

	private final List<Part> parts;
	private final long total;
	/** Each place's share, rounded together to make 100%. */
	private final String[] shares;

	StoreBreakdown(List<Part> parts)
	{
		this.parts = parts;
		long sum = 0;
		for (Part p : parts)
		{
			sum += Math.max(0, p.value);
		}
		this.total = Math.max(1, sum);
		long[] values = new long[parts.size()];
		for (int i = 0; i < values.length; i++)
		{
			values[i] = parts.get(i).value;
		}
		shares = Format.shares(values, total);
		setOpaque(false);
		setPreferredSize(new Dimension(10, BAR + 8 + parts.size() * ROW));
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
		for (Part p : parts)
		{
			float pw = w * Math.max(0, p.value) / (float) total;
			if (pw <= 0)
			{
				continue;
			}
			Color c = Theme.store(p.store);
			g.setPaint(new GradientPaint(0, 0, Theme.mix(c, Theme.GOLD_BRIGHT, 0.2), 0, BAR, Theme.mix(c, Theme.INK, 0.35)));
			g.fill(new java.awt.geom.Rectangle2D.Float(x, 0, pw + 0.5f, BAR));
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
		for (int i = 0; i < parts.size(); i++)
		{
			Part p = parts.get(i);
			// An empty place steps back so the eye goes to where the worth actually is.
			boolean empty = p.value == 0;
			Color c = empty ? Theme.alpha(Theme.store(p.store), 90) : Theme.store(p.store);
			int mid = y + ROW / 2 - 1;
			g.setColor(c);
			g.fill(new Ellipse2D.Float(1, mid - 3.5f, 7, 7));
			int base = mid + fm.getAscent() / 2 - 1;
			Ui.shadowed(g, p.store.getLabel(), 14, base, empty ? Theme.TEXT_MUTED : Theme.TEXT);
			String value = p.seenAgo == NEVER ? "-" : Format.coins(p.value);
			String pct = p.seenAgo == NEVER ? "" : shares[i];
			int vw = fm.stringWidth(value);
			int pw = 30;
			Ui.shadowed(g, value, w - pw - vw - 4, base, empty ? Theme.TEXT_FAINT : Theme.mix(c, Theme.TEXT, 0.35));
			g.setColor(Theme.TEXT_FAINT);
			g.drawString(pct, w - fm.stringWidth(pct), base);
			String seen = p.seenAgo == NEVER ? "not seen yet" : p.seenAgo < 0 ? "" : "seen " + Format.ago(p.seenAgo);
			int sx = 14 + fm.stringWidth(p.store.getLabel()) + 6;
			if (sx + fm.stringWidth(seen) < w - pw - vw - 10)
			{
				g.drawString(seen, sx, base);
			}
			y += ROW;
		}
		g.dispose();
	}
}
