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
	LIVING_DEATH(Pass.GAME_BUS, "LivingDeathEvent",
			"a MinecraftForge mod that prevents or reacts to a death — graves, keep-inventory, totems — does "
					+ "nothing at all, and because the event is cancellable its listener runs, decides, and is "
					+ "ignored, which looks like it works"),
	LIVING_ATTACK(Pass.GAME_BUS, "LivingAttackEvent",
			"a MinecraftForge mod that makes an entity immune to an attack or reacts to one — ghost forms, "
					+ "shields, on-hit perks — does nothing"),
	SHIELD_BLOCK(Pass.GAME_BUS, "ShieldBlockEvent",
			"a MinecraftForge mod that changes what a shield blocks, or whether it wears, has no effect"),
	LIVING_KNOCKBACK(Pass.GAME_BUS, "LivingKnockBackEvent",
			"a MinecraftForge mod that cancels or changes knockback has no effect"),
	LIVING_FALL(Pass.GAME_BUS, "LivingFallEvent",
			"a MinecraftForge mod that cancels or changes fall damage does it for horses and llamas only"),
	PLAYER_CLONE(Pass.GAME_BUS, "PlayerEvent.Clone",
			"a MinecraftForge mod never copies its data to the respawned player, so what it kept for a dead player "
					+ "— backpacks, capabilities, effects — is lost"),
	EXPERIENCE_DROP(Pass.GAME_BUS, "LivingExperienceDropEvent",
			"a MinecraftForge mod cannot cancel or change dropped experience, so a grave mod that keeps it returns "
					+ "it twice"),
	EXPLOSION_DETONATE(Pass.GAME_BUS, "ExplosionEvent.Detonate",
			"a MinecraftForge mod cannot spare blocks or entities from an explosion, so protected blocks — graves — "
					+ "are blown up"),
	BREWING_RECIPES(Pass.GAME_BUS, "BrewingRecipeRegisterEvent",
			"a MinecraftForge mod's brewing recipes are never registered, so its potions cannot be brewed"),
	CHUNK_LOAD(Pass.GAME_BUS, "ChunkEvent.Load",
			"a MinecraftForge mod never sees a chunk load, so a map mod's cache is never fed (JourneyMap draws nothing new)"),
	CHUNK_UNLOAD(Pass.GAME_BUS, "ChunkEvent.Unload",
			"a MinecraftForge mod never sees a chunk unload, so what it keeps per chunk is never released"),
	ENTITY_LEAVE_LEVEL(Pass.GAME_BUS, "EntityLeaveLevelEvent",
			"a MinecraftForge mod never learns an entity left a level, so per-entity state leaks across dimension changes and logouts"),
	ENTERING_SECTION(Pass.GAME_BUS, "EntityEvent.EnteringSection",
			"a MinecraftForge mod never sees an entity move between sections, so region triggers never fire"),
	PLAYER_WAKE_UP(Pass.GAME_BUS, "PlayerWakeUpEvent",
			"a MinecraftForge mod never learns a player woke up"),
	TAGS_UPDATED(Pass.GAME_BUS, "TagsUpdatedEvent",
			"a MinecraftForge mod's caches built from tags are never rebuilt after a reload"),
	EFFECT_ADDED(Pass.GAME_BUS, "MobEffectEvent.Added",
			"a MinecraftForge mod never learns an effect was added"),
	EFFECT_EXPIRED(Pass.GAME_BUS, "MobEffectEvent.Expired",
			"a MinecraftForge mod never learns an effect ran out"),
	EFFECT_APPLICABLE(Pass.GAME_BUS, "MobEffectEvent.Applicable",
			"a MinecraftForge mod cannot make an entity immune to an effect, or force one on"),
	CONVERSION_PRE(Pass.GAME_BUS, "LivingConversionEvent.Pre",
			"a MinecraftForge mod cannot stop a mob converting (lightning, freezing, zombification)"),
	CONVERSION_POST(Pass.GAME_BUS, "LivingConversionEvent.Post",
			"a MinecraftForge mod never learns a mob converted, so it cannot carry its data over"),
	PROJECTILE_IMPACT(Pass.GAME_BUS, "ProjectileImpactEvent",
			"a MinecraftForge mod cannot stop or redirect a projectile's hit"),
	FARMLAND_TRAMPLE(Pass.GAME_BUS, "BlockEvent.FarmlandTrampleEvent",
			"a MinecraftForge mod cannot keep farmland from being trampled"),
	PERMISSIONS_CHANGED(Pass.GAME_BUS, "PermissionsChangedEvent",
			"a MinecraftForge mod never learns a player was opped or deopped, so op-only features stay as they were until relog"),
	COMMAND(Pass.GAME_BUS, "CommandEvent",
			"a MinecraftForge mod cannot see, change or refuse a command"),
	ENTITY_INTERACT_SPECIFIC(Pass.GAME_BUS, "PlayerInteractEvent.EntityInteractSpecific",
			"a MinecraftForge mod's item used on an entity does nothing"),
	LIVING_HEAL(Pass.GAME_BUS, "LivingHealEvent",
			"a MinecraftForge mod cannot stop or change healing"),
	LIVING_VISIBILITY(Pass.GAME_BUS, "LivingEvent.LivingVisibilityEvent",
			"a MinecraftForge mod's stealth does nothing: mobs see the player at full range"),
	CRITICAL_HIT(Pass.GAME_BUS, "CriticalHitEvent",
			"a MinecraftForge mod cannot force, prevent or scale a critical hit"),
	ANVIL_UPDATE(Pass.GAME_BUS, "AnvilUpdateEvent",
			"a MinecraftForge mod's anvil recipes produce nothing"),
	ANVIL_REPAIR(Pass.GAME_BUS, "AnvilRepairEvent",
			"a MinecraftForge mod never learns an anvil result was taken"),
	TOOL_MODIFICATION(Pass.GAME_BUS, "BlockEvent.BlockToolModificationEvent",
			"a MinecraftForge mod's tillable, strippable or flattenable blocks cannot be changed with a tool"),
	LIVING_DROPS(Pass.GAME_BUS, "LivingDropsEvent",
			"a MinecraftForge mod that adds, removes or suppresses mob drops has no effect"),
	ENTITY_JOIN_LEVEL(Pass.GAME_BUS, "EntityJoinLevelEvent",
			"a MinecraftForge mod that refuses an entity entry to the world — mob filters, anti-farm rules, spawn "
					+ "control — is overruled silently"),
	BLOCK_BREAK(Pass.GAME_BUS, "BlockEvent.BreakEvent",
			"a MinecraftForge claim or protection mod does not protect and a block-logging mod records nothing — "
					+ "the block simply breaks, with the mod loaded and its listener registered"),
	RIGHT_CLICK_BLOCK(Pass.GAME_BUS, "PlayerInteractEvent.RightClickBlock",
			"a MinecraftForge mod cannot see or refuse a right-click on a block — protection rules, locks and "
					+ "custom block interactions do nothing"),
	LEFT_CLICK_BLOCK(Pass.GAME_BUS, "PlayerInteractEvent.LeftClickBlock",
			"a MinecraftForge mod cannot see or refuse a left-click on a block — the first half of every "
					+ "protection rule about breaking one"),
	RIGHT_CLICK_ITEM(Pass.GAME_BUS, "PlayerInteractEvent.RightClickItem",
			"a MinecraftForge mod cannot see or refuse an item being used in hand"),
	LEVEL_LOAD(Pass.GAME_BUS, "LevelEvent.Load",
			"a MinecraftForge mod never learns a level came up, so per-world state it builds on load — caches "
					+ "keyed by dimension, per-level managers — is never built"),
	LEVEL_UNLOAD(Pass.GAME_BUS, "LevelEvent.Unload",
			"a MinecraftForge mod never learns a level went away, so whatever it holds for that world — Xaero's "
					+ "map processor, per-dimension caches, background workers — is never told to stop and keeps "
					+ "running against a world that is gone"),
	LEVEL_SAVE(Pass.GAME_BUS, "LevelEvent.Save",
			"a MinecraftForge mod that persists its own per-world data alongside the level's save never gets the "
					+ "chance, so its state is silently a save behind or lost"),
	ITEM_USE_FINISH(Pass.GAME_BUS, "LivingEntityUseItemEvent.Finish",
			"a MinecraftForge mod cannot change what an item becomes when it is finished, so food that should "
					+ "leave a bowl or a bottle behind leaves nothing"),
	PORTAL_SPAWN(Pass.GAME_BUS, "BlockEvent.PortalSpawnEvent",
			"a MinecraftForge mod cannot prevent a nether portal lighting, so dimension- and claim-restricting "
					+ "mods do not restrict it"),
	START_TRACKING(Pass.GAME_BUS, "PlayerEvent.StartTracking",
			"a MinecraftForge mod is never told a player began tracking an entity, so anything it builds per "
					+ "viewer — nameplate state, per-player entity data, sync on first sight — is never built"),
	STOP_TRACKING(Pass.GAME_BUS, "PlayerEvent.StopTracking",
			"a MinecraftForge mod is never told a player stopped tracking an entity, so whatever it built per "
					+ "viewer is never torn down — a leak that grows for as long as the session lasts"),
	LOOT_TABLE_LOAD(Pass.GAME_BUS, "LootTableLoadEvent",
			"loot tables a MinecraftForge mod adds to or replaces on load are left exactly as loaded"),
	ITEM_TOOLTIP(Pass.ON_DEMAND, "ItemTooltipEvent",
			"item tooltips cannot be extended by NeoForge mods — the merged getTooltipLines asks only "
					+ "MinecraftForge's onItemTooltip, so every NeoForge mod that adds a line to an item's "
					+ "tooltip adds it to nothing"),
	ENTITY_PLACE_BLOCK(Pass.GAME_BUS, "BlockEvent.EntityPlaceEvent",
			"a MinecraftForge mod cannot see or refuse a block being placed — the other half of every protection "
					+ "rule, and of every block-logging mod's record"),
	CLIENT_TICK_PRE(Pass.CLIENT_GAME_BUS, "ClientTickEvent.Pre",
			"a MinecraftForge mod polls its key bindings from the client tick (consumeClick drains a counter and "
					+ "has to be drained every tick), so its keys bind, appear in the Controls screen and do "
					+ "nothing at all when pressed"),
	CLIENT_TICK_POST(Pass.CLIENT_GAME_BUS, "ClientTickEvent.Post",
			"as CLIENT_TICK_PRE, for the post-tick half"),
	RENDER_FRAME_PRE(Pass.CLIENT_GAME_BUS, "TickEvent.RenderTickEvent.Pre",
			"the render tick is the only pump a MinecraftForge map mod's region builder runs on, so its map is "
					+ "never built and its screen opens onto nothing, while the vertex buffer the same listener "
					+ "drains grows without bound instead"),
	RENDER_FRAME_POST(Pass.CLIENT_GAME_BUS, "TickEvent.RenderTickEvent.Post",
			"as RENDER_FRAME_PRE, for the post-frame half — and the half Xaero's world map alone listens on"),
	SCREEN_MOUSE_PRESSED_PRE(Pass.CLIENT_GAME_BUS, "ScreenEvent.MouseButtonPressed.Pre",
			"a MinecraftForge mod cannot see or refuse a click inside a screen — an inventory-tweak mod's "
					+ "click handling is absent, and because the event is cancellable the mod decides and is "
					+ "ignored, so the screen handles the click as if the mod were not installed"),
	SCREEN_MOUSE_RELEASED_PRE(Pass.CLIENT_GAME_BUS, "ScreenEvent.MouseButtonReleased.Pre",
			"a MinecraftForge mod never sees a mouse button released over a screen, so a drag it began is never "
					+ "ended and the stack it was moving is left mid-move"),
	SCREEN_MOUSE_DRAG_PRE(Pass.CLIENT_GAME_BUS, "ScreenEvent.MouseDragged.Pre",
			"a MinecraftForge mod cannot see a drag across a screen's slots, so dragging a held stack over a row "
					+ "of slots to distribute it does nothing"),
	SCREEN_MOUSE_SCROLL_POST(Pass.CLIENT_GAME_BUS, "ScreenEvent.MouseScrolled.Post",
			"a MinecraftForge mod never sees the scroll wheel inside a screen, so moving items between a chest "
					+ "and the inventory with the wheel does nothing at all — the mod is loaded, its listener is "
					+ "registered, and the wheel only scrolls the screen"),
	CLIENT_LOGGING_IN(Pass.CLIENT_GAME_BUS, "ClientPlayerNetworkEvent.LoggingIn",
			"a MinecraftForge mod never learns the client joined a world or server, so its handshake, map session "
					+ "or server-synced config never starts"),
	CLIENT_LOGGING_OUT(Pass.CLIENT_GAME_BUS, "ClientPlayerNetworkEvent.LoggingOut",
			"a MinecraftForge mod never learns the client left, so per-server state and synced config linger into "
					+ "the next world"),
	CLIENT_PLAYER_CLONE(Pass.CLIENT_GAME_BUS, "ClientPlayerNetworkEvent.Clone",
			"a MinecraftForge mod keeps state on the client player object that respawning replaced"),
	CLIENT_COMMANDS(Pass.CLIENT_GAME_BUS, "RegisterClientCommandsEvent",
			"a MinecraftForge mod's client commands do not exist — the player types one and the server answers "
					+ "\"Unknown command\""),
	CLIENT_CHAT_RECEIVED(Pass.CLIENT_GAME_BUS, "ClientChatReceivedEvent",
			"a MinecraftForge mod never sees chat arrive — waypoint links and chat-driven features do nothing — and cannot change or hide it"),
	CLIENT_CHAT_SEND(Pass.CLIENT_GAME_BUS, "ClientChatEvent",
			"a MinecraftForge mod cannot see, change or keep back what the player sends, so a chat link it should handle goes to the server as text"),
	KEY_INPUT(Pass.CLIENT_GAME_BUS, "InputEvent.Key",
			"a MinecraftForge mod's hotkeys do nothing"),
	MOUSE_BUTTON_PRE(Pass.CLIENT_GAME_BUS, "InputEvent.MouseButton.Pre",
			"a MinecraftForge mod never sees a mouse button outside a screen"),
	INTERACTION_KEY(Pass.CLIENT_GAME_BUS, "InputEvent.InteractionKeyMappingTriggered",
			"a MinecraftForge mod cannot intercept attacking, using or picking a block"),
	RENDER_FOG(Pass.CLIENT_GAME_BUS, "ViewportEvent.RenderFog",
			"a MinecraftForge mod's fog distance changes do nothing"),
	FOG_COLOR(Pass.CLIENT_GAME_BUS, "ViewportEvent.ComputeFogColor",
			"a MinecraftForge mod's fog colour changes do nothing"),
	FOV_MODIFIER(Pass.CLIENT_GAME_BUS, "ComputeFovModifierEvent",
			"a MinecraftForge mod's field-of-view changes do nothing"),
	BLOCK_OVERLAY(Pass.CLIENT_GAME_BUS, "RenderBlockScreenEffectEvent",
			"a MinecraftForge mod cannot hide the in-block, water or fire overlay"),
	BOSS_EVENT_PROGRESS(Pass.CLIENT_GAME_BUS, "CustomizeGuiOverlayEvent.BossEventProgress",
			"a MinecraftForge mod cannot move or hide a boss bar, so HUD elements placed below them overlap"),
	SCREEN_RENDER_PRE(Pass.CLIENT_GAME_BUS, "ScreenEvent.Render.Pre",
			"a MinecraftForge mod cannot draw under an open screen or stop it drawing"),
	SCREEN_RENDER_POST(Pass.CLIENT_GAME_BUS, "ScreenEvent.Render.Post",
			"a MinecraftForge mod's overlays on an open screen never draw"),
	TEXTURE_STITCHED(Pass.CLIENT_MOD_BUS, "TextureStitchEvent.Post",
			"a MinecraftForge mod never sees an atlas finish stitching, so what it builds from textures goes stale after a reload"),
	MODELS_BAKED(Pass.CLIENT_MOD_BUS, "ModelEvent.BakingCompleted",
			"a MinecraftForge mod never sees models finish baking, so what it builds from them goes stale after a reload"),
	CLIENT_RELOAD_LISTENERS(Pass.CLIENT_MOD_BUS, "RegisterClientReloadListenersEvent",
			"a MinecraftForge mod's client reload listeners are registered on a bus nobody posts to — GeckoLib's "
					+ "whole client model and animation cache hangs off exactly this"),
	CLIENT_INIT_HOOKS(Pass.CLIENT_INIT, "ForgeHooksClient.initClientHooks (11 registration events)",
			"a MinecraftForge mod's key bindings are absent from Controls, its entity and block-entity renderers and "
					+ "model layers are never registered so its entities are invisible or crash the renderer, and its "
					+ "tooltip components, sprite loaders, geometry loaders, named render types, colour resolvers, "
					+ "spectator shaders, item decorations and preset editors are never registered"),
	PARTICLE_PROVIDERS(Pass.CLIENT_INIT, "RegisterParticleProvidersEvent",
			"a MinecraftForge mod's custom particles never render, and nothing is logged about it"),
	BLOCK_TINT_SOURCES(Pass.CLIENT_INIT, "RegisterColorHandlersEvent.Block",
			"a MinecraftForge mod's biome- or state-tinted blocks render untinted (grass, leaves, water and every "
					+ "modded block that borrows their colouring)"),
	CREATIVE_TAB_CONTENTS(Pass.REGISTRATION, "BuildCreativeModeTabContentsEvent",
			"items a MinecraftForge mod adds to vanilla or other mods' creative tabs are missing — only its own tab "
					+ "still fills, so the mod looks installed and its content is not there"),
	SPAWN_PLACEMENTS(Pass.REGISTRATION, "SpawnPlacementRegisterEvent",
			"a MinecraftForge mod's mobs never spawn naturally, and its changes to vanilla spawn rules are ignored"),
	GUI_OVERLAY_LAYERS(Pass.CLIENT_HUD, "AddGuiOverlayLayersEvent",
			"HUD overlay layers a MinecraftForge mod adds never draw — the merged base carries no reference to "
					+ "ForgeLayeredDraw at all, so nothing builds its tree and nothing renders it");

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
		CLIENT_MOD_BUS,
		/**
		 * Landed by a class transformer inside the merged base's own client initialisation — the two
		 * {@code Minecraft.<init>} hook calls and the block-colour table — rather than on a bus. Client only.
		 * Verified from the client setup hook, which the merged constructor reaches only after every one of those
		 * sites. The dead-event audit runs before the client class is even defined, so the pass is
		 * {@link #lateInstalled()}: the audit must not count it as missing, and {@link EventBridges#verify} is what
		 * names a repair that stood down.
		 */
		CLIENT_INIT(true),
		/**
		 * Landed by a class transformer at the game's own registration hooks (creative-tab contents, spawn
		 * placements). Both sides. Verified right after the kernel's registration window has driven both, and late
		 * for the audit for the same reason as {@link #CLIENT_INIT}.
		 */
		REGISTRATION(true),
		/**
		 * Landed at the end of NeoForge's own {@code GuiLayerManager.initModdedLayers}, the one moment where every
		 * mod is loaded and the HUD has not yet drawn a frame. Client only, and later than every other pass — the
		 * merged {@code Minecraft.<init>} reaches it after client setup — so it is {@link #lateInstalled()} and
		 * verified from its own install point.
		 */
		CLIENT_HUD(true),
		/**
		 * Landed by a class transformer at a call site the game reaches only when the feature is USED — an item's
		 * tooltip is built when one is hovered, and on a dedicated server never. There is no moment at which such a
		 * bridge can be verified present, because "it has not fired yet" and "it is missing" look identical until a
		 * player hovers an item. {@link #lateInstalled()} for that reason, and what proves the seam is really in the
		 * bytecode is the transformer census rather than a verify pass.
		 */
		ON_DEMAND(true);

		private final boolean lateInstalled;

		Pass() {
			this(false);
		}

		Pass(boolean lateInstalled) {
			this.lateInstalled = lateInstalled;
		}

		/**
		 * Whether this pass lands after the dead-event audit has already run. A late bridge's absence is reported
		 * by {@link EventBridges#verify} at the pass's own moment, with its cost, so the audit leaves it alone
		 * instead of naming every mod on it as waiting.
		 */
		public boolean lateInstalled() {
			return lateInstalled;
		}
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
