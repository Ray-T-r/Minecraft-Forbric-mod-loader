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

package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

/**
 * Two anonymous classes could not keep one name through the byte merge, so NeoForge's copy carries a
 * {@code $forbricneo} suffix. A guest mixin names the vanilla one — the only name its platform has — and the
 * merged code that runs instantiates the twin, so the mixin applies to the half nothing calls.
 *
 * <p>Bad Packets paid for it: its {@code MixinCustomPacketPayload_1} gives that stream codec an interface its own
 * {@code ClientboundCustomPayloadPacket} mixin then casts to, so joining a world died with
 * {@code ClassCastException: CustomPacketPayload$1$forbricneo cannot be cast to ChannelCodecFinder$Holder} and
 * the client said only "Failed to connect to the server — Internal Exception: ExceptionInInitializerError".
 * fabric-api's own {@code CustomPayloadStreamCodecMixin} and {@code TagAppenderMixin} were binding to the same
 * dead half.
 */
@ResourceLock("system-properties")
class MixinMergedTwinTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final String TARGET = "net.minecraft.network.protocol.common.custom.CustomPacketPayload$1";

	@Test
	void aTargetWithATwinGainsItAndKeepsTheOriginal() {
		ClassNode mixin = mixinTargeting(TARGET);
		assertEquals(1, MixinMergedTwin.addTwins(mixin, present(TARGET + MixinMergedTwin.NEO_SUFFIX)));
		assertEquals(List.of(TARGET, TARGET + MixinMergedTwin.NEO_SUFFIX), targetsOf(mixin),
				"the vanilla-named class is still real, so the twin is ADDED, never substituted");
	}

	@Test
	void aTargetWithNoTwinIsLeftExactlyAsCompiled() {
		ClassNode mixin = mixinTargeting("net.minecraft.world.entity.Entity");
		assertEquals(0, MixinMergedTwin.addTwins(mixin, present()));
		assertEquals(List.of("net.minecraft.world.entity.Entity"), targetsOf(mixin));
	}

	@Test
	void aSecondPassAddsNothing() {
		ClassNode mixin = mixinTargeting(TARGET);
		var present = present(TARGET + MixinMergedTwin.NEO_SUFFIX);
		assertEquals(1, MixinMergedTwin.addTwins(mixin, present));
		assertEquals(0, MixinMergedTwin.addTwins(mixin, present), "a mixin read twice must not grow twice");
	}

	@Test
	void theSwitchLeavesTheTargetsAlone() {
		ClassNode mixin = mixinTargeting(TARGET);
		System.setProperty(MixinMergedTwin.PROPERTY, "off");
		try {
			assertEquals(0, MixinMergedTwin.addTwins(mixin, present(TARGET + MixinMergedTwin.NEO_SUFFIX)));
			assertEquals(List.of(TARGET), targetsOf(mixin));
		} finally {
			System.clearProperty(MixinMergedTwin.PROPERTY);
		}
	}

	@Test
	void aMixinWithNoTargetsListIsUntouched() {
		ClassNode mixin = new ClassNode();
		mixin.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "guest/Mixin", null, "java/lang/Object", null);
		AnnotationNode annotation = new AnnotationNode(MixinMergedTwin.MIXIN_DESC);
		annotation.values = new java.util.ArrayList<>(List.of("remap", Boolean.FALSE));
		mixin.invisibleAnnotations = new java.util.ArrayList<>(List.of(annotation));
		assertEquals(0, MixinMergedTwin.addTwins(mixin, present(TARGET + MixinMergedTwin.NEO_SUFFIX)));
	}

	@Test
	void theMergedBaseReallyCarriesTheTwinThisIsAbout() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			assertNotNull(zip.getEntry(TARGET.replace('.', '/') + ".class"), "the vanilla-named half must exist");
			assertNotNull(zip.getEntry((TARGET + MixinMergedTwin.NEO_SUFFIX).replace('.', '/') + ".class"),
					"the renamed twin must exist — if the merge stopped renaming, this adapter is dead code");
			long twins = zip.stream().map(java.util.zip.ZipEntry::getName)
					.filter(n -> n.contains(MixinMergedTwin.NEO_SUFFIX)).count();
			assertTrue(twins >= 1, "at least one renamed twin");
		}
	}

	private static java.util.function.Predicate<String> present(String... binaries) {
		Set<String> set = Set.of(binaries);
		return set::contains;
	}

	private static ClassNode mixinTargeting(String target) {
		ClassNode mixin = new ClassNode();
		mixin.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "guest/Mixin", null, "java/lang/Object", null);
		AnnotationNode annotation = new AnnotationNode(MixinMergedTwin.MIXIN_DESC);
		annotation.values = new java.util.ArrayList<>(
				List.of("targets", new java.util.ArrayList<>(List.of(target))));
		mixin.invisibleAnnotations = new java.util.ArrayList<>(List.of(annotation));
		return mixin;
	}

	@SuppressWarnings("unchecked")
	private static List<String> targetsOf(ClassNode mixin) {
		AnnotationNode annotation = mixin.invisibleAnnotations.get(0);
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			if ("targets".equals(annotation.values.get(i))) return (List<String>) annotation.values.get(i + 1);
		}
		throw new AssertionError("no targets");
	}
}
