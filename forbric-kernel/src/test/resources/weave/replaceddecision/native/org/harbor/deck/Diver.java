package org.harbor.deck;

import fixture.replaceddecision.Trace;

/** As the guest's own loader has it: both updates ask the diver's own question. */
public class Diver {
	public final String name;
	public final Lung lung = new Lung();

	public Diver(String name) {
		this.name = name;
	}

	public void tick(Tide tide) {
		Trace.add("tick");
		if (!this.isUnder(tide)) {
			this.lung.hold(this.name);
		}
		Trace.add("tail");
	}

	public void surface(Tide tide) {
		if (!this.isUnder(tide)) {
			this.lung.hold(this.name);
		}
	}

	public boolean isUnder(Tide tide) {
		Trace.add("under:" + tide.name);
		return tide.name.equals("deep");
	}
}
