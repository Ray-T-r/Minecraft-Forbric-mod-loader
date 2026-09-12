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

package net.forbric.kernel.classloading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The parsing half of {@code -Dforbric.traceClassDefine}. The dumping half needs a real definition, which the
 * loader tests already cover; what can go wrong here is the SET — a stray space making a name never match, or an
 * unset property producing a non-empty set and putting work on the hot define path.
 */
class TraceClassDefineTest {

	@AfterEach
	void forget() {
		System.clearProperty("forbric.traceClassDefine");
	}

	@Test
	void unsetMeansTraceNothing() {
		System.clearProperty("forbric.traceClassDefine");
		assertTrue(ForbricClassLoader.traceDefineTargets().isEmpty());
	}

	@Test
	void blankMeansTraceNothing() {
		System.setProperty("forbric.traceClassDefine", "   ");
		assertTrue(ForbricClassLoader.traceDefineTargets().isEmpty());
	}

	@Test
	void namesAreSplitAndTrimmed() {
		System.setProperty("forbric.traceClassDefine",
				" net.minecraft.world.level.BlockGetter , net.minecraft.world.item.TooltipFlag ");
		assertEquals(Set.of("net.minecraft.world.level.BlockGetter", "net.minecraft.world.item.TooltipFlag"),
				ForbricClassLoader.traceDefineTargets());
	}

	@Test
	void emptyEntriesAreDropped() {
		System.setProperty("forbric.traceClassDefine", ",,net.minecraft.world.level.BlockGetter,,");
		assertEquals(Set.of("net.minecraft.world.level.BlockGetter"), ForbricClassLoader.traceDefineTargets());
	}
}
