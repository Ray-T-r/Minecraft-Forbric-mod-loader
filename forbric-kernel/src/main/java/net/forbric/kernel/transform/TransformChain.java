/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The single ordered, pluggable class-transformation pipeline.
 *
 * <p>Transformers are registered into a {@link TransformPhase}. Phases always run in
 * {@link TransformPhase#ordinal()} order. Within a phase the order is deterministic:
 *
 * <ol>
 *   <li>a topological sort honours each transformer's {@code predepends} (a transformer that
 *       pre-depends on another always runs <em>after</em> it), then</li>
 *   <li>ties are broken by ascending {@code sortIndex}, then by registration order.</li>
 * </ol>
 *
 * <p>This merges Fabric's fixed pipeline with Forge's {@code @SortingIndex} + pre-depends coremod
 * ordering into one model. {@link TransformPhase#MIXIN} is terminal and may not be registered here;
 * the class delegate applies Mixin after this chain.
 *
 * <p>Registration is not thread-safe and is expected to happen once during bootstrap, before the
 * game class loader starts pulling classes. {@link #apply} is thread-safe after the chain is built.
 */
public final class TransformChain {
	private static final class Entry {
		final ClassTransformer transformer;
		final int sortIndex;
		final int registrationOrder;
		final String[] predepends;

		Entry(ClassTransformer transformer, int sortIndex, int registrationOrder, String[] predepends) {
			this.transformer = transformer;
			this.sortIndex = sortIndex;
			this.registrationOrder = registrationOrder;
			this.predepends = predepends;
		}
	}

	private final EnumMap<TransformPhase, List<Entry>> phases = new EnumMap<>(TransformPhase.class);
	private final EnumMap<TransformPhase, List<ClassTransformer>> sorted = new EnumMap<>(TransformPhase.class);
	private int registrationCounter;

	/** Registers a transformer with default ordering (sort index 0, no pre-depends). */
	public void register(TransformPhase phase, ClassTransformer transformer) {
		register(phase, transformer, 0);
	}

	/** Registers a transformer at the given sort index with no pre-depends. */
	public void register(TransformPhase phase, ClassTransformer transformer, int sortIndex) {
		register(phase, transformer, sortIndex, (String[]) null);
	}

	/**
	 * Registers a transformer.
	 *
	 * @param phase       the phase to run in; must not be {@link TransformPhase#MIXIN}
	 * @param transformer the transformer
	 * @param sortIndex   tie-breaking order within the phase (lower runs earlier)
	 * @param predepends  names of transformers in the same phase that must run before this one
	 */
	public void register(TransformPhase phase, ClassTransformer transformer, int sortIndex, String... predepends) {
		if (phase == null) throw new NullPointerException("phase");
		if (transformer == null) throw new NullPointerException("transformer");

		if (phase == TransformPhase.MIXIN) {
			throw new IllegalArgumentException("MIXIN is terminal and exclusive; it cannot be registered into the chain");
		}

		String[] deps = predepends == null ? new String[0] : predepends.clone();
		phases.computeIfAbsent(phase, p -> new ArrayList<>())
				.add(new Entry(transformer, sortIndex, registrationCounter++, deps));
		sorted.remove(phase); // invalidate cache for this phase
	}

	/** Runs the full pre-Mixin pipeline ({@code RAW_PATCH} .. {@code FABRIC_BUILTIN}). */
	public byte[] applyBeforeMixin(String className, byte[] classBytes, TransformContext context) {
		return apply(className, classBytes, context, TransformPhase.RAW_PATCH, TransformPhase.LAST_CHAIN_PHASE);
	}

	/**
	 * Runs every transformer in the phases {@code [fromInclusive, toInclusive]} (in phase order) against
	 * the class, threading the bytes through each transformer.
	 *
	 * @return the transformed bytes (the input array if nothing changed it)
	 */
	public byte[] apply(String className, byte[] classBytes, TransformContext context,
			TransformPhase fromInclusive, TransformPhase toInclusive) {
		if (toInclusive == TransformPhase.MIXIN) {
			throw new IllegalArgumentException("the chain does not run MIXIN; it is applied by the class delegate");
		}

		byte[] bytes = classBytes;

		TransformPhase[] all = TransformPhase.values();

		for (int i = fromInclusive.ordinal(); i <= toInclusive.ordinal(); i++) {
			for (ClassTransformer t : ordered(all[i])) {
				byte[] result = t.transform(className, bytes, context);
				if (result != null) bytes = result;
			}
		}

		return bytes;
	}

	/** The deterministically-ordered transformers for a phase (computed once, then cached). */
	public List<ClassTransformer> ordered(TransformPhase phase) {
		List<ClassTransformer> cached = sorted.get(phase);
		if (cached != null) return cached;

		List<Entry> entries = phases.get(phase);

		if (entries == null || entries.isEmpty()) {
			List<ClassTransformer> empty = Collections.emptyList();
			sorted.put(phase, empty);
			return empty;
		}

		List<ClassTransformer> order = topologicalSort(phase, entries);
		List<ClassTransformer> result = Collections.unmodifiableList(order);
		sorted.put(phase, result);
		return result;
	}

	private static List<ClassTransformer> topologicalSort(TransformPhase phase, List<Entry> entries) {
		// Map transformer name -> entry, for resolving predepends. Names should be unique within a phase.
		Map<String, Entry> byName = new HashMap<>();

		for (Entry e : entries) {
			byName.putIfAbsent(e.transformer.name(), e);
		}

		// Build edges: for an edge "dep -> e" (dep runs before e), e gains an in-degree and dep gains a successor.
		Map<Entry, Integer> inDegree = new HashMap<>();
		Map<Entry, List<Entry>> successors = new HashMap<>();

		for (Entry e : entries) {
			inDegree.putIfAbsent(e, 0);

			for (String depName : e.predepends) {
				Entry dep = byName.get(depName);
				if (dep == null || dep == e) continue; // unknown/self pre-depend is ignored

				successors.computeIfAbsent(dep, k -> new ArrayList<>()).add(e);
				inDegree.merge(e, 1, Integer::sum);
			}
		}

		// Kahn's algorithm; the ready set is ordered by (sortIndex, registrationOrder) for determinism.
		PriorityQueue<Entry> ready = new PriorityQueue<>((a, b) -> {
			if (a.sortIndex != b.sortIndex) return Integer.compare(a.sortIndex, b.sortIndex);
			return Integer.compare(a.registrationOrder, b.registrationOrder);
		});

		for (Entry e : entries) {
			if (inDegree.get(e) == 0) ready.add(e);
		}

		List<ClassTransformer> result = new ArrayList<>(entries.size());

		while (!ready.isEmpty()) {
			Entry e = ready.poll();
			result.add(e.transformer);

			for (Entry succ : successors.getOrDefault(e, Collections.emptyList())) {
				if (inDegree.merge(succ, -1, Integer::sum) == 0) {
					ready.add(succ);
				}
			}
		}

		if (result.size() != entries.size()) {
			throw new IllegalStateException("cyclic predepends among transformers in phase " + phase
					+ " (resolved " + result.size() + " of " + entries.size() + ")");
		}

		return result;
	}

	/** Number of transformers registered in a phase (test/diagnostics helper). */
	public int size(TransformPhase phase) {
		List<Entry> entries = phases.get(phase);
		return entries == null ? 0 : entries.size();
	}

	@Override
	public String toString() {
		List<String> parts = new ArrayList<>();

		for (TransformPhase phase : TransformPhase.values()) {
			int n = size(phase);
			if (n > 0) parts.add(phase + "=" + n);
		}

		return "TransformChain" + Arrays.toString(parts.toArray());
	}
}
