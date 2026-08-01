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

package net.forbric.kernel.fabric;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

import net.forbric.kernel.util.ForbricLog;

/**
 * The language adapters this instance can construct entrypoints with — the default one, plus every adapter a
 * discovered mod declares in its {@code languageAdapters} block.
 *
 * <p>An adapter is an ordinary mod class implementing {@link LanguageAdapter}: {@code fabric-language-kotlin}
 * ships {@code net.fabricmc.language.kotlin.KotlinAdapter}, which resolves a Kotlin {@code object} singleton
 * through its {@code INSTANCE} field and hands anything else back to {@link LanguageAdapter#getDefault()}. Mods
 * written in Kotlin then name it per entrypoint. Without this the kernel could only construct plain Java
 * entrypoints, and every Kotlin mod died on "language adapter 'kotlin' is not supported yet" — Zoomify's whole
 * client entrypoint, in a pack where it is one of forty-one mods.
 *
 * <p>Adapters are declared by one mod and used by another, so the map is built from ALL mods before any
 * entrypoint is constructed. Instantiation is lazy and cached: an adapter whose own class fails to load must
 * fail the entrypoints that name it, not the whole discovery pass.
 */
public final class KernelLanguageAdapters {
	/** The adapter name an entrypoint gets when it names none. */
	public static final String DEFAULT = "default";

	private static final LanguageAdapter DEFAULT_ADAPTER = new DefaultAdapter();

	/** adapter name -> declaring class name, from every discovered mod's {@code languageAdapters}. */
	private static final Map<String, String> DECLARED = new ConcurrentHashMap<>();
	/** adapter name -> the instance, constructed on first use. */
	private static final Map<String, LanguageAdapter> INSTANCES = new ConcurrentHashMap<>();

	private static volatile ClassLoader gameLoader;

	private KernelLanguageAdapters() {
	}

	/** The plain-Java adapter, also handed to third-party adapters through {@code LanguageAdapter.getDefault()}. */
	public static LanguageAdapter defaultAdapter() {
		return DEFAULT_ADAPTER;
	}

	/** Forgets every declaration and instance; called when a new loader is built (tests, relaunch). */
	public static void reset() {
		DECLARED.clear();
		INSTANCES.clear();
		gameLoader = null;
	}

	/** The loader adapter classes are resolved from — the same one entrypoints are. */
	public static void bindGameLoader(ClassLoader loader) {
		gameLoader = loader;
	}

	/**
	 * Records one mod's {@code languageAdapters} block. A duplicate name keeps the FIRST declaration, matching how
	 * the kernel resolves duplicate mod ids, and is reported because two adapters answering to one name means the
	 * entrypoints naming it are built by whichever mod happened to be discovered first.
	 */
	public static void declare(String modId, Map<String, String> adapters) {
		if (adapters == null) return;

		adapters.forEach((name, className) -> {
			if (name == null || className == null || name.isBlank() || className.isBlank()) return;

			String existing = DECLARED.putIfAbsent(name, className);
			if (existing != null && !existing.equals(className)) {
				ForbricLog.warn("[Forbric/Fabric] %s declares language adapter '%s' as %s, but %s is already "
						+ "registered under that name — keeping the first", modId, name, className, existing);
			}
		});
	}

	/** How many distinct adapters mods have declared, for the boot summary. */
	public static int declaredCount() {
		return DECLARED.size();
	}

	/** The declared adapter names, for diagnostics. */
	public static java.util.Set<String> declaredNames() {
		return java.util.Set.copyOf(DECLARED.keySet());
	}

	/**
	 * The adapter for {@code name}, constructing it on first use.
	 *
	 * @throws LanguageAdapterException if no mod declares that name, or its class cannot be instantiated
	 */
	public static LanguageAdapter get(String name) throws LanguageAdapterException {
		if (name == null || name.isBlank() || DEFAULT.equals(name)) return DEFAULT_ADAPTER;

		LanguageAdapter cached = INSTANCES.get(name);
		if (cached != null) return cached;

		String className = DECLARED.get(name);
		if (className == null) {
			throw new LanguageAdapterException("no mod provides the language adapter '" + name
					+ "' (a mod declaring it in fabric.mod.json's languageAdapters block is missing)");
		}

		try {
			ClassLoader loader = gameLoader == null ? Thread.currentThread().getContextClassLoader() : gameLoader;
			Object instance = Class.forName(className, true, loader).getDeclaredConstructor().newInstance();

			if (!(instance instanceof LanguageAdapter adapter)) {
				throw new LanguageAdapterException(className + " is registered as the language adapter '" + name
						+ "' but does not implement LanguageAdapter");
			}
			INSTANCES.put(name, adapter);
			ForbricLog.info("[Forbric/Fabric] language adapter '%s' ready (%s)", name, className);
			return adapter;
		} catch (LanguageAdapterException rethrow) {
			throw rethrow;
		} catch (Throwable t) {
			throw new LanguageAdapterException("could not construct the language adapter '" + name + "' ("
					+ className + ")", t);
		}
	}

	/**
	 * Plain Java resolution: {@code a.b.C} is instantiated through its no-arg constructor, {@code a.b.C::member}
	 * resolves to a static field's value or binds a static method to the requested functional interface.
	 *
	 * <p>This is the same resolution the kernel has always applied to an adapter-less entrypoint, lifted here so a
	 * third-party adapter delegating through {@code LanguageAdapter.getDefault()} lands on exactly it.
	 */
	private static final class DefaultAdapter implements LanguageAdapter {
		@Override
		public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
			try {
				ClassLoader loader = gameLoader == null ? Thread.currentThread().getContextClassLoader() : gameLoader;
				int sep = value.indexOf("::");

				if (sep < 0) {
					return type.cast(Class.forName(value, true, loader).getDeclaredConstructor().newInstance());
				}

				Class<?> owner = Class.forName(value.substring(0, sep), true, loader);
				String member = value.substring(sep + 2);

				for (Field field : owner.getDeclaredFields()) {
					if (field.getName().equals(member) && Modifier.isStatic(field.getModifiers())) {
						field.setAccessible(true);
						return type.cast(field.get(null));
					}
				}

				for (Method method : owner.getDeclaredMethods()) {
					if (!method.getName().equals(member) || !Modifier.isStatic(method.getModifiers())) continue;

					method.setAccessible(true);
					MethodHandle handle = MethodHandles.lookup().unreflect(method);
					// Binds the static method to the requested functional interface without generating a class in
					// the mod's package (LambdaMetafactory would need a lookup inside the mod's own class).
					return MethodHandleProxies.asInterfaceInstance(type, handle);
				}

				throw new NoSuchMethodException("no static member '" + member + "' on " + owner.getName());
			} catch (Throwable t) {
				throw new LanguageAdapterException("could not construct '" + value + "' of mod "
						+ (mod == null ? "?" : mod.getMetadata().getId()), t);
			}
		}
	}
}
