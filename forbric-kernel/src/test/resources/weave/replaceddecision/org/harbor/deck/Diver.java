package org.harbor.deck;

import fixture.replaceddecision.Trace;

/**
 * As merged: both updates ask a carrier's question where they asked the diver's own. On {@code tick} it decides the very
 * block the native question did; on {@code surface} the block it skips is another one (the diver gasps).
 */
public class Diver {
	public final String name;
	public final Lung lung = new Lung();

	public Diver(String name) {
		this.name = name;
	}

	public void tick(Tide tide) {
		Trace.add("tick");
		if (!org.harbor.carrier.Currents.covers(this, tide)) {
			this.lung.hold(this.name);
		}
		Trace.add("tail");
	}

	public void surface(Tide tide) {
		if (!org.harbor.carrier.Currents.covers(this, tide)) {
			this.lung.gasp(this.name);
		}
	}

	public boolean isUnder(Tide tide) {
		Trace.add("under:" + tide.name);
		return tide.name.equals("deep");
	}
}
