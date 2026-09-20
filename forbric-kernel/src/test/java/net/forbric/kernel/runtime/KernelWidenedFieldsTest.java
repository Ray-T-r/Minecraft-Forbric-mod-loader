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

package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Source-text pins on the two write shapes, in the {@code KernelGameTickEventsTest} shape: a {@code Mob} cannot be
 * constructed without a level, so {@code asMonster} is pinned by what it tests and returns, and {@code drain} by
 * which guava call it makes and which map merge it uses — the two things a mutation would change.
 */
class KernelWidenedFieldsTest {
	private static final Path SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWidenedFields.java");

	private static String bodyOf(String signature) throws Exception {
		String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
		int start = source.indexOf(signature);
		assertTrue(start >= 0, signature + " is gone from " + SOURCE + " — WidenedFieldTwinInjector calls it by name and "
				+ "descriptor, so its absence is a NoSuchMethodError at the first construction, not a compile error");
		int end = source.indexOf("\n\t}", start);
		assertTrue(end > start, "could not find the end of " + signature);
		return source.substring(start, end);
	}

	@Test
	void asMonsterIsAGuardedCastThatAnswersNullNotAClassCastException() throws Exception {
		String body = bodyOf("public static Monster asMonster(Mob mob)");
		assertTrue(body.contains("instanceof Monster"), "the guard");
		assertTrue(body.contains("return null;"), "a non-Monster reads as null for the vanilla-typed twin");
		assertTrue(!body.contains("(Monster) mob"), "never a bare cast: NeoForge's widening is a legitimate value");
	}

	@Test
	void drainMovesTheTwinsEntriesIntoTheMapWithoutOverridingWhatTheMapHas() throws Exception {
		String body = bodyOf("public static void drain(Map<Object, Object> map, ImmutableMap.Builder<Object, Object> twin)");
		assertTrue(body.contains("buildKeepingLast()"), "the builder is read without throwing on a duplicate key");
		assertTrue(body.contains("putIfAbsent"), "the map's own entries win — Fabric's copy-then-override order");
		assertTrue(!body.contains("map::put)") && !body.contains(".put("), "never a plain put: that would let a twin entry override an add()");
	}
}
