package org.orchard.press;

/** The carrier's own question for the same thing vanilla measures. */
public final class Taster {
	private Taster() {
	}

	public static boolean sweet(String juice) {
		return juice.length() > 12;
	}
}
