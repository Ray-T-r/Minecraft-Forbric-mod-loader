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

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.discovery.ModAnnotationScanner;
import net.forbric.api.Side;

/**
 * Covers the two things that silently mis-routed a subscriber before: which family declared it, and where its
 * listeners belong. Both were computed and then thrown away — the scan discarded which annotation matched, and
 * nothing ever read {@code bus()} or {@code value()}.
 */
class KernelEventSubscribersTest {
	private static final String EBS_FORGE = "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;";
	private static final String EBS_NEO = "Lnet/neoforged/fml/common/EventBusSubscriber;";
	private static final String DIST_FORGE = "Lnet/minecraftforge/api/distmarker/Dist;";
	private static final String DIST_NEO = "Lnet/neoforged/api/distmarker/Dist;";
	private static final String BUS_FORGE = "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus;";

	/** A subscriber class carrying {@code descriptors}' annotations, each with the given attributes. */
	private static byte[] subscriber(String internalName, String descriptor, String modId, String bus,
			String distDescriptor, String... dists) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		annotate(cw, descriptor, modId, bus, distDescriptor, dists);
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void annotate(ClassWriter cw, String descriptor, String modId, String bus, String distDescriptor,
			String... dists) {
		AnnotationVisitor av = cw.visitAnnotation(descriptor, true);
		if (modId != null) av.visit("modid", modId);
		if (bus != null) av.visitEnum("bus", BUS_FORGE, bus);
		if (dists.length > 0) {
			AnnotationVisitor arr = av.visitArray("value");
			for (String d : dists) arr.visitEnum(null, distDescriptor, d);
			arr.visitEnd();
		}
		av.visitEnd();
	}

	// --- which family declared it — the bit the old scan threw away ---------------------------------------------

	@Test
	void keepsWhichFamilyDeclaredTheSubscriber() {
		var forge = KernelEventSubscribers.scanClassBytes(
				subscriber("com/example/ForgeEvents", EBS_FORGE, "example", null, DIST_FORGE));
		var neo = KernelEventSubscribers.scanClassBytes(
				subscriber("com/example/NeoEvents", EBS_NEO, "example", null, DIST_NEO));

		assertEquals(Ecosystem.FORGE, forge.family());
		assertEquals(Ecosystem.NEOFORGE, neo.family());
		assertEquals("com.example.ForgeEvents", forge.className());
	}

	@Test
	void aClassWithNeitherAnnotationIsNotASubscriber() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Plain", null, "java/lang/Object", null);
		cw.visitEnd();

		assertNull(KernelEventSubscribers.scanClassBytes(cw.toByteArray()));
	}

	@Test
	void aUniversalGlueClassCarryingBothAnnotationsIsClaimedOnce() {
		// A universal jar can annotate one class for both families. It can only be constructed once, so honouring
		// both would double every listener; first-declared wins.
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/BothEvents", null, "java/lang/Object", null);
		annotate(cw, EBS_FORGE, "example", null, DIST_FORGE);
		annotate(cw, EBS_NEO, "example", null, DIST_NEO);
		cw.visitEnd();

		assertEquals(Ecosystem.FORGE,
				KernelEventSubscribers.scanClassBytes(cw.toByteArray()).family());
	}

	// --- the attributes that decide routing --------------------------------------------------------------------

	@Test
	void capturesDistsModIdAndBus() {
		var s = KernelEventSubscribers.scanClassBytes(
				subscriber("com/example/ClientOnly", EBS_FORGE, "geckolib", "MOD", DIST_FORGE, "CLIENT"));

		assertEquals(Set.of("CLIENT"), s.dists());
		assertEquals("geckolib", s.modId());
		assertEquals("MOD", s.bus());
	}

	@Test
	void anAbsentBusIsBOTH() {
		// Forge's own default. It is the value that routes per event type, so defaulting to FORGE instead would
		// strand every mod-bus listener in the class.
		var s = KernelEventSubscribers.scanClassBytes(
				subscriber("com/example/Events", EBS_FORGE, null, null, DIST_FORGE));

		assertEquals("BOTH", s.bus());
		assertNull(s.modId());
		assertTrue(s.dists().isEmpty());
	}

	// --- matchesSide: empty value() means EVERY side, not none -------------------------------------------------

	@Test
	void anUndeclaredSideRunsEverywhere() {
		assertTrue(KernelEventSubscribers.matchesSide(Set.of(), Side.CLIENT));
		assertTrue(KernelEventSubscribers.matchesSide(Set.of(), Side.DEDICATED_SERVER));
	}

	@Test
	void aClientOnlySubscriberIsSkippedOnADedicatedServer() {
		// GeckoLibClient is @Dist.CLIENT and gate-m4 is a dedicated server; it used to be loaded and registered
		// there anyway.
		assertTrue(KernelEventSubscribers.matchesSide(Set.of("CLIENT"), Side.CLIENT));
		assertFalse(KernelEventSubscribers.matchesSide(Set.of("CLIENT"), Side.DEDICATED_SERVER));
		assertFalse(KernelEventSubscribers.matchesSide(Set.of("DEDICATED_SERVER"), Side.CLIENT));
		assertTrue(KernelEventSubscribers.matchesSide(Set.of("CLIENT", "DEDICATED_SERVER"), Side.DEDICATED_SERVER));
	}

	// --- busGroupChoice: the piece that silently mis-routes if wrong -------------------------------------------

	@Test
	void busAttributeSelectsTheGroup() {
		assertEquals(KernelEventSubscribers.BusChoice.DEFAULT,
				KernelEventSubscribers.busGroupChoice("FORGE", true));
		assertEquals(KernelEventSubscribers.BusChoice.MOD,
				KernelEventSubscribers.busGroupChoice("MOD", true));
		// BOTH (and anything unrecognised) means "pass null, let Forge route per event type".
		assertEquals(KernelEventSubscribers.BusChoice.AUTO,
				KernelEventSubscribers.busGroupChoice("BOTH", true));
		assertEquals(KernelEventSubscribers.BusChoice.AUTO,
				KernelEventSubscribers.busGroupChoice("BOTH", false));
	}

	@Test
	void aModBusSubscriberWithNoConstructedModIsSkippedNotDowngraded() {
		// Parking a mod-bus listener on the game bus would never fire and would hide the problem.
		assertEquals(KernelEventSubscribers.BusChoice.SKIP,
				KernelEventSubscribers.busGroupChoice("MOD", false));
	}
}
