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
import java.util.List;
import java.util.Map;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/**
 * Loads each carrier's OWN built-in translations, the way its client mod loader would have.
 *
 * <p>The carriers keep a second, private translation table beside Minecraft's. Minecraft's is built from the
 * resource packs at the first reload; the carrier's is a plain map loaded from the classpath before any of that,
 * because the text it holds — the loading screen, the mod list, the "you are running X" branding, every load-error
 * message — has to render before a resource pack exists. {@code FMLTranslations.getPattern} reads
 * {@code I18nManager.currentLocale} and MinecraftForge's {@code ForgeI18n.getPattern} reads its own map; neither
 * ever consults the resource manager, and when a key is missing BOTH return the key itself, so the screen shows
 * {@code fml.menu.branding} where it meant to show a sentence.
 *
 * <p>That table is filled in exactly one place per carrier, and both are inside the client mod loader the kernel
 * replaces: NeoForge's {@code ClientModLoader.begin()} calls {@code LanguageHook.loadBuiltinLanguages()}, which
 * reads {@code assets/minecraft/lang/en_us.json} and {@code assets/neoforge/lang/en_us.json} off the context class
 * loader and ends in {@code I18nManager.injectTranslations}; MinecraftForge's twin is
 * {@code LanguageHook.loadForgeAndMCLangs()}, ending in {@code ForgeI18n.loadLanguageData}. The kernel redirects
 * the {@code begin()} CALL SITE, so its body never runs and neither map was ever filled — on a Forbric client the
 * main menu's branding line and the loading screen's continue button rendered as their raw keys.
 *
 * <p>Both calls are idempotent (each rebuilds its map from scratch) and neither touches Minecraft's own language,
 * so running them both on a merged base costs nothing: the two tables belong to different classes and neither
 * carrier reads the other's.
 *
 * <p>{@code -Dforbric.carrierLanguages=off} skips the load, which is the old behaviour — every FML-side string
 * renders as its key.
 */
public final class CarrierLanguages {
	static final String PROPERTY = "forbric.carrierLanguages";

	private CarrierLanguages() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * One carrier's built-in language load.
	 *
	 * @param label      the ecosystem's name, for the log
	 * @param hookClass  the class holding the load method
	 * @param loadMethod its no-argument static loader
	 * @param storeClass the class holding the filled table
	 * @param storeField that table's static field
	 * @param probeKey   a key the carrier's own {@code lang/en_us.json} declares, so a table that loaded but
	 *                   loaded the wrong thing is still reported as a failure
	 */
	record Carrier(String label, String hookClass, String loadMethod, String storeClass, String storeField,
			String probeKey) {
	}

	/** What one carrier's load left behind: the size of its table and whether the probe key is in it. */
	record Loaded(int size, boolean probeResolved) {
	}

	private static final List<Carrier> CARRIERS = List.of(
			new Carrier("neoforge", ForeignType.LANGUAGE_HOOK.binary(Ecosystem.NEOFORGE), "loadBuiltinLanguages",
					"net.neoforged.fml.i18n.I18nManager", "currentLocale", "fml.menu.branding"),
			new Carrier("forge", ForeignType.LANGUAGE_HOOK.binary(Ecosystem.FORGE), "loadForgeAndMCLangs",
					"net.minecraftforge.common.ForgeI18n", "i18n", "fml.menu.mods"));

	static List<Carrier> carriers() {
		return CARRIERS;
	}

	/** Runs every carrier present on {@code cl}. Best-effort: a carrier that is absent or throws costs only its text. */
	public static void loadBuiltins(ClassLoader cl) {
		if (!enabled()) {
			ForbricLog.warn("[Forbric/Lang] -D%s=off — the carriers' built-in translations are not loaded, so "
					+ "FML-side text renders as its raw key", PROPERTY);
			return;
		}
		for (Carrier carrier : CARRIERS) load(cl, carrier);
	}

	/**
	 * Calls one carrier's loader and reports what landed in its table.
	 *
	 * <p>The context class loader is pointed at {@code cl} for the call: both loaders read their assets through
	 * {@code Thread.currentThread().getContextClassLoader()}, and the thread that reaches the kernel's mod-loading
	 * window is not guaranteed to be carrying the loader that can see the carrier jars.
	 *
	 * @return what landed in the carrier's table, or null when the carrier is absent or its loader threw
	 */
	static Loaded load(ClassLoader cl, Carrier carrier) {
		Class<?> hook;
		try {
			hook = Class.forName(carrier.hookClass(), false, cl);
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Lang] no %s carrier on the classpath; its built-in translations are not needed",
					carrier.label());
			return null;
		}

		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		try {
			thread.setContextClassLoader(cl);
			Method load = hook.getDeclaredMethod(carrier.loadMethod());
			load.setAccessible(true);
			load.invoke(null);
		} catch (Throwable t) {
			ForbricLog.warn(String.format("[Forbric/Lang] the %s carrier's built-in translations did not load; its "
					+ "own screens will render their raw keys", carrier.label()), Reflect.unwrap(t));
			return null;
		} finally {
			thread.setContextClassLoader(previous);
		}

		Map<?, ?> table = table(cl, carrier);
		int size = table == null ? 0 : table.size();
		boolean resolves = table != null && table.containsKey(carrier.probeKey());
		if (resolves) {
			ForbricLog.info("[Forbric/Lang] %s carrier: %d built-in translation(s) loaded; '%s' resolves",
					carrier.label(), size, carrier.probeKey());
		} else {
			ForbricLog.warn("[Forbric/Lang] %s carrier loaded %d built-in translation(s) but '%s' still does not "
					+ "resolve; its own screens will render their raw keys", carrier.label(), size,
					carrier.probeKey());
		}
		return new Loaded(size, resolves);
	}

	/** The carrier's filled table, or null when the field is not where it was. */
	private static Map<?, ?> table(ClassLoader cl, Carrier carrier) {
		try {
			Field field = Class.forName(carrier.storeClass(), false, cl).getDeclaredField(carrier.storeField());
			field.setAccessible(true);
			return field.get(null) instanceof Map<?, ?> map ? map : null;
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Lang] could not read the %s carrier's translation table: %s", carrier.label(),
					String.valueOf(Reflect.unwrap(t)));
			return null;
		}
	}
}
