package org.brook.mill;

import fixture.carrierchain.Trace;

/** The vanilla operation the harvest ends in. */
public class Field {
	public void mark(int day, String kernel) {
		Trace.add("mark day " + day + " " + kernel);
	}
}
