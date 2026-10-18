package com.codex.ledger.ui;

import com.codex.ledger.core.Day;
import com.codex.ledger.core.Ledger;
import com.codex.ledger.core.Offer;
import com.codex.ledger.core.Report;
import com.codex.ledger.core.Store;
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
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.PluginPanel;

/**
 * The Ledger side panel: the crest, then three pages. Worth is the line of the account's worth
 * over time and where it is kept, Day book is where the gold came from and went, and Hoard is
 * everything held, most valuable first.
 */
public class LedgerPanel extends PluginPanel
{
	static final int WIDTH = 200;
	private static final String[] TABS = {"worth", "daybook", "hoard"};
	private static final String[] RANGES = {"Day", "Week", "Month", "Year", "All"};
	private static final long[] RANGE_MS = {86_400_000L, 7 * 86_400_000L, 30 * 86_400_000L, 365 * 86_400_000L, 0};
	private static final int[] RANGE_DAYS = {1, 7, 30, 365, 0};
	private static final String[] RANGE_NOTES = {"today", "last 7 days", "last 30 days", "last year", "all time"};
	private static final String[] SPANS = {"Today", "7 days", "30 days", "All"};
	private static final int[] SPAN_DAYS = {1, 7, 30, 0};
	private static final int LINES = 6;
	private static final int PAGES = 14;
	private static final int HOARD = 40;

	/** What the panel has to show. */
	public enum State
	{
		LOGGED_OUT,
		WAITING_FOR_BANK,
		READY
	}

	private final ItemIcons icons;
	private final Crest crest = new Crest();
	private final Segmented tabs = new Segmented(true, "Worth", "Day book", "Hoard");
	private final Segmented range = new Segmented(false, RANGES);
	private final Segmented span = new Segmented(false, SPANS);
	private final CardLayout cards = new CardLayout();
	private final JPanel content = new JPanel(cards);
	private final JScrollPane worth = scroller();
	private final JScrollPane daybook = scroller();
	private final JScrollPane hoard = scroller();

	private Ledger ledger;
	private Map<Integer, String> names = Collections.emptyMap();
	private State state = State.LOGGED_OUT;
	private long now;
	private ZoneId zone = ZoneId.systemDefault();
	private String openDay;
	private int pagesShown = PAGES;
	private final int[] chartPointer = {-1};
	/** Tabs whose content is out of date; a hidden tab is only rebuilt when it is next shown. */
	private final boolean[] stale = {true, true, true};

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
		content.add(hoard, TABS[2]);
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

	@Override
	public void onActivate()
	{
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
		if (tab == 0)
		{
			rebuildWorth();
		}
		else if (tab == 1)
		{
			rebuildDaybook();
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
			stack.add(note("Open your bank once", "The ledger begins the first time it sees your bank. After that it keeps count on its own, and the bank is remembered as last seen whenever it is closed."));
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
		JPanel tiles = new JPanel(new GridLayout(2, 2, 6, 6));
		tiles.setOpaque(false);
		String when = RANGE_NOTES[r];
		tiles.add(new StatTile(Format.signed(report.getPlay()), "Play", when, Theme.signed(report.getPlay())));
		tiles.add(new StatTile(Format.signed(report.getMarket()), "Market", when, report.getMarket() >= 0 ? Theme.MARKET : Theme.EMBER));
		Day today = ledger.day(today());
		if (r == 0 && today != null)
		{
			// One day has no best and worst; its high and low say more.
			tiles.add(new StatTile(Format.coins(today.getHigh()), "High", "today", Theme.GOLD_BRIGHT));
			tiles.add(new StatTile(Format.coins(today.getLow()), "Low", "today", Theme.GOLD));
			stack.add(tiles);
			whereKept(stack);
			replace(worth, stack);
			return;
		}
		tiles.add(new StatTile(best == null ? "-" : Format.signed(best.change()), "Best day",
			best == null ? "" : Format.day(LocalDate.parse(best.getDate())), best == null ? Theme.TEXT_MUTED : Theme.signed(best.change())));
		tiles.add(new StatTile(worst == null ? "-" : Format.signed(worst.change()), "Worst day",
			worst == null ? "" : Format.day(LocalDate.parse(worst.getDate())), worst == null ? Theme.TEXT_MUTED : Theme.signed(worst.change())));
		stack.add(tiles);
		whereKept(stack);
		replace(worth, stack);
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
		String done = String.format("%,d", o.getDone());
		String total = String.format("%,d", o.getTotal());
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
		stack.add(head);

		lines(stack, "Came in", report.getGains(), false);
		lines(stack, "Went out", report.getLosses(), false);
		lines(stack, "Price moves", report.getMovers(), true);
		if (report.getGains().isEmpty() && report.getLosses().isEmpty() && report.getMovers().isEmpty())
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
			String note = each <= 1 ? String.format("%,d", l.getQuantity())
				: l.getQuantity() > 1 ? String.format("%,d x %s", l.getQuantity(), Format.coins(each)) : Format.coins(each) + " each";
			Color color = i == 0 ? Theme.GOLD_BRIGHT : Theme.GOLD;
			list.add(new ItemRow(l.getItemId(), name, note, Format.coins(l.getValue()), color, l.getValue() / (double) total, icons,
				name + ": " + String.format("%,d", l.getQuantity()) + ", worth " + Format.coins(l.getValue()) + ". " + places(l.getItemId())));
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

	/** "Bank 2, worn 1", where an item is kept. */
	private String places(int itemId)
	{
		StringBuilder text = new StringBuilder();
		for (Map.Entry<Store, Ledger.Holding> e : ledger.getStores().entrySet())
		{
			Long q = e.getValue().getItems().get(itemId);
			if (q != null && q > 0)
			{
				text.append(text.length() == 0 ? "" : ", ").append(e.getKey().getLabel()).append(' ').append(String.format("%,d", q));
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
