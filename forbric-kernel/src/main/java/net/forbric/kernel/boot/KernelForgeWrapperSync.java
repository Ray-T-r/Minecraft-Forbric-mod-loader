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

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.forbric.kernel.util.ForbricLog;

/**
 * The boot-side half of {@link net.forbric.kernel.transform.RegistrySyncParityInjector}: stages the ids NeoForge's
 * registry sync assigns to a MinecraftForge-wrapped registry, then applies them all at once through Forge's own
 * {@code GameData.injectSnapshot}.
 *
 * <p>Why stage rather than apply per entry: NeoForge hands ids over one {@code registerIdMapping(key, id)} at a
 * time, while Forge remaps a registry whole — {@code loadIds} onto a STAGING copy, then {@code sync} back into
 * ACTIVE, re-adding every entry at its new id and letting {@code NamespacedWrapper.onAdded} re-index the holders it
 * already has. Feeding Forge one id at a time would mean one full copy-and-sync per entry. So {@code clear(false)}
 * opens a per-registry map, {@code registerIdMapping} fills it, and the flush after NeoForge's loop hands every
 * staged registry to {@code injectSnapshot} in one call.
 *
 * <p>The flush is skipped when NeoForge reports missing entries: {@code ClientPayloadHandler} disconnects on that,
 * and a registry left half-remapped on a dying connection is worse than one left alone. When it does run and the
 * block registry moved, the block-state id map is rebuilt in the new registry order — on the merged base
 * {@code Block.BLOCK_STATE_REGISTRY} IS NeoForge's map, and its bake callback is not on the wrapper's freeze path.
 *
 * <p>Known limit, on purpose: fabric-api's {@code remap} is a mixin on {@code MappedRegistry}'s fields, so on a
 * wrapped registry it is a silent no-op — a Forbric client against a PURE Fabric server gets no remap of these
 * seventeen registries from either ecosystem. Against a Forbric server, NeoForge's sync carries the same ids and
 * this class applies them. {@code -Dforbric.forgeWrapperSync=off} turns the staging into a no-op, which is the old
 * behaviour minus the crash.
 */
public final class KernelForgeWrapperSync {
	private static final String PROPERTY = "forbric.forgeWrapperSync";
	private static final boolean ENABLED = !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));

	private static final String BLOCK_REGISTRY = "minecraft:block";

	/** wrapper → (entry Identifier → server id), in the order NeoForge staged them. Identity keys: registries are unique objects. */
	private static final Map<Object, Map<Object, Integer>> STAGED = new IdentityHashMap<>();
	private static final Set<Object> WARNED_FULL_CLEAR = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

	private KernelForgeWrapperSync() {
	}

	/** Head of {@code RegistryManager.applySnapshot(Map, boolean)}: nothing staged by an aborted earlier pass survives. */
	public static void beginSnapshotApplication() {
		synchronized (STAGED) {
			STAGED.clear();
		}
	}

	/** The wrapper's {@code clear(boolean)}: {@code false} opens a fresh staging map; {@code true} is not ours to honour. */
	public static void clear(Object wrapper, boolean full) {
		if (!ENABLED || wrapper == null) return;
		if (full) {
			// A full clear is NeoForge's revertToVanilla/revertToFrozen, whose freeze/revert lifecycle the kernel
			// owns (RegistryManager.revertToFrozen is neutered). Emptying a Forge-backed registry from here would
			// leave ForgeRegistry and the wrapper disagreeing about its contents.
			if (WARNED_FULL_CLEAR.add(wrapper)) {
				ForbricLog.warn("[Forbric/RegistrySync] ignoring a full clear of Forge-wrapped %s — the kernel owns "
						+ "the registry revert lifecycle", describe(wrapper));
			}
			return;
		}
		synchronized (STAGED) {
			STAGED.put(wrapper, new LinkedHashMap<>());
		}
	}

	/** The wrapper's {@code registerIdMapping(ResourceKey, int)}: remember the server's id for this entry. */
	public static void stageIdMapping(Object wrapper, Object key, int id) {
		if (!ENABLED || wrapper == null || key == null) return;
		Object name;
		try {
			name = key.getClass().getMethod("identifier").invoke(key);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/RegistrySync] cannot read the id of registry key " + key, unwrap(t));
			return;
		}
		synchronized (STAGED) {
			STAGED.computeIfAbsent(wrapper, w -> new LinkedHashMap<>()).put(name, id);
		}
	}

	/**
	 * Every return of {@code RegistryManager.applySnapshot(Map, boolean)}: apply what was staged, unless NeoForge is
	 * about to disconnect over {@code missing}.
	 */
	public static void finishSnapshotApplication(Set<?> missing) {
		Map<Object, Map<Object, Integer>> staged;
		synchronized (STAGED) {
			if (STAGED.isEmpty()) return;
			staged = new IdentityHashMap<>(STAGED);
			STAGED.clear();
		}
		if (missing != null && !missing.isEmpty()) {
			ForbricLog.info("[Forbric/RegistrySync] NeoForge found %d missing registry entr(ies) and will disconnect — "
					+ "leaving the %d Forge-wrapped registr(ies) at their local ids", missing.size(), staged.size());
			return;
		}
		Object any = staged.keySet().iterator().next();
		ClassLoader cl = any.getClass().getClassLoader();
		try {
			apply(cl, staged);
		} catch (Throwable t) {
			// Loud, not fatal: the connection continues with these seventeen registries at their LOCAL ids. With an
			// identical mod set on both ends those are the server's ids anyway; with a different set they are not,
			// and the symptom downstream is wrong blocks and items, which is why this is an error and not a warning.
			ForbricLog.error("[Forbric/RegistrySync] could not apply the server's ids to the Forge-wrapped registries "
					+ "through GameData.injectSnapshot — they keep their local ids", unwrap(t));
		}
	}

	private static void apply(ClassLoader cl, Map<Object, Map<Object, Integer>> staged) throws Exception {
		Class<?> snapshotCls = Class.forName("net.minecraftforge.registries.ForgeRegistry$Snapshot", false, cl);
		Class<?> gameData = Class.forName("net.minecraftforge.registries.GameData", false, cl);
		Class<?> identifierCls = Class.forName("net.minecraft.resources.Identifier", false, cl);
		Method injectSnapshot = gameData.getMethod("injectSnapshot", Map.class, boolean.class, boolean.class);
		// Resolved on the PUBLIC interfaces, not on the wrapper: NamespacedWrapper is package-private, and a Method
		// looked up on a package-private class fails the access check even when the method itself is public.
		Class<?> registryCls = Class.forName("net.minecraft.core.Registry", false, cl);
		Method getValue = registryCls.getMethod("getValue", identifierCls);
		Method getId = Class.forName("net.minecraft.core.IdMap", false, cl).getMethod("getId", Object.class);

		Map<Object, Object> snapshots = new HashMap<>();
		Map<String, Integer> sizes = new TreeMap<>();
		int moved = 0;
		boolean blockMoved = false;
		for (Map.Entry<Object, Map<Object, Integer>> e : staged.entrySet()) {
			Object wrapper = e.getKey();
			Map<Object, Integer> ids = e.getValue();
			Object registryName = registryName(wrapper);

			Object snapshot = snapshotCls.getConstructor().newInstance();
			Object idMap = snapshotCls.getField("ids").get(snapshot);
			Method put = idMap.getClass().getMethod("put", Object.class, int.class);
			int movedHere = 0;
			for (Map.Entry<Object, Integer> id : ids.entrySet()) {
				put.invoke(idMap, id.getKey(), id.getValue());
				Object value = getValue.invoke(wrapper, id.getKey());
				if (value != null && !id.getValue().equals(getId.invoke(wrapper, value))) movedHere++;
			}
			snapshots.put(registryName, snapshot);
			sizes.put(String.valueOf(registryName), ids.size());
			moved += movedHere;
			if (movedHere > 0 && BLOCK_REGISTRY.equals(String.valueOf(registryName))) blockMoved = true;
		}

		// injectFrozenData=false, isLocalWorld=false: the remote-server shape, the one a Forge client uses on login.
		Object notFound = injectSnapshot.invoke(null, snapshots, false, false);
		String leftovers = "";
		try {
			if (notFound != null && !(Boolean) notFound.getClass().getMethod("isEmpty").invoke(notFound)) {
				leftovers = "; Forge could not place: " + notFound;
			}
		} catch (Throwable ignored) {
			// A Multimap without isEmpty is not a thing; the summary line just goes without the detail.
		}

		if (blockMoved) rebuildBlockStateIds(cl);
		ForbricLog.info("[Forbric/RegistrySync] %d Forge-wrapped registr(ies) followed the server's ids through Forge's own "
				+ "injectSnapshot — %d id(s) moved%s: %s", staged.size(), moved, leftovers, sizes);
	}

	/**
	 * Re-numbers the block-state id map in registry order after the block registry moved. Vanilla assigns state ids
	 * by walking the block registry in id order, so this reproduces the numbering the server uses. NeoForge's map
	 * is the one {@code Block.BLOCK_STATE_REGISTRY} points at on the merged base, and it is clearable.
	 */
	private static void rebuildBlockStateIds(ClassLoader cl) {
		try {
			Class<?> neoGameData = Class.forName("net.neoforged.neoforge.registries.GameData", false, cl);
			Object idMap = neoGameData.getMethod("getBlockStateIDMap").invoke(null);
			idMap.getClass().getMethod("clear").invoke(idMap);
			Method add = Class.forName("net.minecraft.core.IdMapper", false, cl).getMethod("add", Object.class);

			Class<?> blockCls = Class.forName("net.minecraft.world.level.block.Block", false, cl);
			Method getStateDefinition = blockCls.getMethod("getStateDefinition");
			Method getPossibleStates = Class.forName("net.minecraft.world.level.block.state.StateDefinition", false, cl)
					.getMethod("getPossibleStates");
			Object blockRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl)
					.getField("BLOCK").get(null);
			int states = 0;
			for (Object block : (Iterable<?>) blockRegistry) {
				for (Object state : (List<?>) getPossibleStates.invoke(getStateDefinition.invoke(block))) {
					add.invoke(idMap, state);
					states++;
				}
			}
			ForbricLog.info("[Forbric/RegistrySync] re-numbered %d block state(s) to follow the remapped block registry",
					states);
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/RegistrySync] block ids moved but the block-state id map could not be rebuilt — "
					+ "chunk and block_update packets will decode to the wrong blocks", unwrap(t));
		}
	}

	private static Object registryName(Object wrapper) throws Exception {
		// Registry.key() rather than wrapper.getClass().getMethod("key"): see apply() — the wrapper is package-private.
		Object key = Class.forName("net.minecraft.core.Registry", false, wrapper.getClass().getClassLoader())
				.getMethod("key").invoke(wrapper);
		return key.getClass().getMethod("identifier").invoke(key);
	}

	private static String describe(Object wrapper) {
		try {
			return String.valueOf(registryName(wrapper));
		} catch (Throwable t) {
			return wrapper.getClass().getName();
		}
	}

	private static Throwable unwrap(Throwable t) {
		return t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
	}
}
