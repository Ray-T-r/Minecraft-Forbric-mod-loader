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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.UnifiedDependency;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.fabric.KernelModMetadata;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.forbric.kernel.util.ForbricLog;

/**
 * Explains, to somebody who has never read this code, what happened when two modpacks were merged into one
 * instance: which mods existed twice, which copy is running, who depends on them, and the one line to change if
 * something looks missing.
 *
 * <p>The kernel's arbitration is correct but invisible — a player who merges a Fabric pack and a NeoForge pack sees
 * only that one copy of Sodium is running and has no way to know that was a decision, let alone how to revisit it.
 * A log line scrolls past; a file in the instance folder does not.
 *
 * <p><b>Language follows the system.</b> The report and its one-line log summary are written in Chinese when the
 * default locale's language is Chinese, English otherwise. Only this user-facing text is translated — code,
 * comments and logs elsewhere stay English, matching the rest of the tree.
 *
 * <p>Written to {@code <rundir>/.forbric-kernel/merge-report.txt} on every boot that has duplicates. Entirely
 * best-effort, following {@code KernelLifecycle.logRegisteredContent}'s rule: a diagnostic must never be able to
 * fail the window it reports on.
 */
public final class MergeReport {
	private static final String FILE = "merge-report.txt";

	private MergeReport() {
	}

	private static boolean chinese() {
		return "zh".equalsIgnoreCase(Locale.getDefault().getLanguage());
	}

	/** The commented header of {@code forbric-mods.txt}, in the system language. */
	static List<String> overrideTemplateHeader() {
		if (chinese()) {
			return List.of(
					"# 这个整合包里有些 mod 装了两份(Fabric 版和 NeoForge 版各一份)。",
					"# 每个 mod 只会启用一份 —— 两份同时跑会互相覆盖类、重复施加 mixin。",
					"#",
					"# 下面列出了所有这样的 mod,以及内核当前选用的那一份。",
					"# 想换成另一边,把那一行前面的 # 去掉,并把等号后面改成想要的加载器。",
					"#   可填:fabric / neoforge / minecraftforge",
					"#",
					"# 详细说明(谁依赖谁、出问题怎么办)见 .forbric-kernel/" + FILE,
					"");
		}
		return List.of(
				"# Some mods in this instance are installed twice — once built for Fabric, once for NeoForge.",
				"# Only one copy of each can run: two would shadow each other's classes and apply their mixins twice.",
				"#",
				"# Every such mod is listed below with the copy the kernel chose.",
				"# To use the other one, remove the leading # and set the loader you want.",
				"#   values: fabric / neoforge / minecraftforge",
				"#",
				"# The full explanation (who depends on what, what to do if something is missing) is in",
				"# .forbric-kernel/" + FILE,
				"");
	}

	/**
	 * Writes the report. No-op when nothing was duplicated — an instance with one modpack has nothing to explain.
	 *
	 * @param rundir  the instance directory ({@code mods/}'s parent)
	 * @param modsDir scanned again here rather than threaded through arbitration: this is a diagnostic, and
	 *                re-reading ~100 manifests costs milliseconds while keeping it decoupled from the decision path
	 */
	public static void write(Path rundir, Path modsDir, DuplicateModArbiter.Decision decision) {
		if (rundir == null || decision.ownerByModId().isEmpty()) return;
		try {
			Map<String, List<Entry>> byId = scan(modsDir);
			String text = render(decision, byId);

			Path dir = rundir.resolve(".forbric-kernel");
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(FILE), text, StandardCharsets.UTF_8);

			ForbricLog.info(chinese()
					? "[Forbric/DupeId] %d 个 mod 装了两份,已各选一份;说明见 %s,改用另一份见 %s"
					: "[Forbric/DupeId] %d mod(s) were installed twice and one copy of each is running; see %s, "
							+ "and %s to switch",
					decision.ownerByModId().size(), dir.resolve(FILE), rundir.resolve(DuplicateModArbiter.OVERRIDE_FILE));
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/DupeId] could not write the merge report: %s", String.valueOf(t));
		}
	}

	/** One jar's declaration of one mod id. */
	private record Entry(Path jar, Ecosystem ecosystem, Set<String> dependsOn) {
	}

	private static String render(DuplicateModArbiter.Decision decision, Map<String, List<Entry>> byId) {
		boolean zh = chinese();
		StringBuilder b = new StringBuilder();
		b.append(zh ? "Forbric 合并报告\n" : "Forbric merge report\n");
		b.append("=".repeat(60)).append("\n\n");
		b.append(zh
				? "这个实例里有 " + decision.ownerByModId().size() + " 个 mod 装了两份。每个只会跑一份。\n"
						+ "两份同时跑会互相覆盖类、重复施加 mixin、重复注册内容,所以内核必须选一份。\n\n"
				: "This instance has " + decision.ownerByModId().size() + " mod(s) installed twice. "
						+ "Only one copy of each runs.\nTwo copies would shadow each other's classes, apply their "
						+ "mixins twice and register their content twice, so the kernel has to pick one.\n\n");

		for (Map.Entry<String, Path> e : decision.ownerByModId().entrySet()) {
			String id = e.getKey();
			Path winner = e.getValue();
			List<Entry> claimants = byId.getOrDefault(id, List.of());

			b.append(zh ? "■ " : "* ").append(id).append('\n');
			b.append(zh ? "    使用:  " : "    using:    ").append(winner.getFileName()).append('\n');
			for (Entry claimant : claimants) {
				if (claimant.jar().toAbsolutePath().equals(winner.toAbsolutePath())) continue;
				b.append(zh ? "    未使用:" : "    not used: ").append(claimant.jar().getFileName())
						.append(" (").append(claimant.ecosystem().name().toLowerCase(Locale.ROOT)).append(")\n");
			}

			List<String> dependents = dependentsOf(id, byId);
			if (!dependents.isEmpty()) {
				b.append(zh ? "    依赖它的 mod:" : "    depended on by: ").append(String.join(", ", dependents))
						.append('\n');
			}
			b.append('\n');
		}

		b.append(zh ? "如果某个功能不见了\n" : "If something is missing\n");
		b.append("-".repeat(60)).append("\n");
		b.append(zh
				? "把对应的一行加进实例目录下的 " + DuplicateModArbiter.OVERRIDE_FILE + ",例如:\n"
						+ "    sodium = neoforge\n"
						+ "然后重启游戏。命令行 -Dforbric.modOwner=sodium=neoforge 效果相同且优先级更高。\n\n"
				: "Add the matching line to " + DuplicateModArbiter.OVERRIDE_FILE + " in the instance folder, e.g.\n"
						+ "    sodium = neoforge\n"
						+ "then restart. The command line -Dforbric.modOwner=sodium=neoforge does the same and "
						+ "takes precedence.\n\n");

		b.append(zh ? "内核帮不上忙的一种情况\n" : "The one case the kernel cannot fix\n");
		b.append("-".repeat(60)).append("\n");
		b.append(zh
				? "同一个 mod 的两份构建,绝大部分类是一样的,所以没被选中那一侧的依赖方通常照常工作。\n"
						+ "但如果两侧各有 mod 直接用到了对方专有的类,一次只能满足一边 —— 这时只能用上面的\n"
						+ "方式钉住其中一个,另一边的那个功能会失效。日志里出现 \"served ... from a superseded\n"
						+ "jar\" 时,就是碰到了这种边界。\n"
				: "Two builds of one mod share nearly all their classes, so a dependent on the losing side normally\n"
						+ "keeps working. If mods on BOTH sides use their own side's platform-only classes, only one\n"
						+ "can be satisfied — pin whichever matters more, and expect the other's feature to be off.\n"
						+ "A \"served ... from a superseded jar\" line in the log means you have reached that edge.\n");
		return b.toString();
	}

	/** Mod ids that declare a dependency on {@code id}, using whichever manifest each jar carries. */
	private static List<String> dependentsOf(String id, Map<String, List<Entry>> byId) {
		Set<String> dependents = new LinkedHashSet<>();
		for (Map.Entry<String, List<Entry>> e : byId.entrySet()) {
			if (e.getKey().equals(id)) continue;
			for (Entry entry : e.getValue()) {
				if (entry.dependsOn().contains(id)) {
					dependents.add(e.getKey());
					break;
				}
			}
		}
		return new ArrayList<>(dependents);
	}

	/** mod id -> every jar declaring it, with what that jar depends on. Uses the kernel's own manifest parsers. */
	private static Map<String, List<Entry>> scan(Path modsDir) {
		Map<String, List<Entry>> byId = new LinkedHashMap<>();
		if (modsDir == null || !Files.isDirectory(modsDir)) return byId;

		ForbricModDiscoverer discoverer = new ForbricModDiscoverer();
		List<Path> jars;
		try (var entries = Files.list(modsDir)) {
			jars = entries.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.filter(Files::isRegularFile).sorted().toList();
		} catch (Exception e) {
			return byId;
		}

		for (Path jar : jars) {
			Ecosystem owner = MultiLoaderArbiter.ownerOf(jar);
			if (owner == null) continue;
			if (owner == Ecosystem.FABRIC) {
				addFabric(byId, jar, owner);
			} else {
				addForgeFamily(byId, jar, owner, discoverer);
			}
		}
		return byId;
	}

	private static void addFabric(Map<String, List<Entry>> byId, Path jar, Ecosystem owner) {
		try (JarFile zip = new JarFile(jar.toFile())) {
			ZipEntry manifest = zip.getEntry(ForbricModDiscoverer.FABRIC_MANIFEST);
			if (manifest == null) return;
			try (InputStream in = zip.getInputStream(manifest)) {
				KernelModMetadata metadata = FabricModMetadataParser.read(in);
				if (metadata == null || metadata.getId() == null) return;
				Set<String> deps = new LinkedHashSet<>();
				for (ModDependency dep : metadata.getDependencies()) {
					if (dep.getKind().isPositive()) deps.add(dep.getModId());
				}
				byId.computeIfAbsent(metadata.getId(), k -> new ArrayList<>())
						.add(new Entry(jar, owner, deps));
			}
		} catch (Throwable ignored) {
			// A jar the report cannot read simply does not appear in it.
		}
	}

	private static void addForgeFamily(Map<String, List<Entry>> byId, Path jar,
			Ecosystem owner, ForbricModDiscoverer discoverer) {
		try {
			for (DiscoveredMod mod : discoverer.discoverJar(jar)) {
				if (!mod.getEcosystem().isForgeFamily() || mod.getId() == null) continue;
				Set<String> deps = new LinkedHashSet<>();
				for (UnifiedDependency dep : mod.getDependencies()) deps.add(dep.getModId());
				byId.computeIfAbsent(mod.getId(), k -> new ArrayList<>()).add(new Entry(jar, owner, deps));
			}
		} catch (Throwable ignored) {
			// Same: unreadable means absent from the report, never a failed boot.
		}
	}
}
