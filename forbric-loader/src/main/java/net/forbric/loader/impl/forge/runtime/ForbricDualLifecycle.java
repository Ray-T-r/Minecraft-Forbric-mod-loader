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

package net.forbric.loader.impl.forge.runtime;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Tri-in-one only: drives Forge's genuine server mod-loading lifecycle on the MERGED (Forge+NeoForge+Fabric)
 * game base, where the game jar's {@code Main.main} entry only calls NeoForge's
 * {@code ServerModLoader.load(boolean)} (NeoForge won the byte-merge for that method), so Forge's own
 * {@code ModLoader}/{@code ModList}/event-bus lifecycle never runs. Without it, {@code net.minecraftforge.fml.
 * ModList.indexedMods} stays null and {@code ServerStatusPing.<init>} NPEs on the main thread the moment the
 * server builds its status (seconds after {@code Done}) — killing a merged server that otherwise boots fine.
 *
 * <p>Invoked from {@link net.forbric.loader.impl.forge.mixin.NeoDualLifecycleMixin} at the TAIL of NeoForge's
 * {@code ServerModLoader.load(Z)} — byte-identically where the Forge-patched {@code Main} would have called
 * Forge's own {@code ServerModLoader.load()}: after NeoForge's complete {@code begin}+{@code load} phases, before
 * EULA / {@code DedicatedServer.initServer}. NeoForge has no later {@code finish} phase to order against.
 *
 * <p>Reflection-only, gated on the traditional-Forge runtime actually being present — a no-op (silent return) on a
 * pure-NeoForge instance, where the mixin's target class exists but there is no Forge {@code ServerModLoader} to
 * drive. Never aborts NeoForge's own completed lifecycle: any failure is logged, not thrown.
 */
public final class ForbricDualLifecycle {
	private ForbricDualLifecycle() {
	}

	private static final AtomicBoolean FORGE_LIFECYCLE_RAN = new AtomicBoolean(false);

	/**
	 * Runs Forge's {@code ServerModLoader.load()} once, wrapping it in a Forge registry-freeze reset so Forge's
	 * own unfreeze→{@code RegisterEvent}→freeze cycle doesn't trip over the freeze NeoForge's just-completed
	 * lifecycle left behind. {@code cl} is the class loader the merged game + both runtimes share (Knot).
	 */
	public static void runForgeServerLifecycle(ClassLoader cl) {
		if (!FORGE_LIFECYCLE_RAN.compareAndSet(false, true)) return;

		Class<?> forgeServerModLoader;
		try {
			forgeServerModLoader = Class.forName("net.minecraftforge.server.loading.ServerModLoader", false, cl);
		} catch (Throwable notMerged) {
			// No traditional-Forge runtime on the classpath — pure-NeoForge instance, nothing to drive.
			return;
		}

		try {
			// Forge injects GameData.vanillaSnapshot() into Bootstrap.bootStrap() (snapshots the freshly-created
			// vanilla registries into RegistryManager.VANILLA and records their key order in vanillaRegistryOrder),
			// but on the MERGED base Bootstrap runs NeoForge's vanillaSnapshot, not Forge's — so Forge's
			// vanillaRegistryOrder stays null and its own postRegisterEvents NPEs on `new LinkedHashSet<>(null)`
			// (caught empirically). Replay Forge's snapshot here, once, before driving its lifecycle. It reads
			// Forge's RegistryManager.ACTIVE (populated by the merged BuiltInRegistries.internalRegister ->
			// GameData.getWrapper path), independent of the NamespacedWrapper freeze state handled below.
			Class.forName("net.minecraftforge.registries.GameData", false, cl).getMethod("vanillaSnapshot").invoke(null);

			// Forge's FREEZE_DATA (the last gather state of ServerModLoader.load) calls NamespacedWrapper.freeze(),
			// which throws "Tags already present before freezing" if frozenTags is still bound from NeoForge's
			// earlier freeze. Reset every Forge-wrapped registry to (frozen=false, frozenTags=unbound) so Forge's
			// own unfreeze/register/freeze window runs against a clean slate — same mechanism the registry bridge
			// already uses around NeoForge's CommonModLoader.begin.
			ForbricRegistryBridge.setForgeWrappersFrozen(cl, false);

			ForbricLog.info("[Forbric/DualLifecycle] running genuine Forge ServerModLoader.load() on the merged base "
					+ "(builds Forge ModList, fires Forge RegisterEvents, starts the Forge event bus)");
			Method load = forgeServerModLoader.getMethod("load");
			load.invoke(null);
			ForbricLog.info("[Forbric/DualLifecycle] Forge server lifecycle complete — ModList populated, Forge bus started");
		} catch (Throwable t) {
			// Log, never rethrow: NeoForge's lifecycle already succeeded; a Forge-side failure must not abort the
			// server. A partially-loaded Forge ModList is still better than the guaranteed ServerStatusPing NPE.
			ForbricLog.error("[Forbric/DualLifecycle] Forge ServerModLoader.load() failed on the merged base", t);
		} finally {
			// Ensure the shared wrappers end frozen even if Forge's own FREEZE_DATA didn't complete (e.g. it threw
			// partway) — a no-op when Forge already froze them.
			ForbricRegistryBridge.setForgeWrappersFrozen(cl, true);
			net.forbric.loader.impl.compat.ForbricCustomPayloadInterop.bootstrapMirrors(cl);

			// Forge's event buses are armed (BusGroup.DEFAULT.startup() ran at the tail of load()). Install the
			// Neo->Forge event bridge so Forge mods see gameplay events, AND so Forge's server-lifecycle hooks —
			// every one of which lost its merge site to NeoForge's — get re-anchored (see ForbricEventBridge).
			// In the finally: a Forge mod-loading failure above must not leave the allowLogins gate pinned shut.
			// CAS-guarded, so the client twin installing first (or at all) can never double-subscribe.
			net.forbric.loader.impl.forge.bridge.ForbricEventBridge.install(cl);
		}
	}
}
