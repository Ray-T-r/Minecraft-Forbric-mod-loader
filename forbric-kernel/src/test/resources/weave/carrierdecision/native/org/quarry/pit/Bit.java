package org.quarry.pit;

/** As the guest's own loader has it: only the vanilla question. */
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
}
