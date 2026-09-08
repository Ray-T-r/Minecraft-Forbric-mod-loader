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

	private static Object lastLevel;
	private static int worldTicks;
	private static boolean joined;
	private static boolean ready;
	private static boolean disconnectRequested;
	private static boolean stopRequested;
	private static int drillTick = -1;
	private static boolean drillDone;
	private static boolean probed;
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
		}
		if (ready && !drillDone && Boolean.getBoolean(DRILL)) drill(minecraft, player);
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
