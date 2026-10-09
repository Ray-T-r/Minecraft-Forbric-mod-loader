/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * {@code MixinBlockQueryAdapters} with the body each released mod was compiled against (vanilla 26.2) beside the real
 * merged one. Create Fly's six friction and explosion-resistance wraps pair, occurrence by occurrence, with NeoForge's
 * context-aware state queries and move as without that body. Origins' Apoli hooks the FIRST {@code Block.getFriction()}
 * in {@code LivingEntity.travelInAir} with an {@code @ModifyExpressionValue} ({@code ordinal = 0}): with the vanilla body
 * its point moves to NeoForge's {@code BlockState.getFriction(level, pos, entity)}, ordinal translated; without it the
 * ordinal is not guessed. No query name is listed anywhere.
 */
class BlockQueryNativeEvidenceTest {
	private static final BiFunction<Ecosystem, String, ClassNode> NATIVE = (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name);
	private static final String STATE_FRICTION = "Lnet/minecraft/world/level/block/state/BlockState;getFriction(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)F";

	@Test void createsReleasedWrapsPairWithTheVanillaBodies() throws Exception {
		for (var entry : Map.of("LivingEntityMixin", 2, "ItemEntityMixin", 1, "ExperienceOrbMixin", 1, "AbstractBoatMixin", 1,
				"LeashableMixin", 1, "ExplosionDamageCalculatorMixin", 1).entrySet()) {
			ClassNode node = CreateGuestMixinFixture.mixin("com/zurrtum/create/mixin/" + entry.getKey());
			assertEquals(entry.getValue(), MixinBlockQueryAdapters.adapt(node, CarpetMixinAdapterTest::target, NATIVE), entry.getKey());
			CarpetMixinAdapterTest.verify(node);
			assertEquals(0, MixinBlockQueryAdapters.adapt(node, CarpetMixinAdapterTest::target, NATIVE), entry.getKey() + " idempotence");
		}
	}

	@Test void apolisFirstFrictionHookMovesWithItsOrdinalOnlyBesideTheVanillaBody() throws Exception {
		ClassNode node = apoli();
		assertEquals(1, MixinBlockQueryAdapters.adapt(node, CarpetMixinAdapterTest::target, NATIVE));
		AnnotationNode point = MixinFit.atNodes(MixinFit.injectorOf(method(node, "modifySlipperiness"))).getFirst();
		assertEquals(STATE_FRICTION, MixinFit.value(point, "target"));
		assertEquals(0, MixinFit.value(point, "ordinal"));
		assertTrue(CurrentBodyOrdinals.counted(method(node, "modifySlipperiness")), "the ordinal now counts the merged body");
		CarpetMixinAdapterTest.verify(node);

		ClassNode alone = apoli();
		byte[] before = CarpetMixinAdapterTest.bytes(alone);
		assertEquals(0, MixinBlockQueryAdapters.adapt(alone, CarpetMixinAdapterTest::target, (family, name) -> null));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(alone));
	}

	private static ClassNode apoli() throws Exception {
		Path origins = Path.of("build/compat-inputs/popular/mods/Origins-Legacy-1.12.18+26.2.jar");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(origins), "real fixture required: " + origins);
		try (ZipFile zip = new ZipFile(origins.toFile())) {
			ZipEntry nested = zip.getEntry("META-INF/jars/Apoli-Legacy-2.12.12+26.2.jar");
			assertNotNull(nested, "Apoli is no longer nested in " + origins);
			try (ZipInputStream apoli = new ZipInputStream(new ByteArrayInputStream(zip.getInputStream(nested).readAllBytes()))) {
				for (ZipEntry entry; (entry = apoli.getNextEntry()) != null;) {
					if (entry.getName().equals("io/github/apace100/apoli/mixin/LivingEntityMixin.class")) return MixinFit.parse(apoli.readAllBytes());
				}
			}
		}
		throw new AssertionError("Apoli's LivingEntityMixin is missing");
	}

	private static MethodNode method(ClassNode node, String name) {
		List<MethodNode> found = node.methods.stream().filter(m -> m.name.equals(name) && MixinFit.injectorOf(m) != null).toList();
		assertEquals(1, found.size(), name);
		return found.getFirst();
	}
}
