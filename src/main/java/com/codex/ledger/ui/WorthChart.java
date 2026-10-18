package com.codex.ledger.ui;

import com.codex.ledger.core.Ledger;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import javax.swing.JComponent;

/**
 * Worth over time as a gilt line over a fading wash of gold, with a dashed line where the range
 * began, the high and low marked, and a crosshair that reads out any moment under the pointer.
 */
class WorthChart extends JComponent
{
	private static final int TOP = 20;
	private static final int BOTTOM = 16;

	private final List<Ledger.Point> points;
	private final long from;
	private final long to;
	private final boolean intraday;
	private int hover = -1;
	/** Where the pointer was, carried from the chart this one replaced so a refresh keeps the crosshair. */
	private final int[] pointer;

	/**
	 * @param points oldest first, already cut to the range
	 * @param intraday label the axis with times rather than dates
	 */
	WorthChart(List<Ledger.Point> points, long from, long to, boolean intraday, int[] pointer)
	{
		this.pointer = pointer;
		this.points = points;
		this.from = from;
		this.to = Math.max(from + 1, to);
		this.intraday = intraday;
		setOpaque(false);
		setPreferredSize(new Dimension(10, 132));
		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				pointer[0] = e.getX();
				int index = nearest(e.getX());
				if (index != hover)
				{
					hover = index;
					repaint();
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				pointer[0] = -1;
				hover = -1;
				repaint();
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	private float xOf(long t)
	{
		return 2 + (getWidth() - 4) * (float) ((t - from) / (double) (to - from));
	}

	private int nearest(int x)
	{
		int best = -1;
		float bestDistance = Float.MAX_VALUE;
		for (int i = 0; i < points.size(); i++)
		{
			float d = Math.abs(xOf(points.get(i).getTime()) - x);
			if (d < bestDistance)
			{
				bestDistance = d;
				best = i;
			}
		}
		return best;
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		int w = getWidth();
		int h = getHeight();
		int plotBottom = h - BOTTOM;
		if (hover < 0 && pointer[0] >= 0)
		{
			hover = nearest(pointer[0]);
		}
		g.setFont(Theme.small());
		FontMetrics fm = g.getFontMetrics();

		// Guide lines.
		g.setColor(Theme.alpha(Theme.STROKE_SOFT, 150));
		for (int i = 0; i <= 2; i++)
		{
			int gy = TOP + (plotBottom - TOP) * i / 2;
			g.drawLine(0, gy, w, gy);
		}

		if (points.size() < 2)
		{
			String text = "The line fills in as you play";
			g.setColor(Theme.TEXT_FAINT);
			g.drawString(text, (w - fm.stringWidth(text)) / 2, (TOP + plotBottom) / 2 + fm.getAscent() / 2);
			g.dispose();
			return;
		}

		long min = Long.MAX_VALUE;
		long max = Long.MIN_VALUE;
		int hi = 0;
		int lo = 0;
		for (int i = 0; i < points.size(); i++)
		{
			long v = points.get(i).getValue();
			if (v > max)
			{
				max = v;
				hi = i;
			}
			if (v < min)
			{
				min = v;
				lo = i;
			}
		}
		long span = Math.max(1, max - min);
		double pad = span * 0.12;
		double low = min - pad;
		double high = max + pad;
		java.util.function.LongFunction<Float> yOf = v -> (float) (plotBottom - (plotBottom - TOP) * ((v - low) / (high - low)));

		Path2D line = new Path2D.Float();
		Path2D area = new Path2D.Float();
		for (int i = 0; i < points.size(); i++)
		{
			float px = xOf(points.get(i).getTime());
			float py = yOf.apply(points.get(i).getValue());
			if (i == 0)
			{
				line.moveTo(px, py);
				area.moveTo(px, plotBottom);
				area.lineTo(px, py);
			}
			else
			{
				line.lineTo(px, py);
				area.lineTo(px, py);
			}
		}
		area.lineTo(xOf(points.get(points.size() - 1).getTime()), plotBottom);
		area.closePath();

		long first = points.get(0).getValue();
		long last = points.get(points.size() - 1).getValue();
		Color tone = last >= first ? Theme.GOLD : Theme.mix(Theme.GOLD, Theme.LOSS, 0.45);
		g.setPaint(new GradientPaint(0, TOP, Theme.alpha(tone, 90), 0, plotBottom, Theme.alpha(tone, 0)));
		g.fill(area);

		// Where the range began.
		float base = yOf.apply(first);
		g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{3, 3}, 0));
		g.setColor(Theme.alpha(Theme.TEXT_MUTED, 110));
		g.drawLine(0, Math.round(base), w, Math.round(base));

		g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(Theme.alpha(Theme.INK_DEEP, 200));
		g.draw(line);
		g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setPaint(new GradientPaint(0, 0, Theme.mix(tone, Theme.INK, 0.25), w, 0, Theme.mix(tone, Theme.GOLD_BRIGHT, 0.6)));
		g.draw(line);
		g.setStroke(new BasicStroke(1));

		// High and low.
		marker(g, fm, xOf(points.get(hi).getTime()), yOf.apply(max), "High " + Format.coins(max), true, w, TOP, plotBottom);
		if (lo != hi)
		{
			marker(g, fm, xOf(points.get(lo).getTime()), yOf.apply(min), "Low " + Format.coins(min), false, w, TOP, plotBottom);
		}

		// The present, glowing.
		float ex = xOf(points.get(points.size() - 1).getTime());
		float ey = yOf.apply(last);
		g.setColor(Theme.alpha(Theme.GOLD_BRIGHT, 70));
		g.fill(new Ellipse2D.Float(ex - 5, ey - 5, 10, 10));
		g.setColor(Theme.GOLD_BRIGHT);
		g.fill(new Ellipse2D.Float(ex - 2.5f, ey - 2.5f, 5, 5));

		// Axis labels at the start, middle and end.
		g.setColor(Theme.TEXT_FAINT);
		for (int i = 0; i <= 2; i++)
		{
			long t = from + (to - from) * i / 2;
			String label = intraday ? Format.time(t) : to - from > 300L * 86_400_000L ? Format.month(t) : Format.shortDay(t);
			int lx = i == 0 ? 0 : i == 1 ? (w - fm.stringWidth(label)) / 2 : w - fm.stringWidth(label);
			g.drawString(label, lx, h - 3);
		}

		if (hover >= 0)
		{
			Ledger.Point p = points.get(hover);
			float hx = xOf(p.getTime());
			float hy = yOf.apply(p.getValue());
			g.setColor(Theme.alpha(Theme.GOLD, 120));
			g.drawLine(Math.round(hx), TOP, Math.round(hx), plotBottom);
			g.setColor(Theme.INK_DEEP);
			g.fill(new Ellipse2D.Float(hx - 4, hy - 4, 8, 8));
			g.setColor(Theme.GOLD_BRIGHT);
			g.fill(new Ellipse2D.Float(hx - 2.5f, hy - 2.5f, 5, 5));
			String when = intraday ? Format.time(p.getTime()) : Format.day(p.getTime()) + " " + Format.time(p.getTime());
			String worth = Format.coins(p.getValue());
			long delta = p.getValue() - first;
			String change = Format.signed(delta);
			int boxW = fm.stringWidth(when) + fm.stringWidth(worth) + fm.stringWidth(change) + 26;
			int bx = Math.max(0, Math.min(w - boxW, Math.round(hx - boxW / 2f)));
			RoundRectangle2D box = new RoundRectangle2D.Float(bx + 0.5f, 0.5f, boxW - 1, 16, 6, 6);
			g.setColor(Theme.alpha(Theme.INK_DEEP, 235));
			g.fill(box);
			g.setColor(Theme.alpha(Theme.GOLD, 140));
			g.draw(box);
			int tx = bx + 6;
			int ty = 12;
			Ui.shadowed(g, when, tx, ty, Theme.TEXT_MUTED);
			tx += fm.stringWidth(when) + 7;
			Ui.shadowed(g, worth, tx, ty, Theme.GOLD_BRIGHT);
			tx += fm.stringWidth(worth) + 7;
			Ui.shadowed(g, change, tx, ty, Theme.signed(delta));
		}
		g.dispose();
	}

	private static void marker(Graphics2D g, FontMetrics fm, float x, float y, String label, boolean above, int w, int top, int bottom)
	{
		Path2D tri = new Path2D.Float();
		if (above)
		{
			tri.moveTo(x, y - 3);
			tri.lineTo(x + 3, y - 7);
			tri.lineTo(x - 3, y - 7);
		}
		else
		{
			tri.moveTo(x, y + 3);
			tri.lineTo(x + 3, y + 7);
			tri.lineTo(x - 3, y + 7);
		}
		tri.closePath();
		Color c = above ? Theme.GAIN : Theme.LOSS;
		g.setColor(c);
		g.fill(tri);
		int lw = fm.stringWidth(label);
		int lx = Math.round(x + 6);
		if (lx + lw > w)
		{
			lx = Math.round(x - 6 - lw);
		}
		int ly = Math.round(above ? y - 3 : y + fm.getAscent() + 3);
		ly = Math.max(top + fm.getAscent() - 4, Math.min(bottom - 2, ly));
		Ui.shadowed(g, label, Math.max(0, lx), ly, Theme.mix(c, Theme.TEXT, 0.3));
	}
}
