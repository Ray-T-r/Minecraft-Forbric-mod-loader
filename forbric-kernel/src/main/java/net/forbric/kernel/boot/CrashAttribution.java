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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.forbric.api.ModCatalog;
import net.forbric.kernel.util.ForbricLog;

/**
 * After a crash, says which mods it might be.
 *
 * <p>Minecraft writes a crash report and nothing in it answers the only question a player has. The Forge line
 * that would — {@code Suspected Mods:} — is produced by walking a {@code ModuleLayer} of {@code IModInfo}, which
 * this kernel deliberately does not have ({@code KernelForgeModContext}), so on a Forbric instance it says
 * {@code NONE} when it appears at all. Measured across every crash report in {@code run/}: not one names a mod.
 * Meanwhile the trace itself is full of the answer — the top frame of six of six crashes in {@code client-popular}
 * belongs to a mod jar by name.
 *
 * <h2>It reads the report; it does not catch the crash</h2>
 *
 * <p>Deliberately not an uncaught-exception handler. Minecraft catches its own fatal throwable, writes the report
 * and calls {@code System.exit} ({@code Minecraft.crash} is two instructions: save, then exit), so a handler of
 * ours would never see the crashes that matter. Running from a shutdown hook instead means the file is already
 * on disk, complete, and is the same evidence a player would paste into a bug report. It also costs nothing on
 * every boot that does not crash.
 *
 * <h2>It joins; it never parses a mod id out of a name</h2>
 *
 * <p>A jar file name is not a mod id — {@code xaeroworldmap-forge-26.2-1.46.1.jar} carries ids from the
 * {@code xaerominimap} family, and jars renamed to a content hash are real. So every name found in the trace is
 * looked up in {@link ModCatalog}, and a name no row carries contributes nothing. That also supplies the
 * deny-list for free: the merged base, the runtime carriers, the kernel's own jar and Minecraft's libraries are
 * not mods, so they are not in the catalogue, so they cannot be blamed.
 *
 * <p>Same rule for the other two forms. {@code MixinConfigOwners}' policy is the one this inherits: a
 * confidently wrong mod name is worse than no mod name.
 */
public final class CrashAttribution {
	/** {@code -Dforbric.crashAnalysis=off} — the crash report is written, and nothing of ours reads it. */
	public static final String SWITCH = "forbric.crashAnalysis";

	private static final String FILE = "crash-analysis.txt";

	/** How many mods the file names. Past a handful this stops being an answer and becomes a second list. */
	static final int MOST = 5;

	/**
	 * A frame's jar, as a stack trace prints it: {@code ~[supermartijn642corelib-1.1.24a-forge-mc26.2.jar:?]}.
	 *
	 * <p>The bracket is {@code Throwable}'s rendering of the class's {@code CodeSource}, which every mod class
	 * has here because {@code ForbricClassLoader.domainFor} gives it one. Measured: 0 unknown-jar frames across
	 * {@code client-popular}'s six crashes, against 1153 in the reports from before that method existed.
	 */
	private static final Pattern FRAME_JAR = Pattern.compile("\\[([^\\[\\]:]+\\.jar):[^\\[\\]]*\\]");

	/**
	 * A mixin handler running inside somebody else's class:
	 * {@code handler$chm000$sodium$loadConfig}, {@code redirect$dfp000$useStandardTemplateList}.
	 *
	 * <p>Mod code under a vanilla class name and a vanilla jar, which is the one way the jar bracket gets it
	 * exactly backwards. The token after the allocated prefix is Mixin's convention and not a guarantee — about
	 * half of the handler frames in {@code run/} carry a mod id there and half carry a method name — so it is
	 * used ONLY when it is an id the catalogue already has.
	 */
	private static final Pattern MIXIN_HANDLER = Pattern.compile("\\$[a-z]{3}\\d{3}\\$([A-Za-z0-9_\\-]+)\\$");

	/** Mixin's own words, in the exception message rather than in a frame: {@code from mod sodium}. */
	private static final Pattern FROM_MOD = Pattern.compile("from mod ([A-Za-z0-9_\\-]+)");

	private static volatile Path rundir;

	/**
	 * When this run started. A report older than this belongs to a different one.
	 *
	 * <p>A rundir accumulates crash reports, and nothing deletes them. Without this, every CLEAN quit would find
	 * last week's crash and announce "it might be one of these mods" about a session that ended fine — which is
	 * worse than saying nothing, because it teaches the player to ignore the file.
	 */
	private static volatile long startedAt;

	private CrashAttribution() {
	}

	/**
	 * One mod the crash report points at, and what pointed at it.
	 *
	 * @param depth how far down the trace it first appears; the ordering the answer is sorted by, because the
	 *              frame that threw is a better suspect than the frame that called it
	 */
	record Suspect(String modId, String name, String version, String reason, int depth) {
	}

	/** Where to look. Set from the boot, which is the only place that knows the instance directory. */
	public static void setRunDir(Path dir) {
		setRunDir(dir, System.currentTimeMillis());
		Runtime.getRuntime().addShutdownHook(new Thread(CrashAttribution::run, "forbric-crash-analysis"));
	}

	/** The same, with the start time explicit, so a test can own both sides of the comparison. */
	static void setRunDir(Path dir, long bootMillis) {
		rundir = dir;
		startedAt = bootMillis;
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/**
	 * Reads the crash report this run produced, if it produced one, and writes the answer beside it.
	 *
	 * <p>Wrapped whole. This runs while the JVM is going down after something has already gone wrong; a
	 * diagnostic that throws there would replace a crash report the player can read with a shutdown-hook stack
	 * trace they cannot.
	 */
	static void run() {
		Path dir = rundir;
		if (dir == null || !enabled()) return;
		try {
			Path report = crashReportFromThisRun(dir.resolve("crash-reports"), startedAt);
			if (report == null) return;
			List<Suspect> suspects = suspects(Files.readString(report, StandardCharsets.UTF_8));
			String rendered = render(chinese(), report.getFileName().toString(), suspects);

			Path out = dir.resolve(".forbric-kernel").resolve(FILE);
			Files.createDirectories(out.getParent());
			Files.writeString(out, rendered, StandardCharsets.UTF_8);

			// Printed as well as written. A launcher shows the tail of stdout when the game dies, and that is
			// where a player is already looking; the file is for the person they send it to.
			System.out.println();
			System.out.print(rendered);
			ForbricLog.warn("[Forbric/Crash] %s — analysis in .forbric-kernel/%s",
					suspects.isEmpty() ? "no mod could be named from the crash report" : summary(suspects), FILE);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Crash] could not analyse the crash report: %s", String.valueOf(t));
		}
	}

	private static String summary(List<Suspect> suspects) {
		List<String> ids = new ArrayList<>();
		for (Suspect s : suspects) ids.add(s.modId());
		return "the crash points at " + String.join(", ", ids);
	}

	/**
	 * The report THIS run wrote, or null.
	 *
	 * <p>Newest by modification time, and only if it is newer than the boot. The second half is the whole
	 * correctness of this: a rundir keeps every crash report it has ever produced, so "the newest one" is an
	 * answer even when this session ended cleanly.
	 */
	static Path crashReportFromThisRun(Path dir, long bootMillis) throws IOException {
		if (!Files.isDirectory(dir)) return null;
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".txt"))
					.filter(p -> modifiedAt(p).toEpochMilli() >= bootMillis)
					.max(Comparator.comparing(CrashAttribution::modifiedAt))
					.orElse(null);
		}
	}

	private static java.time.Instant modifiedAt(Path file) {
		try {
			return Files.getLastModifiedTime(file).toInstant();
		} catch (IOException unreadable) {
			return java.time.Instant.EPOCH;
		}
	}

	/**
	 * The mods a crash report points at, most likely first.
	 *
	 * <p>Only the exception chain at the top is read, not the whole file. Everything below the walkthrough
	 * divider is Minecraft's own system report — thread dumps, the mod lists, the graphics card — and a mod
	 * named there is named for being installed, not for being involved.
	 */
	static List<Suspect> suspects(String crashReport) {
		String trace = exceptionChain(crashReport);
		Map<String, Suspect> byId = new LinkedHashMap<>();

		int depth = 0;
		for (String line : trace.split("\n")) {
			depth++;
			// The handler form FIRST: such a frame carries the vanilla jar, so reading its bracket would
			// blame Minecraft for a mod's mixin.
			Matcher handler = MIXIN_HANDLER.matcher(line);
			while (handler.find()) {
				remember(byId, handler.group(1), "its mixin was running", depth);
			}
			Matcher fromMod = FROM_MOD.matcher(line);
			while (fromMod.find()) {
				remember(byId, fromMod.group(1), "Mixin named it", depth);
			}
			Matcher jar = FRAME_JAR.matcher(line);
			while (jar.find()) {
				for (ModCatalog.Entry entry : ModCatalog.everything()) {
					if (entry.jar().equals(jar.group(1))) {
						remember(byId, entry.modId(), "its code is in the crash", depth);
					}
				}
			}
		}
		List<Suspect> found = new ArrayList<>(byId.values());
		found.sort(Comparator.comparingInt(Suspect::depth));
		return found.size() > MOST ? found.subList(0, MOST) : found;
	}

	/**
	 * Records a suspect, if the catalogue has it and nothing shallower already did.
	 *
	 * <p>The id has to be one a row carries. A jar file name is not a mod id, the token in a mixin handler's
	 * name is a convention rather than a guarantee, and a name nothing recognises is a guess — which is the one
	 * thing an answer like this cannot afford to be.
	 */
	private static void remember(Map<String, Suspect> byId, String modId, String reason, int depth) {
		if (modId == null || modId.isBlank() || byId.containsKey(modId)) return;
		for (ModCatalog.Entry entry : ModCatalog.everything()) {
			if (entry.modId().equalsIgnoreCase(modId)) {
				byId.put(entry.modId(), new Suspect(entry.modId(), entry.name(), entry.version(), reason, depth));
				return;
			}
		}
	}

	/**
	 * The exception and its causes, which is everything above Minecraft's walkthrough divider.
	 *
	 * <p>The divider is a run of hyphens the report writes once, before the per-section detail. Absent — a
	 * truncated file, a format that moved — the whole text is read, which over-reports rather than under-reports
	 * and is the safer direction for a file whose only job is to point somewhere.
	 */
	static String exceptionChain(String crashReport) {
		int divider = crashReport.indexOf("\n------------");
		return divider < 0 ? crashReport : crashReport.substring(0, divider);
	}

	private static boolean chinese() {
		return "zh".equalsIgnoreCase(Locale.getDefault().getLanguage());
	}

	/** Package-private so both renderings can be asserted without a locale dance. */
	static String render(boolean zh, String reportName, List<Suspect> suspects) {
		StringBuilder sb = new StringBuilder();
		if (zh) {
			sb.append("Forbric 崩溃分析\n");
			sb.append("=================\n\n");
			if (suspects.isEmpty()) {
				sb.append("这次崩溃里没有出现任何一个你装的 mod，所以说不准是哪个 mod 的问题 —— 也可能不是 mod 的问题。\n");
				sb.append("完整的报错在 crash-reports/").append(reportName).append(" 里。\n");
				return sb.toString();
			}
			sb.append(suspects.size() == 1 ? "可能是这个 mod 的问题：\n\n" : "可能是这几个 mod 的问题，越靠前越可能：\n\n");
			for (Suspect s : suspects) {
				sb.append("  ").append(s.name());
				if (!s.version().isEmpty()) sb.append(' ').append(s.version());
				sb.append("  (").append(s.modId()).append(")\n");
				sb.append("    ").append(zhReason(s.reason())).append("\n\n");
			}
			sb.append("怎么办\n");
			sb.append("------\n");
			sb.append("先把最上面那个 mod 从 mods 文件夹里拿出来再开一次。还是崩就换下一个。\n");
			sb.append("这只是个猜测：它说的是这些 mod 出现在了报错里，不是说它们一定有毛病。\n");
			sb.append("完整的报错在 crash-reports/").append(reportName).append(" 里。\n");
			return sb.toString();
		}
		sb.append("Forbric crash analysis\n");
		sb.append("======================\n\n");
		if (suspects.isEmpty()) {
			sb.append("No mod you installed appears in this crash, so there is nothing to point at — it may not\n");
			sb.append("be a mod at all. The full error is in crash-reports/").append(reportName).append(".\n");
			return sb.toString();
		}
		sb.append(suspects.size() == 1 ? "This mod might be the one:\n\n"
				: "It might be one of these, likeliest first:\n\n");
		for (Suspect s : suspects) {
			sb.append("  ").append(s.name());
			if (!s.version().isEmpty()) sb.append(' ').append(s.version());
			sb.append("  (").append(s.modId()).append(")\n");
			sb.append("    ").append(s.reason()).append("\n\n");
		}
		sb.append("What to do\n");
		sb.append("----------\n");
		sb.append("Take the first one out of your mods folder and start again. If it still crashes, try the next.\n");
		sb.append("This is a guess: it says these mods were in the error, not that they are at fault.\n");
		sb.append("The full error is in crash-reports/").append(reportName).append(".\n");
		return sb.toString();
	}

	private static String zhReason(String reason) {
		return switch (reason) {
			case "its mixin was running" -> "它改过的代码正在运行";
			case "Mixin named it" -> "报错里直接点了它的名字";
			default -> "报错里有它的代码";
		};
	}
}
