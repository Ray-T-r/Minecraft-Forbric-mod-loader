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

package net.forbric.loader.impl.launch;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.fabricmc.loader.impl.util.SystemProperties;

import net.forbric.loader.impl.access.AccessTransformer;
import net.forbric.loader.impl.access.AccessTransformerParser;
import net.forbric.loader.impl.access.AtDirective;
import net.forbric.loader.impl.discovery.ForbricModDiscoverer;
import net.forbric.loader.impl.mapping.ForbricCache;
import net.forbric.loader.impl.mapping.ForbricMappings;
import net.forbric.loader.impl.mapping.ForgeModRemapper;
import net.forbric.loader.impl.metadata.DiscoveredMod;
import net.forbric.loader.impl.metadata.ModEcosystem;
import net.forbric.loader.impl.metadata.UnifiedDependency;
import net.forbric.loader.impl.transformer.ForbricMergedBaseCompatTransformer;
import net.forbric.loader.impl.transformer.ForbricTransformBridge;
import net.forbric.loader.impl.transformer.TransformChain;
import net.forbric.loader.impl.transformer.TransformContext;
import net.forbric.loader.impl.transformer.TransformPhase;
import net.forbric.loader.impl.util.ForbricLog;

/**
 * Forbric's pre-launch step. It runs the {@linkplain ForbricModDiscoverer unified discovery pass}, installs
 * the unified transform chain into the substrate's pre-Mixin byte path, and — when the mapping inputs are
 * available — automatically remaps + wraps every discovered Forge/NeoForge mod and hands them to the
 * substrate via {@code fabric.addMods}, plus applies their Access Transformers. It then returns control to
 * Knot to boot the game. All Forge setup is best-effort: any failure logs and falls back to a Fabric-only
 * boot rather than aborting.
 *
 * <p>Forge support is driven by system properties so the launch environment can supply the (non-bundled)
 * mapping inputs:
 * <ul>
 *   <li>{@code forbric.intermediary} — path to the Fabric intermediary mappings (tiny) for this MC version,</li>
 *   <li>{@code forbric.mojmap} — path to the Mojang client mappings (ProGuard) for this MC version,</li>
 *   <li>{@code forbric.gameJar} — path to the (obfuscated) vanilla game jar.</li>
 * </ul>
 */
public final class ForbricBootstrap {
	public static final String VERSION = "0.1.0";

	private ForbricBootstrap() {
	}

	public static void run(String[] args, String side) {
		ForbricLog.info("======================================================");
		ForbricLog.info(" Forbric Loader " + VERSION + " (" + side + ") — unified Fabric + Forge");
		ForbricLog.info("======================================================");

		LegacyAncestorContracts.verifyLaunchInputs(side);
		Path gameDir = resolveGameDir(args);
		Path mods = gameDir.resolve("mods");
		EnvType envType = "server".equalsIgnoreCase(side) ? EnvType.SERVER : EnvType.CLIENT;

		try {
			List<DiscoveredMod> all = new ForbricModDiscoverer().discover(mods);

			// Which Forge family this instance's game base carries (FORGE or NEOFORGE): the two bases patch
			// vanilla differently and are mutually exclusive per instance; Fabric layers on either. Explicit
			// via -Dforbric.forgeFamily (the installer writes it), else probed from the staged runtime jar's
			// injected identity (mod id "forge" / "neoforge").
			// Which Forge families are active this instance: {FORGE}, {NEOFORGE}, or BOTH on the tri-in-one merged
			// base (both runtimes staged, or -Dforbric.forgeFamily=both). Mods of any active family are welcomed.
			java.util.Set<ModEcosystem> activeFamilies = resolveActiveFamilies(all);
			// A representative family for single-ecosystem diagnostics — the wrong-family skip below only fires when
			// exactly one family is active (in BOTH mode every Forge-family mod is welcome, so it never fires).
			ModEcosystem family = activeFamilies.size() == 1 ? activeFamilies.iterator().next() : ModEcosystem.FORGE;
			java.util.Set<String> activeFamilySources = new java.util.HashSet<>();
			for (DiscoveredMod mod : all) {
				if (activeFamilies.contains(mod.getEcosystem())) activeFamilySources.add(mod.getSource());
			}

			// A jar carrying BOTH manifests is usually already substrate-loadable via its fabric.mod.json
			// (the merged forge-runtime.jar, pre-wrapped mods, genuine dual-loader builds) — preparing its
			// FORGE identity would duplicate the whole jar. EXCEPTION: "wrongloader traps" — Forge-only
			// builds ship a fake fabric.mod.json (unparseable version, an entrypoint that just throws) to
			// fail fast when dropped into a Fabric loader. Those we suppress on the Fabric side
			// (-Dforbric.suppressMods, substrate patch 0006) and load the REAL Forge identity instead.
			java.util.Set<String> fabricSources = new java.util.HashSet<>();
			java.util.Map<String, DiscoveredMod> fabricBySource = new java.util.HashMap<>();
			java.util.Map<String, DiscoveredMod> dubiousFabricBySource = new java.util.HashMap<>();
			for (DiscoveredMod mod : all) {
				if (mod.getEcosystem().isForgeFamily()) continue;
				boolean semverOk;
				try {
					net.fabricmc.loader.api.SemanticVersion.parse(mod.getVersion());
					semverOk = true;
				} catch (Exception e) {
					semverOk = false;
				}
				if (semverOk) {
					fabricSources.add(mod.getSource());
					fabricBySource.put(mod.getSource(), mod);
				} else {
					dubiousFabricBySource.put(mod.getSource(), mod);
				}
			}

			List<DiscoveredMod> forge = new ArrayList<>();
			java.util.List<String> suppress = new ArrayList<>();
			java.util.List<String> suppressSources = new ArrayList<>();
			long fabric = 0;
			java.util.List<String> wrongFamilyWarned = new ArrayList<>();
			for (DiscoveredMod mod : all) {
				if (mod.getEcosystem().isForgeFamily()) {
					// The forge-runtime.jar / neoforge-runtime.jar (mod ids "forge"/"neoforge") IS the Knot-loaded
					// runtime — it must load through its own fabric identity, never Forge-wrapped. Same for any
					// jar Forbric already produced.
					if (("forge".equals(mod.getId()) || "neoforge".equals(mod.getId()))
							&& fabricSources.contains(mod.getSource())) {
						ForbricLog.warn("[Forbric] skipping Forge prep for %s (runtime jar, substrate-loadable)%n", mod.getId());
						continue;
					}

					// The other Forge family's identity: on a dual-toml (multiloader) jar the active family's
					// entry loads it, so the sibling is dropped silently; a mod with ONLY an INACTIVE family's
					// manifest cannot work on this game base — skip it honestly, naming the right profile. In BOTH
					// mode every Forge family is active, so this never fires.
					if (!activeFamilies.contains(mod.getEcosystem())) {
						if (!activeFamilySources.contains(mod.getSource()) && !wrongFamilyWarned.contains(mod.getId())) {
							wrongFamilyWarned.add(mod.getId());
							ForbricLog.warn("[Forbric] '%s' is a %s mod, but this instance runs the %s game base — "
									+ "skipping it. Install the mod's %s build here, or launch the forbric-%s-26.2 "
									+ "profile to load it.", mod.getId(), mod.getEcosystem().familyId(),
									family.familyId(), family.familyId(), mod.getEcosystem().familyId());
						}
						continue;
					}

					// Genuine multiloader "unimod": one jar shipping BOTH a real Fabric identity and this Forge
					// identity, with loader-specific mixins/ATs (e.g. collective_fabric.mixins.json vs
					// collective_forge.mixins.json) — and, unlike a wrongloader trap, the SAME mod id on both
					// sides. On the Forge-patched game base the Forge variant is the one written for this
					// bytecode, so prefer it: suppress the original jar (its Fabric identity would apply the
					// loader-wrong mixins) BY SOURCE — id-based suppression can't be used when both identities
					// share the id — and load the mod through the Forge lifecycle (re-wrapped under cache/).
					DiscoveredMod dual = fabricBySource.get(mod.getSource());
					if (dual != null) {
						String file = sourceFileName(mod.getSource());
						if (file != null && !suppressSources.contains(file)) {
							ForbricLog.warn("[Forbric] multiloader jar %s: preferring Forge identity on the Forge base, suppressing the Fabric identity in %s%n",
									mod.getId(), file);
							suppressSources.add(file);
						}
						forge.add(mod);
						continue;
					}

					DiscoveredMod trap = dubiousFabricBySource.get(mod.getSource());
					if (trap != null && !suppress.contains(trap.getId())) {
						ForbricLog.warn("[Forbric] suppressing wrongloader-trap fabric identity '%s' of Forge mod %s%n",
								trap.getId(), mod.getId());
						suppress.add(trap.getId());
					}
					forge.add(mod);
				} else {
					fabric++;
				}
			}
			if (!suppress.isEmpty()) {
				String existing = System.getProperty("forbric.suppressMods", "");
				System.setProperty("forbric.suppressMods",
						existing.isEmpty() ? String.join(",", suppress) : existing + "," + String.join(",", suppress));
			}
			if (!suppressSources.isEmpty()) {
				String existing = System.getProperty("forbric.suppressModSources", "");
				System.setProperty("forbric.suppressModSources",
						existing.isEmpty() ? String.join(",", suppressSources) : existing + "," + String.join(",", suppressSources));
			}

			// Guest mixins retain their declared require/group/shadow contracts. Mixin checks each actual
			// target definition and reports incompatibility; this loader never grants an ecosystem-wide
			// zero-require/error downgrade or discards a renderer/lifecycle config by its name.
			if (all.stream().anyMatch(m -> m.getEcosystem().isForgeFamily())) {
				// A guest Forge/NeoForge mod pinned to a DIFFERENT MC version (e.g. xaerominimap-26.1.4, which itself
				// declares minecraft (1.21.10, 26.1.0) while we run 26.2) carries mixins whose @Shadow/@Inject anchors
				// target members that MC version renamed or removed. Per-mixin WARN-skip is NOT enough here: Mixin
				// abandons the ENTIRE target class when one mixin fails during context creation, so a load-bearing
				// co-located mixin from ANOTHER mod (e.g. fabric-rendering-v1 adding GuiRendererExtensions to
				// GuiRenderer) is dropped too, and a later cast to that interface throws ClassCastException. Such a mod
				// cannot function anyway, so suppress its mixin CONFIGS whole — they never enter any target's apply
				// batch — instead of relaxing per-mixin. Fail OPEN: suppress ONLY when the mod ITSELF declares it
				// excludes the running MC version; a missing constraint or any parse trouble keeps the mod, so a
				// compatible mod is never suppressed by mistake.
				String runningMc = System.getProperty("forbric.mcVersion", "26.2");
				java.util.Set<String> versionSuppressedModIds = new java.util.HashSet<>();
				java.util.LinkedHashSet<String> versionSuppressedConfigs = new java.util.LinkedHashSet<>();
				for (DiscoveredMod mod : all) {
					if (!mod.getEcosystem().isForgeFamily()) continue; // only Forge-family guests pin to an exact MC version
					String id = mod.getId();
					if (id.startsWith("forbric") || "forge".equals(id) || "neoforge".equals(id) || "minecraft".equals(id)) continue;
					if (!declaresMcIncompatibility(mod, runningMc)) continue;
					versionSuppressedModIds.add(id);
					versionSuppressedConfigs.addAll(mod.getMixinConfigs());
					ForbricLog.warn("[Forbric] '%s' declares it does not support Minecraft %s (version-incompatible mod); "
							+ "suppressing its mixin configs %s and excluding it from Forge-family mod loading — the mod "
							+ "is inert here, install a build for this MC version.", id, runningMc, mod.getMixinConfigs());
				}
				if (!versionSuppressedConfigs.isEmpty()) {
					String csv = String.join(",", versionSuppressedConfigs);
					String prev = System.getProperty("forbric.suppressMixinConfigs", "");
					System.setProperty("forbric.suppressMixinConfigs", prev.isEmpty() ? csv : prev + "," + csv);
				}
				// Same signal, loading side: publish the set so the FML discovery drivers keep these mods OUT of
				// ModSorter (one alien-version mod there aborts the whole ecosystem's LoadingModList — see
				// ForbricVersionGate) and the client asset wiring skips their packs. User-supplied entries append.
				if (!versionSuppressedModIds.isEmpty()) {
					String csv = String.join(",", versionSuppressedModIds);
					String prev = System.getProperty(net.forbric.loader.impl.util.ForbricVersionGate.PROPERTY, "");
					System.setProperty(net.forbric.loader.impl.util.ForbricVersionGate.PROPERTY,
							prev.isEmpty() ? csv : prev + "," + csv);
				}

			}

			ForbricLog.info("[Forbric] unified discovery in %s: %d mod(s) — %d Fabric, %d Forge%n",
					mods, all.size(), fabric, forge.size());
			for (DiscoveredMod mod : all) {
				ForbricLog.info("[Forbric]   - %-6s %s @ %s%n", mod.getEcosystem(), mod.getId(), mod.getVersion());
			}

			// Canonical runtime namespace: intermediary (1.21.x) by default, or "named" (Mojmap) for the
			// Mojmap-canonical path (MC 26.2+, where the game itself is Mojmap-named and no remap is needed).
			String runtimeNamespace = System.getProperty("forbric.runtimeNamespace", ForbricMappings.INTERMEDIARY);
			TransformChain chain = new TransformChain();
			chain.register(TransformPhase.RAW_PATCH, new ForbricMergedBaseCompatTransformer(), -1000);
			ForbricTransformBridge.install(chain, new TransformContext(envType, false, runtimeNamespace));

			if (!forge.isEmpty()) {
					// Mod ids present this boot (both raw and sanitized forms), so a Forge mod's mandatory dependency
					// on a mod that IS installed resolves, while a dependency with no provider is softened rather than
					// aborting the whole launch (see ForgeModRemapper.wrapAsFabricMod).
					java.util.Set<String> presentIds = new java.util.HashSet<>();
					for (DiscoveredMod pm : all) {
						if (pm.getId() == null) continue;
						// Wrong-family entries were skipped above and will NOT load — they are not providers.
						if (pm.getEcosystem().isForgeFamily() && !forge.contains(pm)) continue;
						String lc = pm.getId().toLowerCase();
						presentIds.add(lc);
						presentIds.add(lc.replaceAll("[^a-z0-9_]", "_"));
					}
					setupForgeSupport(forge, gameDir, chain, envType, presentIds, family);
			}
		} catch (IOException e) {
			ForbricLog.error("[Forbric] unified discovery failed", e);
		}
	}

	/**
	 * Forbric: {@code true} iff {@code mod} DECLARES a {@code minecraft} dependency whose version constraint the
	 * running MC version does not satisfy — i.e. the mod itself states it targets a different Minecraft. Used to
	 * suppress a version-incompatible guest's mixin configs whole (a per-mixin skip would poison co-located
	 * load-bearing mixins on shared targets). Fail-open: returns {@code false} on a missing constraint or any
	 * parse trouble, so a compatible mod is never suppressed by mistake.
	 */
	private static boolean declaresMcIncompatibility(DiscoveredMod mod, String runningMc) {
		for (UnifiedDependency dep : mod.getDependencies()) {
			if (!"minecraft".equals(dep.getModId())) continue;
			String constraint = dep.getVersionConstraint();
			if (constraint == null || constraint.isEmpty() || "*".equals(constraint)) return false;
			try {
				return !VersionPredicate.parse(constraint).test(Version.parse(runningMc));
			} catch (Exception e) {
				return false; // unparseable constraint or MC version — keep the mod (never a false positive)
			}
		}
		return false; // no declared minecraft constraint — nothing to judge
	}

	/** Best-effort: build the mapping spine + Mojmap game jar, auto-prepare Forge mods, and apply their ATs. */
	private static void setupForgeSupport(List<DiscoveredMod> forge, Path gameDir, TransformChain chain, EnvType envType,
			java.util.Set<String> presentIds, ModEcosystem family) {
		// Mojmap-canonical path (MC 26.2+): the game is already Mojmap-named, so Forge-family mods need NO remap —
		// just scan @Mod + wrap, and let the Knot-loaded runtime driver bring them up. Opt in with
		// -Dforbric.runtimeNamespace=named (no intermediary/mojmap mappings required).
		if (ForbricMappings.NAMED.equalsIgnoreCase(System.getProperty("forbric.runtimeNamespace"))) {
			setupForgeIdentity(forge, gameDir, chain, presentIds, family);
			return;
		}

		String intermediary = System.getProperty("forbric.intermediary");
		String mojmap = System.getProperty("forbric.mojmap");
		String gameJar = System.getProperty("forbric.gameJar");

		if (intermediary == null || mojmap == null || gameJar == null) {
			ForbricLog.warn("[Forbric] Forge mods detected; set -Dforbric.intermediary/-Dforbric.mojmap/-Dforbric.gameJar "
					+ "to auto-remap+load them, or -Dforbric.runtimeNamespace=named for Mojmap-canonical (26.2+). (Fabric mods boot now.)");
			return;
		}

		try {
			ForbricMappings mappings = ForbricMappings.load(Paths.get(intermediary), Paths.get(mojmap));

			ForbricCache cache = new ForbricCache(gameDir.resolve(".forbric").resolve("cache"));
			String mappingsKey = ForbricCache.key(Paths.get(intermediary), Paths.get(mojmap));

			// Mojmap game jar = the remap classpath for inheritance resolution (cached).
			Path mojmapGame = cache.resolve("minecraft", mappingsKey, ".named.jar");
			if (!ForbricCache.isCached(mojmapGame)) {
				ForgeModRemapper.remapJar(Paths.get(gameJar), mojmapGame,
						ForgeModRemapper.provider(mappings, ForbricMappings.OFFICIAL, ForbricMappings.NAMED), List.of());
			}

			// Apply Access Transformers (remapped named -> intermediary) on the unified ACCESS phase.
			applyAccessTransformers(forge, mappings, chain);

			// Remap + wrap each Forge mod and hand them to the substrate.
			ForbricForgeLoader loader = new ForbricForgeLoader(mappings, List.of(mojmapGame), cache, mappingsKey, presentIds);
			List<Path> prepared = loader.prepare(forge);

			if (!prepared.isEmpty()) {
				addMods(prepared);
				ForbricLog.info("[Forbric] handed %d auto-prepared Forge mod(s) to the substrate via %s%n",
						prepared.size(), SystemProperties.ADD_MODS);
			}
		} catch (Exception e) {
			ForbricLog.warn("[Forbric] Forge auto-load failed (booting Fabric-only)", e);
		}
	}

	/**
	 * Mojmap-canonical (MC 26.2+) Forge support: the game classes are already Mojmap-named, so each Forge-family
	 * mod is prepared with NO bytecode remap — just {@code @Mod} scan + a synthetic {@code fabric.mod.json}
	 * (carrying the {@code forbric:forgeClasses} keys) — then handed to Knot. The Knot-loaded Forge runtime driver
	 * ({@code ForbricMinecraftForgeRuntime} for traditional Forge) discovers those keys and brings the mods up. No
	 * intermediary/mojmap mappings and no game jar are needed here.
	 */
	private static void setupForgeIdentity(List<DiscoveredMod> forge, Path gameDir, TransformChain chain,
			java.util.Set<String> presentIds, ModEcosystem family) {
		try {
			// Access Transformers still apply on the Mojmap-canonical path — the game is already Mojmap-named,
			// so the mod's cfg names ARE the runtime names (no remap). Without this, a mod that widens a vanilla
			// member (e.g. TerraBlender's MultiNoiseBiomeSource.parameters()) hits IllegalAccessError at runtime.
			applyAccessTransformers(forge, null, chain);

			ForbricCache cache = new ForbricCache(gameDir.resolve(".forbric").resolve("cache"));

			// Partition by each mod's OWN family and prepare one wrap batch per family present: ForbricForgeLoader
			// stays single-family (its wrap cache key + nested-jar filter are per-family), and each mod's wrapped
			// identity is stamped with the correct ecosystem so the right Knot-loaded driver (traditional-Forge vs
			// NeoForge) claims it. On the tri-in-one merged base both families are present at once; single-ecosystem
			// instances have exactly one group, identical to before.
			java.util.Map<ModEcosystem, List<DiscoveredMod>> byFamily = new java.util.EnumMap<>(ModEcosystem.class);
			for (DiscoveredMod mod : forge) {
				byFamily.computeIfAbsent(mod.getEcosystem(), k -> new ArrayList<>()).add(mod);
			}
			for (java.util.Map.Entry<ModEcosystem, List<DiscoveredMod>> e : byFamily.entrySet()) {
				ForbricForgeLoader loader = ForbricForgeLoader.identity(cache, presentIds, e.getKey());
				List<Path> prepared = loader.prepare(e.getValue());
				if (!prepared.isEmpty()) {
					addMods(prepared);
					ForbricLog.info("[Forbric] handed %d Mojmap %s mod(s) to the substrate via %s%n",
							prepared.size(), e.getKey().familyId(), SystemProperties.ADD_MODS);
				}
			}
		} catch (Exception e) {
			ForbricLog.warn("[Forbric] Mojmap Forge auto-load failed (booting Fabric-only)", e);
		}
	}

	private static void applyAccessTransformers(List<DiscoveredMod> forge, ForbricMappings mappings, TransformChain chain) {
		List<AtDirective> directives = new ArrayList<>();

		for (DiscoveredMod mod : forge) {
			if (mod.getAccessTransformers().isEmpty()) continue;

			Path jar = Paths.get(mod.getSource());
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (String path : mod.getAccessTransformers()) {
					ZipEntry entry = zip.getEntry(path);
					if (entry == null) continue;

					try (Reader reader = new java.io.InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
						// Identity (Mojmap-canonical) mode: mappings == null, the cfg names are already runtime
						// names, so parse without remapping. Intermediary mode remaps named -> intermediary.
						directives.addAll(mappings == null
								? AccessTransformerParser.parse(reader)
								: AccessTransformerParser.parseAndRemap(reader, mappings));
					}
				}
			} catch (IOException e) {
				ForbricLog.error("[Forbric] could not read ATs from " + mod.getId(), e);
			}
		}

		if (!directives.isEmpty()) {
			chain.register(TransformPhase.ACCESS, new AccessTransformer(directives));
			ForbricLog.info("[Forbric] applied %d Access Transformer directive(s) to the game%n", directives.size());
		}
	}

	/** Appends paths to the substrate's {@code fabric.addMods} so Knot discovers them before it freezes. */
	private static void addMods(List<Path> jars) {
		StringBuilder value = new StringBuilder();
		String existing = System.getProperty(SystemProperties.ADD_MODS);
		if (existing != null && !existing.isEmpty()) value.append(existing);

		for (Path jar : jars) {
			if (value.length() > 0) value.append(java.io.File.pathSeparatorChar);
			value.append(jar.toAbsolutePath());
		}

		System.setProperty(SystemProperties.ADD_MODS, value.toString());
	}

	/**
	 * The Forge family/families this instance's game base carries. Explicit {@code -Dforbric.forgeFamily=forge|
	 * neoforge|both} wins (the installer writes it into the profile); otherwise probe the discovered mods for the
	 * staged runtime jars' injected identity (mod id {@code "forge"} / {@code "neoforge"}). BOTH runtimes staged
	 * → the tri-in-one merged base: return both families so mods of either load side by side. Defaults to a single
	 * {@link ModEcosystem#FORGE} (the legacy single-family behavior) when no signal is present.
	 */
	private static java.util.Set<ModEcosystem> resolveActiveFamilies(List<DiscoveredMod> all) {
		String prop = System.getProperty("forbric.forgeFamily", "").trim();
		if ("both".equalsIgnoreCase(prop)) return java.util.Set.of(ModEcosystem.FORGE, ModEcosystem.NEOFORGE);
		if ("neoforge".equalsIgnoreCase(prop)) return java.util.Set.of(ModEcosystem.NEOFORGE);
		if ("forge".equalsIgnoreCase(prop)) return java.util.Set.of(ModEcosystem.FORGE);
		if (!prop.isEmpty()) {
			ForbricLog.warn("[Forbric] unknown -Dforbric.forgeFamily='%s' (expected forge|neoforge|both) — probing instead", prop);
		}

		boolean neo = all.stream().anyMatch(m -> "neoforge".equals(m.getId()));
		boolean forge = all.stream().anyMatch(m -> "forge".equals(m.getId()));
		if (neo && forge) {
			// The tri-in-one merged base stages both runtimes deliberately (single-ecosystem installs stage one).
			return java.util.Set.of(ModEcosystem.FORGE, ModEcosystem.NEOFORGE);
		}
		if (neo) return java.util.Set.of(ModEcosystem.NEOFORGE);
		return java.util.Set.of(ModEcosystem.FORGE);
	}

	/** The file name of a DiscoveredMod source path (used to suppress the original jar by name in the substrate). */
	private static String sourceFileName(String source) {
		if (source == null || source.isEmpty()) return null;
		try {
			Path name = Paths.get(source).getFileName();
			return name == null ? null : name.toString();
		} catch (Exception e) {
			int slash = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
			return slash >= 0 ? source.substring(slash + 1) : source;
		}
	}

	private static Path resolveGameDir(String[] args) {
		for (int i = 0; i < args.length - 1; i++) {
			if (args[i].equals("--gameDir")) return Paths.get(args[i + 1]);
		}

		return Paths.get(System.getProperty("user.dir"));
	}
}
