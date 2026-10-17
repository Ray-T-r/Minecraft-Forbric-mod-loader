package org.brook.carrier;

import fixture.carrierchain.Trace;
import org.brook.mill.Grain;
import org.brook.mill.Sack;
import org.brook.mill.Stones;

/** A carrier mod's grain: mills itself, never through the stones. */
public class CustomGrain implements Grain {
	@Override
	public String milledBy(Stones stones, Sack sack, boolean fine) {
		Trace.add("custom mill " + sack.kind());
		return "custom " + sack.kind();
	}
}
