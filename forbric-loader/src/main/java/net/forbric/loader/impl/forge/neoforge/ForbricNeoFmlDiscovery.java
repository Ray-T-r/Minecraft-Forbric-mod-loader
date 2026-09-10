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

package net.forbric.loader.impl.forge.neoforge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Runs NeoForge FML's <b>genuine</b> mod discovery under Knot, so the patched game's own
 * {@code ClientModLoader.begin()} / {@code ServerModLoader.load()} (which call
 * {@code ModLoader.gatherAndInitializeMods}) find a populated {@code LoadingModList} and drive the full,
 * unmodified NeoForge lifecycle — construct, config load, registry events, setup events, complete.
 *
 * <p>Structurally the NeoForge twin of the MinecraftForge {@code ForbricFmlDiscovery}, but materially simpler:
 * NeoForge's {@code ModFile} is built over a public {@code JarContents.ofPath(Path)} (no SecureJar/union-fs
 * proxy needed), {@code ModFileDiscoveryAttributes.DEFAULT} is public, and the plain static
 * {@code ModSorter.sort(files, issues)} RETURNS the {@code LoadingModList} (it does not self-install — the
 * caller sets it on the current {@code FMLLoader} instance's private {@code loadingModList} field, replacing
 * the empty placeholder seeded at preLaunch).
 *
 * <p>{@code ModSorter.detectSystemMods} demands modids {@code minecraft} AND {@code neoforge}: {@code minecraft}
 * is a ModFile over the patched game jar whose metadata comes from FML's own (package-private)
 * {@code locators.MinecraftModInfo}; {@code neoforge} is the merged neoforge-runtime jar, whose
 * {@code META-INF/neoforge.mods.toml} the assemble script now preserves.
 *
 * <p>Reflection/{@link Proxy}-only, like the rest of the driver (no compile-time NeoForge dependency;
 * NeoForge is LGPL, supplied at runtime).
 */
final class ForbricNeoFmlDiscovery {
	private final ClassLoader cl;

	// resolved NeoForge types (reflected once)
	private Class<?> jarContentsCls;      // net.neoforged.fml.jarcontents.JarContents
	private Class<?> modFileCls;          // net.neoforged.fml.loading.moddiscovery.ModFile
	private Class<?> iModFileCls;         // net.neoforged.neoforgespi.locating.IModFile
	private Class<?> parserItfCls;        // net.neoforged.neoforgespi.locating.ModFileInfoParser
	private Class<?> attributesCls;       // net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes
	private Object defaultAttributes;     // ModFileDiscoveryAttributes.DEFAULT
	private Method ofPath;                // static JarContents.ofPath(Path)
	private Method modsTomlParser;        // static ModFileParser.modsTomlParser(IModFile)
	private Method buildMinecraftModInfo; // MinecraftModInfo.buildMinecraftModInfo(IModFile) (instance)
	private Object minecraftModInfo;      // the MinecraftModInfo instance (package-private class)

	ForbricNeoFmlDiscovery(ClassLoader cl) {
		this.cl = cl;
	}

	/**
	 * Build + scan + sort + install. Returns the {@code LoadingModList} (as Object) or null on failure —
	 * failure is logged and non-fatal: the empty placeholder LoadingModList stays, so vanilla + Fabric still
	 * boot (real-lifecycle NeoForge mod loading just won't see mods).
	 */
	Object run(List<Path> neoModJars, String mcVersion) {
		try {
			resolveTypes(mcVersion);

			List<Object> modFiles = new ArrayList<>();

			// 1) "minecraft" system mod over the patched game jar (metadata via FML's own builder).
			Path gameJar = locateGameJar();
			modFiles.add(newModFile(gameJar, parserProxy(this::buildMinecraftInfo)));

			// 2) "neoforge" (the runtime jar's own neoforge.mods.toml) + every wrapped NeoForge mod,
			//    via FML's own toml parser.
			for (Path jar : neoModJars) {
				modFiles.add(newModFile(jar, parserProxy(this::parseModsToml)));
			}

			// 3) Genuine language resolution (throws on a jar FML cannot type — drop it, keep the rest).
			List<Object> valid = new ArrayList<>();
			Method identifyLanguage = modFileCls.getMethod("identifyLanguage");
			for (Object file : modFiles) {
				try {
					identifyLanguage.invoke(file);
					valid.add(file);
				} catch (Throwable t) {
					ForbricLog.warn("[Forbric/NeoFML] discovery: identifyLanguage failed, dropping " + file,
							t.getCause() != null ? t.getCause() : t);
				}
			}

			// 4) Genuine background scan (populates ModFileScanData - @Mod targets, @EventBusSubscriber, ...);
			//    the handler's ctor submits every file itself. gatherAndInitializeMods later calls
			//    waitForScanToComplete on the CURRENT FMLLoader's public backgroundScanHandler field.
			Class<?> scanHandlerCls = Class.forName("net.neoforged.fml.loading.modscan.BackgroundScanHandler", false, cl);
			Object scanHandler = scanHandlerCls.getConstructor(java.util.Collection.class).newInstance((Object) valid);

			// 5) The keystone: plain static sort BUILDS the LoadingModList; install it on the current loader.
			//    ModSorter is a package-private class, so its public static sort needs setAccessible.
			Class<?> sorterCls = Class.forName("net.neoforged.fml.loading.ModSorter", false, cl);
			Method sort = sorterCls.getDeclaredMethod("sort", List.class, List.class);
			sort.setAccessible(true);
			Object loadingModList = sort.invoke(null, valid, List.of());

			Class<?> fmlLoaderCls = Class.forName("net.neoforged.fml.loading.FMLLoader", false, cl);
			Object loader = fmlLoaderCls.getMethod("getCurrentOrNull").invoke(null);
			if (loader == null) throw new IllegalStateException("FMLLoader not current (seed it before discovery)");

			setInstanceField(fmlLoaderCls, loader, "loadingModList", loadingModList);
			fmlLoaderCls.getField("backgroundScanHandler").set(loader, scanHandler);

			report(loadingModList);
			return loadingModList;
		} catch (Throwable t) {
			Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
			ForbricLog.error("[Forbric/NeoFML] genuine discovery FAILED (real-lifecycle boots will not see mods)", cause);
			return null;
		}
	}

	private void resolveTypes(String mcVersion) throws Exception {
		jarContentsCls = Class.forName("net.neoforged.fml.jarcontents.JarContents", false, cl);
		modFileCls = Class.forName("net.neoforged.fml.loading.moddiscovery.ModFile", false, cl);
		iModFileCls = Class.forName("net.neoforged.neoforgespi.locating.IModFile", false, cl);
		parserItfCls = Class.forName("net.neoforged.neoforgespi.locating.ModFileInfoParser", false, cl);
		attributesCls = Class.forName("net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes", false, cl);
		defaultAttributes = attributesCls.getField("DEFAULT").get(null);
		ofPath = jarContentsCls.getMethod("ofPath", Path.class);
		modsTomlParser = Class.forName("net.neoforged.fml.loading.moddiscovery.ModFileParser", false, cl)
				.getMethod("modsTomlParser", iModFileCls);

		// MinecraftModInfo is a package-private final class with a public ctor: (String minecraftVersion).
		Class<?> mcInfoCls = Class.forName("net.neoforged.fml.loading.moddiscovery.locators.MinecraftModInfo", false, cl);
		Constructor<?> mcInfoCtor = mcInfoCls.getDeclaredConstructor(String.class);
		mcInfoCtor.setAccessible(true);
		minecraftModInfo = mcInfoCtor.newInstance(mcVersion);
		buildMinecraftModInfo = mcInfoCls.getMethod("buildMinecraftModInfo", iModFileCls);
		buildMinecraftModInfo.setAccessible(true);
	}

	/** The patched game jar = the code source of a known vanilla class on the Knot classpath. */
	private Path locateGameJar() throws Exception {
		URL url = cl.getResource("net/minecraft/server/Bootstrap.class");
		if (url == null) throw new IllegalStateException("game class net.minecraft.server.Bootstrap not on the Knot classpath");
		String s = url.toString();
		if (!s.startsWith("jar:file:") || !s.contains("!")) throw new IllegalStateException("unexpected game code source: " + s);
		return Path.of(java.net.URI.create(s.substring(4, s.indexOf('!'))));
	}

	/** {@code new ModFile(JarContents.ofPath(jar), parser, ModFileDiscoveryAttributes.DEFAULT)}. */
	private Object newModFile(Path jar, Object parser) throws Exception {
		Object contents = ofPath.invoke(null, jar); // intentionally never closed: live for the whole run
		return modFileCls.getConstructor(jarContentsCls, parserItfCls, attributesCls)
				.newInstance(contents, parser, defaultAttributes);
	}

	private Object buildMinecraftInfo(Object iModFile) throws Exception {
		return buildMinecraftModInfo.invoke(minecraftModInfo, iModFile);
	}

	private Object parseModsToml(Object iModFile) throws Exception {
		return modsTomlParser.invoke(null, iModFile);
	}

	private interface InfoBuilder {
		Object build(Object iModFile) throws Exception;
	}

	/** A reflective {@code ModFileInfoParser} (single abstract method: {@code build(IModFile)}). */
	private Object parserProxy(InfoBuilder builder) {
		return Proxy.newProxyInstance(cl, new Class<?>[]{parserItfCls}, (proxy, method, args) -> {
			if ("build".equals(method.getName())) return builder.build(args[0]);
			switch (method.getName()) {
			case "toString": return "ForbricNeoModFileInfoParser";
			case "hashCode": return System.identityHashCode(proxy);
			case "equals": return proxy == args[0];
			default: throw new UnsupportedOperationException("Forbric proxy does not implement " + method);
			}
		});
	}

	private static void setInstanceField(Class<?> cls, Object instance, String name, Object value) throws Exception {
		Field f = cls.getDeclaredField(name);
		f.setAccessible(true);
		f.set(instance, value);
	}

	// --- report ---------------------------------------------------------------------------------------

	private void report(Object loadingModList) {
		try {
			List<?> mods = (List<?>) loadingModList.getClass().getMethod("getMods").invoke(loadingModList);
			List<String> ids = new ArrayList<>();
			for (Object modInfo : mods) {
				Object id = modInfo.getClass().getMethod("getModId").invoke(modInfo);
				Object ver = modInfo.getClass().getMethod("getVersion").invoke(modInfo);
				ids.add(id + "@" + ver);
			}
			List<?> issues = (List<?>) loadingModList.getClass().getMethod("getModLoadingIssues").invoke(loadingModList);
			ForbricLog.info("[Forbric/NeoFML] genuine LoadingModList installed: " + ids.size() + " mod(s) " + ids
					+ (issues.isEmpty() ? ", 0 issues" : ", ISSUES: " + issues));
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/NeoFML] LoadingModList report failed", t);
		}
	}
}
