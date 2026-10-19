package com.codex.ledger.core;

/**
 * What one item did to the account's worth over a stretch of time: how many came or went,
 * what that came to at the day's prices, and how much the price moving changed what was held.
 */
public final class Flow
{
	/** Net quantity gained (or lost, when negative). */
	private long q;
	/** Value gained or lost by quantity changing, at the price of the moment. */
	private long v;
	/** Value gained or lost by the price moving on what was already held. */
	private long d;
	/** The part of the quantity that was bought or sold on the Grand Exchange. */
	private long tq;
	/** What that part came to at the price of the moment. */
	private long tv;

	public Flow()
	{
	}

	public Flow(long quantity, long value, long drift)
	{
		this.q = quantity;
		this.v = value;
		this.d = drift;
	}

	void add(long quantity, long value, long drift)
	{
		q += quantity;
		v += value;
		d += drift;
	}

	void traded(long quantity, long value)
	{
		tq += quantity;
		tv += value;
	}

	void add(Flow other)
	{
		add(other.q, other.v, other.d);
		traded(other.tq, other.tv);
	}

	public long getQuantity()
	{
		return q;
	}

	public long getValue()
	{
		return v;
	}

	public long getDrift()
	{
		return d;
	}

	/** The quantity gained or lost other than by trading on the Grand Exchange. */
	public long getOwnQuantity()
	{
		return q - tq;
	}

	/** The value gained or lost other than by trading on the Grand Exchange. */
	public long getOwnValue()
	{
		return v - tv;
	}

	/** How much this item mattered either way, for ranking and trimming. */
	long weight()
	{
		return Math.abs(v) + Math.abs(d);
	}
}
