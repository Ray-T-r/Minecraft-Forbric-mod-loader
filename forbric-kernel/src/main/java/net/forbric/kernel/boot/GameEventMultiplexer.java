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

import java.lang.reflect.Method;

import net.forbric.api.EventBridges;
import net.forbric.api.GameEventBridge;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/**
 * Re-emits game events the merged base's byte-merge left firing on only ONE ecosystem's hook, so the other
 * ecosystem's mods still receive them — with a single owner per family (no double-fire).
 *
 * <p>On the merged base NeoForge won the tick hook: {@code MinecraftServer.tickServer} calls
 * {@code EventHooks.fireServerTickPost} (NeoForge), and Forge's {@code ForgeEventFactory.onPostServerTick} site is
 * dead. So a traditional-Forge mod's {@code ServerTickEvent} listener never fires natively. This multiplexer
 * subscribes to the NeoForge event (the surviving hook) at LOWEST priority and forwards it to the Forge side via
 * {@code ForgeEventFactory}, giving a 1:1 re-emission. This is the kernel-native successor to the old weld's
 * reflective Neo→Forge event bridge — a single forwarding pass, no genuine loader lifecycle.
 *
 * <p>The forward depends on the Forge-family {@code @EventBusSubscriber} listeners having been registered with a
 * full-power, game-side {@code MethodHandles.Lookup} (see {@link KernelGameLookup}); registering them with a
 * boot-side lookup makes Forge's EventBus resolve the event type through the app loader and this forward then
 * throws {@code NoClassDefFoundError} on the Forge event class.
 */
public final class GameEventMultiplexer {
	/** Ticks to observe before logging the 1:1 bridge proof once (mirrors the old canary's "20 server ticks"). */
	private static final int TICK_PROOF_THRESHOLD = 20;

	private GameEventMultiplexer() {
	}

	/** Installs the Neo→Forge tick bridges. No-op if either ecosystem is absent. Call after buses exist. */
	public static void install(ClassLoader cl) {
		install(cl, false);
	}

	/**
	 * @param client whether the CLIENT game-bus bridges may go on too. They name types in NeoForge's client event
	 *               package, which a dedicated server must never be made to resolve — which is also why they are
	 *               their own {@link GameEventBridge.Pass}, verified separately, rather than reported missing on
	 *               every server boot.
	 */
	public static void install(ClassLoader cl, boolean client) {
		if (!EventBridges.enabled()) {
			ForbricLog.info("[Forbric/EventMux] -D%s=off — installing no bridges; each Forge family will receive "
					+ "only the events whose hook won the byte merge", EventBridges.SWITCH_NAME);
			return;
		}
		try {
			Object neoBus = Class.forName("net.neoforged.neoforge.common.NeoForge", false, cl)
					.getField("EVENT_BUS").get(null);
			// Resolved for their ABSENCE, not their presence. Every bridge below is game-side now and names these
			// families as TYPES, so a single-family instance would fail to LINK a game-side class — which arrives
			// as a LinkageError per bridge and reads as five warnings about a broken kernel. Probing one class
			// from each family here turns that back into what it is: a ClassNotFoundException at THIS line, and
			// one debug line saying there is only one family to bridge between.
			Class.forName("net.minecraftforge.event.ForgeEventFactory", false, cl);
			Class.forName("net.neoforged.neoforge.event.tick.ServerTickEvent", false, cl);

			// Each bridge is installed INDEPENDENTLY. They used to be five `n +=` calls in this one try, so the
			// first setup failure skipped every bridge after it — and the cost of the ones skipped (see
			// GameEventBridge.cost()) is silent: a listener on a bus nobody posts to. One failing bridge must cost
			// only itself.
			install(cl, neoBus, GameEventBridge.SERVER_TICK_PRE, bus -> tickBridge(cl, "installPre").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.SERVER_TICK_POST, bus -> tickBridge(cl, "installPost").invoke(null, bus));
			// The other three ticks the merge left NeoForge-only. Only the SERVER tick was ever bridged, which is
			// what made this so hard to see: ticking looked healthy in every log and every gate while a Forge mod's
			// per-level and per-player work never ran at all — and, on the client, while its key bindings did
			// nothing when pressed, because consumeClick is drained from the client tick.
			install(cl, neoBus, GameEventBridge.LEVEL_TICK_PRE, bus -> tickBridge(cl, "installLevelPre").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.LEVEL_TICK_POST, bus -> tickBridge(cl, "installLevelPost").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.PLAYER_TICK_PRE, bus -> tickBridge(cl, "installPlayerPre").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.PLAYER_TICK_POST,
					bus -> tickBridge(cl, "installPlayerPost").invoke(null, bus));
			// Commands and the player lifecycle. Measured on the merged base: Commands is 7 NeoForge references to
			// 0 MinecraftForge, PlayerList is 13 to 0. So a Forge mod's commands do not exist — the player types
			// one and gets "Unknown command" while the mod loaded cleanly — and nothing it does on join, leave,
			// respawn or a dimension change ever runs.
			install(cl, neoBus, GameEventBridge.REGISTER_COMMANDS,
					bus -> playerBridge(cl, "installCommands").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.PLAYER_LOGGED_IN,
					bus -> playerBridge(cl, "installLoggedIn").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.PLAYER_LOGGED_OUT,
					bus -> playerBridge(cl, "installLoggedOut").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.PLAYER_RESPAWN,
					bus -> playerBridge(cl, "installRespawn").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.PLAYER_CHANGED_DIMENSION,
					bus -> playerBridge(cl, "installChangedDimension").invoke(null, bus));
			// The CANCELLABLE ones, which are a different kind of forward: a MinecraftForge mod cancelling one is
			// the whole point of listening, so the bridge carries its veto back onto the NeoForge event rather
			// than merely observing. See KernelGameEntityEvents for why that is sound.
			install(cl, neoBus, GameEventBridge.LIVING_DEATH,
					bus -> entityBridge(cl, "installLivingDeath").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.LIVING_DROPS,
					bus -> entityBridge(cl, "installLivingDrops").invoke(null, bus));
			// The damage family. Nothing on the merged base calls MinecraftForge's attack, shield or knockback
			// hooks, and its fall hook only from horses and llamas; NeoForge posts its own event at each of those
			// positions and reads it back, so these forward at LOWEST with the answer carried back. Hurt and Damage
			// have no NeoForge event to forward from: ForgeDamageSeamsInjector posts them in the pipeline itself.
			install(cl, neoBus, GameEventBridge.LIVING_ATTACK,
					bus -> damageBridge(cl, "installLivingAttack").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.SHIELD_BLOCK,
					bus -> damageBridge(cl, "installShieldBlock").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.LIVING_KNOCKBACK,
					bus -> damageBridge(cl, "installKnockBack").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.LIVING_FALL,
					bus -> damageBridge(cl, "installFall").invoke(null, bus));
			// What a player keeps: a respawn copy (backpacks kept by a mod), the experience a death drops, the blocks
			// an explosion spares, and a mod's brewing recipes. Each merged site posts NeoForge's event and reads it
			// back, and none of MinecraftForge's hooks is called.
			install(cl, neoBus, GameEventBridge.PLAYER_CLONE,
					bus -> serverBridge(cl, "installPlayerClone").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.EXPERIENCE_DROP,
					bus -> serverBridge(cl, "installExperienceDrop").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.EXPLOSION_DETONATE,
					bus -> serverBridge(cl, "installExplosionDetonate").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.BREWING_RECIPES,
					bus -> serverBridge(cl, "installBrewingRecipes").invoke(null, bus));
			// The rest of the world and entity events the merged game posts only NeoForge's version of: chunks,
			// section moves, leaving a level, waking, tag reloads, effects, conversions, impacts, trampling,
			// permissions, commands, entity interaction, healing, visibility, critical hits, anvils and tools.
			install(cl, neoBus, GameEventBridge.CHUNK_LOAD,
					bus -> worldBridge(cl, "installChunkLoad").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.CHUNK_UNLOAD,
					bus -> worldBridge(cl, "installChunkUnload").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.ENTITY_LEAVE_LEVEL,
					bus -> worldBridge(cl, "installEntityLeaveLevel").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.ENTERING_SECTION,
					bus -> worldBridge(cl, "installEnteringSection").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.PLAYER_WAKE_UP,
					bus -> worldBridge(cl, "installPlayerWakeUp").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.TAGS_UPDATED,
					bus -> worldBridge(cl, "installTagsUpdated").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.EFFECT_ADDED,
					bus -> worldBridge(cl, "installEffectAdded").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.EFFECT_EXPIRED,
					bus -> worldBridge(cl, "installEffectExpired").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.EFFECT_APPLICABLE,
					bus -> worldBridge(cl, "installEffectApplicable").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.CONVERSION_PRE,
					bus -> worldBridge(cl, "installConversionPre").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.CONVERSION_POST,
					bus -> worldBridge(cl, "installConversionPost").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.PROJECTILE_IMPACT,
					bus -> worldBridge(cl, "installProjectileImpact").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.FARMLAND_TRAMPLE,
					bus -> worldBridge(cl, "installFarmlandTrample").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.PERMISSIONS_CHANGED,
					bus -> worldBridge(cl, "installPermissionsChanged").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.COMMAND,
					bus -> worldBridge(cl, "installCommand").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.ENTITY_INTERACT_SPECIFIC,
					bus -> worldBridge(cl, "installEntityInteractSpecific").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.LIVING_HEAL,
					bus -> worldBridge(cl, "installHeal").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.LIVING_VISIBILITY,
					bus -> worldBridge(cl, "installVisibility").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.CRITICAL_HIT,
					bus -> worldBridge(cl, "installCriticalHit").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.ANVIL_UPDATE,
					bus -> worldBridge(cl, "installAnvilUpdate").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.ANVIL_REPAIR,
					bus -> worldBridge(cl, "installAnvilRepair").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.TOOL_MODIFICATION,
					bus -> worldBridge(cl, "installToolModification").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.ENTITY_JOIN_LEVEL,
					bus -> entityBridge(cl, "installEntityJoinLevel").invoke(null, bus));
			// The level lifecycle. Every producer in the merged base is NeoForge's (Minecraft x3 and
			// MinecraftServer post Unload, ClientLevel and MinecraftServer post Load, ServerLevel posts Save),
			// and MinecraftForge's three hooks have no call site at all — measured by disassembly. Observers,
			// not vetoes: none of the three is cancellable.
			install(cl, neoBus, GameEventBridge.LEVEL_LOAD,
					bus -> levelBridge(cl, "installLevelLoad").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.LEVEL_UNLOAD,
					bus -> levelBridge(cl, "installLevelUnload").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.LEVEL_SAVE,
					bus -> levelBridge(cl, "installLevelSave").invoke(null, bus));
			// Entity tracking, as a PAIR. Chosen by hook-worklist.sh rather than from a log: collective
			// subscribes to StartTracking and onStartEntityTracking has no call site on the merged base.
			// Stop is bridged with it because a mod told only that tracking began accumulates per-viewer state
			// for entities that are gone, which is a leak instead of a silence.
			// The two whose MinecraftForge side RETURNS something. Forwarding these and dropping the answer
			// would leave a mod's listener running and its change ignored, which is worse than not bridging.
			install(cl, neoBus, GameEventBridge.ITEM_USE_FINISH,
					bus -> resultBridge(cl, "installItemUseFinish").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.PORTAL_SPAWN,
					bus -> resultBridge(cl, "installPortalSpawn").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.START_TRACKING,
					bus -> trackingBridge(cl, "installStartTracking").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.STOP_TRACKING,
					bus -> trackingBridge(cl, "installStopTracking").invoke(null, bus));
			// Breaking a block. ServerPlayerGameMode posts only NeoForge's BreakBlockEvent and branches on its
			// isCanceled(); there is no MinecraftForge hook in that class at all. Same cancellable shape as the
			// three above, in its own class because it names NeoForge's block-event package.
			install(cl, neoBus, GameEventBridge.BLOCK_BREAK,
					bus -> blockBridge(cl, "installBlockBreak").invoke(null, bus));
			// Clicking a block. Same class in the merged base, same absence of any MinecraftForge hook — and these
			// two carry a useBlock/useItem decision as well as a cancel, so the forward translates NeoForge's
			// TriState to MinecraftForge's Result and back.
			install(cl, neoBus, GameEventBridge.RIGHT_CLICK_BLOCK,
					bus -> blockBridge(cl, "installRightClickBlock").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.LEFT_CLICK_BLOCK,
					bus -> blockBridge(cl, "installLeftClickBlock").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.RIGHT_CLICK_ITEM,
					bus -> blockBridge(cl, "installRightClickItem").invoke(null, bus));
			// Loot tables. Not a bus forward: the merged ReloadableServerRegistries is already routed through
			// KernelLootBridge, which chains NeoForge then Fabric, and this puts MinecraftForge's event in
			// between. Installed here so the switch and the dead-event audit treat it like every other bridge.
			install(cl, neoBus, GameEventBridge.LOOT_TABLE_LOAD, bus -> lootBridge(cl).invoke(null));
			// Placing a block. The merged ItemStack.useOn calls only NeoForge's onPlaceItemIntoWorld, because the
			// snapshot list it drains is NeoForge-typed, so the MinecraftForge event went with it.
			install(cl, neoBus, GameEventBridge.ENTITY_PLACE_BLOCK,
					bus -> blockBridge(cl, "installEntityPlace").invoke(null, bus));
			// Playing a sound. ServerLevel's and ClientLevel's playSeededSound and LocalPlayer.playSound ask only
			// NeoForge's PlayLevelSoundEvent and play what it ends with; MinecraftForge rewrote the same vanilla lines
			// and the merge keeps NeoForge's rewrite. The answer — cancel, sound, source, volume, pitch — is carried back.
			install(cl, neoBus, GameEventBridge.PLAY_SOUND_AT_ENTITY,
					bus -> soundBridge(cl, "installAtEntity").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.PLAY_SOUND_AT_POSITION,
					bus -> soundBridge(cl, "installAtPosition").invoke(null, bus));
			// Server-lifecycle hooks. A merged base whose runServer calls only NeoForge's ServerLifecycleHooks
			// .handleServerStarted leaves MinecraftForge's login gate (ServerLifecycleHooks.handleServerLogin →
			// `if (!allowLogins.get())`) permanently CLOSED, so the local player's integrated-server connection is
			// rejected "Server is still starting" and singleplayer world-join fails; these re-emit the dropped Forge
			// hooks off NeoForge's events. A base that calls a Forge hook natively on the same path (one that keeps
			// both families' edits in initServer and runServer) has these as BRIDGED rows, so each forward stands down
			// for the event the native call delivered.
			install(cl, neoBus, GameEventBridge.SERVER_ABOUT_TO_START,
					bus -> aboutToStartBridge(cl).invoke(null, bus));
			install(cl, neoBus, GameEventBridge.SERVER_STARTING,
					bus -> lifecycleBridge(cl, "installStarting").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.SERVER_STARTED, bus -> lifecycleBridge(cl, "installStarted").invoke(null, bus));
			// The kernel's own work at server start is no forward, so it goes on NeoForge's bus itself, after the
			// forward, and runs whether the forward ran or stood down.
			try {
				lifecycleBridge(cl, "installStartedDuties").invoke(null, neoBus);
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/EventMux] the kernel's server-started work did not install — no dead-event "
						+ "judgement, load report or compatibility decision once a world is up", Reflect.unwrap(t));
			}
			install(cl, neoBus, GameEventBridge.SERVER_STOPPING,
					bus -> lifecycleBridge(cl, "installStopping").invoke(null, bus));
			install(cl, neoBus, GameEventBridge.SERVER_STOPPED,
					bus -> lifecycleBridge(cl, "installStopped").invoke(null, bus));

			EventBridges.verify(GameEventBridge.Pass.GAME_BUS);

			if (client) {
				install(cl, neoBus, GameEventBridge.CLIENT_TICK_PRE,
						bus -> clientTickBridge(cl, "installPre").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.CLIENT_TICK_POST,
						bus -> clientTickBridge(cl, "installPost").invoke(null, bus));
				// Its own class and its own two installs: the render frame is a different NeoForge event from the
				// client tick, and a mod can be dead on one and live on the other.
				install(cl, neoBus, GameEventBridge.RENDER_FRAME_PRE,
						bus -> renderFrameBridge(cl, "installPre").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.RENDER_FRAME_POST,
						bus -> renderFrameBridge(cl, "installPost").invoke(null, bus));
				// The screen MOUSE family, which the render-frame bridge does not reach: a different producer in
				// a different class (the merged MouseHandler routes every one of these to NeoForge's ClientHooks),
				// so a mod can be live on the frame and still dead on the mouse. MouseTweaks is exactly these four
				// listeners and nothing else, so with them missing it loads cleanly and does nothing at all.
				install(cl, neoBus, GameEventBridge.SCREEN_MOUSE_PRESSED_PRE,
						bus -> screenMouseBridge(cl, "installPressedPre").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.SCREEN_MOUSE_RELEASED_PRE,
						bus -> screenMouseBridge(cl, "installReleasedPre").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.SCREEN_MOUSE_DRAG_PRE,
						bus -> screenMouseBridge(cl, "installDragPre").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.SCREEN_MOUSE_SCROLL_POST,
						bus -> screenMouseBridge(cl, "installScrollPost").invoke(null, bus));
				// The connection lifecycle and client commands: the merged ClientPacketListener and Minecraft post
				// NeoForge's events at MinecraftForge's positions and nothing calls MinecraftForge's hooks.
				install(cl, neoBus, GameEventBridge.CLIENT_LOGGING_IN,
						bus -> clientNetworkBridge(cl, "installLoggingIn").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.CLIENT_LOGGING_OUT,
						bus -> clientNetworkBridge(cl, "installLoggingOut").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.CLIENT_PLAYER_CLONE,
						bus -> clientNetworkBridge(cl, "installClone").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.CLIENT_COMMANDS,
						bus -> clientNetworkBridge(cl, "installClientCommands").invoke(null, bus));
				// Chat, input, fog, field of view, overlays, the boss bar and screen drawing: each merged producer
				// posts NeoForge's event and reads it back, and no MinecraftForge hook is called.
				install(cl, neoBus, GameEventBridge.CLIENT_CHAT_RECEIVED,
						bus -> clientBridge(cl, "installChatReceived").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.CLIENT_CHAT_SEND,
						bus -> clientBridge(cl, "installChatSend").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.KEY_INPUT,
						bus -> clientBridge(cl, "installKey").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.SCREEN_INIT_PRE,
						bus -> clientBridge(cl, "installScreenInitPre").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.SCREEN_INIT_POST,
						bus -> clientBridge(cl, "installScreenInitPost").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.SCREEN_KEY_PRESSED_PRE,
						bus -> clientBridge(cl, "installScreenKeyPressedPre").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.SCREEN_KEY_PRESSED_POST,
						bus -> clientBridge(cl, "installScreenKeyPressedPost").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.SCREEN_KEY_RELEASED_PRE,
						bus -> clientBridge(cl, "installScreenKeyReleasedPre").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.SCREEN_KEY_RELEASED_POST,
						bus -> clientBridge(cl, "installScreenKeyReleasedPost").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.MOUSE_BUTTON_PRE,
						bus -> clientBridge(cl, "installMouseButtonPre").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.INTERACTION_KEY,
						bus -> clientBridge(cl, "installInteractionKey").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.RENDER_FOG,
						bus -> clientBridge(cl, "installRenderFog").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.FOG_COLOR,
						bus -> clientBridge(cl, "installFogColor").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.FOV_MODIFIER,
						bus -> clientBridge(cl, "installFovModifier").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.BLOCK_OVERLAY,
						bus -> clientBridge(cl, "installBlockOverlay").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.BOSS_EVENT_PROGRESS,
						bus -> clientBridge(cl, "installBossEventProgress").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.SCREEN_RENDER_PRE,
						bus -> clientBridge(cl, "installScreenRenderPre").invoke(null, bus));
				install(cl, neoBus, GameEventBridge.SCREEN_RENDER_POST,
						bus -> clientBridge(cl, "installScreenRenderPost").invoke(null, bus));
				EventBridges.verify(GameEventBridge.Pass.CLIENT_GAME_BUS);
			}
		} catch (ClassNotFoundException single) {
			ForbricLog.debug("[Forbric/EventMux] only one Forge family present — no bridge needed");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/EventMux] could not install Neo→Forge game-event bridge",
					Reflect.unwrap(t));
		}
	}

	/**
	 * NeoForge {@code AddClientReloadListenersEvent} → MinecraftForge {@code RegisterClientReloadListenersEvent}.
	 *
	 * <p>Same shape as the tick bridge, one layer up: NeoForge won the client reload-listener path in the byte merge
	 * (the base calls {@code neoforge/client/ClientHooks} and carries ZERO references to the MinecraftForge event),
	 * so a traditional-Forge mod's {@code @SubscribeEvent RegisterClientReloadListenersEvent} handler is registered
	 * on a bus nobody ever posts to. GeckoLib's whole client model/animation cache hangs off exactly that.
	 *
	 * <p>The two events have incompatible shapes — Forge's hands out a {@code ReloadableResourceManager} and takes
	 * unnamed listeners, NeoForge's takes {@code (Identifier, listener)} pairs and sorts them in a dependency graph.
	 * {@code ReloadableResourceManager} is a class, not an interface, so it cannot be proxied; instead a THROWAWAY
	 * instance is used purely as a capture buffer ({@code registerReloadListener} delegates straight to it), and what
	 * lands in it is then fed into NeoForge's graph under a synthesised {@code forbric:forge/…} name. Going through
	 * the graph rather than registering on the real manager is deliberate: it is the officially-correct path on a
	 * NeoForge-derived base and needs no assumption about whether a late direct registration is still honoured.
	 *
	 * @param modBus the NeoForge mod bus the game posts client mod-bus events to; {@code AddClientReloadListenersEvent}
	 *               is an {@code IModBusEvent}, so the game bus would never see it
	 */
	/**
	 * Watches NeoForge's own data-map reload path (registration, apply per reload, dispatch) so the kernel's
	 * about-to-start fallback can stand down when it ran. Not a {@link GameEventBridge}: it forwards nothing.
	 */
	public static void installDataMapWatch(ClassLoader cl) {
		try {
			Object neoBus = Class.forName("net.neoforged.neoforge.common.NeoForge", false, cl).getField("EVENT_BUS").get(null);
			Class.forName("net.forbric.kernel.runtime.KernelNeoDataMapWatch", true, cl)
					.getMethod("installDataMapWatch", Object.class).invoke(null, neoBus);
			ForbricLog.debug("[Forbric/EventMux] watching NeoForge's data-map reload path");
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/EventMux] no NeoForge bus — no data-map path to watch");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/EventMux] could not watch NeoForge's data-map reload path — the kernel's fallback "
					+ "will load the data maps as before", Reflect.unwrap(t));
		}
	}

	public static void installClientReloadBridge(ClassLoader cl, Object modBus) {
		if (!EventBridges.enabled()) {
			ForbricLog.info("[Forbric/EventMux] -D%s=off — skipping the client reload-listener bridge",
					EventBridges.SWITCH_NAME);
			return;
		}
		if (modBus == null) {
			ForbricLog.debug("[Forbric/EventMux] no NeoForge mod bus — skipping the client reload-listener bridge");
			return;
		}
		try {
			Class.forName("net.forbric.kernel.runtime.KernelGameClientReload", true, cl)
					.getMethod("install", Object.class).invoke(null, modBus);
			EventBridges.installed(GameEventBridge.CLIENT_RELOAD_LISTENERS);
			ForbricLog.info("[Forbric/EventMux] installed the Neo→Forge client reload-listener bridge");
			// An atlas stitched and the models baked: NeoForge posts both on this bus and nothing calls MinecraftForge's.
			Class.forName("net.forbric.kernel.runtime.KernelGameClientResourceEvents", true, cl)
					.getMethod("install", Object.class).invoke(null, modBus);
			EventBridges.installed(GameEventBridge.TEXTURE_STITCHED);
			EventBridges.installed(GameEventBridge.MODELS_BAKED);
			EventBridges.verify(GameEventBridge.Pass.CLIENT_MOD_BUS);
		} catch (ClassNotFoundException single) {
			ForbricLog.debug("[Forbric/EventMux] only one Forge family present — no reload-listener bridge needed");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/EventMux] could not install the client reload-listener bridge",
					Reflect.unwrap(t));
		}
	}

	/**
	 * One entry point on the game-side tick bridge, resolved by name because this file cannot name it.
	 *
	 * <p>Written as a complete string literal on purpose: {@code KernelRuntimeClassesTest} finds game-side names
	 * by scanning boot-side sources for exactly this shape, and a name assembled from a constant and a suffix
	 * would slip past it — leaving the class unregistered, the boot-time seam check blind to it, and a renamed
	 * method a mid-game {@code NoSuchMethodException} instead of one line at startup.
	 */
	/**
	 * One entry point on the game-side entity-tracking bridge, resolved by name because this file cannot name it.
	 *
	 * <p>Written as a complete string literal on purpose, for the reason {@link #tickBridge} gives: the name is
	 * found by scanning boot-side sources for exactly this shape.
	 */
	/**
	 * One entry point on the game-side result-carrying bridges, resolved by name because this file cannot name
	 * it. Complete string literal, for the reason {@link #tickBridge} gives.
	 */
	private static Method resultBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameResultBridges", true, cl)
				.getMethod(entry, Object.class);
	}

	private static Method trackingBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGamePlayerTrackingEvents", true, cl)
				.getMethod(entry, Object.class);
	}

	private static Method tickBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameTickEvents", true, cl)
				.getMethod(entry, Object.class);
	}

	/** One entry point on the game-side cancellable-entity bridge. Complete literal, for the reason above. */
	private static Method entityBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameEntityEvents", true, cl)
				.getMethod(entry, Object.class);
	}

	/** One entry point on the game-side client-events bridge. Complete literal, for the reason above. */
	private static Method clientBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameClientEvents", true, cl)
				.getMethod(entry, Object.class);
	}

	/** One entry point on the game-side world-events bridge. Complete literal, for the reason above. */
	private static Method worldBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameWorldEvents", true, cl)
				.getMethod(entry, Object.class);
	}

	/** One entry point on the game-side server-events bridge. Complete literal, for the reason above. */
	private static Method serverBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameServerEvents", true, cl)
				.getMethod(entry, Object.class);
	}

	/** One entry point on the game-side client connection bridge. Complete literal, for the reason above. */
	private static Method clientNetworkBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameClientNetworkEvents", true, cl)
				.getMethod(entry, Object.class);
	}

	/** One entry point on the game-side damage bridge. Complete literal, for the reason above. */
	private static Method damageBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameDamageEvents", true, cl)
				.getMethod(entry, Object.class);
	}

	/** One entry point on the game-side level-lifecycle bridge. Complete literal, for the reason above. */
	private static Method levelBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameLevelEvents", true, cl)
				.getMethod(entry, Object.class);
	}

	/** The loot-table chain's MinecraftForge link. Complete literal, for the reason above. */
	private static Method lootBridge(ClassLoader cl) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelLootBridge", true, cl).getMethod("install");
	}

	/** One entry point on the game-side cancellable-block bridge. Complete literal, for the reason above. */
	private static Method blockBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameBlockEvents", true, cl)
				.getMethod(entry, Object.class);
	}

	/** One entry point on the game-side sound bridge. Complete literal, for the reason above. */
	private static Method soundBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameSoundEvents", true, cl)
				.getMethod(entry, Object.class);
	}

	/** One entry point on the game-side player/command bridge. Complete literal, for the reason above. */
	private static Method playerBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGamePlayerEvents", true, cl)
				.getMethod(entry, Object.class);
	}

	/** One entry point on the game-side CLIENT tick bridge. Complete literal, for the reason above. */
	private static Method clientTickBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameClientTickEvents", true, cl)
				.getMethod(entry, Object.class);
	}

	/** One entry point on the game-side render-frame bridge. Complete literal, for the reason above. */
	private static Method renderFrameBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameRenderFrameEvents", true, cl)
				.getMethod(entry, Object.class);
	}

	/** One entry point on the game-side screen-MOUSE bridge. Complete literal, for the reason above. */
	private static Method screenMouseBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameScreenMouseEvents", true, cl)
				.getMethod(entry, Object.class);
	}

	/** One entry point on the game-side server-lifecycle bridge. Complete literal, for the reason above. */
	private static Method lifecycleBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameServerLifecycle", true, cl)
				.getMethod(entry, Object.class);
	}

	/** The game-side about-to-start bridge. Complete literal, for the reason above. */
	private static Method aboutToStartBridge(ClassLoader cl) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameServerAboutToStart", true, cl)
				.getMethod("install", Object.class);
	}

	/**
	 * One bridge's setup, handed the bus it installs on, so a failure can be caught per bridge instead of taking the rest
	 * of the pass with it.
	 */
	@FunctionalInterface
	private interface Setup {
		void run(Object bus) throws Exception;
	}

	/**
	 * Runs one bridge's setup and records it only if it succeeded.
	 *
	 * <p>Swallowing here is deliberate and is the opposite of what the old code did by accident: it caught at the
	 * whole-pass level, so one failure silently cost every bridge after it. Catching per bridge means a failure
	 * costs exactly its own feature, and {@link EventBridges#verify} then names what was lost.
	 */
	private static void install(ClassLoader cl, Object neoBus, GameEventBridge bridge, Setup setup) {
		try {
			setup.run(guarded(cl, neoBus, bridge));
			EventBridges.installed(bridge);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/EventMux] bridge " + bridge + " (" + bridge.event() + ") did not install — "
					+ bridge.cost(), Reflect.unwrap(t));
		}
	}

	/** NeoForge's game-bus interface, which every bridge's install entry receives its bus as. */
	static final String NEO_BUS = "net.neoforged.bus.api.IEventBus";

	/**
	 * The bus a bridge installs on.
	 *
	 * <p>A bridge whose MinecraftForge events are {@link DeadEventAudit#BRIDGED} rows may meet a merged method that now
	 * delivers one of them natively on the same path as the NeoForge event the bridge forwards from. For those, the
	 * bridge is handed a view of NeoForge's bus whose four-argument {@code addListener} wraps the listener: for an event
	 * {@code NativeDualEventInjector} scoped as natively delivered with one of this bridge's events, the forward is
	 * skipped; every other event, nested ones of the same class included, reaches the bridge as before. The bridge
	 * classes are untouched, and a bridge with no row gets NeoForge's bus itself.
	 */
	static Object guarded(ClassLoader cl, Object neoBus, GameEventBridge bridge) throws ClassNotFoundException {
		java.util.Set<String> forgeEvents = DeadEventAudit.forgeEventsOf(bridge);
		if (forgeEvents.isEmpty() || neoBus == null) return neoBus;
		Class<?> busType = Class.forName(NEO_BUS, false, cl);
		if (!busType.isInstance(neoBus)) return neoBus;
		try {
			return java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[] {busType}, new StandDownBus(neoBus, forgeEvents));
		} catch (RuntimeException | LinkageError unproxyable) {
			// The bridge itself matters more than its stand-down: it installs on the bus as before.
			ForbricLog.warn("[Forbric/EventMux] bridge " + bridge + " installs unguarded — where the merged game also "
					+ "delivers its MinecraftForge event natively, a mod may receive it twice", unproxyable);
			return neoBus;
		}
	}

	/** NeoForge's bus, with each explicitly typed listener added through it guarded by {@link StandDownListener}. */
	static final class StandDownBus implements java.lang.reflect.InvocationHandler {
		private final Object bus;
		private final java.util.Set<String> forgeEvents;

		StandDownBus(Object bus, java.util.Set<String> forgeEvents) {
			this.bus = bus;
			this.forgeEvents = java.util.Set.copyOf(forgeEvents);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (method.getDeclaringClass() == Object.class) {
				switch (method.getName()) {
					case "equals":
						return proxy == args[0] || bus.equals(args[0]);
					case "hashCode":
						return bus.hashCode();
					case "toString":
						return "stand-down view of " + bus;
					default:
						break;
				}
			}
			Object[] call = args;
			if (method.getName().equals("addListener") && args != null && typed(args)
					&& args[args.length - 1] instanceof java.util.function.Consumer<?> listener) {
				call = args.clone();
				@SuppressWarnings("unchecked")
				java.util.function.Consumer<Object> forward = (java.util.function.Consumer<Object>) listener;
				call[call.length - 1] = new StandDownListener(forward, forgeEvents);
			}
			try {
				return method.invoke(bus, call);
			} catch (java.lang.reflect.InvocationTargetException thrown) {
				throw thrown.getCause();
			}
		}

		/**
		 * Whether the overload names its event class: the untyped ones resolve it from the listener's own generic
		 * signature, which a wrapper would hide, so those are passed through untouched.
		 */
		private static boolean typed(Object[] args) {
			for (Object arg : args) if (arg instanceof Class<?>) return true;
			return false;
		}
	}

	/** One bridge listener that stands down for an event the merged game delivered natively on its path. */
	record StandDownListener(java.util.function.Consumer<Object> forward, java.util.Set<String> forgeEvents)
			implements java.util.function.Consumer<Object> {
		@Override
		public void accept(Object event) {
			if (net.forbric.api.NativeEventDelivery.covered(event, forgeEvents)) {
				EventChainAudit.nativelyDelivered(event, forgeEvents);
				return;
			}
			forward.accept(event);
		}
	}
}
