package org.brook.mill;

public class Ear implements EarExtension {
	private final Stalk stalk;
	private final String name;

	public Ear(Stalk stalk, String name) {
		this.stalk = stalk;
		this.name = name;
	}

	public Stalk stalk() {
		return stalk;
	}

	public String kernel(int day) {
		return name + "@" + day;
	}
}
