/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The guest scan against the staged merged base's own classes: a mod's override of {@code travelInFluid(Vec3)} calling
 * super leaves the census's verdict and the twin pass alone (the custom-fluid wrap still moves to the overload travel
 * calls), and an {@code @Invoker} of another class whose name spells a private row's leaves that row uncalled.
 */
@ResourceLock("system-properties")
class MergedBaseGuestScanStagedTest {
	private static final Path MERGED = TestFixtures.stagedBase();
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String VEC3 = "Lnet/minecraft/world/phys/Vec3;";
	private static final String DEAD = "(" + VEC3 + ")V";

	@AfterEach void reset() {
		MergedBaseUncalledMethods.forgetGuests();
		MixinStubRebind.forget();
	}

	private static ClassNode living() throws Exception {
		TestFixtures.require(Fixture.STAGED, java.nio.file.Files.isRegularFile(MERGED), "staged merged base absent: " + MERGED);
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(LIVING + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}

	/** A guest entity (unrelated names) overriding travelInFluid(Vec3) and calling super — dead itself, as the census's SulfurCube is. */
	private static byte[] guestSwimmer() {
		ClassNode guest = new ClassNode();
		guest.version = Opcodes.V21;
		guest.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
		guest.name = "org/tidepool/entity/Swimmer";
		guest.superName = LIVING;
		MethodNode override = new MethodNode(Opcodes.ACC_PROTECTED, "travelInFluid", DEAD, null, null);
		override.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		override.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		override.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, LIVING, "travelInFluid", DEAD, false));
		override.instructions.add(new InsnNode(Opcodes.RETURN));
		override.maxStack = 2;
		override.maxLocals = 2;
		guest.methods.add(override);
		ClassWriter writer = new ClassWriter(0);
		guest.accept(writer);
		return writer.toByteArray();
	}

	/** A Fabric mixin with a HEAD @Inject on travelInFluid(Vec3), the shape MixinTwinRebind moves to the overload. */
	private static ClassNode headMixin() {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.name = "org/brine/mixin/FluidPushMixin";
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(LIVING)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "HEAD"));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("travelInFluid" + DEAD)), "at", new ArrayList<>(List.of(at))));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "brine$push", "(" + VEC3 + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		mixin.methods = new ArrayList<>(List.of(handler));
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	@Test void aGuestOverridesSuperCallOfItselfLeavesTheDeadRowDead() throws Exception {
		ClassNode living = living();
		MethodNode dead = living.methods.stream().filter(m -> m.name.equals("travelInFluid") && m.desc.equals(DEAD)).findFirst().orElseThrow();
		ClassNode mixin = headMixin();
		MethodNode handler = mixin.methods.getFirst();

		String before = MergedBaseUncalledMethods.neverRuns(living, dead, Ecosystem.FABRIC);
		MixinTwinRebind.Plan planBefore = MixinTwinRebind.plan(mixin, handler, living, Ecosystem.FABRIC);
		assertNotNull(before, "the census reads travelInFluid(Vec3) as never running");
		assertNotNull(planBefore, "the twin pass moves the HEAD hook to the overload travel calls");

		MergedBaseUncalledMethods.noteGuest(guestSwimmer());
		assertEquals(before, MergedBaseUncalledMethods.neverRuns(living, dead, Ecosystem.FABRIC),
				"a guest override's super call of itself runs only when the override runs");
		assertNotNull(MixinTwinRebind.plan(mixin, handler, living, Ecosystem.FABRIC), "and the hook still moves to the live overload");
	}

	/** An accessor interface of ANOTHER class whose @Invoker names a method "load": owner-agnostic, it revives every private row of that name. */
	private static byte[] otherClassInvoker() {
		ClassNode accessor = new ClassNode();
		accessor.version = Opcodes.V21;
		accessor.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
		accessor.name = "org/tidepool/mixin/ChunkCacheAccessor";
		accessor.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType("net/minecraft/server/level/ServerChunkCache")))));
		accessor.invisibleAnnotations = new ArrayList<>(List.of(type));
		MethodNode invoker = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "tidepool$load", "()V", null, null);
		AnnotationNode named = new AnnotationNode("Lorg/spongepowered/asm/mixin/gen/Invoker;");
		named.values = new ArrayList<>(List.of("value", "load"));
		invoker.visibleAnnotations = new ArrayList<>(List.of(named));
		accessor.methods.add(invoker);
		ClassWriter writer = new ClassWriter(0);
		accessor.accept(writer);
		return writer.toByteArray();
	}

	@Test void anInvokerOfAnotherClassLeavesAPrivateRowOfTheSameNameUncalled() throws Exception {
		living();
		ClassNode loader = new ClassNode();
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			new ClassReader(zip.getInputStream(zip.getEntry("net/minecraft/resources/RegistryDataLoader.class")).readAllBytes()).accept(loader, 0);
		}
		MethodNode load = loader.methods.stream().filter(m -> m.name.equals("load")
				&& m.desc.equals("(Lnet/minecraft/resources/RegistryDataLoader$LoaderFactory;Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;")).findFirst().orElseThrow();
		String before = MergedBaseUncalledMethods.neverRuns(loader, load, Ecosystem.FABRIC);
		MergedBaseUncalledMethods.noteGuest(otherClassInvoker());
		assertNotNull(before);
		assertEquals(before, MergedBaseUncalledMethods.neverRuns(loader, load, Ecosystem.FABRIC), "an @Invoker(\"load\") of ServerChunkCache");
	}

	@Test void anInvokerNamedForAnotherClassesRemoveBlockLeavesTheTwinRowUncalled() throws Exception {
		living();
		ClassNode mode = new ClassNode();
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			new ClassReader(zip.getInputStream(zip.getEntry("net/minecraft/server/level/ServerPlayerGameMode.class")).readAllBytes()).accept(mode, 0);
		}
		MethodNode remove = mode.methods.stream().filter(m -> m.name.equals("removeBlock") && m.desc.equals("(Lnet/minecraft/core/BlockPos;Z)Z")).findFirst().orElseThrow();
		String before = MergedBaseUncalledMethods.neverRuns(mode, remove, Ecosystem.FORGE);
		ClassNode accessor = new ClassNode();
		accessor.version = Opcodes.V21;
		accessor.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
		accessor.name = "org/tidepool/mixin/PistonAccessor";
		accessor.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType("net/minecraft/world/level/block/piston/PistonBaseBlock")))));
		accessor.invisibleAnnotations = new ArrayList<>(List.of(type));
		MethodNode invoker = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "invokeRemoveBlock", "()V", null, null);
		invoker.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/gen/Invoker;")));
		accessor.methods.add(invoker);
		ClassWriter writer = new ClassWriter(0);
		accessor.accept(writer);
		MergedBaseUncalledMethods.noteGuest(writer.toByteArray());
		assertNotNull(before);
		assertEquals(before, MergedBaseUncalledMethods.neverRuns(mode, remove, Ecosystem.FORGE), "an @Invoker invokeRemoveBlock of PistonBaseBlock");
	}
}
