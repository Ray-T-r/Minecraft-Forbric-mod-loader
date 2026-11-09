/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * The one clause of {@link FabricRegistryLoaderMixinAdapter#adapt} no other test reaches: a companion injector written for
 * a LIVE method of the class the mod was compiled against, which the current class binds instead to a widened twin that is
 * itself a dead loader the moved wrap leaves behind ({@code deadCallees.contains(bound)}).
 *
 * <p>NeoForge's own {@code RegistryDataLoader} has no such shape (lane T-A, M3b: one dead loader, no twin of a twin), so
 * the case is constructed: the mod was compiled against {@code w(String)} and {@code w(String, boolean)}; the current class
 * declares {@code w(String, boolean)} (dead: nothing calls it) and {@code w(String, boolean, boolean)} (live, the carrier's
 * widening of it). The mod's wrap of {@code w(String, boolean)} moves to the live widening. Its bare-name {@code "w"}
 * companion was written for {@code w(String)} — the first {@code w} where it was compiled — and binds the first {@code w}
 * of the current class, {@code w(String, boolean)}: the same name widened by exactly the parameter the carrier appended,
 * so the widened-twin rule would accept it, and the method it binds runs never. Nothing here can map that companion for
 * certain, so the whole mixin is left as written — which is what the clause does, and what this pins.
 *
 * <p>Red check: without the clause the adapter moves the wrap and leaves the companion bound to the dead loader (adapt
 * answers 1 and edits the mixin).
 */
class RegistryLoaderDeadBindingTest {
	private static final String LOADER = "net/minecraft/resources/RegistryDataLoader";
	private static final String MIXIN = "org/example/sync/mixin/DeadTwinMixin";
	private static final String OP = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	private static final String ONE = "(Ljava/lang/String;)V", DEAD = "(Ljava/lang/String;Z)V", LIVE = "(Ljava/lang/String;ZZ)V";

	@Test void aCompanionTheCurrentClassBindsToADeadLoaderLeavesTheWholeMixinAlone() {
		ClassNode current = loader(List.of(DEAD, LIVE), LIVE), compiledAgainst = loader(List.of(ONE, DEAD), DEAD);
		ClassNode mixin = mixin();
		MethodNode companion = StagedFabricMixinFixture.method(mixin, "seeW");
		// Premises, each read through the adapter's own selector readers.
		MethodNode written = MixinCallbackShape.written(companion, compiledAgainst), bound = MixinTargetSelectors.one(companion, current);
		assertEquals("w" + ONE, written.name + written.desc, "premise: where it was compiled the companion binds w(String), a live method");
		assertEquals("w" + DEAD, bound.name + bound.desc, "premise: here it binds w(String, boolean), the dead loader the wrap names");
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> current, (ecosystem, name) -> compiledAgainst));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin), "nothing moved: neither the wrap nor its companion");
	}

	/** The positive beside it: the same mixin without the companion moves its wrap onto the live widening. */
	@Test void withoutThatCompanionTheWrapMoves() {
		ClassNode current = loader(List.of(DEAD, LIVE), LIVE), compiledAgainst = loader(List.of(ONE, DEAD), DEAD);
		ClassNode mixin = mixin();
		mixin.methods.removeIf(m -> m.name.equals("seeW"));
		assertEquals(1, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> current, (ecosystem, name) -> compiledAgainst));
		AnnotationNode injector = MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin, "wrapW"));
		assertEquals("L" + LOADER + ";w" + LIVE, MixinFit.value(MixinFit.atNodes(injector).getFirst(), "target"));
	}

	/** A class holding {@code w} at each descriptor (in order) and {@code host()} calling {@code w} at {@code called}. */
	private static ClassNode loader(List<String> ws, String called) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = LOADER;
		node.superName = "java/lang/Object";
		for (String desc : ws) {
			MethodNode w = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "w", desc, null, null);
			w.instructions.add(new InsnNode(Opcodes.RETURN));
			w.maxLocals = Type.getArgumentsAndReturnSizes(desc) >> 2;
			node.methods.add(w);
		}
		MethodNode host = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "host", "()V", null, null);
		host.instructions.add(new LdcInsnNode("id"));
		for (int i = 1; i < Type.getArgumentTypes(called).length; i++) host.instructions.add(new InsnNode(Opcodes.ICONST_1));
		host.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOADER, "w", called, false));
		host.instructions.add(new InsnNode(Opcodes.RETURN));
		host.maxStack = Type.getArgumentTypes(called).length;
		node.methods.add(host);
		return node;
	}

	/** wrapW: a WrapOperation of host's call to w(String, boolean); seeW: a HEAD inject on the bare name "w". */
	private static ClassNode mixin() {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC;
		mixin.name = MIXIN;
		mixin.superName = "java/lang/Object";
		AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		target.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(LOADER)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(target));

		MethodNode wrap = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "wrapW", "(Ljava/lang/String;ZL" + OP + ";)V", null, null);
		wrap.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		wrap.instructions.add(new InsnNode(Opcodes.ICONST_2));
		wrap.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
		wrap.instructions.add(new InsnNode(Opcodes.DUP));
		wrap.instructions.add(new InsnNode(Opcodes.ICONST_0));
		wrap.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		wrap.instructions.add(new InsnNode(Opcodes.AASTORE));
		wrap.instructions.add(new InsnNode(Opcodes.DUP));
		wrap.instructions.add(new InsnNode(Opcodes.ICONST_1));
		wrap.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
		wrap.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
		wrap.instructions.add(new InsnNode(Opcodes.AASTORE));
		wrap.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, OP, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true));
		wrap.instructions.add(new InsnNode(Opcodes.POP));
		wrap.instructions.add(new InsnNode(Opcodes.RETURN));
		wrap.maxStack = 6;
		wrap.maxLocals = 3;
		wrap.visibleAnnotations = new ArrayList<>(List.of(injector("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
				"host()V", at("INVOKE", "L" + LOADER + ";w" + DEAD))));
		mixin.methods.add(wrap);

		MethodNode see = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "seeW",
				"(Ljava/lang/String;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		see.instructions.add(new InsnNode(Opcodes.RETURN));
		see.maxLocals = 2;
		see.visibleAnnotations = new ArrayList<>(List.of(injector("Lorg/spongepowered/asm/mixin/injection/Inject;", "w", at("HEAD", null))));
		mixin.methods.add(see);
		return mixin;
	}

	private static AnnotationNode at(String value, String point) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", value));
		if (point != null) at.values.addAll(List.of("target", point));
		return at;
	}

	private static AnnotationNode injector(String desc, String method, AnnotationNode at) {
		AnnotationNode injector = new AnnotationNode(desc);
		injector.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(method)), "at", new ArrayList<>(List.of(at))));
		return injector;
	}
}
