package org.mill.carrier;

import fixture.nativeentry.Trace;
import org.mill.works.Gate;
import org.mill.works.Water;

/** The other carrier's own decision where the host used to ask its own method: true where it settled the water itself. */
public final class Weirs {
	private Weirs() {
	}

	public static boolean settle(Water water, Gate gate) {
		Trace.add("weirs:" + water.name);
		return water.name.equals("mud");
	}
}
