package org.meadow.carrier;

import fixture.carrierguard.Trace;
import org.meadow.kite.Kite;

/** The carrier's own veto of a launch. */
public final class Hooks {
	private Hooks() {
	}

	public static boolean vetoLaunch(Kite kite) {
		Trace.add("carrier-veto " + kite.name);
		return kite.name.equals("tethered");
	}
}
