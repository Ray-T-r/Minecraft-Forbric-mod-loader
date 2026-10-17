package org.harbor.deck;

import fixture.replaceddecision.Trace;

/**
 * As merged: every update asks a carrier's question where it asked the diver's own. On {@code tick} it decides the very
 * block the native question did; on {@code surface} the block it skips is another one (the diver gasps); on {@code dive}
 * it is kept in the same local, {@code under}, as the native answer was.
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

	public void dive(Tide tide) {
		boolean under = org.harbor.carrier.Currents.covers(this, tide);
		if (!under) {
			this.lung.hold(this.name);
		}
		Trace.add("dived " + under);
	}

	public boolean isUnder(Tide tide) {
		Trace.add("under:" + tide.name);
		return tide.name.equals("deep");
	}
}
