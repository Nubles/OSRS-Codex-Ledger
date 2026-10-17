package com.codex.ledger.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * A rounded, softly lit surface. Optionally clickable, with a hover lift and an
 * accent stripe down the left edge.
 */
public class Card extends JPanel
{
	private Color accent;
	private boolean hover;
	private boolean highlighted;
	private Runnable onClick;

	public Card(LayoutManager layout)
	{
		super(layout);
		setOpaque(false);
		setBorder(new EmptyBorder(8, 10, 8, 10));
	}

	public Card accent(Color color)
	{
		accent = color;
		repaint();
		return this;
	}

	/** A brighter rim for the card that matters most on screen. */
	public Card highlighted(boolean value)
	{
		highlighted = value;
		repaint();
		return this;
	}

	public Card onClick(Runnable action)
	{
		onClick = action;
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		MouseAdapter adapter = new MouseAdapter()
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
				// Children forward their events here, so only clear hover when the pointer has really left.
				if (!contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), Card.this)))
				{
					hover = false;
					repaint();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e) && onClick != null
					&& contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), Card.this)))
				{
					onClick.run();
				}
			}
		};
		addMouseListener(adapter);
		Ui.forwardMouse(this, adapter);
		return this;
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		int w = getWidth();
		int h = getHeight();
		RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, Theme.RADIUS, Theme.RADIUS);

		Color top = hover ? Theme.CARD_HOVER : Theme.CARD_TOP;
		Color bottom = hover ? Theme.mix(Theme.CARD_HOVER, Theme.CARD, 0.5) : Theme.CARD;
		g.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
		g.fill(shape);

		if (accent != null)
		{
			g.setClip(shape);
			g.setPaint(new GradientPaint(0, 0, accent, 0, h, Theme.alpha(accent, 90)));
			g.fillRect(0, 0, 3, h);
			g.setClip(null);
		}

		Color rim = highlighted ? Theme.alpha(Theme.GOLD, 150) : hover ? Theme.alpha(Theme.GOLD, 90) : Theme.STROKE_SOFT;
		g.setColor(rim);
		g.draw(shape);

		// A one pixel sheen along the top edge, like light on vellum.
		g.setColor(Theme.alpha(Color.WHITE, hover ? 22 : 12));
		g.drawLine(Theme.RADIUS / 2, 1, w - Theme.RADIUS / 2, 1);
		g.dispose();
		super.paintComponent(graphics);
	}
}
