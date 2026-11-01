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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;

/**
 * The delegated-constructor replay moves a guest's narrow-constructor handler into the wide constructor only where the
 * mixin's own platform does not delegate that way itself: a vanilla delegation into a private wide constructor
 * (CommandSourceStack's) and a NeoForge mixin's (the base is NeoForge's class) are left as Mixin wove them. A public wide
 * constructor is moved even when vanilla delegates the same way, since the carrier may route a vanilla caller of the
 * narrow constructor onto it (MappedRegistry's, through NeoForge's RegistryBuilder).
 */
class PostMixinFixupsDelegatedConstructorTest {
	private static final String BOX = "org/harbor/shelf/Box";
	private static final String NARROW = "(I)V";
	private static final String WIDE = "(II)V";
	private static final String MIXIN = "com/tallyman/mark/mixin/BoxMixin";
	private static final String HANDLER = "handler$abc000$tallyman$init";
	private static final String HANDLER_DESC = "(ILorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";

	@Test
	void aWideConstructorTheMixinsPlatformLacksGetsTheHandler() {
		byte[] woven = woven();
		byte[] out = replay(woven, Ecosystem.FABRIC, (e, owner) -> nativeBox(false));
		assertNotSame(woven, out);
		assertEquals(Map.of(NARROW, 0, WIDE, 1), handlerCalls(out), "moved into the constructor it delegates to");
	}

	@Test
	void aDelegationThePlatformHasItselfIsLeftAsMixinWoveIt() {
		byte[] woven = woven(Opcodes.ACC_PRIVATE);
		assertSame(woven, replay(woven, Ecosystem.FABRIC, (e, owner) -> nativeBox(true, Opcodes.ACC_PRIVATE)),
				"natively the handler runs for callers of the narrow constructor only");
	}

	@Test
	void aPublicWideConstructorIsMovedEvenWhenThePlatformDelegatesTheSameWay() {
		byte[] woven = woven();
		assertEquals(Map.of(NARROW, 0, WIDE, 1), handlerCalls(replay(woven, Ecosystem.FABRIC, (e, owner) -> nativeBox(true))),
				"the carrier may have moved a native caller of the narrow constructor onto the public wide one");
	}

	@Test
	void aWideConstructorTheCarrierOpenedIsMoved() {
		byte[] woven = woven();
		assertEquals(Map.of(NARROW, 0, WIDE, 1),
				handlerCalls(replay(woven, Ecosystem.FABRIC, (e, owner) -> nativeBox(true, Opcodes.ACC_PRIVATE))),
				"private natively, public in the carrier's class: callers outside the class can reach it");
	}

	@Test
	void theNativeDelegationSwitchOffMovesAsBefore() {
		byte[] woven = woven(Opcodes.ACC_PRIVATE);
		String old = System.getProperty(PostMixinFixups.NATIVE_DELEGATION_PROPERTY);
		System.setProperty(PostMixinFixups.NATIVE_DELEGATION_PROPERTY, "off");
		try {
			assertEquals(Map.of(NARROW, 0, WIDE, 1),
					handlerCalls(replay(woven, Ecosystem.FABRIC, (e, owner) -> nativeBox(true, Opcodes.ACC_PRIVATE))));
		} finally {
			if (old == null) System.clearProperty(PostMixinFixups.NATIVE_DELEGATION_PROPERTY);
			else System.setProperty(PostMixinFixups.NATIVE_DELEGATION_PROPERTY, old);
		}
	}

	@Test
	void aNeoForgeMixinIsNeverMoved() {
		byte[] woven = woven();
		assertSame(woven, replay(woven, Ecosystem.NEOFORGE, (e, owner) -> null), "its native class is the base itself");
	}

	@Test
	void anUnknownPlatformOrNoNativeReferenceMovesAsBefore() {
		byte[] woven = woven(Opcodes.ACC_PRIVATE);
		assertEquals(Map.of(NARROW, 0, WIDE, 1),
				handlerCalls(replay(woven, null, (e, owner) -> nativeBox(true, Opcodes.ACC_PRIVATE))));
		assertEquals(Map.of(NARROW, 0, WIDE, 1), handlerCalls(replay(woven, Ecosystem.FABRIC, (e, owner) -> null)));
	}

	@Test
	void theMixinIsReadFromTheHandlersMergedAnnotation() {
		Map<String, Ecosystem> asked = new HashMap<>();
		Function<String, Ecosystem> ecosystemOf = mixin -> {
			asked.put(mixin, Ecosystem.FABRIC);
			return Ecosystem.FABRIC;
		};
		PostMixinFixups.replayDelegatedConstructorInjections(BOX, woven(), (e, owner) -> nativeBox(false), ecosystemOf);
		assertEquals(Map.of(MIXIN, Ecosystem.FABRIC), asked);
	}

	private static byte[] replay(byte[] bytes, Ecosystem ecosystem, BiFunction<Ecosystem, String, ClassNode> natives) {
		return PostMixinFixups.replayDelegatedConstructorInjections(BOX, bytes, natives, mixin -> MIXIN.equals(mixin) ? ecosystem : null);
	}

	private static Map<String, Integer> handlerCalls(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		Map<String, Integer> calls = new HashMap<>();
		for (MethodNode method : node.methods) {
			if (!method.name.equals("<init>")) continue;
			int n = 0;
			for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call && call.name.equals(HANDLER)) n++;
			calls.put(method.desc, n);
		}
		return calls;
	}

	/** The class as Mixin wove it: the narrow constructor delegates to the wide one, then calls the guest's handler. */
	private static byte[] woven() {
		return woven(Opcodes.ACC_PUBLIC);
	}

	private static byte[] woven(int wideAccess) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, BOX, null, "java/lang/Object", null);
		wide(cw, wideAccess);
		MethodVisitor narrow = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", NARROW, null, null);
		narrow.visitCode();
		narrow.visitVarInsn(Opcodes.ALOAD, 0);
		narrow.visitVarInsn(Opcodes.ILOAD, 1);
		narrow.visitInsn(Opcodes.ICONST_0);
		narrow.visitMethodInsn(Opcodes.INVOKESPECIAL, BOX, "<init>", WIDE, false);
		narrow.visitVarInsn(Opcodes.ALOAD, 0);
		narrow.visitVarInsn(Opcodes.ILOAD, 1);
		narrow.visitInsn(Opcodes.ACONST_NULL);
		narrow.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BOX, HANDLER, HANDLER_DESC, false);
		narrow.visitInsn(Opcodes.RETURN);
		narrow.visitMaxs(0, 0);
		narrow.visitEnd();
		MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE, HANDLER, HANDLER_DESC, null, null);
		AnnotationVisitor merged = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;", true);
		merged.visit("mixin", MIXIN.replace('/', '.'));
		merged.visit("priority", 1000);
		merged.visitEnd();
		handler.visitCode();
		handler.visitInsn(Opcodes.RETURN);
		handler.visitMaxs(0, 0);
		handler.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** The class as the mixin's platform compiled it: with the same delegation, or with the narrow constructor alone. */
	private static ClassNode nativeBox(boolean delegates) {
		return nativeBox(delegates, Opcodes.ACC_PUBLIC);
	}

	private static ClassNode nativeBox(boolean delegates, int wideAccess) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, BOX, null, "java/lang/Object", null);
		MethodVisitor narrow = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", NARROW, null, null);
		narrow.visitCode();
		narrow.visitVarInsn(Opcodes.ALOAD, 0);
		if (delegates) {
			narrow.visitVarInsn(Opcodes.ILOAD, 1);
			narrow.visitInsn(Opcodes.ICONST_0);
			narrow.visitMethodInsn(Opcodes.INVOKESPECIAL, BOX, "<init>", WIDE, false);
		} else {
			narrow.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		}
		narrow.visitInsn(Opcodes.RETURN);
		narrow.visitMaxs(0, 0);
		narrow.visitEnd();
		if (delegates) wide(cw, wideAccess);
		cw.visitEnd();
		ClassNode node = new ClassNode();
		new ClassReader(cw.toByteArray()).accept(node, 0);
		return node;
	}

	private static void wide(ClassWriter cw, int access) {
		MethodVisitor wide = cw.visitMethod(access, "<init>", WIDE, null, null);
		wide.visitCode();
		wide.visitVarInsn(Opcodes.ALOAD, 0);
		wide.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		wide.visitInsn(Opcodes.RETURN);
		wide.visitMaxs(0, 0);
		wide.visitEnd();
	}
}
