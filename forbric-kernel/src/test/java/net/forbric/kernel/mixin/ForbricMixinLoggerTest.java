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

package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.spongepowered.asm.logging.Level;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.throwables.InvalidMixinException;

/**
 * Mixin's own WARN and stack for a mixin the kernel superseded goes to DEBUG — only at WARN, only while the table
 * and the repair's switch name a replacement, and never for any other mixin.
 */
@ResourceLock("system-properties")
class ForbricMixinLoggerTest {
	private static final String SUPERSEDED =
			"net.fabricmc.fabric.mixin.resource.conditions.SimpleJsonResourceReloadListenerMixin";
	private static final String MESSAGE = "Mixin apply for mod fabric-resource-conditions-api-v1 failed "
			+ "fabric-resource-conditions-api-v1.mixins.json:SimpleJsonResourceReloadListenerMixin -> "
			+ "net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener: InvalidInjectionException";

	@AfterEach
	void reset() {
		for (String property : new String[] { ForbricMixinLogger.QUIET_PROPERTY, SupersededMixins.PROPERTY,
				"forbric.fabricConditions", KernelMixinErrorHandler.PROPERTY }) {
			System.clearProperty(property);
		}
	}

	@Test
	void theSupersededMixinsApplyFailureIsNotRepeatedAsAWarnWithAStack() {
		String out = logged(Level.WARN, failure(SUPERSEDED));
		assertFalse(out.contains("Mixin apply for mod"), out);
		assertFalse(out.contains("\tat "), "no stack trace for a failure the kernel already reported: " + out);
	}

	@Test
	void anyOtherMixinsFailureKeepsMixinsOwnWarnAndStack() {
		String out = logged(Level.WARN, failure("a.b.SomeOtherMixin"));
		assertTrue(out.contains("Mixin apply for mod") && out.contains("\tat "), out);
	}

	@Test
	void onlyARelaxedWarnIsQuietedNeverAnError() {
		assertTrue(ForbricMixinLogger.supersededFailure(Level.WARN, failure(SUPERSEDED)));
		assertFalse(ForbricMixinLogger.supersededFailure(Level.ERROR, failure(SUPERSEDED)),
				"an ERROR is a required config stopping the game");
		assertFalse(ForbricMixinLogger.supersededFailure(Level.WARN, new IllegalStateException("not Mixin's")));
		assertFalse(ForbricMixinLogger.supersededFailure(Level.WARN, null));
	}

	@Test
	void eachSwitchBringsTheReportBack() {
		for (String property : new String[] { ForbricMixinLogger.QUIET_PROPERTY, SupersededMixins.PROPERTY,
				"forbric.fabricConditions", KernelMixinErrorHandler.PROPERTY }) {
			System.setProperty(property, "off");
			assertFalse(ForbricMixinLogger.supersededFailure(Level.WARN, failure(SUPERSEDED)),
					property + "=off must bring Mixin's report back");
			String out = logged(Level.WARN, failure(SUPERSEDED));
			assertTrue(out.contains("Mixin apply for mod") && out.contains("\tat "), property + ": " + out);
			System.clearProperty(property);
		}
	}

	private static String logged(Level level, Throwable t) {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		PrintStream sink = new PrintStream(buffer, true, StandardCharsets.UTF_8);
		PrintStream out = System.out, err = System.err;
		try {
			System.setOut(sink);
			System.setErr(sink);
			new ForbricMixinLogger("mixin").log(level, MESSAGE, t);
		} finally {
			System.setOut(out);
			System.setErr(err);
		}
		return buffer.toString(StandardCharsets.UTF_8);
	}

	private static InvalidMixinException failure(String mixinClass) {
		IMixinInfo info = (IMixinInfo) Proxy.newProxyInstance(ForbricMixinLoggerTest.class.getClassLoader(),
				new Class<?>[] { IMixinInfo.class }, (proxy, method, args) -> switch (method.getName()) {
					case "getClassName" -> mixinClass;
					case "getName" -> mixinClass.substring(mixinClass.lastIndexOf('.') + 1);
					case "toString" -> mixinClass;
					default -> throw new UnsupportedOperationException(method.getName());
				});
		return new InvalidMixinException(info, "Invalid descriptor on " + mixinClass);
	}
}
