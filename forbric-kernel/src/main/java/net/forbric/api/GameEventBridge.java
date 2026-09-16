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

package net.forbric.api;

/**
 * Every game event the byte-merge left firing on only ONE Forge family's hook, and therefore has to be re-emitted
 * to the other.
 *
 * <p>On the merged base the two Forge families' hooks compete for the same call sites and one of them wins. The
 * loser's site is dead code, so a mod of that family has a listener on a bus nobody posts to. That cannot be fixed
 * by making the two families agree on an event type — a mod compiled against
 * {@code net.minecraftforge.event.TickEvent} needs an instance of exactly that class — so the re-emission is
 * intrinsic, not a workaround to be designed away.
 *
 * <p><b>What this type is for, then, is the inventory.</b> The bridges used to exist only as a sequence of calls
 * inside one method: five of them summed into a local {@code n}, inside a single {@code try}, so the FIRST setup
 * failure skipped every bridge after it and the only evidence was one warning line and a smaller number in a log
 * message no gate asserted. That is the exact failure shape this project keeps paying for — not a crash, an
 * absence. Declaring the set here lets the installer check what it achieved against what was required, and say so
 * loudly when they differ.
 *
 * <p>{@link #cost()} is the point of the enum rather than a bare count: when one is missing, the log should say
 * what the player is about to experience, not just that a number was short.
 */
public enum GameEventBridge {
	SERVER_TICK_PRE(Pass.GAME_BUS, "ServerTickEvent.Pre",
			"Forge-family mods stop receiving the server tick, so anything driven per-tick simply never runs"),
	SERVER_TICK_POST(Pass.GAME_BUS, "ServerTickEvent.Post",
			"as SERVER_TICK_PRE, for the post-tick half"),
	SERVER_ABOUT_TO_START(Pass.GAME_BUS, "ServerAboutToStartEvent",
			"MinecraftForge mods' per-world SERVER configs stay at their defaults, and their "
					+ "ServerAboutToStartEvent listeners never fire"),
	SERVER_STARTED(Pass.GAME_BUS, "ServerStartedEvent",
			"MinecraftForge's login gate never opens, so every join is rejected with \"Server is still starting\" "
					+ "— including the local player's on an integrated server, which fails singleplayer world-join"),
	SERVER_STOPPING(Pass.GAME_BUS, "ServerStoppingEvent",
			"MinecraftForge mods never learn the server is going away, so their shutdown work is skipped"),
	SERVER_STARTING(Pass.GAME_BUS, "ServerStartingEvent",
			"MinecraftForge's PermissionAPI is never initialised, so every permission question a Forge mod asks "
					+ "NPEs inside Forge's own API; a dedicated server also loads no server-side language file, and "
					+ "ServerStartingEvent listeners — where mods start schedulers and world-bound managers — never "
					+ "run"),
	SERVER_STOPPED(Pass.GAME_BUS, "ServerStoppedEvent",
			"MinecraftForge's per-world SERVER configs are never unloaded, so a second world opened in the same "
					+ "session reads the FIRST world's values and each world leaks another file watcher; "
					+ "ServerStoppedEvent listeners never clean up either"),
	LEVEL_TICK_PRE(Pass.GAME_BUS, "LevelTickEvent.Pre",
			"MinecraftForge mods stop receiving the level tick, so per-world work — weather and time managers, "
					+ "world-bound schedulers, chunk bookkeeping — never runs"),
	LEVEL_TICK_POST(Pass.GAME_BUS, "LevelTickEvent.Post",
			"as LEVEL_TICK_PRE, for the post-tick half"),
	PLAYER_TICK_PRE(Pass.GAME_BUS, "PlayerTickEvent.Pre",
			"MinecraftForge mods stop receiving the per-player tick, so their timers do not advance and whatever "
					+ "they attached to the player is never ticked"),
	PLAYER_TICK_POST(Pass.GAME_BUS, "PlayerTickEvent.Post",
			"as PLAYER_TICK_PRE, for the post-tick half"),
	REGISTER_COMMANDS(Pass.GAME_BUS, "RegisterCommandsEvent",
			"a MinecraftForge mod's commands DO NOT EXIST — the player types one and gets \"Unknown command\", "
					+ "while the mod itself loaded cleanly and reports no problem"),
	PLAYER_LOGGED_IN(Pass.GAME_BUS, "PlayerEvent.PlayerLoggedInEvent",
			"MinecraftForge mods never learn a player joined, so join messages, login rewards and per-player "
					+ "state restored on connect do not happen"),
	PLAYER_LOGGED_OUT(Pass.GAME_BUS, "PlayerEvent.PlayerLoggedOutEvent",
			"MinecraftForge mods never learn a player left, so per-player cleanup and saves on disconnect are "
					+ "skipped"),
	PLAYER_RESPAWN(Pass.GAME_BUS, "PlayerEvent.PlayerRespawnEvent",
			"MinecraftForge mods never see a respawn, so whatever they restore or grant on death is lost"),
	PLAYER_CHANGED_DIMENSION(Pass.GAME_BUS, "PlayerEvent.PlayerChangedDimensionEvent",
			"MinecraftForge mods never see a dimension change, so per-dimension state is not swapped when a "
					+ "player enters the Nether or the End"),
	CLIENT_TICK_PRE(Pass.CLIENT_GAME_BUS, "ClientTickEvent.Pre",
			"a MinecraftForge mod polls its key bindings from the client tick (consumeClick drains a counter and "
					+ "has to be drained every tick), so its keys bind, appear in the Controls screen and do "
					+ "nothing at all when pressed"),
	CLIENT_TICK_POST(Pass.CLIENT_GAME_BUS, "ClientTickEvent.Post",
			"as CLIENT_TICK_PRE, for the post-tick half"),
	CLIENT_RELOAD_LISTENERS(Pass.CLIENT_MOD_BUS, "RegisterClientReloadListenersEvent",
			"a MinecraftForge mod's client reload listeners are registered on a bus nobody posts to — GeckoLib's "
					+ "whole client model and animation cache hangs off exactly this");

	/** Which install pass owns a bridge. They run at different times and only one of them is client-only. */
	public enum Pass {
		/** Installed on the NeoForge game event bus once the buses exist. Both sides. */
		GAME_BUS,
		/**
		 * Installed on the NeoForge game event bus, but only on the client — the event types live in NeoForge's
		 * client package and a dedicated server must never be made to resolve them. Verified as its own pass so a
		 * server does not report them missing on every boot.
		 */
		CLIENT_GAME_BUS,
		/** Installed on the baseline mod bus during client mod loading. Client only. */
		CLIENT_MOD_BUS
	}

	private final Pass pass;
	private final String event;
	private final String cost;

	GameEventBridge(Pass pass, String event, String cost) {
		this.pass = pass;
		this.event = event;
		this.cost = cost;
	}

	/** Which install pass is responsible for this bridge. */
	public Pass pass() {
		return pass;
	}

	/** The NeoForge event being observed, for logs. */
	public String event() {
		return event;
	}

	/** What a player loses when this bridge is not installed. Written to be readable in a warning. */
	public String cost() {
		return cost;
	}
}
