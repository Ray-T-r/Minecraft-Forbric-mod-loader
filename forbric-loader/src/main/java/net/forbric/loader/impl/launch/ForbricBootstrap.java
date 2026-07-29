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
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.fabricmc.loader.impl.util.SystemProperties;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

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
	private static final java.util.Set<String> FORGE_OWNED_GUEST_MIXIN_TARGETS = java.util.Set.of(
			"net/minecraft/client/gui/GuiGraphicsExtractor",
			"net/minecraft/client/renderer/EndFlashState",
			"net/minecraft/client/renderer/GameRenderer",
			"net/minecraft/client/renderer/ItemInHandRenderer",
			"net/minecraft/client/renderer/LevelRenderer",
			"net/minecraft/client/renderer/LightmapRenderStateExtractor",
			"net/minecraft/client/renderer/OrderedSubmitNodeCollector",
			"net/minecraft/client/renderer/Projection",
			"net/minecraft/client/renderer/ScreenEffectRenderer",
			"net/minecraft/client/renderer/SkyRenderer",
			"net/minecraft/client/renderer/SubmitNodeCollection",
			"net/minecraft/client/renderer/SubmitNodeStorage",
			"net/minecraft/client/renderer/WeatherEffectRenderer",
			"net/minecraft/server/network/config/SynchronizeRegistriesTask",
			"net/minecraft/tags/TagNetworkSerialization");
	private static final java.util.List<String> FORGE_OWNED_GUEST_MIXIN_TARGET_PREFIXES = java.util.List.of(
			"net/minecraft/client/gui/render/",
			"net/minecraft/client/particle/",
			"net/minecraft/client/renderer/block/",
			"net/minecraft/client/renderer/blockentity/",
			"net/minecraft/client/renderer/chunk/",
			"net/minecraft/client/renderer/debug/",
			"net/minecraft/client/renderer/entity/",
			"net/minecraft/client/renderer/extract/",
			"net/minecraft/client/renderer/feature/",
			"net/minecraft/client/renderer/fog/",
			"net/minecraft/client/renderer/item/",
			"net/minecraft/client/renderer/rendertype/",
			"net/minecraft/client/renderer/state/",
			"net/minecraft/client/resources/model/");

	private ForbricBootstrap() {
	}

	public static void run(String[] args, String side) {
		ForbricLog.info("======================================================");
		ForbricLog.info(" Forbric Loader " + VERSION + " (" + side + ") — unified Fabric + Forge");
		ForbricLog.info("======================================================");

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

			// On the traditional-MinecraftForge base (mods/forge-runtime.jar, mod id "forge"), Forge's GameData
			// owns the registry lifecycle: freeze/unfreeze windows (Forbric bridge), id tracking, sync and
			// persistence. Fabric API's registry-sync module manages that same lifecycle for the vanilla base —
			// redundant here, and its mixin anchors do not survive Forge's binary patches (Bootstrap.bootStrap
			// no longer calls wrapStreams). Neutralize its configs via substrate patch 0007; user-supplied
			// -Dforbric.suppressMixinConfigs entries are preserved.
			if (all.stream().anyMatch(m -> "forge".equals(m.getId()) || "neoforge".equals(m.getId()))) {
				// Hard-suppressed for a SEMANTIC reason (not just a failed mixin): Forge's GameData owns the
				// registry lifecycle here — freeze/unfreeze windows, id tracking, sync, persistence — so
				// Fabric's registry-sync must never partially apply and double-manage it. Other Fabric API
				// modules whose mixins simply can't apply on the Forge-patched base are handled generically
				// by the best-effort mixin error handler (relaxMixinOverwrites, substrate patch 0007), which
				// skips the unpatchable mixin with a warning instead of needing a name here.
				String defaults = "fabric-registry-sync-v0.mixins.json,fabric-registry-sync-v0.client.mixins.json";
				String existing = System.getProperty("forbric.suppressMixinConfigs", "");
				System.setProperty("forbric.suppressMixinConfigs",
						existing.isEmpty() ? defaults : existing + "," + defaults);
				ForbricLog.warn("[Forbric] Forge base detected - suppressing GameData-owned Fabric mixin configs: " + defaults);

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

				// Guest mixin configs deep-hook vanilla internals that the merged base has moved or rewritten, so
				// an anchor no longer resolves and the mixin throws FATAL during prepare/apply (crash-to-desktop).
				// This bites two kinds of guest equally: (1) a Fabric mixin whose injector Forge's binary patches
				// invalidated, and (2) a Forge/NeoForge mod built for a DIFFERENT MC version (e.g. a 1.21.11 build
				// on the 26.2 base) whose @Shadow/@Inject targets a member that no longer exists. Both degrade the
				// same way. Two coordinated defenses, computed from the guest configs ACTUALLY present — NOT gated
				// on an umbrella "fabric-api" id, which the individually resolved / JiJ-nested Fabric API modules
				// (fabric-lifecycle-events-v1, …) do not carry:
				//   - forbric.relaxMixinOverwrites (substrate patch 0008 + the ForbricMixinErrorHandler registered
				//     in patch 0007): patch 0008 rewrites a matched config's defaultRequire -> 0 (and
				//     requireAnnotations -> false) so a DEFAULT-group injector whose anchor moved soft-skips; the
				//     error handler additionally WARN-skips a single mixin that hard-fails to apply (e.g. an
				//     @Shadow field the version-mismatched target lacks) while the rest of that config still applies.
				//   - forbric.downgradeInjectionErrors (ForbricMixinDowngrade, via substrate patch 0004): the
				//     backstop for EXPLICIT-require injectors that patch 0008 cannot relax — the target class is
				//     loaded WITHOUT that config's mixins instead of crashing.
				// Scoped to GUEST configs of ANY ecosystem: only the forge/neoforge runtime, forbric*, and minecraft
				// are excluded, so a real failure in the loader/runtime itself still crashes loudly.
				java.util.LinkedHashSet<String> guestConfigs = new java.util.LinkedHashSet<>();
				// Fabric API is usually one top-level jar with many JiJ module jars; Knot registers those nested
				// module mixin configs by name, but unified discovery only sees the umbrella source jar here.
				// Match the Fabric API config namespace generically instead of naming individual modules.
				guestConfigs.add("fabric-*");
				for (DiscoveredMod mod : all) {
					// Any guest mod, ANY ecosystem — a version-mismatched Forge/NeoForge mod (xaerominimap et al.)
					// hits the same unpatchable-anchor failure as a Fabric guest. Exclude only infrastructure so a
					// genuine failure in the loader/runtime still crashes loudly.
					String id = mod.getId();
					if (id.startsWith("forbric") || "forge".equals(id) || "neoforge".equals(id) || "minecraft".equals(id)) continue;
					if (versionSuppressedModIds.contains(id)) continue; // whole-config suppressed above; don't also relax it
					guestConfigs.addAll(mod.getMixinConfigs()); // exact config names of each discovered guest mod (any ecosystem)
				}
				java.util.LinkedHashSet<String> ownedMixins =
						forgeOwnedPipelineGuestMixins(all, versionSuppressedModIds);
				if (!ownedMixins.isEmpty()) {
					String csv = String.join(",", ownedMixins);
					String suppressMixinExisting = System.getProperty("forbric.suppressMixins", "");
					System.setProperty("forbric.suppressMixins",
							suppressMixinExisting.isEmpty() ? csv : suppressMixinExisting + "," + csv);
					ForbricLog.warn("[Forbric] Forge base detected - suppressing guest mixins that target "
							+ "Forge/NeoForge-owned renderer/model/registry-sync pipeline entries: " + csv);
				}
				if (!guestConfigs.isEmpty()) {
					String guestCsv = String.join(",", guestConfigs);
					String relaxExisting = System.getProperty("forbric.relaxMixinOverwrites", "");
					System.setProperty("forbric.relaxMixinOverwrites",
							relaxExisting.isEmpty() ? guestCsv : relaxExisting + "," + guestCsv);
					String downgradeExisting = System.getProperty("forbric.downgradeInjectionErrors", "");
					System.setProperty("forbric.downgradeInjectionErrors",
							downgradeExisting.isEmpty() ? guestCsv : downgradeExisting + "," + guestCsv);
					ForbricLog.warn("[Forbric] Forge base detected - discovered guest mixin configs will soft-skip "
							+ "failing injectors/mixins instead of crashing (relax + downgrade set: " + guestCsv + ")");
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

	/**
	 * Finds guest mixins that target vanilla classes whose runtime shape or lifecycle is owned by the Forge/NeoForge
	 * base. These mixins can apply cleanly yet still break later by feeding Fabric-typed state into NeoForge-typed
	 * constructors, model registries or registry/tag sync tasks, so they must be removed before Mixin registers them.
	 */
	private static LinkedHashSet<String> forgeOwnedPipelineGuestMixins(List<DiscoveredMod> all,
			java.util.Set<String> versionSuppressedModIds) {
		LinkedHashMap<String, LinkedHashSet<String>> configsBySource = new LinkedHashMap<>();
		for (DiscoveredMod mod : all) {
			String id = mod.getId();
			if (id == null || id.startsWith("forbric") || "forge".equals(id) || "neoforge".equals(id)
					|| "minecraft".equals(id)) {
				continue;
			}
			if (versionSuppressedModIds.contains(id)) continue;
			String source = mod.getSource();
			if (source == null || source.isEmpty()) continue;
			configsBySource.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).addAll(mod.getMixinConfigs());
		}

		LinkedHashSet<String> suppressions = new LinkedHashSet<>();
		for (Map.Entry<String, LinkedHashSet<String>> entry : configsBySource.entrySet()) {
			try {
				scanGuestMixinSource(Paths.get(entry.getKey()), entry.getValue(), suppressions);
			} catch (IOException | RuntimeException e) {
				ForbricLog.warn("[Forbric] could not scan guest mixin targets in " + sourceFileName(entry.getKey()), e);
			}
		}
		return suppressions;
	}

	private static void scanGuestMixinSource(Path source, java.util.Set<String> rootConfigs,
			LinkedHashSet<String> suppressions) throws IOException {
		if (!Files.isRegularFile(source)) return;
		try (ZipFile zip = new ZipFile(source.toFile())) {
			Map<String, byte[]> entries = relevantZipEntries(zip);
			collectForgeOwnedMixinSuppressions(entries, rootConfigs, suppressions);
			collectNestedForgeOwnedMixinSuppressions(entries, suppressions, 0);
		}
	}

	private static Map<String, byte[]> relevantZipEntries(ZipFile zip) throws IOException {
		LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
		java.util.Enumeration<? extends ZipEntry> it = zip.entries();
		while (it.hasMoreElements()) {
			ZipEntry entry = it.nextElement();
			if (entry.isDirectory()) continue;
			String name = entry.getName();
			if (!isRelevantMixinScanEntry(name)) continue;
			try (java.io.InputStream in = zip.getInputStream(entry)) {
				entries.put(name, in.readAllBytes());
			}
		}
		return entries;
	}

	private static Map<String, byte[]> relevantZipEntries(byte[] jarBytes) throws IOException {
		LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				if (entry.isDirectory()) continue;
				String name = entry.getName();
				if (!isRelevantMixinScanEntry(name)) continue;
				entries.put(name, in.readAllBytes());
			}
		}
		return entries;
	}

	private static boolean isRelevantMixinScanEntry(String name) {
		return isMixinConfigEntryName(name)
				|| name.endsWith(".class")
				|| name.equals("fabric.mod.json")
				|| name.equals("META-INF/MANIFEST.MF")
				|| isNestedFabricLoaderJar(name);
	}

	private static boolean isMixinConfigEntryName(String name) {
		int slash = name.lastIndexOf('/');
		String file = slash >= 0 ? name.substring(slash + 1) : name;
		return file.endsWith(".mixins.json")
				|| file.endsWith(".mixin.json")
				|| (file.startsWith("mixins.") && file.endsWith(".json"));
	}

	private static boolean isNestedFabricLoaderJar(String name) {
		return name.endsWith(".jar") && (name.startsWith("META-INF/jars/") || name.startsWith("META-INF/jij/"));
	}

	private static void collectNestedForgeOwnedMixinSuppressions(Map<String, byte[]> entries,
			LinkedHashSet<String> suppressions, int depth) throws IOException {
		if (depth >= 2) return;
		for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
			if (!isNestedFabricLoaderJar(entry.getKey())) continue;
			Map<String, byte[]> nested = relevantZipEntries(entry.getValue());
			java.util.Set<String> configs = nestedMixinConfigs(nested);
			collectForgeOwnedMixinSuppressions(nested, configs, suppressions);
			collectNestedForgeOwnedMixinSuppressions(nested, suppressions, depth + 1);
		}
	}

	private static java.util.Set<String> nestedMixinConfigs(Map<String, byte[]> entries) {
		LinkedHashSet<String> configs = new LinkedHashSet<>();
		byte[] fabric = entries.get("fabric.mod.json");
		if (fabric != null) {
			try (Reader reader = new InputStreamReader(new ByteArrayInputStream(fabric), StandardCharsets.UTF_8)) {
				UnmodifiableConfig json = JsonFormat.fancyInstance().createParser().parse(reader);
				Object value = json.get(Collections.singletonList("mixins"));
				if (value instanceof List<?>) {
					for (Object element : (List<?>) value) {
						if (element instanceof String) {
							configs.add((String) element);
						} else if (element instanceof UnmodifiableConfig) {
							Object config = ((UnmodifiableConfig) element).get(Collections.singletonList("config"));
							if (config != null) configs.add(config.toString());
						} else if (element instanceof Map<?, ?>) {
							Object config = ((Map<?, ?>) element).get("config");
							if (config != null) configs.add(config.toString());
						}
					}
				}
			} catch (RuntimeException | IOException ignored) {
				// Fall through to manifest and best-effort config scanning.
			}
		}

		byte[] manifest = entries.get("META-INF/MANIFEST.MF");
		if (manifest != null) {
			try {
				java.util.jar.Manifest mf = new java.util.jar.Manifest(new ByteArrayInputStream(manifest));
				String attr = mf.getMainAttributes().getValue("MixinConfigs");
				if (attr != null) {
					for (String config : attr.split(",")) {
						if (!config.strip().isEmpty()) configs.add(config.strip());
					}
				}
			} catch (IOException ignored) {
				// Keep whatever fabric.mod.json supplied.
			}
		}
		return configs;
	}

	private static void collectForgeOwnedMixinSuppressions(Map<String, byte[]> entries, java.util.Set<String> configs,
			LinkedHashSet<String> suppressions) {
		for (String configName : configs) {
			byte[] configBytes = entries.get(configName);
			if (configBytes == null) continue;
			UnmodifiableConfig config;
			try (Reader reader = new InputStreamReader(new ByteArrayInputStream(configBytes), StandardCharsets.UTF_8)) {
				config = JsonFormat.fancyInstance().createParser().parse(reader);
			} catch (RuntimeException | IOException e) {
				continue;
			}

			String pkg = configString(config, "package");
			List<String> mixins = new ArrayList<>();
			addMixinEntries(config, "mixins", mixins);
			addMixinEntries(config, "client", mixins);
			addMixinEntries(config, "server", mixins);
			for (String mixin : mixins) {
				String classPath = mixinClassPath(pkg, mixin);
				byte[] classBytes = entries.get(classPath);
				if (classBytes == null) continue;
				String ownedTarget = forgeOwnedPipelineTarget(classBytes);
				if (ownedTarget == null) continue;
				if (isPureAccessorMixin(classBytes)) {
					ForbricLog.debug("[Forbric] keeping guest accessor/invoker mixin %s registered even though it targets "
							+ "Forge/NeoForge-owned pipeline class %s", configName + ":" + mixin, ownedTarget.replace('/', '.'));
					continue;
				}
				String suppression = configName + ":" + mixin;
				if (suppressions.add(suppression)) {
					ForbricLog.warn("[Forbric] suppressing guest mixin %s because it targets Forge/NeoForge-owned "
							+ "pipeline class %s", suppression, ownedTarget.replace('/', '.'));
				}
			}
		}
	}

	private static String configString(UnmodifiableConfig config, String key) {
		Object value = config.get(Collections.singletonList(key));
		return value == null ? null : value.toString();
	}

	private static void addMixinEntries(UnmodifiableConfig config, String key, List<String> out) {
		Object value = config.get(Collections.singletonList(key));
		if (!(value instanceof List<?> list)) return;
		for (Object element : list) {
			if (element != null) out.add(element.toString());
		}
	}

	private static String mixinClassPath(String pkg, String mixin) {
		String className = mixin;
		if (pkg != null && !pkg.isBlank() && !mixin.startsWith(pkg + ".")) {
			className = pkg + "." + mixin;
		}
		return className.replace('.', '/') + ".class";
	}

	private static String forgeOwnedPipelineTarget(byte[] classBytes) {
		try {
			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			String target = forgeOwnedPipelineTarget(node.visibleAnnotations);
			return target != null ? target : forgeOwnedPipelineTarget(node.invisibleAnnotations);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static boolean isPureAccessorMixin(byte[] classBytes) {
		try {
			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			if (!hasAnnotation(node.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;")
					&& !hasAnnotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;")) {
				return false;
			}
			if (node.fields != null && !node.fields.isEmpty()) return false;

			boolean sawAccessor = false;
			for (MethodNode method : node.methods) {
				if (method.name.startsWith("<")) continue;
				if ((method.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
				if (!hasAnnotation(method.visibleAnnotations, "Lorg/spongepowered/asm/mixin/gen/Accessor;")
						&& !hasAnnotation(method.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/gen/Accessor;")
						&& !hasAnnotation(method.visibleAnnotations, "Lorg/spongepowered/asm/mixin/gen/Invoker;")
						&& !hasAnnotation(method.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/gen/Invoker;")) {
					return false;
				}
				sawAccessor = true;
			}
			return sawAccessor;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static boolean hasAnnotation(List<AnnotationNode> annotations, String desc) {
		if (annotations == null) return false;
		for (AnnotationNode annotation : annotations) {
			if (desc.equals(annotation.desc)) return true;
		}
		return false;
	}

	private static String forgeOwnedPipelineTarget(List<AnnotationNode> annotations) {
		if (annotations == null) return null;
		for (AnnotationNode annotation : annotations) {
			if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc)) continue;
			List<Object> values = annotation.values;
			if (values == null) continue;
			for (int i = 0; i + 1 < values.size(); i += 2) {
				Object key = values.get(i);
				Object value = values.get(i + 1);
				if ("value".equals(key)) {
					String target = forgeOwnedPipelineAnnotationTarget(value);
					if (target != null) return target;
				} else if ("targets".equals(key)) {
					String target = forgeOwnedPipelineAnnotationTarget(value);
					if (target != null) return target;
				}
			}
		}
		return null;
	}

	private static String forgeOwnedPipelineAnnotationTarget(Object value) {
		if (value instanceof List<?> list) {
			for (Object element : list) {
				String target = forgeOwnedPipelineAnnotationTarget(element);
				if (target != null) return target;
			}
			return null;
		}
		String name = null;
		if (value instanceof Type type) {
			name = type.getInternalName();
		} else if (value instanceof String) {
			name = normalizeMixinTarget((String) value);
		}
		return isForgeOwnedPipelineTarget(name) ? name : null;
	}

	private static String normalizeMixinTarget(String target) {
		String name = target.trim();
		if (name.startsWith("L") && name.endsWith(";")) {
			name = name.substring(1, name.length() - 1);
		}
		return name.replace('.', '/');
	}

	private static boolean isForgeOwnedPipelineTarget(String target) {
		if (target == null || target.isEmpty()) return false;
		if (FORGE_OWNED_GUEST_MIXIN_TARGETS.contains(target)) return true;
		for (String prefix : FORGE_OWNED_GUEST_MIXIN_TARGET_PREFIXES) {
			if (target.startsWith(prefix)) return true;
		}
		return false;
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
