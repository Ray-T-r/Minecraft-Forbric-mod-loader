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

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModMetadata;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.ModPresence;
import net.forbric.api.Side;
import net.forbric.api.UnifiedDependency;
import net.forbric.api.VersionPredicate;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Which nested (jar-in-jar) Fabric mods Fabric Loader itself leaves out, and why.
 *
 * <p>Read off fabric-loader 0.19.5's {@code ModSolver}. A jar in {@code mods/} has load condition {@code ALWAYS}
 * and must be selected; a nested one is {@code IF_POSSIBLE}: never forced, weighted so the optimiser loads it
 * whenever it can, and only with a selected parent. {@code minecraft}, {@code java} and {@code fabricloader} are
 * preselected builtins, so a {@code depends} on one that excludes the running version, or a {@code breaks} that
 * includes it, switches the mod off outright ({@code HARD_DEP_INCOMPATIBLE_PRESELECTED}). On a root that is a
 * resolution error; on a nested mod it only means the mod is not loaded — and nor is a nested mod that hard-depends
 * on it and nothing else, or that only it bundles, because neither has a selectable way left in.
 *
 * <p>The kernel used to load every nested Fabric mod a parent declared. ViaFabric 0.4.22 nests one platform layer
 * per Minecraft line under two ids, {@code viafabric-mc26-1} ({@code minecraft >=26.1 <=26.1.2}) and
 * {@code viafabric-mc26-2}. Native lists only the second; the kernel ran both, and the second
 * {@code ViaFabric.onInitialize} died on "ViaManager is already set", which stopped a STRICT launch.
 *
 * <p>Only what Fabric Loader itself would reach is judged: a jar a Fabric mod declares in its {@code jars}. A
 * NeoForge or MinecraftForge mod's nested jar is not, even when it carries a {@code fabric.mod.json}: Fabric Loader
 * 0.19.5's {@code ModDiscoverer} records a jar without {@code fabric.mod.json} as a non-Fabric mod and opens nothing
 * in it. A child the parent's {@code META-INF/jarjar/metadata.json} declares is FML's: both FMLs read only that file
 * ({@code JarSelector} in NeoForge, {@code JarInJarDependencyLocator.Selector} in MinecraftForge), neither reads a
 * {@code fabric.mod.json}, and NeoForge's {@code NestedLibraryModReader} makes a declared child no other reader claims
 * a {@code LIBRARY}. A jar that only sits in {@code META-INF/jars/} or {@code META-INF/jarjar/} with no entry in that
 * file is loaded by no native loader at all; the kernel loads it because its Forge-family walk has always taken both
 * directories (a multiloader library nests the Fabric way under a NeoForge parent), and Fabric's rule is not a reason
 * to stop. A jar reached both ways is kept. {@code NestedCandidateInventory} makes that distinction; the no-plan
 * discovery walks Fabric {@code jars} only.
 *
 * <p>Only {@code minecraft} and {@code java} are judged: the kernel reports both exactly as Fabric Loader does.
 * {@code fabricloader} answers the kernel's API level and {@code mixinextras} its bundled copy, neither of them the
 * version a native instance compares against, so a range on them never leaves a mod out. Nor does a range nobody
 * could read: this removes mods, and must not do it on a guess. A dependency nothing installed meets is not judged
 * either; {@code DependencyAudit} reports that. Jars in {@code mods/} are never judged here — the player chose them.
 *
 * <p>One place the kernel does not follow native: a mod that loads and hard-requires an id that nothing loading
 * meets, while a left-out copy meets it or nothing loading provides the id at all. For a jar in {@code mods/} native
 * refuses to start ({@code ModSolver} forces a root, and its dependency has no selectable provider left). The kernel
 * loads that mod anyway, as it loads any mod with an unmet dependency, so leaving the provider out would only take its
 * dependency away; the provider is kept back instead, with a WARN naming both (see {@link #resolve}).
 *
 * <p>Fabric Loader also loads one mod per id, {@code provides} included. That half is decided on the selection, after
 * cross-jar arbitration has picked one build per declared id, by {@link FabricUniqueIds}; this class's rule runs
 * before arbitration, on what a nested mod needs from the platform.
 *
 * <p>{@code -Dforbric.nestedRequirements=off} loads every nested Fabric mod again;
 * {@code -Dforbric.nestedRequirements=off:<id>,<id>} keeps only those ids out of the rule (the kernel does not read
 * Fabric's {@code config/fabric_loader_dependencies.json}, so this is the per-mod override).
 */
public final class NestedFabricRequirements {
	public static final String SWITCH = "forbric.nestedRequirements";


	private NestedFabricRequirements() {
	}

	/** The versions a nested mod's platform requirements are held against. A null {@code minecraft} is not judged. */
	public record Platform(String minecraft, String java) {
		/** This JVM's Java, as the kernel's {@code java} builtin reports it, and the given game version. */
		public static Platform running(String minecraftVersion) {
			return new Platform(minecraftVersion, String.valueOf(Runtime.version().feature()));
		}
	}

	/** A hard requirement a mod brings if it loads: {@code by} needs {@code id} at a version {@code accepts} takes. */
	public record Requirement(String by, String id, String range, Predicate<String> accepts) {
	}

	/**
	 * One candidate for {@link #resolve}: its display id, the ids it answers to (spelling key to version), whether it
	 * is a root, whether Fabric's rule may leave it out, the candidates that bundle it, its Fabric manifest
	 * ({@code null} for another ecosystem's or none) and the hard requirements it brings if it loads.
	 */
	public record Candidate<K>(K key, String id, boolean root, boolean judged, Map<String, String> provides,
			List<K> parents, ModMetadata fabric, List<Requirement> requires) {
	}

	/**
	 * Left out by Fabric's rule but kept, because a mod that loads needs it: why Fabric leaves it out, and who needs it
	 * and whether this copy meets what they ask for.
	 */
	public record KeptBack(String reason, String because) {
	}

	/** {@link #resolve}'s answer: what is left out (with why), and what was kept back from it. */
	public record Resolution<K>(Map<K, String> leftOut, Map<K, KeptBack> keptBack) {
	}

	/** Whether a carrier or the kernel answers for {@code id}, so that no candidate is ever weighed for it. */
	static boolean platform(String id) {
		return net.forbric.kernel.util.PlatformIds.provides(id);
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on").trim());
	}

	/** Whether {@code id} is held to the rule: the switch is on and {@code off:<ids>} does not name it. */
	public static boolean judged(String id) {
		if (!enabled()) return false;
		String value = System.getProperty(SWITCH, "on").trim();
		if (id == null || !value.toLowerCase(Locale.ROOT).startsWith("off:")) return true;
		Set<String> kept = Arrays.stream(value.substring(4).split(",")).map(String::trim).filter(s -> !s.isEmpty())
				.map(ModPresence::spellingKey).collect(Collectors.toSet());
		return !kept.contains(ModPresence.spellingKey(id));
	}

	/** Why a nested Fabric mod cannot load on {@code platform}, or {@code null} if it can. */
	public static String unmet(ModMetadata metadata, Platform platform) {
		if (metadata == null || platform == null || !judged(metadata.getId())) return null;
		for (ModDependency dependency : metadata.getDependencies()) {
			String running = switch (dependency.getModId()) {
				case "minecraft" -> platform.minecraft();
				case "java" -> platform.java();
				default -> null;
			};
			if (running == null) continue;
			List<String> ranges = ranges(dependency);
			String range = String.join(" || ", ranges);
			if (dependency.getKind() == ModDependency.Kind.DEPENDS
					&& ranges.stream().noneMatch(r -> VersionPredicate.matches(r, running))) {
				return dependency.getModId() + " " + range + " does not include " + running;
			}
			if (dependency.getKind() == ModDependency.Kind.BREAKS
					&& ranges.stream().anyMatch(r -> VersionPredicate.matchesStrictly(r, running))) {
				return "it breaks " + dependency.getModId() + " " + range + ", which includes " + running;
			}
		}
		return null;
	}

	/** A Fabric mod's {@code depends}, as requirements. */
	public static List<Requirement> requirementsOf(ModMetadata fabric) {
		if (fabric == null) return List.of();
		List<Requirement> out = new ArrayList<>();
		for (ModDependency dependency : fabric.getDependencies()) {
			if (dependency.getKind() != ModDependency.Kind.DEPENDS) continue;
			List<String> ranges = ranges(dependency);
			out.add(new Requirement(fabric.getId(), dependency.getModId(), String.join(" || ", ranges),
					version -> ranges.stream().anyMatch(r -> VersionPredicate.matches(r, version))));
		}
		return out;
	}

	/** Any ecosystem's mandatory dependencies that apply on {@code side} ({@code null}: on every side). */
	public static List<Requirement> requirementsOf(DiscoveredMod mod, Side side) {
		List<Requirement> out = new ArrayList<>();
		for (UnifiedDependency dependency : mod.getDependencies()) {
			if (!dependency.isMandatory() || dependency.getModId() == null) continue;
			if (side != null && !dependency.appliesOn(side)) continue;
			out.add(new Requirement(mod.getId(), dependency.getModId(), String.valueOf(dependency.getVersionConstraint()),
					dependency::isSatisfiedBy));
		}
		return out;
	}

	/**
	 * What the jars the no-plan discovery does not read hard-require. That discovery walks {@code mods/} and Fabric
	 * {@code jars} only, so without this it would not know that a NeoForge mod needs a nested Fabric one. Two kinds
	 * count: every Forge-family mod in {@code forgeFamilyJars} and {@code jarJarChildren}, and the Fabric manifest of
	 * every jar in {@code jarJarChildren}, the jars the Forge-family walk took out of their parents. Such a Fabric jar
	 * is no Fabric container without a plan, but its classes are on the classpath, and before the rule it found its
	 * dependency there. An unreadable jar adds none.
	 */
	public static List<Requirement> requiredOutsideFabricDiscovery(List<Path> forgeFamilyJars, List<Path> jarJarChildren,
			Side side) {
		ForbricModDiscoverer discoverer = new ForbricModDiscoverer();
		List<Requirement> out = new ArrayList<>();
		List<Path> all = new ArrayList<>(forgeFamilyJars);
		for (Path child : jarJarChildren) if (!all.contains(child)) all.add(child);
		for (Path jar : all) {
			try {
				for (DiscoveredMod mod : discoverer.discoverJar(jar)) {
					if (mod.getEcosystem() != null && mod.getEcosystem().isForgeFamily()) out.addAll(requirementsOf(mod, side));
				}
				if (!jarJarChildren.contains(jar)) continue;
				try (ZipFile zip = new ZipFile(jar.toFile())) {
					ZipEntry manifest = zip.getEntry(FabricModDiscovery.MANIFEST);
					if (manifest != null) try (InputStream in = zip.getInputStream(manifest)) {
						out.addAll(requirementsOf(FabricModMetadataParser.read(in)));
					}
				}
			} catch (Exception unreadable) {
				Caught.warn(unreadable, "[Forbric/JiJ] could not read the requirements of %s; it adds none",
						jar.getFileName());
			}
		}
		return out;
	}

	/**
	 * Fabric Loader's verdict on the nested mods, as far as the kernel follows it.
	 *
	 * <p>Starts from {@code direct}, the nested mods whose own {@code minecraft}/{@code java} requirement fails, and
	 * adds every judged nested mod that cannot load without them (see {@link #propagate}). Then it keeps back from that
	 * set the providers of an id something that does load hard-requires, when nothing that loads meets the
	 * requirement: every left-out provider that meets it, or, when none does and nothing that loads provides the id at
	 * all, every left-out provider, which is what the kernel loaded before the rule. The WARN tells the two apart. The
	 * provider stays with the mod that needs it, and so does whatever bundles it on the way to a loaded parent. That
	 * repeats until nothing more is needed. A key kept back is never left out again, so it ends.
	 *
	 * <p>For a jar in {@code mods/} native refuses to start here. The kernel loads such a mod with a dependency missing
	 * too, so leaving its provider out would only take the dependency away.
	 *
	 * @param requiredElsewhere requirements of mods that load but are not candidates (the no-plan discovery's
	 *                          Forge-family mods)
	 */
	public static <K> Resolution<K> resolve(List<Candidate<K>> candidates, Map<K, String> direct,
			List<Requirement> requiredElsewhere) {
		Map<K, Candidate<K>> byKey = new LinkedHashMap<>();
		Map<String, List<Candidate<K>>> byId = new HashMap<>();
		for (Candidate<K> candidate : candidates) {
			byKey.put(candidate.key(), candidate);
			for (String id : candidate.provides().keySet()) byId.computeIfAbsent(id, ignored -> new ArrayList<>()).add(candidate);
		}
		Map<K, KeptBack> keptBack = new LinkedHashMap<>();
		Set<K> pinned = new HashSet<>();
		while (true) {
			Map<K, String> leftOut = new LinkedHashMap<>(direct);
			leftOut.keySet().removeAll(pinned);
			propagate(candidates, byId, leftOut, pinned);
			Set<K> loadable = loadable(candidates, leftOut);
			List<Requirement> needed = new ArrayList<>(requiredElsewhere);
			for (Candidate<K> candidate : candidates) if (loadable.contains(candidate.key())) needed.addAll(candidate.requires());
			boolean grew = false;
			for (Requirement requirement : needed) {
				if (requirement.id() == null || net.forbric.kernel.util.PlatformIds.provides(requirement.id())) continue;
				String wanted = ModPresence.spellingKey(requirement.id());
				List<Candidate<K>> providers = byId.getOrDefault(wanted, List.of());
				if (providers.stream().anyMatch(p -> loadable.contains(p.key()) && requirement.accepts().test(p.provides().get(wanted)))) continue;
				List<Candidate<K>> outside = providers.stream().filter(p -> !loadable.contains(p.key())).toList();
				List<Candidate<K>> meeting = outside.stream().filter(p -> requirement.accepts().test(p.provides().get(wanted))).toList();
				String need = requirement.by() + " requires " + requirement.id() + " " + requirement.range();
				List<Candidate<K>> keep;
				String because;
				if (!meeting.isEmpty()) {
					keep = meeting;
					because = need + ", and nothing else installed meets that";
				} else if (outside.size() == providers.size()) {
					// No build installed meets it, and none would load: keep what there is, as the kernel did before
					// the rule. DependencyAudit still reports the version the dependent did not get.
					keep = outside;
					because = need + "; no installed build meets that, and no other " + requirement.id() + " would load";
				} else {
					continue;
				}
				for (Candidate<K> provider : keep) grew |= keepBack(provider, because, byKey, leftOut, loadable, pinned, keptBack);
			}
			if (!grew) return new Resolution<>(leftOut, keptBack);
		}
	}

	/** Pins {@code candidate}, and a chain of its bundlers up to one that loads, so that it is reachable. */
	private static <K> boolean keepBack(Candidate<K> candidate, String because, Map<K, Candidate<K>> byKey,
			Map<K, String> leftOut, Set<K> loadable, Set<K> pinned, Map<K, KeptBack> keptBack) {
		if (!pinned.add(candidate.key())) return false;
		String reason = leftOut.get(candidate.key());
		if (reason != null) keptBack.put(candidate.key(), new KeptBack(reason, because));
		if (candidate.root() || candidate.parents().stream().anyMatch(loadable::contains)) return true;
		for (K parent : candidate.parents()) {
			Candidate<K> bundler = byKey.get(parent);
			if (bundler == null || pinned.contains(parent)) continue;
			keepBack(bundler, "it bundles " + candidate.id() + " (" + because + ")", byKey, leftOut, loadable, pinned, keptBack);
			break;
		}
		return true;
	}

	/** Roots, and every candidate not left out that a loadable candidate bundles. */
	private static <K> Set<K> loadable(List<Candidate<K>> candidates, Map<K, String> leftOut) {
		Map<K, List<Candidate<K>>> children = new HashMap<>();
		Deque<K> work = new ArrayDeque<>();
		Set<K> loadable = new HashSet<>();
		for (Candidate<K> candidate : candidates) {
			for (K parent : candidate.parents()) children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(candidate);
			if (candidate.root() && loadable.add(candidate.key())) work.add(candidate.key());
		}
		while (!work.isEmpty()) {
			for (Candidate<K> child : children.getOrDefault(work.remove(), List.of())) {
				if (!leftOut.containsKey(child.key()) && loadable.add(child.key())) work.add(child.key());
			}
		}
		return loadable;
	}

	/**
	 * Adds to {@code leftOut}, until nothing changes, every judged nested Fabric candidate that cannot load without the
	 * ones already in it: one whose parents are all left out, and one with a hard dependency that installed candidates
	 * meet, but only left-out ones. {@code pinned} candidates are never added.
	 */
	private static <K> void propagate(List<Candidate<K>> candidates, Map<String, List<Candidate<K>>> byId,
			Map<K, String> leftOut, Set<K> pinned) {
		if (!enabled()) return;
		for (boolean changed = true; changed;) {
			changed = false;
			for (Candidate<K> candidate : candidates) {
				if (!candidate.judged() || candidate.fabric() == null || leftOut.containsKey(candidate.key())
						|| pinned.contains(candidate.key()) || !judged(candidate.fabric().getId())) continue;
				String reason = !candidate.parents().isEmpty() && leftOut.keySet().containsAll(candidate.parents())
						? "only mods left out bundle it" : null;
				for (ModDependency dependency : candidate.fabric().getDependencies()) {
					if (reason != null) break;
					if (dependency.getKind() != ModDependency.Kind.DEPENDS || net.forbric.kernel.util.PlatformIds.provides(dependency.getModId())) {
						continue;
					}
					List<String> ranges = ranges(dependency);
					String wanted = ModPresence.spellingKey(dependency.getModId());
					List<K> meeting = new ArrayList<>();
					for (Candidate<K> other : byId.getOrDefault(wanted, List.of())) {
						String version = other.provides().get(wanted);
						if (ranges.stream().anyMatch(r -> VersionPredicate.matches(r, version))) meeting.add(other.key());
					}
					if (!meeting.isEmpty() && leftOut.keySet().containsAll(meeting)) {
						reason = "it requires " + dependency.getModId() + " " + String.join(" || ", ranges)
								+ ", and every installed build of that is left out";
					}
				}
				if (reason != null) {
					leftOut.put(candidate.key(), reason);
					changed = true;
				}
			}
		}
	}

	/** The one line each left-out nested mod gets. */
	public static void log(ModMetadata metadata, Object parent, String reason) {
		ForbricLog.info("[Forbric/JiJ] nested %s %s in %s left out: %s — Fabric Loader does not load it either",
				metadata.getId(), version(metadata), parent, reason);
	}

	/** The one line each mod kept back from the rule gets. */
	public static void logKeptBack(ModMetadata metadata, Object parent, KeptBack kept) {
		ForbricLog.warn("[Forbric/JiJ] nested %s %s in %s loaded although Fabric Loader would leave it out (%s): %s",
				metadata.getId(), version(metadata), parent, kept.reason(), kept.because());
	}

	private static String version(ModMetadata metadata) {
		return metadata.getVersion() == null ? "?" : metadata.getVersion().getFriendlyString();
	}

	/** The declared alternatives, OR-joined by Fabric; none declared is {@code *}. */
	private static List<String> ranges(ModDependency dependency) {
		if (dependency instanceof KernelMetadataSupport.SimpleModDependency simple && !simple.getConstraints().isEmpty()) {
			return simple.getConstraints();
		}
		return List.of("*");
	}
}
