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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What a logged line turns into, and — the part that was broken — whether the exception survives.
 *
 * <p>A message built by concatenation can easily contain a literal {@code %}: a file name, a mod id, "100%
 * done". The conversion counter treats that as a format conversion, so a trailing exception looked like the
 * argument for it, formatting then failed, and the old fallback replaced the whole message with "Format error:
 * …" and dropped the exception. A warning about a real failure became a warning about the logger, with no stack
 * trace and nothing pointing back at the cause.
 */
class ForbricLogRenderTest {

	@Test
	void aLiteralPercentKeepsBothTheMessageAndTheException() {
		Throwable cause = new IllegalStateException("the real failure");

		ForbricLog.Rendered rendered = ForbricLog.render("could not read 100% of pack.mcmeta", cause);

		assertSame(cause, rendered.thrown(), "the exception is the whole reason the line was logged");
		assertEquals("could not read 100% of pack.mcmeta", rendered.message(),
				"the message was never a format string; it has to survive as written");
	}

	@Test
	void aRealFormatStringStillFormats() {
		ForbricLog.Rendered rendered = ForbricLog.render("loaded %d mod(s) from %s", 12, "mods/");

		assertEquals("loaded 12 mod(s) from mods/", rendered.message());
		assertNull(rendered.thrown());
	}

	@Test
	void aRealFormatStringWithATrailingExceptionKeepsBoth() {
		Throwable cause = new RuntimeException("boom");

		ForbricLog.Rendered rendered = ForbricLog.render("mod %s failed", "jei", cause);

		assertEquals("mod jei failed", rendered.message());
		assertSame(cause, rendered.thrown());
	}

	@Test
	void aMessageWithNoArgumentsIsLeftAlone() {
		// Including one full of percent signs: with no arguments there is nothing to format.
		assertEquals("50% done, 50% left", ForbricLog.render("50% done, 50% left").message());
		assertNull(ForbricLog.render("plain").thrown());
	}

	@Test
	void anUnformattableMessageKeepsItsOtherArgumentsVisible() {
		// No exception to rescue here, so the arguments would otherwise vanish along with the failed format.
		ForbricLog.Rendered rendered = ForbricLog.render("progress 100% for %q", "jei");

		assertTrue(rendered.message().startsWith("progress 100% for %q"));
		assertTrue(rendered.message().contains("jei"), "the argument has to still be readable somewhere");
		assertNull(rendered.thrown());
	}

	@Test
	void anEscapedPercentFormatsAsUsual() {
		ForbricLog.Rendered rendered = ForbricLog.render("%d%% complete", 75);

		assertEquals("75% complete", rendered.message());
	}
}
