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

package net.forbric.loader.impl.util;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Central diagnostics sink for Forbric's Forge/Fabric bridge drivers.
 *
 * <p>Historically the drivers printed to {@code System.out}. Stock launchers (PCL2/HMCL) do NOT capture the
 * game process's stdout into {@code logs/latest.log} — that file is the game's log4j output — so every Forbric
 * driver line was invisible in the only log a user (or a bug report) has. That blind spot repeatedly caused
 * misdiagnosis. Routing through the Fabric loader's {@link Log} facility instead lands Forbric lines in
 * {@code latest.log} with the normal {@code [thread/LEVEL]} formatting.
 *
 * <p>Classloader note: {@link Log} lives under {@code net.fabricmc.loader.*}, which Knot always delegates to the
 * parent classloader. This helper touches ONLY {@link Log} (no game/intermediary types), so it is safe to be
 * parent-loaded in the loader-core jar and still callable from the Knot-loaded {@code forbricruntime} drivers.
 *
 * <p>{@link #debug} lines are gated behind {@code -Dforbric.debug} so verbose bring-up tracing is opt-in.
 */
public final class ForbricLog {
	/** Custom log context so Forbric lines are attributable; the game's log pattern shows the level/thread. */
	private static final LogCategory CATEGORY = LogCategory.createCustom("Forbric");
	private static final boolean DEBUG = Boolean.getBoolean("forbric.debug");

	private ForbricLog() {
	}

	/** Whether {@code -Dforbric.debug} is set — guard expensive debug-only computation with this. */
	public static boolean debugEnabled() {
		return DEBUG;
	}

	public static void info(String msg) {
		Log.info(CATEGORY, msg);
	}

	public static void info(String format, Object... args) {
		Log.info(CATEGORY, format, args);
	}

	public static void warn(String msg) {
		Log.warn(CATEGORY, msg);
	}

	public static void warn(String format, Object... args) {
		Log.warn(CATEGORY, format, args);
	}

	public static void warn(String msg, Throwable exc) {
		Log.warn(CATEGORY, msg, exc);
	}

	public static void error(String msg) {
		Log.error(CATEGORY, msg);
	}

	public static void error(String format, Object... args) {
		Log.error(CATEGORY, format, args);
	}

	public static void error(String msg, Throwable exc) {
		Log.error(CATEGORY, msg, exc);
	}

	/** Verbose bring-up tracing; only emitted (at INFO) when {@code -Dforbric.debug} is set. */
	public static void debug(String msg) {
		if (DEBUG) Log.info(CATEGORY, msg);
	}

	public static void debug(String format, Object... args) {
		if (DEBUG) Log.info(CATEGORY, format, args);
	}
}
