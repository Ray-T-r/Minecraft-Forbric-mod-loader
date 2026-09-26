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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.kernel.util.ForbricLog;

/**
 * The answer {@code Registries.registryDirPath} gives for a registry, decided by the ecosystem that owns it.
 *
 * <p>Vanilla's body is the registry's path and nothing else, so a mod registry {@code wover:wover/world_preset_info}
 * would read {@code data/<ns>/wover/world_preset_info/}. All three loaders put the registry's namespace in front of
 * that — but in different places. NeoForge and MinecraftForge patched the BODY ({@code CommonHooks.prefixNamespace},
 * or the same test inline). Fabric left the body vanilla and adds the namespace afterwards: fabric-registry-sync's
 * {@code RegistriesMixin} is a {@code @ModifyReturnValue} on {@code elementsDirPath} and {@code tagsDirPath}. For
 * a plain mod the three agree on {@code data/<ns>/<registry ns>/<registry path>/}.
 *
 * <p>They stop agreeing the moment another mixin stands between the body and Fabric's modifier, and WorldWeaver
 * (wover) does exactly that. Its {@code RegistryDataLoaderMixinEarly}, at priority 200, injects at the RETURN of
 * {@code elementsDirPath}, cancellable, and for each of its own six datapack registries hands back the value the
 * body returned. Being applied first, its early return comes before fabric's modifier and skips it, so on native
 * Fabric those registries read the vanilla directory — which is where wover ships them:
 * {@code data/wover/wover/world_preset_info/normal.json} is element {@code wover:normal} of
 * {@code wover:wover/world_preset_info}, and {@code data/minecraft/wover/worldgen/biome_data/} holds the ten
 * vanilla Nether and End biomes' placement data.
 *
 * <p>The merged base took NeoForge's body. The value wover handed back was therefore already prefixed,
 * {@code wover/wover/world_preset_info} — {@code data/<ns>/wover/wover/world_preset_info/}, a directory nothing
 * ships — and the two of its registries that have data loaded EMPTY without a word: the sweep pack's content
 * census counted 5+3 preset entries and 10 biome entries on native Fabric and none here. Wover's world types lost
 * their settings, its Nether and End generators lost where each vanilla biome belongs, and both fell back to
 * defaults in silence. Its tags were never affected: its mixin leaves {@code tagsDirPath} to fabric's modifier.
 *
 * <p>So the body answers as the owner's loader would: vanilla's path for a registry whose namespace belongs to a
 * Fabric mod, whatever the merged body said for everything else. Nothing changes for a plain Fabric registry,
 * because fabric's modifier still runs after this and puts the namespace back exactly as it does natively — the
 * only registries whose directory moves are those a mod intercepted the way wover does, and they move to where
 * that mod put its data. {@code componentsDirPath} (datagen only) follows too; Fabric never prefixed it. That
 * leans on fabric-registry-sync's {@code RegistriesMixin} applying here as it does natively, so {@code Registries}
 * is asked first whether it took it ({@link #fabricPrefixes}): where it did not, every registry keeps the merged
 * answer.
 *
 * <p>Ownership is the namespace's: {@link ModPresence#soleEcosystem}. A namespace no mod claims — or that mods of
 * two ecosystems claim — keeps the merged answer, which is what every registry had before this existed. Called from
 * game code on resource-reload threads, so it never throws: a failed decision is the merged answer.
 */
public final class KernelRegistryDirectories {
	/** {@code RegistryDirectoryOwnerInjector.PROPERTY}; {@code force} skips {@link #fabricPrefixes}. */
	private static final String OWNER_PROPERTY = "forbric.registryDirectoryOwner";

	/** fabric-registry-sync's {@code @ModifyReturnValue} mixin, which puts the namespace back after the body. */
	static final String FABRIC_PREFIXER = "net.fabricmc.fabric.mixin.registry.sync.RegistriesMixin";

	/** The class the edit calls from, whose merged methods say whether {@link #FABRIC_PREFIXER} is in it. */
	private static final String REGISTRIES = "net.minecraft.core.registries.Registries";

	/** Namespaces already named in the debug log — one line per mod, not one per registry per reload. */
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	/** Whether {@link #FABRIC_PREFIXER} runs after the body here; null until the first Fabric registry asks. */
	private static volatile Boolean fabricPrefixer;

	private KernelRegistryDirectories() {
	}

	/**
	 * What {@code Registries.registryDirPath} returns for the registry {@code namespace:path}.
	 *
	 * @param merged    what the merged body computed (NeoForge's prefixed directory)
	 * @param namespace the registry id's namespace
	 * @param path      the registry id's path — vanilla's whole answer
	 */
	public static String registryDirPath(String merged, String namespace, String path) {
		try {
			if (namespace == null || path == null || "minecraft".equals(namespace)) return merged;
			if (ModPresence.soleEcosystem(namespace) != Ecosystem.FABRIC) return merged;
			if (!fabricPrefixes()) return merged;
			// DEBUG: for almost every Fabric registry the modifier puts the namespace straight back, so the change is
			// invisible there, and a line per mod at INFO would point triage at registries that read as before.
			if (ForbricLog.debugEnabled() && !path.equals(merged) && REPORTED.add(namespace)) {
				ForbricLog.debug("[Forbric/Registries] registries in the Fabric namespace %s (first: %s:%s) get "
						+ "vanilla's data directory from Registries' body, as on native Fabric; fabric-registry-sync "
						+ "adds the namespace after it unless the mod's own mixin keeps it off", namespace, namespace,
						path);
			}
			return path;
		} catch (Throwable t) {
			return merged;
		}
	}

	/**
	 * Whether fabric-registry-sync's modifier is in {@code Registries} as the game defined it, decided once.
	 *
	 * <p>Vanilla's path is only right for a Fabric registry because that modifier puts the namespace back after
	 * the body. Before this hook the merged body made the modifier redundant, so losing it cost nothing; handing
	 * out vanilla's path without it would move every Fabric registry's elements and tags to an unprefixed
	 * directory, and no line would name why — the guest mixin adapter dropping it as unfit, a pin, or an arbitrated
	 * fabric-api without it all look like one more row in the load report. So without it every registry keeps the
	 * merged answer, which is what they all had before this hook: WorldWeaver's two are empty again, and one WARN
	 * says so.
	 *
	 * <p>Read off the class itself. Mixin marks every method it merges into a target with the runtime annotation
	 * {@code @MixinMerged(mixin = <mixin class>)}, so {@code Registries}' own declared methods say which mixins it
	 * really took — after the adapter, the pins and arbitration, in the class the game runs. It is complete on the
	 * first call, because this runs inside it. Mixin's own record is no substitute: {@code MixinInfo.postApply} adds
	 * an application to the MIXIN's {@code ClassInfo}, never the target's, so {@code Mixins.getMixinsForClass} on
	 * {@code Registries} is empty however many it took, and the mixin's entry sits in a plain map the transformer
	 * writes from other class-loading threads. {@code -Dforbric.registryDirectoryOwner=force} skips the question.
	 */
	static boolean fabricPrefixes() {
		Boolean known = fabricPrefixer;
		if (known != null) return known;
		synchronized (KernelRegistryDirectories.class) {
			if (fabricPrefixer != null) return fabricPrefixer;
			boolean present;
			if ("force".equalsIgnoreCase(System.getProperty(OWNER_PROPERTY, "on"))) {
				present = true;
				ForbricLog.warn("[Forbric/Registries] -D%s=force — registries in Fabric namespaces get vanilla's data "
						+ "directory without asking Registries whether fabric-registry-sync's %s is in it",
						OWNER_PROPERTY, FABRIC_PREFIXER);
			} else {
				try {
					present = Merged.into(registries(), FABRIC_PREFIXER);
				} catch (Throwable t) {
					present = false;
				}
				if (!present) {
					ForbricLog.warn("[Forbric/Registries] fabric-registry-sync's %s is not in Registries, so nothing "
							+ "puts a Fabric registry's namespace back after the body — every registry keeps the "
							+ "merged (NeoForge) data directory, which is not where a mod that returns the body's "
							+ "answer early (WorldWeaver's world presets and biome data) ships its data; -D%s=force "
							+ "hands Fabric registries vanilla's directory anyway", FABRIC_PREFIXER, OWNER_PROPERTY);
				}
			}
			fabricPrefixer = present;
			return present;
		}
	}

	/** {@code Registries}, off the stack: the edit's call is the only way in, so it is always a frame below this. */
	private static Class<?> registries() {
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
				.<Class<?>>map(StackWalker.StackFrame::getDeclaringClass)
				.filter(c -> REGISTRIES.equals(c.getName()))
				.findFirst().orElse(null));
	}

	/** Mixin's annotation, in a class of its own: a loader without Mixin fails here, inside the caller's catch. */
	private static final class Merged {
		static boolean into(Class<?> target, String mixin) {
			if (target == null) return false;
			for (java.lang.reflect.Method method : target.getDeclaredMethods()) {
				org.spongepowered.asm.mixin.transformer.meta.MixinMerged merged =
						method.getAnnotation(org.spongepowered.asm.mixin.transformer.meta.MixinMerged.class);
				if (merged != null && mixin.equals(merged.mixin().replace('/', '.'))) return true;
			}
			return false;
		}
	}

	/**
	 * Forgets what was logged and what {@code Registries} answered — for tests. {@code fabricPrefixer} stands in for
	 * that answer (a test's {@code Registries} never passed through Mixin); null asks the calling class again.
	 */
	public static void resetForTests(Boolean fabricPrefixer) {
		REPORTED.clear();
		KernelRegistryDirectories.fabricPrefixer = fabricPrefixer;
	}
}
