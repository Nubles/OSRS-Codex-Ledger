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

	void add(Flow other)
	{
		add(other.q, other.v, other.d);
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

	/** How much this item mattered either way, for ranking and trimming. */
	long weight()
	{
		return Math.abs(v) + Math.abs(d);
	}
}
