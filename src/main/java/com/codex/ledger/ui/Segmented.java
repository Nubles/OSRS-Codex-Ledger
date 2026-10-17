package com.codex.ledger.ui;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.function.IntConsumer;
import javax.swing.JComponent;

/**
 * Tabs drawn as one engraved strip: the chosen segment is lit from below by a
 * gold glow, the rest sit quietly in the ink.
 */
public class Segmented extends JComponent
{
	private final String[] labels;
	private final boolean large;
	private final Animator slide = new Animator(this);
	private int selected;
	private int hover = -1;
	private IntConsumer listener;

	public Segmented(boolean large, String... labels)
	{
		this.labels = labels;
		this.large = large;
		setFont(large ? Theme.title() : Theme.small());
		int h = large ? 28 : 22;
		setPreferredSize(new Dimension(10, h));
		setMinimumSize(new Dimension(10, h));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				int index = indexAt(e.getX());
				if (index != hover)
				{
					hover = index;
					repaint();
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hover = -1;
				repaint();
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				setSelected(indexAt(e.getX()), true);
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	public void onChange(IntConsumer listener)
	{
		this.listener = listener;
	}

	public int getSelected()
	{
		return selected;
	}

	public void setSelected(int index, boolean notify)
	{
		if (index < 0 || index >= labels.length)
		{
			return;
		}
		boolean changed = index != selected;
		selected = index;
		slide.set(index, true);
		repaint();
		if (changed && notify && listener != null)
		{
			listener.accept(index);
		}
	}

	private int indexAt(int x)
	{
		int w = Math.max(1, getWidth());
		return Math.max(0, Math.min(labels.length - 1, x * labels.length / w));
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		int w = getWidth();
		int h = getHeight();
		float seg = w / (float) labels.length;

		RoundRectangle2D strip = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 8, 8);
		g.setColor(Theme.INK_DEEP);
		g.fill(strip);

		float x = (float) (slide.get() * seg);
		RoundRectangle2D lit = new RoundRectangle2D.Float(x + 2, 2, seg - 4, h - 4, 6, 6);
		g.setPaint(new GradientPaint(0, 2, Theme.CARD_TOP, 0, h, Theme.mix(Theme.CARD_TOP, Theme.GOLD_DIM, 0.35)));
		g.fill(lit);
		g.setColor(Theme.alpha(Theme.GOLD, 120));
		g.draw(lit);
		g.setColor(Theme.GOLD_BRIGHT);
		g.fillRect(Math.round(x + seg * 0.3f), h - 4, Math.round(seg * 0.4f), 1);

		g.setFont(getFont());
		FontMetrics fm = g.getFontMetrics();
		for (int i = 0; i < labels.length; i++)
		{
			int tx = Math.round(i * seg + (seg - fm.stringWidth(labels[i])) / 2);
			int ty = (h + fm.getAscent()) / 2 - (large ? 2 : 1);
			java.awt.Color color = i == selected ? Theme.GOLD_BRIGHT : i == hover ? Theme.TEXT : Theme.TEXT_MUTED;
			Ui.shadowed(g, labels[i], tx, ty, color);
		}
		g.setColor(Theme.STROKE_SOFT);
		g.draw(strip);
		g.dispose();
	}
}
