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

package net.forbric.kernel.mixin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.api.ModCatalog;
import net.forbric.kernel.util.ForbricLog;

/**
 * Keeps a suppression off the mod's row when the mod's own mixin config plugin did not want that mixin either.
 *
 * <p>A config plugin ({@code IMixinConfigPlugin}) is how a mod says "apply this mixin only when …". The common
 * case is a compatibility mixin gated on another mod being installed — {@code mixinconstraints}'
 * {@code @IfModLoaded}, which Flashback uses for its Bobby, Sodium and Iris compat classes. On Fabric the plugin
 * is asked first and answers {@code false}, so the mixin is never prepared and nothing is lost.
 *
 * <p>Here the order is inverted, for a reason worth keeping: {@link KernelGuestMixinAdapter} rewrites the config
 * JSON to remove a mixin it has judged {@code UNFIT} <em>before</em> Mixin reads it, because a mixin that fails
 * to APPLY costs its target class every other mod's mixins too. So the plugin is never asked about the entry
 * that was removed, and the mod got marked DEGRADED for losing a mixin it had itself switched off. Flashback's
 * {@code compat.bobby.MixinIntegratedServer} is the worked example: {@code @IfModLoaded("bobby")}, bobby not
 * installed, every anchor resolving against Bobby's classes and therefore none of them resolving here — UNFIT
 * for exactly the reason the mod already knew about.
 *
 * <p>What this class changes is the REPORT, never what applies. The suppression still happens, at the same
 * moment, for the same reason. The attribution is held back and settled later, by asking the plugin the same
 * question Mixin would have: {@code shouldApplyMixin(target, mixin)}. Only a clear {@code false} clears the mod.
 * No plugin instance, a plugin that throws (the guard turns that into {@code true}), anything other than
 * {@code false} — all attribute exactly as before. Guessing in the mod's favour is how a report starts lying.
 *
 * <p>The instance is the one Mixin built, captured by {@link GuestMixinPluginGuard} when it wraps {@code onLoad}.
 * Constructing a second one would be a side effect nobody asked for, and would answer for a plugin that had not
 * been initialised.
 *
 * <p>{@code -Dforbric.pluginDeclinedMixins=off} attributes everything, which is how the claim in each skipped
 * line can be checked against the Mods screen.
 */
public final class PluginDeclinedMixins {
	static final String PROPERTY = "forbric.pluginDeclinedMixins";

	/** One held-back attribution: everything needed to either ask the plugin or mark the mod. */
	record Pending(String configName, String pluginClass, String mixinEntry, String mixinClass, String target,
			String detail) {
	}

	private static final List<Pending> PENDING = java.util.Collections.synchronizedList(new ArrayList<>());
	/** Dotted plugin class name → the instance Mixin built. */
	private static final Map<String, Object> PLUGINS = new ConcurrentHashMap<>();

	private PluginDeclinedMixins() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * Remembers the plugin Mixin just built. Called from the {@code onLoad} wrapper
	 * {@link GuestMixinPluginGuard} generates, which is the one method every plugin declares and Mixin calls
	 * exactly once, right after construction and before it asks anything.
	 */
	public static void rememberPlugin(Object plugin) {
		if (plugin == null) return;
		PLUGINS.put(plugin.getClass().getName(), plugin);
	}

	/**
	 * Holds an attribution back until the plugin can be asked, or marks the mod now when it cannot be.
	 *
	 * @return true when the attribution was deferred
	 */
	static boolean defer(String configName, String pluginClass, String mixinEntry, String mixinClass, String target,
			String detail) {
		if (!enabled() || pluginClass == null || pluginClass.isEmpty()) return false;
		PENDING.add(new Pending(configName, pluginClass, mixinEntry, mixinClass, target, detail));
		return true;
	}

	/**
	 * Settles every held-back attribution. Idempotent, and safe to call from any of the load report's writers:
	 * each pending entry is removed before it is settled, so a second call has nothing to do.
	 */
	public static void resolve() {
		List<Pending> batch;
		synchronized (PENDING) {
			if (PENDING.isEmpty()) return;
			batch = new ArrayList<>(PENDING);
			PENDING.clear();
		}

		for (Pending p : batch) {
			try {
				settle(p);
			} catch (Throwable t) {
				// The load report calls this; one bad entry must not cost the whole file. Mark and move on.
				ForbricLog.debug("[Forbric/Mixin] could not settle the attribution for %s — %s", p.mixinClass(),
						String.valueOf(t));
				String modId = MixinConfigOwners.modIdOf(p.configName());
				if (modId != null) ModCatalog.mark(modId, ModCatalog.Status.DEGRADED, p.detail());
			}
		}
	}

	/** One held-back attribution: cleared only by a clear {@code false}, marked on anything else. */
	private static void settle(Pending p) {
		if (Boolean.FALSE.equals(askThePlugin(p))) {
			ForbricLog.info("[Forbric/Mixin] not marking %s for %s:%s — the mod's own config plugin %s does not "
					+ "apply that mixin on this instance either, so leaving it out cost the mod nothing",
					String.valueOf(MixinConfigOwners.modIdOf(p.configName())),
					MixinConfigOwners.describe(p.configName()), p.mixinEntry(), p.pluginClass());
			return;
		}
		String modId = MixinConfigOwners.modIdOf(p.configName());
		if (modId != null) ModCatalog.mark(modId, ModCatalog.Status.DEGRADED, p.detail());
	}

	/**
	 * {@code shouldApplyMixin} on the live plugin, or null when there is no answer to be had.
	 *
	 * <p>Reflective on purpose: the plugin is a guest class loaded by the game loader, and the kernel is not
	 * compiled against Mixin's extensibility interface from here. Any failure answers null, which attributes.
	 */
	private static Boolean askThePlugin(Pending p) {
		Object plugin = PLUGINS.get(p.pluginClass());
		if (plugin == null) return null;
		try {
			Method m = plugin.getClass().getMethod("shouldApplyMixin", String.class, String.class);
			Object answer = m.invoke(plugin, p.target(), p.mixinClass());
			return answer instanceof Boolean b ? b : null;
		} catch (Throwable noAnswer) {
			ForbricLog.debug("[Forbric/Mixin] could not ask %s about %s — %s", p.pluginClass(), p.mixinClass(),
					String.valueOf(noAnswer));
			return null;
		}
	}

	/** How many attributions are still waiting on a plugin; a test and diagnostics seam. */
	static int pending() {
		return PENDING.size();
	}

	/** Test seam: forget every captured plugin and pending attribution. */
	static void reset() {
		PENDING.clear();
		PLUGINS.clear();
	}
}
