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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;

/**
 * Resolves a registry alias for the registries whose lookups are traditional-Forge wrappers.
 *
 * <p>A registry alias lets a mod rename an entry without breaking the old id: {@code waystones:waystone} is an
 * alias for {@code waystones:andesite_waystone}, and waystones' own datapack still says {@code waystones:waystone}.
 * Both loader families support this — NeoForge in {@code BaseMappedRegistry}, fabric-api in
 * {@code fabric-registry-sync-v0}'s {@code MappedRegistryMixin} — and on a genuine instance exactly one of those is
 * live and complete.
 *
 * <p>On the merged base NEITHER is complete. NeoForge's {@code IRegistryExtension.addAlias} did not survive the
 * byte merge at all: the merged {@code MappedRegistry} has no {@code addAlias}. fabric-api's mixin does, and it
 * wins by default — so a NEOFORGE mod's alias call lands in fabric-api's map, which is fine. But fabric-api
 * implements the READING half as {@code @ModifyVariable} on eight {@code MappedRegistry} lookups, and
 * {@code BuiltInRegistries.BLOCK} on the merged base is a {@code net.minecraftforge.registries
 * .NamespacedDefaultedWrapper} — a {@code MappedRegistry} subclass that OVERRIDES every one of those eight to
 * delegate to a {@code ForgeRegistry}. {@code addAlias} is mixin-ADDED, so it is inherited and works. The eight
 * readers are overridden, so the rewrite never runs. Aliases are recorded and never honoured.
 *
 * <p>What that costs: waystones' own {@code configured_feature} JSON says {@code waystones:waystone}, the block
 * lookup misses, {@code Failed to parse waystones:waystone from pack …}, and because waystones also adds those ids
 * to the VANILLA {@code minecraft:enchantable/durability} tag, that tag drops too and vanilla's own
 * {@code minecraft:mending}, {@code unbreaking} and {@code vanishing_curse} fail to parse with it. One unresolved
 * alias takes the whole registry load down. It stayed invisible only because nothing read a Forge-family mod's
 * {@code data/} until {@link KernelDataPacks} started serving it.
 *
 * <p>This class is the reading half, put back on the wrapper by {@code RegistryAliasParityInjector}. It mirrors
 * fabric-api's semantics exactly — {@code aliases.getOrDefault(id, id)} for an {@code Identifier}, and for a
 * {@code ResourceKey} a null-safe lookup that rebuilds the key in the same registry — because the point is parity,
 * not a second opinion. It reads fabric-api's map rather than keeping one of its own, so an alias registered
 * through either ecosystem resolves in both.
 */
public final class KernelRegistryAliases {
	/** {@code off} restores the unresolved behaviour — i.e. puts the silently-ignored aliases back. */
	static final String PROPERTY = "forbric.registryAliasParity";

	/**
	 * Per-registry-class cache of fabric-api's mixin-added {@code aliases} field, or empty when this build has none.
	 *
	 * <p>A {@code ClassValue} rather than one static {@code Field}: the field is added to {@code MappedRegistry}, so
	 * every wrapper resolves to the same one in practice, but a single cached {@code Field} would hand the wrong
	 * declaring class an {@code IllegalArgumentException} and silently turn aliases off for it. Per-class is the
	 * shape that cannot be wrong, and it does not pin classes the way a map keyed on {@code Class} would.
	 */
	private static final ClassValue<Optional<Field>> ALIASES = new ClassValue<>() {
		@Override
		protected Optional<Field> computeValue(Class<?> type) {
			return Optional.ofNullable(findAliases(type));
		}
	};

	/**
	 * Read once and remembered: this sits in front of every id-keyed lookup on a Forge-wrapped registry, and
	 * {@code System.getProperty} goes through a synchronized {@code Hashtable}. It is a launch flag either way.
	 */
	private static volatile Boolean enabled;

	private static volatile Method identifierOf;
	private static volatile Method createKey;
	private static final AtomicBoolean REPORTED = new AtomicBoolean();

	private KernelRegistryAliases() {
	}

	static boolean enabled() {
		Boolean known = enabled;
		if (known == null) {
			known = !"off".equalsIgnoreCase(String.valueOf(System.getProperty(PROPERTY, "on")).trim());
			enabled = known;
		}
		return known;
	}

	/** Forgets the cached reflection — for tests. */
	static void resetForTests() {
		enabled = null;
		identifierOf = null;
		createKey = null;
		REPORTED.set(false);
	}

	/**
	 * The id {@code registry} should actually be asked for: the alias target if there is one, else {@code id}.
	 *
	 * <p>Hot enough to be worth the two early exits — this sits in front of every {@code Identifier} lookup on a
	 * Forge-wrapped registry, and the overwhelmingly common case is a registry with no aliases at all.
	 */
	public static Object resolveId(Object registry, Object id) {
		Map<?, ?> aliases = aliasesOf(registry);
		if (aliases == null || aliases.isEmpty() || id == null) return id;
		Object target = aliases.get(id);
		return target == null ? id : target;
	}

	/** The {@code ResourceKey} form: same map, keyed by the key's id, rebuilt in the same registry. */
	public static Object resolveKey(Object registry, Object key) {
		if (key == null) return null;
		Map<?, ?> aliases = aliasesOf(registry);
		if (aliases == null || aliases.isEmpty()) return key;
		try {
			Method identifier = identifierOf;
			// Re-derive if the cache was filled by a different key class — one process only ever has one
			// ResourceKey, but a stale Method would throw on every lookup rather than simply being slower.
			if (identifier == null || !identifier.getDeclaringClass().isInstance(key)) {
				identifier = key.getClass().getMethod("identifier");
				identifierOf = identifier;
				createKey = null;
			}
			Object target = aliases.get(identifier.invoke(key));
			if (target == null) return key;

			Method create = createKey;
			if (create == null) {
				// ResourceKey.create(ResourceKey<? extends Registry<T>> registryKey, Identifier id)
				for (Method m : key.getClass().getMethods()) {
					if ("create".equals(m.getName()) && m.getParameterCount() == 2
							&& java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
						create = m;
						break;
					}
				}
				if (create == null) return key;
				createKey = create;
			}
			Object registryKey = registry.getClass().getMethod("key").invoke(registry);
			return create.invoke(null, registryKey, target);
		} catch (Throwable t) {
			// A rebuild we cannot do is a lookup that behaves exactly as it does today — never a failed boot.
			return key;
		}
	}

	/**
	 * fabric-api's mixin-added {@code aliases} map on this registry, or {@code null} when this build has none.
	 *
	 * <p>The field is added by {@code fabric-registry-sync-v0}'s mixin, so it exists only when fabric-api is
	 * installed — and the kernel has to run without it. The result of that one lookup is cached, including the
	 * negative, because this runs in front of every wrapped registry lookup.
	 */
	private static Map<?, ?> aliasesOf(Object registry) {
		if (registry == null || !enabled()) return null;
		Optional<Field> field = ALIASES.get(registry.getClass());
		if (field.isEmpty()) {
			if (REPORTED.compareAndSet(false, true)) {
				ForbricLog.debug("[Forbric/Aliases] no fabric-api alias map on %s — registry aliases are whatever "
						+ "the Forge wrapper does natively", registry.getClass().getName());
			}
			return null;
		}
		try {
			Object map = field.get().get(registry);
			return map instanceof Map<?, ?> typed ? typed : null;
		} catch (Throwable t) {
			return null;
		}
	}

	private static Field findAliases(Class<?> type) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField("aliases");
				if (Map.class.isAssignableFrom(f.getType())) {
					f.setAccessible(true);
					return f;
				}
			} catch (NoSuchFieldException keepLooking) {
				// fabric-api adds it to MappedRegistry, so it is usually a superclass of the wrapper
			}
		}
		return null;
	}
}
