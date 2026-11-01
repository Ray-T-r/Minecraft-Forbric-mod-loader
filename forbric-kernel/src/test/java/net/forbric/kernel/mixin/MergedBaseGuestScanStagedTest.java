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
 * The guest scan against the staged base's own classes: a mod's override of {@code createCookie(ClientInformation)}
 * calling super leaves the census's verdict and the twin pass alone (a HEAD hook still moves to the overload NeoForge's
 * listeners call), and an {@code @Invoker} of another class whose name spells a private row's leaves that row uncalled.
 * (On the merged base these were LivingEntity's travelInFluid(Vec3), RegistryDataLoader's private load and
 * ServerPlayerGameMode's removeBlock; none of those rows is on NeoForge's own base.)
 */
@ResourceLock("system-properties")
class MergedBaseGuestScanStagedTest {
	private static final Path MERGED = TestFixtures.stagedBase();
	private static final String LISTENER = "net/minecraft/server/network/ServerCommonPacketListenerImpl";
	private static final String COOKIE = "(Lnet/minecraft/server/level/ClientInformation;)Lnet/minecraft/server/network/CommonListenerCookie;";
	private static final String RENDERER = "net/minecraft/client/renderer/block/ModelBlockRenderer";
	private static final String FACE = "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;"
			+ "Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;)Z";

	@AfterEach void reset() {
		MergedBaseUncalledMethods.forgetGuests();
		MixinStubRebind.forget();
	}

	private static ClassNode staged(String owner) throws Exception {
		TestFixtures.require(Fixture.STAGED, java.nio.file.Files.isRegularFile(MERGED), "staged base absent: " + MERGED);
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}

	private static MethodNode method(ClassNode owner, String name, String desc) {
		return owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
	}

	/** A guest listener (unrelated names) overriding createCookie(ClientInformation) and calling super — dead itself. */
	private static byte[] guestListener() {
		ClassNode guest = new ClassNode();
		guest.version = Opcodes.V21;
		guest.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
		guest.name = "org/tidepool/net/Listener";
		guest.superName = LISTENER;
		MethodNode override = new MethodNode(Opcodes.ACC_PROTECTED, "createCookie", COOKIE, null, null);
		override.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		override.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		override.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, LISTENER, "createCookie", COOKIE, false));
		override.instructions.add(new InsnNode(Opcodes.ARETURN));
		override.maxStack = 2;
		override.maxLocals = 2;
		guest.methods.add(override);
		ClassWriter writer = new ClassWriter(0);
		guest.accept(writer);
		return writer.toByteArray();
	}

	/** A Fabric mixin with a HEAD @Inject on createCookie(ClientInformation), the shape MixinTwinRebind moves to the overload. */
	private static ClassNode headMixin() {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.name = "org/brine/mixin/CookieMixin";
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(LISTENER)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "HEAD"));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("createCookie" + COOKIE)), "at", new ArrayList<>(List.of(at))));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "brine$cookie", "(Lnet/minecraft/server/level/ClientInformation;"
				+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		mixin.methods = new ArrayList<>(List.of(handler));
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	@Test void aGuestOverridesSuperCallOfItselfLeavesTheDeadRowDead() throws Exception {
		ClassNode listener = staged(LISTENER);
		MethodNode dead = method(listener, "createCookie", COOKIE);
		ClassNode mixin = headMixin();
		MethodNode handler = mixin.methods.getFirst();

		String before = MergedBaseUncalledMethods.neverRuns(listener, dead, Ecosystem.FABRIC);
		MixinTwinRebind.Plan planBefore = MixinTwinRebind.plan(mixin, handler, listener, Ecosystem.FABRIC);
		assertNotNull(before, "the census reads createCookie(ClientInformation) as never running");
		assertNotNull(planBefore, "the twin pass moves the HEAD hook to the overload NeoForge's listeners call");

		MergedBaseUncalledMethods.noteGuest(guestListener());
		assertEquals(before, MergedBaseUncalledMethods.neverRuns(listener, dead, Ecosystem.FABRIC),
				"a guest override's super call of itself runs only when the override runs");
		assertNotNull(MixinTwinRebind.plan(mixin, handler, listener, Ecosystem.FABRIC), "and the hook still moves to the live overload");
	}

	/** An accessor interface of {@code target} whose @Invoker {@code name} names {@code value} (or derives it, when null). */
	private static byte[] invoker(String target, String accessorName, String name, String desc, String value) {
		ClassNode accessor = new ClassNode();
		accessor.version = Opcodes.V21;
		accessor.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
		accessor.name = accessorName;
		accessor.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(target)))));
		accessor.invisibleAnnotations = new ArrayList<>(List.of(type));
		MethodNode invoker = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, desc, null, null);
		AnnotationNode named = new AnnotationNode("Lorg/spongepowered/asm/mixin/gen/Invoker;");
		if (value != null) named.values = new ArrayList<>(List.of("value", value));
		invoker.visibleAnnotations = new ArrayList<>(List.of(named));
		accessor.methods.add(invoker);
		ClassWriter writer = new ClassWriter(0);
		accessor.accept(writer);
		return writer.toByteArray();
	}

	/** An @Invoker("hurtEntities") of ServerChunkCache: owner-agnostic, it would revive every private row of that name. */
	@Test void anInvokerOfAnotherClassLeavesAPrivateRowOfTheSameNameUncalled() throws Exception {
		ClassNode explosion = staged("net/minecraft/world/level/ServerExplosion");
		MethodNode hurt = method(explosion, "hurtEntities", "()V");
		String before = MergedBaseUncalledMethods.neverRuns(explosion, hurt, Ecosystem.FABRIC);
		MergedBaseUncalledMethods.noteGuest(invoker("net/minecraft/server/level/ServerChunkCache", "org/tidepool/mixin/ChunkCacheAccessor",
				"tidepool$hurt", "()V", "hurtEntities"));
		assertNotNull(before);
		assertEquals(before, MergedBaseUncalledMethods.neverRuns(explosion, hurt, Ecosystem.FABRIC), "an @Invoker(\"hurtEntities\") of ServerChunkCache");
		// Control: the same invoker on the row's own class calls it.
		MergedBaseUncalledMethods.noteGuest(invoker("net/minecraft/world/level/ServerExplosion", "org/tidepool/mixin/ExplosionAccessor",
				"tidepool$hurt", "()V", "hurtEntities"));
		assertNull(MergedBaseUncalledMethods.neverRuns(explosion, hurt, Ecosystem.FABRIC), "an invoker of its own class calls it");
	}

	/** A twin row's private method, named by an invoker of another class: the row stays uncalled. */
	@Test void anInvokerNamedForAnotherClassesTwinRowMethodLeavesTheTwinRowUncalled() throws Exception {
		ClassNode renderer = staged(RENDERER);
		MethodNode face = method(renderer, "shouldRenderFace", FACE);
		String before = MergedBaseUncalledMethods.neverRuns(renderer, face, Ecosystem.FABRIC);
		MergedBaseUncalledMethods.noteGuest(invoker("net/minecraft/world/level/block/piston/PistonBaseBlock",
				"org/tidepool/mixin/PistonAccessor", "invokeShouldRenderFace", "()V", null));
		assertNotNull(before);
		assertEquals(before, MergedBaseUncalledMethods.neverRuns(renderer, face, Ecosystem.FABRIC), "an @Invoker invokeShouldRenderFace of PistonBaseBlock");
	}
}
