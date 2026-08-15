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

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;

/**
 * Covers {@link NeoEnumExtensionInjector}'s two decisions that are not NeoForge's compiled code: whether to
 * install at all, and how to write the node back.
 *
 * <p>The actual enum rewrite is {@code RuntimeEnumExtender}'s and is verified by running mods that declare
 * extensions (Tool Belt, Sophisticated Backpacks, Earth Mobs), not here.
 */
class NeoEnumExtensionInjectorTest {

	@Test
	void isNotInstalledWhenTheSpiIsAbsent() {
		// The test classpath has no net.neoforged.*, the same shape as a runtime without the enumextension package
		// or a Fabric-only instance. KernelBoot registers only a non-null injector, so returning null here is what
		// keeps the transform chain byte-identical on every instance that has no NeoForge enum extensions.
		assertNull(NeoEnumExtensionInjector.create(getClass().getClassLoader()));
	}

	@Test
	void computeFlagsMapOntoTheAsmWriterFlags() {
		assertEquals(-1, NeoEnumExtensionInjector.writerFlags("NO_REWRITE"), "NO_REWRITE must skip the write");
		assertEquals(0, NeoEnumExtensionInjector.writerFlags("SIMPLE_REWRITE"));
		assertEquals(ClassWriter.COMPUTE_MAXS, NeoEnumExtensionInjector.writerFlags("COMPUTE_MAXS"));
		assertEquals(ClassWriter.COMPUTE_FRAMES, NeoEnumExtensionInjector.writerFlags("COMPUTE_FRAMES"));
	}

	@Test
	void anUnknownFlagFallsToTheMostConservativeWriteNotToSkipping() {
		// The ClassNode has already been edited by the time the flags come back. Reading an unrecognised future
		// value as "no rewrite" would silently discard a transform that DID happen — the enum would load looking
		// untouched and the mod would fail on its own lookup with no hint of why.
		assertEquals(ClassWriter.COMPUTE_FRAMES, NeoEnumExtensionInjector.writerFlags("SOME_FUTURE_VALUE"));
		assertEquals(ClassWriter.COMPUTE_FRAMES, NeoEnumExtensionInjector.writerFlags(null));
	}
}
