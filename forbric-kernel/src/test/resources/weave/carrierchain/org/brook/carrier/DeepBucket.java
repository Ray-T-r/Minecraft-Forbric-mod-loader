package org.brook.carrier;

import fixture.carrierchain.Trace;
import org.brook.mill.Bucket;

/** A carrier mod's bucket: dips its own way. */
public class DeepBucket extends Bucket {
	@Override
	public double dipped() {
		Trace.add("deep dip");
		return 9.0;
	}
}
