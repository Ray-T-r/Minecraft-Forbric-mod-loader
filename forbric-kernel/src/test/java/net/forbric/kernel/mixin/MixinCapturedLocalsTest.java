/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

/**
 * Architectury's CLIENT_RIGHT_CLICK_AIR captures {@code (InteractionHand[], int, int, InteractionHand, ItemStack)} at
 * vanilla's second {@code ItemStack.isEmpty} in {@code Minecraft.startUseItem}. NeoForge keeps its
 * {@code InteractionKeyMappingTriggered} event in slot 5, between the hand and the held item: Mixin's check of the
 * capture failed, the kernel softened it to a skip, and the event never fired. Each captured local now reads the merged
 * slot that holds the same value.
 */
class MixinCapturedLocalsTest {
	private static final String MINECRAFT = "net/minecraft/client/Minecraft";

	@Test void architecturysRightClickAirReadsTheHeldItemPastNeoForgesEvent() throws Exception {
		ClassNode merged = full(TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar"));
		ClassNode vanilla = full(TestFixtures.vanillaJar());
		ClassNode mixin = architectury();
		assertEquals(1, MixinCapturedLocals.adapt(mixin, name -> name.equals(MINECRAFT) ? merged : null,
				(ecosystem, name) -> name.equals(MINECRAFT) ? vanilla : null));
		MethodNode handler = StagedFabricMixinFixture.method(mixin, "rightClickAir");
		assertNull(MixinFit.value(MixinFit.injectorOf(handler), "locals"), "the capture is gone");
		List<Integer> indices = new java.util.ArrayList<>();
		for (int p = 1; p <= 5; p++) indices.add((Integer) MixinFit.value(handler.invisibleParameterAnnotations[p].getFirst(), "index"));
		assertEquals(List.of(1, 2, 3, 4, 6), indices, "array, length, index, hand — and the held item past the event in slot 5");
	}

	@Test void onVanillaTheCaptureStaysAsWritten() throws Exception {
		ClassNode vanilla = full(TestFixtures.vanillaJar());
		ClassNode mixin = architectury();
		assertEquals(0, MixinCapturedLocals.adapt(mixin, name -> vanilla, (ecosystem, name) -> vanilla));
		assertNotNull(MixinFit.value(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin, "rightClickAir")), "locals"));
	}

	private static ClassNode architectury() throws Exception {
		Path jar = Path.of("run/client-popular/mods/architectury-fabric-21.1.10.jar");
		TestFixtures.require(TestFixtures.Fixture.THIRD_PARTY, Files.isRegularFile(jar), "popular Architectury fixture");
		ClassNode mixin;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			mixin = MixinFit.parse(zip.getInputStream(zip.getEntry("dev/architectury/mixin/fabric/client/MixinMinecraft.class")).readAllBytes());
		}
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	private static ClassNode full(Path jar) throws Exception {
		TestFixtures.require(TestFixtures.Fixture.STAGED, Files.isRegularFile(jar), "game jar");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(MINECRAFT + ".class")).readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
			return node;
		}
	}
}
