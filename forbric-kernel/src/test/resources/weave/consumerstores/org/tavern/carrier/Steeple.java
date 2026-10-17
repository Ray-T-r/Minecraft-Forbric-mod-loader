package org.tavern.carrier;

import org.tavern.hall.Metal;

/** The other carrier's custom tone: only for iron. */
public final class Steeple {
	private Steeple() {
	}

	public static String tone(Metal metal) {
		return metal == Metal.IRON ? "clang" : null;
	}
}
