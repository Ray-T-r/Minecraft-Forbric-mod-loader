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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Feeds NeoForge's own {@code RuntimeEnumExtender} the {@code META-INF/enumextensions.json} of every Forge-family
 * mod jar, so {@link net.forbric.kernel.transform.NeoEnumExtensionInjector} can then add those constants to the
 * target enums as they load.
 *
 * <p><b>What this mechanism is.</b> A NeoForge mod cannot add a constant to a vanilla enum in Java, so FML does it
 * in bytecode: the mod declares the constant in {@code META-INF/enumextensions.json} (name, constructor descriptor,
 * and either literal parameters or a reference to a static field holding them), and FML rewrites the enum's
 * {@code <clinit>} and {@code $VALUES} array at class load. Three mods in the Odyssey pack use it — Tool Belt and
 * Sophisticated Backpacks add {@code ItemDisplayContext} constants, Earth Mobs adds a {@code Raid$RaiderType} —
 * and without it Sophisticated Backpacks' model loader hit
 * {@code No enum constant ItemDisplayContext.SOPHISTICATEDBACKPACKS_WORN} during the resource reload and took the
 * whole client down.
 *
 * <p><b>Why drive NeoForge's transformer instead of writing one.</b> The rewrite is not a one-liner: it appends
 * fields, extends {@code $VALUES} in place, renumbers ordinals, resolves {@code EnumProxy} field references,
 * synthesises the {@code ExtensionInfo} accessor and honours {@code @IndexedEnum}/{@code @NamedEnum}/
 * {@code @ReservedConstructor}. {@code RuntimeEnumExtender} is a {@code ClassProcessor} sitting in the runtime jar
 * with a public {@code loadEnumPrototypes(Map<IModInfo, JarResource>)} entry point, so the kernel supplies the
 * inputs and lets NeoForge's compiled code do the transform — the same "read their bytecode for the contract,
 * write none of their source" line the rest of the kernel holds.
 *
 * <p>Everything here is best-effort: a runtime without the enumextension package, or a mod with a malformed json,
 * logs and leaves the enums alone rather than failing the boot.
 */
public final class NeoEnumExtensions {
	/** {@code -Dforbric.enumExtensions=off} restores the previous behaviour (no constants added). */
	static final String SWITCH = "forbric.enumExtensions";

	private static final String DECLARATION = "META-INF/enumextensions.json";
	private static final String EXTENDER =
			net.forbric.api.ForeignType.RUNTIME_ENUM_EXTENDER.binary(net.forbric.api.Ecosystem.NEOFORGE);

	private NeoEnumExtensions() {
	}

	/**
	 * Parses every Forge-family mod jar's declaration and hands the set to {@code RuntimeEnumExtender}.
	 *
	 * @return the number of mods that declared extensions; 0 means the injector has nothing to do.
	 */
	public static int load(ClassLoader gameLoader, List<Path> modJars) {
		if ("off".equalsIgnoreCase(System.getProperty(SWITCH, "on"))) {
			ForbricLog.warn("[Forbric/EnumExt] enum extensions DISABLED (-D%s=off) — a mod that adds a vanilla enum "
					+ "constant will fail on the first lookup of it", SWITCH);
			return 0;
		}

		try {
			Class<?> extender = Class.forName(EXTENDER, false, gameLoader);
			Class<?> iModInfo = Class.forName(ForeignType.MOD_INFO_SPI.binary(Ecosystem.NEOFORGE), false, gameLoader);
			Class<?> jarResource = Class.forName("net.neoforged.fml.jarcontents.JarResource", false, gameLoader);

			ForbricModDiscoverer discoverer = new ForbricModDiscoverer();
			Map<Object, Object> declarations = new LinkedHashMap<>();
			Map<String, String> byMod = new LinkedHashMap<>();
			for (Path jar : modJars) {
				for (var declaration : declarationsForJar(discoverer, jar).entrySet()) {
					declarations.put(modInfo(iModInfo, declaration.getKey()), resource(gameLoader, jarResource, declaration.getValue()));
					byMod.put(declaration.getKey(), jar.getFileName().toString());
				}
			}
			if (declarations.isEmpty()) return 0;

			// loadEnumPrototypes REPLACES the static prototype map, so this must be one call with every mod in it.
			extender.getMethod("loadEnumPrototypes", Map.class).invoke(null, declarations);

			// Report what actually landed, not what was handed over. NeoForge COLLECTS a bad declaration as a
			// ModLoadingIssue instead of throwing, so a wholly rejected set returns normally and would otherwise
			// read as a clean load — which is exactly how a wrong mod id hid here for two runs.
			Map<?, ?> accepted = acceptedPrototypes(extender);
			if (accepted != null && accepted.isEmpty()) {
				ForbricLog.warn("[Forbric/EnumExt] %d mod(s) declared enum extensions and NONE were accepted %s — "
						+ "NeoForge rejected every declaration (a constant must be prefixed with its mod id in "
						+ "upper case); those mods' constants will be missing", byMod.size(), byMod.keySet());
				return 0;
			}
			ForbricLog.info("[Forbric/EnumExt] loaded enum extension declarations from %d mod(s) %s — extending %s",
					byMod.size(), byMod.keySet(), accepted == null ? "(count unavailable)" : accepted.keySet());
			return byMod.size();
		} catch (ClassNotFoundException absent) {
			// no NeoForge enumextension package — skipping
			Caught.expected(absent);
			return 0;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/EnumExt] could not load enum extension declarations — mods that add vanilla "
					+ "enum constants will fail on the first lookup");
			return 0;
		}
	}

	/** The prototypes NeoForge actually accepted, keyed by target enum; null if the field cannot be read. */
	private static Map<?, ?> acceptedPrototypes(Class<?> extender) {
		try {
			java.lang.reflect.Field field = extender.getDeclaredField("prototypes");
			field.setAccessible(true);
			return (Map<?, ?>) field.get(null);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/EnumExt] could not read the accepted prototypes of %s; their count is unavailable",
					extender.getName());
			return null;
		}
	}

	/**
	 * The full {@code IModInfo} chain, not a getModId()-only stub.
	 *
	 * <p>{@code EnumPrototype.load} reports a rejected declaration through {@code ModLoadingIssue.withAffectedMod},
	 * which dereferences {@code getOwningFile().getFile().getFilePath()}. With a bare stub that NPEs and MASKS
	 * whatever the declaration was actually rejected for — the same trap {@code KernelModContainerFactory}'s
	 * listener-error path documents, which is why this reuses its hardened proxy rather than growing a second one.
	 */
	private static Object modInfo(Class<?> iModInfo, String modId) throws Exception {
		return KernelModContainerFactory.modInfo(iModInfo.getClassLoader(), modId);
	}

	/**
	 * A {@code JarResource} over bytes already in hand.
	 *
	 * <p>{@code readAllBytes()} and {@code bufferedReader()} are DEFAULT methods on the interface, and that is a
	 * trap here: a dynamic {@code Proxy} routes default methods to the handler too — their default bodies never
	 * run. Left to the fallback they returned null, {@code EnumPrototype.load} read no json at all, and every
	 * declaration came back as zero entries with no error anywhere. So they are implemented explicitly.
	 */
	private static Object resource(ClassLoader cl, Class<?> jarResource, byte[] bytes) {
		Object[] self = new Object[1];
		InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
			case "open" -> new ByteArrayInputStream(bytes);
			case "readAllBytes" -> bytes.clone();
			case "bufferedReader" -> new java.io.BufferedReader(new java.io.InputStreamReader(
					new ByteArrayInputStream(bytes),
					args != null && args.length == 1 && args[0] instanceof java.nio.charset.Charset cs
							? cs : java.nio.charset.StandardCharsets.UTF_8));
			case "retain" -> self[0];
			case "toString" -> "KernelJarResource[" + bytes.length + "B]";
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == (args == null ? null : args[0]);
			default -> defaultReturn(method);
		};
		Object proxy = Proxy.newProxyInstance(cl, new Class<?>[] {jarResource}, h);
		self[0] = proxy;
		return proxy;
	}

	private static Object defaultReturn(Method method) {
		Class<?> r = method.getReturnType();
		if (r == boolean.class) return Boolean.FALSE;
		if (r == int.class) return 0;
		if (r == List.class) return List.of();
		if (r == Map.class) return Map.of();
		return null;
	}

	/** Reads each NeoForge mod's declared enum resource, retaining the conventional path for older mods. */
	static Map<String, byte[]> declarationsForJar(ForbricModDiscoverer discoverer, Path jar) {
		try {
			Map<String, byte[]> result = new LinkedHashMap<>();
			for (DiscoveredMod mod : discoverer.discoverJar(jar)) {
				if (mod.getEcosystem() != Ecosystem.NEOFORGE) continue;
				Object configured = mod.getConfigElements().get("enumExtensions");
				String path = configured instanceof String value && !value.isBlank() ? value : DECLARATION;
				byte[] json = read(jar, path);
				if (json != null) result.put(mod.getId(), json);
				else if (configured instanceof String) ForbricLog.warn("[Forbric/EnumExt] %s declares missing enum extension resource %s", mod.getId(), path);
			}
			return result;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/EnumExt] could not read enum extension declarations from %s; none of them load", jar);
			return Map.of();
		}
	}

	private static byte[] read(Path jar, String entry) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry e = zip.getEntry(entry);
			if (e == null) return null;
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/EnumExt] could not read %s from %s", entry, jar);
			return null;
		}
	}
}
