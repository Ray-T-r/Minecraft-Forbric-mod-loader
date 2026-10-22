/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.util.concurrent.ConcurrentHashMap;

/**
 * One run of a method a carrier split into pieces the merged game calls one by one, as a guest's cancel at its start sees
 * it (MixinSplitBodyEdges): the first piece starts the run, a cancel there marks it, and each later piece asks whether the
 * run it belongs to was cancelled — the native method returned at that cancel, so none of its later work ran.
 *
 * <p>A later piece entered with an index not above the last one entered belongs to a new run, one whose first piece did not
 * run at all (a carrier's own event skipped that layer): such a run was not cancelled by anyone, and nothing is skipped.
 * Keyed per guest handler, so two mods' cancels do not mix.
 */
public final class SplitBodyRuns {
	private static final class Run {
		volatile int last = -1;
		volatile boolean cancelled;
	}

	private static final ConcurrentHashMap<String, Run> RUNS = new ConcurrentHashMap<>();

	private SplitBodyRuns() {
	}

	/** The first piece is entered: a new run, not cancelled. */
	public static void started(String key) {
		Run run = RUNS.computeIfAbsent(key, k -> new Run());
		run.cancelled = false;
		run.last = 0;
	}

	/** The guest's handler at the first piece cancelled: the rest of this run does not run. */
	public static void cancelled(String key) {
		RUNS.computeIfAbsent(key, k -> new Run()).cancelled = true;
	}

	/** Whether piece {@code index} (1 for the second) is to be skipped: it belongs to a run cancelled at its start. */
	public static boolean skips(String key, int index) {
		Run run = RUNS.computeIfAbsent(key, k -> new Run());
		if (index <= run.last) run.cancelled = false;    // a new run whose first piece never ran
		run.last = index;
		return run.cancelled;
	}

	/** Test seam. */
	static void reset() {
		RUNS.clear();
	}
}
