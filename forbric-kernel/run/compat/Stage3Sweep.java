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

import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.UnifiedDependency;
import net.forbric.kernel.boot.MultiLoaderArbiter;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.discovery.MetadataFailures;

/**
 * One side of the Stage 3 sweep: asks a kernel's OWN arbitration how it would load each jar in a list, and writes one
 * TSV row per jar. {@code stage3-sweep.py} runs it twice — once over this tree's kernel classes ("after"), and once with
 * the {@code tri-eco-26.2-final} versions of the three classes that decide a jar's owner ({@code MultiLoaderArbiter},
 * {@code ForbricModDiscoverer}, {@code MetadataFailures}) compiled from that tag's sources and put first on the class
 * path ("before"). Nothing is reproduced here: the owner is whatever {@code MultiLoaderArbiter.ownerOf} answers, and a
 * family counts as lost when the owner's own manifest is one {@code MetadataFailures} recorded as unreadable, which is
 * how {@code MetadataFailures.recordFindings} turns it into a "nothing of this jar loads" finding at boot.
 *
 * <p>Written against both versions at once: what only the new kernel has ({@code MultiLoaderArbiter.refused},
 * {@code LoaderHalfJudge}) is reached by reflection, and answers {@code -} on the old one.
 *
 * <p>Usage: {@code java -cp <kernel classpath> Stage3Sweep.java <jar-list.txt> <out.tsv>}; one absolute jar path per
 * line in the list. Columns: path, declared manifests, owner, failed families, refused, verdict (owner / refused /
 * library / lost:FAMILY), per-half reasons, discovered mods, mandatory dependencies, provided aliases.
 */
public final class Stage3Sweep {
	public static void main(String[] args) throws Exception {
		List<String> jars = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
		Method refused = method(MultiLoaderArbiter.class, "refused", Path.class);
		Class<?> judge = type("net.forbric.kernel.discovery.LoaderHalfJudge");
		Method judgeJar = judge == null ? null : judge.getMethod("judge", Path.class);
		Method describe = judge == null ? null : type("net.forbric.kernel.discovery.LoaderHalfJudge$Verdict").getMethod("describe");
		// The kernel logs to System.err without a game; keep the TSV on its own stream.
		try (PrintStream out = new PrintStream(Files.newOutputStream(Path.of(args[1])), true, StandardCharsets.UTF_8)) {
			for (String line : jars) {
				if (line.isBlank()) continue;
				Path jar = Path.of(line.strip());
				List<String> row = new ArrayList<>();
				row.add(jar.toString());
				try {
					List<Ecosystem> declared = MultiLoaderArbiter.declaredBy(jar);
					Ecosystem owner = MultiLoaderArbiter.ownerOf(jar);
					Set<Ecosystem> failed = MetadataFailures.failedFamilies(jar);
					String isRefused = refused == null ? "-" : String.valueOf(refused.invoke(null, jar));
					String verdict;
					if ("true".equals(isRefused)) verdict = "refused";
					else if (owner == null) verdict = "library";
					else if (failed.contains(owner)) verdict = "lost:" + owner;
					else verdict = owner.name();
					String halves = judgeJar == null ? "-" : String.valueOf(describe.invoke(judgeJar.invoke(null, jar)));
					Set<String> mods = new TreeSet<>(), deps = new TreeSet<>(), aliases = new TreeSet<>();
					for (DiscoveredMod mod : new ForbricModDiscoverer().discoverJar(jar)) {
						mods.add(mod.getEcosystem() + ":" + mod.getId());
						for (UnifiedDependency dep : mod.getDependencies()) {
							if (dep.isMandatory()) deps.add(mod.getEcosystem() + ":" + mod.getId() + ">" + dep.getModId());
						}
						for (String alias : mod.getAliases()) aliases.add(mod.getEcosystem() + ":" + alias);
					}
					row.add(join(declared));
					row.add(owner == null ? "-" : owner.name());
					row.add(join(failed));
					row.add(isRefused);
					row.add(verdict);
					row.add(clean(halves));
					row.add(String.join(",", mods));
					row.add(String.join(",", deps));
					row.add(String.join(",", aliases));
				} catch (Throwable error) {
					// One unreadable jar is a row saying so, not the end of a thousand-jar sweep.
					row.add("-");
					row.add("-");
					row.add("-");
					row.add("-");
					row.add("error");
					row.add(clean(error.toString()));
					row.add("");
					row.add("");
					row.add("");
				}
				out.println(String.join("\t", row));
			}
		}
	}

	private static String join(Iterable<Ecosystem> families) {
		List<String> names = new ArrayList<>();
		for (Ecosystem family : families) names.add(family.name());
		return names.isEmpty() ? "-" : String.join(",", names);
	}

	private static String clean(String text) {
		return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
	}

	private static Method method(Class<?> owner, String name, Class<?>... parameters) {
		try {
			return owner.getMethod(name, parameters);
		} catch (NoSuchMethodException absent) {
			return null; // the tri-eco kernel: no refusal existed
		}
	}

	private static Class<?> type(String name) {
		try {
			return Class.forName(name);
		} catch (ClassNotFoundException absent) {
			return null;
		}
	}
}
