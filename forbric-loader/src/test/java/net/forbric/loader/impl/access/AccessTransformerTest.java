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

package net.forbric.loader.impl.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.fabricmc.api.EnvType;
import net.forbric.loader.impl.transformer.TransformContext;

class AccessTransformerTest {
	private static final String OWNER = "com/example/Target";

	// A final class with a private final field and a private final method.
	private static byte[] sampleClass() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, OWNER, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "secret", "I", null, null).visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "hidden", "()V", null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static final class Flags extends ClassVisitor {
		int classAccess;
		int fieldAccess;
		int methodAccess;

		Flags() {
			super(Opcodes.ASM9);
		}

		@Override
		public void visit(int version, int access, String name, String sig, String sup, String[] itf) {
			classAccess = access;
		}

		@Override
		public FieldVisitor visitField(int access, String name, String desc, String sig, Object val) {
			if (name.equals("secret")) fieldAccess = access;
			return null;
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
			if (name.equals("hidden")) methodAccess = access;
			return null;
		}
	}

	private static Flags flagsOf(byte[] bytes) {
		Flags f = new Flags();
		new ClassReader(bytes).accept(f, 0);
		return f;
	}

	@Test
	void widensAndStripsFinalMonotonically() {
		List<AtDirective> directives = List.of(
				new AtDirective(OWNER, null, null, false, AtAccess.PUBLIC, AtDirective.FinalOp.LEAVE),
				new AtDirective(OWNER, "secret", null, false, AtAccess.PUBLIC, AtDirective.FinalOp.STRIP),
				new AtDirective(OWNER, "hidden", "()V", true, AtAccess.PUBLIC, AtDirective.FinalOp.STRIP));

		AccessTransformer at = new AccessTransformer(directives);
		assertTrue(at.handles(OWNER));

		byte[] out = at.transform(OWNER.replace('/', '.'), sampleClass(),
				new TransformContext(EnvType.CLIENT, false, "intermediary"));
		Flags f = flagsOf(out);

		assertTrue((f.fieldAccess & Opcodes.ACC_PUBLIC) != 0, "field made public");
		assertEquals(0, f.fieldAccess & Opcodes.ACC_PRIVATE, "private bit cleared");
		assertEquals(0, f.fieldAccess & Opcodes.ACC_FINAL, "final stripped from field");
		assertTrue((f.methodAccess & Opcodes.ACC_PUBLIC) != 0, "method made public");
		assertEquals(0, f.methodAccess & Opcodes.ACC_FINAL, "final stripped from method");
	}

	@Test
	void doesNotNarrowAlreadyPublicMembers() {
		// A 'protected' directive must not narrow an already-public class.
		List<AtDirective> directives = List.of(
				new AtDirective(OWNER, null, null, false, AtAccess.PROTECTED, AtDirective.FinalOp.LEAVE));

		byte[] out = new AccessTransformer(directives).transform(OWNER.replace('/', '.'), sampleClass(),
				new TransformContext(EnvType.CLIENT, false, "intermediary"));

		assertTrue((flagsOf(out).classAccess & Opcodes.ACC_PUBLIC) != 0, "stays public");
	}

	@Test
	void untouchedClassReturnsInputUnchanged() {
		byte[] in = sampleClass();
		byte[] out = new AccessTransformer(List.of()).transform("com.example.Other", in,
				new TransformContext(EnvType.CLIENT, false, "intermediary"));
		assertEquals(in, out);
	}

	@Test
	void parsesCfgGrammar() throws Exception {
		String cfg = String.join("\n",
				"# a comment",
				"public net.minecraft.world.level.block.Block       # widen class",
				"public-f net.minecraft.world.level.block.Block field_name",
				"protected net.minecraft.world.level.block.Block method_name()Lnet/minecraft/world/level/block/state/BlockState;",
				"public net.minecraft.world.level.block.Block *",
				"public net.minecraft.world.level.block.Block *()",
				"");

		List<AtDirective> ds = AccessTransformerParser.parse(new StringReader(cfg));
		assertEquals(5, ds.size());

		assertTrue(ds.get(0).isClass());
		assertEquals("net/minecraft/world/level/block/Block", ds.get(0).className);

		assertEquals(AtDirective.FinalOp.STRIP, ds.get(1).finalOp);
		assertEquals("field_name", ds.get(1).memberName);

		assertTrue(ds.get(2).method);
		assertEquals("method_name", ds.get(2).memberName);
		assertEquals("()Lnet/minecraft/world/level/block/state/BlockState;", ds.get(2).memberDesc);

		assertTrue(ds.get(3).isAllFields());
		assertTrue(ds.get(4).isAllMethods());
	}
}
