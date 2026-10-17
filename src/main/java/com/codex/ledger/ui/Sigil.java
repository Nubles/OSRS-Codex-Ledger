package com.codex.ledger.ui;

import java.awt.BasicStroke;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/** The Ledger's mark: a stack of gilt coins with a teal line climbing past it to a bright point. */
public final class Sigil
{
	private Sigil()
	{
	}

	public static BufferedImage icon(int size)
	{
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		Theme.smooth(g);
		paint(g, 0, 0, size);
		g.dispose();
		return image;
	}

	/** Draws the mark in a size by size box. */
	public static void paint(Graphics2D g, float x, float y, float size)
	{
		float s = size / 16f;
		float stroke = Math.max(0.6f, 0.55f * s);

		// Three coins, the top one face up.
		float cx = x + 6.2f * s;
		float rw = 5.4f * s;
		float rh = 2.1f * s;
		for (int i = 0; i < 3; i++)
		{
			float cy = y + (13.4f - i * 2.6f) * s;
			Path2D edge = new Path2D.Float();
			edge.append(new Ellipse2D.Float(cx - rw, cy - rh + 1.2f * s, rw * 2, rh * 2), false);
			g.setPaint(new GradientPaint(cx - rw, 0, Theme.GOLD_DIM, cx + rw, 0, Theme.mix(Theme.GOLD_DIM, Theme.INK_DEEP, 0.4)));
			g.fill(edge);
			Ellipse2D face = new Ellipse2D.Float(cx - rw, cy - rh, rw * 2, rh * 2);
			g.setPaint(new GradientPaint(cx - rw, cy - rh, Theme.GOLD_BRIGHT, cx + rw, cy + rh, Theme.GOLD));
			g.fill(face);
			g.setColor(Theme.INK_DEEP);
			g.setStroke(new BasicStroke(stroke));
			g.draw(face);
		}
		g.setColor(Theme.alpha(Theme.INK_DEEP, 150));
		g.draw(new Ellipse2D.Float(cx - rw * 0.55f, y + 8.2f * s - rh * 0.55f, rw * 1.1f, rh * 1.1f));

		// The line, rising from the left past the coins.
		Path2D line = new Path2D.Float();
		line.moveTo(x + 1.2f * s, y + 6.6f * s);
		line.lineTo(x + 5.2f * s, y + 4.4f * s);
		line.lineTo(x + 8.4f * s, y + 5.6f * s);
		line.lineTo(x + 13.6f * s, y + 1.8f * s);
		g.setStroke(new BasicStroke(Math.max(2.2f, 2.4f * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(Theme.INK_DEEP);
		g.draw(line);
		g.setStroke(new BasicStroke(Math.max(1.1f, 1.3f * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(Theme.TEAL);
		g.draw(line);

		// A bright point where it ends.
		float r = 1.7f * s;
		g.setColor(Theme.alpha(Theme.TEAL, 90));
		g.fill(new Ellipse2D.Float(x + 13.6f * s - r * 1.7f, y + 1.8f * s - r * 1.7f, r * 3.4f, r * 3.4f));
		g.setColor(Theme.mix(Theme.TEAL, Theme.TEXT, 0.5));
		g.fill(new Ellipse2D.Float(x + 13.6f * s - r, y + 1.8f * s - r, r * 2, r * 2));
	}
}
