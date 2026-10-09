package com.google.common.collect;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A stand-in for Guava's ImmutableMap (the game's library, not the harness's), with the members the GuiRenderer
 * constructor and the kernel use, and Guava's contracts: buildOrThrow refuses a key put twice, buildKeepingLast keeps the
 * later value.
 */
public class ImmutableMap<K, V> extends AbstractMap<K, V> {
	private final Map<K, V> values;

	private ImmutableMap(Map<K, V> values) {
		this.values = values;
	}

	public static <K, V> Builder<K, V> builder() {
		return new Builder<>();
	}

	@Override
	public Set<Entry<K, V>> entrySet() {
		return values.entrySet();
	}

	public static class Builder<K, V> {
		private final List<Map.Entry<K, V>> entries = new ArrayList<>();

		public Builder<K, V> put(K key, V value) {
			entries.add(Map.entry(key, value));
			return this;
		}

		public Builder<K, V> putAll(Map<? extends K, ? extends V> map) {
			map.forEach(this::put);
			return this;
		}

		public ImmutableMap<K, V> buildOrThrow() {
			Map<K, V> built = new LinkedHashMap<>();
			for (Map.Entry<K, V> entry : entries) {
				if (built.putIfAbsent(entry.getKey(), entry.getValue()) != null) throw new IllegalArgumentException("Multiple entries with same key: " + entry.getKey());
			}
			return new ImmutableMap<>(built);
		}

		public ImmutableMap<K, V> buildKeepingLast() {
			Map<K, V> built = new LinkedHashMap<>();
			for (Map.Entry<K, V> entry : entries) built.put(entry.getKey(), entry.getValue());
			return new ImmutableMap<>(built);
		}
	}
}
