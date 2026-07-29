package net.minecraft.core;

import java.util.LinkedHashMap;
import java.util.Map;

public class IdMapper<T> {
	private final Map<T, Integer> ids = new LinkedHashMap<>();

	public int getId(Object value) {
		return ids.getOrDefault(value, -1);
	}

	public void add(Object value) {
		@SuppressWarnings("unchecked")
		T cast = (T) value;
		ids.put(cast, ids.size());
	}

	public void clear() {
		ids.clear();
	}

	public int size() {
		return ids.size();
	}
}
