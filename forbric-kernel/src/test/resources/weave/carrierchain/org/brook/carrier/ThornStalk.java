package org.brook.carrier;

import fixture.carrierchain.Trace;
import org.brook.mill.Ear;
import org.brook.mill.Field;
import org.brook.mill.Stalk;

/** A carrier mod's stalk: ripens its own way. */
public class ThornStalk extends Stalk {
	@Override
	public void ripen(Ear ear, Field field, int day) {
		Trace.add("thorn ripen " + ear.kernel(day));
	}
}
