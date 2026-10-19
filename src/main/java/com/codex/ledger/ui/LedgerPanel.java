package com.codex.ledger.ui;

import com.codex.ledger.core.Alchemy;
import com.codex.ledger.core.BankCopy;
import com.codex.ledger.core.Channel;
import com.codex.ledger.core.Day;
import com.codex.ledger.core.Deal;
import com.codex.ledger.core.Earnings;
import com.codex.ledger.core.Forecast;
import com.codex.ledger.core.Ledger;
import com.codex.ledger.core.Offer;
import com.codex.ledger.core.Report;
import com.codex.ledger.core.Store;
import com.codex.ledger.core.Trades;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.PluginPanel;

/**
 * The Ledger side panel: the crest, then four pages. Worth is the line of the account's worth
 * over time, where it is kept and where it is heading; Day book is where the gold came from and
 * went; Trades is what trading on the Grand Exchange made after tax; and Hoard is everything held,
 * most valuable first.
 */
public class LedgerPanel extends PluginPanel
{
	static final int WIDTH = 200;
	private static final String[] TABS = {"worth", "daybook", "trades", "hoard"};
	private static final int WORTH = 0;
	private static final int DAYBOOK = 1;
	private static final int TRADES = 2;
	private static final String[] RANGES = {"Day", "Week", "Month", "Year", "All"};
	private static final long[] RANGE_MS = {86_400_000L, 7 * 86_400_000L, 30 * 86_400_000L, 365 * 86_400_000L, 0};
	private static final int[] RANGE_DAYS = {1, 7, 30, 365, 0};
	private static final String[] RANGE_NOTES = {"today", "last 7 days", "last 30 days", "last year", "all time"};
	private static final String[] SPANS = {"Today", "7 days", "30 days", "All"};
	private static final int[] SPAN_DAYS = {1, 7, 30, 0};
	private static final int LINES = 6;
	private static final int PAGES = 14;
	private static final int HOARD = 40;
	private static final int TRADED = 12;
	private static final int HELD = 8;

	/** The worth the player aims at, from the settings. */
	public static final class Goal
	{
		final long amount;
		final String name;

		public Goal(long amount, String name)
		{
			this.amount = amount;
			this.name = name == null ? "" : name.trim();
		}
	}

	/** What the panel has to show. */
	public enum State
	{
		LOGGED_OUT,
		WAITING_FOR_BANK,
		READY
	}

	private final ItemIcons icons;
	private final Crest crest = new Crest();
	private final Segmented tabs = new Segmented(true, "Worth", "Days", "Trades", "Hoard");
	private final Segmented range = new Segmented(false, RANGES);
	private final Segmented span = new Segmented(false, SPANS);
	private final Segmented tradeSpan = new Segmented(false, SPANS);
	private final CardLayout cards = new CardLayout();
	private final JPanel content = new JPanel(cards);
	private final JScrollPane worth = scroller();
	private final JScrollPane daybook = scroller();
	private final JScrollPane trades = scroller();
	private final JScrollPane hoard = scroller();
	private Goal goal = new Goal(0, "");

	private Ledger ledger;
	private Map<Integer, String> names = Collections.emptyMap();
	private State state = State.LOGGED_OUT;
	private long now;
	private ZoneId zone = ZoneId.systemDefault();
	private String openDay;
	private int pagesShown = PAGES;
	private final int[] chartPointer = {-1};
	/** Tabs whose content is out of date; a hidden tab is only rebuilt when it is next shown. */
	private final boolean[] stale = {true, true, true, true};

	public LedgerPanel(ItemIcons icons)
	{
		super(false);
		this.icons = icons;
		setLayout(new BorderLayout());
		setBackground(Theme.INK);

		JPanel north = new JPanel(new BorderLayout());
		north.setOpaque(false);
		north.add(crest, BorderLayout.NORTH);
		JPanel tabHolder = new JPanel(new BorderLayout());
		tabHolder.setOpaque(false);
		tabHolder.setBorder(new EmptyBorder(8, 8, 2, 8));
		tabHolder.add(tabs, BorderLayout.CENTER);
		north.add(tabHolder, BorderLayout.SOUTH);
		add(north, BorderLayout.NORTH);

		content.setOpaque(false);
		content.add(worth, TABS[0]);
		content.add(daybook, TABS[1]);
		content.add(trades, TABS[2]);
		content.add(hoard, TABS[3]);
		add(content, BorderLayout.CENTER);

		tabs.onChange(this::showTab);
		range.setSelected(1, false);
		range.onChange(i ->
		{
			chartPointer[0] = -1;
			rebuildWorth();
		});
		span.onChange(i ->
		{
			openDay = null;
			pagesShown = PAGES;
			rebuildDaybook();
		});
		tradeSpan.setSelected(1, false);
		tradeSpan.onChange(i -> rebuildTrades());
		rebuild();
	}

	private static JScrollPane scroller()
	{
		JScrollPane scroll = new JScrollPane();
		scroll.setBorder(null);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
		return scroll;
	}

	private Alchemy alchemy;
	private Earnings earnings;
	/** Codex Bank Steward's copy of the bank, priced, while the ledger waits for the real one; null otherwise. */
	private BankCopy.Estimate estimate;
	/** Asks for a fresh copy of the ledger when the panel opens; null in previews. */
	private Runnable onOpen;

	/**
	 * Called on the Swing thread whenever the ledger changes.
	 *
	 * @param ledger a copy the panel may keep, or null when no account is open
	 * @param names item names for every item the ledger mentions
	 */
	public void update(Ledger ledger, Map<Integer, String> names, String player, State state, long now)
	{
		this.ledger = ledger;
		this.names = names;
		this.state = state;
		this.now = now;
		updateCrest(player);
		if (isShowing())
		{
			rebuild();
		}
		else
		{
			// Nobody is looking; the tab on show is rebuilt when the panel is opened.
			java.util.Arrays.fill(stale, true);
		}
	}

	/** What is better alched than sold, or null to say nothing (ironmen, who cannot sell); shown at the next rebuild. */
	public void setAlchemy(Alchemy alchemy)
	{
		this.alchemy = alchemy;
	}

	/** An estimate from Bank Steward's copy of the bank, or null; shown with the next update while the ledger waits for the bank. */
	public void setEstimate(BankCopy.Estimate estimate)
	{
		this.estimate = estimate;
	}

	/** The estimate, only while the ledger is waiting to see the bank. */
	private BankCopy.Estimate estimating()
	{
		return ledger != null && !ledger.isReady() && state == State.WAITING_FOR_BANK ? estimate : null;
	}

	/** What pays by activity, from Codex Session Chronicle's sessions, or null without it; shown at the next rebuild. */
	public void setEarnings(Earnings earnings)
	{
		this.earnings = earnings;
	}

	/** The worth goal from the settings; shown at the next rebuild. */
	public void setGoal(Goal goal)
	{
		this.goal = goal;
	}

	/** Called on the Swing thread when the panel opens, so a ledger that changed while it was closed is sent. */
	public void setOnOpen(Runnable onOpen)
	{
		this.onOpen = onOpen;
	}

	@Override
	public void onActivate()
	{
		if (onOpen != null)
		{
			onOpen.run();
		}
		int tab = tabs.getSelected();
		if (stale[tab])
		{
			rebuild(tab);
		}
	}

	/** Opens one tab, for previews and tests. */
	public void showTab(int index)
	{
		tabs.setSelected(index, false);
		cards.show(content, TABS[index]);
		if (stale[index])
		{
			rebuild(index);
		}
	}

	/** Picks the worth chart's range, for previews and tests. */
	public void showRange(int index)
	{
		range.setSelected(index, false);
		rebuildWorth();
	}

	/** Picks the trades page's span, for previews and tests. */
	public void showTradeSpan(int index)
	{
		tradeSpan.setSelected(index, false);
		rebuildTrades();
	}

	/** Opens one day book page by date, or the chosen span when null. */
	public void openDay(String date)
	{
		openDay = date;
		rebuildDaybook();
		SwingUtilities.invokeLater(() -> daybook.getVerticalScrollBar().setValue(0));
	}

	private LocalDate today()
	{
		return Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
	}

	private void updateCrest(String player)
	{
		if (ledger == null)
		{
			crest.update(player, null, "Log in to open the ledger", null);
			return;
		}
		BankCopy.Estimate e = estimating();
		if (e != null)
		{
			crest.update(player, ledger.getTotal() + e.getBank(), "Estimate: open your bank", null);
			return;
		}
		if (state != State.READY)
		{
			crest.update(player, ledger.getTotal(), state == State.LOGGED_OUT ? "Logged out, as last seen" : "Open your bank to begin", null);
			return;
		}
		Long[] changes = new Long[3];
		int[] spans = {1, 7, 30};
		for (int i = 0; i < 3; i++)
		{
			changes[i] = Report.of(ledger, spans[i], today(), 0).change();
		}
		crest.update(player, ledger.getTotal(), null, changes);
	}

	/** Rebuilds the tab on show and marks the others to be rebuilt when they are opened. */
	private void rebuild()
	{
		java.util.Arrays.fill(stale, true);
		rebuild(tabs.getSelected());
	}

	private void rebuild(int tab)
	{
		stale[tab] = false;
		if (tab == WORTH)
		{
			rebuildWorth();
		}
		else if (tab == DAYBOOK)
		{
			rebuildDaybook();
		}
		else if (tab == TRADES)
		{
			rebuildTrades();
		}
		else
		{
			rebuildHoard();
		}
	}

	private boolean waiting(StackPanel stack)
	{
		if (ledger == null)
		{
			stack.add(note("The ledger is closed", "Log in and Codex Ledger counts what your account is worth: your bank, inventory, worn gear, Grand Exchange offers, seed vault and looting bag."));
			return true;
		}
		if (!ledger.isReady())
		{
			BankCopy.Estimate e = estimating();
			stack.add(note("Open your bank once", "The ledger begins the first time it sees your bank. After that it keeps count on its own, and the bank is remembered as last seen whenever it is closed."
				+ (e == null ? "" : " Until then the worth above is an estimate, with Codex Bank Steward's copy of your bank at today's prices.")));
			return true;
		}
		return false;
	}

	// The Worth tab.

	private void rebuildWorth()
	{
		StackPanel stack = new StackPanel(8, 8);
		stack.add(range);
		if (waiting(stack))
		{
			if (ledger != null)
			{
				stack.add(new SectionHeader("Counted so far", Format.coins(ledger.getTotal())));
				stack.add(new StoreBreakdown(parts()));
				BankCopy.Estimate e = estimating();
				if (e != null)
				{
					stack.add(new SectionHeader("Bank Steward's copy", Format.coins(e.getBank())));
					String seen = e.getSavedAt() > 0 ? "Your bank as Bank Steward last saw it, " + Format.ago(now - e.getSavedAt()) : "Your bank as Bank Steward last saw it";
					stack.add(Ui.wrapped(seen + ": " + Format.plural(e.getKinds(), "kind") + " of item."
						+ (e.isTruncated() ? " A bank this large is only partly copied, so the estimate runs low." : "")
						+ " Nothing in it is booked: the ledger starts from your real bank.", Theme.small(), Theme.TEXT_MUTED, WIDTH - 10));
				}
			}
			replace(worth, stack);
			return;
		}
		int r = range.getSelected();
		long to = now;
		long from = RANGE_MS[r] > 0 ? now - RANGE_MS[r] : Math.min(ledger.getSince(), firstPoint());
		List<Ledger.Point> points = slice(ledger.getHistory(), from, to, ledger.getTotal());
		Card chart = new Card(new BorderLayout());
		chart.setBorder(new EmptyBorder(8, 8, 6, 8));
		chart.add(new WorthChart(points, from, to, r == 0, chartPointer), BorderLayout.CENTER);
		stack.add(chart);

		Report report = Report.of(ledger, RANGE_DAYS[r], today(), 1);
		Day best = null;
		Day worst = null;
		LocalDate start = RANGE_DAYS[r] > 0 ? today().minusDays(RANGE_DAYS[r] - 1) : null;
		for (Day d : ledger.getDays())
		{
			if (start != null && LocalDate.parse(d.getDate()).isBefore(start))
			{
				continue;
			}
			if (best == null || d.change() > best.change())
			{
				best = d;
			}
			if (worst == null || d.change() < worst.change())
			{
				worst = d;
			}
		}
		boolean timed = report.getPlayedMs() > 0;
		JPanel tiles = new JPanel(new GridLayout(timed ? 3 : 2, 2, 6, 6));
		tiles.setOpaque(false);
		String when = RANGE_NOTES[r];
		tiles.add(new StatTile(Format.signed(report.getPlay()), "Play", when, Theme.signed(report.getPlay())));
		tiles.add(new StatTile(Format.signed(report.getMarket()), "Market", when, report.getMarket() >= 0 ? Theme.MARKET : Theme.EMBER));
		if (timed)
		{
			hourTiles(tiles, report);
		}
		Day today = ledger.day(today());
		if (r == 0 && today != null)
		{
			// One day has no best and worst; its high and low say more.
			tiles.add(new StatTile(Format.coins(today.getHigh()), "High", "today", Theme.GOLD_BRIGHT));
			tiles.add(new StatTile(Format.coins(today.getLow()), "Low", "today", Theme.GOLD));
			stack.add(tiles);
			goal(stack);
			kinds(stack, report, "today");
			pays(stack);
			whereKept(stack);
			replace(worth, stack);
			return;
		}
		tiles.add(new StatTile(best == null ? "-" : Format.signed(best.change()), "Best day",
			best == null ? "" : Format.day(LocalDate.parse(best.getDate())), best == null ? Theme.TEXT_MUTED : Theme.signed(best.change())));
		tiles.add(new StatTile(worst == null ? "-" : Format.signed(worst.change()), "Worst day",
			worst == null ? "" : Format.day(LocalDate.parse(worst.getDate())), worst == null ? Theme.TEXT_MUTED : Theme.signed(worst.change())));
		stack.add(tiles);
		goal(stack);
		kinds(stack, report, RANGE_NOTES[r]);
		pays(stack);
		whereKept(stack);
		replace(worth, stack);
	}

	/** Play an hour logged in, and the time it was made in. */
	private static void hourTiles(JPanel tiles, Report report)
	{
		String hourly = report.hasHourly() ? Format.signed(report.perHour()) : "-";
		tiles.add(new StatTile(hourly, "Per hour", report.hasHourly() ? "of play logged in" : "after 10 minutes",
			report.hasHourly() ? Theme.signed(report.perHour()) : Theme.TEXT_MUTED));
		tiles.add(new StatTile(Format.duration(report.getPlayedMs()), "Logged in", report.getDays() == 1 ? "today" : "over "
			+ Format.plural(report.getDays(), "day"), Theme.GOLD));
	}

	/** What the worth is made of, and how each kind moved over the span. */
	private void kinds(StackPanel stack, Report report, String when)
	{
		long[] kinds = report.getKinds();
		if (kinds == null)
		{
			return;
		}
		long[] change = report.kindChange();
		stack.add(new SectionHeader("What it is", change == null ? "" : "change " + when));
		stack.add(new KindBreakdown(kinds, change));
		String moved = kindStory(kinds, report.kindFlow());
		if (moved != null)
		{
			stack.add(Ui.wrapped(moved, Theme.small(), Theme.TEXT_FAINT, WIDTH - 10));
		}
	}

	/**
	 * One plain sentence when coins clearly turned into gear or back, the move a player makes on purpose
	 * and likes to see named: "About 48.2M in coins went into gear."
	 */
	static String kindStory(long[] kinds, long[] flow)
	{
		// Told from items changing hands only: gear rising in price while coins are spent is no purchase.
		if (kinds == null || flow == null)
		{
			return null;
		}
		long cash = flow[com.codex.ledger.core.Kind.CASH.ordinal()];
		long gear = flow[com.codex.ledger.core.Kind.GEAR.ordinal()];
		long total = 0;
		for (long v : kinds)
		{
			total += Math.max(0, v);
		}
		long amount = Math.min(Math.abs(cash), Math.abs(gear));
		if (amount < Math.max(1_000_000, total / 100))
		{
			return null;
		}
		if (cash < 0 && gear > 0)
		{
			return "About " + Format.coins(amount) + " in coins went into gear.";
		}
		if (cash > 0 && gear < 0)
		{
			return "About " + Format.coins(amount) + " of gear was turned back into coins.";
		}
		return null;
	}

	/** The worth goal, or the next round figure, with how far along it is and when it falls at this pace. */
	private void goal(StackPanel stack)
	{
		Forecast f = Forecast.of(ledger, today(), goal.amount);
		String name = f.isGoalSet() ? (goal.name.isEmpty() ? "Your goal" : goal.name) : "Next mark";
		stack.add(new SectionHeader(f.isGoalSet() ? "Goal" : "Heading for", Format.coins(f.getTarget())));
		Card card = new Card(new BorderLayout(0, 5));
		card.accent(f.isReached() ? Theme.GREEN : Theme.GOLD);
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		top.add(Ui.label(name, Theme.body(), Theme.GOLD_BRIGHT), BorderLayout.CENTER);
		top.add(Ui.label(Format.percent(f.progress()), Theme.body(), f.isReached() ? Theme.GREEN : Theme.GOLD), BorderLayout.EAST);
		card.add(top, BorderLayout.NORTH);
		card.add(new GoalBar(f.progress()), BorderLayout.CENTER);
		card.add(Ui.wrapped(forecastText(f), Theme.small(), Theme.TEXT_MUTED, WIDTH - 30), BorderLayout.SOUTH);
		card.setToolTipText("The pace is your worth's change over the last " + Forecast.WINDOW
			+ " days, spread over every day, days off included. Set a goal in the plugin's settings.");
		stack.add(card);
	}

	static String forecastText(Forecast f)
	{
		if (f.isReached())
		{
			return "Reached. Set a new goal in the settings to keep going.";
		}
		String left = Format.coins(f.remaining()) + " to go. ";
		if (!f.hasPace())
		{
			return left + "A pace appears after " + Forecast.MIN_DAYS + " days of pages.";
		}
		String pace = Format.signed(Math.round(f.getPerDay())) + " a day over " + f.getDays() + " days";
		if (f.getPerDay() <= 0)
		{
			return left + "At " + pace + " it is not getting closer.";
		}
		if (f.getEta() == null)
		{
			return left + "At " + pace + " it is more than ten years off.";
		}
		return left + "At " + pace + ", around " + Format.longDay(f.getEta()) + ".";
	}

	/** Net worth an hour by what the play was spent on, from Session Chronicle's sessions. */
	private void pays(StackPanel stack)
	{
		Earnings e = earnings;
		if (e == null || e.isEmpty())
		{
			return;
		}
		stack.add(new SectionHeader("What pays", e.getDays() == 1 ? "last day" : "last " + e.getDays() + " days"));
		List<Earnings.Line> lines = e.getLines();
		for (int i = 0; i < lines.size() && i < Earnings.SHOWN; i++)
		{
			Earnings.Line l = lines.get(i);
			Card row = new Card(new BorderLayout(6, 1));
			row.setBorder(new EmptyBorder(5, 10, 5, 10));
			row.accent(Theme.signed(l.perHour()));
			JLabel name = Ui.label(l.getName(), Theme.body(), Theme.TEXT);
			name.setMinimumSize(new Dimension(10, 10));
			name.setPreferredSize(new Dimension(10, name.getPreferredSize().height));
			row.add(name, BorderLayout.CENTER);
			row.add(Ui.label(Format.signed(l.perHour()) + "/hr", Theme.body(), Theme.signed(l.perHour())), BorderLayout.EAST);
			row.add(Ui.label(Format.plural(l.getSessions(), "session") + ", " + Format.duration(l.getPlayedMs()) + ", "
				+ Format.signed(l.getChange()), Theme.small(), Theme.TEXT_FAINT), BorderLayout.SOUTH);
			row.setToolTipText(l.getName() + ": " + Format.signed(l.getChange()) + " over " + Format.plural(l.getSessions(), "session")
				+ " and " + Format.duration(l.getPlayedMs()) + " of play, " + Format.signed(l.perHour()) + " an hour.");
			stack.add(row);
		}
		stack.add(Ui.wrapped("Net worth an hour played, by what Codex Session Chronicle saw each session spent on: loot, supplies used,"
			+ " deaths and prices all count.", Theme.small(), Theme.TEXT_FAINT, WIDTH - 10));
	}

	private void whereKept(StackPanel stack)
	{
		stack.add(new SectionHeader("Where it is kept", Format.coins(ledger.getTotal())));
		stack.add(new StoreBreakdown(parts()));
		offers(stack);
		stack.add(Ui.wrapped("Kept since " + Format.date(ledger.getSince()) + ". Places are counted as last seen, so the bank updates each time you open it.",
			Theme.small(), Theme.TEXT_FAINT, WIDTH - 10));
	}

	private long firstPoint()
	{
		List<Ledger.Point> history = ledger.getHistory();
		return history.isEmpty() ? now : history.get(0).getTime();
	}

	/** The points in a range, starting at its left edge and running on to now. */
	static List<Ledger.Point> slice(List<Ledger.Point> history, long from, long to, long current)
	{
		List<Ledger.Point> out = new ArrayList<>();
		Ledger.Point before = null;
		for (Ledger.Point p : history)
		{
			if (p.getTime() < from)
			{
				before = p;
			}
			else if (p.getTime() <= to)
			{
				out.add(p);
			}
		}
		if (before != null)
		{
			out.add(0, new Ledger.Point(from, before.getValue()));
		}
		if (out.isEmpty() || out.get(out.size() - 1).getTime() < to)
		{
			out.add(new Ledger.Point(to, current));
		}
		return out;
	}

	/** Each offer on the Grand Exchange with how far it has got and what is sitting in it. */
	private void offers(StackPanel stack)
	{
		Map<Integer, Offer> offers = ledger.getOffers();
		if (offers.isEmpty())
		{
			return;
		}
		stack.add(new SectionHeader("On the exchange", offers.size() + (offers.size() == 1 ? " offer" : " offers")));
		JPanel list = new JPanel(new GridLayout(0, 1, 0, 2));
		list.setOpaque(false);
		for (Offer o : offers.values())
		{
			String name = name(o.getItem());
			long value = ledger.value(o.contents());
			list.add(new ItemRow(o.getItem(), name, offerNote(o), Format.coins(value), o.isBuy() ? Theme.TEAL : Theme.GOLD,
				o.getTotal() > 0 ? o.getDone() / (double) o.getTotal() : 0, icons,
				name + ": " + offerNote(o) + " at " + Format.coins(o.getPrice()) + " each. Holds " + Format.coins(value)
					+ (o.isBuy() ? " in items and unspent coins." : " in unsold items and coins after tax.")));
		}
		stack.add(list);
	}

	static String offerNote(Offer o)
	{
		String done = Format.whole(o.getDone());
		String total = Format.whole(o.getTotal());
		if (o.isActive())
		{
			return (o.isBuy() ? "Bought " : "Sold ") + done + " of " + total;
		}
		if (o.getDone() >= o.getTotal())
		{
			return (o.isBuy() ? "Bought " : "Sold ") + total + ", to collect";
		}
		return "Cancelled at " + done + " of " + total;
	}

	private List<StoreBreakdown.Part> parts()
	{
		List<StoreBreakdown.Part> parts = new ArrayList<>();
		Map<Store, Ledger.Holding> stores = ledger.getStores();
		for (Store s : Store.values())
		{
			Ledger.Holding h = stores.get(s);
			if (h == null)
			{
				if (s == Store.BANK)
				{
					parts.add(new StoreBreakdown.Part(s, 0, StoreBreakdown.NEVER));
				}
				continue;
			}
			long value = ledger.value(s);
			if (value > 0 || s == Store.BANK || s == Store.INVENTORY)
			{
				parts.add(new StoreBreakdown.Part(s, value, s == Store.BANK || s == Store.SEED_VAULT ? now - h.getSeen() : -1));
			}
		}
		parts.sort((a, b) -> Long.compare(b.value, a.value));
		return parts;
	}

	// The Day book tab.

	private void rebuildDaybook()
	{
		StackPanel stack = new StackPanel(8, 8);
		Day page = openDay == null || ledger == null ? null : ledger.day(LocalDate.parse(openDay));
		if (page == null)
		{
			// While one page is open the span does not apply; the banner's Back returns to it.
			stack.add(span);
		}
		if (waiting(stack))
		{
			replace(daybook, stack);
			return;
		}
		Report report;
		String title;
		String subtitle;
		if (page != null)
		{
			report = Report.page(page, LINES);
			title = Format.day(LocalDate.parse(page.getDate()));
			subtitle = "from " + Format.coins(page.getOpen()) + " to " + Format.coins(page.getClose());
			Card banner = new Card(new BorderLayout(6, 0));
			banner.accent(Theme.GOLD_DIM);
			banner.add(Ui.label("One page of the day book", Theme.small(), Theme.TEXT_MUTED), BorderLayout.CENTER);
			banner.add(new CodexButton("Back", false, () -> openDay(null)), BorderLayout.EAST);
			stack.add(banner);
		}
		else
		{
			int s = span.getSelected();
			report = Report.of(ledger, SPAN_DAYS[s], today(), LINES);
			title = s == 0 ? "Today" : s == 3 ? "Since " + Format.day(report.getFrom()) : "The last " + SPANS[s];
			subtitle = "from " + Format.coins(report.getStart()) + " to " + Format.coins(report.getEnd());
		}

		Card head = new Card(new BorderLayout(0, 6));
		head.highlighted(true);
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		JPanel words = new JPanel();
		words.setOpaque(false);
		words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
		words.add(Ui.label(title, Theme.title(), Theme.GOLD_BRIGHT));
		words.add(Ui.label(subtitle, Theme.small(), Theme.TEXT_MUTED));
		top.add(words, BorderLayout.CENTER);
		top.add(Ui.label(Format.signed(report.change()), Theme.title(), Theme.signed(report.change())), BorderLayout.EAST);
		head.add(top, BorderLayout.NORTH);
		head.add(new SplitRows(report.getPlay(), report.getMarket()), BorderLayout.CENTER);
		if (report.getPlayedMs() > 0)
		{
			String line = Format.duration(report.getPlayedMs()) + " logged in"
				+ (report.hasHourly() ? " · " + Format.signed(report.perHour()) + "/hr" : "");
			JLabel label = Ui.label(line, Theme.small(), Theme.TEXT_MUTED);
			label.setToolTipText("Play an hour logged in: what playing made or spent, prices moving left out.");
			head.add(label, BorderLayout.SOUTH);
		}
		stack.add(head);
		whereItWent(stack, report);

		lines(stack, "Came in", report.getGains(), false);
		lines(stack, "Went out", report.getLosses(), false);
		Deal traded = report.getDays() == 1 && page != null ? page.getTrades() : null;
		if (traded != null && traded.volume() > 0)
		{
			stack.add(Ui.wrapped("Trading that day: " + Format.coins(traded.getBuyCoins()) + " bought, " + Format.coins(traded.getSaleCoins())
				+ " sold, " + Format.signed(traded.getProfit()) + " profit. The Trades page has every item.", Theme.small(), Theme.TEXT_FAINT, WIDTH - 10));
		}
		lines(stack, "Price moves", report.getMovers(), true);
		if (report.getGains().isEmpty() && report.getLosses().isEmpty() && report.getMovers().isEmpty() && report.channel(Channel.EXCHANGE) == 0)
		{
			stack.add(note("A quiet page", "Nothing came in or went out yet. Loot, supplies used, trades and price moves all land here as they happen."));
		}

		List<Day> days = new ArrayList<>(ledger.getDays());
		Collections.reverse(days);
		if (!days.isEmpty())
		{
			stack.add(new SectionHeader("Pages", days.size() + (days.size() == 1 ? " day" : " days")));
			long scale = 1;
			int shown = Math.min(days.size(), pagesShown);
			for (int i = 0; i < shown; i++)
			{
				scale = Math.max(scale, Math.max(Math.abs(days.get(i).getPlay()), Math.abs(days.get(i).getMarket())));
			}
			for (int i = 0; i < shown; i++)
			{
				Day d = days.get(i);
				stack.add(new DayRow(d, scale, d.getDate().equals(openDay), () -> openDay(d.getDate())));
			}
			if (days.size() > shown)
			{
				int older = days.size() - shown;
				JPanel more = new JPanel(new BorderLayout());
				more.setOpaque(false);
				more.add(Ui.label(older + (older == 1 ? " older page" : " older pages"), Theme.small(), Theme.TEXT_FAINT), BorderLayout.CENTER);
				more.add(new CodexButton("Show " + Math.min(PAGES, older) + " more", false, () ->
				{
					pagesShown += PAGES;
					rebuildDaybook();
				}), BorderLayout.EAST);
				stack.add(more);
			}
		}
		replace(daybook, stack);
	}

	/** The play taken apart by how it happened: trading, loot, supplies, coins and deaths. */
	private void whereItWent(StackPanel stack, Report report)
	{
		List<ChannelRows.Row> rows = new ArrayList<>();
		for (Channel c : Channel.values())
		{
			long value = report.channel(c);
			if (value != 0)
			{
				rows.add(new ChannelRows.Row(c.getLabel(), value, Theme.GAIN, Theme.LOSS));
			}
		}
		long older = report.unexplained();
		if (older != 0 && !rows.isEmpty())
		{
			// Pages written before the channels were kept.
			rows.add(new ChannelRows.Row("Earlier pages", older, Theme.GOLD_DIM, Theme.GOLD_DIM));
		}
		if (rows.isEmpty())
		{
			return;
		}
		if (report.getMarket() != 0)
		{
			rows.add(new ChannelRows.Row("Market", report.getMarket(), Theme.MARKET, Theme.EMBER));
		}
		stack.add(new SectionHeader("Where it went", Format.signed(report.change())));
		Card card = new Card(new BorderLayout());
		card.setBorder(new EmptyBorder(6, 10, 6, 10));
		card.add(new ChannelRows(rows, "Exchange is buying and selling against the market price, after tax. Loot is every item that came in "
			+ "some other way, Supplies every item that went out other than by a sale. Deaths is what dying took, less what you got back."),
			BorderLayout.CENTER);
		stack.add(card);
	}

	private void lines(StackPanel stack, String heading, List<Report.Line> lines, boolean drift)
	{
		if (lines.isEmpty())
		{
			return;
		}
		long sum = 0;
		for (Report.Line l : lines)
		{
			sum += drift ? l.getDrift() : l.getValue();
		}
		stack.add(new SectionHeader(heading, Format.signed(sum)));
		JPanel list = new JPanel(new GridLayout(0, 1, 0, 2));
		list.setOpaque(false);
		for (Report.Line l : lines)
		{
			String name = name(l.getItemId());
			long amount = drift ? l.getDrift() : l.getValue();
			Color color = drift ? (amount >= 0 ? Theme.MARKET : Theme.EMBER) : Theme.signed(amount);
			String note;
			if (drift)
			{
				long price = ledger.price(l.getItemId());
				note = price > 0 ? "now " + Format.coins(price) + " each" : "price moved";
			}
			else
			{
				long each = Math.abs(l.getValue() / Math.max(1, Math.abs(l.getQuantity())));
				note = Format.count(l.getQuantity()) + (Math.abs(l.getQuantity()) == 1 || each <= 1 ? "" : " (" + Format.coins(each) + " each)");
			}
			list.add(new ItemRow(l.getItemId(), name, note, Format.signed(amount), color, -1, icons,
				name + ": " + Format.count(l.getQuantity()) + ", " + Format.signed(l.getValue()) + " from play, " + Format.signed(l.getDrift()) + " from prices"));
		}
		stack.add(list);
	}

	private String name(int itemId)
	{
		String name = names.get(itemId);
		return name != null ? name : "Item " + itemId;
	}

	// The Trades tab.

	private void rebuildTrades()
	{
		StackPanel stack = new StackPanel(8, 8);
		stack.add(tradeSpan);
		if (waiting(stack))
		{
			replace(trades, stack);
			return;
		}
		int s = tradeSpan.getSelected();
		Trades t = Trades.of(ledger, SPAN_DAYS[s], today());
		Deal total = t.getTotal();
		if (total.volume() == 0 && t.getOpen().isEmpty())
		{
			stack.add(note("No trades yet", "Buy and sell on the Grand Exchange and every trade lands here: what each item made after the 2% tax, "
				+ "and what you still hold from your buys with what it would fetch now."));
			replace(trades, stack);
			return;
		}

		Card head = new Card(new BorderLayout(0, 6));
		head.highlighted(true);
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		JPanel words = new JPanel();
		words.setOpaque(false);
		words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
		String title = s == 0 ? "Today" : s == 3 ? "All trading" : "The last " + SPANS[s];
		words.add(Ui.label(title, Theme.title(), Theme.GOLD_BRIGHT));
		words.add(Ui.label("profit after " + Format.coins(total.getTax()) + " tax", Theme.small(), Theme.TEXT_MUTED));
		top.add(words, BorderLayout.CENTER);
		top.add(Ui.label(Format.signed(total.getProfit()), Theme.title(), Theme.signed(total.getProfit())), BorderLayout.EAST);
		head.add(top, BorderLayout.NORTH);
		JPanel tiles = new JPanel(new GridLayout(2, 2, 6, 6));
		tiles.setOpaque(false);
		tiles.add(new StatTile(Format.coins(total.getBuyCoins()), "Bought", Format.plural(total.getBought(), "item"), Theme.TEAL));
		tiles.add(new StatTile(Format.coins(total.getSaleCoins()), "Sold", Format.plural(total.getSold(), "item"), Theme.GOLD));
		tiles.add(new StatTile(Format.coins(total.getTax()), "Tax paid", "2% of sales", Theme.EMBER));
		double roi = total.roi();
		tiles.add(new StatTile(roi == 0 ? "-" : Format.signedPercent(roi), "Return", "on cost", roi >= 0 ? Theme.GREEN : Theme.LOSS));
		head.add(tiles, BorderLayout.CENTER);
		stack.add(head);

		List<Trades.Item> items = t.getItems();
		if (!items.isEmpty())
		{
			stack.add(new SectionHeader("By item", items.size() + (items.size() == 1 ? " item" : " items")));
			JPanel list = new JPanel(new GridLayout(0, 1, 0, 2));
			list.setOpaque(false);
			for (int i = 0; i < items.size() && i < TRADED; i++)
			{
				Trades.Item it = items.get(i);
				Deal d = it.getDeal();
				String name = name(it.getItemId());
				// Only bought so far: what was spent. Only sold, never bought here: what it fetched, with no profit to tell.
				boolean matched = d.getSold() > d.getUnmatched();
				String amount = matched ? Format.signed(d.getProfit()) : d.getSold() > 0 ? Format.coins(d.getSaleCoins()) : Format.coins(d.getBuyCoins());
				Color color = matched ? Theme.signed(d.getProfit()) : d.getSold() > 0 ? Theme.TEXT_MUTED : Theme.TEAL;
				list.add(new ItemRow(it.getItemId(), name, tradeNote(d), amount, color, -1, icons, tradeTip(name, d)));
			}
			stack.add(list);
			if (items.size() > TRADED)
			{
				stack.add(Ui.label("and " + (items.size() - TRADED) + " more", Theme.small(), Theme.TEXT_FAINT));
			}
		}

		List<Trades.Open> open = t.getOpen();
		if (!open.isEmpty())
		{
			stack.add(new SectionHeader("Still held", "if sold " + Format.signed(t.openGain())));
			JPanel list = new JPanel(new GridLayout(0, 1, 0, 2));
			list.setOpaque(false);
			for (int i = 0; i < open.size() && i < HELD; i++)
			{
				Trades.Open o = open.get(i);
				String name = name(o.getItemId());
				long each = Math.round(o.getCost() / (double) o.getQuantity());
				long now = Math.round(o.getWorth() / (double) o.getQuantity());
				list.add(new ItemRow(o.getItemId(), name, Format.whole(o.getQuantity()) + " at " + Format.coins(each) + ", now " + Format.coins(now),
					Format.signed(o.gain()), Theme.signed(o.gain()), -1, icons,
					name + ": " + Format.whole(o.getQuantity()) + " bought for " + Format.coins(o.getCost()) + " would sell for "
						+ Format.coins(o.getWorth()) + " after tax at today's price."));
			}
			stack.add(list);
		}

		if (total.getUnmatched() > 0)
		{
			stack.add(Ui.wrapped("Sold " + Format.whole(total.getUnmatched()) + (total.getUnmatched() == 1 ? " item" : " items")
				+ " for " + Format.coins(total.getUnmatchedCoins()) + " that were not bought while the ledger watched (loot, or bought before),"
				+ " so no profit or loss is counted on them.", Theme.small(), Theme.TEXT_FAINT, WIDTH - 10));
		}
		stack.add(Ui.wrapped("Each sale is matched to the average cost of what you bought. Tax is 2% of the price, at most 5M an item; "
			+ "none under 50 coins, or on bonds and the other tax-free items." + (s == 3 ? "" : " Pages older than today keep their biggest items only."),
			Theme.small(), Theme.TEXT_FAINT, WIDTH - 10));
		replace(trades, stack);
	}

	/** "Bought 1,000 · sold 1,000 · +26%", or what is known when only one side traded. */
	static String tradeNote(Deal d)
	{
		String bought = d.getBought() > 0 ? "Bought " + Format.whole(d.getBought()) : null;
		String sold = d.getSold() > 0 ? "sold " + Format.whole(d.getSold()) : null;
		if (bought == null && sold == null)
		{
			return "";
		}
		if (sold == null)
		{
			return bought + " at " + Format.coins(Math.round(d.getBuyCoins() / (double) d.getBought()));
		}
		if (bought == null && d.getUnmatched() >= d.getSold())
		{
			return "Sold " + Format.whole(d.getSold()) + ", not bought here";
		}
		String text = bought == null ? "Sold " + Format.whole(d.getSold())
			: d.getBought() == d.getSold() ? "Flipped " + Format.whole(d.getSold()) : bought + ", " + sold;
		double roi = d.roi();
		return roi == 0 ? text : text + ", " + Format.signedPercent(roi);
	}

	private static String tradeTip(String name, Deal d)
	{
		StringBuilder tip = new StringBuilder(name).append(": ");
		if (d.getBought() > 0)
		{
			tip.append("bought ").append(Format.whole(d.getBought())).append(" for ").append(Format.coins(d.getBuyCoins())).append(". ");
		}
		if (d.getSold() > 0)
		{
			tip.append("Sold ").append(Format.whole(d.getSold())).append(" for ").append(Format.coins(d.getSaleCoins()))
				.append(" after ").append(Format.coins(d.getTax())).append(" tax, ").append(Format.signed(d.getProfit())).append(" profit.");
		}
		if (d.getUnmatched() > 0)
		{
			tip.append(' ').append(Format.whole(d.getUnmatched())).append(" of them were never bought here.");
		}
		return tip.toString().trim();
	}

	// The Hoard tab.

	private void rebuildHoard()
	{
		StackPanel stack = new StackPanel(8, 8);
		if (ledger == null)
		{
			waiting(stack);
			replace(hoard, stack);
			return;
		}
		List<Report.Line> lines = Report.holdings(ledger, Integer.MAX_VALUE);
		if (lines.isEmpty())
		{
			stack.add(note("Nothing counted yet", "Items with a Grand Exchange price appear here, most valuable first."));
			replace(hoard, stack);
			return;
		}
		long total = Math.max(1, ledger.getTotal());
		long topFive = 0;
		for (int i = 0; i < lines.size() && i < 5; i++)
		{
			topFive += lines.get(i).getValue();
		}
		Card head = new Card(new BorderLayout(0, 4));
		head.highlighted(true);
		head.add(Ui.label(lines.size() + " kinds of item", Theme.title(), Theme.GOLD_BRIGHT), BorderLayout.NORTH);
		head.add(Ui.wrapped("Your five most valuable hold " + Format.percent(topFive / (double) total) + " of your worth."
			+ (ledger.isReady() ? "" : " Open your bank to count the rest."), Theme.small(), Theme.TEXT_MUTED, WIDTH - 30), BorderLayout.CENTER);
		stack.add(head);

		stack.add(new SectionHeader("Most valuable", "share"));
		JPanel list = new JPanel(new GridLayout(0, 1, 0, 2));
		list.setOpaque(false);
		for (int i = 0; i < lines.size() && i < HOARD; i++)
		{
			Report.Line l = lines.get(i);
			String name = name(l.getItemId());
			long each = ledger.price(l.getItemId());
			String note = each <= 1 ? Format.whole(l.getQuantity())
				: l.getQuantity() > 1 ? Format.whole(l.getQuantity()) + " x " + Format.coins(each) : Format.coins(each) + " each";
			Color color = i == 0 ? Theme.GOLD_BRIGHT : Theme.GOLD;
			list.add(new ItemRow(l.getItemId(), name, note, Format.coins(l.getValue()), color, l.getValue() / (double) total, icons,
				name + ": " + Format.whole(l.getQuantity()) + ", worth " + Format.coins(l.getValue()) + ". " + places(l.getItemId())));
		}
		stack.add(list);
		if (lines.size() > HOARD)
		{
			long rest = 0;
			for (int i = HOARD; i < lines.size(); i++)
			{
				rest += lines.get(i).getValue();
			}
			stack.add(Ui.label("and " + (lines.size() - HOARD) + " more, worth " + Format.coins(rest), Theme.small(), Theme.TEXT_FAINT));
		}
		alchemy(stack);
		List<String> untradeable = new ArrayList<>();
		for (Map.Entry<Integer, Long> e : ledger.holdings().entrySet())
		{
			if (ledger.price(e.getKey()) <= 0)
			{
				untradeable.add(name(e.getKey()));
			}
		}
		if (!untradeable.isEmpty())
		{
			Collections.sort(untradeable);
			String shown = String.join(", ", untradeable.subList(0, Math.min(6, untradeable.size())))
				+ (untradeable.size() > 6 ? " and " + (untradeable.size() - 6) + " more" : "");
			stack.add(Ui.wrapped(untradeable.size() + (untradeable.size() == 1 ? " item has" : " items have")
				+ " no Grand Exchange price and count as nothing: " + shown + ".", Theme.small(), Theme.TEXT_FAINT, WIDTH - 10));
		}
		replace(hoard, stack);
	}

	/** Casts of High Level Alchemy an hour, five ticks each. */
	static final int CASTS_AN_HOUR = 1_200;
	/** Magic experience a cast of High Level Alchemy gives. */
	static final int CAST_XP = 65;

	/** Items worth more alched than sold, with what alching them all makes over selling. */
	private void alchemy(StackPanel stack)
	{
		Alchemy a = alchemy;
		if (a == null || a.isEmpty())
		{
			return;
		}
		stack.add(new SectionHeader("Better alched", Format.signed(a.getGain())));
		long minutes = Math.max(1, Math.round(a.getCasts() * 60.0 / CASTS_AN_HOUR));
		String time = minutes < 60 ? minutes + " minutes" : Format.hours(minutes / 60.0);
		stack.add(Ui.wrapped("High Level Alchemy beats selling these by " + Format.coins(a.getGain()) + ", after nature runes and the "
			+ "exchange's tax: " + Format.whole(a.getCasts()) + " casts, about " + time + " and "
			+ Format.compact(a.getCasts() * CAST_XP) + " Magic xp.", Theme.small(), Theme.TEXT_MUTED, WIDTH - 10));
		JPanel list = new JPanel(new GridLayout(0, 1, 0, 2));
		list.setOpaque(false);
		List<Alchemy.Line> lines = a.getLines();
		long top = Math.max(1, lines.get(0).gain());
		for (int i = 0; i < lines.size() && i < Alchemy.SHOWN; i++)
		{
			Alchemy.Line l = lines.get(i);
			String name = name(l.getItemId());
			String note = Format.whole(l.getQuantity()) + " x " + Format.coins(l.getAlch() - l.getSale()) + " more";
			list.add(new ItemRow(l.getItemId(), name, note, Format.signed(l.gain()), Theme.TEAL, l.gain() / (double) top, icons,
				name + ": alched for " + Format.coins(l.getAlch()) + " each after a nature rune, against "
					+ Format.coins(l.getSale()) + " each sold after tax."));
		}
		stack.add(list);
		if (lines.size() > Alchemy.SHOWN)
		{
			stack.add(Ui.label("and " + (lines.size() - Alchemy.SHOWN) + " more", Theme.small(), Theme.TEXT_FAINT));
		}
	}

	/** "Bank 2, worn 1", where an item is kept. */
	private String places(int itemId)
	{
		StringBuilder text = new StringBuilder();
		for (Map.Entry<Store, Ledger.Holding> e : ledger.getStores().entrySet())
		{
			Long q = e.getValue().getItems().get(itemId);
			if (q != null && q > 0)
			{
				text.append(text.length() == 0 ? "" : ", ").append(e.getKey().getLabel()).append(' ').append(Format.whole(q));
			}
		}
		return text.toString();
	}

	static Card note(String title, String text)
	{
		Card card = new Card(new BorderLayout(0, 4));
		card.accent(Theme.GOLD_DIM);
		card.add(Ui.label(title, Theme.body(), Theme.GOLD), BorderLayout.NORTH);
		card.add(Ui.wrapped(text, Theme.small(), Theme.TEXT_MUTED, WIDTH - 30), BorderLayout.CENTER);
		return card;
	}

	private static void replace(JScrollPane scroll, JComponent view)
	{
		int position = scroll.getVerticalScrollBar().getValue();
		scroll.setViewportView(view);
		scroll.revalidate();
		SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(position));
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		g.setColor(Theme.INK);
		g.fillRect(0, 0, getWidth(), getHeight());
	}
}
