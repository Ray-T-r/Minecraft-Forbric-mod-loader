/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.ui;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.util.ForbricLog;

/** Chooses whether to proceed; this class never exits a JVM or interrupts a game thread. */
public final class CompatibilityDecision {
	public static final String PROPERTY = "forbric.compatibilityPolicy";
	public enum Policy { ASK, CONTINUE, STRICT }
	private static final Set<String> ACCEPTED = new LinkedHashSet<>();
	private static final Set<String> QUEUED = new LinkedHashSet<>();
	private static volatile boolean launchStopRequested;

	private CompatibilityDecision() { }

	/** Invalid values fail closed instead of silently disabling a required confirmation. */
	public static Policy policy() {
		return switch (System.getProperty(PROPERTY, "ask").toLowerCase(java.util.Locale.ROOT)) {
			case "ask" -> Policy.ASK;
			case "continue" -> Policy.CONTINUE;
			case "strict" -> Policy.STRICT;
			default -> Policy.STRICT;
		};
	}

	/** Boot integration: false means the caller must stop before entering the game. */
	public static boolean check(boolean isClient) {
		CompatibilityFindings.observeInitializationFailures();
		return decide(CompatibilityFindings.confirmedRequired(), isClient);
	}

	/** An explicit loading-boundary decision, separate from the best-effort report writer and mod callbacks. */
	public static void requireContinuation(boolean isClient) {
		if (check(isClient)) return;
		launchStopRequested = true;
		ForbricLog.error("[Forbric/Compatibility] launch stopped: required mod initialization or features are unavailable; continuation was not approved");
		throw new LaunchStopped();
	}

	/** Launch callers can distinguish a deliberate policy stop from a game/mod crash. */
	public static final class LaunchStopped extends IllegalStateException {
		private LaunchStopped() { super("Forbric compatibility policy stopped this launch; see .forbric-kernel/compatibility-report.json"); }
	}

	/** The game main may catch the typed stop before returning to the launcher. This is evidence, not cleanup. */
	public static boolean launchStopRequested() { return launchStopRequested; }

	/**
	 * A policy stop the game carries out through its own loop (a late strict refusal on the client) rather than by
	 * throwing. Recorded so the launcher boundary still reports it as the policy stop once the game main returns.
	 */
	public static void recordPolicyStop() { launchStopRequested = true; }

	public static boolean isLaunchStop(Throwable failure) {
		Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		while (failure != null && visited.add(failure)) {
			if (failure instanceof LaunchStopped) return true;
			if (failure instanceof java.lang.reflect.InvocationTargetException reflection) failure = reflection.getTargetException();
			else if (failure instanceof ExceptionInInitializerError initialization) failure = initialization.getException();
			else failure = failure.getCause();
		}
		return false;
	}

	/** Safe UI integration: call on the client UI thread, never from a transformer or server tick. */
	public static boolean decide(List<CompatibilityFinding> findings, boolean isClient) {
		return decide(findings, policy(), isClient && !java.awt.GraphicsEnvironment.isHeadless(), rows -> {
			try {
				return DependencyDialog.askCompatibility(rows, List.of());
			} catch (Exception failure) {
				if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
				ForbricLog.warn("[Forbric/Compatibility] confirmation unavailable; continuation was not approved", failure);
				return DependencyDialogMain.QUIT;
			}
		});
	}

	static boolean decide(List<CompatibilityFinding> findings, Policy policy, boolean display,
			Function<List<DependencyReport.CompatibilityRow>, Integer> ask) {
		List<CompatibilityFinding> required = findings.stream().filter(CompatibilityFinding::confirmedRequired).toList();
		if (required.isEmpty()) return true;
		// A release gate stays strict even if this process previously had an interactive approval.
		if (policy == Policy.STRICT) return false;
		if (policy == Policy.CONTINUE) {
			accept(required);
			return true;
		}
		List<CompatibilityFinding> unanswered;
		synchronized (CompatibilityDecision.class) {
			unanswered = required.stream().filter(f -> !ACCEPTED.contains(f.key())).toList();
		}
		if (unanswered.isEmpty()) return true;
		if (!display) return false;
		List<DependencyReport.CompatibilityRow> rows = unanswered.stream().map(f -> {
			String name = ModCatalog.everything().stream().filter(e -> e.modId().equals(f.modId()))
					.map(ModCatalog.Entry::name).findFirst().orElse(f.modId());
			return new DependencyReport.CompatibilityRow(f.modId(), name, f.feature(), f.detail(), f.source(),
					String.join("; ", f.evidence()));
		}).toList();
		Integer answer;
		try { answer = ask.apply(rows); }
		catch (RuntimeException failure) { return false; }
		if (answer == null || answer != DependencyDialogMain.CONTINUE) return false;
		accept(unanswered);
		return true;
	}

	private static synchronized void accept(List<CompatibilityFinding> findings) {
		for (CompatibilityFinding f : findings) ACCEPTED.add(f.key());
	}

	/** Called only by a real in-game Continue action; never clears evidence or a strict gate's verdict. */
	public static void acknowledge(List<CompatibilityFinding> findings) {
		accept(findings.stream().filter(CompatibilityFinding::confirmedRequired).toList());
	}

	/** Producers can queue late findings without drawing or blocking. The client drains at a safe boundary. */
	public static synchronized void queue() {
		for (CompatibilityFinding f : CompatibilityFindings.confirmedRequired()) {
			if (!ACCEPTED.contains(f.key())) QUEUED.add(f.key());
		}
	}

	public static synchronized List<CompatibilityFinding> drain() {
		List<CompatibilityFinding> ready = CompatibilityFindings.confirmedRequired().stream()
				.filter(f -> QUEUED.contains(f.key()) && !ACCEPTED.contains(f.key())).toList();
		QUEUED.clear();
		return ready;
	}

	public static synchronized void reset() {
		ACCEPTED.clear();
		QUEUED.clear();
		launchStopRequested = false;
	}
}
