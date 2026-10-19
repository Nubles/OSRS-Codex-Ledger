package com.codex.ledger.core;

import java.util.function.Function;

/**
 * Writes the ledger out for saving within a size budget, cheaply. A ledger inside its budget is
 * written once. One that passes it is trimmed below a lower mark ({@link #ROOM} of the budget) so
 * the next saves have room to grow before trimming again; and one that trimming cannot bring under
 * (its holdings alone pass the budget) is not trimmed again until it has gathered enough new detail
 * for a trim to take anything. Either way a save is one write-out, plus a few when the budget is
 * first passed. Not thread safe: one per plugin, used on the client thread.
 */
public final class Saver
{
	/** Trimming aims this far under the budget, so the saves after it do not trim again. */
	static final double ROOM = 0.9;
	/** New detail (worth points, page items, trades) a stuck ledger gathers before it is trimmed again. */
	static final int REGROW = 64;

	private final int budget;
	/** The ledger's detail after a trim that could not bring it under the budget; -1 when none did. */
	private int stuckAt = -1;
	private int writes;

	public Saver(int budget)
	{
		this.budget = budget;
	}

	/**
	 * The ledger written out, trimmed first if it passes the budget and trimming can help.
	 *
	 * @param json writes the ledger out as it stands
	 */
	public String save(Ledger ledger, Function<Ledger, String> json)
	{
		Function<Ledger, String> counted = l ->
		{
			writes++;
			return json.apply(l);
		};
		String saved = counted.apply(ledger);
		if (saved.length() <= budget)
		{
			return saved;
		}
		if (stuckAt >= 0 && ledger.detail() < stuckAt + REGROW)
		{
			// Trimmed already as far as it goes, and too little has been added since for a trim to take anything.
			return saved;
		}
		saved = ledger.fit((int) (budget * ROOM), counted, saved);
		stuckAt = saved.length() > budget ? ledger.detail() : -1;
		return saved;
	}

	/** Times the ledger has been written out, for measuring. */
	public int getWrites()
	{
		return writes;
	}

	/** A new ledger (another account) starts with no history of trimming. */
	public void reset()
	{
		stuckAt = -1;
	}
}
