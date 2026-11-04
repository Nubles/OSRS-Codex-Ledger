package com.codex.ledger.ui;

import com.codex.ledger.core.FlipBook;
import com.codex.ledger.core.Ledger;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

/**
 * The Flips page: realised flips after tax, the slots with how long each has stood still, each
 * item's margin as it has moved, and what is bought but not yet sold, valued apart as market.
 */
final class FlipsPage
{
	static final int RECENT = 8;
	static final int MARGINS = 6;
	static final int STOCK = 6;
	/** Offers that ended out of sight are told for a week. */
	static final long UNSEEN_MS = 7 * 86_400_000L;
	private static final int WIDTH = LedgerPanel.WIDTH;

	private FlipsPage()
	{
	}

	static void build(StackPanel stack, FlipBook book, Ledger ledger, Map<Integer, String> names, ItemIcons icons, long now,
		long from, String title, int idleMinutes)
	{
		if (book == null)
		{
			stack.add(LedgerPanel.note("The flips book is closed", "Log in and every Grand Exchange offer is watched slot by slot: "
				+ "each sale matched to what you paid, the profit after tax, and how long each slot has stood still."));
			return;
		}
		boolean empty = book.getSlots().isEmpty() && book.getFlips().isEmpty() && book.getLots().isEmpty();
		if (empty)
		{
			stack.add(LedgerPanel.note("No flips yet", "Buy on the Grand Exchange and sell again, and each sale lands here matched to what "
				+ "those items cost: the profit after the 2% tax, how long you held them, and each item's margin as it moves. "
				+ "Slots are timed from their last trade, so you can see which offer needs a new price."));
			return;
		}
		FlipBook.Summary sum = book.summary(from);
		head(stack, sum, title);
		slots(stack, book, names, icons, now, idleMinutes);
		unseen(stack, book, names, now);
		recent(stack, sum, names, icons);
		margins(stack, book, sum, names, icons, from);
		stock(stack, book, ledger, names, icons);
		stack.add(Ui.wrapped("Each sale is matched to your oldest unsold buys of the item first. Profit is what the sale brought after tax "
			+ "over what those items cost: 2% of the price, rounded down, at most 5M an item, none under 50 coins or on bonds and the "
			+ "other tax-free items. Prices moving on what you still hold are market, shown on the Worth page, never flip profit.",
			Theme.small(), Theme.TEXT_FAINT, WIDTH - 10));
		stack.add(Ui.wrapped("Offers keep trading while you are logged out. What moved is read at your next login and marked seen late: "
			+ "the amounts are exact, the times only known to lie between your last log out and that login. An offer placed and "
			+ "collected wholly on another device never shows in its slot, so it cannot be counted.",
			Theme.small(), Theme.TEXT_FAINT, WIDTH - 10));
	}

	private static void head(StackPanel stack, FlipBook.Summary sum, String title)
	{
		FlipBook.Item t = sum.getTotal();
		Card head = new Card(new BorderLayout(0, 6));
		head.highlighted(true);
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		JPanel words = new JPanel();
		words.setOpaque(false);
		words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
		words.add(Ui.label(title, Theme.title(), Theme.GOLD_BRIGHT));
		words.add(Ui.label("realised, after " + Format.coins(t.getTax()) + " tax", Theme.small(), Theme.TEXT_MUTED));
		top.add(words, BorderLayout.CENTER);
		top.add(Ui.label(Format.signed(t.getProfit()), Theme.title(), Theme.signed(t.getProfit())), BorderLayout.EAST);
		head.add(top, BorderLayout.NORTH);
		JPanel tiles = new JPanel(new GridLayout(2, 2, 6, 6));
		tiles.setOpaque(false);
		tiles.add(new StatTile(Format.whole(t.getFlips()), "Flips", Format.plural(flipped(sum), "item"), Theme.GOLD));
		tiles.add(new StatTile(Format.amount(t.getQuantity()), "Flipped", "items", Theme.TEAL));
		long margin = Math.round(t.margin());
		tiles.add(new StatTile(t.getQuantity() == 0 ? "-" : Format.signed(margin), "Margin", "an item", Theme.signed(margin)));
		double roi = t.roi();
		tiles.add(new StatTile(roi == 0 ? "-" : Format.signedPercent(roi), "Return", "on cost", roi >= 0 ? Theme.GREEN : Theme.LOSS));
		head.add(tiles, BorderLayout.CENTER);
		if (sum.getSeenLate() > 0)
		{
			head.add(Ui.wrapped(Format.plural(sum.getSeenLate(), "flip") + " (" + Format.signed(sum.getSeenLateProfit())
				+ ") traded while you were away: amounts exact, times read at login.", Theme.small(), Theme.VIOLET, WIDTH - 30), BorderLayout.SOUTH);
		}
		stack.add(head);
	}

	/** Items with at least one sale matched to a buy. */
	private static int flipped(FlipBook.Summary sum)
	{
		int n = 0;
		for (FlipBook.Item it : sum.getItems())
		{
			n += it.getFlips() > 0 ? 1 : 0;
		}
		return n;
	}

	private static void slots(StackPanel stack, FlipBook book, Map<Integer, String> names, ItemIcons icons, long now, int idleMinutes)
	{
		Map<Integer, FlipBook.Slot> slots = book.getSlots();
		if (slots.isEmpty())
		{
			return;
		}
		long idleMs = idleMinutes * 60_000L;
		int idle = 0;
		JPanel list = new JPanel(new GridLayout(0, 1, 0, 2));
		list.setOpaque(false);
		List<Integer> order = new ArrayList<>(slots.keySet());
		order.sort(Comparator.naturalOrder());
		for (int slot : order)
		{
			FlipBook.Slot s = slots.get(slot);
			String name = name(names, s.getItem());
			long[] still = s.idle(now);
			boolean trading = s.isActive() && !s.isComplete();
			boolean isIdle = trading && idleMs > 0 && still[0] >= idleMs;
			idle += isIdle ? 1 : 0;
			String clock;
			Color clockColor;
			if (s.isComplete() || !s.isActive())
			{
				clock = "collect";
				clockColor = s.isComplete() ? Theme.GREEN : Theme.TEXT_MUTED;
			}
			else
			{
				clock = (isIdle ? "idle " : "") + since(still);
				clockColor = isIdle ? Theme.EMBER : s.isSeenLate() && still[1] != still[0] ? Theme.VIOLET : Theme.TEXT;
			}
			String verb = s.isBuy() ? s.isActive() ? "Buying " : "Bought " : s.isActive() ? "Selling " : "Sold ";
			String note = verb + Format.whole(s.getDone()) + "/" + Format.whole(s.getTotal()) + " at " + Format.coins(s.getPrice());
			list.add(new SlotRow(slot + 1, s.getItem(), name, note, clock, clockColor, s.isBuy() ? Theme.TEAL : Theme.GOLD,
				s.getTotal() > 0 ? s.getDone() / (double) s.getTotal() : 0, s.isSeenLate(), icons, slotTip(s, name, still, now)));
		}
		stack.add(new SectionHeader("Slots", idle > 0 ? idle + " idle" : slots.size() + (slots.size() == 1 ? " offer" : " offers")));
		stack.add(list);
	}

	/** How long a slot has stood still: "14m", or "14m-3h" when it is only known to lie between. */
	static String since(long[] still)
	{
		long least = still[0];
		long most = still[1];
		if (most < 0)
		{
			return brief(least) + "+";
		}
		if (most - least < 60_000L || coarse(least).equals(coarse(most)))
		{
			return brief(least);
		}
		return coarse(least) + "-" + coarse(most);
	}

	/** For the ends of a range, which need no minutes once past an hour: "40m", "2h", "3d". */
	static String coarse(long ms)
	{
		long m = Math.max(0, ms) / 60_000L;
		if (m < 60)
		{
			return brief(ms);
		}
		return m < 24 * 60 ? Math.round(m / 60.0) + "h" : Math.round(m / (24 * 60.0)) + "d";
	}

	/** "<1m", "14m", "2h", "2h 5m", "3d". */
	static String brief(long ms)
	{
		long m = Math.max(0, ms) / 60_000L;
		if (m < 1)
		{
			return "<1m";
		}
		if (m < 60)
		{
			return m + "m";
		}
		if (m < 24 * 60)
		{
			return m % 60 == 0 || m >= 10 * 60 ? m / 60 + "h" : m / 60 + "h " + m % 60 + "m";
		}
		return m / (24 * 60) + "d";
	}

	private static String slotTip(FlipBook.Slot s, String name, long[] still, long now)
	{
		StringBuilder tip = new StringBuilder(name).append(": ").append(s.isBuy() ? "buying " : "selling ")
			.append(Format.whole(s.getTotal())).append(" at ").append(Format.whole(s.getPrice())).append(" each, ")
			.append(Format.whole(s.getDone())).append(" done. ");
		if (s.getFilledTo() > 0)
		{
			tip.append(s.getFilledFrom() == s.getFilledTo() ? "Last traded " + Format.ago(now - s.getFilledTo()) + "."
				: "Last traded while you were away, between " + Format.time(s.getFilledFrom()) + " and " + Format.time(s.getFilledTo()) + ".");
		}
		else if (s.getPlacedFrom() <= 0)
		{
			tip.append("Already there when the flips book began, so how long it has stood is not known.");
		}
		else if (s.getPlacedFrom() != s.getPlacedTo())
		{
			tip.append("Placed while you were away, between ").append(Format.time(s.getPlacedFrom())).append(" and ")
				.append(Format.time(s.getPlacedTo())).append(", and not traded since.");
		}
		else
		{
			tip.append("Placed ").append(Format.ago(now - s.getPlacedTo())).append(", not traded since.");
		}
		return tip.toString();
	}

	private static void unseen(StackPanel stack, FlipBook book, Map<Integer, String> names, long now)
	{
		List<FlipBook.Unseen> recent = new ArrayList<>();
		for (FlipBook.Unseen u : book.getUnseen())
		{
			if (now - u.getTo() <= UNSEEN_MS)
			{
				recent.add(0, u);
			}
		}
		if (recent.isEmpty())
		{
			return;
		}
		Card card = new Card(new BorderLayout(0, 4));
		card.accent(Theme.VIOLET);
		card.add(Ui.label("Ended out of sight", Theme.body(), Theme.VIOLET), BorderLayout.NORTH);
		JPanel lines = new JPanel();
		lines.setOpaque(false);
		lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
		for (FlipBook.Unseen u : recent)
		{
			lines.add(Ui.wrapped((u.isBuy() ? "A buy of " : "A sale of ") + name(names, u.getItem()) + " left its slot at "
				+ Format.whole(u.getDone()) + " of " + Format.whole(u.getTotal()) + " while you were away (" + Format.day(u.getTo()) + "). "
				+ "Anything it traded after that was collected on another device and is not counted.", Theme.small(), Theme.TEXT_MUTED, WIDTH - 30));
		}
		card.add(lines, BorderLayout.CENTER);
		stack.add(card);
	}

	private static void recent(StackPanel stack, FlipBook.Summary sum, Map<Integer, String> names, ItemIcons icons)
	{
		List<FlipBook.Flip> flips = sum.getFlips();
		if (flips.isEmpty())
		{
			return;
		}
		stack.add(new SectionHeader("Recent flips", Format.plural(flips.size(), "sale")));
		JPanel list = new JPanel(new GridLayout(0, 1, 0, 2));
		list.setOpaque(false);
		for (int i = 0; i < flips.size() && i < RECENT; i++)
		{
			FlipBook.Flip f = flips.get(i);
			String name = name(names, f.getItem());
			list.add(new ItemRow(f.getItem(), name, flipNote(f), f.getQuantity() > 0 ? Format.signed(f.getProfit()) : Format.coins(f.getUnmatchedRevenue()),
				f.getQuantity() > 0 ? Theme.signed(f.getProfit()) : Theme.TEXT_MUTED, -1, icons, flipTip(f, name)));
		}
		stack.add(list);
	}

	/** "1,000 at +12 ea, held 2h 5m", with "seen late" in front for one traded while away. */
	static String flipNote(FlipBook.Flip f)
	{
		String late = f.isSeenLate() ? "Seen late, " : "";
		if (f.getQuantity() <= 0)
		{
			return late + "sold " + Format.whole(f.getUnmatched()) + ", not bought here";
		}
		String note = Format.whole(f.getQuantity()) + " at " + Format.signed(Math.round(f.margin())) + " ea";
		long held = f.held();
		if (held >= 0)
		{
			note += ", held " + brief(held);
		}
		return late + note;
	}

	private static String flipTip(FlipBook.Flip f, String name)
	{
		StringBuilder tip = new StringBuilder(name).append(": ");
		if (f.getQuantity() > 0)
		{
			tip.append(Format.whole(f.getQuantity())).append(" bought for ").append(Format.coins(f.getCost())).append(" sold for ")
				.append(Format.coins(f.getRevenue())).append(" after tax, ").append(Format.signed(f.getProfit())).append(".");
		}
		if (f.getUnmatched() > 0)
		{
			tip.append(' ').append(Format.whole(f.getUnmatched())).append(" more sold for ").append(Format.coins(f.getUnmatchedRevenue()))
				.append(" were never seen bought, so no profit is counted on them.");
		}
		tip.append(" Tax ").append(Format.coins(f.getTax())).append('.');
		if (f.isSeenLate())
		{
			tip.append(" Part of it traded while you were away: the amounts are exact, the times are not.");
		}
		return tip.toString();
	}

	private static void margins(StackPanel stack, FlipBook book, FlipBook.Summary sum, Map<Integer, String> names, ItemIcons icons, long from)
	{
		// Items with both a buy and a sale on record, most recently traded first.
		List<Map.Entry<Integer, List<FlipBook.Mark>>> items = new ArrayList<>();
		for (Map.Entry<Integer, List<FlipBook.Mark>> e : book.getMarks().entrySet())
		{
			List<FlipBook.Mark> marks = e.getValue();
			if (marks.isEmpty() || marks.get(marks.size() - 1).getTime() < from)
			{
				continue;
			}
			boolean buy = false;
			boolean sell = false;
			for (FlipBook.Mark m : marks)
			{
				buy |= m.isBuy();
				sell |= !m.isBuy();
			}
			if (buy && sell)
			{
				items.add(e);
			}
		}
		if (items.isEmpty())
		{
			return;
		}
		items.sort(Comparator.comparingLong((Map.Entry<Integer, List<FlipBook.Mark>> e) -> e.getValue().get(e.getValue().size() - 1).getTime()).reversed());
		stack.add(new SectionHeader("Margins", "buy and sell"));
		JPanel list = new JPanel(new GridLayout(0, 1, 0, 4));
		list.setOpaque(false);
		for (int k = 0; k < items.size() && k < MARGINS; k++)
		{
			int id = items.get(k).getKey();
			List<FlipBook.Mark> marks = items.get(k).getValue();
			long lastBuy = 0;
			long lastSell = 0;
			int n = marks.size();
			long[] times = new long[n];
			long[] prices = new long[n];
			boolean[] buys = new boolean[n];
			boolean[] late = new boolean[n];
			for (int i = 0; i < n; i++)
			{
				FlipBook.Mark m = marks.get(i);
				times[i] = m.getTime();
				prices[i] = m.getPrice();
				buys[i] = m.isBuy();
				late[i] = m.isSeenLate();
				if (m.isBuy())
				{
					lastBuy = m.getPrice();
				}
				else
				{
					lastSell = m.getPrice();
				}
			}
			long tax = FlipBook.taxEach(id, lastSell);
			long margin = lastSell - tax - lastBuy;
			String name = name(names, id);
			FlipBook.Item realised = null;
			for (FlipBook.Item it : sum.getItems())
			{
				if (it.getItemId() == id)
				{
					realised = it;
				}
			}
			String tip = name + ": last bought at " + Format.whole(lastBuy) + ", last sold at " + Format.whole(lastSell)
				+ " (" + Format.whole(tax) + " tax), " + Format.signed(margin) + " each after tax."
				+ (realised != null && realised.getQuantity() > 0 ? " Realised over the span: " + Format.plural(realised.getFlips(), "flip") + ", "
				+ Format.signed(realised.getProfit()) + ", " + Format.signed(Math.round(realised.margin())) + " each." : "")
				+ " Teal is what you paid, gold what you sold for; a ring traded while you were away.";
			list.add(new MarginRow(id, name, "buy " + Format.coins(lastBuy) + " / sell " + Format.coins(lastSell), Format.signed(margin) + " ea",
				Theme.signed(margin), times, prices, buys, late, icons, tip));
		}
		stack.add(list);
	}

	private static void stock(StackPanel stack, FlipBook book, Ledger ledger, Map<Integer, String> names, ItemIcons icons)
	{
		Map<Integer, List<FlipBook.Lot>> lots = book.getLots();
		if (lots.isEmpty())
		{
			return;
		}
		List<long[]> rows = new ArrayList<>();
		long total = 0;
		for (Map.Entry<Integer, List<FlipBook.Lot>> e : lots.entrySet())
		{
			long q = 0;
			long c = 0;
			for (FlipBook.Lot l : e.getValue())
			{
				q += l.getQuantity();
				c += l.getCost();
			}
			long price = ledger == null ? 0 : ledger.price(e.getKey());
			long worth = price <= 0 ? -1 : q * (price - FlipBook.taxEach(e.getKey(), price));
			rows.add(new long[]{e.getKey(), q, c, worth});
			total += worth < 0 ? 0 : worth - c;
		}
		rows.sort(Comparator.comparingLong((long[] r) -> -r[2]));
		stack.add(new SectionHeader("Not yet sold", "if sold " + Format.signed(total)));
		JPanel list = new JPanel(new GridLayout(0, 1, 0, 2));
		list.setOpaque(false);
		for (int i = 0; i < rows.size() && i < STOCK; i++)
		{
			long[] r = rows.get(i);
			int id = (int) r[0];
			String name = name(names, id);
			long each = Math.round(r[2] / (double) r[1]);
			boolean priced = r[3] >= 0;
			long gain = priced ? r[3] - r[2] : 0;
			String note = Format.whole(r[1]) + " at " + Format.coins(each) + (priced ? ", now " + Format.coins(Math.round(r[3] / (double) r[1])) : "");
			list.add(new ItemRow(id, name, note, priced ? Format.signed(gain) : "-", priced ? Theme.TEAL : Theme.TEXT_FAINT, -1, icons,
				name + ": " + Format.whole(r[1]) + " bought for " + Format.coins(r[2]) + (priced ? ", would sell for " + Format.coins(r[3])
				+ " after tax at today's price. That is market, not a flip, until you sell." : ".")));
		}
		stack.add(list);
	}

	private static String name(Map<Integer, String> names, int itemId)
	{
		String name = names.get(itemId);
		return name != null ? name : "Item " + itemId;
	}
}
