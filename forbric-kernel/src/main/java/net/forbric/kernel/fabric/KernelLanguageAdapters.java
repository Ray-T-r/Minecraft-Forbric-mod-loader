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
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

import net.forbric.kernel.util.Caught;
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
 * entrypoint is constructed. Every declared adapter is then constructed up front ({@link #constructAll}), as Fabric
 * Loader's {@code freeze()} does, because some adapters exist only for their constructor: Configured Defaults copies
 * a pack's default config files from it and names no entrypoint at all, so constructing on first use meant it never
 * ran. A failed construction is kept: it fails the entrypoints that name the adapter, not the whole discovery pass,
 * and asking again answers with the same failure rather than running a constructor with side effects twice.
 */
public final class KernelLanguageAdapters {
	/** The adapter name an entrypoint gets when it names none. */
	public static final String DEFAULT = "default";

	/**
	 * {@code -Dforbric.eagerLanguageAdapters=off}: construct an adapter only when an entrypoint first names it, as
	 * before — which leaves an adapter that does its work in its constructor and is named by nothing unconstructed.
	 */
	public static final String EAGER_PROPERTY = "forbric.eagerLanguageAdapters";

	private static final LanguageAdapter DEFAULT_ADAPTER = new DefaultAdapter();

	/** adapter name -> declaring class name, from every discovered mod's {@code languageAdapters}. */
	private static final Map<String, String> DECLARED = new ConcurrentHashMap<>();
	/** adapter name -> the mod whose declaration {@link #DECLARED} kept. */
	private static final Map<String, String> OWNERS = new ConcurrentHashMap<>();
	/** adapter name -> the instance, constructed up front or on first use. */
	private static final Map<String, LanguageAdapter> INSTANCES = new ConcurrentHashMap<>();
	/** adapter name -> why it could not be constructed. */
	private static final Map<String, LanguageAdapterException> FAILED = new ConcurrentHashMap<>();

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
		OWNERS.clear();
		INSTANCES.clear();
		FAILED.clear();
		gameLoader = null;
	}

	/** Whether {@link #constructAll} runs, per {@link #EAGER_PROPERTY}. */
	public static boolean eager() {
		return !"off".equalsIgnoreCase(System.getProperty(EAGER_PROPERTY, "on"));
	}

	/** An adapter that could not be constructed, and the mod that declared it. */
	public record Failure(String name, String modId, String className, LanguageAdapterException cause) {
	}

	/**
	 * Constructs every declared adapter, in the order given — the mods' order, which is the order Fabric Loader's
	 * {@code setupLanguageAdapters} walks them in. Names already constructed, or already failed, are not tried again.
	 *
	 * @param names adapter names in mod order; a name nothing declares is skipped
	 * @return the adapters that could not be constructed, in that order
	 */
	public static List<Failure> constructAll(List<String> names) {
		List<Failure> failures = new ArrayList<>();
		for (String name : names) {
			if (name == null || !DECLARED.containsKey(name) || INSTANCES.containsKey(name) || FAILED.containsKey(name)) {
				continue;
			}
			try {
				get(name);
			} catch (LanguageAdapterException e) {
				Caught.expected(e);
				failures.add(new Failure(name, OWNERS.get(name), DECLARED.get(name), e));
			}
		}
		return failures;
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
			if (existing == null) OWNERS.put(name, modId == null ? "?" : modId);
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
	 * The adapter for {@code name}: the instance {@link #constructAll} made, or constructed now.
	 *
	 * <p>Constructed once. Two entrypoints asking at the same moment get one instance, and an adapter that failed is
	 * not constructed again: its constructor may already have done part of its work.
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

		synchronized (KernelLanguageAdapters.class) {
			cached = INSTANCES.get(name);
			if (cached != null) return cached;
			LanguageAdapterException failed = FAILED.get(name);
			if (failed != null) {
				throw new LanguageAdapterException("the language adapter '" + name + "' (" + className
						+ ") could not be constructed", failed);
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
			} catch (Throwable t) {
				LanguageAdapterException failure = t instanceof LanguageAdapterException adapterFailure
						? adapterFailure
						: new LanguageAdapterException("could not construct the language adapter '" + name + "' ("
								+ className + ")", t);
				FAILED.put(name, failure);
				throw failure;
			}
		}
	}

	/** Where {@link #resolveDefault} gets a class from: the caller decides the loader and whether to initialise. */
	@FunctionalInterface
	interface ClassSource {
		Class<?> load(String name) throws ClassNotFoundException;
	}

	/**
	 * Fabric Loader's {@code DefaultLanguageAdapter} resolution, for every value it accepts.
	 *
	 * <ul>
	 *   <li>{@code a.b.C} — a new instance through the no-arg constructor;</li>
	 *   <li>{@code a.b.C::field} — a static field's value;</li>
	 *   <li>{@code a.b.C::method} — the one method of that name bound to the requested functional interface: a static
	 *       one as it is, an instance one on a new {@code C} made through its no-arg constructor;</li>
	 *   <li>{@code a.b.C::<init>} — {@code C}'s one constructor, bound to the requested functional interface.</li>
	 * </ul>
	 *
	 * <p>The last two were missing, so an entrypoint written either way was refused here and ran on Fabric — the
	 * resolution only knew static members. Where Fabric REFUSES a value this one stays as lenient as it was: a
	 * private member is opened rather than refused, a non-static field of that name is passed over for a method,
	 * and of several methods of one name the first static one is taken, as before, where Fabric throws "Found
	 * multiple method entries". A mod relying on any of those cannot run on Fabric, so none can tell; a value Fabric
	 * accepts resolves to what Fabric resolves it to.
	 *
	 * <p>One implementation for both callers — an entrypoint with no adapter, and a third-party adapter delegating
	 * through {@code LanguageAdapter.getDefault()} — because there were two, and they had already been written out
	 * twice with the same gap.
	 */
	static Object resolveDefault(String value, Class<?> type, ClassSource classes) throws Throwable {
		int sep = value.indexOf("::");

		if (sep < 0) {
			return classes.load(value).getDeclaredConstructor().newInstance();
		}

		Class<?> owner = classes.load(value.substring(0, sep));
		String member = value.substring(sep + 2);

		for (Field field : owner.getDeclaredFields()) {
			if (field.getName().equals(member) && Modifier.isStatic(field.getModifiers())) {
				field.setAccessible(true);
				return field.get(null);
			}
		}

		List<Executable> candidates = new ArrayList<>();
		for (Method method : owner.getDeclaredMethods()) {
			if (method.getName().equals(member)) candidates.add(method);
		}
		if (CONSTRUCTOR.equals(member)) candidates.addAll(Arrays.asList(owner.getDeclaredConstructors()));

		Executable target = pick(candidates);
		if (target == null) {
			throw new NoSuchMethodException(candidates.isEmpty()
					? "no member '" + member + "' on " + owner.getName()
					: candidates.size() + " members named '" + member + "' on " + owner.getName()
							+ " and no single static one to choose");
		}

		target.setAccessible(true);
		MethodHandle handle = target instanceof Method method
				? MethodHandles.lookup().unreflect(method)
				: MethodHandles.lookup().unreflectConstructor((Constructor<?>) target);

		if (target instanceof Method method && !Modifier.isStatic(method.getModifiers())) {
			// Fabric's own receiver: a fresh instance of the declaring class, one per entrypoint.
			Constructor<?> receiver = owner.getDeclaredConstructor();
			receiver.setAccessible(true);
			handle = handle.bindTo(receiver.newInstance());
		}

		// Binds to the requested functional interface without generating a class in the mod's package
		// (LambdaMetafactory would need a lookup inside the mod's own class).
		return MethodHandleProxies.asInterfaceInstance(type, handle);
	}

	/** The member name Fabric reads as "the constructor". */
	static final String CONSTRUCTOR = "<init>";

	/** The one candidate, or of several the first static method; {@code null} when neither exists. */
	private static Executable pick(List<Executable> candidates) {
		if (candidates.size() == 1) return candidates.get(0);
		for (Executable candidate : candidates) {
			if (candidate instanceof Method && Modifier.isStatic(candidate.getModifiers())) return candidate;
		}
		return null;
	}

	/** Plain Java resolution, {@link #resolveDefault}, through the loader entrypoints resolve through. */
	private static final class DefaultAdapter implements LanguageAdapter {
		@Override
		public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
			try {
				ClassLoader loader = gameLoader == null ? Thread.currentThread().getContextClassLoader() : gameLoader;
				return type.cast(resolveDefault(value, type, name -> Class.forName(name, true, loader)));
			} catch (Throwable t) {
				throw new LanguageAdapterException("could not construct '" + value + "' of mod "
						+ (mod == null ? "?" : mod.getMetadata().getId()), t);
			}
		}
	}
}
