package com.codex.ledger.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;

/**
 * Where the gold came from and went, one row a channel: its name, a bar growing right for gold
 * gained and left for gold lost from a gilt centre line, all on one scale, and the amount.
 */
class ChannelRows extends JComponent
{
	private static final int ROW = 18;
	private static final int LABEL = 58;
	private static final int VALUE = 44;

	/** One row: a channel's name, its amount and the colours it is drawn in either way. */
	static final class Row
	{
		final String label;
		final long value;
		final Color up;
		final Color down;

		Row(String label, long value, Color up, Color down)
		{
			this.label = label;
			this.value = value;
			this.up = up;
			this.down = down;
		}
	}

	private final List<Row> rows;

	ChannelRows(List<Row> rows, String tip)
	{
		this.rows = new ArrayList<>(rows);
		setOpaque(false);
		setPreferredSize(new Dimension(10, ROW * Math.max(1, rows.size())));
		setToolTipText(tip);
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		long scale = 1;
		for (Row r : rows)
		{
			scale = Math.max(scale, Math.abs(r.value));
		}
		for (int i = 0; i < rows.size(); i++)
		{
			row(g, i * ROW, rows.get(i), scale);
		}
		g.dispose();
	}

	private void row(Graphics2D g, int y, Row r, long scale)
	{
		int w = getWidth();
		Color color = r.value >= 0 ? r.up : r.down;
		g.setFont(Theme.small());
		FontMetrics fm = g.getFontMetrics();
		int mid = y + ROW / 2;
		int base = mid + fm.getAscent() / 2 - 1;
		g.setColor(Theme.TEXT_MUTED);
		g.drawString(Ui.clip(r.label, fm, LABEL - 4), 0, base);
		String text = Format.signedCompact(r.value);
		Ui.shadowed(g, text, w - fm.stringWidth(text), base, r.value == 0 ? Theme.TEXT_FAINT : Theme.mix(color, Theme.TEXT, 0.2));

		int left = LABEL;
		int right = w - VALUE;
		int centre = (left + right) / 2;
		int half = (right - left) / 2;
		g.setColor(Theme.INK_DEEP);
		g.fill(new RoundRectangle2D.Float(left, mid - 2.5f, right - left, 5, 5, 5));
		if (r.value != 0)
		{
			float len = Math.max(2, (float) (half * Math.min(1, Math.abs(r.value) / (double) scale)));
			float x = r.value > 0 ? centre : centre - len;
			g.setPaint(new GradientPaint(centre, 0, Theme.mix(color, Theme.INK, 0.35), r.value > 0 ? centre + half : centre - half, 0, color));
			g.fill(new RoundRectangle2D.Float(x, mid - 2.5f, len, 5, 5, 5));
		}
		g.setColor(Theme.GOLD);
		g.fillRect(centre, mid - 4, 1, 8);
	}
}
