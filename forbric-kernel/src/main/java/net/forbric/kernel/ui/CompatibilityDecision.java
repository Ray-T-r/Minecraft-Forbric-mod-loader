/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.ui;

import java.util.ArrayList;
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
		if (CompatibilityFindings.confirmedRequired().isEmpty()) {
			// Only the dependency notice can refuse without a required loss, and only by the player's own Quit.
			ForbricLog.error("[Forbric/Compatibility] launch stopped: the player chose to quit at the dependency notice");
		} else {
			ForbricLog.error("[Forbric/Compatibility] launch stopped: required mod initialization or features are unavailable; "
					+ "continuation was not approved (policy %s). Evidence: .forbric-kernel/compatibility-report.json. "
					+ "-D%s=continue launches anyway, for runs with nobody to ask", policy(), PROPERTY);
		}
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

	/**
	 * Safe UI integration: call on the client UI thread, never from a transformer or server tick.
	 *
	 * <p>The first decision of a launch also shows the dependency notice the audit held back, so the player sees
	 * ONE window: the fail-closed confirmation, with the notice folded in, when a required loss needs an answer;
	 * the old fail-open notice when nothing does; nothing at all under strict or without a display.
	 */
	public static boolean decide(List<CompatibilityFinding> findings, boolean isClient) {
		return decide(findings, policy(), isClient && !java.awt.GraphicsEnvironment.isHeadless(), isClient,
				DependencyDialog.takeHeld(), new Windows() {
					@Override public Integer confirm(DependencyReport.Confirmation confirmation) {
						try {
							return DependencyDialog.confirm(confirmation);
						} catch (Exception failure) {
							if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
							ForbricLog.warn("[Forbric/Compatibility] confirmation unavailable; continuation was not approved", failure);
							return DependencyDialogMain.QUIT;
						}
					}

					@Override public boolean notice(DependencyDialog.Notice notice, List<DependencyReport.CompatibilityRow> suspected) {
						return DependencyDialog.offer(notice.rows(), notice.mixins(), suspected, isClient);
					}

					@Override public void unshown(DependencyDialog.Notice notice, String why) {
						DependencyDialog.unshown(notice, isClient, why);
					}
				});
	}

	/** The windows a decision may open. An interface so a test can stand in for the child process. */
	interface Windows {
		/** The fail-closed confirmation; only {@link DependencyDialogMain#CONTINUE} approves. */
		Integer confirm(DependencyReport.Confirmation confirmation);

		/** The fail-open dependency notice; false only when the player chose to quit. */
		boolean notice(DependencyDialog.Notice notice, List<DependencyReport.CompatibilityRow> suspected);

		/** Nothing can be shown: say so, and where the findings are. */
		void unshown(DependencyDialog.Notice notice, String why);
	}

	/** The confirmation alone, as the tests that predate the folded notice drive it. */
	static boolean decide(List<CompatibilityFinding> findings, Policy policy, boolean display,
			Function<List<DependencyReport.CompatibilityRow>, Integer> ask) {
		return decide(findings, policy, display, display, DependencyDialog.Notice.EMPTY, new Windows() {
			@Override public Integer confirm(DependencyReport.Confirmation confirmation) { return ask.apply(confirmation.required()); }
			@Override public boolean notice(DependencyDialog.Notice notice, List<DependencyReport.CompatibilityRow> suspected) { return true; }
			@Override public void unshown(DependencyDialog.Notice notice, String why) { }
		});
	}

	static boolean decide(List<CompatibilityFinding> findings, Policy policy, boolean display, boolean isClient,
			DependencyDialog.Notice notice, Windows windows) {
		List<CompatibilityFinding> required = findings.stream().filter(CompatibilityFinding::confirmedRequired).toList();
		// Suspicions are only ever details. The ones the notice's mixin section already names are not listed twice.
		List<DependencyReport.CompatibilityRow> suspected = CompatibilityFindings.suspected().stream()
				.filter(f -> notice.mixins().stream().noneMatch(m -> sameMixin(f, m)))
				.map(CompatibilityDecision::row).toList();
		if (required.isEmpty()) return notice.isEmpty() || windows.notice(notice, suspected);
		// A release gate stays strict even if this process previously had an interactive approval. Strict never
		// asks, so the notice is not shown either: a window offering a choice the policy has already made would lie.
		if (policy == Policy.STRICT) {
			windows.unshown(notice, "strict compatibility policy");
			return false;
		}
		if (policy == Policy.CONTINUE) {
			accept(required);
			return notice.isEmpty() || windows.notice(notice, suspected);
		}
		List<CompatibilityFinding> unanswered;
		synchronized (CompatibilityDecision.class) {
			unanswered = required.stream().filter(f -> !ACCEPTED.contains(f.key())).toList();
		}
		if (unanswered.isEmpty()) return notice.isEmpty() || windows.notice(notice, suspected);
		if (!display) {
			windows.unshown(notice, "no display to ask on");
			return false;
		}
		List<DependencyReport.Row> open = new ArrayList<>();
		List<DependencyReport.Row> covered = new ArrayList<>();
		for (DependencyReport.Row row : notice.rows()) (asksAbout(unanswered, row) ? covered : open).add(row);
		Integer answer;
		try {
			answer = windows.confirm(new DependencyReport.Confirmation(unanswered.stream().map(CompatibilityDecision::row).toList(),
					suspected, open, covered, notice.mixins()));
		} catch (RuntimeException failure) {
			return false;
		}
		if (answer == null || answer != DependencyDialogMain.CONTINUE) return false;
		accept(unanswered);
		return true;
	}

	/**
	 * Whether an unmet requirement the audit reported is the same question as a required finding the candidate
	 * arbitration recorded for it ({@code arbitration:dependency:<id>} on the consumer). Asked once, not twice.
	 */
	static boolean asksAbout(List<CompatibilityFinding> findings, DependencyReport.Row row) {
		String id = "arbitration:dependency:" + row.requiredId();
		return findings.stream().anyMatch(f -> f.modId().equals(row.requiredBy()) && f.id().equalsIgnoreCase(id));
	}

	/** A mixin preflight row ({@code mixin:<config>:<class>}) for a break the notice's mixin section names. */
	private static boolean sameMixin(CompatibilityFinding finding, DependencyReport.MixinRow mixin) {
		return finding.id().startsWith("mixin:") && finding.id().endsWith(":" + mixin.mixin())
				&& (finding.modId().equals(mixin.owner()) || finding.modId().equals("config:" + mixin.owner()));
	}

	private static DependencyReport.CompatibilityRow row(CompatibilityFinding f) {
		String name = ModCatalog.everything().stream().filter(e -> e.modId().equals(f.modId()))
				.map(ModCatalog.Entry::name).findFirst().orElse(f.modId());
		return new DependencyReport.CompatibilityRow(f.modId(), name, f.feature(), f.detail(), f.source(),
				String.join("; ", f.evidence()));
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
		DependencyDialog.takeHeld();
	}
}
