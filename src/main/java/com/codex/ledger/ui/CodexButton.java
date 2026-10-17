package com.codex.ledger.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JComponent;

/** A gilt-edged button; the primary style is filled, the quiet style is just an outline. */
public class CodexButton extends JComponent
{
	private final String text;
	private final boolean primary;
	private boolean hover;
	private boolean pressed;

	public CodexButton(String text, boolean primary, Runnable action)
	{
		this.text = text;
		this.primary = primary;
		setFont(Theme.small());
		setOpaque(false);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		FontMetrics fm = getFontMetrics(getFont());
		Dimension d = new Dimension(fm.stringWidth(text) + 20, 22);
		setPreferredSize(d);
		setMinimumSize(d);
		setMaximumSize(d);
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
				pressed = false;
				repaint();
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				pressed = true;
				repaint();
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				boolean fire = pressed && contains(e.getPoint());
				pressed = false;
				repaint();
				if (fire)
				{
					action.run();
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
		int dy = pressed ? 1 : 0;
		RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f + dy, w - 1, h - 2, 8, 8);
		Color text;
		if (primary)
		{
			Color top = hover ? Theme.GOLD_BRIGHT : Theme.GOLD;
			g.setPaint(new GradientPaint(0, 0, top, 0, h, Theme.GOLD_DIM));
			g.fill(shape);
			text = Theme.INK_DEEP;
		}
		else
		{
			g.setColor(hover ? Theme.alpha(Theme.GOLD, 40) : Theme.alpha(Theme.GOLD, 14));
			g.fill(shape);
			text = hover ? Theme.GOLD_BRIGHT : Theme.GOLD;
		}
		g.setColor(Theme.alpha(Theme.GOLD, primary ? 220 : 140));
		g.draw(shape);
		g.setFont(getFont());
		FontMetrics fm = g.getFontMetrics();
		g.setColor(text);
		g.drawString(this.text, (w - fm.stringWidth(this.text)) / 2, (h + fm.getAscent()) / 2 - 2 + dy);
		g.dispose();
	}
}
