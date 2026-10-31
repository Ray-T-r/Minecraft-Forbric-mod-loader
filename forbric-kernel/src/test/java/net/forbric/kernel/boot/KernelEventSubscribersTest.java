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
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
class KernelEventSubscribersTest {
	@Test
	void aFailedModsSubscribersAreSkippedAndTheLoopAsksThePredicate() throws Exception {
		java.util.List<net.forbric.api.ModCatalog.Entry> previous = net.forbric.api.ModCatalog.everything();
		try {
			net.forbric.api.ModCatalog.publish(java.util.List.of(
					new net.forbric.api.ModCatalog.Entry(Ecosystem.FORGE, "broken", "B", "1", "", java.util.List.of(), "b.jar", "", ""),
					new net.forbric.api.ModCatalog.Entry(Ecosystem.FORGE, "bruised", "C", "1", "", java.util.List.of(), "c.jar", "", "")));
			net.forbric.api.ModCatalog.mark("broken", net.forbric.api.ModCatalog.Status.FAILED, "its @Mod constructor threw");
			net.forbric.api.ModCatalog.mark("bruised", net.forbric.api.ModCatalog.Status.DEGRADED, "it threw during common setup");
			assertTrue(KernelEventSubscribers.didNotFinishLoading("broken"));
			assertFalse(KernelEventSubscribers.didNotFinishLoading("bruised"), "DEGRADED still gets its listeners");
			// wthit's shape: a second id in the SAME jar as the failed one is skipped too.
			net.forbric.api.ModCatalog.publish(java.util.List.of(
					new net.forbric.api.ModCatalog.Entry(Ecosystem.FORGE, "waila", "waila", "1", "", java.util.List.of(), "wthit.jar", "", ""),
					new net.forbric.api.ModCatalog.Entry(Ecosystem.FORGE, "wthit", "wthit", "1", "", java.util.List.of(), "wthit.jar", "", ""),
					new net.forbric.api.ModCatalog.Entry(Ecosystem.FORGE, "other", "other", "1", "", java.util.List.of(), "other.jar", "", "")));
			net.forbric.api.ModCatalog.mark("waila", net.forbric.api.ModCatalog.Status.FAILED, "its @Mod constructor threw");
			assertTrue(KernelEventSubscribers.didNotFinishLoading("wthit"), "shares the jar with a failed mod");
			assertFalse(KernelEventSubscribers.didNotFinishLoading("other"));
			assertFalse(KernelEventSubscribers.didNotFinishLoading("nobody"));
			assertFalse(KernelEventSubscribers.didNotFinishLoading(null));
		} finally {
			net.forbric.api.ModCatalog.publish(previous);
		}
		// The loop consults it: a bytecode pin, since registerAll needs a live game loader to drive.
		java.nio.file.Path compiled = java.nio.file.Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelEventSubscribers.class");
		assertTrue(java.nio.file.Files.isRegularFile(compiled),
				"KernelEventSubscribers not found in the compiled src/main classes, which exist before any test runs");
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(java.nio.file.Files.readAllBytes(compiled)).accept(node, 0);
		boolean asked = false;
		for (org.objectweb.asm.tree.MethodNode m : node.methods) {
			if (!m.name.equals("registerAll")) continue;
			for (org.objectweb.asm.tree.AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && "didNotFinishLoading".equals(call.name)) asked = true;
			}
		}
		assertTrue(asked, "registerAll skips a FAILED mod's subscribers");
	}

	@Test
	void aRegistrationFailureMarksTheOwningMod() {
		java.util.List<net.forbric.api.ModCatalog.Entry> previous = net.forbric.api.ModCatalog.everything();
		try {
			net.forbric.api.ModCatalog.publish(java.util.List.of(new net.forbric.api.ModCatalog.Entry(
					Ecosystem.NEOFORGE, "xmod", "X", "1", "", java.util.List.of(), "x.jar", "", "")));
			KernelEventSubscribers.registrationFailed("xmod", "a.b.C", new RuntimeException("boom"));
			assertEquals(1, net.forbric.api.ModCatalog.failures().size());
			net.forbric.api.ModCatalog.Entry xmod = net.forbric.api.ModCatalog.failures().get(0);
			assertEquals(net.forbric.api.ModCatalog.Status.DEGRADED, xmod.status());
			assertTrue(xmod.statusDetail().contains("@EventBusSubscriber C "), xmod.statusDetail());

			KernelEventSubscribers.registrationFailed(null, "a.b.D", new RuntimeException("boom"));
			assertFalse(xmod.statusDetail().contains("D"), "a subscriber no mod owns marks nobody");
			assertEquals(1, net.forbric.api.ModCatalog.failures().size());
		} finally {
			net.forbric.api.ModCatalog.publish(previous);
		}
	}

	private static final String EBS_FORGE = "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;";
	private static final String EBS_NEO = "Lnet/neoforged/fml/common/EventBusSubscriber;";
	private static final String DIST_FORGE = "Lnet/minecraftforge/api/distmarker/Dist;";
	private static final String DIST_NEO = "Lnet/neoforged/api/distmarker/Dist;";
	private static final String BUS_NEO = "Lnet/neoforged/fml/common/EventBusSubscriber$Bus;";

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
		if (bus != null) av.visitEnum("bus", BUS_NEO, bus);
		if (dists.length > 0) {
			AnnotationVisitor arr = av.visitArray("value");
			for (String d : dists) arr.visitEnum(null, distDescriptor, d);
			arr.visitEnd();
		}
		av.visitEnd();
	}

	// --- which annotation makes a subscriber: NeoForge's, as on NeoForge -----------------------------------------

	@Test
	void onlyNeoForgesAnnotationMakesASubscriber() {
		var neo = KernelEventSubscribers.scanClassBytes(
				subscriber("com/example/NeoEvents", EBS_NEO, "example", null, DIST_NEO));
		assertEquals(Ecosystem.NEOFORGE, neo.family());
		assertEquals("com.example.NeoEvents", neo.className());
		assertNull(KernelEventSubscribers.scanClassBytes(
				subscriber("com/example/ForgeEvents", EBS_FORGE, "example", null, DIST_FORGE)),
				"MinecraftForge's annotation alone marks nothing: Forbric loads no MinecraftForge mod");
	}

	@Test
	void aClassWithNeitherAnnotationIsNotASubscriber() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Plain", null, "java/lang/Object", null);
		cw.visitEnd();

		assertNull(KernelEventSubscribers.scanClassBytes(cw.toByteArray()));
	}

	@Test
	void aUniversalGlueClassCarryingBothAnnotationsIsNeoForgesWhicheverComesFirst() {
		// A universal jar can annotate one class for both families. NeoForge reads only its own annotation, so the class
		// is NeoForge's subscriber, once, in either order.
		for (boolean forgeFirst : new boolean[] {true, false}) {
			ClassWriter cw = new ClassWriter(0);
			cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/BothEvents", null, "java/lang/Object", null);
			if (forgeFirst) annotate(cw, EBS_FORGE, "forge_side", null, DIST_FORGE);
			annotate(cw, EBS_NEO, "example", null, DIST_NEO);
			if (!forgeFirst) annotate(cw, EBS_FORGE, "forge_side", null, DIST_FORGE);
			cw.visitEnd();

			var s = KernelEventSubscribers.scanClassBytes(cw.toByteArray());
			assertEquals(Ecosystem.NEOFORGE, s.family(), "MinecraftForge's annotation first: " + forgeFirst);
			assertEquals("example", s.modId(), "NeoForge's attributes, not MinecraftForge's: " + forgeFirst);
		}
	}

	// --- the attributes that decide routing --------------------------------------------------------------------

	@Test
	void capturesDistsModIdAndBus() {
		var s = KernelEventSubscribers.scanClassBytes(
				subscriber("com/example/ClientOnly", EBS_NEO, "geckolib", "MOD", DIST_NEO, "CLIENT"));

		assertEquals(Set.of("CLIENT"), s.dists());
		assertEquals("geckolib", s.modId());
		assertEquals("MOD", s.bus());
	}

	@Test
	void anAbsentBusIsBOTH() {
		var s = KernelEventSubscribers.scanClassBytes(
				subscriber("com/example/Events", EBS_NEO, null, null, DIST_NEO));

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

	// --- ownerModId: whose bus an unnamed subscriber reaches ----------------------------------------------------

	/**
	 * FML injects a jar's subscribers through each of its mods' containers, a mod with no {@code @Mod} class
	 * included, and an unnamed one belongs to the container injecting it. With no {@code @Mod} class in the jar to
	 * name it, only the jar's class-less mod can.
	 */
	@Test
	void anUnnamedNeoForgeSubscriberInAJarWithNoModClassBelongsToItsClasslessMod() {
		KernelEventSubscribers.Subscriber unnamed = new KernelEventSubscribers.Subscriber("a.Events",
				Ecosystem.NEOFORGE, Set.of(), null, null);
		assertEquals("data_only", KernelEventSubscribers.ownerModId(unnamed, java.util.List.of(), () -> "data_only"));
		assertNull(KernelEventSubscribers.ownerModId(unnamed, java.util.List.of(), () -> null),
				"none, or several, class-less mods in the jar: no owner, as before");
	}

	@Test
	void theClasslessFallbackNeverOverridesWhatAlreadyAnswered() {
		java.util.function.Supplier<String> classless = () -> "data_only";
		KernelEventSubscribers.Subscriber unnamed = new KernelEventSubscribers.Subscriber("a.Events",
				Ecosystem.NEOFORGE, Set.of(), null, null);
		assertEquals("named", KernelEventSubscribers.ownerModId(new KernelEventSubscribers.Subscriber("a.Events",
				Ecosystem.NEOFORGE, Set.of(), "named", null), java.util.List.of(), classless));
		assertEquals("code", KernelEventSubscribers.ownerModId(unnamed, java.util.List.of(
				new ModAnnotationScanner.ModClassInfo("a.Code", "code", Ecosystem.NEOFORGE)), classless),
				"the jar's @Mod class still owns it");
		assertNull(KernelEventSubscribers.ownerModId(unnamed, java.util.List.of(
				new ModAnnotationScanner.ModClassInfo("a.One", "one", Ecosystem.NEOFORGE),
				new ModAnnotationScanner.ModClassInfo("a.Two", "two", Ecosystem.NEOFORGE)), classless),
				"two @Mod classes and no name is still ambiguous");
	}

	@Test
	void commonAndClientEntryClassesForTheSameModHaveOneSubscriberOwner() {
		var unnamed = new KernelEventSubscribers.Subscriber("a.AllKeys",
				Ecosystem.NEOFORGE, Set.of("CLIENT"), null, null);
		assertEquals("create", KernelEventSubscribers.ownerModId(unnamed, java.util.List.of(
				new ModAnnotationScanner.ModClassInfo("a.Create", "create", Ecosystem.NEOFORGE),
				new ModAnnotationScanner.ModClassInfo("a.CreateClient", "create", Ecosystem.NEOFORGE)),
				() -> { throw new AssertionError("the entry classes already identify the owner"); }));
	}
}
