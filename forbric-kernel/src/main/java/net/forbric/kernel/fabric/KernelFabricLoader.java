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

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import net.forbric.kernel.fabric.KernelModMetadata.EntrypointDecl;
import net.forbric.kernel.util.ForbricLog;

/**
 * The sovereign kernel's {@link FabricLoader} — the singleton every Fabric mod reaches through
 * {@code FabricLoader.getInstance()}.
 *
 * <p>This replaces Fabric Loader's {@code FabricLoaderImpl} outright. The kernel owns discovery, classloading
 * and the lifecycle, so this type is a view over kernel state rather than a loader in its own right: the boot
 * orchestrator {@linkplain #register registers} the discovered mods, then {@linkplain #freeze freezes} the
 * entrypoint index before any mod code runs.
 *
 * <p><b>Class identity.</b> This class and the whole {@code net.fabricmc.loader.api} surface are parent-loaded
 * (see {@code DelegationPolicy}), while mod classes are defined by the transforming {@code ForbricClassLoader}.
 * That is what makes a game-side mod's {@code implements ModInitializer} resolve to the same interface this
 * boot-side code casts to. Entrypoint classes are therefore resolved through {@link #gameLoader}, never
 * through this class's own loader.
 */
public final class KernelFabricLoader implements FabricLoader {
	private static volatile KernelFabricLoader instance;

	private final EnvType envType;
	private final Path gameDir;
	private final Path configDir;
	private final String[] launchArguments;
	private final String rawGameVersion;

	private final ObjectShare objectShare = new KernelObjectShare();
	private final MappingResolver mappingResolver = new KernelMappingResolver();

	private final List<ModContainer> mods = new ArrayList<>();
	private final Map<String, ModContainer> modsById = new LinkedHashMap<>();
	private final Map<String, List<Entrypoint>> entrypointsByKey = new LinkedHashMap<>();

	private volatile ClassLoader gameLoader;
	private volatile Object gameInstance;
	private volatile boolean frozen;

	private KernelFabricLoader(EnvType envType, Path gameDir, Path configDir, String[] launchArguments,
			String rawGameVersion) {
		this.envType = envType;
		this.gameDir = gameDir;
		this.configDir = configDir;
		this.launchArguments = launchArguments == null ? new String[0] : launchArguments.clone();
		this.rawGameVersion = rawGameVersion;
	}

	/** The live instance, or {@code null} if the kernel has not created it yet ({@code FabricLoader.getInstance()}). */
	public static KernelFabricLoader getInstanceOrNull() {
		return instance;
	}

	/** Creates the process-wide instance. Called once by the boot orchestrator, before any mod class loads. */
	public static synchronized KernelFabricLoader create(EnvType envType, Path gameDir, Path configDir,
			String[] launchArguments, String rawGameVersion) {
		if (instance != null) throw new IllegalStateException("KernelFabricLoader already created");

		instance = new KernelFabricLoader(envType, gameDir, configDir, launchArguments, rawGameVersion);
		return instance;
	}

	/** The transforming loader that defines mod + game classes; entrypoints resolve through it. */
	public void setGameLoader(ClassLoader loader) {
		this.gameLoader = loader;
	}

	/** Publishes the {@code MinecraftServer} / {@code Minecraft} object for {@link #getGameInstance()}. */
	public void setGameInstance(Object gameInstance) {
		this.gameInstance = gameInstance;
	}

	/** Adds a discovered mod. Its id and every {@code provides} alias become resolvable. */
	public synchronized void register(KernelModContainer container) {
		if (frozen) throw new IllegalStateException("mods registered after freeze");

		KernelModMetadata metadata = container.getMetadata();
		ModContainer existing = modsById.get(metadata.getId());

		if (existing != null) {
			ForbricLog.warn("[Forbric/Fabric] duplicate mod id '%s' (%s and %s) — keeping the first",
					metadata.getId(), existing, container);
			return;
		}

		mods.add(container);
		modsById.put(metadata.getId(), container);

		for (String alias : metadata.getProvides()) {
			modsById.putIfAbsent(alias, container);
		}

		for (Map.Entry<String, List<EntrypointDecl>> entry : metadata.getEntrypoints().entrySet()) {
			List<Entrypoint> sink = entrypointsByKey.computeIfAbsent(entry.getKey(), k -> new ArrayList<>());

			for (EntrypointDecl decl : entry.getValue()) {
				sink.add(new Entrypoint(container, decl));
			}
		}
	}

	/** Seals the mod set. Everything after this point is read-only, so entrypoint lookup needs no lock. */
	public synchronized void freeze() {
		frozen = true;
		ForbricLog.info("[Forbric/Fabric] FabricLoader ready — %d mod(s), entrypoint keys %s",
				mods.size(), entrypointsByKey.keySet());
	}

	/** Whether any mod declared an entrypoint under {@code key} (cheap pre-check for the kernel's own drivers). */
	public boolean hasEntrypoints(String key) {
		List<Entrypoint> entries = entrypointsByKey.get(key);
		return entries != null && !entries.isEmpty();
	}

	@Override
	public <T> List<T> getEntrypoints(String key, Class<T> type) {
		List<T> out = new ArrayList<>();

		for (EntrypointContainer<T> container : getEntrypointContainers(key, type)) {
			out.add(container.getEntrypoint());
		}

		return out;
	}

	@Override
	public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
		List<Entrypoint> entries = entrypointsByKey.get(key);
		if (entries == null) return List.of();

		List<EntrypointContainer<T>> out = new ArrayList<>(entries.size());

		for (Entrypoint entry : entries) {
			// Type-filter before construction: a mod may register several entrypoint types under one key (Fabric's
			// own `main` key carries only ModInitializer, but custom keys are routinely polymorphic).
			if (!entry.provides(type)) continue;

			out.add(new TypedContainer<>(entry, type));
		}

		return out;
	}

	@Override
	public <T> void invokeEntrypoints(String key, Class<T> type, Consumer<? super T> invoker) {
		RuntimeException failure = null;

		for (EntrypointContainer<T> container : getEntrypointContainers(key, type)) {
			try {
				invoker.accept(container.getEntrypoint());
			} catch (Throwable t) {
				// Contract: run every entrypoint, then report. One bad mod must not silently skip the rest.
				if (failure == null) {
					failure = new RuntimeException("failed to invoke entrypoint '" + key + "'", t);
				} else {
					failure.addSuppressed(t);
				}
			}
		}

		if (failure != null) throw failure;
	}

	@Override
	public ObjectShare getObjectShare() {
		return objectShare;
	}

	@Override
	public MappingResolver getMappingResolver() {
		return mappingResolver;
	}

	@Override
	public Optional<ModContainer> getModContainer(String id) {
		return Optional.ofNullable(modsById.get(id));
	}

	@Override
	public Collection<ModContainer> getAllMods() {
		return Collections.unmodifiableList(mods);
	}

	@Override
	public boolean isModLoaded(String id) {
		return modsById.containsKey(id);
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return false;
	}

	@Override
	public EnvType getEnvironmentType() {
		return envType;
	}

	@Override
	public String getRawGameVersion() {
		return rawGameVersion;
	}

	@Override
	@Deprecated
	public Object getGameInstance() {
		return gameInstance;
	}

	@Override
	public Path getGameDir() {
		return gameDir;
	}

	@Override
	@Deprecated
	public File getGameDirectory() {
		return gameDir.toFile();
	}

	@Override
	public Path getConfigDir() {
		return configDir;
	}

	@Override
	@Deprecated
	public File getConfigDirectory() {
		return configDir.toFile();
	}

	@Override
	public String[] getLaunchArguments(boolean sanitize) {
		// The kernel's server launch never carries credentials; on the client (M5) the account token args must be
		// stripped here before this returns anything to a mod.
		return launchArguments.clone();
	}

	private ClassLoader entrypointLoader() {
		ClassLoader loader = gameLoader;
		return loader != null ? loader : Thread.currentThread().getContextClassLoader();
	}

	/** One entrypoint declaration, resolved lazily and then cached (Fabric constructs on first access). */
	private final class Entrypoint {
		private final KernelModContainer provider;
		private final EntrypointDecl decl;

		private volatile Object value;
		private volatile Class<?> resolvedClass;

		Entrypoint(KernelModContainer provider, EntrypointDecl decl) {
			this.provider = provider;
			this.decl = decl;
		}

		/** Whether this declaration can yield an instance of {@code type}, without constructing it. */
		boolean provides(Class<?> type) {
			try {
				String v = decl.value();
				int sep = v.indexOf("::");

				if (sep < 0) {
					return type.isAssignableFrom(loadClass(v));
				}

				Class<?> owner = loadClass(v.substring(0, sep));
				String member = v.substring(sep + 2);

				for (Field field : owner.getDeclaredFields()) {
					if (field.getName().equals(member) && Modifier.isStatic(field.getModifiers())) {
						return type.isAssignableFrom(field.getType());
					}
				}

				// A method reference can satisfy any functional interface; the exact shape is checked on construction.
				for (Method method : owner.getDeclaredMethods()) {
					if (method.getName().equals(member)) return type.isInterface();
				}

				return false;
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/Fabric] %s: cannot resolve entrypoint '%s': %s", provider.getMetadata().getId(),
						decl.value(), String.valueOf(t));
				return false;
			}
		}

		Object get(Class<?> type) {
			Object v = value;
			if (v != null) return v;

			synchronized (this) {
				if (value != null) return value;

				try {
					value = construct(type);
				} catch (Throwable t) {
					throw new RuntimeException("could not construct entrypoint '" + decl.value() + "' of mod "
							+ provider.getMetadata().getId(), t);
				}

				return value;
			}
		}

		private Object construct(Class<?> type) throws Throwable {
			if (!decl.isDefaultAdapter()) {
				// Custom language adapters (e.g. fabric-language-kotlin) are an M2b concern: they are themselves
				// mods providing a `languageAdapters` block, which the kernel does not yet honour.
				throw new UnsupportedOperationException("language adapter '" + decl.adapter()
						+ "' is not supported yet (kernel M2a implements the default adapter only)");
			}

			String v = decl.value();
			int sep = v.indexOf("::");

			if (sep < 0) {
				Class<?> cls = loadClass(v);
				return cls.getDeclaredConstructor().newInstance();
			}

			Class<?> owner = loadClass(v.substring(0, sep));
			String member = v.substring(sep + 2);

			for (Field field : owner.getDeclaredFields()) {
				if (field.getName().equals(member) && Modifier.isStatic(field.getModifiers())) {
					field.setAccessible(true);
					return field.get(null);
				}
			}

			for (Method method : owner.getDeclaredMethods()) {
				if (!method.getName().equals(member) || !Modifier.isStatic(method.getModifiers())) continue;

				method.setAccessible(true);
				MethodHandle handle = MethodHandles.lookup().unreflect(method);
				// Binds the static method to the requested functional interface without generating a class in the
				// mod's package (LambdaMetafactory would need a lookup inside the mod's own class).
				return MethodHandleProxies.asInterfaceInstance(type, handle);
			}

			throw new NoSuchMethodException("no static member '" + member + "' on " + owner.getName());
		}

		private Class<?> loadClass(String name) throws ClassNotFoundException {
			Class<?> cls = resolvedClass;
			if (cls != null && cls.getName().equals(name)) return cls;

			cls = Class.forName(name, true, entrypointLoader());
			resolvedClass = cls;
			return cls;
		}

		String definition() {
			return decl.value();
		}

		KernelModContainer provider() {
			return provider;
		}
	}

	/** The mod-facing view of one resolved entrypoint. */
	private final class TypedContainer<T> implements EntrypointContainer<T> {
		private final Entrypoint entry;
		private final Class<T> type;

		TypedContainer(Entrypoint entry, Class<T> type) {
			this.entry = entry;
			this.type = type;
		}

		@Override
		public T getEntrypoint() {
			return type.cast(entry.get(type));
		}

		@Override
		public ModContainer getProvider() {
			return entry.provider();
		}

		@Override
		public String getDefinition() {
			return entry.definition();
		}
	}
}
