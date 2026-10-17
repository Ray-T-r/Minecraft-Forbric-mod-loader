package org.brook.carrier;

import fixture.decisionisland.Trace;
import org.brook.fen.Charms;
import org.brook.fen.Wader;

/**
 * The carrier's own air calculation: it asks the charm with the head in any liquid (lava too), and a liquid one cannot
 * drown in lets one breathe without refilling.
 */
public final class Lungs {
	private Lungs() {
	}

	public static void breathe(Wader wader, int air) {
		boolean dry = !wader.isSubmergedAny() || wader.pond.isFoam(wader.depth());
		boolean canBreathe = dry;
		int refill = 1;
		if (!dry && (Charms.canHoldBreath(wader) || !wader.drownsIn(wader.in) || wader.hero && wader.immortal)) {
			canBreathe = true;
			refill = 0;
		}
		if (canBreathe) wader.air = Math.min(air + refill, 10);
		else {
			wader.air = air - 1;
			Trace.add("choke");
		}
	}
}
