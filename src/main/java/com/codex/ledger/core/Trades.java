package com.codex.ledger.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The account's trading on the Grand Exchange over a stretch of days: what each item made after
 * tax, the tax paid, and what is still held from buys and what it would fetch if sold now.
 */
public final class Trades
{
	/** One item's trading over the stretch. */
	public static final class Item
	{
		private final int itemId;
		private final Deal deal;

		Item(int itemId, Deal deal)
		{
			this.itemId = itemId;
			this.deal = deal;
		}

		public int getItemId()
		{
			return itemId;
		}

		public Deal getDeal()
		{
			return deal;
		}
	}

	/** Items bought and still held, with what they cost and what they would fetch now after tax. */
	public static final class Open
	{
		private final int itemId;
		private final long quantity;
		private final long cost;
		private final long worth;

		Open(int itemId, long quantity, long cost, long worth)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.cost = cost;
			this.worth = worth;
		}

		public int getItemId()
		{
			return itemId;
		}

		public long getQuantity()
		{
			return quantity;
		}

		public long getCost()
		{
			return cost;
		}

		/** What they would fetch at today's price, after tax. */
		public long getWorth()
		{
			return worth;
		}

		public long gain()
		{
			return worth - cost;
		}
	}

	private final Deal total;
	private final List<Item> items;
	private final List<Open> open;
	private final LocalDate from;

	private Trades(Deal total, List<Item> items, List<Open> open, LocalDate from)
	{
		this.total = total;
		this.items = items;
		this.open = open;
		this.from = from;
	}

	/**
	 * @param span days to cover, today included; 0 or less covers everything traded since the
	 *             ledger began watching trades
	 */
	public static Trades of(Ledger ledger, int span, LocalDate today)
	{
		Deal total = new Deal();
		Map<Integer, Deal> byItem = new HashMap<>();
		LocalDate first = null;
		if (span <= 0)
		{
			for (Map.Entry<Integer, Position> e : ledger.getBook().entrySet())
			{
				Deal life = e.getValue().life();
				if (life.volume() > 0)
				{
					byItem.computeIfAbsent(e.getKey(), k -> new Deal()).add(life);
					total.add(life);
				}
			}
		}
		else
		{
			LocalDate start = today.minusDays(span - 1);
			for (Day day : ledger.getDays())
			{
				LocalDate date = LocalDate.parse(day.getDate());
				if (date.isBefore(start))
				{
					continue;
				}
				first = first == null ? date : first;
				total.add(day.getTrades());
				for (Map.Entry<Integer, Deal> e : day.getDeals().entrySet())
				{
					byItem.computeIfAbsent(e.getKey(), k -> new Deal()).add(e.getValue());
				}
			}
		}
		List<Item> items = new ArrayList<>();
		for (Map.Entry<Integer, Deal> e : byItem.entrySet())
		{
			items.add(new Item(e.getKey(), e.getValue()));
		}
		// The biggest winners first, the biggest losers last; items only bought sit between, by what was spent.
		items.sort(Comparator.comparingLong((Item i) -> i.deal.getProfit()).reversed()
			.thenComparing(Comparator.comparingLong((Item i) -> i.deal.volume()).reversed()));

		List<Open> open = new ArrayList<>();
		Map<Integer, Long> held = ledger.holdings();
		for (Map.Entry<Integer, Position> e : ledger.getBook().entrySet())
		{
			Position p = e.getValue();
			long quantity = Math.min(p.getHeld(), held.getOrDefault(e.getKey(), 0L));
			long price = ledger.price(e.getKey());
			if (quantity <= 0 || price <= 0)
			{
				continue;
			}
			long cost = Position.share(p.getCost(), quantity, p.getHeld());
			long worth = quantity * price - Offer.tax(e.getKey(), quantity, quantity * price);
			open.add(new Open(e.getKey(), quantity, cost, worth));
		}
		open.sort(Comparator.comparingLong((Open o) -> Math.abs(o.gain())).reversed());
		return new Trades(total, Collections.unmodifiableList(items), Collections.unmodifiableList(open), first);
	}

	/** Every item traded together. */
	public Deal getTotal()
	{
		return total;
	}

	/** Each item traded, the biggest winner first. */
	public List<Item> getItems()
	{
		return items;
	}

	/** Items bought and still held, the biggest gain or loss if sold now first. */
	public List<Open> getOpen()
	{
		return open;
	}

	/** The first day book page the stretch covers, or null for everything since trades were watched. */
	public LocalDate getFrom()
	{
		return from;
	}

	/** What everything still held from buys would make or lose if sold now, after tax. */
	public long openGain()
	{
		long sum = 0;
		for (Open o : open)
		{
			sum += o.gain();
		}
		return sum;
	}
}
