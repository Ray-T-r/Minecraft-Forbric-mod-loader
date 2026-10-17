package org.meadow.kite;

import java.util.List;

public final class Picker {
	private Picker() {
	}

	/** Natively never handed an empty list: it would fail. */
	public static String pick(List<String> parts) {
		if (parts.isEmpty()) throw new IllegalArgumentException("nothing to pick");
		return parts.get(0);
	}
}
