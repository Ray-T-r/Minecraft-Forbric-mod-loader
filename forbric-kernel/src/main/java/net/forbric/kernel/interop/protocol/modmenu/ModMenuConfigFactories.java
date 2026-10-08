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

package net.forbric.kernel.interop.protocol.modmenu;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.forbric.kernel.util.ForbricLog;

/**
 * Fabric mods' config screen factories, read from their {@code "modmenu"} entrypoints the way Mod Menu reads them.
 *
 * <p>Used when Mod Menu itself is not installed (with it, the kernel asks Mod Menu). The only place a Fabric mod says
 * it has a config screen is a {@code "modmenu"} entrypoint implementing Mod Menu's {@code ModMenuApi}; the kernel's
 * stand-in for that interface makes those classes linkable, and this reads them. Mod Menu's own reading is the
 * contract, because it is what every one of these mods was written and tested against:
 *
 * <ul>
 *   <li>per entrypoint, in declaration order, the providing mod's own {@code getModConfigScreenFactory()} is stored
 *       under its mod id with {@code put} — unless it is a {@code NullScreenFactory}, the API's default, which is how
 *       a mod says it has no screen (by not overriding the method, or by falling back to the default when its config
 *       library is missing);</li>
 *   <li>every entrypoint's {@code getProvidedConfigScreenFactories()} — a config library's way of supplying screens
 *       for the mods built on it — is merged with {@code putIfAbsent} AFTER all of the mods' own, so a mod's own
 *       factory always beats one a library offers for it; and, as Mod Menu does, again on every lookup, so a library
 *       that registers a screen later in the session is not missed;</li>
 *   <li>an entrypoint that throws while being built or asked is that mod's loss alone: logged once and skipped,
 *       never costing another mod its button or the player the screen.</li>
 * </ul>
 *
 * <p>One deliberate difference: the entrypoints are read lazily, on the first question — after every mod's client
 * initializer has run, which is later than Mod Menu reads, never earlier. Whether a mod HAS a config is decided
 * without building its screen, as under Mod Menu. Update checkers and modpack badges are never asked for — the kernel
 * checks nothing over the network and draws no badges.
 *
 * <p>Boot-side and reflective on purpose: it names no game type, so it is testable off-game against any interface of
 * the same shape, and the same code serves the stand-in and a real Mod Menu API shipped by some other jar. So the
 * default is not named either: it is whatever the interface's own default method returns — a {@code NullScreenFactory}
 * in Mod Menu's API and in the stand-in — and a factory of that class is the default, exactly Mod Menu's
 * {@code instanceof} test. Only if the default cannot be asked does it fall back to whether the class overrides.
 */
public final class ModMenuConfigFactories {
	/** The entrypoint key Mod Menu reads. */
	public static final String KEY = "modmenu";

	private final Map<String, Object> factories;
	private final Method create;
	private final Method provided;
	private final List<Object> implementations;
	private final List<String> implementers;
	private final List<String> definitions;
	private final Set<String> failed;
	private final int entrypoints;

	private ModMenuConfigFactories(Map<String, Object> factories, Method create, Method provided, List<Object> implementations,
			List<String> implementers, List<String> definitions, Set<String> failed, int entrypoints) {
		this.factories = factories;
		this.create = create;
		this.provided = provided;
		this.implementations = implementations;
		this.implementers = implementers;
		this.definitions = definitions;
		this.failed = failed;
		this.entrypoints = entrypoints;
	}

	/**
	 * Reads every {@code "modmenu"} entrypoint of type {@code api}.
	 *
	 * @param api the {@code ModMenuApi} interface as the game class loader defined it — the stand-in, or a real copy
	 * @throws NoSuchMethodException when {@code api} is not shaped like Mod Menu's API at all
	 */
	public static ModMenuConfigFactories read(FabricLoader fabric, Class<?> api) throws NoSuchMethodException {
		Method own = api.getMethod("getModConfigScreenFactory");
		Method provided = api.getMethod("getProvidedConfigScreenFactories");
		Method create = factoryMethod(own.getReturnType());
		Class<?> defaultFactory = defaultFactoryClass(api, own);

		Map<String, Object> factories = new LinkedHashMap<>();
		List<Object> implementations = new ArrayList<>();
		List<String> implementers = new ArrayList<>();
		List<String> definitions = new ArrayList<>();
		Set<String> failed = new LinkedHashSet<>();
		List<? extends EntrypointContainer<?>> containers = fabric.getEntrypointContainers(KEY, api);
		for (EntrypointContainer<?> container : containers) {
			String modId = modId(container);
			String definition = container.getDefinition();
			try {
				Object implementation = container.getEntrypoint();
				boolean says = defaultFactory != null || overrides(implementation.getClass(), own);
				Object factory = says ? own.invoke(implementation) : null;
				// put, not putIfAbsent: a mod declaring two entrypoints ends with its last one's, as under Mod Menu.
				if (says && (defaultFactory == null || !defaultFactory.isInstance(factory))) factories.put(modId, factory);
				implementations.add(implementation);
				implementers.add(modId);
				definitions.add(definition);
			} catch (Throwable t) {
				if (failed.add(modId + " " + definition)) broken(modId, definition, "building it or asking it for its factory", t);
			}
		}
		// A null stored with put means "no screen" under Mod Menu too: it answers with a null check. Dropping it here
		// also lets a library's provided factory fill the id, which Mod Menu's putIfAbsent over a null value does.
		factories.values().removeIf(java.util.Objects::isNull);
		ModMenuConfigFactories read = new ModMenuConfigFactories(factories, create, provided, implementations,
				implementers, definitions, failed, containers.size());
		read.mergeProvided();
		return read;
	}

	/** Whether {@code modId} has a config screen factory. Builds nothing. */
	public synchronized boolean has(String modId) {
		if (modId == null) return false;
		mergeProvided();
		return factories.containsKey(modId);
	}

	/**
	 * Builds {@code modId}'s config screen over {@code parent}, or returns {@code null} when it has no factory or its
	 * factory produced no screen. A factory that throws throws its own exception, not a reflective wrapper.
	 */
	public Object create(String modId, Object parent) throws Exception {
		Object factory;
		synchronized (this) {
			mergeProvided();
			factory = modId == null ? null : factories.get(modId);
		}
		if (factory == null) return null;
		try {
			return create.invoke(factory, parent);
		} catch (InvocationTargetException thrown) {
			if (thrown.getCause() instanceof Exception e) throw e;
			if (thrown.getCause() instanceof Error e) throw e;
			throw thrown;
		}
	}

	/**
	 * Every entrypoint's provided factories, merged under the mods' own with {@code putIfAbsent} — on each lookup, as
	 * Mod Menu's {@code getConfigScreenFactory} does, so the answer follows a library that registers late.
	 */
	private synchronized void mergeProvided() {
		for (int i = 0; i < implementations.size(); i++) {
			String key = implementers.get(i) + " " + definitions.get(i);
			if (failed.contains(key)) continue;
			try {
				if (!(provided.invoke(implementations.get(i)) instanceof Map<?, ?> offered)) continue;
				for (Map.Entry<?, ?> entry : offered.entrySet()) {
					if (entry.getKey() instanceof String id && entry.getValue() != null) factories.putIfAbsent(id, entry.getValue());
				}
			} catch (Throwable t) {
				if (failed.add(key)) broken(implementers.get(i), definitions.get(i), "asking it for the factories it provides", t);
			}
		}
	}

	/** Mod ids with a factory, own ones first in entrypoint order, then provided ones. */
	public synchronized Set<String> modIds() {
		mergeProvided();
		return Collections.unmodifiableSet(new LinkedHashSet<>(factories.keySet()));
	}

	/** {@code "modmenu"} entrypoints of the API type that were read. */
	public int entrypoints() {
		return entrypoints;
	}

	/** Entrypoints skipped because they threw. */
	public synchronized int broken() {
		return failed.size();
	}

	/**
	 * The class of what {@code api}'s own default {@code getModConfigScreenFactory()} returns — Mod Menu's
	 * {@code NullScreenFactory} — asked of a bare proxy that overrides nothing; {@code null} if it cannot be asked.
	 */
	private static Class<?> defaultFactoryClass(Class<?> api, Method own) {
		try {
			Object bare = java.lang.reflect.Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api},
					(proxy, method, args) -> method.isDefault() ? java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args) : null);
			Object factory = own.invoke(bare);
			return factory == null ? null : factory.getClass();
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	/**
	 * Whether {@code type} declares its own {@code method} somewhere below the interface. The fallback when the
	 * interface's default cannot be asked: otherwise the factory itself says whether it is the default.
	 *
	 * <p>Asked of the public member set, so an override in an abstract base class a config library provides counts,
	 * and a re-abstraction cannot be mistaken for one: a constructed class has a concrete method.
	 */
	static boolean overrides(Class<?> type, Method method) {
		try {
			Method found = type.getMethod(method.getName(), method.getParameterTypes());
			return found.getDeclaringClass() != method.getDeclaringClass() && !Modifier.isAbstract(found.getModifiers());
		} catch (NoSuchMethodException | SecurityException e) {
			return false;
		}
	}

	/** {@code ConfigScreenFactory}'s single abstract method — {@code create(Screen)} — found without naming Screen. */
	private static Method factoryMethod(Class<?> factory) throws NoSuchMethodException {
		for (Method method : factory.getMethods()) {
			if (method.getName().equals("create") && method.getParameterCount() == 1
					&& Modifier.isAbstract(method.getModifiers())) {
				return method;
			}
		}
		throw new NoSuchMethodException(factory.getName() + ".create(Screen)");
	}

	private static String modId(EntrypointContainer<?> container) {
		try {
			return container.getProvider().getMetadata().getId();
		} catch (RuntimeException e) {
			return "<unknown>";
		}
	}

	private static void broken(String modId, String definition, String doing, Throwable t) {
		Throwable cause = t instanceof InvocationTargetException wrapped && wrapped.getCause() != null ? wrapped.getCause() : t;
		ForbricLog.warn("[Forbric/ModConfig] %s's Mod Menu entrypoint %s threw while %s (%s) — it is skipped, as Mod "
				+ "Menu skips a broken one; no other mod's config is affected", modId, definition, doing, String.valueOf(cause));
	}
}
