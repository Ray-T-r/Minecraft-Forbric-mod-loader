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

package net.forbric.kernel.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.util.ForbricLog;

/**
 * Puts an unmet hard dependency in front of the player, instead of only in the log.
 *
 * <p>The log line was always there. It is one WARN in a ten-thousand-line file, and the failure it predicts
 * arrives much later wearing someone else's name: a pack whose BiomesOPlenty was missing TerraBlender logged
 * that requirement at boot, then died forty seconds afterwards on "Failed to load registries due to errors" —
 * 455 unknown block ids, an error naming neither mod. A player cannot be expected to connect those.
 *
 * <p>See {@link DependencyDialogMain} for why the dialog is a separate process on every platform.
 *
 * <h2>What it may and may not do</h2>
 *
 * <p>{@code DependencyAudit} states the policy this belongs to: it reports, it never refuses, because one bad
 * declaration must not cost a player every other mod. A dialog does not change that — the kernel still loads
 * everything, and "Launch anyway" is what happens on every path that is not a deliberate click on Quit: a
 * closed window, a dialog that fails to open, no display, a child that crashes, a timeout. The one case where
 * the boot stops is the player choosing to stop it, which is not the kernel refusing on their behalf.
 *
 * <p>Silent on a dedicated server and in any headless run. A server blocked on a dialog nobody can see is
 * strictly worse than the log line it replaces, and the gates run servers unattended.
 *
 * <h2>One window, not two</h2>
 *
 * <p>The audit no longer opens its window itself: it {@link #hold holds} the notice, and the launch's compatibility
 * decision shows it. When nothing needs a decision, that is the notice above, unchanged. When a confirmed required
 * loss does, the notice is folded into the {@link #confirm confirmation} -- the same child, the same layout -- whose
 * contract is the opposite one: only an explicit click on continue approves, and a closed window, a timeout, no
 * display or {@code -Dforbric.dependencyDialog=off} is a launch that was not approved. Either way the player is asked
 * once, and suspected findings only ever reach the details.
 */
public final class DependencyDialog {
	/**
	 * {@code -Dforbric.dependencyDialog=} {@code on} (default) | {@code off} | {@code dryRun}.
	 *
	 * <ul>
	 *   <li>{@code off} — the warning stays in the log. What {@code run/launch-kernel-client.sh} passes unless
	 *       told otherwise, so no gate and no developer run can ever block on a window. A confirmed required loss
	 *       still needs an explicit continue, and with no window there is none: under the ask policy that launch
	 *       is not approved rather than approved by default.</li>
	 *   <li>{@code dryRun} — fork the real child, with AWT disabled inside it. Every part of the path runs: the
	 *       report is written, the child JVM starts, reads it, finds it cannot draw, and exits — CONTINUE for the
	 *       notice, QUIT for a confirmation, because neither contract may change for want of a display. It
	 *       exists so a gate can assert on the machinery rather than on a mock of it. Nothing is drawn and
	 *       nobody has to click, which is the only way a dialog is testable unattended.</li>
	 *   <li>{@code on} — the real thing.</li>
	 * </ul>
	 *
	 * <p>The default is ON because the player who needs this is the one who never passes a flag. The gates and
	 * the dev launcher opt out explicitly rather than relying on the guards below: a gate hung on an invisible
	 * window is the worst failure mode in this repo, and "three guards make it impossible" is not the same
	 * thing as "it cannot happen".
	 */
	static final String SWITCH = "forbric.dependencyDialog";

	/** @see #SWITCH */
	private static final String DRY_RUN = "dryRun";

	/**
	 * How long to wait for the player. Generous, because reading it is the point — but not unbounded: a child
	 * that somehow renders nothing must not hold the launch forever.
	 */
	private static final long TIMEOUT_MINUTES = 10;

	private DependencyDialog() {
	}

	/**
	 * The unmet requirements and mixin breaks the audit found, held for the launch's one decision.
	 *
	 * @param rows   unmet requirements
	 * @param mixins mixins that were written to attach to another mod and did not
	 */
	public record Notice(List<DependencyReport.Row> rows, List<DependencyReport.MixinRow> mixins) {
		public static final Notice EMPTY = new Notice(List.of(), List.of());

		public Notice {
			rows = rows == null ? List.of() : List.copyOf(rows);
			mixins = mixins == null ? List.of() : List.copyOf(mixins);
		}

		public boolean isEmpty() {
			return rows.isEmpty() && mixins.isEmpty();
		}

		public int size() {
			return rows.size() + mixins.size();
		}
	}

	private static Notice held = Notice.EMPTY;

	/** What the audit found, shown by the launch decision rather than in a window of its own. Replaces, never adds. */
	public static synchronized void hold(List<DependencyReport.Row> rows, List<DependencyReport.MixinRow> mixins) {
		held = new Notice(rows, mixins);
	}

	/** The held notice, handed over once: the first decision shows it and no later one can show it again. */
	static synchronized Notice takeHeld() {
		Notice taken = held;
		held = Notice.EMPTY;
		return taken;
	}

	/**
	 * Says where a notice went when no window can show it.
	 *
	 * @param why the reason on a client; a server always says it is not the client, which is the guard gates name
	 */
	static void unshown(Notice notice, boolean isClient, String why) {
		if (notice.isEmpty()) return;
		if (!isClient) {
			ForbricLog.debug("[Forbric/Deps] not the client — the %d finding(s) stay in the log", notice.size());
			return;
		}
		ForbricLog.info("[Forbric/Deps] %s — %d finding(s) reported in the log only", why, notice.size());
	}

	/**
	 * Shows the dialog if this run is one that can have a player in front of it, and quits if they say so.
	 *
	 * @param rows     the unmet requirements; nothing happens when empty
	 * @param isClient whether this is the physical client. The caller knows the side; this class must not guess
	 *                 it, because guessing wrong in the silent direction costs a warning and guessing wrong in
	 *                 the loud direction hangs a server
	 */
	public static boolean offer(List<DependencyReport.Row> rows, boolean isClient) {
		return offer(rows, List.of(), isClient);
	}

	/**
	 * @param mixins mixins that were written to attach to another mod and did not. A different problem from an
	 *               unmet dependency and reported separately, because no dependency check can see it: both mods
	 *               are installed and each is inside the range the other declares
	 * @return false only when the player chose to quit; the caller turns that into the launch's typed stop rather
	 *         than this class exiting the JVM from the middle of a boot
	 */
	public static boolean offer(List<DependencyReport.Row> rows, List<DependencyReport.MixinRow> mixins,
			boolean isClient) {
		return offer(rows, mixins, List.of(), isClient);
	}

	/** @param suspected notes for the details pane. They never open a window by themselves. */
	static boolean offer(List<DependencyReport.Row> rows, List<DependencyReport.MixinRow> mixins,
			List<DependencyReport.CompatibilityRow> suspected, boolean isClient) {
		if (rows == null) rows = List.of();
		if (mixins == null) mixins = List.of();
		if (suspected == null) suspected = List.of();
		if (rows.isEmpty() && mixins.isEmpty()) return true;
		int findings = rows.size() + mixins.size();
		if (!isClient) {
			ForbricLog.debug("[Forbric/Deps] not the client — the %d finding(s) stay in the log", findings);
			return true;
		}
		String mode = System.getProperty(SWITCH, "on");
		if ("off".equalsIgnoreCase(mode)) {
			ForbricLog.info("[Forbric/Deps] -D%s=off — %d finding(s) reported in the log only",
					SWITCH, findings);
			return true;
		}
		boolean dryRun = DRY_RUN.equalsIgnoreCase(mode);
		if (java.awt.GraphicsEnvironment.isHeadless()) {
			// Measured safe to ask: on a JVM started with -XstartOnFirstThread this returns in ~12ms and starts
			// no AWT thread, so the guard cannot be the thing that breaks the window it is guarding.
			ForbricLog.info("[Forbric/Deps] headless — %d finding(s) reported in the log only", findings);
			return true;
		}

		int answer;
		try {
			// The dry run differs ONLY in the child's flags, so what a gate exercises is this method, this fork
			// and this exit code — not a stand-in for them.
			answer = ask(rows, mixins, suspected, dryRun ? List.of("-Djava.awt.headless=true") : List.of());
			if (dryRun) {
				ForbricLog.info("[Forbric/Deps] -D%s=dryRun — forked the dialog for %d finding(s) with "
						+ "no display; it answered %d (launch anyway) without drawing anything",
						SWITCH, findings, answer);
			}
		} catch (Throwable failed) {
			ForbricLog.warn("[Forbric/Deps] could not show the unmet-dependency dialog — the warnings above are "
					+ "the whole of it; launching anyway", failed);
			return true;
		}
		if (answer == DependencyDialogMain.QUIT) {
			ForbricLog.warn("[Forbric/Deps] the player chose to quit rather than launch with %d finding(s). "
					+ "This is their decision, not the kernel refusing — -D%s=off launches without asking.",
					findings, SWITCH);
			return false;
		}
		ForbricLog.info("[Forbric/Deps] launching anyway with %d finding(s), at the player's choice", findings);
		return true;
	}

	/**
	 * The one window when a confirmed required loss needs an answer, with the held notice folded in.
	 *
	 * <p>Fail-closed where {@link #offer} is fail-open, and the switch keeps its meaning of "no window": with it off
	 * nothing is asked, so nothing is approved. The dry run forks the real child with no display, which cannot
	 * approve either -- so a gate sees the whole path and the refusal it must end in.
	 *
	 * @return {@link DependencyDialogMain#CONTINUE} only for an explicit click on continue
	 */
	static int confirm(DependencyReport.Confirmation confirmation) throws Exception {
		int findings = confirmation.findings();
		String mode = System.getProperty(SWITCH, "on");
		if ("off".equalsIgnoreCase(mode)) {
			ForbricLog.warn("[Forbric/Deps] -D%s=off — %d finding(s) reported in the log only; a required loss "
					+ "needs an explicit continue, so continuation was not approved", SWITCH, findings);
			return DependencyDialogMain.QUIT;
		}
		boolean dryRun = DRY_RUN.equalsIgnoreCase(mode);
		int answer = askConfirmation(confirmation, dryRun ? List.of("-Djava.awt.headless=true") : List.of());
		if (dryRun) {
			ForbricLog.info("[Forbric/Deps] -D%s=dryRun — forked the dialog for %d finding(s) with no display; it "
					+ "answered %d (%s) without drawing anything", SWITCH, findings, answer,
					answer == DependencyDialogMain.CONTINUE ? "continue" : "not approved");
		}
		return answer;
	}

	static int ask(List<DependencyReport.Row> rows) throws Exception {
		return ask(rows, List.of(), List.of());
	}

	static int ask(List<DependencyReport.Row> rows, List<String> extraJvmArgs) throws Exception {
		return ask(rows, List.of(), extraJvmArgs);
	}

	static int ask(List<DependencyReport.Row> rows, List<DependencyReport.MixinRow> mixins,
			List<String> extraJvmArgs) throws Exception {
		return ask(rows, mixins, List.of(), extraJvmArgs);
	}

	/**
	 * Runs the child and reads its exit code.
	 *
	 * @param extraJvmArgs additional flags for the child JVM. Package-visible and exists so a test can drive
	 *                     THIS method — the real fork, the real child, the real exit code — with
	 *                     {@code -Djava.awt.headless=true} instead of a copy of it that proves nothing
	 */
	static int ask(List<DependencyReport.Row> rows, List<DependencyReport.MixinRow> mixins,
			List<DependencyReport.CompatibilityRow> suspected, List<String> extraJvmArgs) throws Exception {
		Path report = Files.createTempFile("forbric-deps", ".tsv");
		try {
			DependencyReport.write(report, rows, mixins, suspected);
			return fork(report, extraJvmArgs, false);
		} finally {
			Files.deleteIfExists(report);
		}
	}

	/** The same child process and layout, with a separate, fail-closed confirmation contract. */
	static int askCompatibility(List<DependencyReport.CompatibilityRow> rows, List<String> extraJvmArgs) throws Exception {
		return askConfirmation(new DependencyReport.Confirmation(rows, List.of(), List.of(), List.of(), List.of()),
				extraJvmArgs);
	}

	static int askConfirmation(DependencyReport.Confirmation confirmation, List<String> extraJvmArgs) throws Exception {
		Path report = Files.createTempFile("forbric-compatibility", ".tsv");
		try {
			DependencyReport.writeConfirmation(report, confirmation);
			return fork(report, extraJvmArgs, true);
		} finally {
			Files.deleteIfExists(report);
		}
	}

	private static int fork(Path report, List<String> extraJvmArgs, boolean confirmation) throws Exception {
		try {
			List<String> command = new ArrayList<>();
			command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
			command.addAll(extraJvmArgs);
			// A child JVM inherits its parent's environment, and so its OS locale, but NOT its -D flags. The
			// common case therefore needs nothing forwarded at all; a deliberate -Dforbric.dialogLanguage does,
			// or the switch would be unreachable from the one process the player actually reads.
			String language = System.getProperty(DialogLang.SWITCH);
			if (language != null && !language.isBlank()) {
				command.add("-D" + DialogLang.SWITCH + "=" + language);
			}
			command.add("-cp");
			command.add(ownJar());
			command.add(DependencyDialogMain.class.getName());
			command.add(report.toString());
			if (confirmation) command.add("--compatibility");

			Process child = new ProcessBuilder(command)
					.redirectOutput(ProcessBuilder.Redirect.INHERIT)
					.redirectError(ProcessBuilder.Redirect.INHERIT)
					.start();
			// A forked process does not die with its parent. If the kernel goes down while the dialog is open --
			// crash, kill, the player quitting the launcher -- the window would otherwise sit on their desktop
			// belonging to nothing. Seen for real: children of a test JVM outlived it during development.
			Thread reaper = new Thread(child::destroyForcibly, "forbric-deps-dialog-reaper");
			Runtime.getRuntime().addShutdownHook(reaper);
			try {
				return await(child, confirmation);
			} finally {
				if (child.isAlive()) child.destroyForcibly();
				try {
					Runtime.getRuntime().removeShutdownHook(reaper);
				} catch (IllegalStateException alreadyShuttingDown) {
					// Removing a hook during shutdown is not allowed and not needed -- it is about to run.
				}
			}
		} finally {
			try {
				Files.deleteIfExists(report);
			} catch (Throwable ignored) {
				// A leftover temp file is not worth a second failure on the way out of a warning.
			}
		}
	}

	private static int await(Process child, boolean confirmation) throws InterruptedException {
		if (!child.waitFor(TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES)) {
			child.destroy();
			ForbricLog.warn("[Forbric/Deps] the dialog did not answer within %d minutes — %s", TIMEOUT_MINUTES,
					confirmation ? "continuation was not approved" : "launching anyway");
			return confirmation ? DependencyDialogMain.QUIT : DependencyDialogMain.CONTINUE;
		}
		return confirmation && child.exitValue() != DependencyDialogMain.CONTINUE
				? DependencyDialogMain.QUIT : child.exitValue();
	}

	/**
	 * The jar this class was loaded from, which is the only classpath the child needs.
	 *
	 * <p>Resolved from the code source rather than from {@code java.class.path}: the kernel is launched with a
	 * classpath holding the whole game, and handing a child JVM all of it to show a dialog would make the child's
	 * startup depend on everything the game depends on.
	 */
	private static String ownJar() throws Exception {
		return Path.of(DependencyDialog.class.getProtectionDomain().getCodeSource().getLocation().toURI())
				.toString();
	}
}
