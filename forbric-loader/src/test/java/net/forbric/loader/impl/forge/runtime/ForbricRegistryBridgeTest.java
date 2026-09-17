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

package net.forbric.loader.impl.forge.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.DebugLevelSource;

class ForbricRegistryBridgeTest {
	@BeforeEach
	void resetFakeRuntime() {
		BuiltInRegistries.BLOCK.clear();
		DebugLevelSource.initCalls = 0;
		IdMapper<Object> idMap = net.neoforged.neoforge.registries.GameData.getBlockStateIDMap();
		idMap.clear();
	}

	@Test
	void verifyAtSyncBoundaryDoesNotRewriteMissingBlockStateIds() {
		Object stateA = "a0";
		Object stateB = "b0";
		BuiltInRegistries.BLOCK.add(new Block(List.of(stateA, stateB)));

		int missing = ForbricRegistryBridge.verifyNeoBlockStateIdsBound(getClass().getClassLoader());

		assertEquals(2, missing);
		assertEquals(0, net.neoforged.neoforge.registries.GameData.getBlockStateIDMap().size());
		assertEquals(0, DebugLevelSource.initCalls);
	}

	@Test
	void rebuildAfterFreezeRepairsMissingBlockStateIds() {
		Object stateA = "a0";
		Object stateB = "b0";
		Object stateC = "b1";
		BuiltInRegistries.BLOCK.add(new Block(List.of(stateA)));
		BuiltInRegistries.BLOCK.add(new Block(List.of(stateB, stateC)));

		int rebuilt = ForbricRegistryBridge.rebuildNeoBlockStateIdsIfMissing(getClass().getClassLoader());

		assertEquals(3, rebuilt);
		assertEquals(1, DebugLevelSource.initCalls);
		IdMapper<Object> idMap = net.neoforged.neoforge.registries.GameData.getBlockStateIDMap();
		assertEquals(0, ForbricRegistryBridge.verifyNeoBlockStateIdsBound(getClass().getClassLoader()));
		assertEquals(0, idMap.getId(stateA));
		assertEquals(1, idMap.getId(stateB));
		assertEquals(2, idMap.getId(stateC));
	}
}
