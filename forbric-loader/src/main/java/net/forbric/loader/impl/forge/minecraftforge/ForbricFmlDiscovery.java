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

package net.forbric.loader.impl.forge.minecraftforge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Runs FML's <b>genuine</b> mod discovery under Knot, so that Forge's own {@code ServerModLoader.load()}/
 * {@code ClientModLoader} (which the patched game calls itself) find a populated {@code LoadingModList} and can
 * drive the full, unmodified lifecycle — construct, config load, registry events, setup events, IMC, complete.
 *
 * <p>The genuine population path needs no ModLauncher: build real {@code ModFile}s (over a Forbric
 * {@code SecureJar} stand-in — the real cpw implementation needs the union-filesystem SPI on the system
 * classloader, unavailable under Knot), let FML's own {@code ModFileParser.modsTomlParser} read each
 * {@code mods.toml}, let FML's own {@code Scanner} produce the annotation scan data (which is also what makes
 * {@code AutomaticEventSubscriber} handle {@code @Mod.EventBusSubscriber} for free later), then call the plain
 * static {@code ModSorter.sort(files, errors)} — that alone builds and installs the {@code LoadingModList}.
 *
 * <p>{@code ModSorter.detectSystemMods} demands modids {@code minecraft} AND {@code forge} be present:
 * {@code minecraft} is a ModFile over the patched game jar whose metadata comes from FML's own (private)
 * {@code MinecraftLocator.buildMinecraftTOML}; {@code forge} is the merged forge-runtime jar, whose
 * {@code META-INF/mods.toml} the assemble script now preserves.
 *
 * <p>Reflection/{@link Proxy}-only, like the rest of the driver (no compile-time Forge dependency).
 */
final class ForbricFmlDiscovery {
	private final ClassLoader cl;

	// resolved Forge types (reflected once)
	private Class<?> secureJarCls;
	private Class<?> moduleDataProviderCls;
	private Class<?> statusCls;
	private Class<?> modProviderCls;
	private Class<?> modFileCls;
	private Class<?> parserItfCls;
	private Method modsTomlParser;      // static ModFileParser.modsTomlParser(IModFile)
	private Method buildMinecraftToml;  // private static MinecraftLocator.buildMinecraftTOML(IModFile)

	ForbricFmlDiscovery(ClassLoader cl) {
		this.cl = cl;
	}

	/**
	 * Build + scan + sort. Returns the {@code LoadingModList} (as Object) or null on total failure.
	 *
	 * <p>The LoadingModList must NEVER be left uninstalled on a real-lifecycle boot: Forge code baked into the
	 * patched base touches it far from here and unconditionally ({@code ServerStatusPing} → {@code ModList} on
	 * world load), and a first {@code LoadingModList.get()} with no sort done permanently poisons the
	 * {@code LoadingModListImpl$1LazyInit} holder class ({@code temp} null → NPE → every later touch is
	 * {@code NoClassDefFoundError}). So a failed full discovery falls back to sorting just the SYSTEM set
	 * ({@code minecraft} + the forge runtime jar) — a 0-content-mod Forge lifecycle runs fine on that.
	 */
	Object run(List<Path> forgeModJars, Path forgeRuntimeJar) {
		try {
			resolveTypes();
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/FML] genuine discovery FAILED resolving FML types "
					+ "(real-lifecycle boots will not see mods)", unwrap(t));
			return null;
		}

		try {
			return discoverSortInstall(forgeModJars);
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/FML] genuine discovery FAILED — retrying with the minimal system set "
					+ "(minecraft + forge runtime) so the LoadingModList is never left uninstalled", unwrap(t));
			if (forgeRuntimeJar == null) {
				ForbricLog.error("[Forbric/FML] no forge runtime jar known — cannot install even a minimal LoadingModList");
				return null;
			}
			try {
				return discoverSortInstall(List.of(forgeRuntimeJar));
			} catch (Throwable t2) {
				ForbricLog.error("[Forbric/FML] minimal fallback discovery ALSO failed "
						+ "(real-lifecycle boots will not see mods)", unwrap(t2));
				return null;
			}
		}
	}

	private Object discoverSortInstall(List<Path> forgeModJars) throws Exception {
		List<Object> modFiles = new ArrayList<>();

		// 1) "minecraft" system mod over the patched game jar (metadata via FML's own builder; the real
		//    MinecraftLocator also skips content scanning for the huge game jar - so do we).
		Path gameJar = locateGameJar();
		modFiles.add(newModFile(gameJar, "minecraft", parserProxy(buildMinecraftToml), true));

		// 2) "forge" + every wrapped Forge mod: their own META-INF/mods.toml via FML's own parser.
		//    Per-jar: one unreadable jar must not abort the whole ecosystem's discovery.
		for (Path jar : forgeModJars) {
			try {
				modFiles.add(newModFile(jar, null, parserProxy(modsTomlParser), false));
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/FML] discovery: cannot build ModFile, dropping " + jar.getFileName(), unwrap(t));
			}
		}

		// 3) Genuine identify + language resolution — per-file, like the NeoForge twin: FML throws on a jar it
		//    cannot type (e.g. a mod built for another MC version wanting javafml [61,62) on a 65.x runtime);
		//    drop that jar, keep the rest.
		List<Object> valid = new ArrayList<>();
		for (Object file : modFiles) {
			try {
				boolean ok = (Boolean) modFileCls.getMethod("identifyMods").invoke(file);
				if (!ok) {
					ForbricLog.warn("[Forbric/FML] discovery: ModFile did not identify as a mod, dropping: " + file);
					continue;
				}
				modFileCls.getMethod("identifyLanguage").invoke(file);
				valid.add(file);
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/FML] discovery: identify failed, dropping " + file, unwrap(t));
			}
		}

		// 4) Genuine background scan (populates ModFileScanData - @Mod targets, @EventBusSubscriber, ...).
		Class<?> scanHandlerCls = Class.forName("net.minecraftforge.fml.loading.moddiscovery.BackgroundScanHandler", false, cl);
		Object scanHandler = scanHandlerCls.getConstructor(List.class).newInstance((Object) retype(valid));
		Class<?> fmlLoader = Class.forName("net.minecraftforge.fml.loading.FMLLoader", false, cl);
		fmlLoader.getField("backgroundScanHandler").set(null, scanHandler);
		Method submit = scanHandlerCls.getMethod("submitForScanning", modFileCls);
		for (Object file : valid) {
			submit.invoke(scanHandler, file);
		}

		// 5) The keystone: plain static sort installs the LoadingModList.
		Class<?> sorterCls = Class.forName("net.minecraftforge.fml.loading.ModSorter", false, cl);
		Object loadingModList = sorterCls.getMethod("sort", List.class, List.class)
				.invoke(null, retype(valid), List.of());

		report(loadingModList);
		return loadingModList;
	}

	private static Throwable unwrap(Throwable t) {
		return t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
	}

	private void resolveTypes() throws Exception {
		secureJarCls = Class.forName("cpw.mods.jarhandling.SecureJar", false, cl);
		moduleDataProviderCls = Class.forName("cpw.mods.jarhandling.SecureJar$ModuleDataProvider", false, cl);
		statusCls = Class.forName("cpw.mods.jarhandling.SecureJar$Status", false, cl);
		modProviderCls = Class.forName("net.minecraftforge.forgespi.locating.IModProvider", false, cl);
		modFileCls = Class.forName("net.minecraftforge.fml.loading.moddiscovery.ModFile", false, cl);
		parserItfCls = Class.forName("net.minecraftforge.forgespi.locating.ModFileFactory$ModFileInfoParser", false, cl);
		Class<?> iModFileCls = Class.forName("net.minecraftforge.forgespi.locating.IModFile", false, cl);

		modsTomlParser = Class.forName("net.minecraftforge.fml.loading.moddiscovery.ModFileParser", false, cl)
				.getMethod("modsTomlParser", iModFileCls);
		buildMinecraftToml = Class.forName("net.minecraftforge.fml.loading.moddiscovery.MinecraftLocator", false, cl)
				.getDeclaredMethod("buildMinecraftTOML", iModFileCls);
		buildMinecraftToml.setAccessible(true);
	}

	/** The patched game jar = the code source of a known vanilla class on the Knot classpath. */
	private Path locateGameJar() throws Exception {
		URL url = cl.getResource("net/minecraft/server/Bootstrap.class");
		if (url == null) throw new IllegalStateException("game class net.minecraft.server.Bootstrap not on the Knot classpath");
		String s = url.toString();
		if (!s.startsWith("jar:file:") || !s.contains("!")) throw new IllegalStateException("unexpected game code source: " + s);
		return Path.of(java.net.URI.create(s.substring(4, s.indexOf('!'))));
	}

	// --- ModFile construction over Forbric SecureJar stand-ins ---------------------------------------

	private Object newModFile(Path jar, String forcedName, Object parser, boolean skipContentScan) throws Exception {
		FileSystem fs = FileSystems.newFileSystem(jar); // intentionally never closed: live for the whole run
		String moduleName = forcedName != null ? forcedName : moduleNameOf(jar);
		ModuleDescriptor descriptor = forcedName != null ? null : descriptorOf(jar);
		Manifest manifest = readManifest(fs);

		Object secureJar = secureJarProxy(jar, fs, manifest, moduleName, descriptor);
		Object provider = providerProxy(fs, skipContentScan);
		return modFileCls.getConstructor(secureJarCls, modProviderCls, parserItfCls)
				.newInstance(secureJar, provider, parser);
	}

	private String moduleNameOf(Path jar) {
		return ModuleFinder.of(jar).findAll().iterator().next().descriptor().name();
	}

	private ModuleDescriptor descriptorOf(Path jar) {
		return ModuleFinder.of(jar).findAll().iterator().next().descriptor();
	}

	private static Manifest readManifest(FileSystem fs) throws IOException {
		Path mf = fs.getPath("META-INF", "MANIFEST.MF");
		if (!Files.exists(mf)) return new Manifest();
		try (InputStream in = Files.newInputStream(mf)) {
			return new Manifest(in);
		}
	}

	private Object secureJarProxy(Path jar, FileSystem fs, Manifest manifest, String name, ModuleDescriptor descriptor) {
		Object statusNone = statusNone();
		Object mdp = Proxy.newProxyInstance(cl, new Class<?>[]{moduleDataProviderCls}, (proxy, method, args) -> {
			switch (method.getName()) {
			case "name": return name;
			case "descriptor": return descriptor;
			case "uri": return jar.toUri();
			case "findFile": {
				Path p = fs.getPath((String) args[0]);
				return Files.exists(p) ? Optional.of(p.toUri()) : Optional.empty();
			}
			case "open": {
				Path p = fs.getPath((String) args[0]);
				return Files.exists(p) ? Optional.of(Files.newInputStream(p)) : Optional.empty();
			}
			case "getManifest": return manifest;
			case "verifyAndGetSigners": return null;
			default: return objectMethod(proxy, method, args, "ForbricModuleData[" + name + "]");
			}
		});

		return Proxy.newProxyInstance(cl, new Class<?>[]{secureJarCls}, (proxy, method, args) -> {
			switch (method.getName()) {
			case "moduleDataProvider": return mdp;
			case "getPrimaryPath": return jar;
			case "getRootPath": return fs.getPath("/");
			case "getPath": {
				String first = (String) args[0];
				String[] more = args.length > 1 && args[1] != null ? (String[]) args[1] : new String[0];
				return fs.getPath(first, more);
			}
			case "name": return name;
			case "getPackages": return packagesOf(fs);
			case "getProviders": return List.of();
			case "getManifestSigners": return null;
			case "verifyPath": return statusNone;
			case "getFileStatus": return statusNone;
			case "getTrustedManifestEntries": return null;
			case "hasSecurityData": return false;
			default: return objectMethod(proxy, method, args, "ForbricSecureJar[" + jar.getFileName() + "]");
			}
		});
	}

	private Object providerProxy(FileSystem fs, boolean skipContentScan) {
		return Proxy.newProxyInstance(cl, new Class<?>[]{modProviderCls}, (proxy, method, args) -> {
			switch (method.getName()) {
			case "name": return "forbric";
			case "scanFile": {
				if (!skipContentScan) {
					@SuppressWarnings("unchecked")
					java.util.function.Consumer<Path> consumer = (java.util.function.Consumer<Path>) args[1];
					try (Stream<Path> walk = Files.walk(fs.getPath("/"))) {
						walk.filter(p -> p.toString().endsWith(".class")).forEach(consumer);
					}
				}
				return null;
			}
			case "initArguments": return null;
			case "isValid": return true;
			default: return objectMethod(proxy, method, args, "ForbricModProvider");
			}
		});
	}

	private Object parserProxy(Method staticBuilder) {
		return Proxy.newProxyInstance(cl, new Class<?>[]{parserItfCls}, (proxy, method, args) -> {
			if ("build".equals(method.getName())) return staticBuilder.invoke(null, args[0]);
			return objectMethod(proxy, method, args, "ForbricModFileInfoParser");
		});
	}

	private Object statusNone() {
		@SuppressWarnings({"unchecked", "rawtypes"})
		Object none = Enum.valueOf((Class<Enum>) (Class) statusCls.asSubclass(Enum.class), "NONE");
		return none;
	}

	private static Set<String> packagesOf(FileSystem fs) throws IOException {
		try (Stream<Path> walk = Files.walk(fs.getPath("/"))) {
			return walk.filter(p -> p.toString().endsWith(".class") && p.getParent() != null)
					.map(p -> p.getParent().toString().replace('/', '.'))
					.map(s -> s.startsWith(".") ? s.substring(1) : s)
					.filter(s -> !s.isEmpty() && !s.startsWith("META-INF"))
					.collect(Collectors.toCollection(LinkedHashSet::new));
		}
	}

	private static Object objectMethod(Object proxy, Method method, Object[] args, String toString) {
		switch (method.getName()) {
		case "toString": return toString;
		case "hashCode": return System.identityHashCode(proxy);
		case "equals": return proxy == args[0];
		default: throw new UnsupportedOperationException("Forbric proxy does not implement " + method);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Object> retype(List<Object> l) {
		return l; // erased: List<ModFile> at the reflective call sites
	}

	// --- report ---------------------------------------------------------------------------------------

	private void report(Object loadingModList) {
		try {
			Class<?> lml = Class.forName("net.minecraftforge.fml.loading.LoadingModList", false, cl);
			List<?> mods = (List<?>) lml.getMethod("getMods").invoke(null);
			List<String> ids = new ArrayList<>();
			for (Object modInfo : mods) {
				Object id = modInfo.getClass().getMethod("getModId").invoke(modInfo);
				Object ver = modInfo.getClass().getMethod("getVersion").invoke(modInfo);
				ids.add(id + "@" + ver);
			}
			List<?> errors = (List<?>) lml.getMethod("getErrors").invoke(null);
			ForbricLog.info("[Forbric/FML] genuine LoadingModList installed: " + ids.size() + " mod(s) " + ids
					+ (errors.isEmpty() ? ", 0 errors" : ", ERRORS: " + errors));
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/FML] LoadingModList report failed", t);
		}
	}
}
