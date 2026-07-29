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

import net.forbric.loader.impl.forge.bridge.ForbricForgeClientPacks;
import net.forbric.loader.impl.util.ForbricLog;

/**
 * Tri-in-one CLIENT twin of {@link ForbricDualLifecycle}: drives Forge's genuine <em>client</em> mod-loading on the
 * MERGED (Forge+NeoForge+Fabric) game base. On the merged client, NeoForge won the byte-merge for the client
 * mod-loading path — {@code Main} calls NeoForge's {@code ClientModLoader.begin()}, and {@code Minecraft.<init>}
 * calls NeoForge's {@code setupModResourcePacks}+{@code finish} — so NeoForge + Fabric mods load coherently, but
 * Forge's own {@code ClientModLoader.begin(Minecraft, PackRepository, ReloadableResourceManager)} (which in the
 * pure-Forge ctor kicks off Forge's client mod construction + {@code RegisterEvent}s) was dropped. Its trailing
 * {@code completeModLoading()} DID survive in {@code Minecraft.lambda$new$8}, but with no {@code begin()} it would
 * run against an unstarted loader (and Forge's static {@code mc} left null). This restores the missing
 * {@code begin()} so Forge client mods actually construct and register.
 *
 * <p>Invoked from {@link net.forbric.loader.impl.forge.mixin.ForbricClientDualLifecycleMixin} at the point in
 * {@code Minecraft.<init>} right AFTER NeoForge's {@code ClientModLoader.finish()} — where the live
 * {@code Minecraft.getInstance()}, its {@code PackRepository} and its {@code ReloadableResourceManager} are all
 * populated (the exact three args Forge's {@code begin} needs). Forge's {@code begin} does its mod construction and
 * {@code RegisterEvent} dispatch SYNCHRONOUSLY (a driven-executor pump, not a background future — byte-for-byte the
 * same shape as the server's {@code ServerModLoader.load()}), so bracketing it in the Forge registry-freeze reset is
 * sufficient, exactly like the server path.
 *
 * <p>Also installs {@link net.forbric.loader.impl.forge.bridge.ForbricEventBridge}. An earlier version of this class
 * deliberately did NOT, on the assumption that the client's bridge was owned by the SERVER lifecycle
 * ({@link ForbricDualLifecycle#runForgeServerLifecycle}) once the integrated server started. <b>That assumption was
 * false:</b> {@code runForgeServerLifecycle} is driven by a mixin on NeoForge's {@code ServerModLoader.load()}, which
 * on the merged base is invoked only from {@code net.minecraft.server.Main} / gametest — never by {@code Minecraft} or
 * {@code IntegratedServer}. So the bridge was never installed on the client at all, and Forge's
 * {@code ServerLifecycleHooks.allowLogins} (whose only writer, Forge's {@code handleServerStarted}, lost its merge
 * site) stayed pinned false while Forge's {@code handleServerLogin} — which WON both handshake sites, including the
 * singleplayer in-memory one — rejected every join with "Server is still starting!". Installing here is safe:
 * {@code install()} is CAS-guarded, and a dedicated server never constructs {@code Minecraft}, so the two call sites
 * are mutually exclusive in practice and idempotent in principle.
 *
 * <p>Reflection-only, gated on the traditional-Forge client runtime actually being present — a silent no-op on a
 * pure-NeoForge / pure-Fabric client (where the mixin's injection point doesn't exist anyway). Never aborts the
 * client: any failure is logged, not thrown, and the Forge wrappers are always left frozen.
 */
public final class ForbricClientDualLifecycle {
	private ForbricClientDualLifecycle() {
	}

	private static final AtomicBoolean FORGE_CLIENT_LIFECYCLE_RAN = new AtomicBoolean(false);

	/**
	 * Runs Forge's {@code ClientModLoader.begin(mc, packRepo, resourceManager)} once, wrapped in a Forge
	 * registry-freeze reset so Forge's own unfreeze&rarr;{@code RegisterEvent}&rarr;freeze cycle runs against a clean
	 * slate on the shared merged registries (NeoForge's already-completed client lifecycle left them frozen).
	 * {@code cl} is the class loader the merged game + both runtimes share (Knot).
	 */
	public static void runForgeClientLifecycle(ClassLoader cl) {
		if (!FORGE_CLIENT_LIFECYCLE_RAN.compareAndSet(false, true)) return;

		Class<?> forgeClientModLoader;
		try {
			forgeClientModLoader = Class.forName("net.minecraftforge.client.loading.ClientModLoader", false, cl);
		} catch (Throwable notMerged) {
			// No traditional-Forge client runtime on the classpath — pure-NeoForge / pure-Fabric client, nothing to drive.
			return;
		}

		try {
			// Same fix as the server twin: Forge injects GameData.vanillaSnapshot() into Bootstrap.bootStrap(), but the
			// merged Bootstrap runs NeoForge's — so Forge's vanillaRegistryOrder stays null and its postRegisterEvents
			// NPEs. Replay Forge's snapshot here, once, before driving its lifecycle.
			Class.forName("net.minecraftforge.registries.GameData", false, cl).getMethod("vanillaSnapshot").invoke(null);

			// Reset every Forge-wrapped registry to (frozen=false, frozenTags=unbound) so Forge's own
			// unfreeze/register/freeze window runs against a clean slate — NeoForge's just-completed client lifecycle
			// left them frozen. Same mechanism the server twin uses around ServerModLoader.load().
			ForbricRegistryBridge.setForgeWrappersFrozen(cl, false);

			// Forge's begin(mc, packRepo, rm) does two separable things: (1) SYNCHRONOUS Forge mod construction +
			// RegisterEvent dispatch — the part we need, which touches NEITHER packRepo nor rm — and (2) Forge's own
			// client RESOURCE integration: ResourcePackLoader.loadResourcePacks(packRepo, true), an AddPackFindersEvent,
			// and appending its reload listeners to rm. On the merged client (2) is actively harmful: Forge's
			// loadResourcePacks resets the pack repository, wiping NeoForge's own resource pack (its required render
			// pipelines/shaders then fail to load -> crash), and its unnamed reload listeners break NeoForge's
			// AddClientReloadListenersEvent. So pass begin() the REAL Minecraft (it stashes it in a static the later
			// completeModLoading() needs) but THROWAWAY, empty packRepo + rm: Forge's resource work lands in the
			// throwaways and is discarded, while mod construction still registers Forge content on the real registries.
			// (Forge mods' own client ASSETS are wired separately, just above, by ForbricForgeClientPacks — a dedicated
			// RepositorySource on the REAL repo with distinct pack ids, so it doesn't reintroduce begin()'s hazards.)
			Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", false, cl);
			Object mc = mcClass.getMethod("getInstance").invoke(null);
			if (mc == null) {
				ForbricLog.warn("[Forbric/DualLifecycle] Minecraft.getInstance() is null at the Forge client hook — "
						+ "skipping Forge client lifecycle (Forge client mods will not load)");
				return;
			}

			// Wire traditional-Forge mods' CLIENT ASSETS (shaders/textures/models/lang) onto the REAL client
			// PackRepository so they resolve during the client's first resource reload. Done here — BEFORE that reload
			// and INDEPENDENT of begin() below (a Forge mod's render pipelines register and demand their shaders whether
			// or not begin() fully ran) — while keeping begin()'s throwaway isolation intact. Distinct pack ids, so
			// nothing collides with NeoForge's own mod_resources. See ForbricForgeClientPacks.
			try {
				Object realPackRepo = mcClass.getMethod("getResourcePackRepository").invoke(mc);
				ForbricForgeClientPacks.addForgeModAssetsTo(realPackRepo, cl);
			} catch (Throwable assetsFailed) {
				ForbricLog.warn("[Forbric/DualLifecycle] wiring Forge-mod client assets failed — Forge mods will render "
						+ "untextured, but the client continues", assetsFailed);
			}

			Class<?> packRepoClass = Class.forName("net.minecraft.server.packs.repository.PackRepository", false, cl);
			Class<?> reloadableClass =
					Class.forName("net.minecraft.server.packs.resources.ReloadableResourceManager", false, cl);
			Object throwawayPackRepo = newEmptyPackRepository(cl, packRepoClass);
			Object throwawayResourceManager = newClientResourceManager(cl, reloadableClass);

			ForbricLog.info("[Forbric/DualLifecycle] running genuine Forge ClientModLoader.begin() on the merged client "
					+ "base with isolated (throwaway) resource repo/manager — constructs Forge mods, fires Forge "
					+ "RegisterEvents, sets Forge's client Minecraft ref, without disturbing NeoForge's resource packs");
			Method begin = forgeClientModLoader.getMethod("begin", mcClass, packRepoClass, reloadableClass);
			begin.invoke(null, mc, throwawayPackRepo, throwawayResourceManager);
			ForbricLog.info("[Forbric/DualLifecycle] Forge client begin() complete — Forge mods constructed, "
					+ "RegisterEvents fired; the merged ctor's surviving completeModLoading() will finalize it");
		} catch (Throwable t) {
			// Log, never rethrow: NeoForge's client lifecycle already succeeded; a Forge-side failure must not abort
			// the client. Forge client mods being absent is still better than a crash to desktop.
			ForbricLog.error("[Forbric/DualLifecycle] Forge ClientModLoader.begin() failed on the merged client base", t);
		} finally {
			// Ensure the shared wrappers end frozen even if Forge's own freeze didn't complete — a no-op when Forge
			// already re-froze them via its RegisterEvent window.
			ForbricRegistryBridge.setForgeWrappersFrozen(cl, true);
			net.forbric.loader.impl.compat.ForbricCustomPayloadInterop.bootstrapMirrors(cl);

			// Arm the Neo->Forge bridge on the CLIENT too. Critically this re-anchors Forge's server-lifecycle hooks
			// onto NeoForge's events for the INTEGRATED server — without it, Forge's allowLogins is never set and the
			// merged client can load a world but can never join it. In the finally: a failed begin() above must not
			// leave the login gate pinned shut. See ForbricEventBridge's class javadoc.
			net.forbric.loader.impl.forge.bridge.ForbricEventBridge.install(cl);
		}
	}

	/** A fresh empty {@code PackRepository} (no sources) — a sink for Forge begin()'s resource-pack manipulation. */
	private static Object newEmptyPackRepository(ClassLoader cl, Class<?> packRepoClass) throws Exception {
		Class<?> sourceClass = Class.forName("net.minecraft.server.packs.repository.RepositorySource", false, cl);
		Object emptySources = java.lang.reflect.Array.newInstance(sourceClass, 0);
		java.lang.reflect.Constructor<?> ctor = packRepoClass.getConstructor(emptySources.getClass());
		return ctor.newInstance(emptySources);
	}

	/** A fresh client {@code ReloadableResourceManager} — a sink for the reload listeners Forge begin() appends. */
	private static Object newClientResourceManager(ClassLoader cl, Class<?> reloadableClass) throws Exception {
		Class<?> packTypeClass = Class.forName("net.minecraft.server.packs.PackType", false, cl);
		Object clientResources = packTypeClass.getField("CLIENT_RESOURCES").get(null);
		return reloadableClass.getConstructor(packTypeClass).newInstance(clientResources);
	}
}
