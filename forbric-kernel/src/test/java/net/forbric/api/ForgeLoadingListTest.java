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

package net.forbric.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The three states, because the whole point of this holder is that "empty" and "not known yet" are different.
 *
 * <p>Collapsing them is the trap the fix exists to avoid: an empty {@code LoadingModList} is frozen into a
 * {@code static final} the first time anything reads it, so answering "empty" while the real answer is merely not
 * computed yet costs MinecraftForge its entire mod list for the run — silently, and with the handshake telling
 * every peer the instance runs no mods.
 */
class ForgeLoadingListTest {
	@BeforeEach
	@AfterEach
	void clear() {
		ForgeLoadingList.reset();
	}

	@Test
	void readingBeforeAnyPublishThrowsRatherThanAnsweringEmpty() {
		assertFalse(ForgeLoadingList.isPublished());
		assertEquals(-1, ForgeLoadingList.publishedModCount());

		IllegalStateException files = assertThrows(IllegalStateException.class, ForgeLoadingList::modFiles);
		IllegalStateException mods = assertThrows(IllegalStateException.class, ForgeLoadingList::modInfos);
		// The message is the diagnosis: this exception is the one that poisons the holder, so it has to say so.
		assertTrue(files.getMessage().contains("before the kernel published one"), files.getMessage());
		assertTrue(mods.getMessage().contains("permanently erroneous"), mods.getMessage());
	}

	@Test
	void anEmptyPublishIsAnAnswerAndReadsBackEmpty() {
		ForgeLoadingList.publish(List.of(), List.of());

		assertTrue(ForgeLoadingList.isPublished(), "an empty list is still a published answer");
		assertEquals(0, ForgeLoadingList.publishedModCount());
		assertEquals(List.of(), ForgeLoadingList.modFiles());
		assertEquals(List.of(), ForgeLoadingList.modInfos());
	}

	@Test
	void publishHandsBackExactlyWhatWasPublished() {
		ForgeLoadingList.publish(List.of("fileA", "fileB"), List.of("modA", "modB", "modC"));

		assertEquals(List.of("fileA", "fileB"), ForgeLoadingList.modFiles());
		assertEquals(List.of("modA", "modB", "modC"), ForgeLoadingList.modInfos());
		assertEquals(3, ForgeLoadingList.publishedModCount());
	}

	@Test
	void theFirstPublishWins() {
		ForgeLoadingList.publish(List.of("early"), List.of("earlyMod"));
		ForgeLoadingList.publish(List.of("late"), List.of("lateMod", "another"));

		// The holder's INSTANCE may already have been built from the first publish, and it is final. Letting the
		// second one through would leave this field and the holder describing different instances.
		assertEquals(List.of("early"), ForgeLoadingList.modFiles());
		assertEquals(1, ForgeLoadingList.publishedModCount());
	}

	@Test
	void aNullListIsRefusedSoAnAbsentAnswerStaysAbsent() {
		assertThrows(IllegalArgumentException.class, () -> ForgeLoadingList.publish(null, List.of()));
		assertThrows(IllegalArgumentException.class, () -> ForgeLoadingList.publish(List.of(), null));
		assertFalse(ForgeLoadingList.isPublished(), "a refused publish must not count as an answer");
	}

	@Test
	void aPublishedListIsCopiedSoALaterMutationCannotChangeIt() {
		List<String> live = new java.util.ArrayList<>(List.of("modA"));
		ForgeLoadingList.publish(List.of("fileA"), live);
		live.add("snuckIn");

		assertEquals(List.of("modA"), ForgeLoadingList.modInfos());
	}
}
