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

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** The three twins over the real merged classes, and the write shapes that put them there. */
class MergedBaseWidenedFieldTwinsTest {
	private static final Path MERGED_BASE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();

	@AfterEach
	void reset() {
		System.clearProperty(WidenedFieldTwinInjector.PROPERTY);
	}

	@Test
	void everyRowDeclaresBothDescriptorsAndWritesTheTwinBesideTheMergedField() throws Exception {
		for (WidenedFieldTwinInjector.Row row : WidenedFieldTwinInjector.ROWS) {
			ClassNode before = parse(bytesOf(row.owner()));
			assertTrue(has(before, row.name(), row.mergedDesc()) && !has(before, row.name(), row.vanillaDesc()), "premise: " + row);
			byte[] out = new WidenedFieldTwinInjector().transform(row.owner().replace('/', '.'), bytesOf(row.owner()), null);
			ClassNode after = parse(out);
			FieldNode twin = field(after, row.name(), row.vanillaDesc());
			assertNotNull(twin, row + ": twin declared");
			assertTrue((twin.access & Opcodes.ACC_PUBLIC) != 0 && (twin.access & Opcodes.ACC_FINAL) == 0, "public, non-final: class tweakers ran a phase earlier");
			assertTrue(has(after, row.name(), row.mergedDesc()), "the merged field stays");

			int mergedWrites = 0, twinWrites = 0;
			for (MethodNode m : after.methods) {
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (!(insn instanceof FieldInsnNode f) || f.getOpcode() != Opcodes.PUTFIELD || !f.name.equals(row.name())) continue;
					if (f.desc.equals(row.mergedDesc())) {
						mergedWrites++;
						// The twin write follows immediately, fed as the row's shape says.
						AbstractInsnNode a = nextReal(f), b = nextReal(a), c = nextReal(b), d = nextReal(c);
						assertTrue(a instanceof VarInsnNode v && v.var == 0, row + ": twin write begins with aload 0");
						if (row.shape() == WidenedFieldTwinInjector.Shape.NARROW) {
							AbstractInsnNode feed = previousReal(f);
							assertTrue(b instanceof VarInsnNode v2 && feed instanceof VarInsnNode fv && v2.var == fv.var, "the same local feeds both");
							assertTrue(c instanceof MethodInsnNode call && "asMonster".equals(call.name) && WidenedFieldTwinInjector.RUNTIME.equals(call.owner), "through the guarded cast");
							assertTrue(d instanceof FieldInsnNode tf && tf.desc.equals(row.vanillaDesc()), "into the twin");
						} else {
							assertTrue(b instanceof MethodInsnNode call && "builder".equals(call.name) && WidenedFieldTwinInjector.IMMUTABLE_MAP.equals(call.owner), "a fresh ImmutableMap.builder()");
							assertTrue(c instanceof FieldInsnNode tf && tf.desc.equals(row.vanillaDesc()), "into the twin");
						}
					} else if (f.desc.equals(row.vanillaDesc())) {
						twinWrites++;
					}
				}
				new Analyzer<>(new BasicVerifier()).analyze(after.name, m);
			}
			assertTrue(mergedWrites >= 1, row + ": at least one merged write");
			assertEquals(mergedWrites, twinWrites, row + ": one twin write per merged write");
		}
	}

	@Test
	void everyReaderOfTheAttributeMapDrainsTheTwinFirst() throws Exception {
		WidenedFieldTwinInjector.Row row = WidenedFieldTwinInjector.ROWS.get(2);
		ClassNode after = parse(new WidenedFieldTwinInjector().transform(row.owner().replace('/', '.'), bytesOf(row.owner()), null));
		int readers = 0;
		for (MethodNode m : after.methods) {
			if ("<init>".equals(m.name)) continue;
			boolean readsMap = false;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD && f.name.equals(row.name()) && f.desc.equals(row.mergedDesc())) readsMap = true;
			}
			if (!readsMap) continue;
			readers++;
			// aload 0; getfield builder:Map; aload 0; getfield builder:ImmutableMap$Builder; invokestatic drain — first thing.
			AbstractInsnNode a = firstReal(m), b = nextReal(a), c = nextReal(b), d = nextReal(c), e = nextReal(d);
			assertTrue(a instanceof VarInsnNode v && v.var == 0, m.name);
			assertTrue(b instanceof FieldInsnNode f1 && f1.desc.equals(row.mergedDesc()), m.name);
			assertTrue(c instanceof VarInsnNode v2 && v2.var == 0, m.name);
			assertTrue(d instanceof FieldInsnNode f2 && f2.desc.equals(row.vanillaDesc()), m.name);
			assertTrue(e instanceof MethodInsnNode call && "drain".equals(call.name), m.name + " begins with the drain, before its first read");
		}
		assertTrue(readers >= 3, "build, combine and hasAttribute at least: " + readers);
		List<String> names = after.methods.stream().map(m -> m.name).toList();
		assertTrue(names.contains("build") && names.contains("combine") && names.contains("hasAttribute"));
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		for (WidenedFieldTwinInjector.Row row : WidenedFieldTwinInjector.ROWS) {
			byte[] once = new WidenedFieldTwinInjector().transform(row.owner().replace('/', '.'), bytesOf(row.owner()), null);
			assertNotSame(bytesOf(row.owner()), once);
			assertSame(once, new WidenedFieldTwinInjector().transform(row.owner().replace('/', '.'), once, null), row.owner());
		}
	}

	/** A NARROW write fed by anything but a local is not the shape: the row stands down whole. */
	@Test
	void aNarrowWriteNotFedByALocalStandsDown() {
		WidenedFieldTwinInjector.Row row = WidenedFieldTwinInjector.ROWS.get(0);
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, row.owner(), null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, row.name(), row.mergedDesc(), null, null).visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitInsn(Opcodes.ACONST_NULL);    // not an aload
		mv.visitFieldInsn(Opcodes.PUTFIELD, row.owner(), row.name(), row.mergedDesc());
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		byte[] bytes = cw.toByteArray();
		assertSame(bytes, new WidenedFieldTwinInjector().transform(row.owner().replace('/', '.'), bytes, null));
	}

	@Test
	void switchedOffItStandsDownAndDeclaresNoAnchor() throws Exception {
		System.setProperty(WidenedFieldTwinInjector.PROPERTY, "off");
		WidenedFieldTwinInjector.Row row = WidenedFieldTwinInjector.ROWS.get(2);
		byte[] bytes = bytesOf(row.owner());
		assertSame(bytes, new WidenedFieldTwinInjector().transform(row.owner().replace('/', '.'), bytes, null));
		assertTrue(new WidenedFieldTwinInjector().anchors().anchors().isEmpty());
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static boolean has(ClassNode node, String name, String desc) {
		return field(node, name, desc) != null;
	}

	private static FieldNode field(ClassNode node, String name, String desc) {
		for (FieldNode f : node.fields) if (f.name.equals(name) && f.desc.equals(desc)) return f;
		return null;
	}

	private static AbstractInsnNode firstReal(MethodNode m) {
		AbstractInsnNode insn = m.instructions.getFirst();
		while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		return insn;
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode cursor) {
		AbstractInsnNode next = cursor == null ? null : cursor.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode cursor) {
		AbstractInsnNode prev = cursor == null ? null : cursor.getPrevious();
		while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
		return prev;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(String internal) throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
