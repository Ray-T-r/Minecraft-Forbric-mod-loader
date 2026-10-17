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

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.transform.RegistrySyncParityInjector;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Puts the synced registries back to their pre-connection ids when the client disconnects.
 *
 * <p>Both ecosystems mean to do this and neither could here. NeoForge's {@code Minecraft.disconnect} calls
 * {@code RegistryManager.revertToFrozen()}, which re-applies a snapshot that {@code GameData.freezeData} takes —
 * and the kernel owns the freeze, so that snapshot was never taken and the call was neutered. fabric-api's
 * {@code unmap()} runs from its client mixin's callback at the RETURN of {@code Minecraft.disconnect}, after
 * {@code revertToFrozen()}, and restores only the ids its own sync replaced. So a client that left a server kept the
 * server's ids: consistent within the JVM, and invisible while the next world used the same mod set, but a
 * client-only mod's entries sat wherever the last server put them.
 *
 * <p>This class takes NeoForge's snapshot itself — {@code RegistryManager.takeSnapshot(SYNC_TO_CLIENT)}, the
 * public API the server uses to build the sync — the first time a connection's remap begins, and
 * {@code revertToFrozen()} now applies it back: plain registries through NeoForge's own {@code applySnapshot}
 * loop, the Forge-wrapped ones through the staged path that loop already goes through on connect, and a remap
 * contract's undo (fabric-api's {@code unmap}) first, so the mod's own bookkeeping is left the way it would leave it
 * and its own call at the end of the disconnect finds nothing left to undo. A snapshot whose ids all still match is not applied at all — the common
 * case, and one where every registry would otherwise be torn down and rebuilt for nothing.
 */
public final class KernelRegistryRevert {
	private static final String REGISTRY_MANAGER = ForeignType.REGISTRY_MANAGER.binary(Ecosystem.NEOFORGE);
	private static final String SNAPSHOT_TYPE = REGISTRY_MANAGER + "$SnapshotType";
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
			Caught.warn(t, "[Forbric/RegistrySync] could not capture the pre-connection registry ids — they will not "
					+ "be restored on disconnect");
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
			ForbricLog.info("[Forbric/RegistrySync] reverted %d synced registr(ies) to their pre-connection ids (a mod's remap "
					+ "contract unmapped %d plain one(s) first; %d entr(ies) NeoForge could not place)", snapshot.size(), unmapped,
					missingCount);
		} catch (Throwable t) {
			Caught.error(t, "[Forbric/RegistrySync] could not revert the synced registries to their pre-connection ids "
					+ "— they keep the last server's");
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
	 * The undo of every remap contract a mixin adds to the registries ({@link RegistrySyncParityInjector.RemapContract}:
	 * fabric-api's {@code unmap()} is one) on every plain registry that carries it, so the mod's own bookkeeping is left
	 * the way its disconnect would leave it before NeoForge's snapshot goes back. The Forge-wrapped registries are
	 * skipped: their ids live in the delegate, and the NeoForge pass that follows restores those through
	 * {@link KernelForgeWrapperSync}. Without such a contract there is nothing to do. The contract is found by what the
	 * mixin does, so its interface and method are called whatever the mod calls them.
	 */
	private static int unmapFabricPlainRegistries(ClassLoader cl) {
		java.util.Map<String, String> undos = new java.util.LinkedHashMap<>();
		for (var contract : RegistrySyncParityInjector.discoveredContracts()) {
			if (contract.undo() != null) undos.putIfAbsent(contract.owner().replace('/', '.') + "." + contract.undo(), contract.owner());
		}
		if (undos.isEmpty()) return 0;
		int done = 0;
		try {
			Class<?> wrapper = null;
			try {
				wrapper = Class.forName(WRAPPER, false, cl);
			} catch (ClassNotFoundException noForge) {
				// no wrappers to skip
				Caught.expected(noForge);
			}
			Object root = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl).getField("REGISTRY").get(null);
			for (var undo : undos.entrySet()) {
				Class<?> owner;
				try {
					owner = Class.forName(undo.getValue().replace('/', '.'), false, cl);
				} catch (ClassNotFoundException absent) {
					Caught.warn(absent, "[Forbric/RegistrySync] the remap contract %s names an owner this loader cannot "
							+ "load; it is not undone", undo.getKey());
					continue;
				}
				Method method = owner.getMethod(undo.getKey().substring(undo.getKey().lastIndexOf('.') + 1));
				for (Object registry : (Iterable<?>) root) {
					if (!owner.isInstance(registry) || (wrapper != null && wrapper.isInstance(registry))) continue;
					try {
						method.invoke(registry);
						done++;
					} catch (Throwable t) {
						Caught.warn(t, "[Forbric/RegistrySync] %s could not unmap %s", undo.getKey(), registry);
					}
				}
			}
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/RegistrySync] the remap contracts' unmap pass failed part-way");
		}
		return done;
	}
}
