package org.mill.works;

import fixture.nativeentry.Trace;

/** As merged: each caller asks a carrier's decision where it asked its own, which nothing calls any more. */
public class Sluice {
	public int flow = 3;

	public void open(Gate gate, Water water, Trough trough) {
		if (!org.mill.carrier.Locks.settle(water, gate)) trough.fill(this.flow);
		Trace.add("tail");
	}

	public void nudge(Gate gate, Water water, Trough trough, int strength) {
		if (!org.mill.carrier.Weirs.settle(water, gate)) trough.fill(this.flow);
	}

	private boolean shouldPour(Water water, Gate gate, Trough trough) {
		Trace.add("native-pour:" + water.name);
		return !water.name.equals("mud");
	}
}
