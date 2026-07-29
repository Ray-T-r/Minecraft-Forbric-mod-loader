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

package net.forbric.kernel.util;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.IllegalFormatException;

/**
 * Central diagnostics sink for the Forbric kernel — self-contained, no loader dependency.
 *
 * <p>Stock launchers (PCL2/HMCL) do NOT capture the game process's {@code System.out} into {@code logs/latest.log}
 * — that file is the game's log4j output — so bare {@code System.out.println} from a loader is invisible in the
 * only log a bug report has. When Minecraft's log4j is on the classpath this helper routes there (lines land in
 * {@code latest.log} with the normal {@code [thread/LEVEL]} formatting); otherwise (unit tests, {@code --scan}
 * tooling, pre-game bootstrap) it falls back to {@code System.err}.
 *
 * <p>Unlike the old substrate version this does NOT touch {@code net.fabricmc.loader.impl.*}: the sovereign kernel
 * never boots fabric-loader. The log4j binding is reached reflectively so the boot-side jar carries no hard
 * dependency on it and works in a plain JVM.
 *
 * <p>Message formatting matches the previous contract exactly: {@link String#format} placeholders ({@code %s},
 * {@code %d}, …), and when the final varargs element is a {@link Throwable} in excess of the format's required
 * argument count it is logged as the exception rather than substituted.
 *
 * <p>{@link #debug} lines are gated behind {@code -Dforbric.debug} so verbose bring-up tracing is opt-in.
 */
public final class ForbricLog {
	private static final boolean DEBUG = Boolean.getBoolean("forbric.debug");

	// log4j Logger + its (String) / (String,Throwable) overloads per level, resolved once. Null → System.err path.
	private static final Object LOG4J_LOGGER;
	private static final Method L4J_INFO, L4J_WARN, L4J_ERROR, L4J_INFO_T, L4J_WARN_T, L4J_ERROR_T;

	static {
		Object logger = null;
		Method info = null, warn = null, error = null, infoT = null, warnT = null, errorT = null;
		try {
			Class<?> logManager = Class.forName("org.apache.logging.log4j.LogManager");
			Class<?> loggerCls = Class.forName("org.apache.logging.log4j.Logger");
			logger = logManager.getMethod("getLogger", String.class).invoke(null, "Forbric");
			info = loggerCls.getMethod("info", String.class);
			warn = loggerCls.getMethod("warn", String.class);
			error = loggerCls.getMethod("error", String.class);
			infoT = loggerCls.getMethod("info", String.class, Throwable.class);
			warnT = loggerCls.getMethod("warn", String.class, Throwable.class);
			errorT = loggerCls.getMethod("error", String.class, Throwable.class);
		} catch (Throwable ignored) {
			// log4j absent (tests / tooling) — System.err fallback.
			logger = null;
		}
		LOG4J_LOGGER = logger;
		L4J_INFO = info; L4J_WARN = warn; L4J_ERROR = error;
		L4J_INFO_T = infoT; L4J_WARN_T = warnT; L4J_ERROR_T = errorT;
	}

	private ForbricLog() {
	}

	/** Whether {@code -Dforbric.debug} is set — guard expensive debug-only computation with this. */
	public static boolean debugEnabled() {
		return DEBUG;
	}

	public static void info(String msg) {
		emit(Level.INFO, msg, null);
	}

	public static void info(String format, Object... args) {
		logFormat(Level.INFO, format, args);
	}

	public static void warn(String msg) {
		emit(Level.WARN, msg, null);
	}

	public static void warn(String format, Object... args) {
		logFormat(Level.WARN, format, args);
	}

	public static void warn(String msg, Throwable exc) {
		emit(Level.WARN, msg, exc);
	}

	public static void error(String msg) {
		emit(Level.ERROR, msg, null);
	}

	public static void error(String format, Object... args) {
		logFormat(Level.ERROR, format, args);
	}

	public static void error(String msg, Throwable exc) {
		emit(Level.ERROR, msg, exc);
	}

	/** Verbose bring-up tracing; only emitted (at INFO) when {@code -Dforbric.debug} is set. */
	public static void debug(String msg) {
		if (DEBUG) emit(Level.INFO, msg, null);
	}

	public static void debug(String format, Object... args) {
		if (DEBUG) logFormat(Level.INFO, format, args);
	}

	private enum Level { INFO, WARN, ERROR }

	private static void logFormat(Level level, String format, Object... args) {
		String msg;
		Throwable exc;
		if (args.length == 0) {
			msg = format;
			exc = null;
		} else {
			Object lastArg = args[args.length - 1];
			Object[] newArgs;
			if (lastArg instanceof Throwable && getRequiredArgs(format) < args.length) {
				exc = (Throwable) lastArg;
				newArgs = Arrays.copyOf(args, args.length - 1);
			} else {
				exc = null;
				newArgs = args;
			}
			try {
				msg = String.format(format, newArgs);
			} catch (IllegalFormatException e) {
				msg = "Format error: fmt=[" + format + "] args=" + Arrays.toString(args);
			}
		}
		emit(level, msg, exc);
	}

	private static void emit(Level level, String msg, Throwable exc) {
		if (LOG4J_LOGGER != null) {
			try {
				Method m = switch (level) {
					case INFO -> exc == null ? L4J_INFO : L4J_INFO_T;
					case WARN -> exc == null ? L4J_WARN : L4J_WARN_T;
					case ERROR -> exc == null ? L4J_ERROR : L4J_ERROR_T;
				};
				if (exc == null) m.invoke(LOG4J_LOGGER, msg);
				else m.invoke(LOG4J_LOGGER, msg, exc);
				return;
			} catch (Throwable ignored) {
				// fall through to stderr
			}
		}
		java.io.PrintStream out = level == Level.INFO ? System.out : System.err;
		out.println("[Forbric/" + level + "] " + msg);
		if (exc != null) exc.printStackTrace(out);
	}

	// Mirrors fabric Log.getRequiredArgs: counts %-conversions that consume an argument (ignores %% and %n/%<).
	private static int getRequiredArgs(String format) {
		int ret = 0;
		boolean wasPct = false;
		for (int i = 0, max = format.length(); i < max; i++) {
			char c = format.charAt(i);
			if (c == '%') {
				wasPct = !wasPct;
			} else if (wasPct) {
				wasPct = false;
				if (c != 'n' && c != '<') ret++;
			}
		}
		return ret;
	}
}
