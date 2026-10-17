package com.codex.ledger.overlay;

import com.codex.ledger.ui.Format;
import com.codex.ledger.ui.Sigil;
import com.codex.ledger.ui.Theme;
import com.codex.ledger.ui.Ui;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;

/** A slim gilt plaque in the game view with the account's worth and today's change. It draws what the plugin hands it. */
public class WorthPlaque extends Overlay
{
	private static final int PAD = 7;

	private volatile long[] view;
	private volatile boolean enabled;

	public WorthPlaque()
	{
		setPosition(OverlayPosition.TOP_LEFT);
	}

	/** @param worth the worth, or null to hide the plaque */
	public void show(Long worth, long today)
	{
		view = worth == null ? null : new long[]{worth, today};
	}

	public void setEnabled(boolean enabled)
	{
		this.enabled = enabled;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		long[] v = view;
		if (!enabled || v == null)
		{
			return null;
		}
		Theme.smooth(g);
		String worth = Format.coins(v[0]);
		String today = Format.signed(v[1]) + " today";

		g.setFont(Theme.title());
		FontMetrics bold = g.getFontMetrics();
		g.setFont(Theme.small());
		FontMetrics small = g.getFontMetrics();
		int icon = 14;
		int width = PAD + icon + 6 + bold.stringWidth(worth) + 10 + small.stringWidth(today) + PAD;
		int height = Math.max(icon, bold.getHeight()) + PAD;

		RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, width - 1, height - 1, 10, 10);
		g.setPaint(new GradientPaint(0, 0, Theme.alpha(Theme.CARD_TOP, 230), 0, height, Theme.alpha(Theme.INK_DEEP, 230)));
		g.fill(shape);
		g.setColor(Theme.alpha(Theme.GOLD, 170));
		g.draw(shape);

		int x = PAD;
		Sigil.paint(g, x, (height - icon) / 2f, icon);
		x += icon + 6;
		int base = (height + bold.getAscent()) / 2 - 2;
		g.setFont(Theme.title());
		Ui.shadowed(g, worth, x, base, Theme.GOLD_BRIGHT);
		x += bold.stringWidth(worth) + 10;
		g.setFont(Theme.small());
		Ui.shadowed(g, today, x, base, Theme.signed(v[1]));
		return new Dimension(width, height);
	}
}
