package org.quarry.pit;

/** As merged: the carrier added its own question, {@code dulls}, which tolerates a step of wear. */
public class Bit {
	public final String kind;
	public final int wear;

	public Bit(String kind, int wear) {
		this.kind = kind;
		this.wear = wear;
	}

	public static boolean sameEdge(Bit a, Bit b) {
		return a.kind.equals(b.kind) && a.wear == b.wear;
	}

	public boolean dulls(Bit held) {
		fixture.carrierdecision.Trace.add("carrier-dulls");
		return !kind.equals(held.kind) || Math.abs(wear - held.wear) > 1;
	}
}
