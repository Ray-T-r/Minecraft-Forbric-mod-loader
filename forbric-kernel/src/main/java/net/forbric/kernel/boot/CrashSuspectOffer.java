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

package net.forbric.kernel.boot;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;

import net.forbric.kernel.ui.DependencyDialog;
import net.forbric.kernel.ui.DependencyDialogMain;
import net.forbric.kernel.ui.DependencyReport;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * On the launch after a crash, offers to start without the mods the crash pointed at.
 *
 * <p>{@link CrashAttribution} names the suspects in a file, and the file ends in "take it out of your mods folder
 * and start again" — a step done by hand, in a folder the player has to find, for a guess that may be wrong. This
 * turns it into one click that is undone by deleting a line: "Start without X, Y" appends the jars to
 * {@link DisabledMods#FILE} with a dated comment, and arbitration, which runs right after this, leaves them out.
 *
 * <p>Asked once per crash. Whatever the answer, {@code crash-suspects.json} becomes
 * {@code crash-suspects.offered.json}, and a pending file whose report the offered one already names is not asked
 * again — so a rename that failed cannot turn into a question on every launch. Only the dedicated server, a run
 * with no display and {@code -Dforbric.dependencyDialog=off} never ask; they log the lines instead and leave the
 * file pending, for a launch that can.
 *
 * <p>Fail-open, like the dependency notice: a closed window, an unreadable file or a child that cannot draw starts
 * the game with every mod, exactly as it would have started without this.
 */
public final class CrashSuspectOffer {
	static final String PENDING = CrashAttribution.JSON;
	static final String OFFERED = "crash-suspects.offered.json";

	/** The window, as a seam: a test answers it without a child process. Returns a {@link DependencyDialogMain} code. */
	@FunctionalInterface
	interface Dialog {
		int ask(DependencyReport.Isolation isolation) throws Exception;
	}

	private CrashSuspectOffer() {
	}

	/**
	 * Before arbitration, on either side.
	 *
	 * @return false only when the player chose Quit; the caller then ends the launch before anything loads
	 */
	public static boolean run(Path gameDir, boolean isClient) {
		return run(gameDir, DependencyDialog.noWindow(isClient), DependencyDialog::isolate, LocalDate.now());
	}

	/**
	 * @param noWindow why nothing can be shown, or null when the dialog may be asked
	 * @param today    the date the comment above the new lines carries
	 */
	static boolean run(Path gameDir, String noWindow, Dialog dialog, LocalDate today) {
		try {
			return offer(gameDir, noWindow, dialog, today);
		} catch (Throwable t) {
			// Nothing about a diagnostic from the last run may stop this one.
			Caught.warn(t, "[Forbric/Crash] could not offer to start without the last crash's suspects");
			return true;
		}
	}

	private static boolean offer(Path gameDir, String noWindow, Dialog dialog, LocalDate today) throws Exception {
		Path dir = gameDir.resolve(".forbric-kernel");
		Path pending = dir.resolve(PENDING);
		if (!Files.isRegularFile(pending)) return true;
		Pending crash = read(pending);
		if (crash == null) {
			ForbricLog.info("[Forbric/Crash] %s is not one this version can read — not offered", pending);
			return true;
		}
		Path offered = dir.resolve(OFFERED);
		if (Files.isRegularFile(offered)) {
			Pending earlier = read(offered);
			if (earlier != null && earlier.report().equals(crash.report())) {
				retire(pending, offered);
				return true;
			}
		}

		List<String> listed = alreadyListed(gameDir);
		List<DependencyReport.IsolationRow> without = new ArrayList<>();
		for (CrashAttribution.Suspect line : linesOf(crash)) {
			// Only a name the file can hold as one line, and so only a jar directly in mods/: the JSON is a file on
			// disk like any other, and "../x.jar" would otherwise resolve outside mods/ and be written as a line
			// that DisabledMods then skips as malformed.
			if (!DisabledMods.parse(List.of(line.jar())).equals(List.of(line.jar()))) continue;
			Path jar = gameDir.resolve("mods").resolve(line.jar());
			if (!Files.isRegularFile(jar) || listed.contains(line.jar())) continue;
			without.add(new DependencyReport.IsolationRow(line.modId(), line.name(), line.jar()));
		}
		if (without.isEmpty()) return true;
		List<String> jars = without.stream().map(DependencyReport.IsolationRow::jar).toList();

		if (noWindow != null) {
			ForbricLog.info("[Forbric/Crash] the last crash (%s) pointed at %s — %s, so nothing is asked; to start "
					+ "without them, add these lines to %s: %s", crash.report(), names(without), noWindow,
					DisabledMods.FILE, String.join(", ", jars));
			return true;
		}

		int answer;
		try {
			answer = dialog.ask(new DependencyReport.Isolation(crash.report(), crash.kept(), without));
		} catch (Exception failed) {
			if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
			Caught.warn(failed, "[Forbric/Crash] could not show the crash-suspects offer — starting with every mod");
			answer = DependencyDialogMain.CONTINUE;
		}
		retire(pending, offered);
		if (answer == DependencyDialogMain.WITHOUT) {
			try {
				DisabledMods.append(gameDir, jars, today + ": switched off after the crash in crash-reports/"
						+ crash.report() + " (Forbric asked; delete a line to turn that mod back on)");
				ForbricLog.info("[Forbric/Crash] the player chose to start without %s — added %s to %s", names(without),
						String.join(", ", jars), DisabledMods.FILE);
			} catch (Exception failed) {
				Caught.warn(failed, "[Forbric/Crash] could not write %s — starting with every mod", DisabledMods.FILE);
			}
			return true;
		}
		if (answer == DependencyDialogMain.QUIT) {
			ForbricLog.info("[Forbric/Crash] the player chose to quit at the crash-suspects offer");
			return false;
		}
		ForbricLog.info("[Forbric/Crash] the player chose to start with every mod despite the last crash (%s)", crash.report());
		return true;
	}

	/** What a pending file says, or null for one this version cannot read. */
	record Pending(String report, boolean clash, List<CrashAttribution.Suspect> suspects) {
		/** The clash side the analysis keeps, by name; empty when the crash named no clash. */
		String kept() {
			if (!clash) return "";
			for (CrashAttribution.Suspect s : suspects) if (CrashAttribution.CLASH.equals(s.reason())) return s.name();
			return "";
		}
	}

	static Pending read(Path file) {
		try {
			UnmodifiableConfig json = JsonFormat.fancyInstance().createParser()
					.parse(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
			if (!(json.get("schema") instanceof Number schema) || schema.intValue() != 1) return null;
			if (!(json.get("report") instanceof String report) || !(json.get("suspects") instanceof List<?> rows)) return null;
			List<CrashAttribution.Suspect> suspects = new ArrayList<>();
			for (Object row : rows) {
				if (!(row instanceof UnmodifiableConfig s)) return null;
				suspects.add(new CrashAttribution.Suspect(text(s, "modId"), text(s, "name"), "", text(s, "reason"),
						s.get("depth") instanceof Number depth ? depth.intValue() : 0, text(s, "jar")));
			}
			return new Pending(report, Boolean.TRUE.equals(json.get("clash")), List.copyOf(suspects));
		} catch (Exception unreadable) {
			Caught.warn(unreadable, "[Forbric/Crash] could not read %s", file);
			return null;
		}
	}

	private static String text(UnmodifiableConfig config, String key) {
		return config.get(key) instanceof String value ? value : "";
	}

	/**
	 * One suspect per jar the analysis would switch off, in its order. The jar list is
	 * {@link CrashAttribution#startWithout} — the same rule the analysis file prints — and each jar is named by the
	 * first suspect that brought it.
	 */
	private static List<CrashAttribution.Suspect> linesOf(Pending crash) {
		List<CrashAttribution.Suspect> out = new ArrayList<>();
		for (String jar : CrashAttribution.startWithout(crash.suspects())) {
			for (CrashAttribution.Suspect s : crash.suspects()) {
				if (s.jar().equals(jar)) { out.add(s); break; }
			}
		}
		return out;
	}

	private static List<String> alreadyListed(Path gameDir) {
		Path file = gameDir.resolve(DisabledMods.FILE);
		try {
			return Files.isRegularFile(file) ? DisabledMods.parse(Files.readAllLines(file, StandardCharsets.UTF_8)) : List.of();
		} catch (Exception unreadable) {
			Caught.warn(unreadable, "[Forbric/Crash] could not read %s; the suspects already in it are offered again",
					file);
			return List.of();
		}
	}

	private static String names(List<DependencyReport.IsolationRow> rows) {
		return String.join(", ", rows.stream().map(DependencyReport.IsolationRow::name).toList());
	}

	private static void retire(Path pending, Path offered) {
		try {
			Files.move(pending, offered, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception failed) {
			Caught.warn(failed, "[Forbric/Crash] could not rename %s; the same crash may be offered again next start",
					pending);
		}
	}
}
