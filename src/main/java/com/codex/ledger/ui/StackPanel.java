package com.codex.ledger.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.border.EmptyBorder;

/**
 * A vertical stack where every child spans the full width at its preferred height.
 * It tracks the viewport width so nothing ever scrolls sideways in the side panel.
 */
public class StackPanel extends JPanel implements Scrollable
{
	public StackPanel(int gap, int pad)
	{
		super(new StackLayout(gap));
		setOpaque(false);
		setBorder(new EmptyBorder(pad, pad, pad, pad));
	}

	public StackPanel space(int height)
	{
		JPanel spacer = new JPanel();
		spacer.setOpaque(false);
		spacer.setPreferredSize(new Dimension(1, height));
		add(spacer);
		return this;
	}

	@Override
	public Dimension getPreferredScrollableViewportSize()
	{
		return getPreferredSize();
	}

	@Override
	public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
	{
		return 16;
	}

	@Override
	public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
	{
		return Math.max(16, visibleRect.height - 32);
	}

	@Override
	public boolean getScrollableTracksViewportWidth()
	{
		return true;
	}

	@Override
	public boolean getScrollableTracksViewportHeight()
	{
		return false;
	}

	static final class StackLayout implements LayoutManager
	{
		private final int gap;

		StackLayout(int gap)
		{
			this.gap = gap;
		}

		@Override
		public void addLayoutComponent(String name, Component comp)
		{
		}

		@Override
		public void removeLayoutComponent(Component comp)
		{
		}

		@Override
		public Dimension preferredLayoutSize(Container parent)
		{
			Insets in = parent.getInsets();
			int width = 0;
			int height = 0;
			int visible = 0;
			for (Component c : parent.getComponents())
			{
				if (!c.isVisible())
				{
					continue;
				}
				Dimension d = c.getPreferredSize();
				width = Math.max(width, d.width);
				height += d.height;
				visible++;
			}
			height += Math.max(0, visible - 1) * gap;
			return new Dimension(width + in.left + in.right, height + in.top + in.bottom);
		}

		@Override
		public Dimension minimumLayoutSize(Container parent)
		{
			return preferredLayoutSize(parent);
		}

		@Override
		public void layoutContainer(Container parent)
		{
			Insets in = parent.getInsets();
			int width = parent.getWidth() - in.left - in.right;
			int y = in.top;
			for (Component c : parent.getComponents())
			{
				if (!c.isVisible())
				{
					continue;
				}
				int h = c.getPreferredSize().height;
				c.setBounds(in.left, y, width, h);
				y += h + gap;
			}
		}
	}
}
