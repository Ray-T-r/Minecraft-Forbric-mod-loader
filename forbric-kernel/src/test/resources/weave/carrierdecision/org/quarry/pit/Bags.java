package org.quarry.pit;

import java.util.HashMap;
import java.util.Map;

public final class Bags {
	private Bags() {
	}

	public static <K, V> Map<K, V> copy(Map<K, V> map) {
		return new HashMap<>(map);
	}
}
