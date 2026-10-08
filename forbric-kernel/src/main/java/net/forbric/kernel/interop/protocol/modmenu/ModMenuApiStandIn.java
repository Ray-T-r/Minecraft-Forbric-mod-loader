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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.ForbricLog;

/**
 * Offers the kernel's stand-in for Mod Menu's API to the game class loader, when no installed jar has the real one.
 *
 * <h2>The failure it prevents</h2>
 *
 * <p>A Fabric mod's config screen is reachable from exactly one declaration: a {@code "modmenu"} entrypoint whose
 * class implements {@code com.terraformersmc.modmenu.api.ModMenuApi}. Fabric itself has no config-screen API, so
 * that interface belongs to the Mod Menu MOD — and on an instance without Mod Menu the entrypoint class cannot be
 * linked at all. The kernel's Mods screen asked Mod Menu for Fabric configs and, finding none installed, showed no
 * Config button for any Fabric mod. A player's pack had 17 Fabric mods declaring one and no Mod Menu, and the Mods
 * screen offered none of their settings ({@code mods with a config screen: 0 Fabric}) — only the mods with their own
 * way in, such as a hotkey or a button on the video settings, could be configured at all.
 *
 * <p>With the five API types present, those entrypoints link and {@code KernelModConfigScreens} reads them the way
 * Mod Menu's own client initializer does. Only the API package is stood in for, plus the {@code NullScreenFactory} its default
 * returns: the mod id {@code modmenu} stays unloaded, and Mod Menu's internals ({@code com.terraformersmc.modmenu.gui.ModsScreen}, the target of Do a Barrel
 * Roll's Mod-Menu-only mixin) stay absent, so code guarded by {@code isModLoaded("modmenu")} keeps skipping.
 *
 * <h2>Why a real Mod Menu always wins</h2>
 *
 * <p>Twice over. This checks first and offers nothing when any owned jar — top level or extracted from a jar-in-jar,
 * both are owned by the time this runs — has {@code ModMenuApi.class}. And the bytes go to
 * {@link ForbricClassLoader#putGeneratedClass}, which the loader consults only after every owned jar has missed the
 * class, so even an offered stand-in could not shadow a jar's copy. The bytes are compiled from
 * {@code src/modmenuApi/java} and shipped as RESOURCES in the game-side jar, under a {@code .class.bin} name no class
 * loader resolves; as class files there they would be one of the loader's own jars and would win.
 *
 * <p>Client only: Mod Menu is a client mod, its API names client screens, and nothing on a dedicated server reads a
 * {@code "modmenu"} entrypoint. {@code -Dforbric.modMenuStandIn=off} restores the old behaviour — no stand-in, and a
 * Fabric mod gets a Config button only from an installed Mod Menu.
 */
public final class ModMenuApiStandIn {
	public static final String SWITCH = "forbric.modMenuStandIn";
	/** The class whose presence in an installed jar means the real API is there. */
	public static final String API = "com/terraformersmc/modmenu/api/ModMenuApi";
	/** Mod Menu's "this mod has no config screen" factory, which the API's default returns. */
	public static final String NULL_FACTORY = "com/terraformersmc/modmenu/util/NullScreenFactory";
	/** Where the game-side jar carries the stand-in's class files, each with {@code .bin} appended to its name. */
	public static final String RESOURCES = "META-INF/forbric/modmenu-api/";
	/**
	 * All of Mod Menu 20.0.3's API package, so a mod naming any part of it links, and the one internal class the API
	 * itself returns: the {@code NullScreenFactory} its default factory is, which is how a reader tells "no config".
	 */
	public static final List<String> CLASSES = List.of(API,
			"com/terraformersmc/modmenu/api/ConfigScreenFactory",
			"com/terraformersmc/modmenu/api/UpdateChecker",
			"com/terraformersmc/modmenu/api/UpdateInfo",
			"com/terraformersmc/modmenu/api/UpdateChannel",
			NULL_FACTORY);

	/** What {@link #install} did. */
	public enum Outcome {
		/** {@code -Dforbric.modMenuStandIn=off}. */
		OFF,
		/** An installed jar has the real API; nothing was offered. */
		PROVIDED,
		/** The stand-in's classes were offered to the loader. */
		OFFERED,
		/** This kernel build carries no stand-in (built without the game side). */
		UNAVAILABLE,
	}

	private ModMenuApiStandIn() {
	}

	/** Whether the stand-in may be offered, and whether {@code KernelModConfigScreens} reads Mod Menu entrypoints. */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/**
	 * Offers the stand-in to {@code loader} unless an owned jar has the real API.
	 *
	 * <p>Called once the loader's URLs are final and before any mod class can load, which is the only window in
	 * which the answer for {@code ModMenuApi} is still open: once something has linked against it, it is whatever
	 * was defined first.
	 */
	public static Outcome install(ForbricClassLoader loader) {
		if (!enabled()) {
			ForbricLog.warn("[Forbric/ModConfig] -D%s=off — no Mod Menu API stand-in; a Fabric mod has a Config button "
					+ "only if Mod Menu itself is installed", SWITCH);
			return Outcome.OFF;
		}
		URL real = loader.findResource(API + ".class");
		if (real != null) {
			ForbricLog.info("[Forbric/ModConfig] Mod Menu's API is installed (%s) — Fabric mods' config screens come "
					+ "from it; no stand-in", jarOf(real));
			return Outcome.PROVIDED;
		}
		Map<String, byte[]> classes = new LinkedHashMap<>();
		for (String name : CLASSES) {
			byte[] bytes = read(loader.findResource(RESOURCES + name + ".class.bin"));
			if (bytes == null) {
				ForbricLog.warn("[Forbric/ModConfig] this kernel build carries no Mod Menu API stand-in (%s is missing) — "
						+ "without Mod Menu installed, Fabric mods have no Config button", name);
				return Outcome.UNAVAILABLE;
			}
			classes.put(name, bytes);
		}
		classes.forEach(loader::putGeneratedClass);
		ForbricLog.info("[Forbric/ModConfig] Mod Menu is not installed — Forbric stands in for its API (%d classes), so "
				+ "a Fabric mod's own Mod Menu entrypoint can give it a Config button; the mod 'modmenu' stays absent",
				classes.size());
		return Outcome.OFFERED;
	}

	private static byte[] read(URL resource) {
		if (resource == null) return null;
		try (InputStream in = resource.openStream()) {
			return in.readAllBytes();
		} catch (IOException e) {
			return null;
		}
	}

	/** {@code jar:file:/x/modmenu.jar!/a/B.class} → {@code modmenu.jar}, for the log line. */
	private static String jarOf(URL resource) {
		String spelled = resource.toString();
		int bang = spelled.indexOf("!/");
		String jar = bang < 0 ? spelled : spelled.substring(0, bang);
		return jar.substring(jar.lastIndexOf('/') + 1);
	}
}
