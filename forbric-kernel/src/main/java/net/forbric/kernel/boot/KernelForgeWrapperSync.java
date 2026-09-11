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

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
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

	/**
	 * Head of every remap entry point ({@code RegistryManager.applySnapshot}, {@code ClientRegistrySyncHandler.apply}):
	 * nothing staged by an aborted earlier pass survives, and the pre-connection ids get captured for the disconnect
	 * ({@link KernelRegistryRevert}). {@code gameClass} is the hooked class itself, pushed as a constant — the loader
	 * to reflect through.
	 */
	public static void beginSnapshotApplication(Class<?> gameClass) {
		synchronized (STAGED) {
			STAGED.clear();
		}
		KernelRegistryRevert.captureIfNeeded(gameClass);
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
	 * The wrapper's fabric-api {@code remap(Object2IntMap<Identifier> ids, RemapMode mode)}: stage the whole map.
	 * fabric-api's {@code checkRemoteRemap} has already refused a server whose entries the client lacks, so every
	 * name here resolves locally; local-only entries are Forge's to place after the server's, in {@code loadIds}.
	 */
	public static void stageFabricRemap(Object wrapper, Object ids, Object mode) {
		if (!ENABLED || wrapper == null || !(ids instanceof Map<?, ?> map)) return;
		Map<Object, Integer> staged = new LinkedHashMap<>();
		for (Map.Entry<?, ?> e : map.entrySet()) {
			if (e.getValue() instanceof Integer id) staged.put(e.getKey(), id);
		}
		synchronized (STAGED) {
			STAGED.put(wrapper, staged);
		}
		ForbricLog.debug("[Forbric/RegistrySync] fabric-api remap (%s) staged %d id(s) for %s", mode, staged.size(),
				describe(wrapper));
	}

	/** Every return of {@code ClientRegistrySyncHandler.apply}: fabric-api has remapped the rest; apply the wrapped ones. */
	public static void finishFabricRemap() {
		finishSnapshotApplication(Set.of());
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
		Class<?> gameData = Class.forName(ForeignType.GAME_DATA.binary(Ecosystem.FORGE), false, cl);
		Class<?> identifierCls = Class.forName("net.minecraft.resources.Identifier", false, cl);
		Method injectSnapshot = gameData.getMethod("injectSnapshot", Map.class, boolean.class, boolean.class);
		// Resolved on the PUBLIC interfaces, not on the wrapper: NamespacedWrapper is package-private, and a Method
		// looked up on a package-private class fails the access check even when the method itself is public.
		Class<?> registryCls = Class.forName("net.minecraft.core.Registry", false, cl);
		Method getValue = registryCls.getMethod("getValue", identifierCls);
		Method getId = Class.forName("net.minecraft.core.IdMap", false, cl).getMethod("getId", Object.class);

		Method getKey = registryCls.getMethod("getKey", Object.class);

		Map<Object, Object> snapshots = new HashMap<>();
		Map<String, Integer> sizes = new TreeMap<>();
		// Per wrapper: every local entry's id BEFORE, so the change can be measured and reported afterwards.
		Map<Object, Map<Object, Integer>> before = new IdentityHashMap<>();
		int moved = 0;
		boolean blockMoved = false;
		for (Map.Entry<Object, Map<Object, Integer>> e : staged.entrySet()) {
			Object wrapper = e.getKey();
			Map<Object, Integer> ids = e.getValue();
			Object registryName = registryName(wrapper);
			before.put(wrapper, localIds(wrapper, getKey, getId));

			Object snapshot = snapshotCls.getConstructor().newInstance();
			Object idMap = snapshotCls.getField("ids").get(snapshot);
			Method put = idMap.getClass().getMethod("put", Object.class, int.class);
			int movedHere = 0;
			int next = 0;
			for (Map.Entry<Object, Integer> id : ids.entrySet()) {
				put.invoke(idMap, id.getKey(), id.getValue());
				next = Math.max(next, id.getValue() + 1);
				Object value = getValue.invoke(wrapper, id.getKey());
				if (value != null && !id.getValue().equals(getId.invoke(wrapper, value))) movedHere++;
			}
			// Entries the server does not know — a client-only mod's blocks, say — get ids after the server's, in
			// their local order. That is fabric-api's REMOTE-mode rule, and it is not Forge's: given a snapshot that
			// omits them, loadIds drops them, and the first client to bring a canary block onto a pure Fabric server
			// lost it (the loss guard below is what said so).
			int kept = 0;
			for (Map.Entry<Object, Integer> local : before.get(wrapper).entrySet()) {
				if (ids.containsKey(local.getKey())) continue;
				put.invoke(idMap, local.getKey(), next++);
				kept++;
			}
			if (kept > 0) {
				int keptHere = kept;
				ForbricLog.info("[Forbric/RegistrySync] %s: %d local-only entr(ies) placed after the server's ids",
						String.valueOf(registryName), keptHere);
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

		// What actually changed, per wrapper — and the one thing that must not have: an entry disappearing. Forge's
		// loadIds places the entries the server does not know after the server's; a registry that came back
		// smaller would mean it did not, and that is a wrong-block world waiting to happen.
		int lost = 0;
		for (Map.Entry<Object, Map<Object, Integer>> e : before.entrySet()) {
			Object wrapper = e.getKey();
			Map<Object, Integer> after = localIds(wrapper, getKey, getId);
			for (Object name : e.getValue().keySet()) {
				if (!after.containsKey(name)) {
					lost++;
					ForbricLog.error("[Forbric/RegistrySync] " + describe(wrapper) + " LOST " + name + " in the remap");
				}
			}
			announceRemapToFabric(cl, wrapper, e.getValue(), after);
		}

		if (blockMoved) rebuildBlockStateIds(cl);
		ForbricLog.info("[Forbric/RegistrySync] %d Forge-wrapped registr(ies) followed the server's ids through Forge's own "
				+ "injectSnapshot — %d id(s) moved, %d entr(ies) lost%s: %s", staged.size(), moved, lost, leftovers, sizes);
	}

	/** Every entry of the wrapper as name → id, read through the public Registry surface. */
	private static Map<Object, Integer> localIds(Object wrapper, Method getKey, Method getId) throws Exception {
		Map<Object, Integer> ids = new LinkedHashMap<>();
		for (Object value : (Iterable<?>) wrapper) {
			ids.put(getKey.invoke(wrapper, value), (Integer) getId.invoke(wrapper, value));
		}
		return ids;
	}

	/**
	 * Fires fabric-api's {@code RegistryIdRemapCallback} for a wrapper whose ids moved — the event its own
	 * {@code remap} would have fired, and what fabric-api's block/fluid state-id trackers and any mod caching raw
	 * ids listen to. Built from the before/after maps, in the shape fabric-api's {@code RemapStateImpl} expects.
	 * Best-effort: a fabric-api that changed the shape costs the notification, not the remap.
	 */
	private static void announceRemapToFabric(ClassLoader cl, Object wrapper, Map<Object, Integer> before,
			Map<Object, Integer> after) {
		try {
			Object oldIdMap = Class.forName("it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap", false, cl)
					.getConstructor().newInstance();
			Object changes = Class.forName("it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap", false, cl)
					.getConstructor().newInstance();
			Method putOld = oldIdMap.getClass().getMethod("put", int.class, Object.class);
			Method putChange = changes.getClass().getMethod("put", int.class, int.class);
			int changed = 0;
			for (Map.Entry<Object, Integer> e : before.entrySet()) {
				putOld.invoke(oldIdMap, e.getValue(), e.getKey());
				Integer now = after.get(e.getKey());
				if (now != null && !now.equals(e.getValue())) {
					putChange.invoke(changes, e.getValue(), now);
					changed++;
				}
			}
			if (changed == 0) return;

			Class<?> stateCls = Class.forName("net.fabricmc.fabric.impl.registry.sync.RemapStateImpl", false, cl);
			Class<?> registryCls = Class.forName("net.minecraft.core.Registry", false, cl);
			Object state = stateCls.getConstructor(registryCls,
					Class.forName("it.unimi.dsi.fastutil.ints.Int2ObjectMap", false, cl),
					Class.forName("it.unimi.dsi.fastutil.ints.Int2IntMap", false, cl)).newInstance(wrapper, oldIdMap, changes);
			// fabric_getRemapEvent is mixin-added to MappedRegistry; the wrapper inherits it. Event.invoker() hands
			// back a RegistryIdRemapCallback whose onRemap(RemapState) fans out to every listener.
			Method getEvent = wrapper.getClass().getMethod("fabric_getRemapEvent");
			getEvent.setAccessible(true);
			Object event = getEvent.invoke(wrapper);
			Object invoker = event.getClass().getMethod("invoker").invoke(event);
			Method onRemap = null;
			for (Method m : invoker.getClass().getMethods()) {
				if (m.getName().equals("onRemap") && m.getParameterCount() == 1) { onRemap = m; break; }
			}
			if (onRemap == null) return;
			onRemap.setAccessible(true);
			onRemap.invoke(invoker, state);
			ForbricLog.info("[Forbric/RegistrySync] told fabric-api's remap listeners about %d moved id(s) in %s",
					changed, describe(wrapper));
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/RegistrySync] could not fire fabric-api's RegistryIdRemapCallback for "
					+ describe(wrapper) + " — listeners that cache raw ids were not told", unwrap(t));
		}
	}

	/**
	 * Re-numbers the block-state id map in registry order after the block registry moved. Vanilla assigns state ids
	 * by walking the block registry in id order, so this reproduces the numbering the server uses. NeoForge's map
	 * is the one {@code Block.BLOCK_STATE_REGISTRY} points at on the merged base, and it is clearable.
	 */
	private static void rebuildBlockStateIds(ClassLoader cl) {
		try {
			Class<?> neoGameData = Class.forName(ForeignType.GAME_DATA.binary(Ecosystem.NEOFORGE), false, cl);
			Object idMap = neoGameData.getMethod("getBlockStateIDMap").invoke(null);
			// NeoForge's map is a package-private nested IdMapper subclass whose clear() is package-private too —
			// getMethod cannot see it; walk the class chain for the declared one.
			Method clear = null;
			for (Class<?> c = idMap.getClass(); c != null && clear == null; c = c.getSuperclass()) {
				try {
					clear = c.getDeclaredMethod("clear");
				} catch (NoSuchMethodException keepLooking) {
					// next superclass
				}
			}
			if (clear == null) throw new NoSuchMethodException(idMap.getClass().getName() + ".clear()");
			clear.setAccessible(true);
			clear.invoke(idMap);
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
