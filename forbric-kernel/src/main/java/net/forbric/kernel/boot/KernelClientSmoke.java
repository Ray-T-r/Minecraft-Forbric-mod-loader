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

	private static Object lastLevel;
	private static int worldTicks;
	private static boolean joined;
	private static boolean ready;
	private static boolean disconnectRequested;
	private static boolean stopRequested;

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
			if (disconnectRequested) {
				disconnectRequested = false;
				stopRequested = true;
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
		if (!joined) {
			joined = true;
			ForbricLog.info("[Forbric/ClientSmoke] joined world via quick-play: %s",
					System.getProperty(WORLD, "<quick-play>"));
		}
		if (!ready && worldTicks >= Integer.getInteger(READY_TICKS, 60)) {
			ready = true;
			ForbricLog.info("[Forbric/ClientSmoke] client-ready after %d world tick(s)", worldTicks);
		}
		if (!disconnectRequested && worldTicks >= Integer.getInteger(DISCONNECT_TICKS, 120)) {
			disconnectRequested = true;
			ForbricLog.info("[Forbric/ClientSmoke] requesting clean disconnect after %d world tick(s)", worldTicks);
			invokeNoArg(minecraft, "disconnectWithSavingScreen");
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
