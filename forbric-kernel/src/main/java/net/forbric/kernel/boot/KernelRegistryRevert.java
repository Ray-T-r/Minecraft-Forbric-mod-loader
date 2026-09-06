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
import java.util.Map;
import java.util.Set;

import net.forbric.kernel.util.ForbricLog;

/**
 * Puts the synced registries back to their pre-connection ids when the client disconnects.
 *
 * <p>Both ecosystems mean to do this and neither could here. NeoForge's {@code Minecraft.disconnect} calls
 * {@code RegistryManager.revertToFrozen()}, which re-applies a snapshot that {@code GameData.freezeData} takes —
 * and the kernel owns the freeze, so that snapshot was never taken and the call was neutered. fabric-api's
 * {@code unmap()} runs from a mixin the kernel suppresses because its other injection re-runs the registry
 * bootstrap. So a client that left a server kept the server's ids: consistent within the JVM, and invisible
 * while the next world used the same mod set, but a client-only mod's entries sat wherever the last server put
 * them.
 *
 * <p>This class takes NeoForge's snapshot itself — {@code RegistryManager.takeSnapshot(SYNC_TO_CLIENT)}, the
 * public API the server uses to build the sync — the first time a connection's remap begins, and
 * {@code revertToFrozen()} now applies it back: plain registries through NeoForge's own {@code applySnapshot}
 * loop, the seventeen Forge-wrapped ones through the staged path that loop already goes through on connect, and
 * fabric-api's {@code unmap} first, so its own bookkeeping ({@code fabric_prevIndexedEntries}, its remap event)
 * is left the way it would leave it. A snapshot whose ids all still match is not applied at all — the common
 * case, and one where every registry would otherwise be torn down and rebuilt for nothing.
 */
public final class KernelRegistryRevert {
	private static final String REGISTRY_MANAGER = "net.neoforged.neoforge.registries.RegistryManager";
	private static final String SNAPSHOT_TYPE = REGISTRY_MANAGER + "$SnapshotType";
	private static final String REMAPPABLE = "net.fabricmc.fabric.impl.registry.sync.RemappableRegistry";
	private static final String WRAPPER = "net.minecraftforge.registries.NamespacedWrapper";

	/** {@code Map<Identifier, RegistrySnapshot>} of every synced registry as it was before the connection remapped it. */
	private static volatile Map<?, ?> originals;
	private static volatile boolean reverting;

	private KernelRegistryRevert() {
	}

	/**
	 * Head of every remap entry point ({@code RegistryManager.applySnapshot}, {@code ClientRegistrySyncHandler
	 * .apply}): remember the ids as they are now, once per connection. The revert's own {@code applySnapshot} passes
	 * through here too, and must not re-capture the ids it is in the middle of undoing.
	 */
	public static void captureIfNeeded(Class<?> gameClass) {
		if (reverting || originals != null || gameClass == null) return;
		try {
			Map<?, ?> snapshot = takeSnapshot(gameClass.getClassLoader());
			originals = snapshot;
			ForbricLog.info("[Forbric/RegistrySync] captured the pre-connection ids of %d synced registr(ies) — the "
					+ "disconnect puts them back", snapshot.size());
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/RegistrySync] could not capture the pre-connection registry ids — they will not "
					+ "be restored on disconnect", unwrap(t));
		}
	}

	/** The body of {@code RegistryManager.revertToFrozen()}: undo the connection's remap, if there was one. */
	public static void revertToPreConnection(Class<?> gameClass) {
		Map<?, ?> snapshot = originals;
		if (snapshot == null || gameClass == null) {
			ForbricLog.debug("[Forbric/RegistrySync] nothing to revert — no connection remapped a registry");
			return;
		}
		reverting = true;
		try {
			ClassLoader cl = gameClass.getClassLoader();
			Map<?, ?> now = takeSnapshot(cl);
			if (sameIds(snapshot, now)) {
				ForbricLog.info("[Forbric/RegistrySync] %d synced registr(ies) already at their pre-connection ids — nothing "
						+ "to revert", snapshot.size());
				return;
			}
			int unmapped = unmapFabricPlainRegistries(cl);
			Class<?> manager = Class.forName(REGISTRY_MANAGER, false, cl);
			Object missing = manager.getMethod("applySnapshot", Map.class, boolean.class).invoke(null, snapshot, true);
			int missingCount = missing instanceof Set<?> s ? s.size() : -1;
			ForbricLog.info("[Forbric/RegistrySync] reverted %d synced registr(ies) to their pre-connection ids (fabric-api "
					+ "unmapped %d plain one(s) first; %d entr(ies) NeoForge could not place)", snapshot.size(), unmapped,
					missingCount);
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/RegistrySync] could not revert the synced registries to their pre-connection ids "
					+ "— they keep the last server's", unwrap(t));
		} finally {
			reverting = false;
			originals = null;
		}
	}

	private static Map<?, ?> takeSnapshot(ClassLoader cl) throws Exception {
		Class<?> manager = Class.forName(REGISTRY_MANAGER, false, cl);
		Class<?> type = Class.forName(SNAPSHOT_TYPE, false, cl);
		Object sync = type.getField("SYNC_TO_CLIENT").get(null);
		return (Map<?, ?>) manager.getMethod("takeSnapshot", type).invoke(null, sync);
	}

	/** Same registries, and the same id → name map in each; a difference anywhere means a remap to undo. */
	private static boolean sameIds(Map<?, ?> a, Map<?, ?> b) throws Exception {
		if (!a.keySet().equals(b.keySet())) return false;
		for (Map.Entry<?, ?> e : a.entrySet()) {
			Object other = b.get(e.getKey());
			if (other == null) return false;
			Method getIds = e.getValue().getClass().getMethod("getIds");
			if (!getIds.invoke(e.getValue()).equals(getIds.invoke(other))) return false;
		}
		return true;
	}

	/**
	 * fabric-api's {@code unmap()} on every plain registry that carries it — what its suppressed client mixin would
	 * have done. The Forge-wrapped registries are skipped: their ids live in the delegate, and the NeoForge pass that
	 * follows restores those through {@link KernelForgeWrapperSync}. Absent fabric-api, there is nothing to do.
	 */
	private static int unmapFabricPlainRegistries(ClassLoader cl) {
		Class<?> remappable;
		try {
			remappable = Class.forName(REMAPPABLE, false, cl);
		} catch (ClassNotFoundException noFabricApi) {
			return 0;
		}
		int done = 0;
		try {
			Class<?> wrapper = null;
			try {
				wrapper = Class.forName(WRAPPER, false, cl);
			} catch (ClassNotFoundException noForge) {
				// no wrappers to skip
			}
			Object root = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl).getField("REGISTRY").get(null);
			Method unmap = remappable.getMethod("unmap");
			for (Object registry : (Iterable<?>) root) {
				if (!remappable.isInstance(registry) || (wrapper != null && wrapper.isInstance(registry))) continue;
				try {
					unmap.invoke(registry);
					done++;
				} catch (Throwable t) {
					ForbricLog.warn("[Forbric/RegistrySync] fabric-api could not unmap " + registry + ": " + unwrap(t));
				}
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/RegistrySync] fabric-api's unmap pass failed part-way", unwrap(t));
		}
		return done;
	}

	private static Throwable unwrap(Throwable t) {
		return t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
	}
}
