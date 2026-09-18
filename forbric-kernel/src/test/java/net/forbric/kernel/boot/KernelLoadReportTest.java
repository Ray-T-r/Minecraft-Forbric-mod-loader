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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;

/**
 * The file a player reads when a mod did not load.
 *
 * <p>Rendering is tested rather than writing, so both languages can be asserted without a locale dance and so
 * the wording — which is the part that can be wrong in a way that costs someone an afternoon — is pinned.
 */
class KernelLoadReportTest {

	@Test
	void everyFailedModAppearsWithItsReasonAndItsJar() {
		String text = KernelLoadReport.render(false, List.of(
				failed("alpha", "Alpha Mod", "alpha-1.0.jar", "its @Mod constructor threw")));

		assertTrue(text.contains("Alpha Mod"), text);
		assertTrue(text.contains("alpha-1.0.jar"), "the jar is what a player removes, so it has to be named");
		assertTrue(text.contains("its @Mod constructor threw"), text);
		assertTrue(text.contains("1 mod(s) did not finish loading"), text);
	}

	@Test
	void aModThatOnlyDegradedIsNotCalledBroken() {
		String degraded = KernelLoadReport.render(false, List.of(
				new ModCatalog.Entry(Ecosystem.NEOFORGE, "beta", "Beta", "1.0", "", List.of(), "beta.jar", "", "",
						ModCatalog.Status.DEGRADED, "it threw during common setup")));

		assertTrue(degraded.contains("partly did not run"), degraded);
		assertFalse(degraded.contains("did not finish loading\n"),
				"collapsing DEGRADED into FAILED would tell a player their mod is not there when most of it is");

		// And the other direction, so this is not passing on wording that never differs.
		String failed = KernelLoadReport.render(false, List.of(failed("alpha", "Alpha", "a.jar", "x")));
		assertTrue(failed.contains("did not finish loading"), failed);
	}

	@Test
	void theReportSaysTheModIsStillPartlyPresent() {
		// The one claim in here that is easy to get wrong and expensive when it is. A withdrawn mod's classes ARE
		// loaded and its mixins ARE applied; isLoaded(id) deliberately still answers true. Saying "not running"
		// would send someone to reinstall what is already there.
		String text = KernelLoadReport.render(false, List.of(failed("alpha", "Alpha", "a.jar", "x")));

		assertTrue(text.contains("still partly present"), text);
		assertTrue(text.contains("not a crash report"),
				"the game did start, and a file that reads like a crash report says otherwise");
		assertFalse(text.contains("is not running"), text);
	}

	@Test
	void theReportIsWrittenInTheSystemLanguage() {
		String zh = KernelLoadReport.render(true, List.of(failed("alpha", "Alpha", "a.jar", "x")));
		String en = KernelLoadReport.render(false, List.of(failed("alpha", "Alpha", "a.jar", "x")));

		assertTrue(zh.contains("没有完成加载"), zh);
		assertTrue(zh.contains("怎么办"), "the what-to-do section is the reason the file exists");
		assertTrue(en.contains("What to do"), en);
		assertFalse(en.contains("没有完成加载"), "the two renderings must not bleed into each other");
	}

	@Test
	void aModWhoseNameIsItsIdIsNotPrintedTwice() {
		String same = KernelLoadReport.render(false, List.of(failed("alpha", "alpha", "a.jar", "x")));
		String different = KernelLoadReport.render(false, List.of(failed("alpha", "Alpha Mod", "a.jar", "x")));

		assertFalse(same.contains("alpha  (alpha)"), "'alpha (alpha)' reads like two different things");
		assertTrue(different.contains("Alpha Mod  (alpha)"),
				"when they differ, the id is what appears in the log the player is about to search");
	}

	@Test
	void aCleanRunRendersNothingToShow() {
		assertTrue(KernelLoadReport.render(false, List.of()).contains("0 mod(s)"),
				"the caller is what decides not to write a file; the renderer must still be total");
	}

	private static ModCatalog.Entry failed(String id, String name, String jar, String why) {
		return new ModCatalog.Entry(Ecosystem.FABRIC, id, name, "1.0", "", List.of(), jar, "", "",
				ModCatalog.Status.FAILED, why);
	}
}
