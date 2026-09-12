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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.UnifiedDependency;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.util.ForbricLog;

/**
 * The sovereign kernel's boot entry point (BOOT side — system classloader).
 *
 * <p>At M0 this exposes the {@code --scan} diagnostic mode: it runs the unified {@link ForbricModDiscoverer}
 * over one or more {@code mods/} directories and emits a deterministic JSON report of every discovered mod
 * (ecosystem, id, version, source, dependencies, mixin configs). The report is the anchor of the
 * <em>differential oracle</em>: {@code run/diff-oracle.sh} compares it against the old forbric-loader system's
 * discovery on the identical mod set, so the kernel's discovery is proven at parity before any game boots.
 *
 * <p>Later milestones add the full launch path here (classloader construction, the lifecycle
 * {@code PhaseMachine}, game start). Those live behind {@code --client}/{@code --server} and are wired in at M1.
 */
public final class Main {
	private Main() {
	}

	public static void main(String[] args) throws Exception {
		Args parsed = Args.parse(args);
		switch (parsed.mode) {
			case SCAN -> runScan(parsed);
			case null, default -> {
				System.err.println("forbric-kernel: no runnable mode selected.");
				System.err.println(USAGE);
				System.exit(2);
			}
		}
	}

	private static void runScan(Args a) throws IOException {
		if (a.modDirs.isEmpty()) {
			System.err.println("forbric-kernel --scan: no --mods <dir> (or --game-dir <dir>) given.");
			System.err.println(USAGE);
			System.exit(2);
			return;
		}

		ForbricModDiscoverer discoverer = new ForbricModDiscoverer();
		List<DiscoveredMod> all = new ArrayList<>();
		for (Path dir : a.modDirs) {
			if (!Files.isDirectory(dir)) {
				ForbricLog.warn("mods dir does not exist, skipping: %s", dir);
				continue;
			}
			all.addAll(discoverer.discover(dir));
		}

		// Deterministic order so the oracle diff is stable across runs and machines.
		all.sort(Comparator.comparing((DiscoveredMod m) -> m.getEcosystem().name())
				.thenComparing(m -> nullToEmpty(m.getId()))
				.thenComparing(m -> nullToEmpty(m.getVersion()))
				.thenComparing(m -> basename(m.getSource())));

		String json = toJson(all);
		if (a.report != null) {
			Files.writeString(a.report, json);
			ForbricLog.info("scan: %d mods across %d dir(s) → %s", all.size(), a.modDirs.size(), a.report);
		} else {
			System.out.println(json);
		}
	}

	// --- deterministic JSON emission (hand-rolled: no dep, stable key order, sorted arrays) ---

	private static String toJson(List<DiscoveredMod> mods) {
		StringBuilder sb = new StringBuilder();
		sb.append("{\n  \"kernel\": \"forbric-kernel\",\n  \"schema\": 1,\n  \"count\": ")
				.append(mods.size()).append(",\n  \"mods\": [");
		for (int i = 0; i < mods.size(); i++) {
			DiscoveredMod m = mods.get(i);
			sb.append(i == 0 ? "\n" : ",\n");
			sb.append("    {")
					.append("\"ecosystem\": \"").append(m.getEcosystem().name()).append("\", ")
					.append("\"id\": ").append(str(m.getId())).append(", ")
					.append("\"version\": ").append(str(m.getVersion())).append(", ")
					.append("\"source\": ").append(str(basename(m.getSource()))).append(", ")
					.append("\"deps\": ").append(deps(m.getDependencies())).append(", ")
					.append("\"mixins\": ").append(strArray(sorted(m.getMixinConfigs())))
					.append("}");
		}
		sb.append(mods.isEmpty() ? "" : "\n  ").append("]\n}\n");
		return sb.toString();
	}

	private static String deps(List<UnifiedDependency> deps) {
		List<String> rendered = new ArrayList<>();
		for (UnifiedDependency d : deps) {
			rendered.add(d.getModId() + (d.isMandatory() ? "" : "?") + " " + d.getVersionConstraint());
		}
		return strArray(sorted(rendered));
	}

	private static List<String> sorted(List<String> in) {
		List<String> out = new ArrayList<>(in);
		out.sort(Comparator.naturalOrder());
		return out;
	}

	private static String strArray(List<String> items) {
		if (items.isEmpty()) return "[]";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < items.size(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(str(items.get(i)));
		}
		return sb.append("]").toString();
	}

	private static String str(String s) {
		if (s == null) return "null";
		StringBuilder sb = new StringBuilder("\"");
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> sb.append(c);
			}
		}
		return sb.append("\"").toString();
	}

	private static String basename(String path) {
		if (path == null) return "";
		int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		return slash >= 0 ? path.substring(slash + 1) : path;
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}

	private enum Mode { SCAN }

	private static final String USAGE = """
			usage: forbric-kernel --scan [--mods <dir>]... [--game-dir <dir>] [--report <file>]
			  --scan            run unified mod discovery and emit a JSON report (the differential-oracle anchor)
			  --mods <dir>      a mods/ directory to scan (repeatable)
			  --game-dir <dir>  convenience: scan <dir>/mods
			  --report <file>   write JSON to <file> (default: stdout)""";

	private static final class Args {
		Mode mode;
		final List<Path> modDirs = new ArrayList<>();
		Path report;

		static Args parse(String[] argv) {
			Args a = new Args();
			for (int i = 0; i < argv.length; i++) {
				switch (argv[i]) {
					case "--scan" -> a.mode = Mode.SCAN;
					case "--mods" -> a.modDirs.add(Path.of(require(argv, ++i, "--mods")));
					case "--game-dir" -> a.modDirs.add(Path.of(require(argv, ++i, "--game-dir"), "mods"));
					case "--report" -> a.report = Path.of(require(argv, ++i, "--report"));
					default -> {
						System.err.println("forbric-kernel: unknown argument: " + argv[i]);
						System.err.println(USAGE);
						System.exit(2);
					}
				}
			}
			return a;
		}

		private static String require(String[] argv, int i, String flag) {
			if (i >= argv.length) {
				System.err.println("forbric-kernel: " + flag + " requires a value");
				System.exit(2);
			}
			return argv[i];
		}
	}
}
