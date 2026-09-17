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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The reject path of the post-Mixin pass, which every class the game loads goes through.
 *
 * <p>Its "cheap pre-check" parsed the whole class — every instruction of every method — to discover that the
 * class had nothing to fix. A class Mixin never wove cannot have a handler method, and a method name lives in
 * the constant pool as plain ASCII, so a byte scan settles it without parsing anything.
 */
class PostMixinFixupsPreFilterTest {

	private static byte[] plainClass() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Plain", null, "java/lang/Object", null);
		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(1, 1);
		ctor.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] wovenClass() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Woven", null, "java/lang/Object", null);
		MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler$abc000$onInit", "()V", null, null);
		handler.visitCode();
		handler.visitInsn(Opcodes.RETURN);
		handler.visitMaxs(0, 1);
		handler.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void aClassWithNoHandlerIsRejectedWithoutParsing() {
		assertFalse(PostMixinFixups.mentionsAMixinHandler(plainClass()),
				"a class Mixin never wove cannot have a handler method, and the byte scan has to say so");
	}

	@Test
	void aWovenClassIsLetThrough() {
		// A "no" here would be a silent loss of the repair, which is far worse than the cost it saves.
		assertTrue(PostMixinFixups.mentionsAMixinHandler(wovenClass()));
	}

	@Test
	void nullBytesAnswerNo() {
		assertFalse(PostMixinFixups.mentionsAMixinHandler(null));
		assertFalse(PostMixinFixups.mentionsAMixinHandler(new byte[0]));
		assertFalse(PostMixinFixups.mentionsAMixinHandler(new byte[] {1, 2, 3}));
	}

	@Test
	void theRejectedClassComesBackUntouched() {
		// Identity, not equality: a pass that rebuilt the class would return equal bytes and still have paid for
		// the parse and the write.
		byte[] plain = plainClass();

		assertSame(plain, PostMixinFixups.apply("com.example.Plain", plain));
	}
}
