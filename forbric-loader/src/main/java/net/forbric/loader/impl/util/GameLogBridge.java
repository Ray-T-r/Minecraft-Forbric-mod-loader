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

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.log.LogHandler;
import net.fabricmc.loader.impl.util.log.LogLevel;

/**
 * Routes fabric-loader's {@link Log} — which {@link ForbricLog} writes to — into the game's log4j, the way
 * fabric-loader's own launcher does once the game is up.
 *
 * <p>Nothing else does that under the kernel, and fabric-loader's default handler is a buffer: it prints only once a
 * message at ERROR arrives (replaying what it held) or someone finishes its configuration, and otherwise keeps every
 * line until the JVM exits. Which is why a dedicated server's log never showed a single loader-side warning — the
 * Fabric-mirror exception that stalled a whole configuration handshake among them — while a client occasionally
 * printed a burst of them after some unrelated ERROR. Wired here, every line lands in the game log with the game's
 * own timestamp, thread and level, next to the kernel's.
 *
 * <p>Installs only when {@code Log} still holds its built-in handler (a launcher that already wired log4j keeps its
 * handler) and only when log4j is actually present (unit tests keep the built-in one). {@code Log.init} replays the
 * buffered lines into the new handler, so nothing logged before this class loaded is lost. Levels map one to one;
 * fabric-loader's DEBUG/TRACE are forwarded only under {@code -Dforbric.debug}, like {@link ForbricLog#debug}.
 */
final class GameLogBridge implements LogHandler {
	private static final String BUILTIN_HANDLER = "net.fabricmc.loader.impl.util.log.BuiltinLogHandler";
	private static final String LOGGER_NAME = "Forbric";

	private final Object logger;
	private final Method[] plain = new Method[LogLevel.values().length];
	private final Method[] withThrowable = new Method[LogLevel.values().length];

	private GameLogBridge(Object logger, Class<?> loggerClass) throws ReflectiveOperationException {
		this.logger = logger;
		for (LogLevel level : LogLevel.values()) {
			String method = log4jMethod(level);
			plain[level.ordinal()] = loggerClass.getMethod(method, String.class);
			withThrowable[level.ordinal()] = loggerClass.getMethod(method, String.class, Throwable.class);
		}
	}

	private static volatile boolean installed;

	static void installIfUnwired() {
		try {
			Field handlerField = Log.class.getDeclaredField("handler");
			handlerField.setAccessible(true);
			Object current = handlerField.get(null);
			if (current == null || !BUILTIN_HANDLER.equals(current.getClass().getName())) return;

			Class<?> logManager = Class.forName("org.apache.logging.log4j.LogManager");
			Class<?> loggerClass = Class.forName("org.apache.logging.log4j.Logger");
			Object logger = logManager.getMethod("getLogger", String.class).invoke(null, LOGGER_NAME);
			GameLogBridge bridge = new GameLogBridge(logger, loggerClass);
			Log.init(bridge); // replays whatever the built-in handler buffered, then closes it
			installed = true;
			bridge.log(0, LogLevel.INFO, LogCategory.GENERAL, "[Forbric/Log] fabric-loader's log is routed into the game "
					+ "log from here on (the built-in handler only printed after an ERROR; on a dedicated server that was never)",
					null, false, false);
		} catch (Throwable noLog4jOrNoSuchField) {
			// Tests and tooling have no log4j: the built-in handler stays. In a game that is a real loss of
			// diagnostics, so say so on stderr — the one channel that is always there.
			if (System.getProperty("forbric.debug") != null || System.getProperty("forbric.clientSmoke") != null
					|| System.getProperty("java.class.path", "").contains("forbric-kernel")) {
				System.err.println("[Forbric/Log] could not route fabric-loader's log into the game log: " + noLog4jOrNoSuchField);
			}
		}
	}

	/** Whether {@link #installIfUnwired()} wired log4j in this JVM. */
	static Boolean installed() {
		return installed;
	}

	/** A bridge over any object shaped like a log4j Logger — for tests, which have no log4j. */
	static GameLogBridge forTest(Object logger, Class<?> loggerClass) throws ReflectiveOperationException {
		return new GameLogBridge(logger, loggerClass);
	}

	private static String log4jMethod(LogLevel level) {
		switch (level) {
			case ERROR: return "error";
			case WARN: return "warn";
			case INFO: return "info";
			case DEBUG: return "debug";
			default: return "trace";
		}
	}

	@Override
	public void log(long time, LogLevel level, LogCategory category, String msg, Throwable exc, boolean fromReplay,
			boolean wasSuppressed) {
		try {
			if (exc == null) {
				plain[level.ordinal()].invoke(logger, msg);
			} else {
				withThrowable[level.ordinal()].invoke(logger, msg, exc);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			System.err.println("[" + level + "] [" + category + "]: " + msg);
			if (exc != null) exc.printStackTrace();
		}
	}

	@Override
	public boolean shouldLog(LogLevel level, LogCategory category) {
		return !level.isLessThan(LogLevel.INFO) || ForbricLog.debugEnabled();
	}

	@Override
	public void close() {
	}
}
