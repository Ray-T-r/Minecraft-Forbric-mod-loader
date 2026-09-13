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
	/** Take a screenshot of the pause menu with the mods button on it, instead of pressing it. */
	public static final String MODS_BUTTON_SHOT = "forbric.clientSmokeModsButtonShot";
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
		return Boolean.getBoolean(ENABLED);
	}

	/**
	 * One client tick. {@code minecraft} is typed {@code Object} because this class is BOOT-side and cannot name
	 * {@code net.minecraft} types at compile time — the same widening-reference trick the other hooks use.
	 */
	public static void onClientTick(Object minecraft) {
		if (minecraft == null || stopRequested || !enabled()) return;
		try {
			tick(minecraft);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/ClientSmoke] tick hook failed: %s", String.valueOf(t));
		}
	}

	private static void tick(Object minecraft) {
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
			reportWindowTitle(minecraft);
		}
		if (ready && !drillDone && Boolean.getBoolean(DRILL)) drill(minecraft, player);
		if (ready) elytraCheck(minecraft, player);
		if (ready) screenshotIfDue(minecraft);
		if (ready) modsScreenIfDue(minecraft);
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
				setScreen(minecraft, null);
			}
		} catch (Throwable t) {
			modsScreenClosed = true;
			ForbricLog.warn("[Forbric/ClientSmoke] the unified Mods screen could not be opened", t);
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
			ForbricLog.warn("[Forbric/ClientSmoke] could not check elytra flight", t);
		}
	}

	/** Anything that touches the entity goes on the server's own thread; a race that usually works is worse. */
	private static void onServer(Object server, ThrowingRunnable body) throws Exception {
		java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
		accessible(server.getClass(), "execute", Runnable.class).invoke(server, (Runnable) () -> {
			try {
				body.run();
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/ClientSmoke] elytra step failed on the server thread", t);
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
			Class<?> rowCls = Class.forName(
					"net.minecraft.client.gui.screens.packs.TransferableSelectionList$PackEntry", true, cl);
			for (Object listed : listedRows(screen, rowCls)) {
				rows++;
				if (String.valueOf(listed).contains("forbric/")) forbricRows++;
			}
			ForbricLog.info("[Forbric/ClientSmoke] the resource-pack screen lists %d pack row(s), %d of them the "
					+ "kernel's ecosystem asset packs (repository holds %d selected)", rows, forbricRows,
					inRepository);
			setScreen(minecraft, null);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ClientSmoke] could not open the resource-pack screen", t);
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
			return;
		}
		for (Object child : (java.util.List<?>) children.invoke(node)) {
			if (child == null) continue;
			if (rowCls.isInstance(child)) {
				// PackEntry has no id accessor; its narration is the pack's own title, which for the kernel's
				// packs is the id itself.
				out.add(String.valueOf(child.getClass().getMethod("getNarration").invoke(child)));
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
			ForbricLog.warn("[Forbric/ClientSmoke] could not press the title screen's mods button", t);
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
			ForbricLog.warn("[Forbric/ClientSmoke] could not press the pause menu's mods button", t);
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
			ForbricLog.warn("[Forbric/ClientSmoke] could not double-click a row in the unified list", t);
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
	 * other two registries, which no screen on this instance had ever asked.
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
			ForbricLog.warn("[Forbric/ClientSmoke] could not open a config screen from the unified list", t);
		}
	}

	private static void shoot(Object minecraft) {
		try {
			Class.forName("net.minecraft.client.Screenshot", true, minecraft.getClass().getClassLoader())
					.getMethod("grab", minecraft.getClass(), boolean.class).invoke(null, minecraft, false);
			ForbricLog.info("[Forbric/ClientSmoke] screenshot of the pause menu's mods button requested");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ClientSmoke] could not screenshot the pause menu", t);
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
			ForbricLog.debug("[Forbric/ClientSmoke] cannot press %s: %s", name, String.valueOf(e));
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
			ForbricLog.debug("[Forbric/ClientSmoke] cannot rotate the player: %s", String.valueOf(e));
		}
	}

	private static void setPos(Object player, double x, double y, double z) {
		try {
			entityClass(player).getMethod("setPos", double.class, double.class, double.class).invoke(player, x, y, z);
		} catch (ReflectiveOperationException | RuntimeException e) {
			ForbricLog.warn("[Forbric/ClientSmoke] the control move could not be made — the positive control is void: " + e);
		}
	}

	private static Object invokeGetter(Object owner, String name) {
		try {
			return entityClass(owner).getMethod(name).invoke(owner);
		} catch (ReflectiveOperationException | RuntimeException e) {
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
			ForbricLog.warn("[Forbric/ClientSmoke] block probe failed: " + t);
		}
		probeHotbar(level);
	}

	/**
	 * Logs what the player holds in hotbar slot 0, by registry name. Item ids travel in every inventory packet and
	 * have no "neighbouring state" to hide an off-by-one in, so a server that gives the player one item and a client
	 * that reads back another is the plainest registry-id mismatch there is.
	 */
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
			ForbricLog.warn("[Forbric/ClientSmoke] hotbar probe failed: " + t);
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
			ForbricLog.warn("[Forbric/ClientSmoke] registry id probe failed: " + t);
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
	}

	private static Object fieldValue(Object owner, String name) {
		for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
			try {
				Field field = c.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(owner);
			} catch (NoSuchFieldException keepLooking) {
				continue;
			} catch (ReflectiveOperationException | RuntimeException unreadable) {
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
		String want = System.getProperty(SCREENSHOTS, "");
		if (want.isBlank() || !shotsTaken.add(worldTicks)) return;
		boolean due = false;
		for (String tick : want.split(",")) {
			try {
				if (Integer.parseInt(tick.trim()) == worldTicks) {
					due = true;
					break;
				}
			} catch (RuntimeException malformed) {
				// A malformed entry costs that entry, not the run.
			}
		}
		if (!due) return;
		try {
			Class<?> screenshot = Class.forName("net.minecraft.client.Screenshot", true,
					minecraft.getClass().getClassLoader());
			java.lang.reflect.Method grab = screenshot.getMethod("grab", minecraft.getClass(), boolean.class);
			grab.invoke(null, minecraft, false);
			ForbricLog.info("[Forbric/ClientSmoke] screenshot requested at world tick %d", worldTicks);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ClientSmoke] could not take a screenshot at world tick %d: %s", worldTicks,
					String.valueOf(t));
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
			ForbricLog.debug("[Forbric/ClientSmoke] could not read the window title: %s", String.valueOf(e));
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
			ForbricLog.debug("[Forbric/ClientSmoke] could not read the break-progress map: %s", String.valueOf(e));
		}
	}

	/** A one-boolean call on the game object; used to hold a control down across ticks. */
	private static void invokeWithBoolean(Object owner, String name, boolean value) {
		try {
			java.lang.reflect.Method method = owner.getClass().getDeclaredMethod(name, boolean.class);
			method.setAccessible(true);
			method.invoke(owner, value);
		} catch (ReflectiveOperationException | RuntimeException e) {
			ForbricLog.debug("[Forbric/ClientSmoke] could not call %s(%s): %s", name, value, String.valueOf(e));
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
					ForbricLog.warn("[Forbric/ClientSmoke] could not invoke Minecraft." + name, e);
				}
				return;
			}
		}
		ForbricLog.warn("[Forbric/ClientSmoke] no no-arg Minecraft.%s to invoke — the run will not end on its own",
				name);
	}
}
