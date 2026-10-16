package org.harbor.carrier;

import fixture.replaceddecision.Trace;
import org.harbor.deck.Diver;
import org.harbor.deck.Tide;

/** A carrier's own question where the host used to ask its own: whether the tide covers the diver. */
public final class Currents {
	private Currents() {
	}

	public static boolean covers(Diver diver, Tide tide) {
		Trace.add("currents:" + tide.name);
		return tide.name.equals("deep");
	}
}
