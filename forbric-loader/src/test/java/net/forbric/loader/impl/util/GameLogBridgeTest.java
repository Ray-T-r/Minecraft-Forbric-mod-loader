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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.log.LogLevel;

/** The bridge forwards each fabric-loader level to the matching log4j-shaped method, throwable and all. */
class GameLogBridgeTest {
	/** Shaped like org.apache.logging.log4j.Logger for the five methods the bridge resolves. */
	public static final class FakeLogger {
		final List<String> calls = new ArrayList<>();
		public void error(String m) { calls.add("error:" + m); }
		public void error(String m, Throwable t) { calls.add("error:" + m + ":" + t.getMessage()); }
		public void warn(String m) { calls.add("warn:" + m); }
		public void warn(String m, Throwable t) { calls.add("warn:" + m + ":" + t.getMessage()); }
		public void info(String m) { calls.add("info:" + m); }
		public void info(String m, Throwable t) { calls.add("info:" + m + ":" + t.getMessage()); }
		public void debug(String m) { calls.add("debug:" + m); }
		public void debug(String m, Throwable t) { calls.add("debug:" + m + ":" + t.getMessage()); }
		public void trace(String m) { calls.add("trace:" + m); }
		public void trace(String m, Throwable t) { calls.add("trace:" + m + ":" + t.getMessage()); }
	}

	@Test
	void forwardsEveryLevelWithAndWithoutThrowable() throws Exception {
		FakeLogger logger = new FakeLogger();
		GameLogBridge bridge = GameLogBridge.forTest(logger, FakeLogger.class);
		LogCategory category = LogCategory.createCustom("Forbric");

		bridge.log(0, LogLevel.WARN, category, "w", null, false, false);
		bridge.log(0, LogLevel.ERROR, category, "e", new IllegalStateException("boom"), false, false);
		bridge.log(0, LogLevel.INFO, category, "i", null, true, false);
		bridge.log(0, LogLevel.DEBUG, category, "d", null, false, false);
		bridge.log(0, LogLevel.TRACE, category, "t", null, false, false);

		assertEquals(List.of("warn:w", "error:e:boom", "info:i", "debug:d", "trace:t"), logger.calls);
	}

	@Test
	void debugAndTraceAreGatedOnTheForbricDebugFlag() throws Exception {
		GameLogBridge bridge = GameLogBridge.forTest(new FakeLogger(), FakeLogger.class);
		LogCategory category = LogCategory.createCustom("Forbric");
		assertTrue(bridge.shouldLog(LogLevel.INFO, category));
		assertTrue(bridge.shouldLog(LogLevel.WARN, category));
		assertEquals(ForbricLog.debugEnabled(), bridge.shouldLog(LogLevel.DEBUG, category));
		assertEquals(ForbricLog.debugEnabled(), bridge.shouldLog(LogLevel.TRACE, category));
	}

	@Test
	void installIsHarmlessWithoutLog4j() {
		// The test classpath has no log4j: the built-in handler must stay and logging must still work.
		GameLogBridge.installIfUnwired();
		ForbricLog.info("[Forbric] still logs through the built-in handler");
		assertFalse(GameLogBridge.installed());
		assertSame(Boolean.FALSE, GameLogBridge.installed());
	}
}
