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

package net.forbric.kernel.fabric;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import net.fabricmc.api.EnvType;

import net.forbric.kernel.util.ForbricLog;

/**
 * Discovers Fabric mods in a {@code mods/} directory, following JiJ ({@code "jars"}) nesting.
 *
 * <p>Nested jars are extracted to a cache directory because the transforming class loader reads from real jar
 * URLs; fabric-api alone ships 43 of them. Extraction is content-addressed by size so a re-launch reuses the
 * cache, and a changed parent jar re-extracts.
 *
 * <p>Mods whose {@code environment} excludes the running side are skipped entirely (Fabric's own behaviour) —
 * their jar never joins the classpath, so a client-only fabric-api module cannot be linked against on a
 * dedicated server. So is a nested mod Fabric Loader could not load here ({@link NestedFabricRequirements}).
 */
public final class FabricModDiscovery {
	public static final String MANIFEST = "fabric.mod.json";

	private final EnvType envType;
	private final Path cacheDir;
	private final List<KernelModContainer> containers = new ArrayList<>();
	private final List<Path> classpathJars = new ArrayList<>();

	/**
	 * Top-level jars this scan must pretend are not installed — the losers of cross-jar mod-id arbitration.
	 * Applied only to top-level jars: a suppressed jar never gets far enough for its nested children to matter,
	 * and the winner brings its own.
	 */
	private java.util.function.Predicate<Path> skip = jar -> false;

	/** What a nested mod's {@code minecraft}/{@code java} requirement is held against; the game is unknown until set. */
	private NestedFabricRequirements.Platform platform = NestedFabricRequirements.Platform.running(null);

	/**
	 * Hard requirements of jars this discovery does not read (the Forge-family mods and the Fabric jars their JarJar
	 * carries), so that a nested Fabric mod one of them needs is kept back from Fabric's rule as it would be for a
	 * Fabric dependent.
	 */
	private List<NestedFabricRequirements.Requirement> requiredElsewhere = List.of();

	/** One jar the walk read, before any container exists; {@code parent} indexes the list ({@code -1}: a root). */
	private record Found(Path jar, KernelModMetadata metadata, int parent) {
	}

	public FabricModDiscovery(EnvType envType, Path cacheDir) {
		this.envType = envType;
		this.cacheDir = cacheDir;
	}

	/** Sets the top-level skip test (see {@link #skip}). */
	public void setSkip(java.util.function.Predicate<Path> skip) {
		if (skip != null) this.skip = skip;
	}

	/** Sets the platform nested mods are judged against (see {@link #platform}). */
	public void setPlatform(NestedFabricRequirements.Platform platform) {
		if (platform != null) this.platform = platform;
	}

	/** Sets {@link #requiredElsewhere}. */
	public void setRequiredElsewhere(List<NestedFabricRequirements.Requirement> requirements) {
		this.requiredElsewhere = requirements == null ? List.of() : List.copyOf(requirements);
	}

	/** Every discovered mod, parents before their nested children. */
	public List<KernelModContainer> getContainers() {
		return containers;
	}

	/** Every jar that must join the game class loader, in discovery order. */
	public List<Path> getClasspathJars() {
		return classpathJars;
	}

	/** Scans {@code modsDir} for jars carrying a {@code fabric.mod.json}. Non-Fabric jars are ignored. */
	public void discover(Path modsDir) {
		var plan = net.forbric.kernel.boot.DuplicateModArbiter.planned(modsDir, envType);
		if (plan != null) {
			discoverPlanned(plan);
			return;
		}
		if (!Files.isDirectory(modsDir)) return;

		List<Path> jars = new ArrayList<>();

		try (Stream<Path> entries = Files.list(modsDir)) {
			entries.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.filter(Files::isRegularFile)
					.sorted()
					.forEach(jars::add);
		} catch (IOException e) {
			ForbricLog.warn("[Forbric/Fabric] could not list %s: %s", modsDir, e.getMessage());
			return;
		}

		List<Found> found = new ArrayList<>();
		Map<Integer, String> leftOut = new LinkedHashMap<>();
		for (Path jar : jars) {
			if (skip.test(jar)) {
				ForbricLog.debug("[Forbric/Fabric] skipping %s — superseded by another jar's copy of the same mod",
						jar.getFileName());
				continue;
			}
			discoverJar(jar, -1, found, leftOut);
		}

		// Read everything first, then register: whether a nested mod can load is only known once every jar that
		// could provide its dependencies, or depend on it, has been read (NestedFabricRequirements.resolve).
		List<NestedFabricRequirements.Candidate<Integer>> candidates = new ArrayList<>();
		for (int i = 0; i < found.size(); i++) {
			Found mod = found.get(i);
			Map<String, String> provides = new LinkedHashMap<>();
			String version = mod.metadata().getVersion() == null ? "0" : mod.metadata().getVersion().getFriendlyString();
			provides.put(net.forbric.api.ModPresence.spellingKey(mod.metadata().getId()), version);
			for (String alias : mod.metadata().getProvides()) provides.putIfAbsent(net.forbric.api.ModPresence.spellingKey(alias), version);
			boolean root = mod.parent() < 0;
			candidates.add(new NestedFabricRequirements.Candidate<>(i, mod.metadata().getId(), root, !root, provides,
					root ? List.of() : List.of(mod.parent()), mod.metadata(), NestedFabricRequirements.requirementsOf(mod.metadata())));
		}
		var resolution = NestedFabricRequirements.resolve(candidates, leftOut, requiredElsewhere);
		// Then one mod per id, provides included, among what still loads (FabricUniqueIds). Parents come before their
		// nested mods in found, so one pass sees whether each parent loads. No arbitration runs here: of two copies of
		// one id KernelFabricLoader.register keeps the first it is given, so the first found is the one weighed (the
		// other's nested mods still load), and a copy that loses takes the later copies that would lose the same way.
		boolean[] loads = new boolean[found.size()];
		java.util.Set<String> registered = new java.util.HashSet<>();
		List<NestedFabricRequirements.Candidate<Integer>> loading = new ArrayList<>();
		for (int i = 0; i < found.size(); i++) {
			int parent = found.get(i).parent();
			loads[i] = !resolution.leftOut().containsKey(i) && (parent < 0 || loads[parent]);
			if (loads[i] && registered.add(found.get(i).metadata().getId())) loading.add(candidates.get(i));
		}
		var unique = FabricUniqueIds.resolve(loading, requiredElsewhere);
		// A copy that lost goes with every later copy of its id that answers to the id it lost: with the first copy out,
		// the next would register in its place and answer to that id after all.
		Map<Integer, String> idLeftOut = new LinkedHashMap<>(unique.leftOut());
		for (var lost : unique.lost().entrySet()) {
			String reason = unique.leftOut().get(lost.getKey());
			String id = found.get(lost.getKey()).metadata().getId();
			for (int j = 0; j < found.size(); j++) {
				if (j == lost.getKey() || !loads[j] || !id.equals(found.get(j).metadata().getId())) continue;
				if (FabricUniqueIds.answersTo(candidates.get(j)).contains(lost.getValue().id())) idLeftOut.putIfAbsent(j, reason);
			}
		}

		KernelModContainer[] built = new KernelModContainer[found.size()];
		for (int i = 0; i < found.size(); i++) {
			Found mod = found.get(i);
			Object parentJar = mod.parent() < 0 ? null : found.get(mod.parent()).jar().getFileName();
			if (resolution.leftOut().containsKey(i)) {
				NestedFabricRequirements.log(mod.metadata(), parentJar, resolution.leftOut().get(i));
				continue;
			}
			if (idLeftOut.containsKey(i)) {
				NestedFabricRequirements.log(mod.metadata(), parentJar, idLeftOut.get(i));
				continue;
			}
			if (resolution.keptBack().containsKey(i)) {
				NestedFabricRequirements.logKeptBack(mod.metadata(), parentJar, resolution.keptBack().get(i));
			}
			if (unique.keptBack().containsKey(i)) {
				NestedFabricRequirements.logKeptBack(mod.metadata(), parentJar, unique.keptBack().get(i));
			}
			KernelModContainer parent = mod.parent() < 0 ? null : built[mod.parent()];
			if (mod.parent() >= 0 && parent == null) continue;
			built[i] = new KernelModContainer(mod.metadata(), mod.jar(), parent);
			containers.add(built[i]);
			classpathJars.add(mod.jar());
		}
	}

	/** Consumes the already selected physical files. No size cache, extraction or second version choice occurs. */
	private void discoverPlanned(net.forbric.kernel.boot.NestedCandidatePlan plan) {
		java.util.Map<Path, KernelModContainer> byPath = new java.util.LinkedHashMap<>();
		java.util.Set<Path> visited = new java.util.LinkedHashSet<>();
		List<net.forbric.kernel.boot.NestedCandidateInventory.Node> pending = new ArrayList<>(
				plan.inventory().nodes().values().stream().filter(n -> plan.selected().contains(n.path())).toList());
		while (!pending.isEmpty()) {
			boolean progress = false;
			for (var iterator = pending.iterator(); iterator.hasNext();) {
				var node = iterator.next();
				Path parentPath = plan.inventory().edges().stream().filter(e -> e.child().equals(node.path()) && visited.contains(e.parent()))
						.map(net.forbric.kernel.boot.NestedCandidateInventory.Edge::parent).findFirst().orElse(null);
				if (!node.root() && parentPath == null) continue;
				try (JarFile jar = new JarFile(node.path().toFile())) {
					ZipEntry manifest = jar.getEntry(MANIFEST);
					if (manifest != null) try (InputStream in = jar.getInputStream(manifest)) {
						KernelModMetadata metadata = FabricModMetadataParser.read(in);
						if (metadata.getEnvironment().matches(envType)) {
							KernelModContainer container = new KernelModContainer(metadata, node.path(), byPath.get(parentPath));
							containers.add(container); classpathJars.add(node.path()); byPath.put(node.path(), container);
						}
					}
				} catch (Exception failed) {
					ForbricLog.warn("[Forbric/Fabric] could not read selected candidate %s: %s", node.path(), String.valueOf(failed));
					recordUnreadable(node.path(), failed);
				}
				visited.add(node.path()); iterator.remove(); progress = true;
			}
			if (!progress) {
				ForbricLog.error("[Forbric/Fabric] selected nested candidates have no selected parent path: %s",
						pending.stream().map(n -> n.path().toString()).toList());
				break;
			}
		}
	}

	/**
	 * A fabric.mod.json that would not parse used to cost its mod with one WARN line and nothing on any report. An
	 * unreadable zip is not recorded: that is not a metadata problem, and nothing in it could be named.
	 */
	private static void recordUnreadable(Path jar, Exception error) {
		if (error instanceof IOException) return;
		String message = error.getMessage();
		net.forbric.kernel.discovery.MetadataFailures.record(new net.forbric.kernel.discovery.MetadataFailures.Failure(
				jar, net.forbric.api.Ecosystem.FABRIC, MANIFEST, List.of(),
				message == null || message.isBlank() ? error.getClass().getSimpleName() : message.strip()));
	}

	/**
	 * Reads one jar; if it is a Fabric mod, records it and recurses into its nested jars. A nested mod Fabric Loader
	 * could not load is recorded as left out; its own nested jars are still read, since a mod that loads may need one.
	 */
	private void discoverJar(Path jar, int parent, List<Found> found, Map<Integer, String> leftOut) {
		KernelModMetadata metadata;

		try (JarFile jarFile = new JarFile(jar.toFile())) {
			ZipEntry entry = jarFile.getEntry(MANIFEST);
			if (entry == null) return;

			try (InputStream in = jarFile.getInputStream(entry)) {
				metadata = FabricModMetadataParser.read(in);
			}
		} catch (Exception e) {
			ForbricLog.warn("[Forbric/Fabric] could not read %s: %s", jar.getFileName(), String.valueOf(e));
			recordUnreadable(jar, e);
			return;
		}

		if (!metadata.getEnvironment().matches(envType)) {
			ForbricLog.debug("[Forbric/Fabric] skipping %s (environment=%s, running %s)",
					metadata.getId(), metadata.getEnvironment(), envType);
			return;
		}

		int index = found.size();
		found.add(new Found(jar, metadata, parent));
		String unmet = parent < 0 ? null : NestedFabricRequirements.unmet(metadata, platform);
		if (unmet != null) leftOut.put(index, unmet);

		for (String nested : metadata.getNestedJars()) {
			Path extracted = extract(jar, nested, metadata.getId());
			if (extracted != null) discoverJar(extracted, index, found, leftOut);
		}
	}

	/**
	 * Extracts {@code entryPath} from {@code jar} into the cache, returning the extracted file (or {@code null}
	 * if absent/unreadable). Reuses an existing extraction whose size already matches the entry.
	 */
	private Path extract(Path jar, String entryPath, String parentModId) {
		String fileName = entryPath.substring(entryPath.lastIndexOf('/') + 1);
		Path target = cacheDir.resolve(parentModId).resolve(fileName);

		try (JarFile jarFile = new JarFile(jar.toFile())) {
			ZipEntry entry = jarFile.getEntry(entryPath);

			if (entry == null) {
				ForbricLog.warn("[Forbric/Fabric] %s declares nested jar '%s' which is not in the jar",
						parentModId, entryPath);
				return null;
			}

			if (Files.isRegularFile(target) && Files.size(target) == entry.getSize()) {
				return target;
			}

			Files.createDirectories(target.getParent());

			try (InputStream in = jarFile.getInputStream(entry)) {
				Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
			}

			return target;
		} catch (IOException e) {
			ForbricLog.warn("[Forbric/Fabric] could not extract '%s' from %s: %s", entryPath, jar.getFileName(),
					String.valueOf(e));
			return null;
		}
	}
}
