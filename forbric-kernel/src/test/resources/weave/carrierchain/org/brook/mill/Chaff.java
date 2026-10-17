package org.brook.mill;

/** A carrier method whose implementation makes the vanilla call but reads a member only its package may: never inlined. */
public class Chaff {
	private final String kind;

	public Chaff(String kind) {
		this.kind = kind;
	}

	public String blownBy(Stones stones) {
		return stones.grind(lift(), false);
	}

	String lift() {
		return kind;
	}
}
