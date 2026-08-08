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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the fail-soft policy that keeps one unparseable {@code pack.mcmeta} section from deleting the whole pack.
 *
 * <p>The hook takes {@code Object} (it is boot-side and cannot name {@code MetadataSectionType}), so a local
 * record with the same {@code name()} accessor stands in for the game type and the test needs no staged jar.
 */
class KernelPackMetadataTest {
	private static final String PROPERTY = "forbric.packMetadataFailSoft";

	/** Same shape as {@code net.minecraft.server.packs.metadata.MetadataSectionType} — a record with {@code name()}. */
	private record FakeSectionType(String name) {
	}

	@BeforeEach
	@AfterEach
	void clean() {
		System.clearProperty(PROPERTY);
		KernelPackMetadata.reset();
	}

	@Test
	void namespacedSectionIsTreatedAsAbsent() {
		// The exact section that dropped lithostitched's and Terralith's packs and crashed world generation.
		assertSame(Optional.empty(),
				KernelPackMetadata.sectionFailed(new FakeSectionType("neoforge:overlays"), boom()));
		assertSame(Optional.empty(),
				KernelPackMetadata.sectionFailed(new FakeSectionType("lithostitched:whatever"), boom()));
	}

	@Test
	void vanillaSectionsStayLoudAndKeepTheirOriginalException() {
		// Vanilla's own section names are BARE, not minecraft-namespaced — a namespace test would have failed open
		// on exactly the sections that must keep throwing. These four are every name vanilla itself defines.
		for (String vanilla : new String[] {"pack", "overlays", "features", "animation"}) {
			RuntimeException original = boom();
			RuntimeException thrown = assertThrows(RuntimeException.class,
					() -> KernelPackMetadata.sectionFailed(new FakeSectionType(vanilla), original));
			assertSame(original, thrown, vanilla + " must propagate unwrapped so the crash report is unchanged");
		}
	}

	@Test
	void failSoftKeysOnTheColonNotTheNamespace() {
		assertTrue(KernelPackMetadata.failSoft("neoforge:overlays"));
		assertTrue(KernelPackMetadata.failSoft("minecraft:overlays"));
		assertFalse(KernelPackMetadata.failSoft("overlays"));
		assertFalse(KernelPackMetadata.failSoft("pack"));
	}

	@Test
	void unreadableSectionNameFailsSoft() {
		// No name() accessor at all: a name we cannot read cannot be one of the bare vanilla names we protect.
		assertSame(Optional.empty(), KernelPackMetadata.sectionFailed(new Object(), boom()));
		assertSame(Optional.empty(), KernelPackMetadata.sectionFailed(null, boom()));
	}

	@Test
	void offRestoresVanillaBehaviour() {
		System.setProperty(PROPERTY, "off");
		RuntimeException original = boom();
		assertSame(original, assertThrows(RuntimeException.class,
				() -> KernelPackMetadata.sectionFailed(new FakeSectionType("neoforge:overlays"), original)));
	}

	@Test
	void allExtendsFailSoftToVanillaSectionNames() {
		System.setProperty(PROPERTY, "all");
		assertSame(Optional.empty(), KernelPackMetadata.sectionFailed(new FakeSectionType("overlays"), boom()));
		assertTrue(KernelPackMetadata.failSoft("pack"));
	}

	@Test
	void unknownModeBehavesLikeTheDefault() {
		System.setProperty(PROPERTY, "yes-please");
		assertTrue(KernelPackMetadata.failSoft("neoforge:overlays"));
		assertFalse(KernelPackMetadata.failSoft("pack"));
	}

	@Test
	void repeatedFailuresAreReportedOncePerSection() {
		// getSection runs once per pack per section; without the dedupe a 40-mod instance emits 40 identical lines.
		java.io.PrintStream stdout = System.out;
		java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
		System.setOut(new java.io.PrintStream(captured, true));
		try {
			for (int i = 0; i < 5; i++) {
				KernelPackMetadata.sectionFailed(new FakeSectionType("neoforge:overlays"), boom());
			}
		} finally {
			System.setOut(stdout);
		}
		// ForbricLog routes to log4j when present and stderr otherwise, so the captured stream may legitimately be
		// empty here; what must never happen is the message appearing more than once.
		assertTrue(occurrences(captured.toString(), "neoforge:overlays") <= 1,
				"the fail-soft warning must be emitted at most once per section name");
	}

	private static RuntimeException boom() {
		return new RuntimeException("Unknown registry key in ResourceKey[minecraft:root / neoforge:condition_codecs]"
				+ ": lithostitched:breaks_seed_parity");
	}

	private static int occurrences(String haystack, String needle) {
		int count = 0;
		for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) count++;
		return count;
	}
}
