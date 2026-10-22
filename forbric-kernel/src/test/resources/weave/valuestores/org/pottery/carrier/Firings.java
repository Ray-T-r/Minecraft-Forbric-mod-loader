package org.pottery.carrier;

import fixture.valuestores.Trace;
import org.pottery.kiln.Kiln;
import org.pottery.kiln.Pot;

/** The carrier's firing event: sees the raw copy and the baked pot, answers the baked pot. */
public final class Firings {
	private Firings() {
	}

	public static Pot fired(Kiln kiln, Pot raw, Pot baked) {
		Trace.add("fired " + raw + " into " + baked);
		return baked;
	}
}
