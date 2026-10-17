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

import net.forbric.kernel.util.Caught;
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
			Caught.warn(t, "[Forbric/Aliases] could not resolve the alias of %s; the lookup uses it as given", key);
			return key;
		}
	}

	/** {@code off} lets fabric-registry-sync warn about NeoForge's alias-first order as it does about any other. */
	static final String DEFERRED_REGISTER_ORDER = "forbric.aliasPresenceParity.deferredRegister";
	private static final String NEO_DEFERRED_REGISTER = "net.neoforged.neoforge.registries.DeferredRegister";

	/**
	 * Whether fabric-registry-sync's {@code addAlias} should consider its alias target present.
	 *
	 * <p>Called from that method's target-missing warning, which PostMixinFixups points at the registry's own
	 * {@code keySet()} instead of the vanilla map a MinecraftForge wrapper never fills; {@code keys} is that set. It
	 * answers the lookup exactly and never through an alias.
	 *
	 * <p>It also answers yes while NeoForge's {@code DeferredRegister.addEntries} is the caller. NeoForge applies a
	 * mod's aliases BEFORE it registers that mod's entries, in the same call, and its own {@code addAlias} never
	 * warns about it. On the merged base fabric-api's mixin-added {@code addAlias} overrides NeoForge's, so the
	 * order NeoForge chose produced "Adding sophisticatedcore:render_info_tag as an alias for …, but the latter
	 * doesn't exist" on every boot, for an entry registered a few lines later. A NeoForge mod gets its own
	 * ecosystem's behaviour there, not a Fabric warning about NeoForge's design. Walked only when the key set says
	 * no, and only a few frames: {@code addAlias} is rare, and the lambda NeoForge calls it through is hidden.
	 * {@code -Dforbric.aliasPresenceParity.deferredRegister=off} warns there again.
	 */
	public static boolean aliasTargetPresent(java.util.Set<?> keys, Object target) {
		if (keys != null && keys.contains(target)) return true;
		if ("off".equalsIgnoreCase(System.getProperty(DEFERRED_REGISTER_ORDER, "on"))) return false;
		return StackWalker.getInstance().walk(frames -> frames.limit(16).anyMatch(frame ->
				NEO_DEFERRED_REGISTER.equals(frame.getClassName()) && "addEntries".equals(frame.getMethodName())));
	}

	/**
	 * The alias map a mixin added to this registry's class (fabric-api's), or {@code null} when this build has none.
	 *
	 * <p>The field is added by a mod's mixin ({@code fabric-registry-sync-v0}'s), so it exists only when that mod is
	 * installed — and the kernel has to run without it. The result of that one lookup is cached, including the
	 * negative, because this runs in front of every wrapped registry lookup.
	 */
	private static Map<?, ?> aliasesOf(Object registry) {
		if (registry == null || !enabled()) return null;
		Optional<Field> field = ALIASES.get(registry.getClass());
		if (field.isEmpty()) {
			if (REPORTED.compareAndSet(false, true)) {
				ForbricLog.debug("[Forbric/Aliases] no mixin-added alias map on %s — registry aliases are whatever "
						+ "the Forge wrapper does natively", registry.getClass().getName());
			}
			return null;
		}
		try {
			Object map = field.get().get(registry);
			return map instanceof Map<?, ?> typed ? typed : null;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Aliases] could not read fabric-api's alias map on %s; its aliases are not followed",
					registry.getClass().getName());
			return null;
		}
	}

	/**
	 * The registry's alias map: the instance field nearest {@code type} declared {@code Map<Identifier, Identifier>} — an
	 * id to the id it stands for — whatever it is called. On the merged base that is the map fabric-registry-sync's mixin
	 * merges into {@code MappedRegistry} (its alias registration writes it, and it overrides NeoForge's), found before
	 * NeoForge's own on {@code BaseMappedRegistry}; without such a mixin, NeoForge's. Where one class level declares
	 * several such maps, the one a mixin merged is taken if it is the only one; otherwise none is guessed at.
	 */
	private static Field findAliases(Class<?> type) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			java.util.List<Field> maps = new java.util.ArrayList<>();
			for (Field f : c.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !Map.class.isAssignableFrom(f.getType())) continue;
				if (declaredIdToId(f)) maps.add(f);
			}
			if (maps.isEmpty()) continue;
			if (maps.size() > 1) maps.removeIf(f -> !Merged.by(f));
			if (maps.size() != 1) return null;
			try {
				maps.getFirst().setAccessible(true);
				return maps.getFirst();
			} catch (RuntimeException inaccessible) {
				Caught.warn(inaccessible, "[Forbric/Aliases] could not open the alias map %s.%s; its aliases are not followed",
						c.getName(), maps.getFirst().getName());
				return null;
			}
		}
		return null;
	}

	/**
	 * Whether {@code field} is declared {@code Map<Identifier, Identifier>}. Mixin merges a mixin's field without its generic
	 * signature, so a merged field that reads as a raw {@code Map} is judged by the signature it has in the mixin that
	 * merged it ({@code @MixinMerged(mixin = …)}), read from that mixin's class file.
	 */
	private static boolean declaredIdToId(Field field) {
		java.lang.reflect.Type type;
		try {
			type = field.getGenericType();
		} catch (RuntimeException | LinkageError unresolvable) {
			Caught.expected(unresolvable);
			return false;
		}
		if (idToId(type)) return true;
		if (!(type instanceof Class<?>)) return false;
		String mixin = Merged.mixinOf(field);
		ClassLoader loader = field.getDeclaringClass().getClassLoader();
		if (mixin == null || loader == null) return false;
		String wanted = "L" + MAP_TYPE + "<L" + IDENTIFIER_INTERNAL + ";L" + IDENTIFIER_INTERNAL + ";>;";
		try (java.io.InputStream in = loader.getResourceAsStream(mixin.replace('.', '/') + ".class")) {
			if (in == null) return false;
			String[] signature = new String[1];
			new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
				@Override
				public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor, String fieldSignature, Object value) {
					if (name.equals(field.getName()) && descriptor.equals(org.objectweb.asm.Type.getDescriptor(field.getType()))) signature[0] = fieldSignature;
					return null;
				}
			}, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
			return wanted.equals(signature[0]);
		} catch (java.io.IOException | RuntimeException unreadable) {
			Caught.warn(unreadable, "[Forbric/Aliases] could not read mixin %s for the signature of %s; it is not taken as an alias map", mixin, field.getName());
			return false;
		}
	}

	private static final String MAP_TYPE = "java/util/Map";
	private static final String IDENTIFIER_INTERNAL = "net/minecraft/resources/Identifier";

	/** {@code Map<Identifier, Identifier>}, by the declared type arguments. */
	private static boolean idToId(java.lang.reflect.Type type) {
		if (!(type instanceof java.lang.reflect.ParameterizedType generic) || generic.getActualTypeArguments().length != 2) return false;
		for (java.lang.reflect.Type argument : generic.getActualTypeArguments()) {
			if (!(argument instanceof Class<?> c) || !IDENTIFIER.equals(c.getName())) return false;
		}
		return true;
	}

	private static final String IDENTIFIER = "net.minecraft.resources.Identifier";

	/** Mixin's annotation, in a class of its own: a loader without Mixin fails here, inside the caller's lookup. */
	private static final class Merged {
		static boolean by(Field field) {
			return mixinOf(field) != null;
		}

		/** The mixin that merged {@code field}, dotted, or null when no mixin did. */
		static String mixinOf(Field field) {
			try {
				org.spongepowered.asm.mixin.transformer.meta.MixinMerged merged =
						field.getAnnotation(org.spongepowered.asm.mixin.transformer.meta.MixinMerged.class);
				return merged == null ? null : merged.mixin();
			} catch (LinkageError noMixin) {
				Caught.expected(noMixin);
				return null;
			}
		}
	}
}
