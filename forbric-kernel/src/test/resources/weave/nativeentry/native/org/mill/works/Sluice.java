package org.mill.works;

import fixture.nativeentry.Trace;

/** As the guest's own loader has it: both callers ask the decision method. */
public class Sluice {
	public int flow = 3;

	public void open(Gate gate, Water water, Trough trough) {
		if (this.shouldPour(water, gate, trough)) trough.fill(this.flow);
		Trace.add("tail");
	}

	public void nudge(Gate gate, Water water, Trough trough, int strength) {
		if (this.shouldPour(water, gate, trough)) trough.fill(this.flow);
	}

	private boolean shouldPour(Water water, Gate gate, Trough trough) {
		Trace.add("native-pour:" + water.name);
		return !water.name.equals("mud");
	}
}
