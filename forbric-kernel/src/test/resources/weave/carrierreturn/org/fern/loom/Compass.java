package org.fern.loom;

import fixture.carrierreturn.Trace;

/** Vanilla's own question: the facing a dial gives a spot, or none. */
public final class Compass {
	private Compass() {
	}

	public static String read(Dial dial, String spot) {
		Trace.add("read " + spot);
		return dial.marked(spot) ? spot : null;
	}
}
