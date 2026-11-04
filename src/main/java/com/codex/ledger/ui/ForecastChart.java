package com.codex.ledger.ui;

import com.codex.ledger.core.Projection;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.swing.JComponent;

/**
 * The worth forecast: the days behind as a gilt line, then the trend ahead as a dashed line inside a
 * teal band that widens the further it looks, the target as a line across, and a dot where the trend
 * meets it. The band is drawn, never just the line, so the chart cannot claim more than it knows.
 */
class ForecastChart extends JComponent
{
	private static final int TOP = 14;
	private static final int BOTTOM = 15;
	private static final DateTimeFormatter AXIS = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

	private final Projection p;
	private final int ahead;
	private final Long target;
	private final String label;

	/**
	 * @param ahead days drawn past today
	 * @param target the worth aimed at, or null to draw none
	 * @param label how the target is written on its line
	 */
	ForecastChart(Projection p, int ahead, Long target, String label)
	{
		this.p = p;
		this.ahead = Math.max(1, ahead);
		this.target = target;
		this.label = label;
		setOpaque(false);
		setPreferredSize(new Dimension(10, 128));
	}

	/** Days drawn ahead: to a little past the date the band says the target falls, between two weeks and two months. */
	static int ahead(Projection p, Projection.Eta eta)
	{
		Integer reach = eta == null ? null : eta.getLate() != null ? eta.getLate() : eta.getCentral();
		if (reach == null || reach <= 0)
		{
			return 30;
		}
		return Math.max(14, Math.min(60, (int) Math.ceil(reach * 1.15)));
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		int w = getWidth();
		int h = getHeight();
		int bottom = h - BOTTOM;
		g.setFont(Theme.small());
		FontMetrics fm = g.getFontMetrics();
		long[] values = p.getValues();
		int past = values.length - 1;
		int span = past + ahead;
		java.util.function.IntFunction<Float> xOf = d -> 2 + (w - 4) * (float) ((d + past) / (double) span);

		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		for (long v : values)
		{
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		for (int d = 1; d <= ahead; d++)
		{
			min = Math.min(min, p.low(d));
			max = Math.max(max, p.high(d));
		}
		boolean targetShown = target != null && target <= max + (max - min) * 0.6;
		if (targetShown)
		{
			max = Math.max(max, target);
			min = Math.min(min, target);
		}
		double pad = Math.max(1, max - min) * 0.1;
		double lo = min - pad;
		double hi = max + pad;
		java.util.function.DoubleFunction<Float> yOf = v -> (float) (bottom - (bottom - TOP) * ((v - lo) / (hi - lo)));

		// Guide lines.
		g.setColor(Theme.alpha(Theme.STROKE_SOFT, 150));
		for (int i = 0; i <= 2; i++)
		{
			int gy = TOP + (bottom - TOP) * i / 2;
			g.drawLine(0, gy, w, gy);
		}

		// The band, widening ahead.
		Path2D band = new Path2D.Float();
		band.moveTo(xOf.apply(0), yOf.apply(p.getWorth()));
		for (int d = 1; d <= ahead; d++)
		{
			band.lineTo(xOf.apply(d), yOf.apply(p.high(d)));
		}
		for (int d = ahead; d >= 1; d--)
		{
			band.lineTo(xOf.apply(d), yOf.apply(p.low(d)));
		}
		band.closePath();
		g.setPaint(new GradientPaint(xOf.apply(0), 0, Theme.alpha(Theme.TEAL, 95), w, 0, Theme.alpha(Theme.TEAL, 35)));
		g.fill(band);
		g.setColor(Theme.alpha(Theme.TEAL, 120));
		g.draw(band);

		// Today.
		float today = xOf.apply(0);
		g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{2, 3}, 0));
		g.setColor(Theme.alpha(Theme.TEXT_MUTED, 120));
		g.drawLine(Math.round(today), TOP - 4, Math.round(today), bottom);

		// The target.
		Integer meet = null;
		if (targetShown)
		{
			float ty = yOf.apply(target);
			g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{4, 3}, 0));
			g.setColor(Theme.alpha(Theme.GREEN, 170));
			g.drawLine(0, Math.round(ty), w, Math.round(ty));
			int lx = 3;
			int ly = Math.round(ty) - 3 < TOP ? Math.round(ty) + fm.getAscent() + 1 : Math.round(ty) - 3;
			Ui.shadowed(g, label, lx, ly, Theme.GREEN);
			Projection.Eta eta = p.eta(target);
			meet = eta.getCentral() != null && eta.getCentral() > 0 && eta.getCentral() <= ahead ? eta.getCentral() : null;
		}

		// The days behind.
		Path2D line = new Path2D.Float();
		for (int i = 0; i <= past; i++)
		{
			float x = xOf.apply(i - past);
			float y = yOf.apply(values[i]);
			if (i == 0)
			{
				line.moveTo(x, y);
			}
			else
			{
				line.lineTo(x, y);
			}
		}
		g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(Theme.alpha(Theme.INK_DEEP, 200));
		g.draw(line);
		g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(Theme.GOLD);
		g.draw(line);

		// The trend ahead.
		g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{4, 3}, 0));
		g.setColor(Theme.GOLD_BRIGHT);
		g.drawLine(Math.round(today), Math.round(yOf.apply(p.getWorth())), Math.round(xOf.apply(ahead)),
			Math.round(yOf.apply(p.mean(ahead))));
		g.setStroke(new BasicStroke(1));

		// Now, and where the trend meets the target.
		dot(g, today, yOf.apply(p.getWorth()), Theme.GOLD_BRIGHT);
		if (meet != null)
		{
			dot(g, xOf.apply(meet), yOf.apply(target), Theme.GREEN);
		}

		// The axis: where the history starts, today, and the far end.
		g.setColor(Theme.TEXT_FAINT);
		int base = h - 3;
		String start = AXIS.format(p.getFrom());
		String end = AXIS.format(p.getToday().plusDays(ahead));
		g.drawString(start, 2, base);
		g.drawString(end, w - fm.stringWidth(end) - 2, base);
		String now = "today";
		int nx = Math.round(today) - fm.stringWidth(now) / 2;
		if (nx > fm.stringWidth(start) + 8 && nx + fm.stringWidth(now) < w - fm.stringWidth(end) - 8)
		{
			g.setColor(Theme.TEXT_MUTED);
			g.drawString(now, nx, base);
		}
		// The band's top and bottom at the far end.
		g.setColor(Theme.alpha(Theme.TEAL, 230));
		String top = Format.coins(p.high(ahead));
		String low = Format.coins(p.low(ahead));
		Ui.shadowed(g, top, w - fm.stringWidth(top) - 2, Math.max(TOP - 3, Math.round(yOf.apply(p.high(ahead))) - 3), Theme.TEAL);
		Ui.shadowed(g, low, w - fm.stringWidth(low) - 2, Math.min(bottom - 1, Math.round(yOf.apply(p.low(ahead))) + fm.getAscent() + 1),
			Theme.TEAL);
		g.dispose();
	}

	private static void dot(Graphics2D g, float x, float y, Color color)
	{
		g.setColor(Theme.alpha(color, 70));
		g.fill(new Ellipse2D.Float(x - 5, y - 5, 10, 10));
		g.setColor(color);
		g.fill(new Ellipse2D.Float(x - 2.5f, y - 2.5f, 5, 5));
	}

	static LocalDate day(Projection p, int ahead)
	{
		return p.getToday().plusDays(ahead);
	}
}
