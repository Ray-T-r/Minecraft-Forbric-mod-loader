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

package net.forbric.loader.impl.forge.runtime;

import java.lang.LayerInstantiationException;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Synthesizes the GAME {@link ModuleLayer} both Forge-family runtimes genuinely require: each Knot-staged
 * runtime/mod jar is derived as an <em>automatic module</em> and defined into one layer whose loader is Knot's
 * transforming classloader — classes keep Knot identity and Knot's transforms keep applying; they just
 * additionally become members of named modules (FML resolves mod containers' modules from this layer, and
 * discovers services via {@code ServiceLoader.load(layer, ...)}).
 *
 * <p><b>HARD ORDERING INVARIANT (probe-proven on the MinecraftForge side):</b> the layer must be defined before
 * ANY class from the layered jars is loaded by Knot. A package that already has a class in Knot's unnamed module
 * cannot join a named module — {@code defineModules} throws {@code LayerInstantiationException}. This class
 * therefore uses pure JDK + Forbric APIs only; all Forge-family reflection happens in the callers, afterwards.
 */
public final class ForbricGameLayer {
	private ForbricGameLayer() {
	}

	/** The synthesized layer plus the jars that actually joined it (underivable ones are dropped, loudly). */
	public record Result(ModuleLayer layer, List<Path> jars) {
	}

	private static volatile Result shared;

	/**
	 * The ONE shared GAME module layer, built from EVERY Forge-family jar present (both the traditional-Forge and
	 * NeoForge runtimes + every wrapped mod of either family, if both happen to be staged in the same instance —
	 * the tri-in-one case). Memoized: the first caller (whichever ecosystem driver runs first at preLaunch) does
	 * the real work; later callers get the same {@link ModuleLayer} back.
	 *
	 * <p>This must be ONE shared layer, not one per ecosystem: JPMS forbids defining two modules with the same
	 * name to the same class loader, and both ecosystems' FML need an (identically-named) empty {@code minecraft}
	 * module in {@code layer.findModule("minecraft")} — two independent {@code defineModules} calls, each adding
	 * its own "minecraft" module to the shared Knot classloader, throw {@code IllegalArgumentException: Module
	 * minecraft is already defined} on the second call (proven empirically running both drivers together).
	 *
	 * <p>Per-ecosystem discovery still uses its OWN jar subset (via each driver's own jar-collection) to build its
	 * {@code ModFile}s — only the JPMS layer itself is shared, since a module reachable from the shared layer is
	 * visible to whichever ecosystem's {@code FMLModContainer.layer.findModule(...)} looks it up.
	 */
	public static synchronized Result defineShared(ClassLoader knotCl) {
		// Only a SUCCESSFUL (non-null) layer is memoized: a failed synthesis must never be cached as if it
		// succeeded (the old bug — a Result(null,[]) cached here made the second driver "reuse" a null layer and
		// skip its whole FML wiring too). On a null result we fall through and let the next caller re-attempt.
		if (shared != null && shared.layer() != null) {
			ForbricLog.info("[Forbric/GameLayer] reusing already-synthesized shared GAME module layer");
			return shared;
		}
		List<Path> jars = collectAllForgeFamilyJars();
		Result result = define(knotCl, jars, List.of("minecraft"), "[Forbric/GameLayer]");
		if (result.layer() != null) shared = result;
		return result;
	}

	/**
	 * Every Knot-staged Forge-family jar (either ecosystem): the "forge"/"neoforge" runtime mods, and every
	 * wrapped mod carrying the {@code forbric:forgeClasses}/{@code forbric:forgeClass} custom key (regardless of
	 * its {@code forbric:ecosystem} stamp — the shared layer needs modules for BOTH ecosystems' mods when both
	 * are present).
	 */
	private static List<Path> collectAllForgeFamilyJars() {
		List<Path> jars = new ArrayList<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			boolean isRuntime = "forge".equals(id) || "neoforge".equals(id);
			boolean isWrapped = mod.getMetadata().containsCustomValue("forbric:forgeClasses")
					|| mod.getMetadata().containsCustomValue("forbric:forgeClass");
			if (!isRuntime && !isWrapped) continue;

			if (mod.getOrigin().getKind() != ModOrigin.Kind.PATH) {
				ForbricLog.warn("[Forbric/GameLayer] skipping non-path origin for mod '" + id + "' (" + mod.getOrigin() + ")");
				continue;
			}
			for (Path p : mod.getOrigin().getPaths()) {
				if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) {
					jars.add(p);
				}
			}
		}
		return jars;
	}

	/**
	 * Derives automatic modules from {@code jars} (dropping any whose descriptor cannot be derived, loudly) plus
	 * one empty, classless module per name in {@code emptyModules} (FML's minecraft language provider asserts
	 * {@code layer.findModule("minecraft")} even though the game classes stay on the flat Knot path), and defines
	 * them all with {@code knotCl} as loader. Returns a {@code Result} with a null layer on failure (logged,
	 * non-fatal — headless registration paths do not need the layer).
	 */
	public static Result define(ClassLoader knotCl, List<Path> jars, List<String> emptyModules, String logTag) {
		// Belt-and-braces: a jar whose automatic-module descriptor cannot be derived (bad filename,
		// dangling shaded service entries the wrap-time prune missed, ...) must not take the whole
		// layer down - derive each individually and drop offenders loudly.
		List<Path> derivable = new ArrayList<>();
		for (Path jar : jars) {
			try {
				ModuleFinder.of(jar).findAll();
				derivable.add(jar);
			} catch (Throwable t) {
				ForbricLog.warn(logTag + " EXCLUDING " + jar.getFileName()
						+ " from the module layer (descriptor underivable; its FML container will fail)", t);
			}
		}

		// Pre-exclude jars whose package is ALREADY defined in Knot's unnamed module — a class from that package
		// was loaded before preLaunch (typically a mod's mixin-config plugin instantiated during mixin config
		// parse). An automatic module cannot claim a package the unnamed module already owns, and including even
		// ONE such jar fails the WHOLE ModuleLayer.defineModules with LayerInstantiationException. Dropping the
		// offender lets the layer synthesize for every other Forge mod (the dropped mod's own FML container
		// degrades, but a null layer for EVERYONE — which skips ALL genuine-FML wiring, incl. gamePath seeding —
		// is far worse). This is the primary probe-based guard; the retry loop below nets any collision the probe
		// misses (e.g. a package first defined between here and defineModules).
		Set<String> knotOwned = new HashSet<>();
		for (Package p : knotCl.getDefinedPackages()) knotOwned.add(p.getName());
		List<Path> layerJars = new ArrayList<>();
		for (Path jar : derivable) {
			String clash = firstClashingPackage(jar, knotOwned);
			if (clash != null) {
				ForbricLog.warn(logTag + " DROPPING " + jar.getFileName() + " from the GAME layer — package "
						+ clash + " already loaded into the unnamed module before preLaunch (its FML container will"
						+ " degrade, but the layer can now synthesize for the other mods)");
			} else {
				layerJars.add(jar);
			}
		}

		// Build the layer; on a residual LayerInstantiationException, drop the named offending module and retry.
		while (true) {
			try {
				return buildLayer(knotCl, layerJars, emptyModules, logTag);
			} catch (LayerInstantiationException e) {
				String badModule = offendingModuleName(e.getMessage());
				Path dropped = badModule == null ? null : removeByModuleName(layerJars, badModule);
				if (dropped == null) {
					ForbricLog.error(logTag + " GAME layer FAILED - unrecoverable ordering break (could not map the"
							+ " offending module to a layered jar): " + e.getMessage());
					return new Result(null, List.of());
				}
				ForbricLog.warn(logTag + " DROPPING " + dropped.getFileName() + " from the GAME layer — module "
						+ badModule + " has a package already in the unnamed module (retrying without it): "
						+ e.getMessage());
			} catch (Throwable t) {
				ForbricLog.error(logTag + " GAME layer synthesis failed", t);
				return new Result(null, List.of());
			}
		}
	}

	/** The core layer build (extracted for the drop-and-retry loop); throws {@link LayerInstantiationException}. */
	private static Result buildLayer(ClassLoader knotCl, List<Path> jars, List<String> emptyModules, String logTag) {
		ModuleFinder finder = ModuleFinder.of(jars.toArray(new Path[0]));
		for (String empty : emptyModules) {
			finder = ModuleFinder.compose(finder, emptyModuleFinder(empty));
		}

		Set<String> names = finder.findAll().stream()
				.map(ref -> ref.descriptor().name())
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Configuration cfg = ModuleLayer.boot().configuration()
				.resolve(finder, ModuleFinder.of(), names);
		ModuleLayer.Controller controller =
				ModuleLayer.defineModules(cfg, List.of(ModuleLayer.boot()), name -> knotCl);

		// Grant every synthetic-layer module read access to the SYSTEM classpath's unnamed module. These are
		// AUTOMATIC modules (no module-info), so each auto-reads only the unnamed module of ITS OWN loader
		// (Knot's) — NOT the system loader's, where the vanilla -cp libraries (log4j, guava, …) are defined.
		// Once class-load ordering lets the SYSTEM loader define such a library first (order-dependent, surfaced
		// the moment content mods shift resolution), a runtime-module <clinit> touching it IllegalAccessErrors:
		// e.g. ForgeRegistry.<clinit> -> MarkerManager "module net.minecraftforge.forge does not read unnamed
		// module" during Bootstrap. Belt-and-braces: also (re)add Knot's own unnamed module.
		Module systemUnnamed = ClassLoader.getSystemClassLoader().getUnnamedModule();
		Module knotUnnamed = knotCl.getUnnamedModule();
		for (Module m : controller.layer().modules()) {
			controller.addReads(m, systemUnnamed);
			if (knotUnnamed != systemUnnamed) controller.addReads(m, knotUnnamed);
		}
		ForbricLog.info(logTag + " synthesized GAME module layer: " + names.size()
				+ " automatic module(s) on the Knot classloader " + names
				+ " (+reads system/knot unnamed modules for -cp libraries)");
		return new Result(controller.layer(), jars);
	}

	/** First package of {@code jar}'s automatic module already present in {@code owned}, or null if none clash. */
	private static String firstClashingPackage(Path jar, Set<String> owned) {
		try {
			for (java.lang.module.ModuleReference ref : ModuleFinder.of(jar).findAll()) {
				for (String pkg : ref.descriptor().packages()) {
					if (owned.contains(pkg)) return pkg;
				}
			}
		} catch (Throwable ignore) {
			// derivability was already checked above; treat an unreadable descriptor here as no-clash.
		}
		return null;
	}

	/** Parse the module name from a "... for module &lt;name&gt; is already in the unnamed module" message. */
	private static String offendingModuleName(String message) {
		if (message == null) return null;
		int i = message.indexOf("for module ");
		if (i < 0) return null;
		int start = i + "for module ".length();
		int end = message.indexOf(' ', start);
		String name = (end > start ? message.substring(start, end) : message.substring(start)).trim();
		return name.isEmpty() ? null : name;
	}

	/** Remove and return the jar in {@code jars} whose automatic module name equals {@code moduleName}. */
	private static Path removeByModuleName(List<Path> jars, String moduleName) {
		for (java.util.Iterator<Path> it = jars.iterator(); it.hasNext(); ) {
			Path jar = it.next();
			try {
				for (java.lang.module.ModuleReference ref : ModuleFinder.of(jar).findAll()) {
					if (moduleName.equals(ref.descriptor().name())) {
						it.remove();
						return jar;
					}
				}
			} catch (Throwable ignore) {
				// unreadable here (shouldn't happen post-derivability-check) — skip it.
			}
		}
		return null;
	}

	/** A finder yielding one empty, open, classless module — see the "minecraft" note in {@link #define}. */
	private static ModuleFinder emptyModuleFinder(String name) {
		java.lang.module.ModuleDescriptor descriptor = java.lang.module.ModuleDescriptor.newOpenModule(name).build();
		java.lang.module.ModuleReference ref = new java.lang.module.ModuleReference(descriptor, null) {
			@Override
			public java.lang.module.ModuleReader open() {
				return new java.lang.module.ModuleReader() {
					@Override
					public java.util.Optional<java.net.URI> find(String n) {
						return java.util.Optional.empty();
					}

					@Override
					public java.util.stream.Stream<String> list() {
						return java.util.stream.Stream.empty();
					}

					@Override
					public void close() {
					}
				};
			}
		};
		return new ModuleFinder() {
			@Override
			public java.util.Optional<java.lang.module.ModuleReference> find(String n) {
				return name.equals(n) ? java.util.Optional.of(ref) : java.util.Optional.empty();
			}

			@Override
			public Set<java.lang.module.ModuleReference> findAll() {
				return Set.of(ref);
			}
		};
	}
}
