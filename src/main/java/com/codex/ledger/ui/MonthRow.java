package com.codex.ledger.ui;

import com.codex.ledger.core.Months;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.swing.JComponent;

/**
 * One month of the day book as a line of a statement: the month and what it made, the worth it
 * closed on, its best day, and the same two slim bars as a page for how much was play and how
 * much was market, scaled against the busiest month listed so months compare at a glance.
 */
class MonthRow extends JComponent
{
	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
	private static final DateTimeFormatter BEST = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
	private static final int BAR_W = 70;

	private final Months.Month month;
	private final long scale;

	MonthRow(Months.Month month, long scale)
	{
		this.month = month;
		this.scale = Math.max(1, scale);
		setOpaque(false);
		setPreferredSize(new Dimension(10, 40));
		setToolTipText(tip(month));
	}

	/** "October 2026". */
	static String title(Months.Month m)
	{
		return MONTH.format(m.getMonth());
	}

	/** "best 13 Oct +12.4M", or null when no day of the month gained. */
	static String best(Months.Month m)
	{
		return m.getBest() == null ? null : "best " + BEST.format(m.getBest()) + " " + Format.signed(m.getBestChange());
	}

	static String tip(Months.Month m)
	{
		StringBuilder tip = new StringBuilder(title(m)).append(m.isCurrent() ? " so far: " : ": ")
			.append("play ").append(Format.signed(m.getPlay())).append(", market ").append(Format.signed(m.getMarket()))
			.append(". ").append(m.isCurrent() ? "Worth now " : "Closed on ").append(Format.coins(m.getClose()))
			.append(", over ").append(Format.plural(m.getPages(), "day")).append(" in the day book");
		if (m.getPlayedMs() >= 60_000)
		{
			tip.append(" and ").append(Format.duration(m.getPlayedMs())).append(" logged in");
		}
		tip.append(".");
		if (m.getBest() != null)
		{
			tip.append(" Best day ").append(Format.day(m.getBest())).append(", ").append(Format.signed(m.getBestChange())).append(".");
		}
		return tip.toString();
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		int w = getWidth();
		int h = getHeight();
		RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, Theme.RADIUS, Theme.RADIUS);
		g.setPaint(new GradientPaint(0, 0, Theme.CARD_TOP, 0, h, Theme.CARD));
		g.fill(shape);
		long change = month.change();
		g.setClip(shape);
		g.setColor(Theme.signed(change));
		g.fillRect(0, 0, 3, h);
		g.setClip(null);
		g.setColor(month.isCurrent() ? Theme.alpha(Theme.GOLD, 120) : Theme.STROKE_SOFT);
		g.draw(shape);

		g.setFont(Theme.body());
		FontMetrics fm = g.getFontMetrics();
		String text = Format.signed(change);
		int tw = fm.stringWidth(text);
		Ui.shadowed(g, text, w - 9 - tw, 4 + fm.getAscent(), Theme.signed(change));
		String name = title(month);
		Ui.shadowed(g, Ui.clip(name, fm, w - 9 - tw - 6 - 10), 10, 4 + fm.getAscent(), month.isCurrent() ? Theme.GOLD_BRIGHT : Theme.TEXT);

		g.setFont(Theme.small());
		FontMetrics small = g.getFontMetrics();
		int bx = w - 9 - BAR_W;
		// "so far" for the month still running, else what it closed on; the best day follows when it fits.
		String foot = month.isCurrent() ? "so far" : "closed " + Format.coins(month.getClose());
		String best = best(month);
		int room = bx - 6 - 10;
		if (best != null && small.stringWidth(foot + " · " + best) <= room)
		{
			foot = foot + " · " + best;
		}
		else if (best != null && month.isCurrent() && small.stringWidth(best) <= room)
		{
			foot = best;
		}
		g.setColor(Theme.TEXT_FAINT);
		g.drawString(Ui.clip(foot, small, room), 10, h - 13);

		// Play and market side by side, as on each page.
		int by = h - 17;
		g.setColor(Theme.INK_DEEP);
		g.fill(new RoundRectangle2D.Float(bx, by, BAR_W, 4, 4, 4));
		g.fill(new RoundRectangle2D.Float(bx, by + 6, BAR_W, 4, 4, 4));
		bar(g, bx, by, month.getPlay(), month.getPlay() >= 0 ? Theme.GAIN : Theme.LOSS);
		bar(g, bx, by + 6, month.getMarket(), month.getMarket() >= 0 ? Theme.MARKET : Theme.EMBER);
		g.dispose();
	}

	private void bar(Graphics2D g, int x, int y, long value, java.awt.Color color)
	{
		if (value == 0)
		{
			return;
		}
		float len = Math.max(2, BAR_W * (float) Math.min(1, Math.abs(value) / (double) scale));
		g.setColor(color);
		g.fill(new RoundRectangle2D.Float(x, y, len, 4, 4, 4));
	}
}
