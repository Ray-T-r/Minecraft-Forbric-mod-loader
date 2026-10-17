package org.brook.fen;

import fixture.decisionisland.Trace;

/** A static question both the native step and the carrier's hook ask. */
public final class Charms {
	private Charms() {
	}

	public static boolean canHoldBreath(Wader wader) {
		Trace.add("charm:" + (wader.in == null ? "air" : wader.in.name));
		return wader.charmed;
	}
}
