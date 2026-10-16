package org.mill.carrier;

import fixture.nativeentry.Trace;
import org.mill.works.Gate;
import org.mill.works.Water;

/** One carrier's own decision where the host used to ask its own method: true where it settled the water itself. */
public final class Locks {
	private Locks() {
	}

	public static boolean settle(Water water, Gate gate) {
		Trace.add("locks:" + water.name);
		return water.name.equals("mud");
	}
}
