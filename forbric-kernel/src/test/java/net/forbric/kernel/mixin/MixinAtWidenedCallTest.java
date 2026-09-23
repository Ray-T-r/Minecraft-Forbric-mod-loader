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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Holds the injection-point widening to the cases where moving a point cannot break the handler.
 *
 * <p>The merged base declares vanilla's {@code CustomPacketPayload.codec(FallbackProvider, List)} beside
 * NeoForge's four-argument one and calls only the long one. Polymer's {@code @ModifyExpressionValue} names the
 * short signature, matched nothing, and its payload codec patch never landed — so {@code polymer:hello} went out
 * with the unknown-id fallback codec and the client was disconnected at world join.
 *
 * <p>The restriction is the part that was learned the hard way. Written without it, this moved
 * fabric-networking's own {@code @WrapOperation} on the same call, whose handler mirrors the call's arguments —
 * Mixin then rejected the handler outright and the mixin stopped applying at all. A handler that describes the
 * call must never be pointed at a different call.
 */
class MixinAtWidenedCallTest {
	private static final String OWNER = "net/minecraft/network/protocol/common/custom/CustomPacketPayload";
	private static final String SHORT = "L" + OWNER + ";codec(Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;";
	private static final String LONG_DESC = "(Ljava/util/List;Lnet/minecraft/network/protocol/PacketFlow;)"
			+ "Lnet/minecraft/network/codec/StreamCodec;";
	private static final String MOVED = "L" + OWNER + ";codec" + LONG_DESC;

	@Test
	void anInjectionPointIsMovedToTheCallTheCarrierLengthened() {
		ClassNode mixin = mixin("Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");

		assertEquals(1, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
		assertEquals(MOVED, atTarget(mixin));
	}

	@Test void aSingleModifiedArgumentAtAnExplicitIndexSurvivesAppendedParameters() {
		ClassNode mixin = mixin("Lorg/spongepowered/asm/mixin/injection/ModifyArg;");
		MethodNode handler = mixin.methods.getFirst();
		handler.desc = "(Ljava/util/List;)Ljava/util/List;";
		handler.visibleAnnotations.getFirst().values.addAll(List.of("index", 0));
		assertEquals(1, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
		assertEquals(MOVED, atTarget(mixin));
	}

	@Test void aModifyArgThatCapturesAllParametersOrInfersItsIndexRemainsUntouched() {
		for (String desc : List.of("(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;", "(Ljava/util/List;)Z")) {
			ClassNode mixin = mixin("Lorg/spongepowered/asm/mixin/injection/ModifyArg;");
			mixin.methods.getFirst().desc = desc;
			mixin.methods.getFirst().visibleAnnotations.getFirst().values.addAll(List.of("index", 0));
			assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
		}
		ClassNode mixin = mixin("Lorg/spongepowered/asm/mixin/injection/ModifyArg;");
		mixin.methods.getFirst().desc = "(Ljava/util/List;)Ljava/util/List;";
		assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
	}

	@Test void fixedIndexMustNameAnOriginalArgumentAndGroupsStillDoNotMove() {
		for (int index : List.of(-1, 1)) {
			ClassNode mixin = mixin("Lorg/spongepowered/asm/mixin/injection/ModifyArg;");
			mixin.methods.getFirst().desc = "(Ljava/util/List;)Ljava/util/List;";
			mixin.methods.getFirst().visibleAnnotations.getFirst().values.addAll(List.of("index", index));
			assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
		}
		ClassNode mixin = mixin("Lorg/spongepowered/asm/mixin/injection/ModifyArg;");
		mixin.methods.getFirst().desc = "(Ljava/util/List;)Ljava/util/List;";
		mixin.methods.getFirst().visibleAnnotations.getFirst().values.addAll(List.of("index", 0));
		mixin.methods.getFirst().invisibleAnnotations = List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
		assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
	}

	@Test void actualFabricRegistryListReplacementTargetsTheCurrentFiveArgumentLoader() throws Exception {
		java.nio.file.Path api = java.nio.file.Path.of("run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar");
		java.nio.file.Path base = java.nio.file.Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"),
				"run/merged-base/patched-mc-merged-26.2.jar");
		org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(api) && java.nio.file.Files.isRegularFile(base), "actual Fabric API and game inputs required");
		byte[] mixinBytes = null, targetBytes;
		try (java.util.zip.ZipFile outer = new java.util.zip.ZipFile(api.toFile())) {
			var module = outer.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-registry-sync-v0-")).findFirst().orElseThrow();
			try (var inner = new java.util.zip.ZipInputStream(outer.getInputStream(module))) {
				for (java.util.zip.ZipEntry e; (e = inner.getNextEntry()) != null;) {
					if (e.getName().equals("net/fabricmc/fabric/mixin/registry/sync/WorldLoaderMixin.class")) { mixinBytes = inner.readAllBytes(); break; }
				}
			}
		}
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(base.toFile())) {
			targetBytes = zip.getInputStream(zip.getEntry("net/minecraft/server/WorldLoader.class")).readAllBytes();
		}
		org.junit.jupiter.api.Assertions.assertNotNull(mixinBytes);
		targetBytes = new net.forbric.kernel.transform.DuplicateLambdaPruneInjector().transform("net.minecraft.server.WorldLoader", targetBytes, null);
		ClassNode target = MixinFit.parse(targetBytes), mixin = MixinFit.parse(mixinBytes);
		assertEquals(1, MixinAtWidenedCall.widen(mixin, name -> target));
		MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("modifyLoadedEntries")).findFirst().orElseThrow();
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst();
		String member = (String) MixinFit.value(at, "target");
		org.junit.jupiter.api.Assertions.assertTrue(member.contains("Ljava/util/concurrent/Executor;Ljava/util/List;)Ljava/util/concurrent/CompletableFuture;"), member);
	}

	/**
	 * The restriction. {@code @WrapOperation}'s handler takes the call's own arguments, so a longer call means a
	 * handler Mixin rejects — the mixin stops applying entirely, which is worse than the point not matching.
	 */
	@Test
	void anInjectorWhoseHandlerMirrorsTheCallIsLeftAlone() {
		ClassNode mixin = mixin("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");

		assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
		assertEquals(SHORT, atTarget(mixin));
	}

	/** The named call really being there is the ordinary case, and it must never be rewritten. */
	@Test
	void aPointThatAlreadyResolvesIsNotMoved() {
		ClassNode mixin = mixin("Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");

		assertEquals(0, MixinAtWidenedCall.widen(mixin,
				name -> targetClass("(Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;")));
		assertEquals(SHORT, atTarget(mixin));
	}

	@Test
	void aDifferentReturnTypeIsADifferentMethod() {
		assertNull(MixinAtWidenedCall.widenedIn(
				body("(Ljava/util/List;Lnet/minecraft/network/protocol/PacketFlow;)Ljava/lang/Object;"), SHORT));
	}

	@Test
	void theNamedParametersMustBeAPrefix() {
		assertNull(MixinAtWidenedCall.widenedIn(
				body("(Ljava/lang/String;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;"), SHORT));
	}

	@Test
	void theSwitchLeavesEveryPointAsCompiled() {
		String previous = System.getProperty(MixinAtWidenedCall.PROPERTY);
		System.setProperty(MixinAtWidenedCall.PROPERTY, "off");
		try {
			ClassNode mixin = mixin("Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");
			assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
			assertEquals(SHORT, atTarget(mixin));
		} finally {
			if (previous == null) System.clearProperty(MixinAtWidenedCall.PROPERTY);
			else System.setProperty(MixinAtWidenedCall.PROPERTY, previous);
		}
	}

	/**
	 * A callback group is the mod's own statement that some of its alternatives are MEANT to miss — they are the
	 * shapes other game versions have. Iris paid for this one: moving one member of a {@code max=1} group made two
	 * match, the group's check failed, and its whole LevelRenderer mixin — every shader hook in it — went with it.
	 */
	@Test
	void aHandlerInACallbackGroupIsLeftAlone() {
		ClassNode mixin = mixin("Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");
		mixin.methods.get(0).visibleAnnotations.add(
				new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));

		assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
		assertEquals(SHORT, atTarget(mixin));
	}

	/** A mixin with one injector of {@code injectorDesc}, selecting {@code <clinit>}, pointed at the short call. */
	private static ClassNode mixin(String injectorDesc) {
		ClassNode mixin = new ClassNode();
		mixin.name = "com/example/SomeMixin";
		mixin.version = Opcodes.V21;

		AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		target.values = new ArrayList<>(List.of("value",
				new ArrayList<>(List.of(Type.getObjectType("net/example/Target")))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(target));

		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", SHORT));
		AnnotationNode injector = new AnnotationNode(injectorDesc);
		injector.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("<clinit>")), "at", at));

		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "handler", "()V", null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(injector));
		mixin.methods = new ArrayList<>(List.of(handler));
		return mixin;
	}

	/** A target class whose {@code <clinit>} makes one call to {@code OWNER.codec} with {@code descriptor}. */
	private static ClassNode targetClass(String descriptor) {
		ClassNode target = new ClassNode();
		target.name = "net/example/Target";
		target.methods = new ArrayList<>(List.of(body(descriptor)));
		return target;
	}

	private static MethodNode body(String descriptor) {
		MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "codec", descriptor, true));
		clinit.instructions.add(new InsnNode(Opcodes.RETURN));
		return clinit;
	}

	private static String atTarget(ClassNode mixin) {
		AnnotationNode injector = mixin.methods.get(0).visibleAnnotations.get(0);
		for (int i = 0; i + 1 < injector.values.size(); i += 2) {
			if ("at".equals(injector.values.get(i)) && injector.values.get(i + 1) instanceof AnnotationNode at) {
				for (int j = 0; j + 1 < at.values.size(); j += 2) {
					if ("target".equals(at.values.get(j))) return (String) at.values.get(j + 1);
				}
			}
		}
		return null;
	}
}
