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

import java.nio.file.Path;
import java.util.List;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;

/**
 * The dialog, as a separate process's {@code main}.
 *
 * <p><b>Why a separate process.</b> On macOS the game JVM is started with {@code -XstartOnFirstThread} (see
 * {@code run/launch-kernel-client.sh}), which GLFW requires and which AWT cannot coexist with: both want thread
 * one.
 *
 * <p>Fabric Loader forks there too, and it is worth being exact about what it keys off, because the obvious
 * reading is wrong. With Minecraft's game provider registered it never inspects the environment at all:
 * {@code MinecraftGameProvider.hasAwtSupport()} is {@code !LoaderUtil.hasMacOs()} and nothing more
 * ({@code invokestatic hasMacOs / ifne 10 / iconst_1 ... iconst_0 / ireturn}), so the decision is
 * {@code os.name} alone. Its scan for an environment key beginning {@code JAVA_STARTED_ON_FIRST_THREAD_} lives
 * in {@code LoaderUtil.hasAwtSupport()}, which {@code FabricGuiEntry.open} reaches only while no provider has
 * been established yet. That environment variable is real — measured here, a JVM started with the flag has
 * {@code JAVA_STARTED_ON_FIRST_THREAD_<pid>} and one started without it does not — it is simply not the trigger
 * on the path that matters. The technique is Fabric's either way, read out of the bytecode rather than copied.
 *
 * <p>Forbric forks ALWAYS, on every platform, rather than only where it must. A dialog costs a JVM start only on
 * the boots that have something to report, which are rare, and in exchange there is ONE code path instead of two.
 * The alternative — in-process where it is safe, forked where it is not — is the shape that has to be right on
 * three operating systems and is only ever exercised on whichever one the author had.
 *
 * <p>The one AWT class the game's own process touches is {@code GraphicsEnvironment.isHeadless()}, in
 * {@link DependencyDialog}'s guard. Measured on a JVM started with {@code -XstartOnFirstThread}: it returns in
 * about 12ms and starts no {@code AWT-} thread, so the guard cannot be the thing that breaks the window it
 * guards. That is a measurement of that one call, not a claim that AWT is never loaded.
 *
 * <p>Exit code IS the answer: {@code 0} continue, {@code 1} quit. Anything else the parent reads as continue,
 * because a dialog that fails must not be able to stop a launch that would otherwise have worked.
 */
public final class DependencyDialogMain {
	/** The player chose to launch anyway. */
	public static final int CONTINUE = 0;
	/** The player chose to quit and go install something. */
	public static final int QUIT = 1;

	private DependencyDialogMain() {
	}

	/**
	 * Takes Java2D off the Direct3D pipeline on Windows before anything is drawn.
	 *
	 * <p>Measured, not guessed: on the machine that reported this, the Windows look and feel hands Swing an
	 * ordinary palette — panel 240/240/240, text area white, text black — and this dialog sets no colour of its
	 * own, yet it painted itself yellow with blue and red text. Nothing computed those colours; they were painted
	 * wrong, which is a rendering-pipeline fault rather than a theming one.
	 *
	 * <p>The installer already carries this exact workaround, for the same symptom in the other window, so this is
	 * a known hazard on this platform rather than a hunch. Only when the caller has no opinion, and only on
	 * Windows, so it never overrides a deliberate {@code -Dsun.java2d.d3d}.
	 *
	 * <p>The cost is software rendering for one modal warning, which nothing animates.
	 */
	private static void avoidOverlayRenderingCorruption() {
		if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return;
		if (System.getProperty("sun.java2d.d3d") != null) return;
		System.setProperty("sun.java2d.d3d", "false");
	}

	public static void main(String[] args) {
		avoidOverlayRenderingCorruption();
		if (args.length < 1) System.exit(CONTINUE);
		List<DependencyReport.Row> rows;
		List<DependencyReport.MixinRow> mixins;
		try {
			rows = DependencyReport.read(Path.of(args[0]));
			mixins = DependencyReport.readMixins(Path.of(args[0]));
		} catch (Throwable unreadable) {
			System.exit(CONTINUE);
			return;
		}
		if (rows.isEmpty() && mixins.isEmpty()) System.exit(CONTINUE);

		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Throwable ignored) {
			// The cross-platform look and feel is not worth failing a warning over.
		}

		JTextArea body = new JTextArea(describe(rows) + describeMixins(mixins));
		body.setEditable(false);
		body.setLineWrap(false);
		body.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
		JScrollPane scroll = new JScrollPane(body);
		scroll.setPreferredSize(new java.awt.Dimension(760,
				Math.min(120 + (rows.size() + mixins.size()) * 52, 500)));

		int answer;
		try {
			answer = JOptionPane.showOptionDialog(null, scroll,
					title(rows, mixins),
					JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
					// "Launch anyway" is the INITIAL value, so it is the one the keyboard default triggers.
					// The policy this dialog belongs to is that continuing is what happens unless the player
					// deliberately chooses otherwise; a player holding Enter must not lose the launch.
					new String[] { "Launch anyway", "Quit" }, "Launch anyway");
		} catch (Throwable noDisplay) {
			System.exit(CONTINUE);
			return;
		}
		// Closing the window is not an answer, so it means the same as the safest button that is not destructive:
		// launching is what would have happened without this dialog at all.
		System.exit(answer == 1 ? QUIT : CONTINUE);
	}

	/**
	 * The window title, which has to match what is actually in the dialog.
	 *
	 * <p>A fixed "a mod is missing something it requires" is a lie on a run where the only finding is a mixin
	 * that did not attach: both mods are installed and neither is missing anything. A player who reads the title
	 * and stops there would go looking for a download that does not exist.
	 */
	static String title(List<DependencyReport.Row> rows, List<DependencyReport.MixinRow> mixins) {
		if (rows.isEmpty()) return "Forbric — two mods do not fit each other";
		if (mixins.isEmpty()) return "Forbric — a mod is missing something it requires";
		return "Forbric — some mods are missing requirements, and some do not fit each other";
	}

	/**
	 * The text of the dialog.
	 *
	 * <p>It says which mod wants what, which ECOSYSTEM that mod belongs to — a player looking for the download
	 * has to know whether to fetch the Fabric build or the Forge one, and on a merged instance that is not
	 * guessable from the pack — and, for a version mismatch, what is actually installed. It also says what the
	 * kernel will do next, because a warning that does not say what happens if you ignore it invites the reader
	 * to assume the worst.
	 */
	static String describe(List<DependencyReport.Row> rows) {
		if (rows.isEmpty()) return "";
		StringBuilder text = new StringBuilder();
		text.append(rows.size() == 1 ? "One mod is missing something it requires:\n\n"
				: rows.size() + " mods are missing something they require:\n\n");
		for (DependencyReport.Row row : rows) {
			text.append("  • ").append(row.requiredByName()).append("  (").append(row.requiredBy())
					.append(", ").append(row.ecosystem()).append(")\n");
			if (row.absent()) {
				text.append("      needs ").append(row.requiredId()).append(' ').append(row.requiredRange())
						.append("  —  NOT INSTALLED\n");
			} else {
				text.append("      needs ").append(row.requiredId()).append(' ').append(row.requiredRange())
						.append("  —  installed: ").append(row.installedVersion()).append('\n');
			}
		}
		text.append("\nForbric will launch anyway if you ask it to. A mod whose requirement is unmet usually\n")
				.append("fails somewhere that names neither mod — an empty world, a missing block, or a crash\n")
				.append("during world creation — so this is worth fixing before you play.\n");
		return text.toString();
	}

	/**
	 * The second kind of problem, and the one nothing else can report.
	 *
	 * <p>Both mods are installed and each is inside the version range the other declares, so every dependency
	 * check — Forbric's and stock Fabric's alike — says this pack is fine. What does not fit is the bytecode: a
	 * mixin written to attach to the other mod found nothing to attach to. It is worded as "did not attach"
	 * rather than "will crash", because an unresolved anchor is all the kernel actually knows.
	 */
	static String describeMixins(List<DependencyReport.MixinRow> mixins) {
		if (mixins.isEmpty()) return "";
		StringBuilder text = new StringBuilder();
		text.append(mixins.size() == 1
				? "\nA mod could not attach to another mod it was built for:\n\n"
				: "\n" + mixins.size() + " mods could not attach to other mods they were built for:\n\n");
		for (DependencyReport.MixinRow row : mixins) {
			text.append("  • ").append(row.owner()).append("  —  ").append(row.mixin()).append('\n');
			text.append("      could not find ").append(row.anchors()).append('\n');
		}
		text.append("\nBoth mods ARE installed, and each is inside the version range the other asks for — so\n")
				.append("nothing reports this as a missing dependency, because it is not one. The two builds\n")
				.append("simply do not fit. One of them needs a different version.\n");
		return text.toString();
	}
}
