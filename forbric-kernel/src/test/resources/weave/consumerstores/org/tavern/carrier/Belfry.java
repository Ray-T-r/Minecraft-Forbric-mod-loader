package org.tavern.carrier;

import org.tavern.hall.Metal;

/** One carrier's tone: the hall's own for bronze and gold, its own for tin and iron. */
public final class Belfry {
	private Belfry() {
	}

	public static String tone(Metal metal) {
		return switch (metal) {
			case BRONZE -> "low";
			case GOLD -> "high";
			case TIN -> "tinny";
			case IRON -> null;
		};
	}
}
