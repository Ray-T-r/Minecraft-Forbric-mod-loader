/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CreateStructureMixinAdapterTest {
	@Test void setupIterationAndCleanupMoveTogetherToTheCalledEntityPlacementBody() throws Exception {
		var node = CreateGuestMixinFixture.mixin("com/zurrtum/create/mixin/StructureTemplateMixin");
		Map<String, String> hashes = new HashMap<>();
		for (String name : List.of("setProcessors", "getIterator", "clearProcessors")) hashes.put(name, MixinInstructionFingerprint.hash(MixinPlayerWorldCallbackAdapter.named(node, name)));
		assertEquals(3, MixinStructurePlacementAdapter.adapt(node, CarpetMixinAdapterTest::target));
		for (String name : hashes.keySet()) assertEquals(hashes.get(name), MixinInstructionFingerprint.hash(MixinPlayerWorldCallbackAdapter.named(node, name)));
		for (String name : List.of("getIterator", "clearProcessors")) assertEquals(List.of(MixinStructurePlacementAdapter.LIVE), MixinFit.value(MixinFit.injectorOf(MixinPlayerWorldCallbackAdapter.named(node, name)), "method"));
		CarpetMixinAdapterTest.verify(node);
		assertEquals(0, MixinStructurePlacementAdapter.adapt(node, CarpetMixinAdapterTest::target));
	}
	@Test void aMissingCallerRefusesAllThreeSelectorsTogether() throws Exception {
		var node = CreateGuestMixinFixture.mixin("com/zurrtum/create/mixin/StructureTemplateMixin"); byte[] before = CarpetMixinAdapterTest.bytes(node);
		assertEquals(0, MixinStructurePlacementAdapter.adapt(node, name -> {var target=CarpetMixinAdapterTest.target(name);target.methods.removeIf(m->m.name.equals("placeInWorld"));return target;}));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(node));
	}
}
