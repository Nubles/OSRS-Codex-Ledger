package com.codex.ledger.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * One item's margin over its recent trades: the prices it was bought at (teal) and sold at (gold)
 * drawn as two threads across time, so the gap between them is the margin as it has moved. A price
 * seen only at login, when it happened while the player was away, is drawn as a hollow ring.
 */
class MarginRow extends JComponent
{
	private static final int WELL = 32;
	private static final int CHART = 22;

	private final int itemId;
	private final String name;
	private final String note;
	private final String amount;
	private final Color color;
	private final long[] times;
	private final long[] prices;
	private final boolean[] buys;
	private final boolean[] late;
	private final ItemIcons icons;

	MarginRow(int itemId, String name, String note, String amount, Color color, long[] times, long[] prices, boolean[] buys,
		boolean[] late, ItemIcons icons, String tip)
	{
		this.itemId = itemId;
		this.name = name;
		this.note = note;
		this.amount = amount;
		this.color = color;
		this.times = times;
		this.prices = prices;
		this.buys = buys;
		this.late = late;
		this.icons = icons;
		setOpaque(false);
		setPreferredSize(new Dimension(10, WELL + CHART + 6));
		setToolTipText(tip);
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		int w = getWidth();
		int y = 2;
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
		int base = y + fm.getAscent() + 1;
		Ui.shadowed(g, amount, w - aw, base, color);
		Ui.shadowed(g, Ui.clip(name, fm, w - tx - aw - 8), tx, base, Theme.TEXT);
		g.setFont(Theme.small());
		FontMetrics small = g.getFontMetrics();
		g.setColor(Theme.TEXT_FAINT);
		g.drawString(Ui.clip(note, small, w - tx), tx, y + WELL - 6);

		chart(g, 0, y + WELL + 2, w, CHART);
		g.dispose();
	}

	private void chart(Graphics2D g, int x, int y, int w, int h)
	{
		RoundRectangle2D frame = new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, 6, 6);
		g.setColor(Theme.INK_DEEP);
		g.fill(frame);
		g.setColor(Theme.STROKE_SOFT);
		g.draw(frame);
		int n = times.length;
		if (n == 0)
		{
			return;
		}
		long t0 = times[0];
		long t1 = times[n - 1];
		long lo = Long.MAX_VALUE;
		long hi = Long.MIN_VALUE;
		for (long p : prices)
		{
			lo = Math.min(lo, p);
			hi = Math.max(hi, p);
		}
		if (hi == lo)
		{
			hi = lo + 1;
			lo = lo - 1;
		}
		int padX = 6;
		int padY = 4;
		float[] px = new float[n];
		float[] py = new float[n];
		for (int i = 0; i < n; i++)
		{
			// Even steps when the trades are close together, so a burst of fills does not pile up in one spot.
			double byTime = t1 == t0 ? 0.5 : (times[i] - t0) / (double) (t1 - t0);
			double byOrder = n == 1 ? 0.5 : i / (double) (n - 1);
			double at = 0.5 * byTime + 0.5 * byOrder;
			px[i] = (float) (x + padX + at * (w - padX * 2));
			py[i] = (float) (y + h - padY - (prices[i] - lo) / (double) (hi - lo) * (h - padY * 2));
		}
		// Each side's thread, then its points.
		for (int side = 0; side < 2; side++)
		{
			boolean buy = side == 0;
			Color c = buy ? Theme.TEAL : Theme.GOLD;
			Path2D path = new Path2D.Float();
			boolean started = false;
			for (int i = 0; i < n; i++)
			{
				if (buys[i] != buy)
				{
					continue;
				}
				if (!started)
				{
					path.moveTo(px[i], py[i]);
					started = true;
				}
				else
				{
					path.lineTo(px[i], py[i]);
				}
			}
			g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(Theme.alpha(c, 170));
			g.draw(path);
			for (int i = 0; i < n; i++)
			{
				if (buys[i] != buy)
				{
					continue;
				}
				Ellipse2D dot = new Ellipse2D.Float(px[i] - 2.5f, py[i] - 2.5f, 5, 5);
				if (late[i])
				{
					g.setColor(Theme.INK_DEEP);
					g.fill(dot);
					g.setStroke(new BasicStroke(1f));
					g.setColor(Theme.VIOLET);
					g.draw(dot);
				}
				else
				{
					g.setColor(c);
					g.fill(dot);
				}
			}
		}
		g.setStroke(new BasicStroke(1f));
	}
}
