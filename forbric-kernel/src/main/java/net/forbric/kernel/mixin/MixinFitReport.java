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

package net.forbric.kernel.mixin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;

/**
 * Offline driver for {@link MixinFit}: reports what every guest mixin in a mods directory would be judged as,
 * against a merged base, without booting the game.
 *
 * <p>This exists because the runtime path cannot enumerate. {@code run/mixin-inventory.sh} boots iteratively for one
 * finding per round, because under {@code -Dforbric.mixinDiagnostics} an {@code InjectionError} escapes as a fatal
 * {@code MixinTransformerError} that {@code required:false} does not catch. A static pass over the same inputs
 * produces the whole table in seconds and cannot break anything.
 *
 * <p>Usage: {@code MixinFitReport <merged-base.jar> <mods-dir> [--verbose]}
 *
 * <p><b>Known divergence from runtime:</b> this resolves against RAW merged-jar bytes, whereas the kernel resolves
 * against post-transform-chain bytes ({@code getPreMixinClassBytes}). The chain both adds members (the
 * {@code KeyMapping.MAP} initializer) and removes them (the interface-default shadowing overrides), so a handful of
 * verdicts here can differ from the live ones. Treat this as the enumeration tool, not as the oracle.
 */
public final class MixinFitReport {
	private MixinFitReport() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 2) {
			System.err.println("usage: MixinFitReport <merged-base.jar> <mods-dir> [--verbose]");
			System.exit(2);
		}
		boolean verbose = args.length > 2 && args[2].contains("verbose");

		Map<String, byte[]> merged = readJar(Path.of(args[0]));
		System.out.printf("[fit] merged base: %d classes from %s%n", merged.size(), Path.of(args[0]).getFileName());

		List<Path> jars = new ArrayList<>();
		try (var stream = Files.list(Path.of(args[1]))) {
			stream.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(jars::add);
		}

		Map<MixinFit.Verdict, Integer> tally = new LinkedHashMap<>();
		Map<String, List<String>> byVerdict = new TreeMap<>();
		// The same config can be reachable through more than one unit (a module jar nested in more than one
		// aggregate); judge each config:mixin once so the counts are honest.
		Set<String> seen = new LinkedHashSet<>();
		int scanned = 0;

		for (Path jar : jars) {
			for (Map.Entry<String, Map<String, byte[]>> unit : expand(jar).entrySet()) {
				Map<String, byte[]> content = unit.getValue();
				for (Map.Entry<String, byte[]> cfg : mixinConfigs(content).entrySet()) {
					Parsed parsed = parseConfig(cfg.getValue());
					if (parsed == null) continue;
					for (String entry : parsed.mixins) {
						String path = parsed.pkg.replace('.', '/') + "/" + entry.replace('.', '/') + ".class";
						byte[] bytes = content.get(path);
						if (bytes == null) continue;
						if (!seen.add(cfg.getKey() + ":" + entry)) continue;
						scanned++;
						MixinFit.Result r;
						try {
							r = MixinFit.evaluate(bytes, name -> merged.get(name));
						} catch (RuntimeException e) {
							System.out.printf("  !! %s:%s — scan failed: %s%n", cfg.getKey(), entry, e);
							continue;
						}
						tally.merge(r.verdict(), 1, Integer::sum);
						if (r.shouldSuppress() || verbose) {
							byVerdict.computeIfAbsent(r.verdict().name(), k -> new ArrayList<>())
									.add(String.format("%-58s %s", cfg.getKey() + ":" + entry, r.reason()));
						}
					}
				}
			}
		}

		System.out.printf("%n[fit] scanned %d guest mixin(s) across %d jar(s)%n", scanned, jars.size());
		for (MixinFit.Verdict v : MixinFit.Verdict.values()) {
			System.out.printf("[fit]   %-8s %4d%n", v, tally.getOrDefault(v, 0));
		}
		int suppress = tally.getOrDefault(MixinFit.Verdict.PARTIAL, 0)
				+ tally.getOrDefault(MixinFit.Verdict.UNFIT, 0)
				+ tally.getOrDefault(MixinFit.Verdict.HAZARD, 0);
		System.out.printf("[fit] would suppress %d of %d (%.1f%%)%n", suppress, scanned,
				scanned == 0 ? 0.0 : 100.0 * suppress / scanned);

		for (Map.Entry<String, List<String>> e : byVerdict.entrySet()) {
			System.out.printf("%n=== %s (%d) ===%n", e.getKey(), e.getValue().size());
			e.getValue().stream().sorted().forEach(line -> System.out.println("  " + line));
		}
	}

	private record Parsed(String pkg, Set<String> mixins) {
	}

	private static Parsed parseConfig(byte[] json) {
		UnmodifiableConfig config;
		try (Reader reader = new InputStreamReader(new ByteArrayInputStream(json), StandardCharsets.UTF_8)) {
			config = JsonFormat.fancyInstance().createParser().parse(reader);
		} catch (RuntimeException | IOException notAConfig) {
			return null;
		}
		Object pkg = config.get(List.of("package"));
		if (pkg == null || pkg.toString().isEmpty()) return null;

		Set<String> mixins = new LinkedHashSet<>();
		for (String key : List.of("mixins", "client", "server")) {
			if (config.get(List.of(key)) instanceof List<?> list) {
				for (Object element : list) {
					if (element instanceof String s && !s.isBlank()) mixins.add(s.trim());
				}
			}
		}
		return mixins.isEmpty() ? null : new Parsed(pkg.toString(), mixins);
	}

	private static Map<String, byte[]> mixinConfigs(Map<String, byte[]> content) {
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : content.entrySet()) {
			String name = e.getKey();
			if (name.indexOf('/') >= 0) continue;
			if (name.endsWith(".mixins.json") || name.endsWith(".mixin.json")
					|| (name.startsWith("mixins.") && name.endsWith(".json"))) {
				out.put(name, e.getValue());
			}
		}
		return out;
	}

	/** A jar plus every mod jar nested inside it (Fabric {@code META-INF/jars}, Forge {@code META-INF/jarjar}). */
	private static Map<String, Map<String, byte[]>> expand(Path jar) throws IOException {
		Map<String, Map<String, byte[]>> units = new LinkedHashMap<>();
		Map<String, byte[]> top = readJar(jar);
		units.put(jar.getFileName().toString(), top);

		for (Map.Entry<String, byte[]> e : top.entrySet()) {
			String name = e.getKey();
			if (!name.endsWith(".jar")) continue;
			if (!name.startsWith("META-INF/jars/") && !name.startsWith("META-INF/jarjar/")) continue;
			units.put(jar.getFileName() + "!" + name, readZip(e.getValue()));
		}
		return units;
	}

	private static Map<String, byte[]> readJar(Path jar) throws IOException {
		return readZip(Files.readAllBytes(jar));
	}

	private static Map<String, byte[]> readZip(byte[] bytes) throws IOException {
		Map<String, byte[]> out = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				if (entry.isDirectory()) continue;
				String name = entry.getName();
				boolean wanted = name.endsWith(".class") || name.endsWith(".json") || name.endsWith(".jar");
				if (wanted) out.put(name, in.readAllBytes());
			}
		}
		return out;
	}
}
