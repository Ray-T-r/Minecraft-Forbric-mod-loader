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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.api.ModCatalog;
import net.forbric.kernel.util.ForbricLog;

/**
 * Tells a player which mods did not finish loading, in a file they can find afterwards.
 *
 * <p>Until now the only record of a mod failing was one WARN line somewhere in a ten-thousand-line log. The
 * README's own advice for "the game crashes when it starts" is to remove half your mods and try again — a manual
 * binary search, offered because nothing else was on offer.
 *
 * <p>Forbric loads as much as it can rather than stopping at the first problem, which is a deliberate trade and
 * not in question here. The gap that follows from it is this one: a mod that fails after the pre-launch
 * dependency dialog has been shown produces no user-visible trace at all. This closes that, and only that.
 *
 * <p>Written next to {@code merge-report.txt} and in the same idiom: the system language, best-effort, and never
 * able to fail the boot it reports on. A shutdown hook covers the boot that dies before loading completes, which
 * is exactly the boot whose reader needs this file most.
 */
public final class KernelLoadReport {
	private static final String FILE = "load-report.txt";

	private static volatile Path rundir;
	/** {@code -Dforbric.loadReportRewrite=off}: the first write wins and later failures never reach the file. */
	public static final String REWRITE_PROPERTY = "forbric.loadReportRewrite";

	/** What the file last said (null: nothing written yet); a render equal to it is not written again. */
	private static volatile String lastRendered;
	/** Whether anything was ever reported — the "every mod finished loading" line is said once, and only then. */
	private static final AtomicBoolean reported = new AtomicBoolean();
	private static final java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();

	private KernelLoadReport() {
	}

	private static boolean chinese() {
		return "zh".equalsIgnoreCase(Locale.getDefault().getLanguage());
	}

	/** Where to write. Set from the boot, which is the only place that knows the instance directory. */
	public static void setRunDir(Path dir) {
		rundir = dir;
		// The boot that never reaches "loading finished" is the one a player most needs this for.
		Runtime.getRuntime().addShutdownHook(new Thread(KernelLoadReport::write, "forbric-load-report"));
	}

	/**
	 * Writes the report whenever what it would say has changed.
	 *
	 * <p>Called at the end of loading on both sides, again once the server (integrated or dedicated) is up —
	 * a mixin that fails to apply to a class first loaded at world creation is only known then — and from the
	 * shutdown hook. A render equal to the last one written is not written again and says nothing, so a clean
	 * boot writes no file and says one INFO line — a file that appears only when something is wrong is a file
	 * whose presence already means something. With {@link #REWRITE_PROPERTY} off, the first write wins.
	 */
	public static void write() {
		Path dir = rundir;
		writeTo(dir == null ? null : dir.resolve(".forbric-kernel").resolve(FILE));
	}

	/** The write with its destination explicit (null: log only), so a test can watch a file it owns. */
	static void writeTo(Path file) {
		try {
			// Attributions held back until the mod's own mixin config plugin could be asked. Settled here rather
			// than where the suppression was decided, because the plugin does not exist yet at that point — and
			// settled before failures() is read, so the first report is already the corrected one.
			net.forbric.kernel.mixin.PluginDeclinedMixins.resolve();
			List<ModCatalog.Entry> failures = ModCatalog.failures();
			if (failures.isEmpty()) {
				if (reported.compareAndSet(false, true)) ForbricLog.info("[Forbric/Load] every mod finished loading");
				return;
			}
			String rendered = render(chinese(), failures);
			synchronized (KernelLoadReport.class) {
				if (rendered.equals(lastRendered)) return;
				if (lastRendered != null && !rewriteEnabled()) return;
				List<String> ids = new ArrayList<>();
				for (ModCatalog.Entry e : failures) ids.add(e.modId());
				ForbricLog.warn("[Forbric/Load] %d mod(s) did not finish loading: %s — details in .forbric-kernel/%s",
						failures.size(), String.join(", ", ids), FILE);
				reported.set(true);
				lastRendered = rendered;
				if (file == null) return;
				Files.createDirectories(file.getParent());
				Files.writeString(file, rendered, StandardCharsets.UTF_8);
				writes.incrementAndGet();
			}
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Load] could not write the load report: %s", String.valueOf(t));
		}
	}

	static boolean rewriteEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(REWRITE_PROPERTY, "on"));
	}

	/** How many times the file was written; a test seam. */
	static int writes() {
		return writes.get();
	}

	/** Forgets what was written, so a test starts from a fresh boot's state. */
	static void reset() {
		synchronized (KernelLoadReport.class) {
			lastRendered = null;
			reported.set(false);
			writes.set(0);
		}
	}

	/** Package-private so both renderings can be asserted without a locale dance. */
	static String render(boolean zh, List<ModCatalog.Entry> failures) {
		StringBuilder sb = new StringBuilder();
		if (zh) {
			sb.append("Forbric 加载报告\n");
			sb.append("=================\n\n");
			sb.append("这一次启动，有 ").append(failures.size()).append(" 个 mod 没有完成加载。\n\n");
		} else {
			sb.append("Forbric load report\n");
			sb.append("===================\n\n");
			sb.append(failures.size()).append(" mod(s) did not finish loading this time.\n\n");
		}

		for (ModCatalog.Entry e : failures) {
			sb.append("  ").append(e.name());
			if (!e.modId().equals(e.name())) sb.append("  (").append(e.modId()).append(')');
			sb.append('\n');
			if (!e.jar().isEmpty()) sb.append("    ").append(e.jar()).append('\n');
			String what = e.status() == ModCatalog.Status.FAILED
					? (zh ? "没有完成加载" : "did not finish loading")
					: (zh ? "有一部分没有跑起来" : "partly did not run");
			sb.append("    ").append(what);
			if (!e.statusDetail().isEmpty()) sb.append(" — ").append(e.statusDetail());
			sb.append('\n');
			// Who else declared they need this one. When the entry above is a library -- and in this project's
			// history most of them are -- the player is not looking at the library, they are looking at the mods
			// that quietly stopped doing anything, and nothing named those anywhere.
			List<String> dependents = Dependents.of(e.modId());
			if (!dependents.isEmpty()) {
				sb.append("    ").append(zh ? "还有 " : "");
				sb.append(zh
						? dependents.size() + " 个 mod 说它们需要这个：" + String.join("、", dependents)
						: dependents.size() + " other mod(s) require this one: " + String.join(", ", dependents));
				sb.append('\n');
			}
			sb.append('\n');
		}

		if (zh) {
			sb.append("怎么办\n");
			sb.append("------\n");
			sb.append("先在 logs/latest.log 里搜上面的 mod 名字，那里有具体的报错。\n");
			sb.append("常见原因是这个 mod 是给别的 Minecraft 版本做的，或者它需要的另一个 mod 没装。\n");
			sb.append("把它从 mods 文件夹里拿出来，游戏的其余部分照常能玩。\n\n");
			sb.append("说明\n");
			sb.append("----\n");
			sb.append("Forbric 不会因为一个 mod 出问题就停下来，它会把能装的都装上。所以上面这些 mod\n");
			sb.append("其实还有一部分留在游戏里（它们的类已经加载了），只是没有走完自己的初始化。\n");
			sb.append("这不是崩溃报告 —— 游戏是起来了的。\n");
		} else {
			sb.append("What to do\n");
			sb.append("----------\n");
			sb.append("Search logs/latest.log for the names above; the actual error is there.\n");
			sb.append("The usual reasons are that the mod was built for a different Minecraft version, or that\n");
			sb.append("something it needs is not installed. Taking it out of the mods folder leaves the rest of\n");
			sb.append("the game working.\n\n");
			sb.append("Note\n");
			sb.append("----\n");
			sb.append("Forbric does not stop at the first mod that goes wrong; it loads everything it can. So the\n");
			sb.append("mods above are still partly present — their classes did load — they just did not finish\n");
			sb.append("initialising. This is not a crash report; the game did start.\n");
		}
		return sb.toString();
	}
}
