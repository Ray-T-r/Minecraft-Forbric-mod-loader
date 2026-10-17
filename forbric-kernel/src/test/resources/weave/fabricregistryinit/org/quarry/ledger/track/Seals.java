package org.quarry.ledger.track;

/** The synthetic mod's own trackers: what its bootstrap and start callbacks do besides the freeze. */
public final class Seals {
	private Seals() {
	}

	public static void open() {
		fixture.fabricregistryinit.Trace.add("fabric:trackers");
	}

	public static void afterSeal() {
		fixture.fabricregistryinit.Trace.add("fabric:postFreeze");
	}
}
