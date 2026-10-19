package com.codex.ledger.ui;

import com.codex.ledger.core.Milestones;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JComponent;

/**
 * One milestone: the round figure struck on a gilt coin, the day the worth first passed it, and
 * how long it took from the milestone before. The newest is lit; older ones are struck in bronze.
 */
class MilestoneRow extends JComponent
{
	static final int HEIGHT = 36;
	private static final int COIN = 24;

	private final Milestones.Mark mark;
	private final boolean newest;

	MilestoneRow(Milestones.Mark mark, boolean newest)
	{
		this.mark = mark;
		this.newest = newest;
		setOpaque(false);
		setPreferredSize(new Dimension(10, HEIGHT));
		setToolTipText("Your worth first passed " + Format.coins(mark.getAmount()) + " on " + Format.date(mark.getAt()) + ".");
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		paint(g, mark, newest, 0, 0, getWidth());
		g.dispose();
	}

	/** Draws the row; the ledger card draws the same. */
	static void paint(Graphics2D g, Milestones.Mark mark, boolean newest, int x, int y, int w)
	{
		RoundRectangle2D box = new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, HEIGHT - 1, Theme.RADIUS, Theme.RADIUS);
		g.setPaint(new GradientPaint(x, y, Theme.CARD_TOP, x, y + HEIGHT, Theme.CARD));
		g.fill(box);
		g.setColor(newest ? Theme.alpha(Theme.GOLD, 150) : Theme.STROKE_SOFT);
		g.draw(box);

		// The coin: gilt for the newest figure, bronze for the ones before.
		Color face = newest ? Theme.GOLD : Theme.mix(Theme.GOLD_DIM, Theme.EMBER, 0.25);
		float cx = x + 6;
		float cy = y + (HEIGHT - COIN) / 2f;
		g.setPaint(new GradientPaint(cx, cy, Theme.mix(face, Theme.TEXT, 0.35), cx, cy + COIN, Theme.mix(face, Theme.INK_DEEP, 0.45)));
		g.fill(new Ellipse2D.Float(cx, cy, COIN, COIN));
		g.setStroke(new BasicStroke(1f));
		g.setColor(Theme.alpha(Theme.INK_DEEP, 150));
		g.draw(new Ellipse2D.Float(cx + 2.5f, cy + 2.5f, COIN - 5, COIN - 5));
		// A stamped diamond, the suite's mark, in the coin's face.
		java.awt.geom.Path2D stamp = new java.awt.geom.Path2D.Float();
		float sx = cx + COIN / 2f;
		float sy = cy + COIN / 2f;
		stamp.moveTo(sx, sy - 5);
		stamp.lineTo(sx + 5, sy);
		stamp.lineTo(sx, sy + 5);
		stamp.lineTo(sx - 5, sy);
		stamp.closePath();
		g.setColor(Theme.alpha(Theme.INK_DEEP, 130));
		g.fill(stamp);
		g.setFont(Theme.small());
		FontMetrics small = g.getFontMetrics();
		String figure = Format.coins(mark.getAmount());

		int tx = Math.round(cx + COIN + 9);
		g.setFont(Theme.body());
		FontMetrics fm = g.getFontMetrics();
		String title = "Passed " + figure;
		Ui.shadowed(g, title, tx, y + 6 + fm.getAscent() - 2, newest ? Theme.GOLD_BRIGHT : Theme.TEXT);
		String when = Format.day(mark.getAt());
		g.setFont(Theme.small());
		g.setColor(Theme.TEXT_MUTED);
		g.drawString(when, x + w - 9 - small.stringWidth(when), y + 6 + fm.getAscent() - 2);
		String after = mark.getAfter() < 0 ? "the first the ledger saw" : Format.gap(mark.getAfter()) + " after " + Format.coins(mark.getPrevious());
		g.setColor(Theme.TEXT_FAINT);
		g.drawString(Ui.clip(after, small, x + w - 9 - tx), tx, y + HEIGHT - 8);
	}
}
