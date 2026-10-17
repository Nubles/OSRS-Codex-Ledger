package com.codex.ledger.ui;

import java.awt.Component;
import javax.swing.Timer;

/** Eases a value toward its target on the Swing thread so rings and bars fill instead of jumping. */
final class Animator
{
	private static final int FRAME_MS = 16;
	private static final double EASE = 0.18;

	private final Component owner;
	private final Timer timer;
	private double value;
	private double target;

	Animator(Component owner)
	{
		this.owner = owner;
		this.timer = new Timer(FRAME_MS, e -> step());
	}

	double get()
	{
		return value;
	}

	void set(double newTarget, boolean animate)
	{
		target = newTarget;
		if (!animate || !owner.isShowing())
		{
			value = newTarget;
			timer.stop();
			owner.repaint();
			return;
		}
		if (!timer.isRunning())
		{
			timer.start();
		}
	}

	private void step()
	{
		value += (target - value) * EASE;
		if (Math.abs(target - value) < 0.002 || !owner.isShowing())
		{
			value = target;
			timer.stop();
		}
		owner.repaint();
	}
}
