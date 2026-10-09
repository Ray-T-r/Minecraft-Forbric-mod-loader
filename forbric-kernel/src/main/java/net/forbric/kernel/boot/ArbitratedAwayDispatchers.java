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
 *     {@code preLaunch} is.</li>
 * </ul>
 * A dispatch it cannot derive (the call takes arguments, or only a callback reaches it) is named, not guessed at.
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
	 */
	public record Orphan(String library, Path losingBuild, Dispatch dispatch) {
		public String key() {
			return dispatch.key();
		}
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
		List<Orphan> found = derive(dupes.rescueJars(), dupes.ownerByModId(), declaredKeys);
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
		Set<String> custom = new LinkedHashSet<>(declaredKeys);
		custom.removeAll(LIFECYCLE_KEYS);
		if (custom.isEmpty() || losingBuilds.isEmpty()) return List.of();

		Map<String, Orphan> byKey = new LinkedHashMap<>();
		for (Path jar : new TreeSet<>(losingBuilds)) {
			KernelModMetadata metadata = fabricMetadata(jar);
			if (metadata == null) continue; // only a Fabric build can have dispatched a Fabric key
			List<Path> winning = winnersOf(metadata, jar, winners);
			if (winning.isEmpty()) continue; // not installed as any other build: nothing was taken away
			List<Dispatch> dispatches;
			try {
				dispatches = EntrypointDispatchScan.scan(jar, custom);
			} catch (IOException | RuntimeException unreadable) {
				ForbricLog.warn("[Forbric/DupeId] could not read which entrypoint keys %s's losing build %s dispatches: %s",
						metadata.getId(), jar.getFileName(), String.valueOf(unreadable));
				continue;
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
				byKey.put(dispatch.key(), new Orphan(metadata.getId(), jar, dispatch));
				ForbricLog.info("[Forbric/DupeId] %s: its losing build %s is the only code that dispatches the '%s' "
						+ "entrypoints (%s.%s, from %s, reached from its %s) — the kernel dispatches them in its place",
						metadata.getId(), jar.getFileName(), dispatch.key(), simpleName(dispatch.type()), dispatch.method(),
						dispatch.site(), dispatch.phases().stream().map(Phase::key).toList());
			}
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

	private static String simpleName(String internalName) {
		return internalName.substring(internalName.lastIndexOf('/') + 1);
	}
}
