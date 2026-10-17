package org.brook.mill;

import fixture.carrierchain.Trace;

/** The vanilla operation every route ends in. */
public class Stones {
	public String grind(String kind, boolean fine) {
		Trace.add("grind " + kind + (fine ? " fine" : " coarse"));
		return "ground " + kind;
	}
}
