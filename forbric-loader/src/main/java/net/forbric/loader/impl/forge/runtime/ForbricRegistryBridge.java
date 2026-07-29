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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Tri-in-one bridge: toggles the {@code frozen} flag on every {@code net.minecraftforge.registries.
 * NamespacedWrapper}-wrapped registry in {@code BuiltInRegistries}.
 *
 * <p><b>Why this exists.</b> On the MERGED (Forge+NeoForge) game base, {@code BuiltInRegistries.internalRegister}
 * is patched Forge's way (Forge's registry-creation hook wins the byte-merge), so every vanilla registry is
 * wrapped in Forge's {@code NamespacedWrapper} — which starts life FROZEN and only opens for writes during
 * Forge's OWN {@code GameData} unfreeze/{@code RegisterEvent}/freeze window, normally driven by Forge's own
 * {@code ClientModLoader}/{@code ServerModLoader}. But on the merged base the game's {@code Main.main} entry
 * calls NeoForge's genuine lifecycle ({@code ServerModLoader.load}/{@code ClientModLoader.begin}), not Forge's —
 * so that window never opens, and NeoForge's OWN "Registry initialization" mod-loading task (which assumes a
 * plain, writable vanilla registry, as it always is on a pure-NeoForge base) throws {@code IllegalStateException:
 * Registry ... is already frozen} the moment it tries to touch one (caught empirically). This class opens the
 * SAME window around NeoForge's registry-init phase instead, so both ecosystems' registry lifecycles can run
 * against the one shared wrapper.
 */
public final class ForbricRegistryBridge {
	private ForbricRegistryBridge() {
	}

	/**
	 * True only for the merged Forge+NeoForge runtime. Used to gate compatibility behavior that would be wasteful
	 * on a single-loader base but is required when both registry/resource lifecycles share one Minecraft instance.
	 */
	public static boolean isMergedForgeNeoBase(ClassLoader cl) {
		try {
			Class.forName("net.minecraftforge.registries.NamespacedWrapper", false, cl);
			Class.forName("net.neoforged.neoforge.network.registration.NetworkRegistry", false, cl);
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	/**
	 * Opens or closes every {@code NamespacedWrapper}-wrapped {@code BuiltInRegistries} registry. Closing uses the
	 * wrapper's real {@code freeze()} path so the frozen tag snapshot is rebuilt; a raw field toggle would leave
	 * {@code frozenTags} unbound and later crash registry/tag sync with {@code Tags not bound}.
	 */
	public static List<Object> setForgeWrappersFrozen(ClassLoader cl, boolean frozen) {
		List<Object> toggled = new ArrayList<>();
		try {
			Class<?> builtin = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
			Class<?> registryCls = Class.forName("net.minecraft.core.Registry", false, cl);
			Class<?> wrapperCls = Class.forName("net.minecraftforge.registries.NamespacedWrapper", false, cl);
			Class<?> tagSetCls = Class.forName("net.minecraft.core.MappedRegistry$TagSet", false, cl);
			Method unfreeze = wrapperCls.getMethod("unfreeze");
			Method freezeMethod = wrapperCls.getMethod("freeze");
			unfreeze.setAccessible(true);
			freezeMethod.setAccessible(true);
			Method isBound = tagSetCls.getMethod("isBound");
			// NamespacedWrapper.freeze() throws "Tags already present before freezing" if frozenTags.isBound()
			// — its ctor's own initial (never-frozen) value, built fresh here so re-unfreezing looks like a
			// registry that has never had its tags bound, letting whichever ecosystem freezes it NEXT rebuild
			// them cleanly instead of tripping over the OTHER ecosystem's earlier freeze cycle.
			Object unbound = frozen ? null : tagSetCls.getMethod("unbound").invoke(null);
			Field frozenField = findField(wrapperCls, "frozen");
			Field frozenTagsField = findField(wrapperCls, "frozenTags");
			if (frozenField == null || frozenTagsField == null) return toggled;
			frozenField.setAccessible(true);
			frozenTagsField.setAccessible(true);

			for (Field f : builtin.getFields()) {
				if (!registryCls.isAssignableFrom(f.getType())) continue;
				Object reg = f.get(null);
				if (reg == null || !wrapperCls.isInstance(reg)) continue;

				boolean changed = false;
				if (frozen) {
					try {
						// Match Forge GameData.freezeData(): clear any previous frozen tag snapshot, then let
						// NamespacedWrapper.freeze() rebuild frozenTags from the live tag map and refresh holders.
						refreezeWrapper(reg, unfreeze, freezeMethod, frozenTagsField, isBound);
						changed = true;
					} catch (Throwable freezeFailed) {
						ForbricLog.warn("[Forbric/RegistryBridge] could not rebuild Forge tag snapshot for "
								+ registryName(f, reg) + "; falling back to frozen flag only", unwrap(freezeFailed));
						if (frozenField.getBoolean(reg) != true) {
							frozenField.setBoolean(reg, true);
							changed = true;
						}
					}
				} else {
					try {
						unfreeze.invoke(reg);
						changed = true;
					} catch (Throwable unfreezeFailed) {
						if (frozenField.getBoolean(reg) != false) {
							frozenField.setBoolean(reg, false);
							changed = true;
						}
					}
					frozenTagsField.set(reg, unbound);
				}
				if (changed) toggled.add(reg);
			}
			if (!toggled.isEmpty()) {
				ForbricLog.info("[Forbric/RegistryBridge] " + (frozen ? "froze " : "unfroze ") + toggled.size()
						+ " Forge-wrapped registr" + (toggled.size() == 1 ? "y" : "ies")
						+ " around NeoForge's registry lifecycle");
			}
			if (frozen) {
				rebuildNeoBlockStateIdsIfMissing(cl);
				validateNeoFrozenSnapshot(cl);
			}
		} catch (Throwable t) {
			// No traditional-Forge runtime present (pure-NeoForge instance) — nothing to do.
		}
		return toggled;
	}

	/**
	 * Defensive boundary for registry/tag network sync. Some merged paths open Forge wrappers indirectly after the
	 * main mod-loading windows; if a wrapper reaches sync with {@code frozen=true} but an unbound tag snapshot,
	 * vanilla's {@code TagNetworkSerialization} hard-crashes. Repair only those wrappers, leaving already-bound
	 * registries untouched.
	 */
	public static int ensureForgeWrapperTagsBound(ClassLoader cl) {
		List<String> rebound = new ArrayList<>();
		try {
			Class<?> builtin = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
			Class<?> registryCls = Class.forName("net.minecraft.core.Registry", false, cl);
			Class<?> wrapperCls = Class.forName("net.minecraftforge.registries.NamespacedWrapper", false, cl);
			Class<?> tagSetCls = Class.forName("net.minecraft.core.MappedRegistry$TagSet", false, cl);
			Method unfreeze = wrapperCls.getMethod("unfreeze");
			Method freezeMethod = wrapperCls.getMethod("freeze");
			unfreeze.setAccessible(true);
			freezeMethod.setAccessible(true);
			Method isBound = tagSetCls.getMethod("isBound");
			Field frozenTagsField = findField(wrapperCls, "frozenTags");
			if (frozenTagsField == null) return 0;
			frozenTagsField.setAccessible(true);

			for (Field f : builtin.getFields()) {
				if (!registryCls.isAssignableFrom(f.getType())) continue;
				Object reg = f.get(null);
				if (reg == null || !wrapperCls.isInstance(reg)) continue;

				Object frozenTags = frozenTagsField.get(reg);
				if (isTagSetBound(isBound, frozenTags)) continue;

				try {
					refreezeWrapper(reg, unfreeze, freezeMethod, frozenTagsField, isBound);
					rebound.add(registryName(f, reg));
				} catch (Throwable repairFailed) {
					ForbricLog.warn("[Forbric/RegistryBridge] could not bind Forge tag snapshot for "
							+ registryName(f, reg) + " before registry sync", unwrap(repairFailed));
				}
			}
			if (!rebound.isEmpty()) {
				ForbricLog.info("[Forbric/RegistryBridge] rebound " + rebound.size()
						+ " Forge registry tag snapshot" + (rebound.size() == 1 ? "" : "s")
						+ " before registry sync");
				ForbricLog.debug("[Forbric/RegistryBridge] rebound registry tag snapshots: " + rebound);
			}
		} catch (Throwable t) {
			// No traditional-Forge runtime present (pure-NeoForge instance) — nothing to do.
		}
		verifyNeoBlockStateIdsBound(cl);
		validateNeoFrozenSnapshot(cl);
		return rebound.size();
	}

	/**
	 * Verification-only sync-boundary probe: by the time registry/chunk sync runs, the final freeze path should have
	 * rebuilt NeoForge's block-state id map already. This method never mutates runtime state.
	 */
	public static int verifyNeoBlockStateIdsBound(ClassLoader cl) {
		try {
			BlockStateIdView view = reflectBlockStateIdView(cl);
			BlockStateIdCounts counts = countBlockStateIds(view.blockRegistry(), view.idMap(), view.getId(),
					view.getStateDefinition(), view.getPossibleStates());
			if (counts.missing == 0 && counts.mapped == counts.total) return 0;

			ForbricLog.warn("[Forbric/RegistryBridge] NeoForge block-state id map still misses " + counts.missing
					+ " state(s) at the registry sync boundary; the freeze-complete repair path should have fixed this");
			return counts.missing;
		} catch (ClassNotFoundException | LinkageError ignored) {
			return 0;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/RegistryBridge] could not verify NeoForge block-state id map", unwrap(t));
			return 0;
		}
	}

	/**
	 * The merged base's {@code Block.BLOCK_STATE_REGISTRY} is NeoForge's block-state id map, while Forge's
	 * {@code NamespacedWrapper} owns the final block registry freeze on the mixed base. Rebuild the Neo map from
	 * the final block registry after each completed merged freeze so chunk palette serialization never writes
	 * id {@code -1} for a real block state.
	 */
	public static int rebuildNeoBlockStateIdsIfMissing(ClassLoader cl) {
		try {
			BlockStateIdView view = reflectBlockStateIdView(cl);
			BlockStateIdCounts counts = countBlockStateIds(view.blockRegistry(), view.idMap(), view.getId(),
					view.getStateDefinition(), view.getPossibleStates());
			if (counts.missing == 0 && counts.mapped == counts.total) return 0;

			Method clear = findMethod(view.idMap().getClass(), "clear");
			if (clear == null) return 0;
			clear.setAccessible(true);

			clear.invoke(view.idMap());
			int added = 0;
			for (Object block : iterable(view.blockRegistry())) {
				Object stateDefinition = view.getStateDefinition().invoke(block);
				Object states = view.getPossibleStates().invoke(stateDefinition);
				for (Object state : iterable(states)) {
					view.add().invoke(view.idMap(), state);
					added++;
				}
			}
			try {
				Class.forName("net.minecraft.world.level.levelgen.DebugLevelSource", false, cl)
						.getMethod("initValidStates").invoke(null);
			} catch (Throwable ignored) {
				// This cache is absent or not loadable on some stripped runtimes; block-state ids are already fixed.
			}
			ForbricLog.info("[Forbric/RegistryBridge] rebuilt NeoForge block-state id map from final block registry "
					+ "(" + added + " state" + (added == 1 ? "" : "s") + ", " + counts.missing
					+ " missing before rebuild)");
			return added;
		} catch (ClassNotFoundException | LinkageError ignored) {
			return 0;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/RegistryBridge] could not rebuild NeoForge block-state id map", unwrap(t));
			return 0;
		}
	}

	/**
	 * Verifies that NeoForge's frozen registry snapshot can still resolve every holder it wants to remap. This
	 * catches broken snapshot/live-registry drift before {@code RegistryManager.revertToFrozen()} does.
	 */
	public static int validateNeoFrozenSnapshot(ClassLoader cl) {
		try {
			Class<?> registryManager = Class.forName("net.neoforged.neoforge.registries.RegistryManager", false, cl);
			Field frozenSnapshotField = findField(registryManager, "frozenSnapshot");
			if (frozenSnapshotField == null) return 0;
			frozenSnapshotField.setAccessible(true);
			Object frozenSnapshot = frozenSnapshotField.get(null);
			if (!(frozenSnapshot instanceof Map<?, ?> snapshots) || snapshots.isEmpty()) return 0;

			Class<?> builtinsCls = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
			Object registryOfRegistries = builtinsCls.getField("REGISTRY").get(null);
				Method containsRegistry = findMethod(registryOfRegistries.getClass(), "containsKey",
						Class.forName("net.minecraft.resources.Identifier", false, cl));
				Method getRegistry = findMethod(registryOfRegistries.getClass(), "getValue",
						Class.forName("net.minecraft.resources.Identifier", false, cl));
				if (containsRegistry == null || getRegistry == null) return 0;
				containsRegistry.setAccessible(true);
				getRegistry.setAccessible(true);
				Class<?> resourceKeyCls = Class.forName("net.minecraft.resources.ResourceKey", false, cl);
				Method createResourceKey = resourceKeyCls.getMethod("create", resourceKeyCls,
						Class.forName("net.minecraft.resources.Identifier", false, cl));
				Class<?> snapshotCls = Class.forName("net.neoforged.neoforge.registries.RegistrySnapshot", false, cl);
				Method getIds = snapshotCls.getMethod("getIds");
				createResourceKey.setAccessible(true);
				getIds.setAccessible(true);

			List<String> broken = new ArrayList<>();
			for (Map.Entry<?, ?> entry : snapshots.entrySet()) {
				Object registryName = entry.getKey();
				Object snapshot = entry.getValue();
				Object ids = getIds.invoke(snapshot);
				if (!(ids instanceof Map<?, ?> idMap) || idMap.isEmpty()) continue;
				if (!Boolean.TRUE.equals(containsRegistry.invoke(registryOfRegistries, registryName))) {
					broken.add(String.valueOf(registryName) + " (live registry missing)");
					continue;
				}

				Object liveRegistry = getRegistry.invoke(registryOfRegistries, registryName);
				Method registryKey = liveRegistry.getClass().getMethod("key");
				Method getHolder = findMethod(liveRegistry.getClass(), "get", resourceKeyCls);
				registryKey.setAccessible(true);
				if (getHolder == null) {
					broken.add(String.valueOf(registryName) + " (missing holder lookup)");
					continue;
				}
				getHolder.setAccessible(true);
				Object rootKey = registryKey.invoke(liveRegistry);
				for (Object value : idMap.values()) {
					Object elementKey = createResourceKey.invoke(null, rootKey, value);
					Object holder = getHolder.invoke(liveRegistry, elementKey);
					if (holder instanceof Optional<?> optional && optional.isPresent()) continue;
					broken.add(String.valueOf(registryName) + " -> " + value);
					if (broken.size() >= 16) break;
				}
				if (broken.size() >= 16) break;
			}

			if (!broken.isEmpty()) {
				ForbricLog.error("[Forbric/RegistryBridge] NeoForge frozen snapshot is inconsistent with the live registries; "
						+ "the frozen snapshot restore would later crash. Sample broken entries: " + broken);
			}
			return broken.size();
		} catch (ClassNotFoundException | LinkageError ignored) {
			return 0;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/RegistryBridge] could not validate NeoForge frozen registry snapshot", unwrap(t));
			return 0;
		}
	}

	/**
	 * Removes frozen-snapshot id entries that NeoForge would otherwise feed into
	 * {@code BaseMappedRegistry.registerIdMapping} with a null live holder during disconnect. Missing entries are
	 * still left for NeoForge's own missing-entry reporting path; only holder-inconsistent mappings are pruned.
	 */
	public static int pruneNeoFrozenSnapshotHolderGaps(ClassLoader cl) {
		if (!isMergedForgeNeoBase(cl)) return 0;
		try {
			Class<?> registryManager = Class.forName("net.neoforged.neoforge.registries.RegistryManager", false, cl);
			Field frozenSnapshotField = findField(registryManager, "frozenSnapshot");
			if (frozenSnapshotField == null) return 0;
			frozenSnapshotField.setAccessible(true);
			Object frozenSnapshot = frozenSnapshotField.get(null);
			if (!(frozenSnapshot instanceof Map<?, ?> snapshots) || snapshots.isEmpty()) return 0;

			Class<?> identifierCls = Class.forName("net.minecraft.resources.Identifier", false, cl);
			Class<?> resourceKeyCls = Class.forName("net.minecraft.resources.ResourceKey", false, cl);
			Class<?> builtinsCls = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
			Object registryOfRegistries = builtinsCls.getField("REGISTRY").get(null);
			Method containsRegistry = findMethod(registryOfRegistries.getClass(), "containsKey", identifierCls);
			Method getRegistry = findMethod(registryOfRegistries.getClass(), "getValue", identifierCls);
			Method createResourceKey = resourceKeyCls.getMethod("create", resourceKeyCls, identifierCls);
			if (containsRegistry == null || getRegistry == null) return 0;
			containsRegistry.setAccessible(true);
			getRegistry.setAccessible(true);
			createResourceKey.setAccessible(true);

			Class<?> snapshotCls = Class.forName("net.neoforged.neoforge.registries.RegistrySnapshot", false, cl);
			Field idsField = findField(snapshotCls, "ids");
			Field binaryField = findField(snapshotCls, "binary");
			if (idsField == null) return 0;
			idsField.setAccessible(true);
			if (binaryField != null) binaryField.setAccessible(true);

			int pruned = 0;
			List<String> sample = new ArrayList<>();
			for (Map.Entry<?, ?> snapshotEntry : snapshots.entrySet()) {
				Object registryName = snapshotEntry.getKey();
				if (!Boolean.TRUE.equals(containsRegistry.invoke(registryOfRegistries, registryName))) continue;
				Object liveRegistry = getRegistry.invoke(registryOfRegistries, registryName);
				Method registryKey = liveRegistry.getClass().getMethod("key");
				Method containsKey = findMethod(liveRegistry.getClass(), "containsKey", resourceKeyCls);
				Method getHolder = findMethod(liveRegistry.getClass(), "get", resourceKeyCls);
				Field vanillaByKey = findField(liveRegistry.getClass(), "byKey");
				if (containsKey == null || (getHolder == null && vanillaByKey == null)) continue;
				registryKey.setAccessible(true);
				containsKey.setAccessible(true);
				if (getHolder != null) getHolder.setAccessible(true);
				if (vanillaByKey != null) vanillaByKey.setAccessible(true);
				Object rootKey = registryKey.invoke(liveRegistry);

				Object idsObj = idsField.get(snapshotEntry.getValue());
				if (!(idsObj instanceof Map<?, ?> ids) || ids.isEmpty()) continue;
				Map<Object, Object> copy = new HashMap<>();
				for (Map.Entry<?, ?> idEntry : ids.entrySet()) {
					copy.put(idEntry.getKey(), idEntry.getValue());
				}

				int removedFromRegistry = 0;
				for (Map.Entry<Object, Object> idEntry : copy.entrySet()) {
					Object elementKey = createResourceKey.invoke(null, rootKey, idEntry.getValue());
					if (!Boolean.TRUE.equals(containsKey.invoke(liveRegistry, elementKey))) continue;
					if (hasRegisterIdMappingHolder(liveRegistry, elementKey, getHolder, vanillaByKey)) continue;

					@SuppressWarnings("unchecked")
					Map<Object, Object> mutableIds = (Map<Object, Object>) idsObj;
					mutableIds.remove(idEntry.getKey());
					pruned++;
					removedFromRegistry++;
					if (sample.size() < 16) sample.add(String.valueOf(registryName) + " -> " + idEntry.getValue());
				}
				if (removedFromRegistry > 0 && binaryField != null) {
					binaryField.set(snapshotEntry.getValue(), null);
				}
			}

			if (pruned > 0) {
				ForbricLog.warn("[Forbric/RegistryBridge] pruned " + pruned
						+ " holder-inconsistent NeoForge frozen snapshot mapping(s) before frozen snapshot restore; sample: "
						+ sample);
			}
			return pruned;
		} catch (ClassNotFoundException | LinkageError ignored) {
			return 0;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/RegistryBridge] could not sanitize NeoForge frozen registry snapshot", unwrap(t));
			return 0;
		}
	}

	private record BlockStateIdCounts(int total, int mapped, int missing) {
	}

	private record BlockStateIdView(Object idMap, Method getId, Method add, Object blockRegistry,
			Method getStateDefinition, Method getPossibleStates) {
	}

	private static BlockStateIdView reflectBlockStateIdView(ClassLoader cl) throws ReflectiveOperationException {
		Class<?> gameData = Class.forName("net.neoforged.neoforge.registries.GameData", false, cl);
		Object idMap = gameData.getMethod("getBlockStateIDMap").invoke(null);
		Class<?> idMapperClass = Class.forName("net.minecraft.core.IdMapper", false, cl);
		Method getId = idMapperClass.getMethod("getId", Object.class);
		Method add = idMapperClass.getMethod("add", Object.class);

		Class<?> builtin = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
		Object blockRegistry = builtin.getField("BLOCK").get(null);
		Method getStateDefinition = Class.forName("net.minecraft.world.level.block.Block", false, cl)
				.getMethod("getStateDefinition");
		Method getPossibleStates = Class.forName("net.minecraft.world.level.block.state.StateDefinition", false, cl)
				.getMethod("getPossibleStates");
		return new BlockStateIdView(idMap, getId, add, blockRegistry, getStateDefinition, getPossibleStates);
	}

	private static BlockStateIdCounts countBlockStateIds(Object blockRegistry, Object idMap, Method getId,
			Method getStateDefinition, Method getPossibleStates) throws ReflectiveOperationException {
		int total = 0;
		int mapped = 0;
		int missing = 0;
		for (Object block : iterable(blockRegistry)) {
			Object stateDefinition = getStateDefinition.invoke(block);
			Object states = getPossibleStates.invoke(stateDefinition);
			for (Object state : iterable(states)) {
				total++;
				int id = (Integer) getId.invoke(idMap, state);
				if (id == -1) {
					missing++;
				} else {
					mapped++;
				}
			}
		}
		return new BlockStateIdCounts(total, mapped, missing);
	}

	private static boolean hasRegisterIdMappingHolder(Object registry, Object elementKey, Method getHolder,
			Field vanillaByKey) throws ReflectiveOperationException {
		if (vanillaByKey != null) {
			Object byKeyObj = vanillaByKey.get(registry);
			if (byKeyObj instanceof Map<?, ?> byKey) {
				return byKey.get(elementKey) != null;
			}
		}
		if (getHolder == null) return false;
		Object holder = getHolder.invoke(registry, elementKey);
		return holder instanceof Optional<?> optional && optional.isPresent();
	}

	private static Iterable<?> iterable(Object value) {
		if (value instanceof Iterable<?> iterable) return iterable;
		throw new IllegalArgumentException("Expected Iterable, got " + (value == null ? "null" : value.getClass()));
	}

	private static void refreezeWrapper(Object reg, Method unfreeze, Method freezeMethod, Field frozenTagsField,
			Method isBound) throws Throwable {
		unfreeze.invoke(reg);
		freezeMethod.invoke(reg);
		if (!isTagSetBound(isBound, frozenTagsField.get(reg))) {
			throw new IllegalStateException("NamespacedWrapper.freeze() returned with unbound tags");
		}
	}

	private static boolean isTagSetBound(Method isBound, Object tagSet) throws ReflectiveOperationException {
		return tagSet != null && Boolean.TRUE.equals(isBound.invoke(tagSet));
	}

	private static String registryName(Field field, Object reg) {
		try {
			return String.valueOf(reg.getClass().getMethod("key").invoke(reg));
		} catch (Throwable ignored) {
			return field.getName();
		}
	}

	private static Throwable unwrap(Throwable t) {
		while (t instanceof InvocationTargetException invocation && invocation.getCause() != null) {
			t = invocation.getCause();
		}
		return t;
	}

	private static Field findField(Class<?> c, String name) {
		for (; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException ignore) {
				// try superclass
			}
		}
		return null;
	}

	private static Method findMethod(Class<?> c, String name, Class<?>... parameterTypes) {
		for (; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredMethod(name, parameterTypes);
			} catch (NoSuchMethodException ignore) {
				// try superclass
			}
		}
		return null;
	}
}
