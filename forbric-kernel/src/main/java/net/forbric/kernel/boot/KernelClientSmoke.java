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

import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Drives an unattended client run so a gate can assert on it: enter a world, live in it, leave cleanly, exit.
 *
 * <p>Every client fix in this kernel has been verified by launching the game and reading the log by hand, which
 * means none of them is protected against the next change. The obstacle is that a client does not end on its own
 * — quick-play gets it into a world, and then it sits there. This is the missing half: a tick hook that counts
 * ticks spent actually in a world, then asks the game to disconnect and stop, so a gate script can wait for a
 * definite outcome instead of a timeout.
 *
 * <p>Three markers, in order, and the gate asserts all three because each rules out a different failure. Joining
 * says the world loaded; surviving READY_TICKS says it did not die on the first tick of real simulation, which is
 * where registry and attribute problems land; the clean disconnect says teardown works and — because the launcher
 * deliberately does not kill the process afterwards — leaves vanilla's own shutdown watchdog free to catch a
 * leaked non-daemon thread.
 *
 * <p>Off unless {@code -Dforbric.clientSmoke=true}. Everything is reached reflectively and every failure is
 * swallowed: a diagnostic must never be able to break the thing it is measuring.
 */
public final class KernelClientSmoke {
	public static final String ENABLED = "forbric.clientSmoke";
	private static final String WORLD = "forbric.clientSmokeWorld";
	private static final String READY_TICKS = "forbric.clientSmokeReadyTicks";
	private static final String DISCONNECT_TICKS = "forbric.clientSmokeDisconnectTicks";
	/** {@code true}: after client-ready, drive the player through the movement drill (see {@link #drill}). */
	public static final String DRILL = "forbric.clientSmokeDrill";
	/** {@code true}: end the drill with one deliberately impossible move, so the gate can prove the anti-cheat is watching. */
	public static final String DRILL_CONTROL = "forbric.clientSmokeDrillControl";
	/**
	 * {@code x,y,z;x,y,z;…}: block positions to read back after client-ready and log by registry name. What a gate
	 * uses to see whether the client decodes the server's blocks as the server meant them — a registry-id mismatch
	 * shows up here as the wrong name, while everything else about the session looks fine.
	 */
	public static final String PROBE = "forbric.clientSmokeProbe";
	private static final String PROBE_TICKS = "forbric.clientSmokeProbeTicks";
	/**
	 * {@code registry:namespace:path;…} (registry is {@code item} or {@code block}): entries whose raw ids to log at
	 * three moments — before connecting, in the world, and after the clean disconnect. The three lines are what a
	 * gate uses to see a remap happen AND be undone: the first and last must agree, the middle may differ.
	 */
	public static final String PROBE_IDS = "forbric.clientSmokeProbeIds";
	/**
	 * {@code tick[,tick…]}: world ticks at which to save a screenshot into {@code <gameDir>/screenshots}.
	 *
	 * <p>Every other marker this class produces is a log line, which can only ever say that code RAN. A feature
	 * whose whole output is pixels — a mod's particles, a ragdoll, a shader pass — runs exactly the same when it
	 * draws nothing, so a log-only gate calls that green. This is the seam for asserting on the frame itself.
	 */
	public static final String SCREENSHOTS = "forbric.clientSmokeScreenshots";

	/**
	 * World tick at which to open the unified Mods screen, hold it, and close it again.
	 *
	 * <p>A screen is the one thing here no unit test can prove: its {@code init} and its draw run only when a
	 * player clicks the button, so a mistake in either is a crash in the middle of a frame on someone else's
	 * machine. This opens it on a real client and reads back how many frames it drew.
	 */
	public static final String MODS_SCREEN = "forbric.clientSmokeModsScreen";
	/**
	 * {@code modid[,modid…]}: open each of these mods' config screens the way a player does — select the mod's row in
	 * the unified Mods screen and press its Config button — then log the class of the screen in front and save a
	 * screenshot named {@code forbric-config-<modid>.png}.
	 *
	 * <p>The class name says whose screen it is; the picture says it is that mod's settings and not an empty frame
	 * or a crash screen. Neither is a claim the resolver can make about itself: it can only say it returned an
	 * object. Starts at {@link #CONFIG_SCREENS_AT}; give the run about {@link #CONFIG_SCREEN_HOLD} + 4 ticks per mod.
	 */
	public static final String CONFIG_SCREENS = "forbric.clientSmokeConfigScreens";
	/** World tick at which {@link #CONFIG_SCREENS} starts (default 100). */
	public static final String CONFIG_SCREENS_AT = "forbric.clientSmokeConfigScreensAt";
	/** Ticks a config screen is left up before its screenshot: long enough for its own init and a few frames. */
	private static final int CONFIG_SCREEN_HOLD = 12;
	/** World tick at which vanilla's key binds screen is opened, to see which screen the game ends up showing. */
	public static final String KEY_BINDS_SCREEN = "forbric.clientSmokeKeyBinds";
	/** Take a screenshot of the pause menu with the mods button on it, instead of pressing it. */
	public static final String MODS_BUTTON_SHOT = "forbric.clientSmokeModsButtonShot";
	/**
	 * World tick at which to open a container screen and drive one press, one drag, one release and one wheel
	 * notch through the game's OWN {@code MouseHandler}. 0 (the default) leaves it alone.
	 *
	 * <p>It exists because the screen-mouse bridges cannot be judged from anything the kernel says. A forward
	 * counter proves the kernel forwarded; what was in doubt is whether a traditional-Forge mod's listener runs,
	 * and the only witness to that is the mod itself. Driving {@code MouseHandler.onButton},
	 * {@code handleAccumulatedMovement} and {@code onScroll} — the three private methods the merged base's own
	 * GLFW callbacks call, and the three that hold NeoForge's hooks — makes a mod that listens on the
	 * MinecraftForge side speak, or stay silent, with nothing in between.
	 */
	public static final String SCREEN_MOUSE = "forbric.clientSmokeScreenMouse";

	/**
	 * World tick at which to run the carry drill: hold sneak with empty hands and right-click a chest, then the
	 * floor, then a pig, then the floor again — the gesture Carry On and every mod like it is built on. 0 (the
	 * default) leaves it alone.
	 *
	 * <p>Every input goes through the game's OWN {@code KeyboardHandler.keyPress} and {@code MouseHandler.onButton}
	 * — the private methods GLFW's callbacks call — so the drill covers the whole chain a player's hands start:
	 * the key mapping going down, a mod's client tick noticing it, the mod's packet to the server, the server's
	 * interaction event, and the world changing. Each report reads the WORLD (is the chest still there, is the pig)
	 * and, when Carry On is installed, Carry On's own data on both sides; nothing Forbric says is part of the answer.
	 */
	public static final String CARRY = "forbric.clientSmokeCarry";

	/** World tick at which to equip an elytra and try to glide. */
	public static final String ELYTRA = "forbric.clientSmokeElytra";
	/** Altitude to drop from. Absolute, because the world keeps whatever the last run left behind. */
	public static final String ELYTRA_ALTITUDE = "forbric.clientSmokeElytraAltitude";
	/** Ticks to leave it open. Long enough for frames to be drawn, short enough not to move the disconnect. */
	private static final int MODS_SCREEN_HOLD = 20;

	private static Object lastLevel;
	private static int worldTicks;
	private static boolean joined;
	private static boolean ready;
	private static boolean disconnectRequested;
	private static boolean stopRequested;
	private static int drillTick = -1;
	private static boolean drillDone;
	private static boolean probed;
	private static final java.util.Set<Integer> shotsTaken = new java.util.HashSet<>();
	private static boolean idsLoggedBeforeConnect;

	private KernelClientSmoke() {
	}

	/** Whether the smoke run is armed. Read per call so a test can drive both modes in one JVM. */
	public static boolean enabled() {
		return Boolean.getBoolean(ENABLED) || KernelSoakHooks.enabled();
	}

	/**
	 * One client tick. {@code minecraft} is typed {@code Object} because this class is BOOT-side and cannot name
	 * {@code net.minecraft} types at compile time — the same widening-reference trick the other hooks use.
	 */
	public static void onClientTick(Object minecraft) {
		if (KernelSoakHooks.enabled()) { KernelSoakHooks.onClientTick(minecraft); return; }
		if (minecraft == null || stopRequested || !enabled()) return;
		try {
			tick(minecraft);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] tick hook failed");
		}
	}

	private static void tick(Object minecraft) {
		if (!connectionProbesArmed) armConnectionProbes(minecraft);
		Object level = fieldValue(minecraft, "level");
		Object player = fieldValue(minecraft, "player");

		if (level == null || player == null) {
			// Out of a world. If we asked to leave one, that request has now been honoured.
			lastLevel = null;
			worldTicks = 0;
			if (!idsLoggedBeforeConnect) {
				idsLoggedBeforeConnect = true;
				probeIds(minecraft, "before connecting");
			}
			if (disconnectRequested) {
				disconnectRequested = false;
				stopRequested = true;
				probeIds(minecraft, "after disconnect");
				if (connectionProbesArmed) {
					ForbricLog.info("[Forbric/ClientSmoke] MinecraftForge connection events: LoggingIn %d, LoggingOut %d",
							forgeLogins[0], forgeLogins[1]);
					StringBuilder census = new StringBuilder();
					for (String[] heard : FORGE_CLIENT_CENSUS) {
						census.append(' ').append(heard[0]).append('=').append(forgeHeard.getOrDefault(heard[0], 0));
					}
					ForbricLog.info("[Forbric/ClientSmoke] MinecraftForge client events heard:%s", census);
				}
				ForbricLog.info("[Forbric/ClientSmoke] clean disconnect observed; stopping client");
				invokeNoArg(minecraft, "stop");
			}
			return;
		}

		if (level != lastLevel) {
			lastLevel = level;
			worldTicks = 0;
			joined = false;
			ready = false;
			disconnectRequested = false;
		}

		worldTicks++;
		lastPlayer = player;
		if (!joined) {
			joined = true;
			ForbricLog.info("[Forbric/ClientSmoke] joined world via quick-play: %s",
					System.getProperty(WORLD, "<quick-play>"));
		}
		if (!ready && worldTicks >= Integer.getInteger(READY_TICKS, 60)) {
			ready = true;
			ForbricLog.info("[Forbric/ClientSmoke] client-ready after %d world tick(s)", worldTicks);
			if (connectionProbesArmed) reportClientCommands(minecraft);
			reportWindowTitle(minecraft);
		}
		if (ready && !drillDone && Boolean.getBoolean(DRILL)) drill(minecraft, player);
		if (ready) elytraCheck(minecraft, player);
		if (ready) carryIfDue(minecraft, player);
		if (ready) screenMouseIfDue(minecraft, player);
		if (ready) screenshotIfDue(minecraft);
		if (ready) keyBindsScreenIfDue(minecraft);
		if (ready) modsScreenIfDue(minecraft);
		if (ready) configScreensIfDue(minecraft);
		if (ready) creativeSearchIfDue(minecraft, player);
		if (ready && !tooltipProbed) probeTooltip(level, player);
		if (ready && !probed && worldTicks >= Integer.getInteger(PROBE_TICKS, 160)) {
			probed = true;
			probeBlocks(level);
			probeIds(minecraft, "in world");
		}
		if (!disconnectRequested && worldTicks >= Integer.getInteger(DISCONNECT_TICKS, 120)) {
			disconnectRequested = true;
			ForbricLog.info("[Forbric/ClientSmoke] requesting clean disconnect after %d world tick(s)", worldTicks);
			invokeNoArg(minecraft, "disconnectWithSavingScreen");
		}
	}

	private static boolean keyBindsTried;

	/** LoggingIn, LoggingOut as MinecraftForge listeners saw them. */
	private static final int[] forgeLogins = new int[2];
	/** MinecraftForge client events a smoke run produces on its own, each heard by a listener as a mod's would be. */
	private static final String[][] FORGE_CLIENT_CENSUS = {
			{"RenderFog", "net.minecraftforge.client.event.ViewportEvent$RenderFog"},
			{"FogColor", "net.minecraftforge.client.event.ViewportEvent$ComputeFogColor"},
			{"FovModifier", "net.minecraftforge.client.event.ComputeFovModifierEvent"},
			{"ScreenRenderPre", "net.minecraftforge.client.event.ScreenEvent$Render$Pre"},
			{"ScreenRenderPost", "net.minecraftforge.client.event.ScreenEvent$Render$Post"},
			{"SystemMessage", "net.minecraftforge.client.event.SystemMessageReceivedEvent"},
			{"TextureStitched", "net.minecraftforge.client.event.TextureStitchEvent$Post"},
			{"ModelsBaked", "net.minecraftforge.client.event.ModelEvent$BakingCompleted"}};
	private static final java.util.Map<String, Integer> forgeHeard = new java.util.concurrent.ConcurrentHashMap<>();
	private static boolean connectionProbesArmed;

	/**
	 * Listens the way a MinecraftForge mod does for the client joining and leaving, and registers one client command
	 * through each family's registration event, before any world is joined. What the game's command tree holds after
	 * joining says whether both registrations reached the dispatcher the game runs — and whether MinecraftForge's own
	 * handler replaced it with a tree of its own (the NeoForge command would then be gone).
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void armConnectionProbes(Object minecraft) {
		connectionProbesArmed = true;
		try {
			ClassLoader cl = minecraft.getClass().getClassLoader();
			Class<?> literal = Class.forName("com.mojang.brigadier.builder.LiteralArgumentBuilder", true, cl);
			java.util.function.BiConsumer<Object, String> register = (dispatcher, name) -> {
				try {
					Object node = literal.getMethod("literal", String.class).invoke(null, name);
					dispatcher.getClass().getMethod("register", literal).invoke(dispatcher, node);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException(e);
				}
			};
			String forgeNet = "net.minecraftforge.client.event.ClientPlayerNetworkEvent$";
			for (String[] heard : FORGE_CLIENT_CENSUS) {
				forgeListen(cl, heard[1], e -> forgeHeard.merge(heard[0], 1, Integer::sum));
			}
			forgeListen(cl, forgeNet + "LoggingIn", e -> forgeLogins[0]++);
			forgeListen(cl, forgeNet + "LoggingOut", e -> forgeLogins[1]++);
			forgeListen(cl, net.forbric.api.ForeignType.CLIENT_COMMANDS_EVENT.binary(net.forbric.api.Ecosystem.FORGE),
					e -> register.accept(invoke(e, "getDispatcher"), "forbricsmokeforge"));
			Object neoBus = Class.forName("net.neoforged.neoforge.common.NeoForge", true, cl).getField("EVENT_BUS").get(null);
			Class<?> neoEvent = Class.forName(net.forbric.api.ForeignType.CLIENT_COMMANDS_EVENT.binary(net.forbric.api.Ecosystem.NEOFORGE), true, cl);
			neoBus.getClass().getMethod("addListener", Class.class, java.util.function.Consumer.class).invoke(neoBus, neoEvent,
					(java.util.function.Consumer) e -> register.accept(invoke(e, "getDispatcher"), "forbricsmokeneo"));
		} catch (ClassNotFoundException absent) {
			Caught.expected(absent);
			ForbricLog.debug("[Forbric/ClientSmoke] not both families on this client — no connection probes");
			connectionProbesArmed = false;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] could not arm the connection probes");
			connectionProbesArmed = false;
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void forgeListen(ClassLoader cl, String event, java.util.function.Consumer<Object> listener) throws Exception {
		Object bus = Class.forName(event, true, cl).getField("BUS").get(null);
		// The bus's public interface, not its class: the implementation is not public.
		for (Class<?> type = bus.getClass(); type != null; type = type.getSuperclass()) {
			for (Class<?> api : type.getInterfaces()) {
				try {
					api.getMethod("addListener", java.util.function.Consumer.class).invoke(bus, (java.util.function.Consumer) listener);
					return;
				} catch (NoSuchMethodException elsewhere) {
					Caught.expected(elsewhere);
					continue;
				}
			}
		}
		throw new NoSuchMethodException(event + ".BUS.addListener(Consumer)");
	}

	private static Object invoke(Object target, String method) {
		try {
			return target.getClass().getMethod(method).invoke(target);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Which of the two probe commands the joined connection's command tree has. */
	private static void reportClientCommands(Object minecraft) {
		try {
			Object connection = invoke(minecraft, "getConnection");
			Object root = invoke(invoke(connection, "getCommands"), "getRoot");
			java.lang.reflect.Method child = root.getClass().getMethod("getChild", String.class);
			ForbricLog.info("[Forbric/ClientSmoke] client command tree after joining: forge=%s neo=%s",
					child.invoke(root, "forbricsmokeforge") != null, child.invoke(root, "forbricsmokeneo") != null);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] could not read the client command tree");
		}
	}

	/**
	 * Opens vanilla's key binds screen the way the options menu does and reports what the game shows. A NeoForge mod
	 * that swaps screens on {@code ScreenEvent.Opening} — Controlling replaces this one with its own — is how the
	 * answer differs from what was asked for, so the report is that mod's own behaviour, not a counter of ours.
	 */
	private static void keyBindsScreenIfDue(Object minecraft) {
		int due = Integer.getInteger(KEY_BINDS_SCREEN, 0);
		if (due <= 0 || keyBindsTried || worldTicks < due) return;
		keyBindsTried = true;
		try {
			ClassLoader cl = minecraft.getClass().getClassLoader();
			Class<?> screenType = Class.forName("net.minecraft.client.gui.screens.Screen", false, cl);
			Class<?> optionsType = Class.forName("net.minecraft.client.Options", false, cl);
			Object screen = Class.forName("net.minecraft.client.gui.screens.options.controls.KeyBindsScreen", true, cl)
					.getConstructor(screenType, optionsType).newInstance(null, fieldValue(minecraft, "options"));
			setScreen(minecraft, screen);
			Object shown = fieldValue(fieldValue(minecraft, "gui"), "screen");
			ForbricLog.info("[Forbric/ClientSmoke] opened vanilla's KeyBindsScreen; the game shows %s",
					shown == null ? "no screen" : shown.getClass().getName());
			setScreen(minecraft, null);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] could not open vanilla's key binds screen");
		}
	}

	private static boolean modsScreenOpened;
	private static boolean modsScreenClosed;
	private static int modsScreenFramesAtOpen;
	private static boolean configScreenTried;
	private static boolean pauseButtonTried;
	private static int shotDueAt = -1;

	/**
	 * Opens the kernel's unified Mods screen the way the pause menu's button does, leaves it up long enough to be
	 * drawn, and closes it.
	 *
	 * <p>What is reported is the FRAME COUNT the screen itself kept, not the fact that no exception reached here:
	 * a screen that threw during init would be replaced by the crash handler and a "no exception" claim from this
	 * method would still be true. Frames drawn is the only thing that says it rendered.
	 */
	private static void modsScreenIfDue(Object minecraft) {
		int due = Integer.getInteger(MODS_SCREEN, 0);
		if (due <= 0 || modsScreenClosed) return;
		try {
			ClassLoader cl = minecraft.getClass().getClassLoader();
			Class<?> screenCls = Class.forName("net.forbric.kernel.runtime.KernelModListScreen", true, cl);
			if (!modsScreenOpened && worldTicks >= due) {
				modsScreenOpened = true;
				modsScreenFramesAtOpen = (int) screenCls.getMethod("framesDrawn").invoke(null);
				Object screen = screenCls.getConstructor(
						Class.forName("net.minecraft.client.gui.screens.Screen", false, cl)).newInstance((Object) null);
				setScreen(minecraft, screen);
				ForbricLog.info("[Forbric/ClientSmoke] opened the unified Mods screen at world tick %d", worldTicks);
				listenToFabricScreenDraws(screen, cl);
				return;
			}
			if (shotDueAt > 0) {
				if (worldTicks >= shotDueAt) {
					shotDueAt = -1;
					pauseButtonTried = true;
					shoot(minecraft);
				}
				return;
			}
			if (!pauseButtonTried) {
				pauseButtonTried = true;
				openTheResourcePackScreen(minecraft, cl);
				listTheTitleScreensButtons(minecraft, cl);
				pressTheRealModsButton(minecraft, cl);
				return;
			}
			if (modsScreenOpened && !configScreenTried && worldTicks >= due + MODS_SCREEN_HOLD / 2) {
				configScreenTried = true;
				doubleClickARowInTheModList(minecraft, cl);
				openAConfigScreen(minecraft, cl);
				return;
			}
			if (modsScreenOpened && worldTicks >= due + MODS_SCREEN_HOLD) {
				modsScreenClosed = true;
				int frames = (int) screenCls.getMethod("framesDrawn").invoke(null) - modsScreenFramesAtOpen;
				int rows = (int) screenCls.getMethod("rowsBuilt").invoke(null);
				ForbricLog.info("[Forbric/ClientSmoke] the unified Mods screen drew %d frame(s) listing %d mod(s) "
						+ "from every ecosystem, then closed", frames, rows);
				if (fabricScreenDrawsArmed) {
					ForbricLog.info("[Forbric/ClientSmoke] Fabric ScreenEvents on the Mods screen: beforeExtract %d, "
							+ "afterExtract %d", fabricScreenDraws[0], fabricScreenDraws[1]);
				}
				setScreen(minecraft, null);
			}
		} catch (Throwable t) {
			modsScreenClosed = true;
			Caught.warn(t, "[Forbric/ClientSmoke] the unified Mods screen could not be opened");
		}
	}

	/** beforeExtract, afterExtract calls fabric-screen-api made on the smoke's Mods screen. */
	private static final int[] fabricScreenDraws = new int[2];
	private static boolean fabricScreenDrawsArmed;

	/**
	 * Registers the way a Fabric mod does (Jade draws its overlay from these): per-screen before/after-extract
	 * listeners on the screen just opened, which counted, say whether Fabric's screen draw events reach a screen at
	 * all on this base. Nothing when fabric-screen-api is not installed.
	 */
	private static void listenToFabricScreenDraws(Object screen, ClassLoader cl) {
		try {
			Class<?> events = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents", true, cl);
			Class<?> event = Class.forName("net.fabricmc.fabric.api.event.Event", false, events.getClassLoader());
			Class<?> screenType = Class.forName("net.minecraft.client.gui.screens.Screen", false, cl);
			String[][] kinds = {{"beforeExtract", "BeforeExtract"}, {"afterExtract", "AfterExtract"}};
			for (int i = 0; i < kinds.length; i++) {
				int slot = i;
				Class<?> callback = Class.forName(events.getName() + "$" + kinds[i][1], false, events.getClassLoader());
				Object listener = java.lang.reflect.Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[] {callback},
						(proxy, method, args) -> {
							if (method.getDeclaringClass() == Object.class) {
								return switch (method.getName()) {
									case "hashCode" -> System.identityHashCode(proxy);
									case "equals" -> proxy == args[0];
									default -> "ForbricSmokeScreenDraws";
								};
							}
							fabricScreenDraws[slot]++;
							return null;
						});
				event.getMethod("register", Object.class).invoke(events.getMethod(kinds[i][0], screenType).invoke(null, screen), listener);
			}
			fabricScreenDrawsArmed = true;
		} catch (ClassNotFoundException absent) {
			Caught.expected(absent);
			ForbricLog.debug("[Forbric/ClientSmoke] fabric-screen-api not installed — no screen draw events to count");
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] could not listen to Fabric's screen draw events");
		}
	}

	private static int screenMouseStage;
	private static int screenMouseAt;

	/**
	 * Opens the player's inventory and produces real mouse input inside it.
	 *
	 * <p>Everything goes through {@code MouseHandler}'s own methods — the ones GLFW calls — rather than through
	 * either family's hook. That is the whole point: calling a hook would prove only that the hook posts, which
	 * was never in doubt. What is being asked is whether the merged base, on its real input path, reaches a
	 * traditional-Forge mod's listener; so the drill starts where a mouse starts and lets the game do the rest.
	 *
	 * <p>One step per {@link #STEP_TICKS} ticks, never several in a tick. A screen opened and clicked in the same
	 * tick is clicked before {@code init()} has laid out a single slot, so the click lands on nothing and a
	 * working build reads as broken — the same trap the elytra drill documents for equipment.
	 *
	 * <p>The judgement is NOT anything logged here. This only reports what it drove; whether a mod heard it is
	 * the mod's own output, which is the only witness that cannot be satisfied by the kernel talking to itself.
	 */
	private static void screenMouseIfDue(Object minecraft, Object player) {
		int due = Integer.getInteger(SCREEN_MOUSE, 0);
		if (due <= 0 || screenMouseStage > 8 || worldTicks < due) return;
		if (screenMouseStage > 0 && worldTicks < screenMouseAt + STEP_TICKS) return;
		screenMouseAt = worldTicks;
		int step = screenMouseStage++;
		try {
			ClassLoader cl = minecraft.getClass().getClassLoader();
			switch (step) {
				case 0 -> stockASlot(minecraft, cl);
				case 1 -> {
					Class<?> inventory = Class.forName(
							"net.minecraft.client.gui.screens.inventory.InventoryScreen", true, cl);
					Class<?> playerCls = Class.forName("net.minecraft.world.entity.player.Player", false, cl);
					setScreen(minecraft, inventory.getConstructor(playerCls).newInstance(player));
					ForbricLog.info("[Forbric/ClientSmoke] opened a container screen at world tick %d to drive "
							+ "the mouse through it", worldTicks);
				}
				case 2 -> hoverAStockedSlot(minecraft);
				case 3 -> driveTheWheel(minecraft, 1.0);
				case 4 -> census(minecraft, "after one notch up");
				case 5 -> driveTheWheel(minecraft, -1.0);
				case 6 -> census(minecraft, "after one notch down");
				case 7 -> driveTheButtons(minecraft, cl);
				default -> setScreen(minecraft, null);
			}
		} catch (Throwable t) {
			screenMouseStage = 9;
			Caught.warn(t, "[Forbric/ClientSmoke] could not drive the mouse through a container screen");
		}
	}

	/**
	 * Ticks between the drill's steps.
	 *
	 * <p>Not one step per tick. The hovered slot a mod asks the screen for is the one the screen last DREW under
	 * the cursor, so a wheel notch in the same tick as the cursor move is a notch over nothing; and a container
	 * move is server-authoritative, so a census on the tick of the input reads the client's prediction rather
	 * than the outcome. Both of those turn a working build into a silent one.
	 */
	private static final int STEP_TICKS = 6;

	/** Puts the cursor on the first stocked slot and records what the container held before anything was done. */
	private static void hoverAStockedSlot(Object minecraft) throws Exception {
		Object mouse = fieldValue(minecraft, "mouseHandler");
		Object window = minecraft.getClass().getMethod("getWindow").invoke(minecraft);
		Object screen = currentScreen(minecraft);
		if (mouse == null || window == null || screen == null) {
			ForbricLog.warn("[Forbric/ClientSmoke] no mouse handler, window or screen — nothing to hover");
			return;
		}
		double[] stocked = firstStockedSlot(screen, window);
		double x = stocked != null ? stocked[0]
				: ((Number) window.getClass().getMethod("getScreenWidth").invoke(window)).doubleValue() / 2;
		double y = stocked != null ? stocked[1]
				: ((Number) window.getClass().getMethod("getScreenHeight").invoke(window)).doubleValue() / 2;
		setField(mouse, "xpos", x);
		setField(mouse, "ypos", y);
		ForbricLog.info("[Forbric/ClientSmoke] container slots before the wheel: %s", slotCensus(screen));
	}

	/** What the open container holds now, under a label saying what has been done to it. */
	private static void census(Object minecraft, String moment) throws Exception {
		Object screen = currentScreen(minecraft);
		ForbricLog.info("[Forbric/ClientSmoke] container slots %s: %s", moment,
				screen == null ? "<screen already closed>" : slotCensus(screen));
	}

	/** The inventory slot the drill stocks and then hovers. First slot of the main grid, away from the hotbar. */
	private static final int STOCKED_SLOT = 9;

	/**
	 * Puts a known stack into the player's inventory, on the SERVER, before the screen is opened.
	 *
	 * <p>Without it the whole drill is a log-line check: a scroll-wheel mod hovering an EMPTY slot has nothing to
	 * move, so it would print that it was called and then correctly do nothing, and "the bridge works" and "the
	 * mod declined" would be the same output. Stocking a slot gives the run a second, behavioural observable —
	 * the stack is somewhere else afterwards — that no amount of kernel logging can fake.
	 *
	 * <p>Server-side, because a menu click is server-authoritative: the client only predicts, and an item written
	 * into the client's own copy is reverted on the next container sync.
	 */
	private static void stockASlot(Object minecraft, ClassLoader cl) throws Exception {
		Object server = accessible(minecraft.getClass(), "getSingleplayerServer").invoke(minecraft);
		if (server == null) {
			ForbricLog.info("[Forbric/ClientSmoke] no integrated server — hovering whatever the player carries");
			return;
		}
		Object list = accessible(server.getClass(), "getPlayerList").invoke(server);
		java.util.List<?> players = (java.util.List<?>) accessible(list.getClass(), "getPlayers").invoke(list);
		if (players.isEmpty()) return;
		Object p = players.get(0);
		onServer(server, () -> {
			Class<?> stackCls = Class.forName("net.minecraft.world.item.ItemStack", true, cl);
			Object cobble = stackCls.getConstructor(
							Class.forName("net.minecraft.world.level.ItemLike", true, cl), int.class)
					.newInstance(Class.forName("net.minecraft.world.item.Items", true, cl)
							.getField("COBBLESTONE").get(null), 16);
			Object inv = accessible(p.getClass(), "getInventory").invoke(p);
			accessible(inv.getClass(), "setItem", int.class, stackCls).invoke(inv, STOCKED_SLOT, cobble);
			accessible(p.getClass(), "initInventoryMenu").invoke(p);
			ForbricLog.info("[Forbric/ClientSmoke] stocked inventory slot %d with 16 cobblestone for the mouse "
					+ "drill", STOCKED_SLOT);
		});
	}

	/**
	 * Every non-empty slot of the open container menu, as {@code index=item*count}.
	 *
	 * <p>Read twice, side by side, because the question the drill is really asking is whether a MinecraftForge
	 * mod MOVED something — and a mod that is never called and a mod that was called and declined produce the
	 * same silence. Two censuses that differ do not.
	 */
	private static String slotCensus(Object screen) {
		try {
			Object menu = screen.getClass().getMethod("getMenu").invoke(screen);
			java.util.List<?> slots = (java.util.List<?>) fieldValue(menu, "slots");
			if (slots == null) return "<no slots>";
			StringBuilder out = new StringBuilder();
			for (int i = 0; i < slots.size(); i++) {
				Object stack = slots.get(i).getClass().getMethod("getItem").invoke(slots.get(i));
				if ((boolean) stack.getClass().getMethod("isEmpty").invoke(stack)) continue;
				Object item = stack.getClass().getMethod("getItem").invoke(stack);
				int count = (int) stack.getClass().getMethod("getCount").invoke(stack);
				if (out.length() > 0) out.append(' ');
				out.append(i).append('=').append(item).append('*').append(count);
			}
			return out.length() == 0 ? "<all empty>" : out.toString();
		} catch (Throwable t) {
			Caught.expected(t);
			return "<unreadable: " + t + ">";
		}
	}

	/**
	 * The window-pixel centre of the first non-empty slot, or null when the screen shows nothing to hover.
	 *
	 * <p>Converted with the SAME ratio {@code MouseHandler.getScaledXPos} divides back out
	 * ({@code gui * screenWidth / guiScaledWidth}) rather than with {@code getGuiScale()}. They disagree whenever
	 * the window is not an exact multiple of the scale, and the first version of this used the scale and put the
	 * cursor 36 pixels below the bottom of an 480-pixel window — where it hovered nothing, and a working bridge
	 * read as a mod that had declined.
	 */
	private static double[] firstStockedSlot(Object screen, Object window) {
		try {
			Object menu = screen.getClass().getMethod("getMenu").invoke(screen);
			java.util.List<?> slots = (java.util.List<?>) fieldValue(menu, "slots");
			Object left = fieldValue(screen, "leftPos");
			Object top = fieldValue(screen, "topPos");
			if (slots == null || left == null || top == null) return null;
			double wide = ((Number) window.getClass().getMethod("getScreenWidth").invoke(window)).doubleValue()
					/ ((Number) window.getClass().getMethod("getGuiScaledWidth").invoke(window)).doubleValue();
			double high = ((Number) window.getClass().getMethod("getScreenHeight").invoke(window)).doubleValue()
					/ ((Number) window.getClass().getMethod("getGuiScaledHeight").invoke(window)).doubleValue();
			for (Object slot : slots) {
				Object stack = slot.getClass().getMethod("getItem").invoke(slot);
				if ((boolean) stack.getClass().getMethod("isEmpty").invoke(stack)) continue;
				// +8 is the middle of a 16x16 slot, in the screen's own gui pixels.
				double guiX = (Integer) left + (int) fieldValue(slot, "x") + 8;
				double guiY = (Integer) top + (int) fieldValue(slot, "y") + 8;
				ForbricLog.info("[Forbric/ClientSmoke] hovering gui (%.0f, %.0f) — the screen calls that slot %s",
						guiX, guiY, hoveredSlotAt(screen, guiX, guiY));
				return new double[] {guiX * wide, guiY * high};
			}
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] cannot locate a stocked slot");
		}
		return null;
	}

	/**
	 * What the SCREEN thinks is under a pair of gui coordinates.
	 *
	 * <p>Asked of the screen rather than recomputed, because the point of the drill is that a mod hovering a real
	 * slot declines for a real reason. A cursor the harness believes is on a slot and the screen believes is on
	 * nothing is the difference between a measurement and a story.
	 */
	private static String hoveredSlotAt(Object screen, double guiX, double guiY) {
		try {
			Method find = accessible(screen.getClass(), "getHoveredSlot", double.class, double.class);
			Object slot = find.invoke(screen, guiX, guiY);
			if (slot == null) return "<nothing>";
			Object stack = slot.getClass().getMethod("getItem").invoke(slot);
			return fieldValue(slot, "index") + " holding " + stack;
		} catch (Throwable t) {
			Caught.expected(t);
			return "<unaskable: " + t + ">";
		}
	}

	/**
	 * ONE wheel notch over the slot the cursor is already on, and nothing else.
	 *
	 * <p>Alone on purpose. Pressing a mouse button over a slot picks the stack up in VANILLA, with or without any
	 * bridge, so a census that spanned a click would change on both arms of the differential and prove nothing.
	 * The wheel over a slot moves nothing in vanilla, so a stack that is somewhere else afterwards was moved by a
	 * mod — which is the only claim here worth making.
	 *
	 * <p>Driven BOTH ways across the drill, because the two directions are not symmetric: one pushes the hovered
	 * stack into the other inventory and the other pulls from it, and which is which depends on the mod's own
	 * direction settings and on where the other inventory sits on screen. Scrolling only the pulling way over a
	 * full slot with an empty partner moves nothing, correctly — and reads exactly like a bridge that is not
	 * there.
	 */
	private static void driveTheWheel(Object minecraft, double notch) throws Exception {
		Object mouse = fieldValue(minecraft, "mouseHandler");
		Object window = minecraft.getClass().getMethod("getWindow").invoke(minecraft);
		if (mouse == null || window == null) return;
		long handle = (long) window.getClass().getMethod("handle").invoke(window);
		Method onScroll = mouse.getClass().getDeclaredMethod("onScroll", long.class, double.class, double.class);
		onScroll.setAccessible(true);
		onScroll.invoke(mouse, handle, 0.0, notch);
		ForbricLog.info("[Forbric/ClientSmoke] drove one wheel notch (%+.0f) through MouseHandler.onScroll", notch);
	}

	/**
	 * One press, one drag and one release, at the same place.
	 *
	 * <p>These three are here for the log, not for the census: their MinecraftForge listeners are what the run is
	 * asking about, and a mod that is called prints that it was called. What they do to the container is not a
	 * differential, for the reason {@link #driveTheWheel} gives.
	 *
	 * <p>{@code handleAccumulatedMovement} refuses to do anything unless the window is focused, and an unattended
	 * run is usually not, so the flag is set for the length of the drag and put back afterwards. Leaving it set
	 * would change how the rest of the run behaves, which is exactly the kind of measurement that quietly alters
	 * what it measures.
	 */
	private static void driveTheButtons(Object minecraft, ClassLoader cl) throws Exception {
		Object mouse = fieldValue(minecraft, "mouseHandler");
		Object window = minecraft.getClass().getMethod("getWindow").invoke(minecraft);
		if (mouse == null || window == null) return;
		long handle = (long) window.getClass().getMethod("handle").invoke(window);

		Class<?> infoCls = Class.forName("net.minecraft.client.input.MouseButtonInfo", true, cl);
		Object left = infoCls.getConstructor(int.class, int.class).newInstance(0, 0);
		Method onButton = mouse.getClass().getDeclaredMethod("onButton", long.class, infoCls, int.class);
		onButton.setAccessible(true);

		onButton.invoke(mouse, handle, left, 1);

		boolean focused = (boolean) window.getClass().getMethod("isFocused").invoke(window);
		if (!focused) setField(window, "focused", true);
		setField(mouse, "accumulatedDX", 6.0);
		setField(mouse, "accumulatedDY", 3.0);
		mouse.getClass().getMethod("handleAccumulatedMovement").invoke(mouse);
		if (!focused) setField(window, "focused", false);

		onButton.invoke(mouse, handle, left, 0);
		ForbricLog.info("[Forbric/ClientSmoke] drove press, drag and release through MouseHandler; whether a "
				+ "MinecraftForge mod heard them is that mod's own log");
	}

	/** Writes one field anywhere up the hierarchy. Same search as {@link #fieldValue}, the other way round. */
	private static void setField(Object owner, String name, Object value) {
		for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
			try {
				Field field = c.getDeclaredField(name);
				field.setAccessible(true);
				field.set(owner, value);
				return;
			} catch (NoSuchFieldException keepLooking) {
				Caught.expected(keepLooking);
				continue;
			} catch (ReflectiveOperationException | RuntimeException unwritable) {
				Caught.warn(unwritable, "[Forbric/ClientSmoke] cannot write %s", name);
				return;
			}
		}
	}

	private static int carryStage;
	private static int carryAt;
	/** Ticks the step that just ran asks for before the next one. */
	private static int carryWait;
	/** The chest's block and the pig's spawn block, two blocks either side of where the player stands. */
	private static int[] carryChestAt;
	private static int[] carryPigAt;
	private static final java.util.Map<String, Boolean> carryVerdict = new java.util.LinkedHashMap<>();

	/**
	 * Ticks between the carry drill's steps. Each one waits on a round trip — a key the client must notice on its
	 * own tick, a packet the server must handle on its tick, a block change that must come back — and a step that
	 * runs before the previous one has landed measures the harness, not the game.
	 */
	private static final int CARRY_STEP_TICKS = 10;
	/**
	 * How long the right button stays down: a click, not a hold. Held for a whole step, the game repeats the use
	 * every four ticks, and the repeat lands on whatever the first use just made — the chest that was put down gets
	 * opened, the open screen releases every key, and the rest of the drill runs with nothing held.
	 */
	private static final int CARRY_CLICK_TICKS = 2;
	private static final int CARRY_LAST_STEP = 20;
	/**
	 * Where the stage is built. High and fixed, because the world is whatever the fixture holds — the first stage
	 * built where the player stood was under water, and a chest picked up there leaves water behind, not air.
	 */
	private static final int CARRY_STAGE_Y = 180;
	private static final int GLFW_KEY_LEFT_SHIFT = 340;
	private static final int GLFW_MOUSE_BUTTON_RIGHT = 1;
	private static final int GLFW_MOD_SHIFT = 1;
	/** What the drill leaves in the chest, so "put back" can also mean "put back with what was inside it". */
	private static final int CARRY_DIAMONDS = 7;

	/**
	 * Sneak, empty hands, right-click a chest; right-click the floor; the same for a pig. See {@link #CARRY}.
	 *
	 * <p>The verdict is read from the world on the SERVER's own thread: a chest that is gone from its block after the
	 * first click and back — with the diamonds still in it — after the second, and a pig that is gone and then back.
	 * Carry On's own data is logged beside it so a failure says which link broke, but it is evidence, not the
	 * verdict: a mod that SAYS it is carrying while the chest still stands has not done what a player wanted.
	 */
	private static void carryIfDue(Object minecraft, Object player) {
		int due = Integer.getInteger(CARRY, 0);
		if (due <= 0 || carryStage > CARRY_LAST_STEP || worldTicks < due) return;
		if (carryStage > 0 && worldTicks < carryAt + carryWait) return;
		carryAt = worldTicks;
		carryWait = CARRY_STEP_TICKS;
		int step = carryStage++;
		try {
			ClassLoader cl = minecraft.getClass().getClassLoader();
			Object server = accessible(minecraft.getClass(), "getSingleplayerServer").invoke(minecraft);
			if (server == null) {
				carryStage = CARRY_LAST_STEP + 1;
				ForbricLog.info("[Forbric/ClientSmoke] carry: no integrated server — the drill reads the world there");
				return;
			}
			Object list = accessible(server.getClass(), "getPlayerList").invoke(server);
			java.util.List<?> players = (java.util.List<?>) accessible(list.getClass(), "getPlayers").invoke(list);
			if (players.isEmpty()) return;
			Object p = players.get(0);
			switch (step) {
				case 0 -> onServer(server, () -> buildCarryStage(p, cl));
				case 1 -> pressKey(minecraft, cl, GLFW_KEY_LEFT_SHIFT, true);
				case 2 -> aimAt(player, carryChestAt[0] + 0.5, carryChestAt[1] + 0.5, carryChestAt[2] + 0.5);
				case 3 -> {
					carryReport(minecraft, server, p, player, cl, "holding sneak, before the click",
							carryWorld(server, p, cl));
					clickRight(minecraft, cl, true);
				}
				case 4 -> clickRight(minecraft, cl, false);
				case 5 -> {
					String world = carryWorld(server, p, cl);
					// Read, and not a chest: an unreadable world must not count as a chest that left.
					carryVerdict.put("chest picked up", world.startsWith("chestBlock=")
							&& !world.contains("chestBlock=minecraft:chest"));
					carryReport(minecraft, server, p, player, cl, "after sneak-right-clicking the chest", world);
					screenshotNow(minecraft, "carrying the chest, first person");
				}
				case 6 -> cameraType(minecraft, cl, "THIRD_PERSON_FRONT");
				case 7 -> {
					screenshotNow(minecraft, "carrying the chest, third person");
					cameraType(minecraft, cl, "FIRST_PERSON");
					pressKey(minecraft, cl, GLFW_KEY_LEFT_SHIFT, false);
				}
				// The floor's top face, under where the chest stood: Carry On puts a block on the face it is given.
				case 8 -> aimAt(player, carryChestAt[0] + 0.5, carryChestAt[1], carryChestAt[2] + 0.5);
				case 9 -> {
					// Looking at where the chest stood, a step after turning there so the frame shows the turn: a chest
					// the client still draws here is one the server took and never told it about.
					screenshotNow(minecraft, "the floor where the chest stood, before putting it down");
					clickRight(minecraft, cl, true);
				}
				case 10 -> clickRight(minecraft, cl, false);
				case 11 -> {
					String world = carryWorld(server, p, cl);
					// Only after a pickup: a chest that never left its block is not a chest that was put back.
					boolean pickedUp = Boolean.TRUE.equals(carryVerdict.get("chest picked up"));
					carryVerdict.put("chest put back", pickedUp && world.contains("chestBlock=minecraft:chest"));
					carryVerdict.put("with its contents", pickedUp
							&& world.contains("chestSlot0=" + CARRY_DIAMONDS + " minecraft:diamond"));
					carryReport(minecraft, server, p, player, cl, "after right-clicking the floor", world);
					onServer(server, () -> spawnCarryPig(p, cl));
					pressKey(minecraft, cl, GLFW_KEY_LEFT_SHIFT, true);
				}
				// A pig is 0.9 tall: its middle, not its feet, or the ray misses it over a slab of floor.
				case 12 -> aimAt(player, carryPigAt[0] + 0.5, carryPigAt[1] + 0.45, carryPigAt[2] + 0.5);
				case 13 -> clickRight(minecraft, cl, true);
				case 14 -> clickRight(minecraft, cl, false);
				case 15 -> {
					String world = carryWorld(server, p, cl);
					carryVerdict.put("pig picked up", world.contains("pigsNearby=0"));
					carryReport(minecraft, server, p, player, cl, "after sneak-right-clicking the pig", world);
					screenshotNow(minecraft, "carrying the pig, first person");
				}
				case 16 -> pressKey(minecraft, cl, GLFW_KEY_LEFT_SHIFT, false);
				case 17 -> aimAt(player, carryPigAt[0] + 0.5, carryPigAt[1], carryPigAt[2] + 0.5);
				case 18 -> clickRight(minecraft, cl, true);
				case 19 -> clickRight(minecraft, cl, false);
				default -> {
					String world = carryWorld(server, p, cl);
					carryVerdict.put("pig put back", Boolean.TRUE.equals(carryVerdict.get("pig picked up"))
							&& world.contains("pigsNearby=1"));
					carryReport(minecraft, server, p, player, cl, "after right-clicking the floor", world);
					StringBuilder verdict = new StringBuilder();
					carryVerdict.forEach((what, held) -> verdict.append(verdict.length() == 0 ? "" : ", ")
							.append(what).append('=').append(held));
					ForbricLog.info("[Forbric/ClientSmoke] carry drill result: %s", verdict);
				}
			}
		} catch (Throwable t) {
			carryStage = CARRY_LAST_STEP + 1;
			Caught.warn(t, "[Forbric/ClientSmoke] the carry drill could not run step " + step);
		}
	}

	/**
	 * A stone floor in the open air with the player on it, a chest with diamonds in it two blocks ahead, and the
	 * pig's block two blocks behind. The world is whatever the last run left, so the stage is built rather than
	 * assumed: a hill, a tree or a lake between the player and the chest makes the click land on something else, and
	 * that reads as the mod declining.
	 */
	private static void buildCarryStage(Object p, ClassLoader cl) throws Exception {
		Class<?> posCls = Class.forName("net.minecraft.core.BlockPos", true, cl);
		Class<?> stateCls = Class.forName("net.minecraft.world.level.block.state.BlockState", true, cl);
		Class<?> blocks = Class.forName("net.minecraft.world.level.block.Blocks", true, cl);
		Class<?> stackCls = Class.forName("net.minecraft.world.item.ItemStack", true, cl);
		java.lang.reflect.Constructor<?> at = posCls.getConstructor(int.class, int.class, int.class);
		Object level = accessible(p.getClass(), "level").invoke(p);
		Method setBlock = accessible(level.getClass(), "setBlockAndUpdate", posCls, stateCls);
		Object feet = accessible(p.getClass(), "blockPosition").invoke(p);
		int x = (int) accessible(feet.getClass(), "getX").invoke(feet);
		int y = CARRY_STAGE_Y;
		int z = (int) accessible(feet.getClass(), "getZ").invoke(feet);
		Object stone = defaultState(blocks, "STONE");
		Object air = defaultState(blocks, "AIR");
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				setBlock.invoke(level, at.newInstance(x + dx, y - 1, z + dz), stone);
				for (int dy = 0; dy <= 2; dy++) setBlock.invoke(level, at.newInstance(x + dx, y + dy, z + dz), air);
			}
		}
		// teleportTo, not setPos: the client must be told, or it keeps sending where it thinks it is.
		accessible(p.getClass(), "teleportTo", double.class, double.class, double.class).invoke(p, x + 0.5, (double) y,
				z + 0.5);
		carryChestAt = new int[] {x, y, z + 2};
		carryPigAt = new int[] {x, y, z - 2};
		Object chestPos = at.newInstance(carryChestAt[0], carryChestAt[1], carryChestAt[2]);
		setBlock.invoke(level, chestPos, defaultState(blocks, "CHEST"));
		Object chest = accessible(level.getClass(), "getBlockEntity", posCls).invoke(level, chestPos);
		Object diamonds = stackCls.getConstructor(Class.forName("net.minecraft.world.level.ItemLike", true, cl), int.class)
				.newInstance(Class.forName("net.minecraft.world.item.Items", true, cl).getField("DIAMOND").get(null),
						CARRY_DIAMONDS);
		accessible(chest.getClass(), "setItem", int.class, stackCls).invoke(chest, 0, diamonds);
		// Carry On picks up only with BOTH hands empty, which is the rule the gesture exists for; the run's world
		// may have left something in them.
		Class<?> handCls = Class.forName("net.minecraft.world.InteractionHand", true, cl);
		Object empty = stackCls.getField("EMPTY").get(null);
		for (Object hand : handCls.getEnumConstants()) {
			accessible(p.getClass(), "setItemInHand", handCls, stackCls).invoke(p, hand, empty);
		}
		ForbricLog.info("[Forbric/ClientSmoke] carry stage: a chest holding %d diamonds at %d %d %d, the pig's "
				+ "block at %d %d %d, the player moved to %d %d %d with empty hands", CARRY_DIAMONDS, carryChestAt[0],
				carryChestAt[1], carryChestAt[2], carryPigAt[0], carryPigAt[1], carryPigAt[2], x, y, z);
	}

	private static Object defaultState(Class<?> blocks, String name) throws Exception {
		Object block = blocks.getField(name).get(null);
		return accessible(block.getClass(), "defaultBlockState").invoke(block);
	}

	/** 26.2 moved the entity type constants out of {@code EntityType} into {@code EntityTypes}; either will do. */
	private static Object pigType(ClassLoader cl) throws Exception {
		try {
			return Class.forName("net.minecraft.world.entity.EntityTypes", true, cl).getField("PIG").get(null);
		} catch (ClassNotFoundException | NoSuchFieldException older) {
			Caught.expected(older);
			return Class.forName("net.minecraft.world.entity.EntityType", true, cl).getField("PIG").get(null);
		}
	}

	/** A pig that stays where it was put: without AI it does not wander off between the click and the count. */
	private static void spawnCarryPig(Object p, ClassLoader cl) throws Exception {
		Class<?> posCls = Class.forName("net.minecraft.core.BlockPos", true, cl);
		Class<?> typeCls = Class.forName("net.minecraft.world.entity.EntityType", true, cl);
		Class<?> reasonCls = Class.forName("net.minecraft.world.entity.EntitySpawnReason", true, cl);
		Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel", true, cl);
		Object level = accessible(p.getClass(), "level").invoke(p);
		Object pos = posCls.getConstructor(int.class, int.class, int.class)
				.newInstance(carryPigAt[0], carryPigAt[1], carryPigAt[2]);
		@SuppressWarnings({"unchecked", "rawtypes"})
		Object reason = Enum.valueOf((Class) reasonCls, "COMMAND");
		Object pig = accessible(typeCls, "spawn", serverLevel, posCls, reasonCls)
				.invoke(pigType(cl), level, pos, reason);
		if (pig != null) {
			Class.forName("net.minecraft.world.entity.Mob", true, cl).getMethod("setNoAi", boolean.class).invoke(pig, true);
		}
		ForbricLog.info("[Forbric/ClientSmoke] carry stage: spawned %s", pig);
	}

	/** One key through {@code KeyboardHandler.keyPress}, the method GLFW's key callback calls. */
	private static void pressKey(Object minecraft, ClassLoader cl, int key, boolean down) throws Exception {
		Object keyboard = fieldValue(minecraft, "keyboardHandler");
		Object window = minecraft.getClass().getMethod("getWindow").invoke(minecraft);
		long handle = (long) window.getClass().getMethod("handle").invoke(window);
		Class<?> eventCls = Class.forName("net.minecraft.client.input.KeyEvent", true, cl);
		// What GLFW itself reports: pressing shift carries the shift modifier, letting it go does not.
		Object event = eventCls.getConstructor(int.class, int.class, int.class)
				.newInstance(key, 0, down && key == GLFW_KEY_LEFT_SHIFT ? GLFW_MOD_SHIFT : 0);
		Method keyPress = keyboard.getClass().getDeclaredMethod("keyPress", long.class, int.class, eventCls);
		keyPress.setAccessible(true);
		keyPress.invoke(keyboard, handle, down ? 1 : 0, event);
		heldShift = key == GLFW_KEY_LEFT_SHIFT ? down : heldShift;
		ForbricLog.info("[Forbric/ClientSmoke] carry: key %d %s through KeyboardHandler.keyPress", key,
				down ? "pressed" : "released");
	}

	private static boolean heldShift;

	/** The right mouse button through {@code MouseHandler.onButton}, with the modifiers a held shift would add. */
	private static void clickRight(Object minecraft, ClassLoader cl, boolean down) throws Exception {
		Object mouse = fieldValue(minecraft, "mouseHandler");
		Object window = minecraft.getClass().getMethod("getWindow").invoke(minecraft);
		long handle = (long) window.getClass().getMethod("handle").invoke(window);
		Class<?> infoCls = Class.forName("net.minecraft.client.input.MouseButtonInfo", true, cl);
		Object right = infoCls.getConstructor(int.class, int.class)
				.newInstance(GLFW_MOUSE_BUTTON_RIGHT, heldShift ? GLFW_MOD_SHIFT : 0);
		Method onButton = mouse.getClass().getDeclaredMethod("onButton", long.class, infoCls, int.class);
		onButton.setAccessible(true);
		if (down) {
			ForbricLog.info("[Forbric/ClientSmoke] carry: right button down through MouseHandler.onButton, the "
					+ "crosshair on %s", fieldValue(minecraft, "hitResult") == null ? "<nothing>"
					: describeHit(fieldValue(minecraft, "hitResult")));
		}
		onButton.invoke(mouse, handle, right, down ? 1 : 0);
		if (down) carryWait = CARRY_CLICK_TICKS;
	}

	private static String describeHit(Object hit) {
		Object type = invokeQuietly(hit, "getType");
		Object where = invokeQuietly(hit, "getBlockPos");
		Object entity = invokeQuietly(hit, "getEntity");
		return type + (where != null ? " " + where : "") + (entity != null ? " " + entity : "");
	}

	private static Object invokeQuietly(Object owner, String name) {
		try {
			return accessible(owner.getClass(), name).invoke(owner);
		} catch (Throwable t) {
			Caught.expected(t);
			return null;
		}
	}

	/**
	 * Turns the client's player to look at a point. The client is the side that decides what was clicked, from
	 * its own view, so that is the rotation that has to be right; the server only checks the result is in reach.
	 */
	private static void aimAt(Object player, double x, double y, double z) throws Exception {
		Object eye = accessible(player.getClass(), "getEyePosition").invoke(player);
		double dx = x - (double) fieldValue(eye, "x");
		double dy = y - (double) fieldValue(eye, "y");
		double dz = z - (double) fieldValue(eye, "z");
		float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		setRotation(player, yaw, pitch);
		// The previous tick's rotation too: anything that interpolates between the two would otherwise look halfway.
		setField(player, "yRotO", yaw);
		setField(player, "xRotO", pitch);
	}

	private static void cameraType(Object minecraft, ClassLoader cl, String type) throws Exception {
		Object options = fieldValue(minecraft, "options");
		Class<?> cameraCls = Class.forName("net.minecraft.client.CameraType", true, cl);
		@SuppressWarnings({"unchecked", "rawtypes"})
		Object value = Enum.valueOf((Class) cameraCls, type);
		accessible(options.getClass(), "setCameraType", cameraCls).invoke(options, value);
	}

	private static void screenshotNow(Object minecraft, String what) {
		try {
			Class<?> screenshot = Class.forName("net.minecraft.client.Screenshot", true,
					minecraft.getClass().getClassLoader());
			screenshot.getMethod("grab", minecraft.getClass(), boolean.class).invoke(null, minecraft, false);
			ForbricLog.info("[Forbric/ClientSmoke] screenshot requested at world tick %d: %s", worldTicks, what);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] could not take a screenshot (%s)", what);
		}
	}

	/** The world facts the verdict is made of, read on the server thread. */
	private static String carryWorld(Object server, Object p, ClassLoader cl) throws Exception {
		String[] out = {"<unread>"};
		onServer(server, () -> {
			Class<?> posCls = Class.forName("net.minecraft.core.BlockPos", true, cl);
			Object level = accessible(p.getClass(), "level").invoke(p);
			Object chestPos = posCls.getConstructor(int.class, int.class, int.class)
					.newInstance(carryChestAt[0], carryChestAt[1], carryChestAt[2]);
			Object state = accessible(level.getClass(), "getBlockState", posCls).invoke(level, chestPos);
			Object block = accessible(state.getClass(), "getBlock").invoke(state);
			Object blockRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, cl)
					.getField("BLOCK").get(null);
			// Through the Registry interface, not the implementation: the merged registry class names Fabric
			// registry-sync types in its signatures, and listing its declared methods links them.
			Object name = Class.forName("net.minecraft.core.Registry", true, cl).getMethod("getKey", Object.class)
					.invoke(blockRegistry, block);
			String slot0 = "-";
			Object chest = accessible(level.getClass(), "getBlockEntity", posCls).invoke(level, chestPos);
			if (chest != null) {
				try {
					Object stack = accessible(chest.getClass(), "getItem", int.class).invoke(chest, 0);
					slot0 = String.valueOf(stack);
				} catch (NoSuchMethodException notAContainer) {
					Caught.expected(notAContainer);
					slot0 = "<not a container>";
				}
			}
			Class<?> entityCls = Class.forName("net.minecraft.world.entity.Entity", true, cl);
			Class<?> aabbCls = Class.forName("net.minecraft.world.phys.AABB", true, cl);
			Object box = aabbCls.getConstructor(double.class, double.class, double.class, double.class, double.class,
					double.class).newInstance(carryPigAt[0] - 1.5, carryPigAt[1] - 1.0, carryPigAt[2] - 1.5,
					carryPigAt[0] + 2.5, carryPigAt[1] + 3.0, carryPigAt[2] + 2.5);
			Object pigType = pigType(cl);
			int pigs = 0;
			for (Object e : (java.util.List<?>) accessible(level.getClass(), "getEntitiesOfClass", Class.class, aabbCls)
					.invoke(level, entityCls, box)) {
				if (entityCls.getMethod("getType").invoke(e) == pigType) pigs++;
			}
			out[0] = "chestBlock=" + name + " chestSlot0=" + slot0 + " pigsNearby=" + pigs;
		});
		return out[0];
	}

	/**
	 * One line per drill moment: the world, then Carry On's own state on each side — the server's (does it believe
	 * the carry key is held, is it carrying), and the client's (is its key mapping down, what did it last send).
	 * Which of those flips and which does not is how a silent failure is placed on one link of the chain.
	 */
	private static void carryReport(Object minecraft, Object server, Object p, Object player, ClassLoader cl,
			String moment, String world) throws Exception {
		String[] serverSide = {"<unread>"};
		onServer(server, () -> serverSide[0] = carryOnState(p, cl) + " sneaking="
				+ accessible(p.getClass(), "isShiftKeyDown").invoke(p));
		Object options = fieldValue(minecraft, "options");
		Object keyShift = options == null ? null : fieldValue(options, "keyShift");
		String carryKey;
		try {
			Object mapping = Class.forName("tschipp.carryon.client.keybinds.CarryOnKeybinds", true, cl)
					.getField("carryKey").get(null);
			carryKey = mapping == null ? "<never registered>" : String.valueOf(mapping.getClass().getMethod("isDown")
					.invoke(mapping));
		} catch (ClassNotFoundException absent) {
			Caught.expected(absent);
			carryKey = "<no Carry On>";
		}
		Object screen = currentScreen(minecraft);
		ForbricLog.info("[Forbric/ClientSmoke] carry %s: %s | server: %s | client: chestBlock=%s carryKey.isDown=%s "
						+ "keyShift.isDown=%s %s screen=%s", moment, world, serverSide[0], clientChestBlock(minecraft, cl),
				carryKey, keyShift == null ? "?" : keyShift.getClass().getMethod("isDown").invoke(keyShift),
				carryOnState(player, cl), screen == null ? "none" : screen.getClass().getSimpleName());
	}

	/**
	 * The block the CLIENT's level holds where the chest was. Beside the server's answer it separates "the mod did
	 * not pick it up" from "the mod did and the client was never told" — a ghost chest is the second.
	 */
	private static String clientChestBlock(Object minecraft, ClassLoader cl) {
		try {
			Object level = fieldValue(minecraft, "level");
			Class<?> posCls = Class.forName("net.minecraft.core.BlockPos", true, cl);
			Object pos = posCls.getConstructor(int.class, int.class, int.class)
					.newInstance(carryChestAt[0], carryChestAt[1], carryChestAt[2]);
			Object state = Class.forName("net.minecraft.world.level.BlockGetter", true, cl)
					.getMethod("getBlockState", posCls).invoke(level, pos);
			Object block = accessible(state.getClass(), "getBlock").invoke(state);
			Object blocks = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, cl)
					.getField("BLOCK").get(null);
			return String.valueOf(Class.forName("net.minecraft.core.Registry", true, cl)
					.getMethod("getKey", Object.class).invoke(blocks, block));
		} catch (Throwable t) {
			Caught.expected(t);
			return "<unreadable: " + t + ">";
		}
	}

	/** Carry On's own record for one player, read through its own manager — or that it is not installed. */
	private static String carryOnState(Object player, ClassLoader cl) {
		try {
			Class<?> manager = Class.forName("tschipp.carryon.common.carry.CarryOnDataManager", true, cl);
			Object data = accessible(manager, "getCarryData", Class.forName("net.minecraft.world.entity.player.Player",
					true, cl)).invoke(null, player);
			return "carrying=" + accessible(data.getClass(), "isCarrying").invoke(data)
					+ " type=" + accessible(data.getClass(), "getType").invoke(data)
					+ " keyPressed=" + accessible(data.getClass(), "isKeyPressed").invoke(data);
		} catch (ClassNotFoundException absent) {
			Caught.expected(absent);
			return "<no Carry On>";
		} catch (Throwable t) {
			Caught.expected(t);
			return "<unreadable: " + (t instanceof java.lang.reflect.InvocationTargetException i && i.getCause() != null
					? i.getCause() : t) + ">";
		}
	}

	private static int elytraStage;
	private static int elytraEquippedAt;

	/**
	 * Equips an elytra, drops the player into the air and asks whether they can glide.
	 *
	 * <p>{@code canGlide()} is the exact predicate that was false: the merge took NeoForge's version, which
	 * decides gliding from the {@code neoforge:gliding_flight} attribute alone, and vanilla's
	 * {@code forEachModifier}, which never posts the event that sets it. So this reports THREE things, because
	 * they fail separately and only the last one is the player's complaint: the attribute reaching the entity,
	 * {@code canGlide} agreeing, and fall-flying actually starting.
	 *
	 * <p>Split across ticks on purpose. Equipment changes are collected on the entity's own tick, so an
	 * attribute asked for in the same tick it was equipped is asked before anything could have applied it — and
	 * would read as broken on a working build.
	 */
	private static double elytraStartX;
	private static double elytraStartY;
	private static double elytraStartZ;
	private static boolean elytraStarted;
	private static boolean elytraEnteredFlight;
	private static String elytraSample;

	/**
	 * The whole elytra flight, in the order a player experiences it: equip, jump off, enter flight, STAY in it.
	 *
	 * <p>Staying in it is the half that was broken and the half a single predicate cannot show. {@code canGlide}
	 * being false did not stop flight from starting — {@code updateFallFlying} runs every tick and clears the
	 * flag when it is false, so the player got a moment of flight and was yanked back. A check that only asked
	 * whether flight STARTED would have passed on the broken build.
	 *
	 * <p>Everything is done to the SERVER's player and on the server's thread. Equipment attributes are applied
	 * server-side ({@code detectEquipmentUpdates} casts the level to {@code ServerLevel}) and flight is
	 * server-authoritative; the client only predicts, which is why the client's own {@code isFallFlying} reads
	 * true even on a build where the server refuses.
	 */
	private static void elytraCheck(Object minecraft, Object player) {
		int due = Integer.getInteger(ELYTRA, 0);
		if (due <= 0 || elytraStage > 3 || worldTicks < due) return;
		try {
			ClassLoader cl = minecraft.getClass().getClassLoader();
			Object server = accessible(minecraft.getClass(), "getSingleplayerServer").invoke(minecraft);
			if (server == null) {
				elytraStage = 4;
				ForbricLog.info("[Forbric/ClientSmoke] elytra: no integrated server — this is a server-side check");
				return;
			}
			Object list = accessible(server.getClass(), "getPlayerList").invoke(server);
			java.util.List<?> players = (java.util.List<?>) accessible(list.getClass(), "getPlayers").invoke(list);
			if (players.isEmpty()) return;
			Object p = players.get(0);

			switch (elytraStage) {
				case 0 -> {
					elytraStage = 1;
					elytraEquippedAt = worldTicks;
					onServer(server, () -> {
						Class<?> stackCls = Class.forName("net.minecraft.world.item.ItemStack", true, cl);
						Class<?> slotCls = Class.forName("net.minecraft.world.entity.EquipmentSlot", true, cl);
						Object elytra = stackCls.getConstructor(
										Class.forName("net.minecraft.world.level.ItemLike", true, cl))
								.newInstance(Class.forName("net.minecraft.world.item.Items", true, cl)
										.getField("ELYTRA").get(null));
						accessible(p.getClass(), "setItemSlot", slotCls, stackCls).invoke(p,
								Enum.valueOf(slotCls.asSubclass(Enum.class), "CHEST"), elytra);
						// teleportTo, not setPos: setPos moves the server's entity without telling the client,
						// and the client then keeps sending the position it still believes in, so the server
						// snaps back and the player never actually falls. The first version of this measured
						// zero movement for exactly that reason.
						// An ABSOLUTE altitude, not a relative one. The world persists between runs, so "+80
						// blocks" stacked up run after run until the player sat at y=892, far above the world and
						// no longer ticking — it had velocity and never moved.
						accessible(p.getClass(), "teleportTo", double.class, double.class, double.class)
								.invoke(p, posOf(p, "getX"), Double.valueOf(
										Double.parseDouble(System.getProperty(ELYTRA_ALTITUDE, "200"))),
										posOf(p, "getZ"));
						// A clean slate: if the flag is already set, "did flight start" cannot be asked.
						accessible(p.getClass(), "setSharedFlag", int.class, boolean.class)
								.invoke(p, 7, Boolean.FALSE);
						elytraStartX = posOf(p, "getX");
						elytraStartY = posOf(p, "getY");
						elytraStartZ = posOf(p, "getZ");
					});
					ForbricLog.info("[Forbric/ClientSmoke] elytra 1/3: equipped, 80 blocks up, flight flag cleared");
				}
				case 1 -> {
					// Long enough for the server to collect the equipment change and apply the attribute.
					if (worldTicks < elytraEquippedAt + 10) return;
					elytraStage = 2;
					elytraEquippedAt = worldTicks;
					onServer(server, () -> {
						elytraStarted = (Boolean) accessible(p.getClass(), "tryToStartFallFlying").invoke(p);
					});
					// The CLIENT's position is the one that moves. In singleplayer the server accepts the
					// client's position packets rather than simulating the player, so the server entity's own
					// motion is zero however well the glide is going — the first version of this measured that
					// and called a working glide a fall.
					elytraStartX = posOf(player, "getX");
					elytraStartY = posOf(player, "getY");
					elytraStartZ = posOf(player, "getZ");
					ForbricLog.info("[Forbric/ClientSmoke] elytra 2/3: asked to start flight");
				}
				case 2 -> {
					if (elytraSample == null) {
						elytraSample = String.valueOf(
								accessible(player.getClass(), "getDeltaMovement").invoke(player));
					}
					if (worldTicks < elytraEquippedAt + 2) return;
					elytraStage = 3;
					elytraEquippedAt = worldTicks;
					onServer(server, () -> {
						elytraEnteredFlight = (Boolean) accessible(p.getClass(), "isFallFlying").invoke(p);
					});
				}
				default -> {
					// The sustain window. updateFallFlying gets ~40 chances to cancel it in here.
					if (worldTicks < elytraEquippedAt + 40) return;
					elytraStage = 4;
					boolean sustained = (Boolean) accessible(p.getClass(), "isFallFlying").invoke(p);
					double dx = posOf(player, "getX") - elytraStartX;
					double dz = posOf(player, "getZ") - elytraStartZ;
					double horizontal = Math.sqrt(dx * dx + dz * dz);
					double fell = elytraStartY - posOf(player, "getY");
					ForbricLog.info("[Forbric/ClientSmoke] elytra 3/3: started=%s entered=%s sustained=%s "
							+ "glidedHorizontally=%.1f fell=%.1f attribute=%s canGlide=%s",
							elytraStarted, elytraEnteredFlight, sustained, horizontal, fell,
							glidingAttribute(p, cl), accessible(p.getClass(), "canGlide").invoke(p));
					ForbricLog.info("[Forbric/ClientSmoke] elytra positions: start=%.1f,%.1f,%.1f now=%.1f,%.1f,%.1f"
							+ " onGround=%s motion=%s", elytraStartX, elytraStartY, elytraStartZ, posOf(p, "getX"),
							posOf(p, "getY"), posOf(p, "getZ"),
							accessible(p.getClass(), "onGround").invoke(p),
							accessible(p.getClass(), "getDeltaMovement").invoke(p));
					ForbricLog.info("[Forbric/ClientSmoke] elytra motion early=%s late=%s", elytraSample,
							accessible(player.getClass(), "getDeltaMovement").invoke(player));
					ForbricLog.info("[Forbric/ClientSmoke] elytra client player: %.1f,%.1f,%.1f motion=%s "
							+ "fallFlying=%s paused=%s", posOf(player, "getX"), posOf(player, "getY"),
							posOf(player, "getZ"), accessible(player.getClass(), "getDeltaMovement").invoke(player),
							accessible(player.getClass(), "isFallFlying").invoke(player),
							accessible(minecraft.getClass(), "isPaused").invoke(minecraft));
				}
			}
		} catch (Throwable t) {
			elytraStage = 4;
			Caught.warn(t, "[Forbric/ClientSmoke] could not check elytra flight");
		}
	}

	/** Anything that touches the entity goes on the server's own thread; a race that usually works is worse. */
	private static void onServer(Object server, ThrowingRunnable body) throws Exception {
		java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
		accessible(server.getClass(), "execute", Runnable.class).invoke(server, (Runnable) () -> {
			try {
				body.run();
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/ClientSmoke] a drill step failed on the server thread");
			} finally {
				done.countDown();
			}
		});
		done.await(5, java.util.concurrent.TimeUnit.SECONDS);
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	/** Invokes a no-arg method on the player and reports what happened, root cause first. */
	private static String force(Object player, String name) {
		try {
			accessible(player.getClass(), name).invoke(player);
			return "ok";
		} catch (Throwable t) {
			Caught.expected(t);
			return String.valueOf(t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null
					? t.getCause() : t);
		}
	}

	/** The collector, with a previous-equipment map that makes every slot count as changed. */
	private static String forceCollect(Object player, ClassLoader cl) {
		try {
			Class<?> slots = Class.forName("net.minecraft.world.entity.EquipmentSlot", true, cl);
			Object emptyStack = Class.forName("net.minecraft.world.item.ItemStack", true, cl)
					.getField("EMPTY").get(null);
			@SuppressWarnings({"unchecked", "rawtypes"})
			java.util.Map<Object, Object> previous = new java.util.EnumMap(slots.asSubclass(Enum.class));
			for (Object slot : slots.getEnumConstants()) previous.put(slot, emptyStack);
			accessible(player.getClass(), "collectEquipmentChanges", java.util.Map.class).invoke(player, previous);
			return "ok";
		} catch (Throwable t) {
			Caught.expected(t);
			return String.valueOf(t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null
					? t.getCause() : t);
		}
	}

	/**
	 * Where the gliding attribute stops coming from, said as data rather than inferred.
	 *
	 * <p>{@code modifiers} is what {@code ItemStack.getAttributeModifiers()} answers for the equipped elytra —
	 * empty means NeoForge's {@code ItemAttributeModifierEvent} listener never ran. {@code onPlayer} is whether
	 * the player's {@code AttributeMap} even has an instance to put it in — false means the modifier is computed
	 * and then dropped on the floor. They are different bugs with the same symptom.
	 */
	private static String elytraProbe(Object player, ClassLoader cl) {
		StringBuilder out = new StringBuilder();
		try {
			Class<?> slotCls = Class.forName("net.minecraft.world.entity.EquipmentSlot", true, cl);
			Object chest = Enum.valueOf(slotCls.asSubclass(Enum.class), "CHEST");
			Object stack = accessible(player.getClass(), "getItemBySlot", slotCls).invoke(player, chest);
			Object mods = accessible(stack.getClass(), "getAttributeModifiers").invoke(stack);
			Object list = accessible(mods.getClass(), "modifiers").invoke(mods);
			out.append("elytraModifiers=").append(((java.util.List<?>) list).size());
			for (Object m : (java.util.List<?>) list) out.append(' ').append(m);
		} catch (Throwable t) {
			Caught.expected(t);
			out.append("elytraModifiers=<").append(t).append('>');
		}
		try {
			Class<?> neoMod = Class.forName("net.neoforged.neoforge.common.NeoForgeMod", true, cl);
			Object holder = neoMod.getField("GLIDING_FLIGHT").get(null);
			Class<?> holderCls = Class.forName("net.minecraft.core.Holder", true, cl);
			Object map = accessible(player.getClass(), "getAttributes").invoke(player);
			Object instance = accessible(map.getClass(), "getInstance", holderCls).invoke(map, holder);
			out.append(" attributeOnPlayer=").append(instance != null);
		} catch (Throwable t) {
			Caught.expected(t);
			out.append(" attributeOnPlayer=<").append(t).append('>');
		}
		// The method the repair actually rewrote, and the one collectEquipmentChanges calls. Proving
		// getAttributeModifiers() returns the modifier proves the EVENT; this proves the hand-off.
		try {
			Class<?> slotCls = Class.forName("net.minecraft.world.entity.EquipmentSlot", true, cl);
			Object chest = Enum.valueOf(slotCls.asSubclass(Enum.class), "CHEST");
			Object stack = accessible(player.getClass(), "getItemBySlot", slotCls).invoke(player, chest);
			int[] seen = {0};
			java.util.function.BiConsumer<Object, Object> sink = (h, m) -> seen[0]++;
			accessible(stack.getClass(), "forEachModifier", slotCls, java.util.function.BiConsumer.class)
					.invoke(stack, chest, sink);
			out.append(" forEachModifierYields=").append(seen[0]);
		} catch (Throwable t) {
			Caught.expected(t);
			out.append(" forEachModifierYields=<").append(t).append('>');
		}
		return out.toString();
	}

	/** The NeoForge attribute the merged base's canGlide reads, or -1 if it cannot be asked. */
	private static double glidingAttribute(Object player, ClassLoader cl) {
		try {
			Class<?> neoMod = Class.forName("net.neoforged.neoforge.common.NeoForgeMod", true, cl);
			Object holder = neoMod.getField("GLIDING_FLIGHT").get(null);
			Class<?> holderCls = Class.forName("net.minecraft.core.Holder", true, cl);
			return (Double) player.getClass().getMethod("getAttributeValue", holderCls).invoke(player, holder);
		} catch (Throwable t) {
			Caught.expected(t);
			return -1;
		}
	}

	private static double posOf(Object player, String getter) throws Exception {
		return (Double) accessible(player.getClass(), getter).invoke(player);
	}

	/** A declared method anywhere up the hierarchy, opened up — canGlide and tryToStartFallFlying are protected. */
	private static java.lang.reflect.Method accessible(Class<?> from, String name, Class<?>... params)
			throws NoSuchMethodException {
		java.util.Deque<Class<?>> queue = new java.util.ArrayDeque<>();
		queue.add(from);
		java.util.Set<Class<?>> seen = new java.util.HashSet<>();
		while (!queue.isEmpty()) {
			Class<?> c = queue.poll();
			if (c == null || !seen.add(c)) continue;
			for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
				if (!m.getName().equals(name) || m.getParameterCount() != params.length) continue;
				boolean matches = true;
				for (int i = 0; i < params.length; i++) {
					if (!m.getParameterTypes()[i].isAssignableFrom(params[i])) matches = false;
				}
				if (!matches) continue;
				m.setAccessible(true);
				return m;
			}
			// INTERFACES too: the method this was written to reach (ItemStack.getAttributeModifiers) is a default
			// on IItemStackExtension and is declared on no class in the chain. A superclass-only walk reported it
			// missing, which reads exactly like the game not having it.
			// ArrayDeque rejects null, and getSuperclass() is null for Object and for every interface.
			if (c.getSuperclass() != null) queue.add(c.getSuperclass());
			java.util.Collections.addAll(queue, c.getInterfaces());
		}
		throw new NoSuchMethodException(name);
	}

	/**
	 * Opens the resource-pack screen and counts what is listed in it.
	 *
	 * <p>A mod's own assets are served into the repository as required packs, and required is what keeps them
	 * applied. They are also marked hidden, and hidden is what is supposed to keep them off this screen — but the
	 * byte merge kept the flag and dropped every reader of it, so ten rows a player did not add and cannot remove
	 * appeared in their resource-pack list.
	 *
	 * <p>Counted from the SCREEN's own row widgets rather than from the repository, because the repository's id
	 * accessors already filter hidden packs and would report success whether or not the screen does.
	 */
	/** The pack id behind a listed row, or null if this row shape no longer carries one. */
	private static String rowPackId(Object row) {
		// Up the chain: the listed row may be a subclass of the entry type that declares the field.
		for (Class<?> c = row.getClass(); c != null; c = c.getSuperclass()) {
			try {
				java.lang.reflect.Field pack = c.getDeclaredField("pack");
				pack.setAccessible(true);
				Object entry = pack.get(row);
				if (entry == null) return null;
				return String.valueOf(entry.getClass().getMethod("getId").invoke(entry));
			} catch (NoSuchFieldException keepLooking) {
				Caught.expected(keepLooking);
				continue;
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/ClientSmoke] could not read the pack id of a %s row", c.getName());
				return null;
			}
		}
		return null;
	}

	private static void openTheResourcePackScreen(Object minecraft, ClassLoader cl) {
		try {
			Object repo = minecraft.getClass().getMethod("getResourcePackRepository").invoke(minecraft);
			int inRepository = ((java.util.Collection<?>) repo.getClass().getMethod("getSelectedPacks")
					.invoke(repo)).size();
			Class<?> screenCls = Class.forName("net.minecraft.client.gui.screens.packs.PackSelectionScreen", true, cl);
			Class<?> repoCls = Class.forName("net.minecraft.server.packs.repository.PackRepository", false, cl);
			Class<?> componentCls = Class.forName("net.minecraft.network.chat.Component", false, cl);
			Object title = componentCls.getMethod("empty").invoke(null);
			Object screen = screenCls.getConstructor(repoCls, java.util.function.Consumer.class,
							java.nio.file.Path.class, componentCls)
					.newInstance(repo, (java.util.function.Consumer<Object>) ignored -> { },
							java.nio.file.Path.of("."), title);
			setScreen(minecraft, screen);

			int rows = 0;
			int forbricRows = 0;
			java.util.List<String> listedIds = new java.util.ArrayList<>();
			Class<?> rowCls = Class.forName(
					"net.minecraft.client.gui.screens.packs.TransferableSelectionList$PackEntry", true, cl);
			for (Object listed : listedRows(screen, rowCls)) {
				rows++;
				// The row's own id, not its toString. A row is a GUI widget and prints as one, so matching on its
				// text answered "none of them" whatever the screen held — which made this count agree with any
				// outcome, including the one it was meant to detect.
				String id = rowPackId(listed);
				listedIds.add(id == null ? "?" : id);
				if (id != null && id.startsWith("forbric/")) forbricRows++;
			}
			ForbricLog.info("[Forbric/ClientSmoke] the resource-pack screen lists %d pack row(s), %d of them the "
					+ "kernel's ecosystem asset packs (repository holds %d selected); rows: %s", rows, forbricRows,
					inRepository, listedIds);
			setScreen(minecraft, null);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] could not open the resource-pack screen");
		}
	}

	/** Every pack row across both columns of the pack screen, by walking its widget tree. */
	private static java.util.List<Object> listedRows(Object screen, Class<?> rowCls) throws Exception {
		java.util.List<Object> out = new java.util.ArrayList<>();
		collectRows(screen, rowCls, out, 0);
		return out;
	}

	private static void collectRows(Object node, Class<?> rowCls, java.util.List<Object> out, int depth)
			throws Exception {
		if (node == null || depth > 4) return;
		java.lang.reflect.Method children;
		try {
			children = node.getClass().getMethod("children");
		} catch (NoSuchMethodException leaf) {
			Caught.expected(leaf);
			return;
		}
		for (Object child : (java.util.List<?>) children.invoke(node)) {
			if (child == null) continue;
			if (rowCls.isInstance(child)) {
				// The row itself, not its narration. Narration is the pack's TITLE, which only happened to be the
				// id while the kernel titled its packs after themselves — so a count keyed on it agreed with any
				// outcome the moment a pack got a real title. See rowPackId.
				out.add(child);
			} else {
				collectRows(child, rowCls, out, depth + 1);
			}
		}
	}

	/**
	 * The title screen's icon row, in the order it is laid out.
	 *
	 * <p>Asked because "which of these five squares is yours" is a real question with a measurable answer, and
	 * five unlabelled icons in a row is exactly the shape in which two mods buttons become indistinguishable.
	 *
	 * <p>And PRESSED, for the reason the pause menu's is: the title screen's Forge-family button is not built in
	 * TitleScreen at all but in a widget class of its own, so a redirect measured only on the pause menu was
	 * measured on the wrong half of the feature and reported as working while this one still opened the old list.
	 */
	private static void listTheTitleScreensButtons(Object minecraft, ClassLoader cl) {
		try {
			Class<?> titleCls = Class.forName("net.minecraft.client.gui.screens.TitleScreen", true, cl);
			Object title = titleCls.getConstructor().newInstance();
			setScreen(minecraft, title);
			Class<?> buttonCls = Class.forName("net.minecraft.client.gui.components.AbstractButton", true, cl);
			Object target = null;
			int n = 0;
			for (Object child : (java.util.List<?>) titleCls.getMethod("children").invoke(title)) {
				if (child == null || !buttonCls.isInstance(child)) continue;
				Object message = child.getClass().getMethod("getMessage").invoke(child);
				String text = (String) message.getClass().getMethod("getString").invoke(message);
				Object rect = child.getClass().getMethod("getRectangle").invoke(child);
				ForbricLog.info("[Forbric/ClientSmoke] title-screen button #%d: %s \"%s\" at %s", ++n,
						child.getClass().getName(), text, rect);
				if (target == null && !child.getClass().getName().startsWith("com.terraformersmc")
						&& text.toLowerCase(java.util.Locale.ROOT).contains("mod")) {
					target = child;
				}
			}
			if (target == null) {
				ForbricLog.warn("[Forbric/ClientSmoke] no Forge-family mods button on the title screen to press");
				return;
			}
			Class<?> input = Class.forName("net.minecraft.client.input.InputWithModifiers", true, cl);
			buttonCls.getMethod("onPress", input).invoke(target, (Object) null);
			Object now = currentScreen(minecraft);
			ForbricLog.info("[Forbric/ClientSmoke] the title screen's mods button opened: %s",
					now == null ? "<none>" : now.getClass().getName());
			setScreen(minecraft, null);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] could not press the title screen's mods button");
		}
	}

	/**
	 * Does what a player does: opens the pause menu and presses the mods button.
	 *
	 * <p>Everything else here reaches the unified screen by NAME, which proves the screen works and proves
	 * nothing about the button. The redirect is a bytecode rewrite inside {@code PauseScreen}, and a rewrite that
	 * logs "re-pointed" has only established that the transformer ran on some bytes — not that those bytes are
	 * the ones the game defined, and not that the button a player can see is bound to them. This presses it and
	 * reports the class that ends up in front of the player, which is the only form of the claim that can be
	 * wrong in the way that matters.
	 *
	 * <p>Every button on the screen is listed first, because the pause menu on a modded instance has more than
	 * one mods button — Mod Menu inserts its own next to the Forge family's — and "the button did not work" and
	 * "that was a different button" look identical from the outside.
	 */
	private static void pressTheRealModsButton(Object minecraft, ClassLoader cl) {
		try {
			Class<?> pauseCls = Class.forName("net.minecraft.client.gui.screens.PauseScreen", true, cl);
			Object pause = pauseCls.getConstructor(boolean.class).newInstance(true);
			setScreen(minecraft, pause);
			// init() runs off setScreen; the widgets do not exist before it.
			Object buttons = pauseCls.getMethod("children").invoke(pause);
			Class<?> buttonCls = Class.forName("net.minecraft.client.gui.components.AbstractButton", true, cl);
			Object target = null;
			for (Object child : (java.util.List<?>) buttons) {
				if (child == null || !buttonCls.isInstance(child)) continue;
				Object message = child.getClass().getMethod("getMessage").invoke(child);
				String text = (String) message.getClass().getMethod("getString").invoke(message);
				ForbricLog.info("[Forbric/ClientSmoke] pause-menu button: %s \"%s\"",
						child.getClass().getName(), text);
				if (target == null && !child.getClass().getName().startsWith("com.terraformersmc")
						&& text.toLowerCase(java.util.Locale.ROOT).contains("mod")) {
					target = child;
				}
			}
			if (target == null) {
				ForbricLog.warn("[Forbric/ClientSmoke] no Forge-family mods button on the pause menu to press");
				return;
			}
			ForbricLog.info("[Forbric/ClientSmoke] pressing the pause menu's mods button (%s)",
					target.getClass().getName());
			// One frame of the pause menu with the button on it, so the icon is something that can be LOOKED at
			// rather than inferred from the absence of a missing-sprite warning. Deferred by a few ticks:
			// Screenshot.grab captures the last frame DRAWN, and on the tick a screen is set none has been.
			if (Boolean.getBoolean(MODS_BUTTON_SHOT)) {
				shotDueAt = worldTicks + 5;
				pauseButtonTried = false;
				return;
			}
			// onPress takes the input that caused it in 26.2; null is what a synthetic press has to pass, and
			// every handler here ignores it.
			Class<?> input = Class.forName("net.minecraft.client.input.InputWithModifiers", true, cl);
			buttonCls.getMethod("onPress", input).invoke(target, (Object) null);
			Object now = currentScreen(minecraft);
			ForbricLog.info("[Forbric/ClientSmoke] the mods button opened: %s",
					now == null ? "<none>" : now.getClass().getName());
			setScreen(minecraft, null);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] could not press the pause menu's mods button");
		}
	}

	/**
	 * Double-clicks a row in the unified list, the way a player does.
	 *
	 * <p>Calling the resolver by name proves the resolver. The shortcut is a {@code doubled} flag arriving on a
	 * row's own {@code mouseClicked}, and nothing about the resolver says that flag is wired to anything — the
	 * mods button was measured working for exactly that reason and still opened the wrong screen for a week.
	 *
	 * <p>The row picked is one whose mod HAS a config, because a double click on a mod without one is supposed
	 * to do nothing, and "nothing happened" is indistinguishable from "the shortcut is not wired".
	 */
	private static void doubleClickARowInTheModList(Object minecraft, ClassLoader cl) {
		try {
			Object screen = currentScreen(minecraft);
			if (screen == null || !screen.getClass().getName().endsWith("KernelModListScreen")) {
				ForbricLog.warn("[Forbric/ClientSmoke] the unified list is not open — cannot double-click a row");
				return;
			}
			Class<?> configs = Class.forName("net.forbric.kernel.runtime.KernelModConfigScreens", true, cl);
			Object wanted = null;
			for (String ecosystem : new String[] {"NEOFORGE", "FORGE", "FABRIC"}) {
				wanted = configs.getMethod("firstWithConfig", String.class).invoke(null, ecosystem);
				if (wanted != null) break;
			}
			if (wanted == null) {
				ForbricLog.info("[Forbric/ClientSmoke] no mod here has a config — nothing to double-click");
				return;
			}
			String modId = (String) wanted.getClass().getMethod("modId").invoke(wanted);
			// Matched on the DISPLAY NAME: a row narrates itself as "<name>, <ecosystem>", and a mod's name and
			// its id are routinely different words.
			String modName = (String) wanted.getClass().getMethod("name").invoke(wanted);
			Object row = rowFor(screen, modName);
			if (row == null) {
				ForbricLog.warn("[Forbric/ClientSmoke] %s has a config but no row in the unified list", modId);
				return;
			}
			// The row's own handler, with doubled=true: the same call the widget makes on the second click.
			Class<?> eventCls = Class.forName("net.minecraft.client.input.MouseButtonEvent", true, cl);
			// setAccessible because a row is a private inner class of the screen -- which is right for it and is
			// a harness problem, not a reason to widen the screen's API for a test.
			java.lang.reflect.Method click =
					row.getClass().getMethod("mouseClicked", eventCls, boolean.class);
			click.setAccessible(true);
			click.invoke(row, null, true);
			Object now = currentScreen(minecraft);
			ForbricLog.info("[Forbric/ClientSmoke] double-clicking %s in the unified list opened: %s", modId,
					now == null ? "<none>" : now.getClass().getName());
			setScreen(minecraft, screen);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] could not double-click a row in the unified list");
		}
	}

	/** The list row narrating {@code modName}, found by walking the screen's widgets. */
	private static Object rowFor(Object screen, String modName) throws Exception {
		for (Object child : (java.util.List<?>) screen.getClass().getMethod("children").invoke(screen)) {
            if (child == null) continue;
			java.lang.reflect.Method children;
			try {
				children = child.getClass().getMethod("children");
			} catch (NoSuchMethodException leaf) {
				Caught.expected(leaf);
				continue;
			}
			for (Object row : (java.util.List<?>) children.invoke(child)) {
				if (row == null || !row.getClass().getName().contains("KernelModListScreen")) continue;
				java.lang.reflect.Method narrate = row.getClass().getMethod("getNarration");
				narrate.setAccessible(true);
				Object narration = narrate.invoke(row);
				String text = (String) narration.getClass().getMethod("getString").invoke(narration);
				if (text.startsWith(modName)) return row;
			}
		}
		return null;
	}

	/**
	 * Opens one mod's config screen through the unified resolver, preferring a mod that is NOT Fabric's.
	 *
	 * <p>Fabric's answer is Mod Menu's, and Mod Menu already opened Fabric mods' configs before any of this
	 * existed — so a Fabric-only success would prove nothing that was ever in doubt. What was in doubt is the
	 * other two registries, which no screen on this instance had ever asked. (Without Mod Menu, the Fabric answer
	 * is the kernel's own reading of the mods' Mod Menu entrypoints; {@link #CONFIG_SCREENS} walks those by name.)
	 *
	 * <p>What is reported is the class of the screen that ended up in front of the player. "The resolver returned
	 * something" is not the same claim: a screen that throws in its own constructor never reaches the player, and
	 * one that throws while drawing takes the client with it — which is itself the assertion, since the gate
	 * requires the client to go on and leave the world cleanly.
	 */
	private static void openAConfigScreen(Object minecraft, ClassLoader cl) {
		try {
			Class<?> configs = Class.forName("net.forbric.kernel.runtime.KernelModConfigScreens", true, cl);
			ForbricLog.info("[Forbric/ClientSmoke] mods with a config screen, by ecosystem: %s",
					configs.getMethod("summary").invoke(null));
			Object entry = null;
			String from = null;
			for (String ecosystem : new String[] {"NEOFORGE", "FORGE", "FABRIC"}) {
				entry = configs.getMethod("firstWithConfig", String.class).invoke(null, ecosystem);
				if (entry != null) {
					from = ecosystem;
					break;
				}
			}
			if (entry == null) {
				ForbricLog.info("[Forbric/ClientSmoke] no mod in this pack registers a config screen — nothing to "
						+ "open");
				return;
			}
			String modId = (String) entry.getClass().getMethod("modId").invoke(entry);
			Object screen = configs.getMethod("openById", String.class, Object.class)
					.invoke(null, modId, currentScreen(minecraft));
			if (screen == null) {
				ForbricLog.warn("[Forbric/ClientSmoke] %s (%s) reported a config screen and then produced none",
						modId, from);
				return;
			}
			setScreen(minecraft, screen);
			Object now = currentScreen(minecraft);
			ForbricLog.info("[Forbric/ClientSmoke] opened %s's config screen from the unified list (%s): %s", modId,
					from, now == null ? "<none>" : now.getClass().getName());
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] could not open a config screen from the unified list");
		}
	}

	private static java.util.List<String> configIds;
	private static int configIndex;
	private static int configNextTick;
	private static Object configModsScreen;
	private static String configShotFor;
	private static boolean configScreensDone;
	private static final java.util.List<String> configOpened = new java.util.ArrayList<>();

	/**
	 * Walks {@link #CONFIG_SCREENS}, one mod per step, through the Mods screen's own Config button.
	 *
	 * <p>A step selects the row with the row's own click handler (which is what asks the resolver whether to show the
	 * button), reads whether the button is visible — a hidden button is the answer "no config" as the player sees it
	 * — and presses it. The screen is then left up for {@link #CONFIG_SCREEN_HOLD} ticks and photographed, and the
	 * next step starts from the same list again, as a player coming back from a mod's settings would.
	 */
	private static void configScreensIfDue(Object minecraft) {
		String wanted = System.getProperty(CONFIG_SCREENS, "");
		if (wanted.isBlank() || configScreensDone) return;
		if (worldTicks < Integer.getInteger(CONFIG_SCREENS_AT, 100) || worldTicks < configNextTick) return;
		try {
			ClassLoader cl = minecraft.getClass().getClassLoader();
			if (configIds == null) {
				configIds = new java.util.ArrayList<>();
				for (String id : wanted.split(",")) if (!id.isBlank()) configIds.add(id.trim());
				Class<?> screenType = Class.forName("net.minecraft.client.gui.screens.Screen", false, cl);
				configModsScreen = Class.forName("net.forbric.kernel.runtime.KernelModListScreen", true, cl)
						.getConstructor(screenType).newInstance((Object) null);
				setScreen(minecraft, configModsScreen);
				ForbricLog.info("[Forbric/ClientSmoke] config screens: mods with a config screen, by ecosystem: %s",
						Class.forName("net.forbric.kernel.runtime.KernelModConfigScreens", true, cl)
								.getMethod("summary").invoke(null));
				configNextTick = worldTicks + 2;
				return;
			}
			if (configShotFor != null) {
				shootNamed(minecraft, "forbric-config-" + configShotFor + ".png");
				configShotFor = null;
				configNextTick = worldTicks + 2;
				return;
			}
			if (configIndex >= configIds.size()) {
				configScreensDone = true;
				setScreen(minecraft, null);
				ForbricLog.info("[Forbric/ClientSmoke] config screens: %d of %d opened through the Config button: %s",
						configOpened.size(), configIds.size(), configOpened);
				return;
			}
			String modId = configIds.get(configIndex++);
			setScreen(minecraft, configModsScreen);
			if (pressConfigFor(minecraft, cl, modId)) {
				configOpened.add(modId);
				configShotFor = modId;
				configNextTick = worldTicks + CONFIG_SCREEN_HOLD;
			} else {
				configNextTick = worldTicks + 1;
			}
		} catch (Throwable t) {
			configScreensDone = true;
			Caught.warn(t, "[Forbric/ClientSmoke] config screens: the walk stopped");
		}
	}

	/** One step of {@link #configScreensIfDue}: select {@code modId}'s row, press Config. True when a screen opened. */
	private static boolean pressConfigFor(Object minecraft, ClassLoader cl, String modId) throws Exception {
		net.forbric.api.ModCatalog.Entry entry = null;
		for (net.forbric.api.ModCatalog.Entry e : net.forbric.api.ModCatalog.all()) {
			if (e.modId().equals(modId)) entry = e;
		}
		if (entry == null) {
			ForbricLog.warn("[Forbric/ClientSmoke] config screen of %s: no such mod in the catalog", modId);
			return false;
		}
		Object screen = currentScreen(minecraft);
		Object row = rowNamed(screen, entry.name());
		if (row == null) {
			ForbricLog.warn("[Forbric/ClientSmoke] config screen of %s: no row named \"%s\" in the Mods screen", modId,
					entry.name());
			return false;
		}
		Class<?> eventCls = Class.forName("net.minecraft.client.input.MouseButtonEvent", true, cl);
		java.lang.reflect.Method click = row.getClass().getMethod("mouseClicked", eventCls, boolean.class);
		click.setAccessible(true);
		click.invoke(row, null, false);
		Object button = buttonLabelled(screen, cl, "Config");
		if (button == null) {
			ForbricLog.warn("[Forbric/ClientSmoke] config screen of %s: the Mods screen has no Config button", modId);
			return false;
		}
		if (!button.getClass().getField("visible").getBoolean(button)) {
			ForbricLog.info("[Forbric/ClientSmoke] config screen of %s (%s): the Config button is hidden — no config "
					+ "screen to open", modId, entry.ecosystem());
			return false;
		}
		Class<?> input = Class.forName("net.minecraft.client.input.InputWithModifiers", true, cl);
		Class.forName("net.minecraft.client.gui.components.AbstractButton", true, cl).getMethod("onPress", input)
				.invoke(button, (Object) null);
		Object now = currentScreen(minecraft);
		if (now == null || now == screen) {
			ForbricLog.warn("[Forbric/ClientSmoke] config screen of %s (%s): pressing Config left %s in front", modId,
					entry.ecosystem(), now == null ? "no screen" : "the Mods screen");
			return false;
		}
		ForbricLog.info("[Forbric/ClientSmoke] config screen of %s (%s) through the Config button: %s", modId,
				entry.ecosystem(), now.getClass().getName());
		return true;
	}

	/**
	 * The list row for the mod named exactly {@code name}. A row narrates itself as {@code "<name>, <ecosystem>"}, and
	 * the separator is part of the match: "Sodium" must not find "Sodium Extra".
	 */
	private static Object rowNamed(Object screen, String name) throws Exception {
		for (Object child : (java.util.List<?>) screen.getClass().getMethod("children").invoke(screen)) {
			if (child == null) continue;
			java.lang.reflect.Method children;
			try {
				children = child.getClass().getMethod("children");
			} catch (NoSuchMethodException leaf) {
				Caught.expected(leaf);
				continue;
			}
			for (Object row : (java.util.List<?>) children.invoke(child)) {
				if (row == null || !row.getClass().getName().contains("KernelModListScreen")) continue;
				java.lang.reflect.Method narrate = row.getClass().getMethod("getNarration");
				narrate.setAccessible(true);
				Object narration = narrate.invoke(row);
				String text = (String) narration.getClass().getMethod("getString").invoke(narration);
				if (text.startsWith(name + ", ")) return row;
			}
		}
		return null;
	}

	/** The screen's button whose label reads {@code label}, found as a player finds it: by what it says. */
	private static Object buttonLabelled(Object screen, ClassLoader cl, String label) throws Exception {
		Class<?> buttonCls = Class.forName("net.minecraft.client.gui.components.AbstractButton", true, cl);
		for (Object child : (java.util.List<?>) screen.getClass().getMethod("children").invoke(screen)) {
			if (child == null || !buttonCls.isInstance(child)) continue;
			Object message = buttonCls.getMethod("getMessage").invoke(child);
			if (label.equals(message.getClass().getMethod("getString").invoke(message))) return child;
		}
		return null;
	}

	/** Saves the last drawn frame as {@code <gameDir>/screenshots/<file>}, so a picture can be paired with its mod. */
	private static void shootNamed(Object minecraft, String file) {
		try {
			ClassLoader cl = minecraft.getClass().getClassLoader();
			Object target = invoke(fieldValue(minecraft, "gameRenderer"), "mainRenderTarget");
			Class<?> targetType = Class.forName("com.mojang.blaze3d.pipeline.RenderTarget", false, cl);
			java.util.function.Consumer<Object> done = message -> { };
			Class.forName("net.minecraft.client.Screenshot", true, cl)
					.getMethod("grab", java.io.File.class, String.class, targetType, int.class,
							java.util.function.Consumer.class)
					.invoke(null, fieldValue(minecraft, "gameDirectory"), file, target, 1, done);
			ForbricLog.info("[Forbric/ClientSmoke] screenshot %s requested at world tick %d", file, worldTicks);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] could not take screenshot %s", file);
		}
	}

	private static void shoot(Object minecraft) {
		try {
			Class.forName("net.minecraft.client.Screenshot", true, minecraft.getClass().getClassLoader())
					.getMethod("grab", minecraft.getClass(), boolean.class).invoke(null, minecraft, false);
			ForbricLog.info("[Forbric/ClientSmoke] screenshot of the pause menu's mods button requested");
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] could not screenshot the pause menu");
		}
	}

	private static Object currentScreen(Object minecraft) throws Exception {
		Object gui = fieldValue(minecraft, "gui");
		return gui == null ? null : gui.getClass().getMethod("screen").invoke(gui);
	}

	/** {@code Minecraft.gui.setScreen} — 26.2 moved it off Minecraft itself. */
	private static void setScreen(Object minecraft, Object screen) throws Exception {
		Object gui = fieldValue(minecraft, "gui");
		Class<?> screenCls = Class.forName("net.minecraft.client.gui.screens.Screen", false,
				minecraft.getClass().getClassLoader());
		gui.getClass().getMethod("setScreen", screenCls).invoke(gui, screen);
	}

	/**
	 * The movement drill: a fixed schedule of inputs a real player might produce, so a server-side anti-cheat has
	 * something to judge. Everything goes through the same path a keyboard would — {@code KeyMapping.setDown} for
	 * movement, {@code Minecraft.startAttack}/{@code startUseItem} for the hands, {@code Entity.setYRot/setXRot}
	 * for the mouse — so the packets the server sees are the packets the real game produces for these inputs,
	 * not a hand-rolled imitation of them. Phases are announced on the log so the gate can act on them (it
	 * teleports the player into water on "swim-wait") and so a flag can be placed against what the player was
	 * doing at the time.
	 *
	 * <p>The optional last phase is the positive control: one impossible move (six blocks in a tick). A drill that
	 * produced zero flags proves nothing on its own — the anti-cheat might not be watching — so the gate demands
	 * silence BEFORE this marker and at least one flag AFTER it.
	 */
	private static void drill(Object minecraft, Object player) {
		drillTick++;
		int t = drillTick;
		drillPlayer = player;
		Object options = fieldValue(minecraft, "options");
		if (options == null) return;
		if (t == 0) {
			setRotation(player, 0f, 0f);
			phase("walk");
		}
		if (t < 60) { key(options, "keyUp", true); return; }
		if (t == 60) phase("sprint");
		if (t < 140) { key(options, "keyUp", true); key(options, "keySprint", true); return; }
		if (t == 140) phase("sprint-jump");
		if (t < 220) { key(options, "keyUp", true); key(options, "keySprint", true); key(options, "keyJump", t % 10 == 0); return; }
		if (t == 220) { phase("turn"); key(options, "keySprint", false); key(options, "keyJump", false); }
		if (t < 300) { key(options, "keyUp", true); setRotation(player, yaw(player) + 4.5f, 0f); return; }
		if (t == 300) { phase("strafe"); key(options, "keyUp", false); }
		if (t < 330) { key(options, "keyLeft", true); return; }
		if (t < 360) { key(options, "keyLeft", false); key(options, "keyRight", true); return; }
		if (t == 360) { phase("backpedal"); key(options, "keyRight", false); }
		if (t < 420) { key(options, "keyDown", true); return; }
		if (t == 420) { phase("sneak-walk"); key(options, "keyDown", false); }
		if (t < 480) { key(options, "keyShift", true); key(options, "keyUp", true); return; }
		// Held down, not tapped: a tap swings, a hold MINES — and only sustained mining puts a block into the
		// level's destroy-progress map, which is the one thing that makes the game extract a block-breaking overlay
		// each frame. That path crashed the render frame on the merged base for the life of the project and was
		// only ever seen once, by accident, because nothing here had held the button down. Looking down first, so
		// the crosshair is on the ground rather than on air.
		if (t == 480) {
			phase("mine");
			key(options, "keyShift", false);
			key(options, "keyUp", false);
			setRotation(player, yaw(player), 80f);
		}
		if (t < 540) {
			invokeWithBoolean(minecraft, "continueAttack", true);
			// Twice, a few ticks apart: this is the only evidence that the game had a break overlay to draw, and
			// therefore that the frame which draws it was exercised at all.
			if (t == 520 || t == 538) reportBreakProgress(fieldValue(minecraft, "level"));
			return;
		}
		if (t == 540) { phase("place"); setRotation(player, yaw(player), 80f); }
		if (t < 600) { key(options, "keyDown", true); if (t % 5 == 0) invokeNoArg(minecraft, "startUseItem"); return; }
		if (t == 600) { phase("swim-wait"); key(options, "keyDown", false); setRotation(player, yaw(player), 0f); }
		if (t < 660) return; // the gate teleports the player into the pool while this holds still
		if (t == 660) phase("swim");
		if (t < 710) { key(options, "keyUp", true); key(options, "keyJump", true); return; }
		if (t < 760) { key(options, "keyJump", false); key(options, "keyUp", true); key(options, "keySprint", true); return; }
		if (t == 760) { phase("idle"); key(options, "keyUp", false); key(options, "keySprint", false); }
		if (t < 800) return;
		if (Boolean.getBoolean(DRILL_CONTROL)) {
			// Announce first, move three seconds later: the gate answers the announcement by writing a marker into
			// the SERVER's log, and the anti-cheat's verdict on the move lands after that marker. Making the move on
			// the same tick as the announcement lost the race by ~50 ms on the first run.
			if (t == 800) phase("control-wait");
			if (t < 860) return;
			if (t == 860) {
				phase("control");
				ForbricLog.info("[Forbric/ClientSmoke] drill control: moving the player 6 blocks in one tick — "
						+ "an anti-cheat that is watching must flag this");
				setPos(player, x(player) + 6.0, y(player), z(player));
			}
			if (t < 920) { key(options, "keyUp", true); return; }
			key(options, "keyUp", false);
		}
		drillDone = true;
		ForbricLog.info("[Forbric/ClientSmoke] drill complete after %d drill tick(s) (%s)", t,
				Boolean.getBoolean(DRILL_CONTROL) ? "with positive control" : "no positive control");
	}

	private static Object drillPlayer;
	private static Object lastPlayer;

	/**
	 * One line per phase, with where the player is and what state it is in. The line is what makes "Grim had
	 * nothing to say" mean something: a drill that never moved would be silent too, so the gate reads the position
	 * off these to see that walking covered ground and that the swim phase happened in water.
	 */
	private static void phase(String name) {
		Object p = drillPlayer;
		ForbricLog.info("[Forbric/ClientSmoke] drill phase %s at world tick %d pos=(%.1f %.1f %.1f) inWater=%s sprinting=%s",
				name, worldTicks, x(p), y(p), z(p), flag(p, "isInWater"), flag(p, "isSprinting"));
	}

	private static String flag(Object player, String getter) {
		Object v = player == null ? null : invokeGetter(player, getter);
		return v instanceof Boolean b ? String.valueOf(b) : "?";
	}

	private static void key(Object options, String name, boolean down) {
		Object mapping = fieldValue(options, name);
		if (mapping == null) return;
		try {
			mapping.getClass().getMethod("setDown", boolean.class).invoke(mapping, down);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Caught.warn(e, "[Forbric/ClientSmoke] cannot press %s", name);
		}
	}

	private static float yaw(Object player) {
		Object v = invokeGetter(player, "getYRot");
		return v instanceof Float f ? f : 0f;
	}

	private static double x(Object player) { return coord(player, "getX"); }
	private static double y(Object player) { return coord(player, "getY"); }
	private static double z(Object player) { return coord(player, "getZ"); }

	private static double coord(Object player, String getter) {
		Object v = player == null ? null : invokeGetter(player, getter);
		return v instanceof Double d ? d : 0d;
	}

	private static void setRotation(Object player, float yRot, float xRot) {
		try {
			// Public on net.minecraft.world.entity.Entity, so resolved there rather than on LocalPlayer's class.
			Class<?> entity = entityClass(player);
			entity.getMethod("setYRot", float.class).invoke(player, yRot);
			entity.getMethod("setXRot", float.class).invoke(player, xRot);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Caught.warn(e, "[Forbric/ClientSmoke] cannot rotate the player");
		}
	}

	private static void setPos(Object player, double x, double y, double z) {
		try {
			entityClass(player).getMethod("setPos", double.class, double.class, double.class).invoke(player, x, y, z);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Caught.warn(e, "[Forbric/ClientSmoke] the control move could not be made — the positive control is void");
		}
	}

	private static Object invokeGetter(Object owner, String name) {
		try {
			return entityClass(owner).getMethod(name).invoke(owner);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Caught.warn(e, "[Forbric/ClientSmoke] cannot read %s() of %s", name, owner.getClass().getName());
			return null;
		}
	}

	/** {@code net.minecraft.world.entity.Entity} as loaded by the game — the public class every getter used here lives on. */
	private static Class<?> entityClass(Object player) throws ClassNotFoundException {
		return Class.forName("net.minecraft.world.entity.Entity", false, player.getClass().getClassLoader());
	}

	/**
	 * Reads back each configured position through the same lookups the game renders from — the client level's
	 * block state, its block, that block's registry name — and logs one line per position.
	 */
	private static void probeBlocks(Object level) {
		String spec = System.getProperty(PROBE, "");
		if (spec.isBlank()) return;
		try {
			ClassLoader cl = level.getClass().getClassLoader();
			var blockPos = Class.forName("net.minecraft.core.BlockPos", false, cl).getConstructor(int.class, int.class, int.class);
			Method getBlockState = Class.forName("net.minecraft.world.level.BlockGetter", false, cl)
					.getMethod("getBlockState", blockPos.getDeclaringClass());
			Method getBlock = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase", false, cl)
					.getMethod("getBlock");
			Object blocks = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl).getField("BLOCK").get(null);
			Method getKey = Class.forName("net.minecraft.core.Registry", false, cl).getMethod("getKey", Object.class);
			Method getId = Class.forName("net.minecraft.core.IdMap", false, cl).getMethod("getId", Object.class);
			for (String one : spec.split(";")) {
				String[] c = one.trim().split(",");
				if (c.length != 3) continue;
				Object pos = blockPos.newInstance(Integer.parseInt(c[0].trim()), Integer.parseInt(c[1].trim()), Integer.parseInt(c[2].trim()));
				Object state = getBlockState.invoke(level, pos);
				Object block = getBlock.invoke(state);
				// The full state, not just the block: a block-STATE id that is off by one usually lands on another
				// state of the same block, and only the properties give that away.
				ForbricLog.info("[Forbric/ClientSmoke] block at (%s %s %s) is %s (registry id %s) state %s", c[0].trim(),
						c[1].trim(), c[2].trim(), getKey.invoke(blocks, block), getId.invoke(blocks, block), state);
			}
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] block probe failed");
		}
		probeHotbar(level);
	}

	/**
	 * Logs what the player holds in hotbar slot 0, by registry name. Item ids travel in every inventory packet and
	 * have no "neighbouring state" to hide an off-by-one in, so a server that gives the player one item and a client
	 * that reads back another is the plainest registry-id mismatch there is.
	 */
	private static boolean tooltipProbed;

	/**
	 * One advanced tooltip, drawn the way the inventory screen draws it, for a damaged iron sword carrying lore: its
	 * lore, attribute and durability lines come from NeoForge's appenders, with every installed mod's tooltip listeners
	 * in the path. Keys are read from the components, not their text, so the client's language does not matter.
	 */
	private static void probeTooltip(Object level, Object player) {
		tooltipProbed = true;
		try {
			ClassLoader cl = level.getClass().getClassLoader();
			Class<?> stackCls = Class.forName("net.minecraft.world.item.ItemStack", false, cl);
			Class<?> componentCls = Class.forName("net.minecraft.network.chat.Component", false, cl);
			Class<?> typeCls = Class.forName("net.minecraft.core.component.DataComponentType", false, cl);
			Object sword = Class.forName("net.minecraft.world.item.Items", false, cl).getField("IRON_SWORD").get(null);
			Object stack = stackCls.getConstructor(Class.forName("net.minecraft.world.level.ItemLike", false, cl)).newInstance(sword);
			Class<?> components = Class.forName("net.minecraft.core.component.DataComponents", false, cl);
			Method set = stackCls.getMethod("set", typeCls, Object.class);
			set.invoke(stack, components.getField("DAMAGE").get(null), 5);
			Object line = componentCls.getMethod("literal", String.class).invoke(null, "forbric-smoke-lore");
			Object lore = Class.forName("net.minecraft.world.item.component.ItemLore", false, cl).getConstructor(java.util.List.class)
					.newInstance(java.util.List.of(line));
			set.invoke(stack, components.getField("LORE").get(null), lore);
			Class<?> contextCls = Class.forName("net.minecraft.world.item.Item$TooltipContext", false, cl);
			Object context = contextCls.getMethod("of", Class.forName("net.minecraft.world.level.Level", false, cl)).invoke(null, level);
			Class<?> flagCls = Class.forName("net.minecraft.world.item.TooltipFlag", false, cl);
			Object advanced = flagCls.getField("ADVANCED").get(null);
			java.util.List<?> lines = (java.util.List<?>) stackCls.getMethod("getTooltipLines", contextCls,
					Class.forName("net.minecraft.world.entity.player.Player", false, cl), flagCls).invoke(stack, context, player, advanced);
			boolean loreShown = false, durability = false, attributes = false;
			for (Object drawn : lines) {
				if (String.valueOf(componentCls.getMethod("getString").invoke(drawn)).contains("forbric-smoke-lore")) loreShown = true;
				Object contents = componentCls.getMethod("getContents").invoke(drawn);
				if (!contents.getClass().getSimpleName().equals("TranslatableContents")) continue;
				String key = String.valueOf(contents.getClass().getMethod("getKey").invoke(contents));
				durability |= key.equals("item.durability");
				attributes |= key.startsWith("item.modifiers.");
			}
			ForbricLog.info("[Forbric/ClientSmoke] advanced tooltip of a damaged iron sword with lore: %d line(s), lore %s, "
					+ "attributes %s, durability %s", lines.size(), loreShown, attributes, durability);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] tooltip probe failed");
		}
	}

	private static void probeHotbar(Object level) {
		Object player = drillPlayer != null ? drillPlayer : lastPlayer;
		if (player == null) return;
		try {
			ClassLoader cl = level.getClass().getClassLoader();
			Object inventory = Class.forName("net.minecraft.world.entity.player.Player", false, cl).getMethod("getInventory").invoke(player);
			Object stack = Class.forName("net.minecraft.world.Container", false, cl).getMethod("getItem", int.class).invoke(inventory, 0);
			Object item = Class.forName("net.minecraft.world.item.ItemStack", false, cl).getMethod("getItem").invoke(stack);
			Object items = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl).getField("ITEM").get(null);
			Method getKey = Class.forName("net.minecraft.core.Registry", false, cl).getMethod("getKey", Object.class);
			Method getId = Class.forName("net.minecraft.core.IdMap", false, cl).getMethod("getId", Object.class);
			ForbricLog.info("[Forbric/ClientSmoke] hotbar slot 0 holds %s (registry id %s) x%s", getKey.invoke(items, item),
					getId.invoke(items, item), stack.getClass().getMethod("getCount").invoke(stack));
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] hotbar probe failed");
		}
	}

	/** One line per configured entry: {@code registry id of item mcwbridges:andesite_bridge <moment>: N}. */
	private static void probeIds(Object minecraft, String moment) {
		String spec = System.getProperty(PROBE_IDS, "");
		if (spec.isBlank()) return;
		try {
			ClassLoader cl = minecraft.getClass().getClassLoader();
			Class<?> builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
			Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", false, cl);
			Method parse = identifier.getMethod("parse", String.class);
			Method getValue = Class.forName("net.minecraft.core.Registry", false, cl).getMethod("getValue", identifier);
			Method getId = Class.forName("net.minecraft.core.IdMap", false, cl).getMethod("getId", Object.class);
			for (String one : spec.split(";")) {
				int colon = one.indexOf(':');
				if (colon < 0) continue;
				String registry = one.substring(0, colon).trim().toUpperCase(java.util.Locale.ROOT);
				String name = one.substring(colon + 1).trim();
				Object reg = builtIn.getField(registry).get(null);
				Object value = getValue.invoke(reg, parse.invoke(null, name));
				ForbricLog.info("[Forbric/ClientSmoke] registry id of %s %s %s: %s", registry.toLowerCase(java.util.Locale.ROOT),
						name, moment, value == null ? "absent" : getId.invoke(reg, value));
			}
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] registry id probe failed");
		}
	}

	/** Test seam: forget everything, so a second run in one JVM starts clean. */
	static void resetForTests() {
		lastLevel = null;
		worldTicks = 0;
		joined = false;
		ready = false;
		disconnectRequested = false;
		stopRequested = false;
		drillTick = -1;
		drillDone = false;
		drillPlayer = null;
		lastPlayer = null;
		probed = false;
		idsLoggedBeforeConnect = false;
		screenMouseStage = 0;
		screenMouseAt = 0;
		carryStage = 0;
		carryAt = 0;
		carryWait = 0;
		carryChestAt = null;
		carryPigAt = null;
		carryVerdict.clear();
		heldShift = false;
	}

	private static Object fieldValue(Object owner, String name) {
		for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
			try {
				Field field = c.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(owner);
			} catch (NoSuchFieldException keepLooking) {
				Caught.expected(keepLooking);
				continue;
			} catch (ReflectiveOperationException | RuntimeException unreadable) {
				Caught.warn(unreadable, "[Forbric/ClientSmoke] cannot read %s of %s", name, owner.getClass().getName());
				return null;
			}
		}
		return null;
	}

	/**
	 * Saves a screenshot when the current world tick is one of {@link #SCREENSHOTS}.
	 *
	 * <p>Runs on the render thread (this is called from {@code Minecraft.tick}), which is where the game's own
	 * screenshot key takes it, so the frame is complete and the GPU read-back is legal. The file lands in
	 * {@code <gameDir>/screenshots}; the log line names the tick so a gate can pair a picture with a drill phase.
	 */
	private static void screenshotIfDue(Object minecraft) {
		if (!screenshotDue(System.getProperty(SCREENSHOTS, ""), worldTicks) || !shotsTaken.add(worldTicks)) return;
		try {
			Class<?> screenshot = Class.forName("net.minecraft.client.Screenshot", true,
					minecraft.getClass().getClassLoader());
			java.lang.reflect.Method grab = screenshot.getMethod("grab", minecraft.getClass(), boolean.class);
			grab.invoke(null, minecraft, false);
			ForbricLog.info("[Forbric/ClientSmoke] screenshot requested at world tick %d", worldTicks);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] could not take a screenshot at world tick %d", worldTicks);
		}
	}

	/** Parse the diagnostic's comma-separated world ticks without linking any game class. */
	static boolean screenshotDue(String ticks, int worldTick) {
		if (ticks == null || ticks.isBlank() || worldTick < 0) return false;
		for (String tick : ticks.split(",")) {
			try {
				if (Integer.parseInt(tick.trim()) == worldTick) return true;
			} catch (NumberFormatException malformed) {
				Caught.expected(malformed);
				// A malformed entry costs that entry, not the run or a later valid entry.
			}
		}
		return false;
	}

	/**
	 * {@code tick[,query…]}: world tick at which to put the player in creative mode, open the creative inventory,
	 * select its search tab and type each query into the screen's OWN search box, then log what the item grid
	 * shows. Queries default to {@link #CREATIVE_SEARCH_QUERIES}: a plain name, a namespaced id and a {@code #} tag
	 * search, because the screen answers the three from different trees.
	 *
	 * <p>The grid is the judgement, not anything a producer logs. The search tab reads a tree that some earlier
	 * call built off-thread and filed under a key; whether the screen looks under the same key as the producer
	 * filed it is exactly what a log line from the producer cannot say, and a wrong answer is not an error — it is
	 * an empty grid.
	 */
	public static final String CREATIVE_SEARCH = "forbric.clientSmokeCreativeSearch";
	private static final String[] CREATIVE_SEARCH_QUERIES = {"stone", "minecraft:oak", "#planks"};
	/** Ticks between the creative probe's steps: long enough for a frame of the grid to be drawn between them. */
	private static final int CREATIVE_STEP_TICKS = 10;
	private static int creativeStage;
	private static int creativeStageAt;

	/**
	 * The creative-search probe, one step per {@link #CREATIVE_STEP_TICKS}: game mode, open, search (pass 1),
	 * screenshot, search again (pass 2), every OTHER tab that has a search bar, a language-change rebuild and a
	 * search after it, close. Pass 2 is there because the trees are built asynchronously: a grid that is empty
	 * on the first look and full on the second is a timing problem, empty on both is a wiring one.
	 */
	private static void creativeSearchIfDue(Object minecraft, Object player) {
		String spec = System.getProperty(CREATIVE_SEARCH, "");
		if (spec.isBlank() || creativeStage > 8) return;
		String[] parts = spec.split(",");
		int due;
		try {
			due = Integer.parseInt(parts[0].trim());
		} catch (NumberFormatException malformed) {
			Caught.expected(malformed);
			creativeStage = 9;
			return;
		}
		if (due <= 0 || worldTicks < due) return;
		if (creativeStage > 0 && worldTicks < creativeStageAt + CREATIVE_STEP_TICKS) return;
		creativeStageAt = worldTicks;
		int step = creativeStage++;
		String[] queries = parts.length > 1 ? java.util.Arrays.copyOfRange(parts, 1, parts.length) : CREATIVE_SEARCH_QUERIES;
		try {
			ClassLoader cl = minecraft.getClass().getClassLoader();
			switch (step) {
				case 0 -> makeCreative(minecraft, cl);
				case 1 -> openCreativeScreen(minecraft, player, cl);
				case 2 -> creativeSearchPass(minecraft, cl, queries, "pass 1");
				case 3 -> screenshotCreative(minecraft, "after pass 1, showing '" + queries[0].trim() + "'");
				case 4 -> creativeSearchPass(minecraft, cl, queries, "pass 2");
				case 5 -> creativeSearchOtherTabs(minecraft, cl, queries[0].trim());
				case 6 -> {
					Object connection = accessible(minecraft.getClass(), "getConnection").invoke(minecraft);
					accessible(connection.getClass(), "updateSearchTrees").invoke(connection);
					ForbricLog.info("[Forbric/ClientSmoke] creative search: rebuilt the search trees the way a "
							+ "language change does (ClientPacketListener.updateSearchTrees)");
				}
				case 7 -> creativeSearchPass(minecraft, cl, new String[] {queries[0]}, "after the language rebuild");
				default -> {
					screenshotCreative(minecraft, "after the language rebuild, showing '" + queries[0].trim() + "'");
					setScreen(minecraft, null);
				}
			}
		} catch (Throwable t) {
			creativeStage = 9;
			Caught.warn(t, "[Forbric/ClientSmoke] creative search probe failed at step " + step);
		}
	}

	/** Creative on the SERVER's player: the game mode is server-authoritative and reaches the client as a packet. */
	private static void makeCreative(Object minecraft, ClassLoader cl) throws Exception {
		Object server = accessible(minecraft.getClass(), "getSingleplayerServer").invoke(minecraft);
		if (server == null) {
			ForbricLog.info("[Forbric/ClientSmoke] creative search: no integrated server — using the player's own mode");
			return;
		}
		Object list = accessible(server.getClass(), "getPlayerList").invoke(server);
		java.util.List<?> players = (java.util.List<?>) accessible(list.getClass(), "getPlayers").invoke(list);
		if (players.isEmpty()) return;
		Object p = players.get(0);
		Class<?> gameType = Class.forName("net.minecraft.world.level.GameType", true, cl);
		Object creative = Enum.valueOf(gameType.asSubclass(Enum.class), "CREATIVE");
		onServer(server, () -> {
			Object changed = accessible(p.getClass(), "setGameMode", gameType).invoke(p, creative);
			ForbricLog.info("[Forbric/ClientSmoke] creative search: server player set to CREATIVE (changed=%s)", changed);
		});
	}

	private static void openCreativeScreen(Object minecraft, Object player, ClassLoader cl) throws Exception {
		boolean infinite = (boolean) accessible(player.getClass(), "hasInfiniteMaterials").invoke(player);
		Object connection = accessible(minecraft.getClass(), "getConnection").invoke(minecraft);
		Object features = accessible(connection.getClass(), "enabledFeatures").invoke(connection);
		Object options = fieldValue(minecraft, "options");
		Object opTab = accessible(options.getClass(), "operatorItemsTab").invoke(options);
		boolean op = (Boolean) accessible(opTab.getClass(), "get").invoke(opTab);
		Class<?> screenCls = Class.forName(
				"net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen", true, cl);
		Object screen = screenCls.getConstructor(Class.forName("net.minecraft.client.player.LocalPlayer", false, cl),
				Class.forName("net.minecraft.world.flag.FeatureFlagSet", false, cl), boolean.class)
				.newInstance(player, features, op);
		setScreen(minecraft, screen);
		Object searchTab = searchTab(cl);
		Object shown = currentScreen(minecraft);
		ForbricLog.info("[Forbric/ClientSmoke] creative search: opened %s (client creative=%s); search tab holds "
						+ "%d item(s); %s", shown == null ? "<nothing>" : shown.getClass().getSimpleName(), infinite,
				((java.util.Collection<?>) accessible(searchTab.getClass(), "getDisplayItems").invoke(searchTab)).size(),
				searchTreeCensus(connection, cl));
	}

	/** Every query in turn, typed into the search tab's box, and what the grid shows after each. */
	private static void creativeSearchPass(Object minecraft, ClassLoader cl, String[] queries, String label)
			throws Exception {
		Object screen = currentScreen(minecraft);
		if (screen == null || !screen.getClass().getSimpleName().equals("CreativeModeInventoryScreen")) {
			ForbricLog.warn("[Forbric/ClientSmoke] creative search %s: the creative screen is not open (%s)", label,
					screen == null ? "<nothing>" : screen.getClass().getName());
			return;
		}
		selectCreativeTab(screen, searchTab(cl));
		// The first query last, so the grid a following screenshot captures is the one the gate names.
		for (int i = queries.length - 1; i >= 0; i--) {
			String query = queries[i].trim();
			ForbricLog.info("[Forbric/ClientSmoke] creative search %s: '%s' -> %s", label, query,
					typeIntoCreativeSearch(screen, cl, query));
		}
	}

	/**
	 * The first query in each tab other than the search tab that has a search bar — a mod's own searchable tab
	 * reads a tree filed under ITS key, a different entry from the search tab's.
	 */
	private static void creativeSearchOtherTabs(Object minecraft, ClassLoader cl, String query) throws Exception {
		Object screen = currentScreen(minecraft);
		if (screen == null) return;
		Object searchTab = searchTab(cl);
		java.util.List<?> tabs = (java.util.List<?>) Class.forName("net.minecraft.world.item.CreativeModeTabs", true, cl)
				.getMethod("allTabs").invoke(null);
		int searched = 0;
		for (Object tab : tabs) {
			if (tab == searchTab || !(boolean) accessible(tab.getClass(), "hasSearchBar").invoke(tab)) continue;
			searched++;
			selectCreativeTab(screen, tab);
			ForbricLog.info("[Forbric/ClientSmoke] creative search in tab %s (%d item(s)): '%s' -> %s",
					accessible(tab.getClass(), "getDisplayName").invoke(tab),
					((java.util.Collection<?>) accessible(tab.getClass(), "getDisplayItems").invoke(tab)).size(), query,
					typeIntoCreativeSearch(screen, cl, query));
		}
		if (searched == 0) ForbricLog.info("[Forbric/ClientSmoke] creative search: no tab besides the search tab has "
				+ "a search bar");
		selectCreativeTab(screen, searchTab);
		typeIntoCreativeSearch(screen, cl, query);
	}

	private static Object searchTab(ClassLoader cl) throws Exception {
		return Class.forName("net.minecraft.world.item.CreativeModeTabs", true, cl).getMethod("searchTab").invoke(null);
	}

	private static void selectCreativeTab(Object screen, Object tab) throws Exception {
		accessible(screen.getClass(), "selectTab", tab.getClass()).invoke(screen, tab);
	}

	/**
	 * Clears the box and types {@code query} one character at a time through the screen's own {@code charTyped},
	 * which is what refreshes the grid — a {@code setValue} would change the text and leave the grid alone.
	 * Returns the grid: how many stacks, and the first few ids.
	 */
	private static String typeIntoCreativeSearch(Object screen, ClassLoader cl, String query) throws Exception {
		Object box = fieldValue(screen, "searchBox");
		if (box == null) return "<no search box>";
		setField(screen, "ignoreTextInput", false);
		box.getClass().getMethod("setValue", String.class).invoke(box, "");
		Class<?> charEvent = Class.forName("net.minecraft.client.input.CharacterEvent", true, cl);
		Method charTyped = accessible(screen.getClass(), "charTyped", charEvent);
		boolean typed = true;
		for (int i = 0; i < query.length(); ) {
			int cp = query.codePointAt(i);
			typed &= (boolean) charTyped.invoke(screen, charEvent.getConstructor(int.class).newInstance(cp));
			i += Character.charCount(cp);
		}
		Object value = box.getClass().getMethod("getValue").invoke(box);
		Object menu = screen.getClass().getMethod("getMenu").invoke(screen);
		java.util.List<?> items = (java.util.List<?>) fieldValue(menu, "items");
		StringBuilder first = new StringBuilder();
		for (int i = 0; items != null && i < Math.min(5, items.size()); i++) {
			Object stack = items.get(i);
			if (first.length() > 0) first.append(", ");
			first.append(stack.getClass().getMethod("getItem").invoke(stack));
		}
		return String.format("%d item(s) [%s]%s", items == null ? -1 : items.size(), first,
				typed && query.equals(value) ? "" : " (box reads '" + value + "', typed=" + typed + ")");
	}

	/**
	 * Where the trees are, said as data: how many name and tag trees NeoForge's registry holds and whether the
	 * search tab's own keys are among them, and how many the merged class's private MinecraftForge map holds. The
	 * screen reads the first; a producer that wrote the second fed nothing the player sees.
	 */
	private static String searchTreeCensus(Object connection, ClassLoader cl) {
		StringBuilder out = new StringBuilder();
		try {
			Class<?> trees = Class.forName("net.minecraft.client.multiplayer.SessionSearchTrees", true, cl);
			Object names = trees.getField("CREATIVE_NAMES").get(null);
			Object tags = trees.getField("CREATIVE_TAGS").get(null);
			Class<?> neo = Class.forName(net.forbric.api.ForeignType.CREATIVE_SEARCH_REGISTRY.binary(
					net.forbric.api.Ecosystem.NEOFORGE), true, cl);
			java.util.Map<?, ?> neoNames = (java.util.Map<?, ?>) staticField(neo, "NAME_SEARCH_TREES");
			java.util.Map<?, ?> neoTags = (java.util.Map<?, ?>) staticField(neo, "TAG_SEARCH_TREES");
			out.append("neoforge name trees=").append(neoNames.size()).append(" (search tab: ")
					.append(neoNames.containsKey(names)).append("), tag trees=").append(neoTags.size())
					.append(" (search tab: ").append(neoTags.containsKey(tags)).append(')');
			Object session = accessible(connection.getClass(), "searchTrees").invoke(connection);
			Object forge = fieldValue(session, "creativeSearch");
			if (forge instanceof java.util.Map<?, ?> map) out.append("; minecraftforge map=").append(map.size());
		} catch (Throwable t) {
			Caught.expected(t);
			out.append("<census unreadable: ").append(t).append('>');
		}
		return out.toString();
	}

	private static Object staticField(Class<?> owner, String name) throws ReflectiveOperationException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(null);
	}

	private static void screenshotCreative(Object minecraft, String moment) {
		try {
			Class.forName("net.minecraft.client.Screenshot", true, minecraft.getClass().getClassLoader())
					.getMethod("grab", minecraft.getClass(), boolean.class).invoke(null, minecraft, false);
			ForbricLog.info("[Forbric/ClientSmoke] creative search: screenshot requested %s", moment);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/ClientSmoke] creative search: could not take a screenshot %s", moment);
		}
	}

	/**
	 * What the game would put on its window. Read from {@code createTitle} itself rather than from the window, which
	 * only has a setter — and that method is the one the merged base carries a single loader's patch of, so this is
	 * the only place the result is observable at all.
	 */
	private static void reportWindowTitle(Object minecraft) {
		try {
			java.lang.reflect.Method createTitle = minecraft.getClass().getDeclaredMethod("createTitle");
			createTitle.setAccessible(true);
			ForbricLog.info("[Forbric/ClientSmoke] window title: %s", createTitle.invoke(minecraft));
		} catch (ReflectiveOperationException | RuntimeException e) {
			Caught.warn(e, "[Forbric/ClientSmoke] could not read the window title");
		}
	}

	/** How many blocks the client is currently drawing a break overlay for — what the render frame extracts. */
	private static void reportBreakProgress(Object level) {
		if (level == null) return;
		try {
			Object progress = level.getClass().getMethod("destructionProgress").invoke(level);
			int showing = progress == null ? 0 : (int) progress.getClass().getMethod("size").invoke(progress);
			ForbricLog.info("[Forbric/ClientSmoke] mining: %d block(s) showing break progress", showing);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Caught.warn(e, "[Forbric/ClientSmoke] could not read the break-progress map");
		}
	}

	/** A one-boolean call on the game object; used to hold a control down across ticks. */
	private static void invokeWithBoolean(Object owner, String name, boolean value) {
		try {
			java.lang.reflect.Method method = owner.getClass().getDeclaredMethod(name, boolean.class);
			method.setAccessible(true);
			method.invoke(owner, value);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Caught.warn(e, "[Forbric/ClientSmoke] could not call %s(%s)", name, value);
		}
	}

	private static void invokeNoArg(Object owner, String name) {
		for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
			for (Method method : c.getDeclaredMethods()) {
				if (!method.getName().equals(name) || method.getParameterCount() != 0) continue;
				try {
					method.setAccessible(true);
					method.invoke(owner);
				} catch (ReflectiveOperationException | RuntimeException e) {
					Caught.warn(e, "[Forbric/ClientSmoke] could not invoke Minecraft." + name);
				}
				return;
			}
		}
		ForbricLog.warn("[Forbric/ClientSmoke] no no-arg Minecraft.%s to invoke — the run will not end on its own",
				name);
	}
}
