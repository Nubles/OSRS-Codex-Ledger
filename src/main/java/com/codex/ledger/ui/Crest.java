package com.codex.ledger.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * The crest at the top of the panel: the Ledger's medallion, the account's worth in large gilt
 * figures, and what it did today, this week and this month.
 */
public class Crest extends JComponent
{
	private static final int HEIGHT = 104;

	private final Animator count = new Animator(this);
	private String player;
	private String status = "Log in to open the ledger";
	private long total;
	private boolean known;
	private final long[] changes = new long[3];
	private final boolean[] hasChange = new boolean[3];
	private static final String[] PERIODS = {"Today", "Week", "Month"};

	// The large figure is drawn small then doubled; while the count runs this would be a new image
	// every frame, so the last one is kept and reused until its text changes.
	private String largeText;
	private BufferedImage largeImage;
	private int largeAscent;

	public Crest()
	{
		setOpaque(false);
		setPreferredSize(new Dimension(10, HEIGHT));
		setMinimumSize(new Dimension(10, HEIGHT));
	}

	/**
	 * @param status a line in place of the changes, or null to show them
	 * @param changes today, the week and the month; null for any not yet known
	 */
	public void update(String player, Long total, String status, Long[] changes)
	{
		this.player = player;
		this.status = status;
		this.known = total != null;
		if (total != null)
		{
			boolean animate = this.total != 0 && Math.abs(total - this.total) < Math.max(1, this.total);
			this.total = total;
			count.set(total, animate);
		}
		else
		{
			// The next figure shown, perhaps another account's, appears at once rather than counting from this one.
			this.total = 0;
		}
		for (int i = 0; i < 3; i++)
		{
			hasChange[i] = changes != null && changes[i] != null;
			this.changes[i] = hasChange[i] ? changes[i] : 0;
		}
		repaint();
	}

	/**
	 * Draws text at twice the size of the game's bold font by doubling every pixel, the way the
	 * game itself draws large numbers, so the figures stay crisp instead of being re-rasterised.
	 *
	 * @return the width drawn
	 */
	private int paintLarge(Graphics2D g, String text, int x, int y)
	{
		if (!text.equals(largeText) || largeImage == null)
		{
			Font font = Theme.title();
			FontMetrics fm = g.getFontMetrics(font);
			largeImage = renderSmall(text, font, fm);
			largeAscent = fm.getAscent();
			largeText = text;
		}
		int w = largeImage.getWidth();
		int h = largeImage.getHeight();
		Graphics2D lg = (Graphics2D) g.create();
		lg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		lg.drawImage(largeImage, x, y - 2 * largeAscent + 22, w * 2, h * 2, null);
		lg.dispose();
		return w * 2;
	}

	private static BufferedImage renderSmall(String text, Font font, FontMetrics fm)
	{
		int w = Math.max(1, fm.stringWidth(text) + 1);
		int h = fm.getHeight() + 1;
		BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D sg = small.createGraphics();
		sg.setFont(font);
		sg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		int base = fm.getAscent();
		sg.setColor(Color.BLACK);
		sg.drawString(text, 1, base + 1);
		sg.setPaint(new GradientPaint(0, base - fm.getAscent() + 3, Theme.GOLD_BRIGHT, 0, base, Theme.GOLD));
		sg.drawString(text, 0, base);
		sg.dispose();
		return small;
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.smooth(g);
		int w = getWidth();
		int h = getHeight();
		g.setPaint(new GradientPaint(0, 0, Theme.mix(Theme.INK, Theme.GOLD_DIM, 0.22), 0, h, Theme.INK));
		g.fillRect(0, 0, w, h);

		// The medallion: a gilt ring with the mark inside, lit from above.
		int mx = 10;
		int my = 14;
		int md = 54;
		g.setPaint(new RadialGradientPaint(mx + md / 2f, my + md / 3f, md * 0.7f, new float[]{0, 1},
			new Color[]{Theme.mix(Theme.CARD_TOP, Theme.GOLD_DIM, 0.35), Theme.INK_DEEP}));
		g.fill(new Ellipse2D.Float(mx, my, md, md));
		g.setStroke(new BasicStroke(2f));
		g.setPaint(new GradientPaint(mx, my, Theme.GOLD_BRIGHT, mx, my + md, Theme.GOLD_DIM));
		g.draw(new Ellipse2D.Float(mx + 1, my + 1, md - 2, md - 2));
		g.setStroke(new BasicStroke(1f));
		g.setColor(Theme.alpha(Theme.GOLD, 70));
		g.draw(new Ellipse2D.Float(mx + 5, my + 5, md - 10, md - 10));
		Sigil.paint(g, mx + 11, my + 11, md - 22);

		int tx = mx + md + 12;
		int room = w - tx - 8;
		g.setFont(Theme.small());
		FontMetrics small = g.getFontMetrics();
		String heading = (player != null ? player + "  \u00b7  " : "") + "NET WORTH";
		g.setColor(Theme.TEXT_MUTED);
		g.drawString(Ui.clip(heading, small, room), tx, 22);

		String figure = known ? Format.coins(Math.round(count.get())) : "-";
		int top = 27;
		int figureW = paintLarge(g, figure, tx, top);
		if (known)
		{
			g.setFont(Theme.small());
			g.setColor(Theme.GOLD_DIM);
			g.drawString("gp", tx + figureW + 3, top + 18);
		}
		int fy = top + 26;
		int cy = fy + 24;
		g.setFont(Theme.small());
		if (status != null)
		{
			g.setColor(Theme.mix(Theme.TEXT_MUTED, Theme.GOLD, 0.3));
			g.drawString(Ui.clip(status, small, room), tx, cy);
		}
		else
		{
			int cw = room / 3;
			for (int i = 0; i < 3; i++)
			{
				int x = tx + i * cw;
				g.setColor(Theme.TEXT_FAINT);
				g.drawString(PERIODS[i], x, cy - 11);
				String value = hasChange[i] ? Format.signedCompact(changes[i]) : "-";
				Ui.shadowed(g, Ui.clip(value, small, cw - 4), x, cy + 1, hasChange[i] ? Theme.signed(changes[i]) : Theme.TEXT_FAINT);
			}
		}

		// The gilt rule under the crest, with a diamond at its middle.
		int y = h - 2;
		g.setPaint(new GradientPaint(0, 0, Theme.alpha(Theme.GOLD, 0), w / 2f, 0, Theme.alpha(Theme.GOLD, 150), true));
		g.drawLine(0, y, w, y);
		Path2D diamond = new Path2D.Float();
		diamond.moveTo(w / 2f, y - 3);
		diamond.lineTo(w / 2f + 3, y);
		diamond.lineTo(w / 2f, y + 3);
		diamond.lineTo(w / 2f - 3, y);
		diamond.closePath();
		g.setColor(Theme.GOLD);
		g.fill(diamond);
		g.dispose();
	}
}
