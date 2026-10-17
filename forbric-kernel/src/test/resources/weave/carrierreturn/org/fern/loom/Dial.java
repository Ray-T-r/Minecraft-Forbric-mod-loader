package org.fern.loom;

import fixture.carrierreturn.Trace;

/** What the spindle reads its facing off. */
public class Dial {
	public boolean marked(String spot) {
		return spot.equals("north");
	}

	public String bearing(String spot) {
		Trace.add("carrier-bearing " + spot);
		return spot;
	}
}
