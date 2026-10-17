package org.brook.mill;

public class Sack {
	private final String kind;

	public Sack(String kind) {
		this.kind = kind;
	}

	public String kind() {
		return kind;
	}

	public boolean fine() {
		return kind.length() > 3;
	}
}
