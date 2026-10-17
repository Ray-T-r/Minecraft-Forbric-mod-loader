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
 * leans on a modifier after the body (fabric-registry-sync's) applying here as it does natively, so {@code Registries}
 * is asked first whether one does ({@link #fabricPrefixes}): where none does, every registry keeps the merged answer.
 *
 * <p>Ownership is the namespace's: {@link ModPresence#soleEcosystem}. A namespace no mod claims — or that mods of
 * two ecosystems claim — keeps the merged answer, which is what every registry had before this existed. Called from
 * game code on resource-reload threads, so it never throws: a failed decision is the merged answer.
 */
public final class KernelRegistryDirectories {
	/** {@code RegistryDirectoryOwnerInjector.PROPERTY}; {@code force} skips {@link #fabricPrefixes}. */
	private static final String OWNER_PROPERTY = "forbric.registryDirectoryOwner";

	/** The class the edit calls from, whose {@code elementsDirPath} is asked whether something puts the namespace back. */
	private static final String REGISTRIES = "net.minecraft.core.registries.Registries";
	/** The path of the registry the question is asked about: a registry nothing ships and no mod intercepts. */
	static final String PROBE_PATH = "forbric_directory_probe";
	/** Set while the question is asked: the body then answers vanilla's path, whoever owns the namespace. */
	private static final ThreadLocal<Boolean> PROBING = new ThreadLocal<>();

	/** Namespaces already named in the debug log — one line per mod, not one per registry per reload. */
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	/** Whether something after the body puts the namespace back here; null until the first Fabric registry asks. */
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
			if (PROBING.get() != null && path != null) return path;
			if (namespace == null || path == null || "minecraft".equals(namespace)) return merged;
			if (ModPresence.soleEcosystem(namespace) != Ecosystem.FABRIC) return merged;
			if (!fabricPrefixes(namespace)) return merged;
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
	 * Whether something after the body of {@code Registries.elementsDirPath} puts a registry's namespace back — on a Fabric
	 * game, fabric-registry-sync's {@code @ModifyReturnValue} — decided once.
	 *
	 * <p>Vanilla's path is only right for a Fabric registry because that modifier puts the namespace back after
	 * the body. Before this hook the merged body made the modifier redundant, so losing it cost nothing; handing
	 * out vanilla's path without it would move every Fabric registry's elements and tags to an unprefixed
	 * directory, and no line would name why — the guest mixin adapter dropping it as unfit, a pin, or an arbitrated
	 * fabric-api without it all look like one more row in the load report. So without it every registry keeps the
	 * merged answer, which is what they all had before this hook: WorldWeaver's two are empty again, and one WARN
	 * says so.
	 *
	 * <p>Asked of the class the game runs, by what it does: {@code elementsDirPath} for a registry {@code namespace:}
	 * {@value #PROBE_PATH} — nothing ships it and no mod intercepts it — with the body answering vanilla's path. A
	 * directory that comes back with the namespace in front of that path had it put there after the body, whichever
	 * mixin did it and whatever it is called. It is complete on the first call, because this runs inside
	 * {@code Registries}, after every mixin it took. {@code -Dforbric.registryDirectoryOwner=force} skips the question.
	 */
	static boolean fabricPrefixes(String namespace) {
		Boolean known = fabricPrefixer;
		if (known != null) return known;
		synchronized (KernelRegistryDirectories.class) {
			if (fabricPrefixer != null) return fabricPrefixer;
			boolean present;
			if ("force".equalsIgnoreCase(System.getProperty(OWNER_PROPERTY, "on"))) {
				present = true;
				ForbricLog.warn("[Forbric/Registries] -D%s=force — registries in Fabric namespaces get vanilla's data "
						+ "directory without asking Registries whether anything puts the namespace back after the body",
						OWNER_PROPERTY);
			} else {
				try {
					present = namespaceAddedAfterBody(registries(), namespace);
				} catch (Throwable t) {
					present = false;
				}
				if (!present) {
					ForbricLog.warn("[Forbric/Registries] nothing in Registries puts a Fabric registry's namespace back after "
							+ "the body (fabric-registry-sync's modifier is not applied), so every registry keeps the "
							+ "merged (NeoForge) data directory, which is not where a mod that returns the body's answer "
							+ "early (WorldWeaver's world presets and biome data) ships its data; -D%s=force hands Fabric "
							+ "registries vanilla's directory anyway", OWNER_PROPERTY);
				}
			}
			fabricPrefixer = present;
			return present;
		}
	}

	/** {@code Registries.elementsDirPath(namespace:PROBE_PATH)} with the body answering vanilla's path: is the namespace back? */
	static boolean namespaceAddedAfterBody(Class<?> registries, String namespace) throws ReflectiveOperationException {
		if (registries == null || namespace == null) return false;
		ClassLoader loader = registries.getClassLoader();
		Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", false, loader);
		Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey", false, loader);
		Object id = identifier.getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, namespace, PROBE_PATH);
		Object key = resourceKey.getMethod("createRegistryKey", identifier).invoke(null, id);
		java.lang.reflect.Method elements = registries.getMethod("elementsDirPath", resourceKey);
		PROBING.set(Boolean.TRUE);
		try {
			Object dir = elements.invoke(null, key);
			return dir instanceof String path && !path.equals(PROBE_PATH) && path.endsWith(PROBE_PATH) && path.startsWith(namespace);
		} finally {
			PROBING.remove();
		}
	}

	/** {@code Registries}, off the stack: the edit's call is the only way in, so it is always a frame below this. */
	private static Class<?> registries() {
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
				.<Class<?>>map(StackWalker.StackFrame::getDeclaringClass)
				.filter(c -> REGISTRIES.equals(c.getName()))
				.findFirst().orElse(null));
	}

	/**
	 * Forgets what was logged and what {@code Registries} answered — for tests. {@code fabricPrefixer} stands in for
	 * that answer; null asks the calling class again.
	 */
	public static void resetForTests(Boolean fabricPrefixer) {
		REPORTED.clear();
		KernelRegistryDirectories.fabricPrefixer = fabricPrefixer;
	}
}
