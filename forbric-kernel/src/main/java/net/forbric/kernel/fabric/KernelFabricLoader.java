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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.entrypoint.EntrypointStorage;

import net.forbric.kernel.classloading.FabricLoaderInternals;
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

	/**
	 * The launch arguments a sanitised read must not contain, with the value that follows each.
	 *
	 * <p>The same four Fabric removes. {@code --accessToken} is the one that matters: it is a live session
	 * credential, and a mod asks for the SANITISED arguments precisely when it is about to write them somewhere
	 * that leaves the machine.
	 */
	private static final java.util.Set<String> SENSITIVE_ARGUMENTS =
			java.util.Set.of("--accessToken", "--username", "--uuid", "--xuid");

	/**
	 * {@code -Dforbric.entrypointResolveFailure=warn}: an entrypoint whose class cannot be loaded is skipped with a
	 * WARN, as before, instead of failing its mod (lifecycle keys) or recording a finding (other keys).
	 */
	public static final String RESOLVE_FAILURE_PROPERTY = "forbric.entrypointResolveFailure";

	/** The keys the kernel itself drives through the lifecycle; a mod whose entry among them cannot load did not start. */
	private static final java.util.Set<String> LIFECYCLE_KEYS = java.util.Set.of("main", "client", "server", "preLaunch");

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

	/**
	 * The map behind Fabric Loader's {@code EntrypointStorage.entryMap} (see {@link #fabricEntryMap()}). One per
	 * process, like the {@code FabricLoaderImpl.INSTANCE} that exposes it; filled from whichever loader froze last.
	 */
	private static final Map<String, List<EntrypointStorage.Entry>> FABRIC_ENTRY_MAP = new LinkedHashMap<>();
	/** Whether a mod has reached for the storage at all; until then the kernel never builds it. */
	private static boolean fabricStorageLive;
	/** The loader {@link #FABRIC_ENTRY_MAP} was last filled from; only that loader may read it back. */
	private static KernelFabricLoader fabricStorageSeededBy;

	private KernelFabricLoader(EnvType envType, Path gameDir, Path configDir, String[] launchArguments,
			String rawGameVersion) {
		this.envType = envType;
		// Absolute, and the config directory made. A mod reads getGameDir()/getConfigDir() to decide where to put
		// its own files, and both answers used to be whatever the launcher happened to pass: a RELATIVE path
		// resolves against the process's working directory, which is not the game directory for every launcher,
		// so a mod wrote its files somewhere else entirely. And a mod writing straight into the config directory
		// got a NoSuchFileException on a first run, because nothing had created it.
		this.gameDir = absolute(gameDir);
		this.configDir = created(absolute(configDir));
		this.launchArguments = launchArguments == null ? new String[0] : launchArguments.clone();
		this.rawGameVersion = rawGameVersion;
	}

	/** A path a mod can resolve against without knowing the process's working directory. Null stays null. */
	static Path absolute(Path path) {
		try {
			return path == null ? null : path.toAbsolutePath().normalize();
		} catch (Throwable unresolvable) {
			return path;
		}
	}

	/**
	 * The config directory, created if it is not there.
	 *
	 * <p>Fabric creates it before any mod can ask, and a mod that writes its config on first run has no reason to
	 * create it itself. Failure to create is not fatal here: the mod's own write will fail and say so with its
	 * own name on it, which is better than failing the boot for every other mod.
	 */
	static Path created(Path dir) {
		try {
			if (dir != null) java.nio.file.Files.createDirectories(dir);
		} catch (Throwable notCreated) {
			ForbricLog.debug("[Forbric/Fabric] could not create the config directory %s: %s", dir,
					String.valueOf(notCreated));
		}
		return dir;
	}

	/** The live instance, or {@code null} if the kernel has not created it yet ({@code FabricLoader.getInstance()}). */
	public static KernelFabricLoader getInstanceOrNull() {
		return instance;
	}

	/** Creates the process-wide instance. Called once by the boot orchestrator, before any mod class loads. */
	public static synchronized KernelFabricLoader create(EnvType envType, Path gameDir, Path configDir,
			String[] launchArguments, String rawGameVersion) {
		if (instance != null) throw new IllegalStateException("KernelFabricLoader already created");

		KernelLanguageAdapters.reset();
		instance = new KernelFabricLoader(envType, gameDir, configDir, launchArguments, rawGameVersion);
		return instance;
	}

	/** The transforming loader that defines mod + game classes; entrypoints resolve through it. */
	public void setGameLoader(ClassLoader loader) {
		this.gameLoader = loader;
		KernelLanguageAdapters.bindGameLoader(loader);
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

		// Registered at discovery, not at first use: an adapter is declared by ONE mod and named by others, so it
		// has to be known before any entrypoint is constructed regardless of discovery order.
		KernelLanguageAdapters.declare(metadata.getId(), metadata.getLanguageAdapters());

		for (Map.Entry<String, List<EntrypointDecl>> entry : metadata.getEntrypoints().entrySet()) {
			List<Entrypoint> sink = entrypointsByKey.computeIfAbsent(entry.getKey(), k -> new ArrayList<>());

			for (EntrypointDecl decl : entry.getValue()) {
				sink.add(new Entrypoint(entry.getKey(), container, decl));
			}
		}
	}

	/**
	 * Seals the mod set. Everything after this point is read-only, so entrypoint lookup needs no lock — with one
	 * exception, {@link #adoptFabricStorage}, which runs on the boot thread between the entrypoint phases.
	 */
	public synchronized void freeze() {
		frozen = true;
		ForbricLog.info("[Forbric/Fabric] FabricLoader ready — %d mod(s), entrypoint keys %s",
				mods.size(), entrypointsByKey.keySet());
		synchronized (FABRIC_ENTRY_MAP) {
			if (fabricStorageLive) seedFabricStorage();
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Fabric Loader's internal entrypoint storage
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * The live map {@code EntrypointStorage.entryMap} is, for the mods that edit Fabric Loader's entrypoint index by
	 * reflection instead of declaring entrypoints (see {@code FabricLoaderImpl}).
	 *
	 * <p>It holds one entry per entrypoint this loader holds, per key, in this loader's order, in mutable lists. The
	 * kernel keeps its own index — this map is a copy handed out for editing, and {@link #adoptFabricStorage} is the
	 * only way an edit reaches the kernel. Built on first request, and never before: a pack with no mod that reaches
	 * for it never has one.
	 */
	public static Map<String, List<EntrypointStorage.Entry>> fabricEntryMap() {
		synchronized (FABRIC_ENTRY_MAP) {
			fabricStorageLive = true;
			KernelFabricLoader current = instance;
			if (current != null && current.frozen && fabricStorageSeededBy != current) current.seedFabricStorage();
		}
		return FABRIC_ENTRY_MAP;
	}

	/** Refills {@link #FABRIC_ENTRY_MAP} from this loader. Caller holds its lock. */
	private void seedFabricStorage() {
		FABRIC_ENTRY_MAP.clear();
		for (Map.Entry<String, List<Entrypoint>> key : entrypointsByKey.entrySet()) {
			List<EntrypointStorage.Entry> entries = new ArrayList<>(key.getValue().size());
			for (Entrypoint entry : key.getValue()) entries.add(entry.storageEntry());
			FABRIC_ENTRY_MAP.put(key.getKey(), entries);
		}
		fabricStorageSeededBy = this;
	}

	/**
	 * Takes back what mods did to Fabric Loader's entrypoint storage: every key's entrypoints become exactly what the
	 * storage lists, IN THE STORAGE'S ORDER. An entry a mod added becomes an entrypoint of the mod it names, built by
	 * the adapter it was given; one it removed is gone; nothing is re-sorted.
	 *
	 * <p>Not re-sorting is the point. Core Lib appends its {@code RegistryEntryPoints} to the END of {@code main},
	 * because that entrypoint flushes the registrations every SuperMartijn642 mod queued in its own
	 * {@code onInitialize}. Sorting it back into dependency order would put it BEFORE those mods (they depend on Core
	 * Lib), and each would then throw "Cannot register new entries after mod initialization!" from its own
	 * {@code onInitialize}.
	 *
	 * <p>A no-op until a mod has reached for the storage, and under {@code -Dforbric.fabricImpl=off}. Called after
	 * {@code preLaunch} and again before the {@code main}, {@code server} and {@code client} phases. An entry added
	 * under a key whose phase has already run is reported: it never runs, as on Fabric.
	 *
	 * @param alreadyRan the entrypoint keys whose phase has run
	 * @return the entries this call adopted, as {@code key:modId->definition}
	 */
	public synchronized List<String> adoptFabricStorage(Set<String> alreadyRan) {
		if (!FabricLoaderInternals.enabled()) return List.of();

		Map<String, List<EntrypointStorage.Entry>> storage = new LinkedHashMap<>();
		synchronized (FABRIC_ENTRY_MAP) {
			if (fabricStorageSeededBy != this) return List.of();
			for (Map.Entry<String, List<EntrypointStorage.Entry>> key : FABRIC_ENTRY_MAP.entrySet()) {
				if (key.getKey() != null && key.getValue() != null) {
					storage.put(key.getKey(), new ArrayList<>(key.getValue()));
				}
			}
		}

		Map<EntrypointStorage.Entry, Entrypoint> known = new IdentityHashMap<>();
		for (List<Entrypoint> entries : entrypointsByKey.values()) {
			for (Entrypoint entry : entries) known.put(entry.storageEntry(), entry);
		}

		Map<String, List<Entrypoint>> rebuilt = new LinkedHashMap<>();
		List<String> adopted = new ArrayList<>();
		Map<String, Map<String, Integer>> addedByMod = new LinkedHashMap<>();
		Map<String, Set<String>> definitionsByMod = new LinkedHashMap<>();

		for (Map.Entry<String, List<EntrypointStorage.Entry>> key : storage.entrySet()) {
			List<Entrypoint> entries = new ArrayList<>(key.getValue().size());
			for (EntrypointStorage.Entry stored : key.getValue()) {
				if (stored == null) continue;
				Entrypoint entry = known.get(stored);
				if (entry == null) {
					entry = adopt(key.getKey(), stored);
					if (entry == null) continue;
					known.put(stored, entry);
					String id = entry.provider().getMetadata().getId();
					adopted.add(key.getKey() + ":" + id + "->" + entry.definition());
					addedByMod.computeIfAbsent(id, k -> new LinkedHashMap<>()).merge(key.getKey(), 1, Integer::sum);
					definitionsByMod.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(entry.definition());
					if (alreadyRan.contains(key.getKey())) {
						ForbricLog.warn("[Forbric/Fabric] %s added a '%s' entrypoint (%s) through Fabric Loader's "
								+ "internal EntrypointStorage after that phase ran — it will not run, as on Fabric",
								id, key.getKey(), entry.definition());
					}
				}
				entries.add(entry);
			}
			rebuilt.put(key.getKey(), entries);
		}

		if (sameEntrypoints(rebuilt)) return adopted;

		int removed = 0;
		for (Map.Entry<String, List<Entrypoint>> key : entrypointsByKey.entrySet()) {
			List<Entrypoint> now = rebuilt.getOrDefault(key.getKey(), List.of());
			for (Entrypoint entry : key.getValue()) {
				if (!containsIdentity(now, entry)) removed++;
			}
		}
		// In place rather than clear-and-refill, so a concurrent reader never sees the index empty: a key the storage
		// kept is re-pointed (not a structural change), a dropped key goes, a new one is appended.
		entrypointsByKey.keySet().retainAll(rebuilt.keySet());
		entrypointsByKey.putAll(rebuilt);

		addedByMod.forEach((id, keys) -> {
			StringBuilder counts = new StringBuilder();
			keys.forEach((k, n) -> counts.append(counts.length() == 0 ? "" : " + ").append(n).append(' ').append(k));
			ForbricLog.info("[Forbric/Fabric] %s added %s entrypoint(s) through Fabric Loader's internal "
					+ "EntrypointStorage (%s) — they run where it put them, after every declared one, as on Fabric "
					+ "(-D%s=off to go back)", id, counts, String.join(", ", definitionsByMod.get(id)),
					FabricLoaderInternals.SWITCH);
		});
		if (removed > 0) {
			ForbricLog.info("[Forbric/Fabric] %d entrypoint(s) were removed through Fabric Loader's internal "
					+ "EntrypointStorage — honoured, as on Fabric", removed);
		}
		if (adopted.isEmpty() && removed == 0) {
			ForbricLog.info("[Forbric/Fabric] entrypoints were reordered through Fabric Loader's internal "
					+ "EntrypointStorage — the new order is kept, as on Fabric");
		}
		return adopted;
	}

	/** The kernel entrypoint for an entry a mod put in the storage itself, or null if it names no mod known here. */
	private Entrypoint adopt(String key, EntrypointStorage.Entry stored) {
		ModContainerImpl mod;
		try {
			mod = stored.getModContainer();
		} catch (Throwable unreadable) {
			mod = null;
		}
		if (!(mod instanceof KernelModContainer provider)) {
			ForbricLog.warn("[Forbric/Fabric] an entry added to '%s' through Fabric Loader's internal EntrypointStorage "
					+ "(%s) names no mod this loader knows — skipped", key, String.valueOf(stored));
			return null;
		}
		return new Entrypoint(key, provider, stored);
	}

	private boolean sameEntrypoints(Map<String, List<Entrypoint>> rebuilt) {
		if (!rebuilt.keySet().equals(entrypointsByKey.keySet())) return false;
		for (Map.Entry<String, List<Entrypoint>> key : rebuilt.entrySet()) {
			List<Entrypoint> current = entrypointsByKey.get(key.getKey());
			if (current.size() != key.getValue().size()) return false;
			for (int i = 0; i < current.size(); i++) {
				if (current.get(i) != key.getValue().get(i)) return false;
			}
		}
		return true;
	}

	private static boolean containsIdentity(List<Entrypoint> entries, Entrypoint wanted) {
		for (Entrypoint entry : entries) {
			if (entry == wanted) return true;
		}
		return false;
	}

	/** Forgets the process-wide instance and the storage it filled — for tests. */
	static void resetForTests() {
		synchronized (KernelFabricLoader.class) {
			instance = null;
		}
		synchronized (FABRIC_ENTRY_MAP) {
			FABRIC_ENTRY_MAP.clear();
			fabricStorageSeededBy = null;
		}
	}

	/** Whether any mod declared an entrypoint under {@code key} (cheap pre-check for the kernel's own drivers). */
	public boolean hasEntrypoints(String key) {
		List<Entrypoint> entries = entrypointsByKey.get(key);
		return entries != null && !entries.isEmpty();
	}

	/**
	 * What each mod DECLARED under {@code key}, as {@code modId -> declared value}, without constructing anything.
	 *
	 * <p>{@link #getEntrypointContainers} cannot answer this. Its containers expose only the constructed instance
	 * and the providing mod, and the thing a cross-ecosystem consumer needs is the class NAME as written: Sodium's
	 * NeoForge config loader takes a {@code String} and does its own {@code Class.forName}, type check and
	 * construction, with its own three warning paths for each failure. Handing it an instance the kernel built
	 * would take those over and answer for a class Sodium never accepted.
	 *
	 * <p>Ordered by declaration, one entry per declaring mod; a mod declaring several under one key keeps only its
	 * first, which is what every consumer of a "which class handles this" key expects.
	 */
	public Map<String, String> declaredEntrypoints(String key) {
		List<Entrypoint> entries = entrypointsByKey.get(key);
		if (entries == null || entries.isEmpty()) return Map.of();

		Map<String, String> declared = new LinkedHashMap<>();
		for (Entrypoint entry : entries) {
			String modId = entry.provider() == null ? null : entry.provider().getMetadata().getId();
			String value = entry.definition();
			if (modId == null || value == null || value.isBlank()) continue;
			declared.putIfAbsent(modId, value);
		}
		return declared;
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
		return sanitize ? sanitized(launchArguments) : launchArguments.clone();
	}

	/**
	 * The launch arguments with the player's credentials removed.
	 *
	 * <p>{@code sanitize} was accepted and ignored, so a mod asking for the SANITISED arguments — which is what a
	 * mod does before writing them into a crash report, a debug dump or a log it uploads — got the real ones,
	 * {@code --accessToken} included. That token is a live session credential. Fabric strips the same four, and
	 * the value that follows each flag goes with it.
	 *
	 * <p>Unknown flags are kept: the caller asked for the launch arguments, not for a whitelist, and dropping
	 * something the kernel does not recognise would quietly change what a mod sees.
	 */
	static String[] sanitized(String[] arguments) {
		List<String> out = new ArrayList<>(arguments.length);
		for (int i = 0; i < arguments.length; i++) {
			if (SENSITIVE_ARGUMENTS.contains(arguments[i])) {
				// Skip its value too. A flag at the very end has none, and must not run off the array.
				if (i + 1 < arguments.length) i++;
				continue;
			}
			out.add(arguments[i]);
		}
		return out.toArray(new String[0]);
	}

	private ClassLoader entrypointLoader() {
		ClassLoader loader = gameLoader;
		return loader != null ? loader : Thread.currentThread().getContextClassLoader();
	}

	/**
	 * One entrypoint declaration, resolved lazily and then cached (Fabric constructs on first access) — or one a mod
	 * added through Fabric Loader's entrypoint storage, which builds its own instances.
	 */
	private final class Entrypoint {
		private final String key;
		private final KernelModContainer provider;
		private final EntrypointDecl decl;
		/** Non-null for an entrypoint a mod added through the storage; it is then also the storage entry. */
		private final EntrypointStorage.Entry added;

		private volatile Object value;
		private volatile Class<?> resolvedClass;
		private volatile EntrypointStorage.Entry storageEntry;
		/** Whether this declaration's load failure has been said; {@link #provides} runs once per query of its key. */
		private volatile boolean unresolvableReported;

		Entrypoint(String key, KernelModContainer provider, EntrypointDecl decl) {
			this.key = key;
			this.provider = provider;
			this.decl = decl;
			this.added = null;
		}

		Entrypoint(String key, KernelModContainer provider, EntrypointStorage.Entry added) {
			this.key = key;
			this.provider = provider;
			this.decl = null;
			this.added = added;
			this.storageEntry = added;
		}

		/** This entrypoint as an entry of Fabric Loader's storage — the same object every time, which is its identity. */
		EntrypointStorage.Entry storageEntry() {
			EntrypointStorage.Entry entry = storageEntry;
			if (entry != null) return entry;

			synchronized (this) {
				if (storageEntry == null) storageEntry = new KernelBackedEntry(this);
				return storageEntry;
			}
		}

		/** Whether this declaration can yield an instance of {@code type}, without constructing it. */
		boolean provides(Class<?> type) {
			// An added entry carries its own adapter, which alone knows; like a named adapter, it answers on use.
			if (added != null) return true;
			// Only the adapter knows how its language spells "this value implements that interface" — Kotlin's, for
			// one, resolves an `object` through a synthetic INSTANCE field. Guessing here would silently drop the
			// entrypoint; let the adapter answer at construction time instead.
			if (!decl.isDefaultAdapter()) return true;

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
				return unresolvable(t);
			}
		}

		/**
		 * An entrypoint whose class, or a type its members name, cannot be loaded or linked here.
		 *
		 * <p>Nothing above throws for a declaration of another type — that is the {@code return false} paths — so
		 * anything that lands here is a load failure, and it used to be a WARN and a skip. fabric-networking's
		 * {@code CommonPacketsImpl::init} names {@code ServerConfigurationPacketListenerImpl} in a lambda's signature;
		 * when creativecore's required redirect made that class fail its weave, reflection on the entrypoint class
		 * threw, the entrypoint was dropped, Fabric's common packet handshake never registered, and the report had no
		 * word about fabric-networking. Fabric itself throws here, for the whole key.
		 *
		 * <p>For the keys the kernel drives ({@code main}, {@code client}, {@code server}, {@code preLaunch}) the
		 * declaration is handed on as matching: construction throws the same error inside the lifecycle driver's own
		 * catch, which already fails the mod and records its CONFIRMED initialization finding — one reporting path, and
		 * Fabric's outcome. For any other key the mod that reads it decides; the entry is skipped as before and a
		 * SUSPECTED finding names the key, because a class that links natively but not here can be a plugin
		 * (modmenu, emi, jade) for a mod that is otherwise fine. {@link #RESOLVE_FAILURE_PROPERTY}{@code =warn}
		 * restores the plain skip.
		 */
		private boolean unresolvable(Throwable t) {
			String id = provider.getMetadata().getId();
			if ("warn".equalsIgnoreCase(System.getProperty(RESOLVE_FAILURE_PROPERTY, "fail"))) {
				ForbricLog.warn("[Forbric/Fabric] %s: cannot resolve entrypoint '%s': %s", id, decl.value(), String.valueOf(t));
				return false;
			}
			boolean first = !unresolvableReported;
			unresolvableReported = true;
			if (LIFECYCLE_KEYS.contains(key)) {
				if (first) {
					ForbricLog.warn("[Forbric/Fabric] %s: %s entrypoint '%s' cannot be loaded here (%s) — it is handed to the "
							+ "%s driver, which fails the mod as Fabric would", id, key, decl.value(), String.valueOf(t), key);
				}
				return true;
			}
			if (first) {
				ForbricLog.warn("[Forbric/Fabric] %s: its '%s' entrypoint '%s' cannot be loaded here (%s) — whatever reads "
						+ "'%s' goes without it", id, key, decl.value(), String.valueOf(t), key);
				net.forbric.api.CompatibilityFindings.record(new net.forbric.api.CompatibilityFinding(
						"entrypoint:" + key + ":" + decl.value(), id, "Mod integration",
						"KernelFabricLoader entrypoint resolution", net.forbric.api.CompatibilityFinding.Confidence.SUSPECTED,
						false, "its '" + key + "' entrypoint " + decl.value() + " cannot be loaded, so the mod reading '" + key
								+ "' goes without it", List.of("key=" + key, "entrypoint=" + decl.value(), String.valueOf(t))));
			}
			return false;
		}

		Object get(Class<?> type) {
			if (added != null) {
				// The entry caches per requested type itself, as Fabric's does.
				try {
					return added.getOrCreate(type);
				} catch (Throwable t) {
					throw new RuntimeException("could not construct entrypoint '" + definition() + "' of mod "
							+ provider.getMetadata().getId(), t);
				}
			}

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
				// A mod-provided adapter (fabric-language-kotlin and friends) builds the instance itself; it may
				// hand anything it does not handle back through LanguageAdapter.getDefault(), which lands on the
				// same resolution as the branch below. See KernelLanguageAdapters.
				return KernelLanguageAdapters.get(decl.adapter()).create(provider, decl.value(), type);
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

		/**
		 * Resolves an entrypoint class WITHOUT running its static initialiser.
		 *
		 * <p>It used to initialise, and {@link #provides} calls this for every candidate — so asking for the
		 * entrypoints of ONE key ran the static initialiser of every entrypoint class of every mod, including
		 * ones that were never going to be constructed and ones belonging to the other side's phase entirely.
		 * A mod that does real work in a static initialiser therefore did it at the wrong moment, and if that
		 * work threw, the class stayed permanently erroneous: a class initialiser is a one-shot.
		 *
		 * <p>Nothing is lost by waiting. Every path in {@code construct} initialises the class as a side effect
		 * of what it does next — {@code newInstance}, a static field read, a method handle invocation — so the
		 * initialiser still runs, at the moment the entrypoint is actually used.
		 */
		private Class<?> loadClass(String name) throws ClassNotFoundException {
			Class<?> cls = resolvedClass;
			if (cls != null && cls.getName().equals(name)) return cls;

			cls = Class.forName(name, false, entrypointLoader());
			resolvedClass = cls;
			return cls;
		}

		String definition() {
			return added != null ? added.getDefinition() : decl.value();
		}

		KernelModContainer provider() {
			return provider;
		}
	}

	/** A kernel entrypoint as Fabric Loader's storage holds it: what a mod reading the storage sees for it. */
	private static final class KernelBackedEntry implements EntrypointStorage.Entry {
		private final Entrypoint entrypoint;

		KernelBackedEntry(Entrypoint entrypoint) {
			this.entrypoint = entrypoint;
		}

		@Override
		public <T> T getOrCreate(Class<T> type) {
			return type.cast(entrypoint.get(type));
		}

		@Override
		public boolean isOptional() {
			return false;
		}

		@Override
		public ModContainerImpl getModContainer() {
			return entrypoint.provider();
		}

		@Override
		public String getDefinition() {
			return entrypoint.definition();
		}

		@Override
		public String toString() {
			return entrypoint.provider().getMetadata().getId() + "->" + entrypoint.definition();
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
