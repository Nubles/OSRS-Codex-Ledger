package com.codex.ledger.ui;

import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JComponent;

/** A gilt progress bar in an ink groove, with a notch every quarter of the way. */
class GoalBar extends JComponent
{
	private static final int H = 8;
	private final double progress;

	GoalBar(double progress)
	{
		this.progress = Math.max(0, Math.min(1, progress));
		setOpaque(false);
		setPreferredSize(new Dimension(10, H + 2));
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		int w = getWidth();
		RoundRectangle2D groove = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, H, H, H);
		g.setColor(Theme.INK_DEEP);
		g.fill(groove);
		g.setColor(Theme.STROKE_SOFT);
		g.draw(groove);
		if (progress > 0)
		{
			float fill = (float) Math.max(H, (w - 3) * progress);
			g.setPaint(new GradientPaint(0, 0, Theme.GOLD_DIM, w, 0, progress >= 1 ? Theme.GREEN : Theme.GOLD_BRIGHT));
			g.fill(new RoundRectangle2D.Float(1.5f, 1.5f, fill, H - 2, H - 2, H - 2));
		}
		g.setColor(Theme.alpha(Theme.INK, 160));
		for (int i = 1; i < 4; i++)
		{
			g.fillRect(w * i / 4, 2, 1, H - 3);
		}
		g.dispose();
	}
}
