/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.fabric.EntrypointDispatchScan;
import net.forbric.kernel.fabric.EntrypointDispatchScan.Dispatch;
import net.forbric.kernel.fabric.EntrypointDispatchScan.Phase;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.fabric.KernelModMetadata;
import net.forbric.kernel.util.ForbricLog;

/**
 * The custom entrypoint keys that only the build of a duplicated library the kernel did NOT load would dispatch.
 *
 * <p>The behavioural half of {@link ArbitratedAwayClasses}. When one library is installed as a Fabric build and as a
 * Forge-family build, arbitration loads one. If the Forge-family build wins, everything the Fabric build's own code
 * did is gone — and for most of it the winner does the same thing its own way. One thing it cannot do is read a
 * Fabric entrypoint key: a Fabric mod that integrates with the library declares, say, {@code "lib-config"} in its
 * {@code fabric.mod.json}, the library's Fabric build calls {@code FabricLoader.getEntrypoints("lib-config", ...)}
 * from its {@code preLaunch} and invokes each one, and the library's NeoForge build has no such call because NeoForge
 * mods declare the same thing from their constructor. So under arbitration the Fabric consumer's declaration is read
 * by nobody, its config is never registered with the (NeoForge) library, and it dies on first use.
 *
 * <p>This keeps that dispatch alive, derived entirely from the two builds:
 * <ul>
 * <li><b>the library is installed</b> — the losing build is the other ecosystem's build of a mod that did load (a
 *     rescue jar of the arbitration decision, with a winner of its own id). A library that is simply absent has no
 *     losing build, so a consumer's optional key stays undispatched, as on Fabric;</li>
 * <li><b>the losing build dispatches the key</b>, for an entrypoint type it declares itself — the protocol is its
 *     own, not another mod's that the other mod still dispatches ({@link EntrypointDispatchScan});</li>
 * <li><b>the winning build does not name the key</b> — not as a Fabric query and not otherwise: a winner that reads
 *     the same declaration through its own platform (a NeoForge build reading a {@code [modproperties]} entry of that
 *     name, which the kernel forwards from Fabric mods) lost nothing either. A key spelled like the library's own
 *     mod id is the exception: every build names its own id, so there only a Fabric Loader query counts;</li>
 * <li><b>what it invokes and when</b> are the losing build's own: the argument-free method it calls on each entrypoint,
 *     at the earliest kernel phase that matches the lifecycle entrypoint of its that reaches the dispatch. A dispatch
 *     reached from {@code preLaunch} runs once every Forge-family mod is constructed and before the first registry
 *     event: on the winner's platform that is when its own consumers have all declared themselves (in their
 *     constructors) and before the winner reads what was declared, and on Fabric it is before any {@code main}, as
 *     {@code preLaunch} is. A dispatch reached from {@code main}, {@code client} or {@code server} runs among that
 *     phase's entrypoints, where the losing build's own entrypoint of the phase would have run ({@link #place}):
 *     a consumer that runs after the library natively reads what the dispatch set up from its own
 *     {@code onInitialize}, and one that runs before it natively still does.</li>
 * </ul>
 * A dispatch it cannot derive (the call takes arguments, or only a callback reaches it) is named, not guessed at; so is
 * a key a loaded mod declares that the losing build holds as a constant while one of its queries asks Fabric Loader for
 * a key or type that is no constant there, since that query may be the one that read it.
 *
 * <p>The consumer still implements the losing build's entrypoint interface; that class is served from the losing
 * build by the class loader's last-resort rescue ({@code ForbricClassLoader.setRescueJars}), which is why the
 * precondition is exactly "is a rescue jar".
 */
public final class ArbitratedAwayDispatchers {
	/** Fabric's own phases: the kernel's drivers run these for every mod, so no losing build takes them away. */
	private static final Set<String> LIFECYCLE_KEYS = Set.of("main", "client", "server", "preLaunch");

	/**
	 * One key nothing loaded dispatches any more.
	 *
	 * @param library     the mod id of the library whose losing build dispatched it
	 * @param losingBuild that build's jar
	 * @param dispatch    how it dispatched it
	 * @param libraryIds  the library's id and every id its losing build {@code provides}: what a mod that requires it
	 *                    names
	 */
	public record Orphan(String library, Path losingBuild, Dispatch dispatch, Set<String> libraryIds) {
		public Orphan {
			Set<String> ids = new LinkedHashSet<>();
			ids.add(library);
			if (libraryIds != null) ids.addAll(libraryIds);
			libraryIds = java.util.Collections.unmodifiableSet(ids);
		}

		public Orphan(String library, Path losingBuild, Dispatch dispatch) {
			this(library, losingBuild, dispatch, Set.of());
		}

		public String key() {
			return dispatch.key();
		}
	}

	/**
	 * Where an orphan's dispatch runs among one phase's entrypoints.
	 *
	 * @param before the index, in the order the phase runs them, of the entrypoint it runs just before; the number of
	 *               entrypoints when it runs after all of them
	 * @param why    what decided it, for the log
	 */
	public record Place(int before, String why) {
	}

	private static volatile List<Orphan> orphans = List.of();
	private static final Set<String> DISPATCHED = ConcurrentHashMap.newKeySet();

	private ArbitratedAwayDispatchers() {
	}

	/**
	 * Derives the orphaned keys from this boot's arbitration and publishes them for the lifecycle phases.
	 *
	 * @param declaredKeys every entrypoint key a loaded Fabric mod declares; a key nobody declares has nothing to run
	 */
	public static List<Orphan> record(DuplicateModArbiter.Decision dupes, Set<String> declaredKeys) {
		return record(dupes, declaredKeys, Set.of());
	}

	/**
	 * The same, knowing which mod ids are installed.
	 *
	 * @param modIds every mod id (and {@code provides} alias) the kernel knows of, in any ecosystem: a key spelled like
	 *               one of them that is not the library's is that mod's own protocol, not something the library's
	 *               losing build took away
	 */
	public static List<Orphan> record(DuplicateModArbiter.Decision dupes, Set<String> declaredKeys, Set<String> modIds) {
		List<Orphan> found = derive(dupes.rescueJars(), dupes.ownerByModId(), declaredKeys, modIds);
		DISPATCHED.clear();
		orphans = found;
		return found;
	}

	/**
	 * The pure half.
	 *
	 * @param losingBuilds the jars arbitration superseded that are another ecosystem's build of a loaded mod
	 * @param winners      mod id → the jar that won it
	 */
	static List<Orphan> derive(Collection<Path> losingBuilds, Map<String, Path> winners, Set<String> declaredKeys) {
		return derive(losingBuilds, winners, declaredKeys, Set.of());
	}

	/** One declared key a losing build holds and may have read through a query this could not derive. */
	private record Suspect(KernelModMetadata metadata, Path jar, List<Path> winning, String key, List<String> sites) {
	}

	static List<Orphan> derive(Collection<Path> losingBuilds, Map<String, Path> winners, Set<String> declaredKeys,
			Set<String> modIds) {
		Set<String> custom = new LinkedHashSet<>(declaredKeys);
		custom.removeAll(LIFECYCLE_KEYS);
		if (custom.isEmpty() || losingBuilds.isEmpty()) return List.of();

		Map<String, Orphan> byKey = new LinkedHashMap<>();
		List<Suspect> suspects = new ArrayList<>();
		for (Path jar : new TreeSet<>(losingBuilds)) {
			KernelModMetadata metadata = fabricMetadata(jar);
			if (metadata == null) continue; // only a Fabric build can have dispatched a Fabric key
			List<Path> winning = winnersOf(metadata, jar, winners);
			if (winning.isEmpty()) continue; // not installed as any other build: nothing was taken away
			EntrypointDispatchScan.Report report;
			try {
				report = EntrypointDispatchScan.report(jar, custom);
			} catch (IOException | RuntimeException unreadable) {
				ForbricLog.warn("[Forbric/DupeId] could not read which entrypoint keys %s's losing build %s dispatches: %s",
						metadata.getId(), jar.getFileName(), String.valueOf(unreadable));
				continue;
			}
			List<Dispatch> dispatches = report.dispatches();
			if (!report.undetermined().isEmpty()) {
				// A query whose key or type this could not derive may be what read a declared key the build holds. A key
				// it does derive a dispatch of is accounted for by that dispatch, whatever became of it.
				Set<String> derived = new java.util.HashSet<>();
				for (Dispatch dispatch : dispatches) derived.add(dispatch.key());
				for (String key : report.named()) {
					if (!derived.contains(key)) suspects.add(new Suspect(metadata, jar, winning, key, report.undetermined()));
				}
			}
			for (Dispatch dispatch : dispatches) {
				if (!dispatch.ownType() || byKey.containsKey(dispatch.key())) continue;
				if (readByAny(winning, dispatch.key(), libraryIds(metadata))) {
					// It reads the key itself — through Fabric Loader, or through its own platform's metadata, the
					// way a NeoForge build reads a [modproperties] entry of the same name. Either way it is not lost.
					ForbricLog.debug("[Forbric/DupeId] %s: its winning build names '%s' itself", metadata.getId(), dispatch.key());
					continue;
				}
				String obstacle = dispatch.obstacle();
				if (obstacle != null) {
					reportUnreachable(metadata.getId(), jar, dispatch, obstacle);
					continue;
				}
				byKey.put(dispatch.key(), new Orphan(metadata.getId(), jar, dispatch, libraryIds(metadata)));
				ForbricLog.info("[Forbric/DupeId] %s: its losing build %s is the only code that dispatches the '%s' "
						+ "entrypoints (%s.%s, from %s, reached from its %s) — the kernel dispatches them in its place",
						metadata.getId(), jar.getFileName(), dispatch.key(), simpleName(dispatch.type()), dispatch.method(),
						dispatch.site(), dispatch.phases().stream().map(Phase::key).toList());
			}
		}

		Set<String> reported = new LinkedHashSet<>();
		for (Suspect suspect : suspects) {
			String key = suspect.key();
			Set<String> ids = libraryIds(suspect.metadata());
			// Another losing build's dispatch of it was derived, and the kernel runs it.
			if (byKey.containsKey(key) || reported.contains(key)) continue;
			// Spelled like another installed mod: that mod's own protocol, which it dispatches (or not) itself.
			if (modIds.contains(key) && !ids.contains(key)) continue;
			// Only a winner asking Fabric Loader for it settles that nothing was lost. That a winner merely holds the
			// string does not, here: with what the losing build did unknown, a constant both builds share (a common
			// module's) and a winner reading it its own way look the same — so that is evidence, not an exemption.
			if (dispatchedByAny(suspect.winning(), key)) {
				ForbricLog.debug("[Forbric/DupeId] %s: its winning build dispatches '%s' itself", suspect.metadata().getId(), key);
				continue;
			}
			reported.add(key);
			reportUndetermined(suspect, !ids.contains(key) && namedByAny(suspect.winning(), key));
		}
		return List.copyOf(byKey.values());
	}

	/** The orphans whose earliest phase on this side is {@code phase}, each handed out once per boot. */
	public static List<Orphan> due(Phase phase, boolean client) {
		List<Orphan> due = new ArrayList<>();
		for (Orphan orphan : orphans) {
			if (Phase.earliest(orphan.dispatch().phases(), client) == phase && DISPATCHED.add(orphan.key())) due.add(orphan);
		}
		return due;
	}

	/**
	 * Where, among one phase's entrypoints in the order the kernel runs them, the losing build's own entrypoint of that
	 * phase would have run — which is where its dispatch ran, since that entrypoint is what reached it.
	 *
	 * <p>In Fabric Loader's order, the kernel's default ({@link FabricLoadOrder}), that is where the library's id sorts:
	 * Fabric Loader hands every key's entrypoints back mod by mod, its mods sorted by id ({@code ModResolver
	 * .findCompatibleSet}), and dependencies play no part. So the dispatch runs just before the first entrypoint of a
	 * mod whose id sorts after the library's. A consumer after it natively saw what the dispatch set up in its own
	 * {@code onInitialize}, and sees it here; one before it natively ran first, and still does.
	 *
	 * <p>In the dependency order ({@code -Dforbric.fabricOrder=off}, {@link ModConstructionOrder}) a mod comes after
	 * everything it requires, so the library comes before the first entrypoint of a mod that requires it. With no such
	 * mod among the phase's entrypoints, where it falls among the others is not known: the dispatch runs before all of
	 * them, which leaves none of them reading what it sets up unset.
	 *
	 * @param providers the mod of each entrypoint of the phase, in the order they run
	 */
	public static Place place(Orphan orphan, List<? extends net.fabricmc.loader.api.metadata.ModMetadata> providers,
			boolean fabricOrder) {
		if (fabricOrder) {
			for (int i = 0; i < providers.size(); i++) {
				String id = providers.get(i).getId();
				if (id != null && id.compareTo(orphan.library()) > 0) {
					return new Place(i, "where " + orphan.library() + " sorts in Fabric Loader's order, by mod id");
				}
			}
			return new Place(providers.size(), "where " + orphan.library() + " sorts in Fabric Loader's order, by mod id: last");
		}
		for (int i = 0; i < providers.size(); i++) {
			if (requires(providers.get(i), orphan.libraryIds())) {
				return new Place(i, "before the first mod that requires " + orphan.library());
			}
		}
		return new Place(0, "before the phase: no mod in it requires " + orphan.library() + ", so where it falls is not known");
	}

	/** Whether {@code mod} declares a hard dependency on one of {@code ids}: what puts it after them in dependency order. */
	private static boolean requires(net.fabricmc.loader.api.metadata.ModMetadata mod, Set<String> ids) {
		Map<String, Boolean> byId = new LinkedHashMap<>();
		for (String id : ids) byId.put(id, Boolean.TRUE);
		for (net.fabricmc.loader.api.metadata.ModDependency dependency : mod.getDependencies()) {
			if (!dependency.getKind().isPositive() || dependency.getKind().isSoft()) continue;
			String target = dependency.getModId();
			if (ids.contains(target) || net.forbric.api.ModIds.underAnotherSpelling(target, byId) != null) return true;
		}
		return false;
	}

	/** Test seam: forget this boot's orphans. */
	static void reset() {
		orphans = List.of();
		DISPATCHED.clear();
	}

	private static KernelModMetadata fabricMetadata(Path jar) {
		if (jar == null || !Files.isRegularFile(jar)) return null;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null) return null;
			try (InputStream in = zip.getInputStream(entry)) {
				return FabricModMetadataParser.read(in);
			}
		} catch (IOException | RuntimeException unreadable) {
			return null;
		}
	}

	private static List<Path> winnersOf(KernelModMetadata metadata, Path jar, Map<String, Path> winners) {
		Set<String> ids = new LinkedHashSet<>();
		ids.add(metadata.getId());
		ids.addAll(metadata.getProvides());
		List<Path> winning = new ArrayList<>();
		for (String id : ids) {
			Path winner = winners.get(id);
			if (winner == null || winner.toAbsolutePath().equals(jar.toAbsolutePath()) || winning.contains(winner)) continue;
			winning.add(winner);
		}
		return winning;
	}

	/**
	 * Whether a winning build reads {@code key} itself: a string constant of that spelling anywhere in it or in what it
	 * bundles. A key spelled like one of the library's own mod ids is the exception — every build names its own id, so
	 * there only a Fabric Loader query for it counts.
	 */
	private static boolean readByAny(List<Path> jars, String key, Set<String> libraryIds) {
		for (Path jar : jars) {
			try {
				if (!Files.isRegularFile(jar)) continue;
				boolean reads = libraryIds.contains(key)
						? !EntrypointDispatchScan.scan(jar, Set.of(key)).isEmpty()
						: !EntrypointDispatchScan.namedIn(jar, Set.of(key)).isEmpty();
				if (reads) return true;
			} catch (IOException | RuntimeException unreadable) {
				// An unreadable winner cannot be shown to read it; the losing build's evidence stands.
			}
		}
		return false;
	}

	/** Whether a winning build itself asks Fabric Loader for {@code key}: then it was not lost, whatever the loser did. */
	private static boolean dispatchedByAny(List<Path> jars, String key) {
		for (Path jar : jars) {
			try {
				if (Files.isRegularFile(jar) && !EntrypointDispatchScan.scan(jar, Set.of(key)).isEmpty()) return true;
			} catch (IOException | RuntimeException unreadable) {
				// An unreadable winner cannot be shown to dispatch it.
			}
		}
		return false;
	}

	/** Whether a winning build, or a jar it bundles, holds {@code key} as a constant at all. */
	private static boolean namedByAny(List<Path> jars, String key) {
		for (Path jar : jars) {
			try {
				if (Files.isRegularFile(jar) && !EntrypointDispatchScan.namedIn(jar, Set.of(key)).isEmpty()) return true;
			} catch (IOException | RuntimeException unreadable) {
				// Nothing to add to the evidence.
			}
		}
		return false;
	}

	private static Set<String> libraryIds(KernelModMetadata metadata) {
		Set<String> ids = new LinkedHashSet<>();
		ids.add(metadata.getId());
		ids.addAll(metadata.getProvides());
		return ids;
	}

	private static void reportUnreachable(String library, Path jar, Dispatch dispatch, String obstacle) {
		ForbricLog.warn("[Forbric/DupeId] %s: its losing build %s dispatches the '%s' entrypoints and the build that "
				+ "loaded does not read that key, but the kernel cannot do it in its place: %s. A mod declaring '%s' goes without it; "
				+ "loading %s's Fabric build instead (forbric-mods.txt) restores it", library, jar.getFileName(),
				dispatch.key(), obstacle, dispatch.key(), library);
		CompatibilityFindings.record(new CompatibilityFinding("arbitration:entrypoint:" + dispatch.key(), library,
				"Mod integration", "ArbitratedAwayDispatchers", CompatibilityFinding.Confidence.SUSPECTED, false,
				"the '" + dispatch.key() + "' entrypoints are dispatched only by this library's build that was not loaded",
				List.of("losing build=" + jar.getFileName(), "site=" + dispatch.site(), obstacle)));
	}

	/** @param winnerNamesIt the build that loaded holds the key too: it may read it its own way, or only share it */
	private static void reportUndetermined(Suspect suspect, boolean winnerNamesIt) {
		String library = suspect.metadata().getId(), key = suspect.key();
		String obstacle = "it asks Fabric Loader for entrypoints with a key or type that is no constant there, so whether "
				+ "it dispatched '" + key + "' cannot be derived";
		String winner = winnerNamesIt
				? "the build that loaded holds '" + key + "' too, but does not ask Fabric Loader for it: it may read it its own way, or only share the constant"
				: "the build that loaded does not hold '" + key + "'";
		ForbricLog.warn("[Forbric/DupeId] %s: its losing build %s holds '%s', which a loaded mod declares entrypoints under, "
				+ "but %s (%s); %s. The kernel does not dispatch it in its place: a mod declaring '%s' may go without it, and "
				+ "loading %s's Fabric build instead (forbric-mods.txt) restores it", library, suspect.jar().getFileName(), key,
				obstacle, String.join(", ", suspect.sites()), winner, key, library);
		List<String> evidence = new ArrayList<>();
		evidence.add("losing build=" + suspect.jar().getFileName());
		for (String site : suspect.sites()) evidence.add("undetermined query=" + site);
		evidence.add(obstacle);
		evidence.add(winner);
		CompatibilityFindings.record(new CompatibilityFinding("arbitration:entrypoint:" + key, library,
				"Mod integration", "ArbitratedAwayDispatchers", CompatibilityFinding.Confidence.SUSPECTED, false,
				"the '" + key + "' entrypoints may be dispatched only by this library's build that was not loaded", evidence));
	}

	private static String simpleName(String internalName) {
		return internalName.substring(internalName.lastIndexOf('/') + 1);
	}
}
