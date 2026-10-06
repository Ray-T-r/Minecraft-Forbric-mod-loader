/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Applies a proved collection-source replacement while preserving the native getter's entire suffix. */
public final class KernelCollectionSources {
	private KernelCollectionSources() { }

	/** The caller has proved a pure collection-stream/concat pipeline, beginning with a copy of original.
	 * Snapshot original before the callback, then obtain the native stream so callback changes to suffix sources
	 * are visible. A changed native prefix is an error, never a guess. */
	public static <T> Stream<T> replacePrefix(Supplier<? extends Stream<T>> nativeSource, List<T> original,
			Supplier<? extends List<T>> replacement, String context) {
		if (nativeSource == null || original == null || replacement == null)
			throw new IllegalStateException("Collection source adaptation " + context + ": null source or callback");
		Object[] expected = original.toArray();
		List<T> changed = replacement.get();
		if (changed == null)
			throw new IllegalStateException("Collection source adaptation " + context + ": replacement returned null");
		Stream<T> nativeStream = nativeSource.get();
		if (nativeStream == null)
			throw new IllegalStateException("Collection source adaptation " + context + ": native source returned null");
		List<T> nativeValues;
		try (nativeStream) { nativeValues = nativeStream.toList(); }
		if (nativeValues.size() < expected.length)
			throw new IllegalStateException("Collection source adaptation " + context + ": native stream has "
					+ nativeValues.size() + " entries, fewer than the original prefix of " + expected.length);
		for (int i = 0; i < expected.length; i++) if (nativeValues.get(i) != expected[i])
			throw new IllegalStateException("Collection source adaptation " + context
					+ ": original prefix identity mismatch at index " + i + " (expected prefix length " + expected.length + ")");
		List<T> result = new ArrayList<>(changed.size() + nativeValues.size() - expected.length);
		result.addAll(changed);
		result.addAll(nativeValues.subList(expected.length, nativeValues.size()));
		return result.stream();
	}
}
