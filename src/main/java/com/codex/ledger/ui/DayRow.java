package com.codex.ledger.ui;

import com.codex.ledger.core.Day;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.time.LocalDate;
import javax.swing.JComponent;

/**
 * One page of the day book in the list: the date and the worth it closed on, the change, and a
 * slim two-part bar for how much of it was play and how much was market.
 */
class DayRow extends JComponent
{
	private final Day day;
	private final long scale;
	private final boolean selected;
	private boolean hover;

	DayRow(Day day, long scale, boolean selected, Runnable onClick)
	{
		this.day = day;
		this.scale = Math.max(1, scale);
		this.selected = selected;
		setOpaque(false);
		setPreferredSize(new Dimension(10, 40));
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		setToolTipText("Play " + Format.signed(day.getPlay()) + ", market " + Format.signed(day.getMarket())
			+ ". High " + Format.coins(day.getHigh()) + ", low " + Format.coins(day.getLow()) + ".");
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				hover = true;
				repaint();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hover = false;
				repaint();
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (contains(e.getPoint()))
				{
					onClick.run();
				}
			}
		});
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		int w = getWidth();
		int h = getHeight();
		RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, Theme.RADIUS, Theme.RADIUS);
		g.setPaint(new GradientPaint(0, 0, hover ? Theme.CARD_HOVER : Theme.CARD_TOP, 0, h, Theme.CARD));
		g.fill(shape);
		long change = day.change();
		g.setClip(shape);
		g.setColor(Theme.signed(change));
		g.fillRect(0, 0, 3, h);
		g.setClip(null);
		g.setColor(selected ? Theme.alpha(Theme.GOLD, 160) : hover ? Theme.alpha(Theme.GOLD, 90) : Theme.STROKE_SOFT);
		g.draw(shape);

		g.setFont(Theme.body());
		FontMetrics fm = g.getFontMetrics();
		String date = Format.day(LocalDate.parse(day.getDate()));
		Ui.shadowed(g, date, 10, 4 + fm.getAscent(), selected ? Theme.GOLD_BRIGHT : Theme.TEXT);
		String text = Format.signed(change);
		Ui.shadowed(g, text, w - 9 - fm.stringWidth(text), 4 + fm.getAscent(), Theme.signed(change));

		g.setFont(Theme.small());
		FontMetrics small = g.getFontMetrics();
		g.setColor(Theme.TEXT_FAINT);
		g.drawString("closed " + Format.coins(day.getClose()), 10, h - 13);
		if (day.getPlayedMs() >= 60_000)
		{
			// Time logged in sits after the date, when it fits before the change.
			String played = Format.duration(day.getPlayedMs());
			int px = 10 + fm.stringWidth(date) + 6;
			if (px + small.stringWidth(played) < w - 9 - fm.stringWidth(text) - 6)
			{
				g.drawString(played, px, 4 + fm.getAscent());
			}
		}

		// Play and market side by side, scaled against the busiest day in the list.
		int bx = w - 9 - 70;
		int by = h - 17;
		int bw = 70;
		g.setColor(Theme.INK_DEEP);
		g.fill(new RoundRectangle2D.Float(bx, by, bw, 4, 4, 4));
		g.fill(new RoundRectangle2D.Float(bx, by + 6, bw, 4, 4, 4));
		bar(g, bx, by, bw, day.getPlay(), day.getPlay() >= 0 ? Theme.GAIN : Theme.LOSS);
		bar(g, bx, by + 6, bw, day.getMarket(), day.getMarket() >= 0 ? Theme.MARKET : Theme.EMBER);
		g.dispose();
	}

	private void bar(Graphics2D g, int x, int y, int w, long value, java.awt.Color color)
	{
		if (value == 0)
		{
			return;
		}
		float len = Math.max(2, w * (float) Math.min(1, Math.abs(value) / (double) scale));
		g.setColor(color);
		g.fill(new RoundRectangle2D.Float(x, y, len, 4, 4, 4));
	}
}
