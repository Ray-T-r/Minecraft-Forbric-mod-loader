/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * setBlock's notification tail on the staged merged Level, hooked in ways neither Carpet nor C2ME wrote: one of Carpet's
 * two fill hooks without the other, the status check's result modified or wrapped instead of its argument, a redirect of
 * another call of the tail. Each follows its operation into markAndNotifyBlock, by the adapter as Mixin loads the class
 * and by MixinRetarget's plan alike. Look-alikes stay as compiled: a call setBlock still makes, a hook that captures
 * setBlock's own argument, an occurrence vanilla's setBlock does not have, a constant whose meaning is not a flags bit,
 * and a mixin one of whose tail hooks cannot follow. With the class the mod was compiled against at hand, the operation's
 * flags tests must match it.
 */
@ResourceLock("system-properties")
class SetBlockTailCallbacksTest {
	private static final String LEVEL = MixinChunkStatusRetarget.LEVEL;
	private static final String POS = MixinChunkStatusRetarget.POSITION, STATE = MixinChunkStatusRetarget.STATE;
	private static final String STATUS = MixinChunkStatusRetarget.STATUS;
	private static final String SET_BLOCK = MixinChunkStatusRetarget.ORIGINAL;
	private static final String HELPER = MixinChunkStatusRetarget.HELPER;
	private static final String BLOCK = "Lnet/minecraft/world/level/block/Block;";
	private static final String UPDATED = "L" + LEVEL + ";sendBlockUpdated(" + POS + STATE + STATE + "I)V";
	private static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";

	private static ClassNode level() {
		return CarpetMixinAdapterTest.target(LEVEL);
	}

	private static ClassNode nativeLevel() {
		return CreateInjectionAdaptersTest.nativeTarget(LEVEL);
	}

	private static ClassNode carpet() throws Exception {
		return MixinCallbackSelectorSpellingTest.unrelatedNames(CarpetMixinAdapterTest.mixin("Level_fillUpdatesMixin"));
	}

	private static ClassNode c2me() throws Exception {
		return MixinCallbackSelectorSpellingTest.unrelatedNames(CarpetMixinAdapterTest.from(Fixture.THIRD_PARTY,
				Path.of("build/compat-inputs/startup-20260930/c2me-notickvd.jar"), "com/ishland/c2me/notickvd/mixin/MixinWorld"));
	}

	/** A Level mixin of another mod with the given handlers. */
	private static ClassNode mixin(MethodNode... handlers) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
		node.name = "org/example/tail/TailHooks";
		node.superName = "java/lang/Object";
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>(List.of("value", List.of(Type.getObjectType(LEVEL))));
		node.invisibleAnnotations = new ArrayList<>(List.of(mixin));
		node.methods.addAll(List.of(handlers));
		return node;
	}

	/** A handler of {@code kind} on {@code setBlock}, at an INVOKE of {@code target}, whose body returns its first operand or a constant. */
	private static MethodNode handler(String name, String kind, String desc, String target, Object... extra) {
		MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, name, desc, null, null);
		AnnotationNode injector = new AnnotationNode(kind);
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", target));
		injector.values = new ArrayList<>(List.of("method", List.of("L" + LEVEL + ";" + SET_BLOCK), "at", at));
		injector.values.addAll(List.of(extra));
		method.visibleAnnotations = new ArrayList<>(List.of(injector));
		body(method);
		return method;
	}

	private static void body(MethodNode method) {
		Type returns = Type.getReturnType(method.desc);
		method.instructions.clear();
		if (returns.equals(Type.VOID_TYPE)) method.instructions.add(new InsnNode(Opcodes.RETURN));
		else {
			method.instructions.add(new VarInsnNode(returns.getOpcode(Opcodes.ILOAD), 1));
			method.instructions.add(new InsnNode(returns.getOpcode(Opcodes.IRETURN)));
		}
		method.maxLocals = Type.getArgumentsAndReturnSizes(method.desc) >> 2;
		method.maxStack = 2;
	}

	private static List<String> selectors(MethodNode handler) {
		return MixinTargetSelectors.selectors(handler);
	}

	private static int plan(ClassNode mixin) {
		ClassNode level = level();
		byte[] bytes = CarpetMixinAdapterTest.bytes(level);
		return MixinRetarget.apply(mixin, MixinRetarget.plan(mixin, name -> name.equals(LEVEL + ".class") ? bytes : null, (family, name) -> null));
	}

	private static void assertMoved(MethodNode... handlers) {
		for (MethodNode handler : handlers) assertEquals(List.of(HELPER), selectors(handler), handler.name);
	}

	@Test void eitherFillHookAloneFollowsItsOperation() throws Exception {
		for (String kept : List.of("ModifyConstant", "Redirect")) {
			ClassNode carpet = carpet();
			carpet.methods.removeIf(m -> MixinFit.injectorOf(m) != null && !MixinFit.injectorOf(m).desc.endsWith("/" + kept + ";"));
			MethodNode hook = carpet.methods.stream().filter(m -> MixinFit.injectorOf(m) != null).findFirst().orElseThrow();
			assertEquals(1, MixinPlayerWorldCallbackAdapter.adapt(carpet, CarpetMixinAdapterTest::target), kept + " alone");
			assertMoved(hook);
			CarpetMixinAdapterTest.verify(carpet);
		}
	}

	@Test void theStatusCheckModifiedOrWrappedFollowsItsCall() throws Exception {
		String isOrAfter = MixinChunkStatusRetarget.ANCHOR;
		MethodNode modified = handler("hooks$everywhere", "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;", "(Z)Z", isOrAfter);
		MethodNode wrapped = handler("hooks$wrapped", "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
				"(" + STATUS + STATUS + OPERATION + ")Z", isOrAfter);
		for (MethodNode hook : List.of(modified, wrapped)) {
			MethodNode copy = copy(hook);
			assertEquals(1, MixinPlayerWorldCallbackAdapter.adapt(mixin(hook), CarpetMixinAdapterTest::target), hook.name);
			assertMoved(hook);
			assertEquals(1, plan(mixin(copy)), hook.name + " through MixinRetarget's plan");
			assertMoved(copy);
		}
	}

	@Test void aRedirectOfAnotherTailCallFollowsIt() throws Exception {
		MethodNode redirect = handler("hooks$quietClients", "Lorg/spongepowered/asm/mixin/injection/Redirect;",
				"(L" + LEVEL + ";" + POS + STATE + STATE + "I)V", UPDATED);
		ClassNode mixin = mixin(redirect);
		assertEquals(1, MixinPlayerWorldCallbackAdapter.adapt(mixin, CarpetMixinAdapterTest::target));
		assertMoved(redirect);
		CarpetMixinAdapterTest.verify(mixin);
	}

	@Test void lookAlikesStayAsCompiled() throws Exception {
		// A call setBlock still makes itself.
		MethodNode chunk = handler("hooks$chunk", "Lorg/spongepowered/asm/mixin/injection/Redirect;",
				"(Lnet/minecraft/world/level/chunk/LevelChunk;" + POS + STATE + "I)" + STATE,
				"Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState(" + POS + STATE + "I)" + STATE);
		assertUntouched(mixin(chunk), "a call setBlock still makes");
		// A @ModifyArg that also takes setBlock's own arguments: it describes setBlock, not the call.
		ClassNode captures = c2me();
		MethodNode threshold = captures.methods.stream().filter(m -> MixinFit.injectorOf(m) != null).findFirst().orElseThrow();
		threshold.desc = "(" + STATUS + POS + STATE + "II)" + STATUS;
		body(threshold);
		assertUntouched(captures, "setBlock's arguments captured");
		// The 16 at an occurrence vanilla's setBlock does not have.
		ClassNode second = carpet();
		second.methods.removeIf(m -> MixinFit.injectorOf(m) != null && MixinFit.injectorOf(m).desc.endsWith("/Redirect;"));
		AnnotationNode constant = (AnnotationNode) ((List<?>) MixinFit.value(MixinFit.injectorOf(second.methods.stream()
				.filter(m -> MixinFit.injectorOf(m) != null).findFirst().orElseThrow()), "constant")).getFirst();
		constant.values.addAll(List.of("ordinal", 1));
		assertUntouched(second, "a second 16");
		// A constant of the tail whose meaning is not a flags bit (the mask that drops two of them).
		ClassNode mask = carpet();
		mask.methods.removeIf(m -> MixinFit.injectorOf(m) != null && MixinFit.injectorOf(m).desc.endsWith("/Redirect;"));
		AnnotationNode masked = (AnnotationNode) ((List<?>) MixinFit.value(MixinFit.injectorOf(mask.methods.stream()
				.filter(m -> MixinFit.injectorOf(m) != null).findFirst().orElseThrow()), "constant")).getFirst();
		MixinPlayerWorldCallbackAdapter.set(masked, "intValue", -34);
		assertUntouched(mask, "-34");
		// Atomic: a tail hook that cannot follow (it takes setBlock's callback) keeps the mixin's other hook with it.
		ClassNode partial = carpet();
		MethodNode inject = handler("hooks$beforeClients", "Lorg/spongepowered/asm/mixin/injection/Inject;",
				"(" + POS + STATE + "IILorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", UPDATED);
		partial.methods.add(inject);
		assertUntouched(partial, "one tail hook of the mixin cannot follow");
	}

	@Test void theNativeSetBlockMustAgreeWhenItIsAtHand() throws Exception {
		ClassNode level = level(), vanilla = nativeLevel();
		assertNotNull(vanilla, "vanilla Level");
		assertEquals(2, MixinChunkStatusRetarget.movable(carpet(), level, vanilla).size(), "Carpet's hooks, against vanilla's setBlock");
		assertEquals(1, MixinChunkStatusRetarget.movable(c2me(), level, vanilla).size(), "C2ME's threshold, against vanilla's setBlock");
		// A native setBlock whose status check sits under another flags test is another operation.
		MethodNode setBlock = vanilla.methods.stream().filter(m -> (m.name + m.desc).equals(SET_BLOCK)).findFirst().orElseThrow();
		for (AbstractInsnNode insn : setBlock.instructions) if (insn.getOpcode() == Opcodes.ICONST_4) { setBlock.instructions.set(insn, new InsnNode(Opcodes.ICONST_5)); break; }
		assertNull(MixinChunkStatusRetarget.movable(c2me(), level, vanilla), "C2ME's threshold: its flags tests no longer match");
		assertEquals(2, MixinChunkStatusRetarget.movable(carpet(), level, vanilla).size(), "Carpet's hooks: theirs still do");
	}

	private static void assertUntouched(ClassNode mixin, String why) {
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		Function<String, ClassNode> targets = CarpetMixinAdapterTest::target;
		assertEquals(0, MixinPlayerWorldCallbackAdapter.adapt(mixin, targets), why);
		assertEquals(0, plan(mixin), why + " (MixinRetarget's plan)");
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin), why);
	}

	private static MethodNode copy(MethodNode method) {
		MethodNode copy = new MethodNode(method.access, method.name, method.desc, method.signature, null);
		method.accept(copy);
		return copy;
	}
}
