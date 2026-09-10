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
