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

package net.fabricmc.loader.impl.game.minecraft;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.boot.KernelFabricEcosystem;
import net.forbric.kernel.boot.KernelLifecycle;
import net.forbric.kernel.util.ForbricLog;

/**
 * The two calls Fabric Loader patches into the game's entry points, which mods anchor on as "Fabric has
 * initialised the mods".
 *
 * <p>Fabric's {@code EntrypointPatch} inserts {@code Hooks.startServer(runDir, null)} into
 * {@code net.minecraft.server.Main.main} and {@code Hooks.startClient(gameDirectory, this)} into
 * {@code Minecraft.<init>}; each runs the {@code main} entrypoints and then the sided ones. Neither call is in the
 * vanilla bytecode, and mods treat their presence as part of Fabric's API anyway: owo's {@code MainMixin} and
 * {@code MinecraftMixin} inject {@code @At(INVOKE, target = Hooks.startServer/startClient, shift = AFTER)} to
 * freeze its network channels, particle controllers and registry-set calls once every mod has initialised, in a
 * {@code @Group(min = 1)}. With no such call in the kernel's game, Mixin found zero matches, the group failed
 * ("expected 1 invocation(s) but 0 succeeded"), owo's freeze never ran, and the load report marked owo as only
 * partly running.
 *
 * <p>The kernel runs the entrypoints itself, so the calls are emitted at the kernel's EQUIVALENT points rather than
 * at Fabric's literal instructions ({@code LifecycleHookInjector}, {@code ClientEntrypointHookInjector}):
 * <ul>
 *   <li>{@link #startServer} is a MARKER placed immediately after the kernel's server mod-loading window. It runs
 *       nothing — the window it follows already ran the {@code main} and {@code server} entrypoints — so an
 *       injection after it sees exactly what it sees on Fabric: every mod initialised. Placing it at Fabric's own
 *       spot (right after {@code Bootstrap.validate}) would have let owo freeze BEFORE any Fabric
 *       {@code onInitialize} ran, and a channel created there would then throw.</li>
 *   <li>{@link #startClient} is the client entrypoint hook itself, carrying Fabric's arguments: the kernel's
 *       {@code onClientEntrypoints} window runs from inside it.</li>
 * </ul>
 *
 * <p>Only what mods are observed to anchor on is declared (the same rule as {@code FabricLauncher}); the rest of
 * Fabric's {@code Hooks} is Fabric's own plumbing. {@code -Dforbric.fabricHooks=off} stops the calls being emitted.
 */
public final class Hooks {
	private static final AtomicBoolean SERVER_REACHED = new AtomicBoolean();
	private static final AtomicBoolean CLIENT_STARTED = new AtomicBoolean();

	private Hooks() {
	}

	/**
	 * Marks the end of the server's mod initialisation, where Fabric's own {@code startServer} returns.
	 *
	 * <p>Runs nothing. If the Fabric {@code main} entrypoints have NOT run by now, the call was emitted in the
	 * wrong place — a mod hooked after it would be acting before the mods it waits for — so that is said once,
	 * loudly, instead of silently running them out of the registry window they need.
	 *
	 * @param runDir       unused; Fabric passes {@code null} here too
	 * @param gameInstance unused; Fabric passes {@code null} here too
	 */
	public static void startServer(File runDir, Object gameInstance) {
		if (!SERVER_REACHED.compareAndSet(false, true)) return;

		if (KernelFabricEcosystem.active() && !KernelFabricEcosystem.mainsAlreadyRan()) {
			ForbricLog.error("[Forbric/Fabric] Hooks.startServer was reached before the Fabric main entrypoints "
					+ "ran — a mod that hooks after it (owo's freeze) now acts before the mods it waits for");
			return;
		}
		ForbricLog.debug("[Forbric/Fabric] Hooks.startServer reached after the server mod-loading window");
	}

	/**
	 * Runs the Fabric {@code main} and {@code client} entrypoints, inside {@code Minecraft.<init>}, exactly where
	 * the kernel always has (see {@code KernelLifecycle.onClientEntrypoints}).
	 *
	 * <p>Once only: the window reopens the registries, and a second call would reopen them again.
	 *
	 * @param runDir       the game directory, as Fabric passes it
	 * @param gameInstance the {@code Minecraft} being constructed, as Fabric passes it
	 */
	public static void startClient(File runDir, Object gameInstance) {
		if (!CLIENT_STARTED.compareAndSet(false, true)) {
			ForbricLog.warn("[Forbric/Fabric] Hooks.startClient called a second time — ignored, the client "
					+ "entrypoints already ran");
			return;
		}
		KernelLifecycle.onClientEntrypoints();
	}
}
