package org.brook.fen;

import fixture.decisionisland.Trace;

/** As merged: the step hands the whole air calculation to the carrier's hook, which decides it its own way. */
public class Wader {
	public Liquid in;
	public boolean alive = true;
	public boolean charmed;
	public boolean hero;
	public boolean immortal;
	public int air = 10;
	public int tank;
	public Pond pond = new Pond();

	public boolean isSubmerged(Liquid liquid) {
		return this.in == liquid;
	}

	public boolean isSubmergedAny() {
		return this.in != null;
	}

	public boolean drownsIn(Liquid liquid) {
		return liquid != Liquid.LAVA;
	}

	public boolean hasGills() {
		return false;
	}

	public int depth() {
		return 3;
	}

	public void step() {
		Trace.add("step");
		if (this.alive) {
			int air = this.air;
			org.brook.carrier.Lungs.breathe(this, air);
		}
		Trace.add("done");
	}
}
