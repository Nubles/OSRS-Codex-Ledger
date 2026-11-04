package com.codex.ledger.core;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The flips book: every Grand Exchange offer watched slot by slot, its buys kept as lots and its
 * sales matched against them, oldest lot first, into realised flips with their profit after tax.
 *
 * <p>A flip's profit is only what a sale actually brought in over what the same items cost: prices
 * moving on what is still held are left to the Worth page's market line and never counted here.
 *
 * <p>The game keeps trading while the player is logged out, and offers can be placed, collected or
 * cancelled from another device. None of that is seen as it happens. What can be told at the next
 * login is told, and marked as seen late: an offer that moved on (the trades are exact, only when
 * they happened is not), one placed elsewhere, and one gone from its slot part way through (what it
 * traded after it was last seen is not known, and is said so instead of guessed).
 */
public final class FlipBook
{
	/** Flips kept, newest last; the oldest go first. */
	static final int FLIPS = 400;
	/** Price marks kept for each item, and items with marks. */
	static final int MARKS = 16;
	static final int MARKED_ITEMS = 150;
	/** Items whose unsold lots are kept, those bought longest ago go first. */
	static final int LOT_ITEMS = 200;
	/** Offers that ended out of sight, kept to tell the player. */
	static final int UNSEEN = 20;
	/** Slots not watched for longer than this (a log out, not a world hop) may have been traded from elsewhere. */
	public static final long AWAY_MS = 60_000L;
	/** How long an offer gone from its slot part way through is kept, should it come back (a free world hides members' slots). */
	static final long GONE_MS = 14 * 86_400_000L;

	/** One Grand Exchange slot as the book last saw it. */
	public static final class Slot
	{
		private int i;
		private boolean b;
		private int n;
		private long p;
		private int d;
		private long s;
		/** Still trading. */
		private boolean a;
		/** The offer's number in this book, so the fills of one sale make one flip. */
		private long o;
		/** Placed somewhere between these times; equal when seen being placed. */
		private long p0;
		private long p1;
		/** Last traded somewhere between these times; 0 when it has not traded. */
		private long f0;
		private long f1;
		/** First seen at login rather than being placed: what it did before that is not known in time. */
		private boolean u;
		/** When it went from its slot part way through, for an offer kept in case it comes back. */
		private long g;

		public Slot()
		{
		}

		boolean same(Offer offer)
		{
			return offer != null && i == offer.getItem() && b == offer.isBuy() && n == offer.getTotal() && p == offer.getPrice();
		}

		public int getItem()
		{
			return i;
		}

		public boolean isBuy()
		{
			return b;
		}

		public int getTotal()
		{
			return n;
		}

		public long getPrice()
		{
			return p;
		}

		public int getDone()
		{
			return d;
		}

		public long getSpent()
		{
			return s;
		}

		public boolean isActive()
		{
			return a;
		}

		public boolean isComplete()
		{
			return d >= n;
		}

		/** Placed between these times; the same time when it was seen being placed, 0 for the earliest when not known at all. */
		public long getPlacedFrom()
		{
			return p0;
		}

		public long getPlacedTo()
		{
			return p1;
		}

		/** Last traded between these times, or 0 when it has not traded since placed. */
		public long getFilledFrom()
		{
			return f0;
		}

		public long getFilledTo()
		{
			return f1;
		}

		public boolean isSeenLate()
		{
			return u;
		}

		/**
		 * How long the slot has stood still, as the shortest and longest it could be: since its last trade,
		 * or since it was placed. The longest is -1 when there is no telling (it was there before the book began).
		 */
		public long[] idle(long now)
		{
			long from = f1 > 0 ? f0 : p0;
			long to = f1 > 0 ? f1 : p1;
			long least = Math.max(0, now - to);
			long most = from <= 0 ? -1 : Math.max(least, now - from);
			return new long[]{least, most};
		}
	}

	/** Items bought and not yet sold, with what they cost. */
	public static final class Lot
	{
		private int i;
		private long q;
		private long c;
		private long t0;
		private long t1;
		private boolean u;

		public Lot()
		{
		}

		public int getItem()
		{
			return i;
		}

		public long getQuantity()
		{
			return q;
		}

		public long getCost()
		{
			return c;
		}

		/** Bought between these times. */
		public long getFrom()
		{
			return t0;
		}

		public long getTo()
		{
			return t1;
		}

		public boolean isSeenLate()
		{
			return u;
		}
	}

	/** One sale matched against what the items cost: a realised flip. */
	public static final class Flip
	{
		private int i;
		/** Items matched to a buy, what they cost, and what they brought after tax. */
		private long q;
		private long c;
		private long r;
		/** Tax the exchange kept from the whole sale. */
		private long x;
		/** Items sold that the book never saw bought, and what they brought after tax. */
		private long uq;
		private long ur;
		/** The earliest buy matched, and when the sale traded (between s0 and s1). */
		private long b0;
		private long s0;
		private long s1;
		/** The sale offer's number in the book. */
		private long o;
		/** Partly seen late: some of it traded, or was bought, while the player was away. */
		private boolean u;

		public Flip()
		{
		}

		public int getItem()
		{
			return i;
		}

		public long getQuantity()
		{
			return q;
		}

		public long getCost()
		{
			return c;
		}

		/** What the matched items brought, after tax. */
		public long getRevenue()
		{
			return r;
		}

		public long getTax()
		{
			return x;
		}

		public long getProfit()
		{
			return r - c;
		}

		public long getUnmatched()
		{
			return uq;
		}

		public long getUnmatchedRevenue()
		{
			return ur;
		}

		public long getBoughtFrom()
		{
			return b0;
		}

		public long getSoldFrom()
		{
			return s0;
		}

		public long getSoldAt()
		{
			return s1;
		}

		public boolean isSeenLate()
		{
			return u;
		}

		/** The profit on each matched item, or 0 when nothing matched. */
		public double margin()
		{
			return q <= 0 ? 0 : (r - c) / (double) q;
		}

		/** How long the items were held, from the first matched buy to the sale; -1 when nothing matched. */
		public long held()
		{
			return q <= 0 || b0 <= 0 ? -1 : Math.max(0, s1 - b0);
		}
	}

	/** A price the player traded at: one buy or sale fill, each. */
	public static final class Mark
	{
		private long t;
		private boolean b;
		private long p;
		private long q;
		private boolean u;

		public Mark()
		{
		}

		Mark(long t, boolean b, long p, long q, boolean u)
		{
			this.t = t;
			this.b = b;
			this.p = p;
			this.q = q;
			this.u = u;
		}

		public long getTime()
		{
			return t;
		}

		public boolean isBuy()
		{
			return b;
		}

		/** The price each, before tax. */
		public long getPrice()
		{
			return p;
		}

		public long getQuantity()
		{
			return q;
		}

		public boolean isSeenLate()
		{
			return u;
		}
	}

	/** An offer that left its slot part way through while the player was away: what it did after it was last seen is not known. */
	public static final class Unseen
	{
		private int i;
		private boolean b;
		private int d;
		private int n;
		private long t0;
		private long t1;

		public Unseen()
		{
		}

		public int getItem()
		{
			return i;
		}

		public boolean isBuy()
		{
			return b;
		}

		/** How far it had got when last seen. */
		public int getDone()
		{
			return d;
		}

		public int getTotal()
		{
			return n;
		}

		/** It went somewhere between these times. */
		public long getFrom()
		{
			return t0;
		}

		public long getTo()
		{
			return t1;
		}
	}

	/** One item's flips over a stretch, added up. */
	public static final class Item
	{
		private final int itemId;
		private int flips;
		private long quantity;
		private long cost;
		private long revenue;
		private long tax;
		private long unmatched;
		private boolean seenLate;
		private long last;

		Item(int itemId)
		{
			this.itemId = itemId;
		}

		void add(Flip f)
		{
			flips += f.q > 0 ? 1 : 0;
			quantity += f.q;
			cost += f.c;
			revenue += f.r;
			tax += f.x;
			unmatched += f.uq;
			seenLate |= f.u;
			last = Math.max(last, f.s1);
		}

		public int getItemId()
		{
			return itemId;
		}

		public int getFlips()
		{
			return flips;
		}

		public long getQuantity()
		{
			return quantity;
		}

		public long getCost()
		{
			return cost;
		}

		public long getRevenue()
		{
			return revenue;
		}

		public long getProfit()
		{
			return revenue - cost;
		}

		public long getTax()
		{
			return tax;
		}

		public long getUnmatched()
		{
			return unmatched;
		}

		public boolean isSeenLate()
		{
			return seenLate;
		}

		public long getLast()
		{
			return last;
		}

		public double margin()
		{
			return quantity <= 0 ? 0 : getProfit() / (double) quantity;
		}

		public double roi()
		{
			return cost <= 0 ? 0 : getProfit() / (double) cost;
		}
	}

	/** The flips of a stretch of time added up, overall and by item. */
	public static final class Summary
	{
		private final Item total = new Item(-1);
		private final List<Flip> flips = new ArrayList<>();
		private final List<Item> items = new ArrayList<>();
		private int seenLate;
		private long seenLateProfit;

		public Item getTotal()
		{
			return total;
		}

		/** The flips of the stretch, newest first. */
		public List<Flip> getFlips()
		{
			return flips;
		}

		/** Each item flipped, the biggest profit first. */
		public List<Item> getItems()
		{
			return items;
		}

		/** Flips partly seen late, and the profit they make together. */
		public int getSeenLate()
		{
			return seenLate;
		}

		public long getSeenLateProfit()
		{
			return seenLateProfit;
		}
	}

	private int v = 1;
	/** The last moment the slots were watched. */
	private long seen;
	/** True once the slots have been read whole: before that an offer's progress is not known to be new. */
	private boolean known;
	private long next;
	private Map<Integer, Slot> slots = new HashMap<>();
	/** Offers gone from their slot part way through, kept in case they come back, by slot. */
	private Map<Integer, Slot> gone = new HashMap<>();
	private Map<Integer, List<Lot>> lots = new HashMap<>();
	private List<Flip> flips = new ArrayList<>();
	private Map<Integer, List<Mark>> marks = new HashMap<>();
	private List<Unseen> unseen = new ArrayList<>();

	public FlipBook()
	{
	}

	/** Notes that the slots are being watched now, so a later read can tell a world hop from time away. */
	public void watching(long now)
	{
		if (known)
		{
			seen = Math.max(seen, now);
		}
	}

	/**
	 * Reads every slot at once, after logging in or turning the plugin on. Whatever moved since the
	 * slots were last watched, if that was long enough ago for the account to have been played or
	 * traded from elsewhere, is booked as seen late, its time known only to lie between then and now.
	 *
	 * @param offers the offer in each slot, null for an empty one
	 * @return true when the book changed
	 */
	public boolean read(Offer[] offers, long now)
	{
		boolean first = !known;
		boolean away = seen <= 0 || now - seen > AWAY_MS;
		long from = seen > 0 ? seen : 0;
		boolean changed = false;
		for (int slot = 0; slot < offers.length; slot++)
		{
			Offer offer = offers[slot];
			if (first)
			{
				changed |= adopt(slot, offer, now);
			}
			else
			{
				changed |= update(slot, offer, away ? from : now, now, away);
			}
		}
		known = true;
		seen = now;
		return changed | first;
	}

	/**
	 * One slot changed while being watched.
	 *
	 * @return true when the book changed
	 */
	public boolean offer(int slot, Offer offer, long now)
	{
		if (!known)
		{
			return false;
		}
		seen = Math.max(seen, now);
		return update(slot, offer, now, now, false);
	}

	/** The first read ever: what is in the slots is taken as it stands, its progress so far not booked, since no one knows when it traded. */
	private boolean adopt(int slot, Offer offer, long now)
	{
		if (offer == null)
		{
			return slots.remove(slot) != null;
		}
		Slot s = slot(offer, 0, now, true);
		slots.put(slot, s);
		return true;
	}

	private Slot slot(Offer offer, long from, long to, boolean late)
	{
		Slot s = new Slot();
		s.i = offer.getItem();
		s.b = offer.isBuy();
		s.n = offer.getTotal();
		s.p = offer.getPrice();
		s.d = offer.getDone();
		s.s = offer.getSpent();
		s.a = offer.isActive();
		s.o = ++next;
		s.p0 = from;
		s.p1 = to;
		s.u = late;
		return s;
	}

	/**
	 * @param from the earliest anything seen now could have happened: now, or when last watched
	 * @param late true when it was not seen as it happened
	 */
	private boolean update(int slot, Offer offer, long from, long now, boolean late)
	{
		Slot old = slots.get(slot);
		// The same offer carries on; one that has gone back is a new one just like it, placed after the old was collected.
		if (old != null && old.same(offer) && offer.getDone() >= old.d && offer.getSpent() >= old.s)
		{
			return progress(old, offer, from, now, late);
		}
		boolean changed = false;
		if (old != null)
		{
			slots.remove(slot);
			changed = true;
			if (old.a && !old.isComplete())
			{
				// Gone part way through. Collected and cancelled from another device, or only hidden for a while
				// (a free world shows no members' slots): kept, so that if it comes back it carries on where it was.
				old.g = now;
				gone.put(slot, old);
				if (late)
				{
					noteUnseen(old, from, now);
				}
			}
		}
		if (offer != null)
		{
			Slot back = gone.get(slot);
			if (back != null && back.same(offer) && offer.getDone() >= back.d && offer.getSpent() >= back.s)
			{
				gone.remove(slot);
				forget(back);
				back.g = 0;
				slots.put(slot, back);
				progress(back, offer, from, now, late);
				return true;
			}
			Slot s = slot(offer, from, now, late);
			slots.put(slot, s);
			Slot blank = new Slot();
			blank.i = s.i;
			blank.b = s.b;
			blank.o = s.o;
			blank.u = late;
			// Placed since the slots were last watched: whatever it has traded, it traded since.
			fill(s, blank, offer, from, now, late);
			changed = true;
		}
		prune(now);
		return changed;
	}

	/** An offer the book is watching moved on: books what it traded since last seen. */
	private boolean progress(Slot s, Offer offer, long from, long now, boolean late)
	{
		boolean changed = s.d != offer.getDone() || s.s != offer.getSpent() || s.a != offer.isActive();
		Slot before = new Slot();
		before.i = s.i;
		before.b = s.b;
		before.d = s.d;
		before.s = s.s;
		before.o = s.o;
		before.u = s.u;
		s.d = offer.getDone();
		s.s = offer.getSpent();
		s.a = offer.isActive();
		changed |= fill(s, before, offer, from, now, late);
		return changed;
	}

	/** Books what traded between two sightings of one offer. */
	private boolean fill(Slot s, Slot before, Offer offer, long from, long now, boolean late)
	{
		long quantity = (long) offer.getDone() - before.d;
		long coins = offer.getSpent() - before.s;
		if (quantity <= 0 || coins < 0)
		{
			return false;
		}
		s.f0 = from;
		s.f1 = now;
		if (late)
		{
			s.u = true;
		}
		mark(s.i, new Mark(now, s.b, Math.round(coins / (double) quantity), quantity, late));
		if (s.b)
		{
			Lot lot = new Lot();
			lot.i = s.i;
			lot.q = quantity;
			lot.c = coins;
			lot.t0 = from;
			lot.t1 = now;
			lot.u = late;
			lots.computeIfAbsent(s.i, k -> new ArrayList<>()).add(lot);
			trimLots();
			return true;
		}
		long tax = Math.max(0, Offer.tax(s.i, offer.getDone(), offer.getSpent()) - Offer.tax(s.i, before.d, before.s));
		sold(s.i, s.o, quantity, coins - tax, tax, from, now, late);
		return true;
	}

	/** Matches a sale against the oldest lots first. */
	void sold(int item, long offerId, long quantity, long net, long tax, long from, long now, boolean late)
	{
		List<Lot> held = lots.getOrDefault(item, Collections.emptyList());
		long matched = 0;
		long cost = 0;
		long firstBuy = 0;
		boolean lateLot = false;
		Iterator<Lot> it = held.iterator();
		while (it.hasNext() && matched < quantity)
		{
			Lot lot = it.next();
			long take = Math.min(lot.q, quantity - matched);
			long part = Position.share(lot.c, take, lot.q);
			firstBuy = firstBuy == 0 ? lot.t0 > 0 ? lot.t0 : lot.t1 : firstBuy;
			lateLot |= lot.u;
			matched += take;
			cost += part;
			lot.q -= take;
			lot.c -= part;
			if (lot.q <= 0)
			{
				it.remove();
			}
		}
		if (held.isEmpty())
		{
			lots.remove(item);
		}
		long matchedNet = Position.share(net, matched, quantity);
		Flip f = null;
		for (int i = flips.size() - 1; i >= 0 && i >= flips.size() - 16; i--)
		{
			if (flips.get(i).o == offerId && flips.get(i).i == item)
			{
				f = flips.get(i);
				break;
			}
		}
		if (f == null)
		{
			f = new Flip();
			f.i = item;
			f.o = offerId;
			f.s0 = from;
			flips.add(f);
		}
		f.q += matched;
		f.c += cost;
		f.r += matchedNet;
		f.x += tax;
		f.uq += quantity - matched;
		f.ur += net - matchedNet;
		if (firstBuy > 0 && (f.b0 == 0 || firstBuy < f.b0))
		{
			f.b0 = firstBuy;
		}
		f.s1 = now;
		f.u |= late || lateLot;
		while (flips.size() > FLIPS)
		{
			flips.remove(0);
		}
	}

	private void mark(int item, Mark mark)
	{
		List<Mark> list = marks.computeIfAbsent(item, k -> new ArrayList<>());
		list.add(mark);
		while (list.size() > MARKS)
		{
			list.remove(0);
		}
		if (marks.size() > MARKED_ITEMS)
		{
			// The item traded longest ago goes.
			int oldest = -1;
			long when = Long.MAX_VALUE;
			for (Map.Entry<Integer, List<Mark>> e : marks.entrySet())
			{
				long t = e.getValue().isEmpty() ? 0 : e.getValue().get(e.getValue().size() - 1).t;
				if (t < when)
				{
					when = t;
					oldest = e.getKey();
				}
			}
			marks.remove(oldest);
		}
	}

	private void trimLots()
	{
		while (lots.size() > LOT_ITEMS)
		{
			int oldest = -1;
			long when = Long.MAX_VALUE;
			for (Map.Entry<Integer, List<Lot>> e : lots.entrySet())
			{
				long t = e.getValue().isEmpty() ? 0 : e.getValue().get(e.getValue().size() - 1).t1;
				if (t < when)
				{
					when = t;
					oldest = e.getKey();
				}
			}
			lots.remove(oldest);
		}
	}

	private void noteUnseen(Slot s, long from, long now)
	{
		Unseen u = new Unseen();
		u.i = s.i;
		u.b = s.b;
		u.d = s.d;
		u.n = s.n;
		u.t0 = from;
		u.t1 = now;
		unseen.add(u);
		while (unseen.size() > UNSEEN)
		{
			unseen.remove(0);
		}
	}

	/** An offer thought gone came back: it did not end out of sight after all. */
	private void forget(Slot back)
	{
		for (int i = unseen.size() - 1; i >= 0; i--)
		{
			Unseen u = unseen.get(i);
			if (u.i == back.i && u.b == back.b && u.n == back.n && u.d == back.d && u.t1 >= back.g)
			{
				unseen.remove(i);
				return;
			}
		}
	}

	private void prune(long now)
	{
		gone.values().removeIf(s -> s == null || now - s.g > GONE_MS);
	}

	/**
	 * Items bought that have since left the account other than by a sale (eaten, used, lost, or only
	 * gone somewhere the ledger cannot see) are no longer there to flip: the oldest lots give them up.
	 *
	 * @param held what the account holds now, by item, the Grand Exchange's slots included
	 * @return true when any lot shrank
	 */
	public boolean holdAtMost(Map<Integer, Long> held)
	{
		boolean changed = false;
		for (Iterator<Map.Entry<Integer, List<Lot>>> it = lots.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<Integer, List<Lot>> e = it.next();
			long have = Math.max(0, held.getOrDefault(e.getKey(), 0L));
			long inLots = 0;
			for (Lot lot : e.getValue())
			{
				inLots += lot.q;
			}
			long excess = inLots - have;
			Iterator<Lot> lotIt = e.getValue().iterator();
			while (excess > 0 && lotIt.hasNext())
			{
				Lot lot = lotIt.next();
				long drop = Math.min(excess, lot.q);
				long part = Position.share(lot.c, drop, lot.q);
				lot.q -= drop;
				lot.c -= part;
				excess -= drop;
				changed = true;
				if (lot.q <= 0)
				{
					lotIt.remove();
				}
			}
			if (e.getValue().isEmpty())
			{
				it.remove();
			}
		}
		return changed;
	}

	/**
	 * The flips whose sale traded at or after {@code from} (0 for all), added up.
	 */
	public Summary summary(long from)
	{
		Summary sum = new Summary();
		Map<Integer, Item> byItem = new HashMap<>();
		for (int i = flips.size() - 1; i >= 0; i--)
		{
			Flip f = flips.get(i);
			if (f.s1 < from)
			{
				continue;
			}
			sum.flips.add(f);
			sum.total.add(f);
			byItem.computeIfAbsent(f.i, Item::new).add(f);
			if (f.u && f.q > 0)
			{
				sum.seenLate++;
				sum.seenLateProfit += f.getProfit();
			}
		}
		sum.items.addAll(byItem.values());
		sum.items.sort(Comparator.comparingLong(Item::getProfit).reversed().thenComparing(Comparator.comparingLong(Item::getLast).reversed()));
		return sum;
	}

	/** The tax the exchange keeps from selling one of an item at a price: see {@link Offer#tax(int, long, long)}. */
	public static long taxEach(int itemId, long price)
	{
		return Offer.tax(itemId, 1, price);
	}

	/** The offer in each slot as last seen, by slot. */
	public Map<Integer, Slot> getSlots()
	{
		return Collections.unmodifiableMap(slots);
	}

	/** Each item's unsold lots, oldest first. */
	public Map<Integer, List<Lot>> getLots()
	{
		return Collections.unmodifiableMap(lots);
	}

	/** Every flip kept, oldest first. */
	public List<Flip> getFlips()
	{
		return Collections.unmodifiableList(flips);
	}

	/** The prices each item was last traded at, oldest first. */
	public List<Mark> marks(int itemId)
	{
		List<Mark> list = marks.get(itemId);
		return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
	}

	public Map<Integer, List<Mark>> getMarks()
	{
		return Collections.unmodifiableMap(marks);
	}

	/** Offers that left their slot part way through while the player was away, oldest first. */
	public List<Unseen> getUnseen()
	{
		return Collections.unmodifiableList(unseen);
	}

	/** True once the slots have been read whole at least once. */
	public boolean isKnown()
	{
		return known;
	}

	public long getSeen()
	{
		return seen;
	}

	/**
	 * Reads a saved book, or null when there is none or it cannot be read. Missing parts (a save from
	 * before they were kept) come back empty, and broken entries are dropped.
	 */
	public static FlipBook read(Gson gson, String json)
	{
		if (json == null || json.trim().isEmpty())
		{
			return null;
		}
		FlipBook book;
		try
		{
			book = gson.fromJson(json, FlipBook.class);
		}
		catch (RuntimeException e)
		{
			return null;
		}
		return book == null ? null : book.repair();
	}

	/** Puts back anything a save left out, and drops entries that make no sense. */
	public FlipBook repair()
	{
		slots = slots == null ? new HashMap<>() : slots;
		gone = gone == null ? new HashMap<>() : gone;
		lots = lots == null ? new HashMap<>() : lots;
		flips = flips == null ? new ArrayList<>() : flips;
		marks = marks == null ? new HashMap<>() : marks;
		unseen = unseen == null ? new ArrayList<>() : unseen;
		slots.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
		gone.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
		lots.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
		for (List<Lot> list : lots.values())
		{
			list.removeIf(l -> l == null || l.q <= 0);
		}
		lots.values().removeIf(List::isEmpty);
		flips.removeIf(f -> f == null);
		marks.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
		for (List<Mark> list : marks.values())
		{
			list.removeIf(m -> m == null);
		}
		unseen.removeIf(u -> u == null);
		return this;
	}
}
