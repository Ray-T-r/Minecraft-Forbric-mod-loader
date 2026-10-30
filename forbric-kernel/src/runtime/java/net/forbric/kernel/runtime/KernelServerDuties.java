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

package net.forbric.kernel.runtime;

import java.util.function.Consumer;

import net.forbric.kernel.util.Caught;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * What the kernel does on a running server for NeoForge and Fabric, apart from any forward to MinecraftForge.
 *
 * <p>Every duty here used to sit inside MinecraftForge-gated code: the compatibility tick on
 * {@link KernelGameServerLifecycle}, a class that names {@code net.minecraftforge.server.ServerLifecycleHooks}; the game
 * instance and the server-started work installed by {@code GameEventMultiplexer}, after its probe for
 * {@code net.minecraftforge.event.ForgeEventFactory} and behind {@code -Dforbric.unifiedEvents}; the experience relay inside
 * the MinecraftForge break bridge. None of them is about MinecraftForge. Measured on the genuine NeoForge base (no
 * MinecraftForge anywhere), the probe's {@code ClassNotFoundException} went to {@code Caught.expected} and took them all
 * with it, silently: {@code FabricLoader.getGameInstance()} stayed null on a dedicated server, no load report was written
 * once a world was up, a dedicated server never made its compatibility decision at world load, and a Fabric mod's hooks in
 * {@code Block.tryDropExperience} never ran. {@code -Dforbric.unifiedEvents=off} did the same on today's base.
 *
 * <p>So this class names nothing of MinecraftForge, and the boot side installs it on NeoForge's bus itself, unconditionally,
 * right after the bridges ({@code KernelServerDutiesInstaller}, from {@code KernelLifecycle.startGameBuses}). Right after
 * them on purpose: each listener here then keeps the place it had among the kernel's own listeners on the same event and
 * priority. What stays with MinecraftForge is what is about MinecraftForge: the login gate its started hook opens, and the
 * dead-event judgement of MinecraftForge listeners ({@link KernelGameServerLifecycle#installStartedDuties}), which runs
 * first, so the load report written here still carries what that judgement found.
 */
public final class KernelServerDuties {
	private KernelServerDuties() {
	}

	/** Invoked by the fixed tick return seam ({@code ServerCompatibilityTickInjector}), independently of any event forwarding. */
	public static void onCompatibilityTick(Object value) {
		MinecraftServer server = (MinecraftServer) value;
		net.forbric.kernel.boot.LateServerCompatibility.tick(server, server.isDedicatedServer(), () -> server.halt(false));
	}

	/**
	 * A dedicated server is Fabric Loader's game instance once it exists: Fabric sets it from the server's own
	 * construction, and {@code FabricLoader.getGameInstance()} answers it from then on (a client's is the Minecraft
	 * instance, set by {@code Hooks.startClient}; an integrated server never replaces it). The server entry does that
	 * itself, as Fabric patches it ({@code LifecycleHookInjector} emits {@code Hooks.setGameInstance} after the server's
	 * constructor); this listener, on NeoForge's {@code ServerAboutToStartEvent} (the earliest event that carries the
	 * server), is only the fallback for an entry where that constructor was not found or the Fabric hooks are off.
	 *
	 * @param neoBus NeoForge's {@code IEventBus}, handed over untyped from the boot side
	 */
	public static void installGameInstance(Object neoBus) {
		Consumer<ServerAboutToStartEvent> listener = neoEvent -> {
			MinecraftServer server = neoEvent.getServer();
			if (server != null && server.isDedicatedServer()) net.forbric.kernel.boot.KernelFabricEcosystem.setGameInstance(server);
		};
		((IEventBus) neoBus).addListener(EventPriority.HIGHEST, false, ServerAboutToStartEvent.class, listener);
	}

	/**
	 * What the kernel does once a server is up: the load report, and on a dedicated server the compatibility decision.
	 *
	 * <p>Registered on NeoForge's bus itself at {@code LOWEST}, after every forward and after MinecraftForge's own
	 * server-started duties, so it runs once per start whoever delivered what, and after the dead-event judgement those
	 * duties make. The world is up, so whatever failed on the way there — a mixin applying to a class first loaded at world
	 * creation, a deferred task on the server thread — is known now. Integrated servers post this too, so the client's
	 * world load is covered; the decision itself is the dedicated server's, a client has its own screen for it.
	 *
	 * @param neoBus NeoForge's {@code IEventBus} itself, not a bridge's guarded view of it
	 */
	public static void installStartedDuties(Object neoBus) {
		installStartedDuties(neoBus, server -> {
			net.forbric.kernel.boot.KernelLoadReport.write();
			if (!net.forbric.kernel.boot.KernelFabricEcosystem.physicalSide().isClient()
					&& !net.forbric.kernel.ui.CompatibilityDecision.check(false)) {
				net.forbric.kernel.util.ForbricLog.error("[Forbric/Compatibility] FATAL: a required feature failed during world loading; stopping the server normally");
				server.halt(false);
			}
		});
	}

	/** {@link #installStartedDuties(Object)} with the kernel's own work handed in, so a test can see it run. */
	static void installStartedDuties(Object neoBus, Consumer<MinecraftServer> duties) {
		Consumer<ServerStartedEvent> listener = neoEvent -> {
			try {
				duties.accept(neoEvent.getServer());
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/EventMux] the kernel's own server-started work failed");
			}
		};
		((IEventBus) neoBus).addListener(EventPriority.LOWEST, false, ServerStartedEvent.class, listener);
	}

	/**
	 * The relay of vanilla's {@code Block.tryDropExperience}, which the merged game never calls (NeoForge moved the value it
	 * computes into {@code BlockDropsEvent}): last of all, whatever the drops event now holds is what a Fabric mod's hooks in
	 * that method are handed, as their own game would hand them the value there, and what they leave is what drops
	 * ({@link net.forbric.kernel.mixin.UncalledValueRelay}). {@code LOWEST}, so every NeoForge listener and MinecraftForge's
	 * experience decision (carried in at {@code HIGHEST} by the break bridge) come first. {@code -Dforbric.uncalledValueRelay=off}
	 * is its switch; nothing woven into the method, or a block whose game never asked it, leaves the event as it is.
	 */
	public static void installExperienceRelay(Object neoBus) {
		((IEventBus) neoBus).addListener(EventPriority.LOWEST, false, BlockDropsEvent.class, drops -> {
			try {
				relayExperience(drops);
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/EventMux] the experience relay failed — a Fabric mod's hook in Block.tryDropExperience did not run for this "
						+ "break, NeoForge's experience stands");
			}
		});
	}

	/**
	 * Runs the relay of vanilla's {@code tryDropExperience} for this break's block, when its native {@code spawnAfterBreak} asked
	 * that method: handed the experience the event holds now, and the event drops what the method would have popped. No relay
	 * (nothing woven into the method, or the block's native game never asked it) leaves the event as it is.
	 */
	static void relayExperience(BlockDropsEvent drops) throws Throwable {
		if (!(drops.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
		Object block = drops.getState().getBlock();
		net.forbric.kernel.mixin.UncalledValueRelay.Relay relay = net.forbric.kernel.mixin.UncalledValueRelay.relayFor(block.getClass(),
				net.forbric.kernel.mixin.UncalledValueRelay.EXPERIENCE);
		if (relay == null) return;
		int popped = net.forbric.kernel.mixin.UncalledValueRelay.run(relay, drops.getDroppedExperience(), block, level, drops.getPos(), drops.getTool(), null);
		drops.setDroppedExperience(popped);
	}
}
