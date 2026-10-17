package org.brook.fen;

import fixture.decisionisland.Trace;

/** As the guest's own loader has it: the step decides the air itself, asking the charm only with the head under water. */
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
			if (this.isSubmerged(Liquid.WATER) && !this.pond.isFoam(this.depth())) {
				boolean choking = !this.hasGills() && !Charms.canHoldBreath(this) && !(this.hero && this.immortal);
				if (choking) {
					this.air = this.air - 1;
					Trace.add("choke");
				}
			} else if (this.air < 10) {
				this.air = this.air + 1;
			}
		}
		Trace.add("done");
	}
}
