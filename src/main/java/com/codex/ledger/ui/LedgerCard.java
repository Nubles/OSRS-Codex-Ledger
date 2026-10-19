package com.codex.ledger.ui;

import com.codex.ledger.core.Earnings;
import com.codex.ledger.core.Forecast;
import com.codex.ledger.core.Kind;
import com.codex.ledger.core.Ledger;
import com.codex.ledger.core.Milestones;
import com.codex.ledger.core.Report;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * The ledger as a picture to share, a statement of account: the worth in large gilt figures with
 * what it did today, this week and this month, the last thirty days as a line, what the worth is
 * made of, the five most valuable things held, the round figures passed, what play paid best by
 * Codex Session Chronicle's sessions, and the goal with when it should fall. Drawn at twice the
 * panel's scale on the suite's gilt page so the game's pixel fonts stay crisp.
 */
public final class LedgerCard
{
	static final int WIDTH = 360;
	static final int SCALE = 2;
	static final int HELD = 5;
	static final int PAYS = 3;
	private static final int PAD = 20;
	private static final int CHART_H = 64;
	private static final int ROW = 22;
	private static final long MONTH_MS = 30 * 86_400_000L;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
	private static final String[] PERIODS = {"Today", "This week", "This month"};

	private LedgerCard()
	{
	}

	/** One item among the most valuable. */
	static final class Held
	{
		final int itemId;
		final String name;
		final long value;

		Held(int itemId, String name, long value)
		{
			this.itemId = itemId;
			this.name = name;
			this.value = value;
		}
	}

	/** Everything the card shows, taken from the ledger once so drawing it twice reads the same. */
	public static final class Statement
	{
		final String player;
		final long total;
		final Long[] changes;
		final List<Ledger.Point> points;
		final long from;
		final long to;
		final long[] kinds;
		final List<Held> held;
		final Milestones milestones;
		final List<Earnings.Line> pays;
		final int paysDays;
		final Forecast forecast;
		final String goalName;
		final long since;
		final long now;
		final ZoneId zone;

		private Statement(String player, long total, Long[] changes, List<Ledger.Point> points, long from, long to, long[] kinds,
			List<Held> held, Milestones milestones, List<Earnings.Line> pays, int paysDays, Forecast forecast, String goalName,
			long since, long now, ZoneId zone)
		{
			this.player = player;
			this.total = total;
			this.changes = changes;
			this.points = points;
			this.from = from;
			this.to = to;
			this.kinds = kinds;
			this.held = held;
			this.milestones = milestones;
			this.pays = pays;
			this.paysDays = paysDays;
			this.forecast = forecast;
			this.goalName = goalName;
			this.since = since;
			this.now = now;
			this.zone = zone;
		}

		/**
		 * @param player the name to print, or null to leave it off
		 * @param earnings Session Chronicle's sessions priced, or null when it is not installed
		 * @return the statement, or null while the ledger has not yet seen the bank
		 */
		public static Statement of(Ledger ledger, Map<Integer, String> names, String player, LocalDate today, long now, ZoneId zone,
			long goal, String goalName, Earnings earnings)
		{
			if (!canDraw(ledger))
			{
				return null;
			}
			Long[] changes = new Long[3];
			int[] spans = {1, 7, 30};
			for (int i = 0; i < 3; i++)
			{
				changes[i] = Report.of(ledger, spans[i], today, 0).change();
			}
			long from = Math.max(now - MONTH_MS, Math.min(ledger.getSince(), now));
			List<Ledger.Point> points = LedgerPanel.slice(ledger.getHistory(), from, now, ledger.getTotal());
			List<Held> held = new ArrayList<>();
			for (Report.Line l : Report.holdings(ledger, HELD))
			{
				String name = names == null ? null : names.get(l.getItemId());
				held.add(new Held(l.getItemId(), name == null ? "Item " + l.getItemId() : name, l.getValue()));
			}
			List<Earnings.Line> pays = earnings == null || earnings.isEmpty() ? Collections.emptyList()
				: earnings.getLines().subList(0, Math.min(PAYS, earnings.getLines().size()));
			return new Statement(player == null || player.isEmpty() ? null : player, ledger.getTotal(), changes, points, from, now,
				Report.of(ledger, 1, today, 0).getKinds(), held, Milestones.of(ledger.getHistory(), ledger.getTotal(), now),
				pays, earnings == null ? 0 : earnings.getDays(), Forecast.of(ledger, today, goal), goalName == null ? "" : goalName.trim(),
				ledger.getSince(), now, zone);
		}
	}

	/** True when there is a ledger worth sharing: the bank has been seen at least once. */
	public static boolean canDraw(Ledger ledger)
	{
		return ledger != null && ledger.isReady();
	}

	/** "Gilded Owl's ledger", or "My ledger" without the name. */
	static String title(String player)
	{
		return player == null || player.isEmpty() ? "My ledger" : player + "'s ledger";
	}

	/** The file name offered when saving: "Ledger 19 October 2026.png". */
	public static String fileName(long now, ZoneId zone)
	{
		return "Ledger " + DATE.format(Instant.ofEpochMilli(now).atZone(zone)) + ".png";
	}

	/** Renders the card; pictures still loading are left out and onLoaded runs when one arrives. */
	public static BufferedImage render(Statement s, ItemIcons icons, Runnable onLoaded)
	{
		BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D sg = scratch.createGraphics();
		int height = content(sg, s, null, null);
		sg.dispose();

		BufferedImage image = new BufferedImage(WIDTH * SCALE, height * SCALE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.scale(SCALE, SCALE);
		Theme.smooth(g);
		frame(g, height);
		content(g, s, icons, onLoaded);
		g.dispose();
		return image;
	}

	private static void frame(Graphics2D g, int h)
	{
		int w = WIDTH;
		RoundRectangle2D page = new RoundRectangle2D.Float(0, 0, w, h, 18, 18);
		g.setPaint(new GradientPaint(0, 0, Theme.mix(Theme.INK, Theme.CARD_TOP, 0.5), 0, h, Theme.INK_DEEP));
		g.fill(page);
		g.setClip(page);
		g.setPaint(new RadialGradientPaint(w / 2f, 60, w * 0.8f, new float[]{0f, 1f},
			new Color[]{Theme.alpha(Theme.GOLD, 50), Theme.alpha(Theme.GOLD, 0)}));
		g.fillRect(0, 0, w, h);
		// The same faint grain every time, so the page reads as vellum.
		Random grain = new Random(11);
		for (int i = 0; i < w * h / 26; i++)
		{
			g.setColor(Theme.alpha(grain.nextBoolean() ? Theme.GOLD_BRIGHT : Color.BLACK, 6 + grain.nextInt(8)));
			g.fillRect(grain.nextInt(w), grain.nextInt(h), 1, 1);
		}
		g.setClip(null);
		g.setStroke(new BasicStroke(1f));
		g.setColor(Theme.alpha(Theme.GOLD, 190));
		g.draw(new RoundRectangle2D.Float(4.5f, 4.5f, w - 9, h - 9, 14, 14));
		g.setColor(Theme.alpha(Theme.GOLD_DIM, 120));
		g.draw(new RoundRectangle2D.Float(8.5f, 8.5f, w - 17, h - 17, 10, 10));
		float[][] corners = {{14, 14}, {w - 14, 14}, {14, h - 14}, {w - 14, h - 14}};
		for (float[] c : corners)
		{
			diamond(g, c[0], c[1], 3.5f, Theme.GOLD);
		}
	}

	/** Lays out and draws the card, returning its height; icons null measures without drawing pictures. */
	private static int content(Graphics2D g, Statement s, ItemIcons icons, Runnable onLoaded)
	{
		Theme.smooth(g);
		int x = PAD;
		int cw = WIDTH - PAD * 2;
		int y = PAD + 4;

		// The title and the day.
		g.setFont(Theme.small());
		FontMetrics small = g.getFontMetrics();
		String date = DATE.format(Instant.ofEpochMilli(s.now).atZone(s.zone));
		g.setFont(Theme.title());
		FontMetrics title = g.getFontMetrics();
		Ui.shadowed(g, clip(title(s.player), title, cw - small.stringWidth(date) - 10), x, y + title.getAscent(), Theme.GOLD_BRIGHT);
		g.setFont(Theme.small());
		g.setColor(Theme.TEXT_MUTED);
		g.drawString(date, x + cw - small.stringWidth(date), y + title.getAscent());
		y += title.getHeight() + 10;

		// The worth itself, doubled the way the game draws large numbers.
		centred(g, "NET WORTH", x, cw, y + small.getAscent(), Theme.TEXT_MUTED);
		y += small.getHeight() + 2;
		String figure = Format.coins(s.total);
		int fw = (title.stringWidth(figure) + 1) * 2;
		int gw = small.stringWidth("gp") + 4;
		int fx = x + (cw - fw - gw) / 2;
		large(g, figure, fx, y);
		g.setFont(Theme.small());
		g.setColor(Theme.GOLD_DIM);
		g.drawString("gp", fx + fw + 4, y + title.getAscent() * 2 - 2);
		y += title.getHeight() * 2 + 8;

		// Today, the week and the month.
		int col = cw / 3;
		for (int i = 0; i < 3; i++)
		{
			int cx = x + i * col;
			Long c = s.changes[i];
			centred(g, PERIODS[i], cx, col, y + small.getAscent(), Theme.TEXT_FAINT);
			g.setFont(Theme.body());
			FontMetrics body = g.getFontMetrics();
			String value = c == null ? "-" : Format.signed(c);
			int vw = body.stringWidth(value);
			Color tone = c == null ? Theme.TEXT_FAINT : c == 0 ? Theme.TEXT_MUTED : Theme.signed(c);
			Ui.shadowed(g, value, cx + (col - vw) / 2, y + small.getHeight() + body.getAscent(), tone);
			g.setFont(Theme.small());
		}
		y += small.getHeight() + 22;
		rule(g, x, y, cw);
		y += 10;

		y = chart(g, s, x, y, cw) + 12;
		y = kinds(g, s, x, y, cw);
		y = held(g, s, icons, onLoaded, x, y, cw);
		y = milestones(g, s, x, y, cw);
		y = pays(g, s, x, y, cw);
		y = goal(g, s, x, y, cw);

		// The footer.
		rule(g, x, y, cw);
		y += 12;
		g.setFont(Theme.small());
		g.setColor(Theme.TEXT_FAINT);
		g.drawString("Kept since " + DATE.format(Instant.ofEpochMilli(s.since).atZone(s.zone)), x, y + small.getAscent());
		String mark = "Codex Ledger";
		g.setColor(Theme.GOLD_DIM);
		g.drawString(mark, x + cw - small.stringWidth(mark), y + small.getAscent());
		return y + small.getHeight() + PAD;
	}

	/** The last thirty days as a gilt line over its own shading, with the high and the low marked. */
	private static int chart(Graphics2D g, Statement s, int x, int y, int cw)
	{
		long days = Math.max(1, Math.round((s.to - s.from) / 86_400_000.0));
		Long month = s.changes[2];
		header(g, days >= 30 ? "Last 30 days" : "Since the ledger began", month == null ? null : Format.signed(month), x, y, cw);
		y += 24;
		// The plot, then a strip under it for the low and the high, inside one well.
		RoundRectangle2D well = new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, cw - 1, CHART_H + 28, 8, 8);
		g.setColor(Theme.alpha(Theme.INK_DEEP, 200));
		g.fill(well);
		g.setColor(Theme.STROKE_SOFT);
		g.draw(well);
		List<Ledger.Point> pts = s.points;
		long lo = Long.MAX_VALUE;
		long hi = Long.MIN_VALUE;
		for (Ledger.Point p : pts)
		{
			lo = Math.min(lo, p.getValue());
			hi = Math.max(hi, p.getValue());
		}
		int px = x + 8;
		int pw = cw - 16;
		int py = y + 8;
		int ph = CHART_H;
		long span = Math.max(1, hi - lo);
		long range = Math.max(1, s.to - s.from);
		if (pts.size() >= 2)
		{
			Path2D line = new Path2D.Float();
			Path2D area = new Path2D.Float();
			for (int i = 0; i < pts.size(); i++)
			{
				Ledger.Point p = pts.get(i);
				float vx = px + pw * (float) Math.max(0, Math.min(1, (p.getTime() - s.from) / (double) range));
				float vy = hi == lo ? py + ph / 2f : py + ph - ph * (float) ((p.getValue() - lo) / (double) span);
				// Worth holds until the next change, so the line steps rather than slopes.
				if (i == 0)
				{
					line.moveTo(vx, vy);
					area.moveTo(vx, py + ph);
					area.lineTo(vx, vy);
				}
				else
				{
					float prev = (float) line.getCurrentPoint().getY();
					line.lineTo(vx, prev);
					line.lineTo(vx, vy);
					area.lineTo(vx, prev);
					area.lineTo(vx, vy);
				}
			}
			area.lineTo(px + pw, py + ph);
			area.closePath();
			boolean up = pts.get(pts.size() - 1).getValue() >= pts.get(0).getValue();
			Color tone = up ? Theme.GOLD : Theme.EMBER;
			g.setPaint(new GradientPaint(0, py, Theme.alpha(tone, 90), 0, py + ph, Theme.alpha(tone, 0)));
			g.fill(area);
			g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(up ? Theme.GOLD_BRIGHT : Theme.EMBER);
			g.draw(line);
			g.setStroke(new BasicStroke(1f));
			float ex = (float) line.getCurrentPoint().getX();
			float ey = (float) line.getCurrentPoint().getY();
			g.setColor(Theme.GOLD_BRIGHT);
			g.fill(new Ellipse2D.Float(ex - 2.5f, ey - 2.5f, 5, 5));
		}
		g.setFont(Theme.small());
		FontMetrics fm = g.getFontMetrics();
		int base = py + ph + fm.getAscent() + 5;
		g.setColor(Theme.TEXT_FAINT);
		g.drawString("low " + Format.coins(lo == Long.MAX_VALUE ? s.total : lo), px, base);
		String high = "high " + Format.coins(hi == Long.MIN_VALUE ? s.total : hi);
		g.drawString(high, px + pw - fm.stringWidth(high), base);
		return y + CHART_H + 28;
	}

	/** One bar split by kind, with each kind's share under it. */
	private static int kinds(Graphics2D g, Statement s, int x, int y, int cw)
	{
		if (s.kinds == null)
		{
			return y;
		}
		long sum = 0;
		for (long v : s.kinds)
		{
			sum += Math.max(0, v);
		}
		if (sum <= 0)
		{
			return y;
		}
		header(g, "What it is", null, x, y, cw);
		y += 24;
		int bar = 10;
		RoundRectangle2D track = new RoundRectangle2D.Float(x, y, cw, bar, bar, bar);
		g.setColor(Theme.INK_DEEP);
		g.fill(track);
		g.setClip(track);
		float bx = x;
		Kind[] all = Kind.values();
		for (int i = 0; i < all.length && i < s.kinds.length; i++)
		{
			float w = cw * Math.max(0, s.kinds[i]) / (float) sum;
			if (w <= 0)
			{
				continue;
			}
			Color c = Theme.kind(all[i]);
			g.setPaint(new GradientPaint(0, y, Theme.mix(c, Theme.GOLD_BRIGHT, 0.2), 0, y + bar, Theme.mix(c, Theme.INK, 0.35)));
			g.fill(new java.awt.geom.Rectangle2D.Float(bx, y, w + 0.5f, bar));
			bx += w;
		}
		g.setClip(null);
		g.setColor(Theme.alpha(Theme.GOLD, 90));
		g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, cw - 1, bar - 1, bar, bar));
		y += bar + 8;
		g.setFont(Theme.small());
		FontMetrics fm = g.getFontMetrics();
		String[] shares = Format.shares(s.kinds, sum);
		int col = cw / all.length;
		for (int i = 0; i < all.length && i < s.kinds.length; i++)
		{
			int cx = x + i * col;
			boolean empty = s.kinds[i] <= 0;
			Color c = Theme.kind(all[i]);
			g.setColor(empty ? Theme.alpha(c, 90) : c);
			g.fill(new Ellipse2D.Float(cx, y + fm.getAscent() / 2f - 3, 6, 6));
			g.setColor(empty ? Theme.TEXT_FAINT : Theme.TEXT);
			g.drawString(all[i].getLabel(), cx + 10, y + fm.getAscent());
			g.setColor(empty ? Theme.TEXT_FAINT : Theme.TEXT_MUTED);
			g.drawString(shares[i], cx + 10, y + fm.getAscent() + fm.getHeight());
		}
		return y + fm.getHeight() * 2 + 12;
	}

	/** The five most valuable things held, each with its picture and its share of the worth. */
	private static int held(Graphics2D g, Statement s, ItemIcons icons, Runnable onLoaded, int x, int y, int cw)
	{
		if (s.held.isEmpty())
		{
			return y;
		}
		header(g, "Most valuable", "share", x, y, cw);
		y += 24;
		g.setFont(Theme.body());
		FontMetrics body = g.getFontMetrics();
		g.setFont(Theme.small());
		FontMetrics small = g.getFontMetrics();
		int shareW = small.stringWidth("100%");
		g.setFont(Theme.body());
		int valueW = 0;
		for (Held h : s.held)
		{
			valueW = Math.max(valueW, body.stringWidth(Format.coins(h.value)));
		}
		long[] values = new long[s.held.size()];
		for (int i = 0; i < values.length; i++)
		{
			values[i] = s.held.get(i).value;
		}
		// Shares of the whole worth, rounded together so they never add up past it.
		String[] shares = Format.shares(values, s.total);
		long sum = 0;
		for (long v : values)
		{
			sum += Math.max(0, v);
		}
		long total = Math.max(1, Math.max(s.total, sum));
		// Every share rail runs the same length, so the shares can be compared at a glance.
		int railW = cw - 33 - shareW - 8 - valueW - 8;
		for (int i = 0; i < s.held.size(); i++)
		{
			Held h = s.held.get(i);
			RoundRectangle2D wellBox = new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, 25, ROW - 1, 6, 6);
			g.setColor(Theme.alpha(Theme.INK_DEEP, 200));
			g.fill(wellBox);
			g.setColor(i == 0 ? Theme.alpha(Theme.GOLD, 150) : Theme.STROKE_SOFT);
			g.draw(wellBox);
			BufferedImage icon = icons == null ? null : icons.icon(h.itemId, onLoaded);
			if (icon != null)
			{
				// Item pictures are 36 by 32; at the card's scale 18 by 16 draws them pixel for pixel.
				g.drawImage(icon, x + 4, y + 3, 18, 16, null);
			}
			double share = h.value / (double) total;
			String value = Format.coins(h.value);
			String pct = shares[i];
			int tx = x + 33;
			int vx = x + cw - shareW - 8 - body.stringWidth(value);
			g.setFont(Theme.body());
			int base = y + ROW / 2 + body.getAscent() / 2 - 2;
			Ui.shadowed(g, clip(h.name, body, railW), tx, base, i == 0 ? Theme.GOLD_BRIGHT : Theme.TEXT);
			Ui.shadowed(g, value, vx, base, Theme.GOLD);
			g.setFont(Theme.small());
			g.setColor(Theme.TEXT_FAINT);
			g.drawString(pct, x + cw - small.stringWidth(pct), base);
			// A hairline under the name for its share of the worth.
			int lineW = railW;
			g.setColor(Theme.alpha(Theme.STROKE, 160));
			g.fillRect(tx, y + ROW - 3, lineW, 1);
			g.setColor(Theme.alpha(i == 0 ? Theme.GOLD_BRIGHT : Theme.GOLD, 170));
			g.fillRect(tx, y + ROW - 3, Math.max(1, (int) Math.round(lineW * Math.min(1, share))), 1);
			y += ROW + 3;
		}
		return y + 9;
	}

	private static int milestones(Graphics2D g, Statement s, int x, int y, int cw)
	{
		Milestones m = s.milestones;
		if (m == null || m.getPassed().isEmpty())
		{
			return y;
		}
		header(g, "Milestones", nextMark(m, s.forecast), x, y, cw);
		y += 24;
		boolean first = true;
		for (Milestones.Mark mark : m.getPassed())
		{
			MilestoneRow.paint(g, mark, first, x, y, cw);
			first = false;
			y += MilestoneRow.HEIGHT + 4;
		}
		return y + 8;
	}

	/**
	 * The milestones heading's note, "next 1B" or "back to 750M", or null when the goal section
	 * already names that same figure (with no goal set it aims at the next round figure), so one
	 * figure is never headed twice.
	 */
	static String nextMark(Milestones m, Forecast f)
	{
		if (f != null && f.getTarget() == m.getNext())
		{
			return null;
		}
		return (m.isRegain() ? "back to " : "next ") + Format.mark(m.getNext());
	}

	/** What play paid best an hour, from Session Chronicle's sessions. */
	private static int pays(Graphics2D g, Statement s, int x, int y, int cw)
	{
		if (s.pays.isEmpty())
		{
			return y;
		}
		header(g, "What pays", s.paysDays == 1 ? "last day" : "last " + s.paysDays + " days", x, y, cw);
		y += 24;
		g.setFont(Theme.body());
		FontMetrics body = g.getFontMetrics();
		g.setFont(Theme.small());
		FontMetrics small = g.getFontMetrics();
		for (Earnings.Line l : s.pays)
		{
			String rate = Format.signed(l.perHour()) + "/hr";
			String note = Format.duration(l.getPlayedMs());
			int rw = body.stringWidth(rate);
			int nw = small.stringWidth(note) + 10;
			g.setFont(Theme.body());
			int base = y + body.getAscent();
			diamond(g, x + 3, base - body.getAscent() / 2f + 1, 2.5f, Theme.signed(l.perHour()));
			Ui.shadowed(g, clip(l.getName(), body, cw - rw - nw - 20), x + 12, base, Theme.TEXT);
			Ui.shadowed(g, rate, x + cw - rw, base, Theme.signed(l.perHour()));
			g.setFont(Theme.small());
			g.setColor(Theme.TEXT_FAINT);
			g.drawString(note, x + cw - rw - nw, base);
			y += body.getHeight() + 4;
		}
		return y + 10;
	}

	/** The goal, or the next round figure, with the way along it and when it should fall. */
	private static int goal(Graphics2D g, Statement s, int x, int y, int cw)
	{
		Forecast f = s.forecast;
		if (f == null)
		{
			return y;
		}
		header(g, f.isGoalSet() ? "Goal" : "Heading for", Format.mark(f.getTarget()), x, y, cw);
		y += 24;
		g.setFont(Theme.body());
		FontMetrics body = g.getFontMetrics();
		String name = f.isGoalSet() ? (s.goalName.isEmpty() ? "My goal" : s.goalName) : "Next mark";
		String pct = Format.percent(f.progress());
		Color tone = f.isReached() ? Theme.GREEN : Theme.GOLD;
		Ui.shadowed(g, clip(name, body, cw - body.stringWidth(pct) - 10), x, y + body.getAscent(), Theme.GOLD_BRIGHT);
		Ui.shadowed(g, pct, x + cw - body.stringWidth(pct), y + body.getAscent(), tone);
		y += body.getHeight() + 4;
		int bar = 8;
		RoundRectangle2D track = new RoundRectangle2D.Float(x, y, cw, bar, bar, bar);
		g.setColor(Theme.INK_DEEP);
		g.fill(track);
		float fill = (float) (cw * Math.max(0, Math.min(1, f.progress())));
		if (fill > 0)
		{
			g.setPaint(new GradientPaint(x, 0, Theme.mix(tone, Theme.INK, 0.3), x + cw, 0, Theme.mix(tone, Theme.GOLD_BRIGHT, 0.3)));
			g.fill(new RoundRectangle2D.Float(x, y, Math.max(bar, fill), bar, bar, bar));
		}
		g.setColor(Theme.alpha(Theme.GOLD, 90));
		g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, cw - 1, bar - 1, bar, bar));
		y += bar + 8;
		g.setFont(Theme.small());
		FontMetrics small = g.getFontMetrics();
		for (String line : lines(LedgerPanel.forecastText(f), small, cw, 3))
		{
			g.setColor(Theme.TEXT_MUTED);
			g.drawString(line.replace(' ', ' '), x, y + small.getAscent());
			y += small.getHeight();
		}
		return y + 12;
	}

	/** Draws the game's bold font doubled pixel for pixel, the way the game draws large numbers. */
	private static void large(Graphics2D g, String text, int x, int y)
	{
		Font font = Theme.title();
		FontMetrics fm = g.getFontMetrics(font);
		int w = Math.max(1, fm.stringWidth(text) + 1);
		int h = fm.getHeight() + 1;
		BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D sg = small.createGraphics();
		sg.setFont(font);
		sg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		int base = fm.getAscent();
		sg.setColor(Color.BLACK);
		sg.drawString(text, 1, base + 1);
		sg.setPaint(new GradientPaint(0, 3, Theme.GOLD_BRIGHT, 0, base, Theme.GOLD));
		sg.drawString(text, 0, base);
		sg.dispose();
		Graphics2D lg = (Graphics2D) g.create();
		lg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		lg.drawImage(small, x, y, w * 2, h * 2, null);
		lg.dispose();
	}

	private static void centred(Graphics2D g, String text, int left, int width, int baseline, Color color)
	{
		FontMetrics fm = g.getFontMetrics();
		g.setColor(color);
		g.drawString(text, left + (width - fm.stringWidth(text)) / 2, baseline);
	}

	private static void header(Graphics2D g, String text, String trailing, int x, int y, int w)
	{
		g.setFont(Theme.small());
		FontMetrics fm = g.getFontMetrics();
		int mid = y + 10;
		diamond(g, x + 3, mid, 3, Theme.GOLD);
		String label = text.toUpperCase(Locale.ROOT);
		g.setColor(Theme.GOLD);
		g.drawString(label, x + 12, mid + fm.getAscent() / 2 - 1);
		int lineFrom = x + 12 + fm.stringWidth(label) + 6;
		int lineTo = x + w - (trailing == null ? 0 : fm.stringWidth(trailing) + 6);
		if (lineTo > lineFrom)
		{
			g.setPaint(new GradientPaint(lineFrom, 0, Theme.alpha(Theme.GOLD_DIM, 160), lineTo, 0, Theme.alpha(Theme.GOLD_DIM, 0)));
			g.fillRect(lineFrom, mid, lineTo - lineFrom, 1);
		}
		if (trailing != null)
		{
			g.setColor(Theme.TEXT_MUTED);
			g.drawString(trailing, x + w - fm.stringWidth(trailing), mid + fm.getAscent() / 2 - 1);
		}
	}

	private static void rule(Graphics2D g, int x, int y, int w)
	{
		g.setStroke(new BasicStroke(1f));
		g.setPaint(new GradientPaint(x, 0, Theme.alpha(Theme.GOLD, 0), x + w / 2f, 0, Theme.alpha(Theme.GOLD, 150), true));
		g.drawLine(x, y, x + w, y);
		diamond(g, x + w / 2f, y, 3, Theme.GOLD);
	}

	private static void diamond(Graphics2D g, float cx, float cy, float r, Color color)
	{
		Path2D d = new Path2D.Float();
		d.moveTo(cx, cy - r);
		d.lineTo(cx + r, cy);
		d.lineTo(cx, cy + r);
		d.lineTo(cx - r, cy);
		d.closePath();
		g.setColor(color);
		g.fill(d);
	}

	/** Word-wrapped lines, at most max, the last ending in an ellipsis when the text runs on. */
	static List<String> lines(String text, FontMetrics fm, int width, int max)
	{
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		String[] words = text.split(" ");
		for (int i = 0; i < words.length; i++)
		{
			String next = line.length() == 0 ? words[i] : line + " " + words[i];
			if (fm.stringWidth(next) <= width || line.length() == 0)
			{
				line.setLength(0);
				line.append(next);
				continue;
			}
			if (out.size() == max - 1)
			{
				StringBuilder rest = new StringBuilder(line);
				for (int j = i; j < words.length; j++)
				{
					rest.append(' ').append(words[j]);
				}
				out.add(clip(rest.toString(), fm, width));
				return out;
			}
			out.add(line.toString());
			line.setLength(0);
			line.append(words[i]);
		}
		if (line.length() > 0)
		{
			out.add(clip(line.toString(), fm, width));
		}
		return out;
	}

	static String clip(String text, FontMetrics fm, int width)
	{
		if (fm.stringWidth(text) <= width)
		{
			return text;
		}
		String dots = "...";
		int end = text.length();
		while (end > 0 && fm.stringWidth(text.substring(0, end) + dots) > width)
		{
			end--;
		}
		return text.substring(0, end).trim() + dots;
	}
}
