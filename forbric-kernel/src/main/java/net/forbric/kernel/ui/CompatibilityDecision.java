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
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/** Chooses whether to proceed; this class never exits a JVM or interrupts a game thread. */
public final class CompatibilityDecision {
	public static final String PROPERTY = "forbric.compatibilityPolicy";
	public enum Policy { ASK, CONTINUE, STRICT }
	private static final Set<String> ACCEPTED = new LinkedHashSet<>();
	private static final Set<String> QUEUED = new LinkedHashSet<>();
	/**
	 * Required losses the launch could not ask about in a window, handed to the game's own prompt. Not accepted: the
	 * game asks before anything else, and a refusal there stops it, as Quit in the window would have.
	 */
	private static final Set<String> IN_GAME = new LinkedHashSet<>();
	/** The client's arguments that open a world as soon as the game has loaded, e.g. {@code --quickPlaySingleplayer}. */
	private static final Set<String> QUICK_PLAY = Set.of("--quickPlaySingleplayer", "--quickPlayMultiplayer", "--quickPlayRealms");
	/** The argument of this launch that opens a world straight away, or null. */
	private static volatile String opensAWorldAtOnce;
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

	/**
	 * Remembers whether the client was told to open a world as soon as it has loaded. Then there is no moment to ask in
	 * the game before the world is loaded and saved -- {@code Minecraft.doWorldLoad} writes level.dat and runs its own
	 * loop without the tick the prompt hangs on -- so a question no window could ask is not moved there.
	 */
	public static void noteGameArguments(String[] args) {
		String found = null;
		if (args != null) {
			for (String arg : args) {
				if (arg != null && QUICK_PLAY.contains(arg.split("=", 2)[0])) { found = arg.split("=", 2)[0]; break; }
			}
		}
		opensAWorldAtOnce = found;
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
		// The window is a separate process: what matters is whether THAT process can draw. A macOS client keeps AWT
		// headless in the game's own JVM on purpose (MacAwtBootstrap) and the forked window still draws; reading the
		// game's flag here refused every macOS launch with a required loss without ever asking.
		boolean display = isClient && (!java.awt.GraphicsEnvironment.isHeadless()
				|| net.forbric.kernel.boot.MacAwtBootstrap.usesHeadlessFonts());
		return decide(findings, policy(), display, isClient,
				DependencyDialog.takeHeld(), new Windows() {
					@Override public Integer confirm(DependencyReport.Confirmation confirmation) {
						try {
							return DependencyDialog.confirm(confirmation);
						} catch (Exception failure) {
							// What DependencyDialog could not even attempt it reports as UNSHOWN; what is left here
							// is the confirmation breaking part-way, which approves nothing.
							if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
							Caught.warn(failure, "[Forbric/Compatibility] confirmation unavailable; continuation was not approved");
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
		/**
		 * The fail-closed confirmation; only {@link DependencyDialogMain#CONTINUE} approves, and
		 * {@link DependencyDialogMain#UNSHOWN} says nobody could be asked in a window.
		 */
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
		if (required.isEmpty()) return notice.isEmpty() || windows.notice(notice, suspected(notice));
		// A release gate stays strict even if this process previously had an interactive approval. Strict never
		// asks, so the notice is not shown either: a window offering a choice the policy has already made would lie.
		if (policy == Policy.STRICT) {
			windows.unshown(notice, "strict compatibility policy");
			return false;
		}
		if (policy == Policy.CONTINUE) {
			accept(required);
			return notice.isEmpty() || windows.notice(notice, suspected(notice));
		}
		List<CompatibilityFinding> unanswered;
		boolean allInGame;
		synchronized (CompatibilityDecision.class) {
			unanswered = required.stream().filter(f -> !ACCEPTED.contains(f.key())).toList();
			allInGame = unanswered.stream().allMatch(f -> IN_GAME.contains(f.key()));
		}
		if (unanswered.isEmpty()) return notice.isEmpty() || windows.notice(notice, suspected(notice));
		// Already handed to the game: it asks once it is up, and a later boundary must not stop it first.
		if (isClient && allInGame) return true;
		if (!display) {
			// The switch is an explicit "no window", for runs with nobody to ask: it fails closed here as in the window.
			if (isClient && !DependencyDialog.switchedOff()) {
				return askInGame(unanswered, notice, windows, "no window can be drawn beside the game");
			}
			windows.unshown(notice, isClient ? "-D" + DependencyDialog.SWITCH + "=off" : "no display to ask on");
			return false;
		}
		List<DependencyReport.Row> open = new ArrayList<>();
		List<DependencyReport.Row> covered = new ArrayList<>();
		for (DependencyReport.Row row : notice.rows()) (asksAbout(unanswered, row) ? covered : open).add(row);
		Integer answer;
		try {
			answer = windows.confirm(new DependencyReport.Confirmation(unanswered.stream().map(CompatibilityDecision::row).toList(),
					suspected(notice), open, covered, notice.mixins()));
		} catch (RuntimeException failure) {
			Caught.warn(failure, "[Forbric/Compatibility] the confirmation broke part-way; continuation was not approved");
			return false;
		}
		if (answer != null && answer == DependencyDialogMain.UNSHOWN && isClient) {
			return askInGame(unanswered, notice, windows, "the confirmation window could not be shown");
		}
		if (answer == null || answer != DependencyDialogMain.CONTINUE) return false;
		accept(unanswered);
		return true;
	}

	/**
	 * Hands {@code unanswered} to the game's own prompt, when a client cannot show the window that asks.
	 *
	 * <p>The question still has to be answered; only where it is asked changes. The game draws its windows itself, so
	 * the prompt reaches a player the forked window cannot: on Android launchers such as FCL the window's process cannot
	 * even start. Nothing is accepted here. {@code KernelCompatibilityPrompts} asks at the first game tick with no
	 * loading screen up, which is the title screen, before the player can open a world; Continue there accepts, and
	 * Quit -- or closing it -- stops the game, as Quit in the window would have. Reading "could not ask" as "the player
	 * said no" stopped every such launch while showing the player nothing but a line at the end of the log.
	 *
	 * <p>Not when the launcher asked for a world straight away ({@link #noteGameArguments}): that world would be loaded
	 * and saved before the game's first tick could ask, so the launch is not approved, as before, and the log says why.
	 */
	private static boolean askInGame(List<CompatibilityFinding> unanswered, DependencyDialog.Notice notice,
			Windows windows, String why) {
		windows.unshown(notice, why);
		String quickPlay = opensAWorldAtOnce;
		if (quickPlay != null) {
			ForbricLog.error("[Forbric/Compatibility] %s, and the launcher asked to open a world straight away (%s): the game "
					+ "would load and save it before its own window could ask, so continuation was not approved. Launch "
					+ "without %s to be asked on the title screen", why, quickPlay, quickPlay);
			return false;
		}
		synchronized (CompatibilityDecision.class) {
			for (CompatibilityFinding f : unanswered) IN_GAME.add(f.key());
		}
		ForbricLog.warn("[Forbric/Compatibility] %s; %d required feature loss(es) will be asked about in the game's own "
				+ "window on the title screen, and nothing is approved until the player answers there", why,
				unanswered.size());
		return true;
	}

	/** Whether the launch handed {@code finding} to the game to ask about, so that refusing it stops the game. */
	public static synchronized boolean askedInGameForTheLaunch(CompatibilityFinding finding) {
		return IN_GAME.contains(finding.key());
	}

	/**
	 * Suspicions for a window's details, built only when a window opens. The ones the notice's mixin section already
	 * names are not listed twice.
	 */
	private static List<DependencyReport.CompatibilityRow> suspected(DependencyDialog.Notice notice) {
		return CompatibilityFindings.suspected().stream()
				.filter(f -> notice.mixins().stream().noneMatch(m -> sameMixin(f, m)))
				.map(CompatibilityDecision::row).toList();
	}

	/**
	 * Whether an unmet requirement the audit reported is the same question as a required finding the candidate
	 * arbitration recorded for it ({@code arbitration:dependency:<id>} on the consumer). Asked once, not twice.
	 */
	static boolean asksAbout(List<CompatibilityFinding> findings, DependencyReport.Row row) {
		String id = "arbitration:dependency:" + row.requiredId();
		return findings.stream().anyMatch(f -> f.modId().equals(row.requiredBy()) && f.id().equalsIgnoreCase(id));
	}

	/**
	 * A mixin preflight row for a break the notice's mixin section names. The finding's id is
	 * {@code mixin:<config>:<package>.<mixin>}, while a break names the mixin as its config lists it, relative to
	 * that package -- so the class is matched at a {@code .} boundary as well as a {@code :} one, within one owner.
	 */
	private static boolean sameMixin(CompatibilityFinding finding, DependencyReport.MixinRow mixin) {
		String id = finding.id();
		return id.startsWith("mixin:") && (id.endsWith(":" + mixin.mixin()) || id.endsWith("." + mixin.mixin()))
				&& (finding.modId().equals(mixin.owner()) || finding.modId().equals("config:" + mixin.owner()));
	}

	private static DependencyReport.CompatibilityRow row(CompatibilityFinding f) {
		String name = ModCatalog.everything().stream().filter(e -> e.modId().equals(f.modId()))
				.map(ModCatalog.Entry::name).findFirst().orElse(f.modId());
		return new DependencyReport.CompatibilityRow(f.modId(), name, f.feature(), f.detail(), f.source(),
				String.join("; ", f.evidence()));
	}

	private static synchronized void accept(List<CompatibilityFinding> findings) {
		for (CompatibilityFinding f : findings) {
			ACCEPTED.add(f.key());
			IN_GAME.remove(f.key());
		}
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
		IN_GAME.clear();
		opensAWorldAtOnce = null;
		launchStopRequested = false;
		DependencyDialog.takeHeld();
		DependencyDialog.forgetUnavailable();
	}
}
