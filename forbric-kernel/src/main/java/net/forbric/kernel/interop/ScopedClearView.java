/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A map that sees only some of another map's keys and writes through to it: what a native {@code field.clear()} would
 * have cleared, where a carrier removes only a computed subset ({@code ScopedClearRelayInjector}).
 *
 * <p>Reads show the backing map's own entries (the same entry objects) whose key is in scope; {@link #clear} removes
 * every in-scope key from the backing map; {@link #put} writes to the backing map and brings the key into scope, so
 * whatever a guest puts back after the clear is in the field and visible here again; {@link #remove} removes from the
 * backing map. So for a guest that snapshots the entries, calls the original clear and puts some back, the field ends
 * exactly as the native clear-and-put-back leaves it, restricted to the keys the carrier was removing.
 */
public final class ScopedClearView<K, V> extends AbstractMap<K, V> {
	private final Map<K, V> backing;
	private final Set<Object> scope;

	private ScopedClearView(Map<K, V> backing, Set<?> keys) {
		this.backing = backing;
		this.scope = new LinkedHashSet<>(keys);
	}

	/** {@code backing} seen through the keys of {@code removing}, the map of what the carrier is about to remove. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static Map of(Map backing, Map removing) {
		return new ScopedClearView<>(backing, removing.keySet());
	}

	@Override public void clear() {
		for (Object key : scope) backing.remove(key);
	}

	@Override public V put(K key, V value) {
		scope.add(key);
		return backing.put(key, value);
	}

	@Override public V remove(Object key) {
		return scope.contains(key) ? backing.remove(key) : null;
	}

	@Override public V get(Object key) {
		return scope.contains(key) ? backing.get(key) : null;
	}

	@Override public boolean containsKey(Object key) {
		return scope.contains(key) && backing.containsKey(key);
	}

	@Override public Set<Entry<K, V>> entrySet() {
		return new AbstractSet<>() {
			@Override public Iterator<Entry<K, V>> iterator() {
				// The backing map's own entry objects, as a native iteration of the field would hand out; taken up front so
				// a removal through this iterator (removeIf) writes through without upsetting the backing iterator.
				java.util.List<Entry<K, V>> entries = new java.util.ArrayList<>();
				for (Entry<K, V> entry : backing.entrySet()) if (scope.contains(entry.getKey())) entries.add(entry);
				Iterator<Entry<K, V>> each = entries.iterator();
				return new Iterator<>() {
					private Entry<K, V> current;

					@Override public boolean hasNext() {
						return each.hasNext();
					}

					@Override public Entry<K, V> next() {
						current = each.next();
						return current;
					}

					@Override public void remove() {
						if (current == null) throw new IllegalStateException();
						backing.remove(current.getKey());
						current = null;
					}
				};
			}

			@Override public int size() {
				int size = 0;
				for (Object key : scope) if (backing.containsKey(key)) size++;
				return size;
			}
		};
	}
}
