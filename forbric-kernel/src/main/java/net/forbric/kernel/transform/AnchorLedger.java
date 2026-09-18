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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.kernel.transform.AnchorSet.Anchor;
import net.forbric.kernel.transform.AnchorSet.Severity;
import net.forbric.kernel.util.ForbricLog;

/**
 * Keeps the books on {@link AnchorSet} declarations: for each declared anchor, was the class ever handed to the
 * transformer, and did the transformer ever change it.
 *
 * <h2>Two findings, and only one of them is unambiguous</h2>
 *
 * <p>{@link Miss} — the class WAS loaded and the transformer declined it — is a defect every time. Nothing is
 * being guessed at: the bytes went past and the repair did not apply. It is reported the moment it happens,
 * at ERROR, once, and needs no end-of-boot checkpoint to be trustworthy.
 *
 * <p>{@link Absent} — the class was never loaded — means nothing on its own. A client-only target on a dedicated
 * server is absent and correct; a {@code HEDGE} anchor is absent and correct by design. It is collected for the
 * summary and never raised on its own. Keeping the two in separate fields is the whole of the "do not cry wolf"
 * design; there is no switch layered on top to quiet it down.
 *
 * <h2>Why booleans and not counters</h2>
 *
 * <p>The chain runs over each class twice — once for Mixin's pre-weave view of it and once when it is defined
 * (see {@code ForbricClassLoader}) — and whether the first of those happens at all depends on whether any mixin
 * config targets the class. A count would therefore differ between two correct runs. "Seen" and "edited" do not.
 */
public final class AnchorLedger {

	/** A declared anchor whose class was loaded and which the transformer did not edit. Always a defect. */
	public record Miss(String transformer, String className, Severity severity, String cost) {
	}

	/** A declared anchor whose class was never loaded. On its own this means nothing. */
	public record Absent(String transformer, String className, Severity severity) {
	}

	/**
	 * What the books say.
	 *
	 * <p>Three buckets rather than two, because "seen and declined" means opposite things at opposite
	 * severities. At REQUIRED or FATAL it is a defect. At HEDGE it is the expected answer: those targets are
	 * carried precisely so that a carrier which ever grows the method is noticed, and on today's carriers they
	 * are supposed to match nothing. Filing a hedge as a defect turns the summary into a line the reader learns
	 * to skip, which is the failure mode this whole mechanism is trying to avoid.
	 *
	 * @param declared how many anchors were declared in total
	 * @param hit      how many were seen and edited
	 * @param misses   seen and declined at REQUIRED or FATAL — defects
	 * @param hedged   seen and declined at HEDGE — the expected answer, kept visible but not a defect
	 * @param absent   never seen — context, not a defect
	 */
	public record Report(int declared, int hit, List<Miss> misses, List<Miss> hedged, List<Absent> absent) {
		public Report {
			misses = List.copyOf(misses);
			hedged = List.copyOf(hedged);
			absent = List.copyOf(absent);
		}

		/** True when no declared anchor was handed its class and refused it at a severity that matters. */
		public boolean clean() {
			return misses.isEmpty();
		}
	}

	private static final class Row {
		final String transformer;
		final Anchor anchor;
		volatile boolean seen;
		volatile boolean edited;
		volatile boolean reported;

		Row(String transformer, Anchor anchor) {
			this.transformer = transformer;
			this.anchor = anchor;
		}
	}

	/** key = transformer name + '\0' + class binary name; insertion-ordered so the report reads in declaration order. */
	private final Map<String, Row> rows = new ConcurrentHashMap<>();
	private final List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());

	/** Registers one declared anchor. Called once per transformer per anchor while the chain is being built. */
	public void declare(String transformerName, Anchor anchor) {
		String key = transformerName + '\0' + anchor.binaryName();
		if (rows.putIfAbsent(key, new Row(transformerName, anchor)) == null) order.add(key);
	}

	/**
	 * Records one pass of {@code className} through {@code transformerName}.
	 *
	 * <p>{@code edited} is the array-identity answer the chain already has: the transformer handed back bytes
	 * that are not the ones it was given. A transformer that declines a class it declared is reported here and
	 * now, because at that instant the question has no other reading.
	 */
	public void record(String transformerName, String className, boolean edited) {
		Row row = rows.get(transformerName + '\0' + className);
		if (row == null) return;

		row.seen = true;
		if (edited) {
			row.edited = true;
			return;
		}
		if (row.edited || row.reported) return;

		// Only REQUIRED and FATAL are worth a line on their own. A HEDGE that declines is the expected case and
		// is reported, if at all, by the summary.
		if (row.anchor.severity() == Severity.HEDGE) return;

		row.reported = true;
		ForbricLog.error("[Forbric/Anchor] %s was handed %s and made no edit — its anchor is gone. %s",
				transformerName, className, row.anchor.cost());
	}

	/** Everything the books know, for the end-of-boot summary and for tests. */
	public Report report() {
		List<Miss> misses = new ArrayList<>();
		List<Miss> hedged = new ArrayList<>();
		List<Absent> absent = new ArrayList<>();
		int hit = 0;

		Map<String, Row> snapshot = new LinkedHashMap<>();
		synchronized (order) {
			for (String key : order) {
				Row row = rows.get(key);
				if (row != null) snapshot.put(key, row);
			}
		}

		for (Row row : snapshot.values()) {
			if (row.edited) {
				hit++;
			} else if (row.seen) {
				Miss miss = new Miss(row.transformer, row.anchor.binaryName(), row.anchor.severity(),
						row.anchor.cost());
				(row.anchor.severity() == Severity.HEDGE ? hedged : misses).add(miss);
			} else {
				absent.add(new Absent(row.transformer, row.anchor.binaryName(), row.anchor.severity()));
			}
		}

		Comparator<Miss> byName = Comparator.comparing(Miss::transformer).thenComparing(Miss::className);
		misses.sort(byName);
		hedged.sort(byName);
		absent.sort(Comparator.comparing(Absent::transformer).thenComparing(Absent::className));
		return new Report(snapshot.size(), hit, misses, hedged, absent);
	}

	/** The class names any transformer has declared, so the chain can watch only those. */
	public java.util.Set<String> watchedClasses() {
		java.util.Set<String> names = new java.util.LinkedHashSet<>();
		for (Row row : rows.values()) names.add(row.anchor.binaryName());
		return names;
	}
}
