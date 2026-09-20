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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * Pins the one javac switch map the merge lost, the census that says it is the only one, and the mapping read
 * off MinecraftForge's own holder class.
 */
class MergedBaseLostSwitchMapTest {
	private static final Path MERGED_BASE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final Path FORGE_PATCHED = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"forge-patched", "patched-mc-forge-26.2.jar").normalize();
	private static final ForbricMergedBaseCompatTransformer.LostSwitchMap FURNACE =
			ForbricMergedBaseCompatTransformer.LOST_SWITCH_MAPS.get(0);

	/** Across the WHOLE merged base: every {@code $SwitchMap$} read whose holder no longer declares the field. */
	@Test
	void theCensusOfLostSwitchMapsIsExactlyTheTable() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		Map<String, String> lost = new TreeMap<>();    // "holder.field" -> user
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry entry = e.nextElement();
				if (!entry.getName().endsWith(".class")) continue;
				byte[] bytes;
				try (InputStream in = zip.getInputStream(entry)) { bytes = in.readAllBytes(); }
				if (indexOf(bytes, "$SwitchMap$".getBytes()) < 0) continue;
				ClassNode node = parse(bytes);
				for (MethodNode m : node.methods) {
					for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
						if (!(insn instanceof FieldInsnNode f) || f.getOpcode() != Opcodes.GETSTATIC || !f.name.startsWith("$SwitchMap$")) continue;
						ZipEntry holder = zip.getEntry(f.owner + ".class");
						boolean declared = false;
						if (holder != null) {
							try (InputStream in = zip.getInputStream(holder)) {
								for (FieldNode field : parse(in.readAllBytes()).fields) if (field.name.equals(f.name)) declared = true;
							}
						}
						if (!declared) lost.put(f.owner + "." + f.name, node.name);
					}
				}
			}
		}
		Map<String, String> table = new TreeMap<>();
		for (var l : ForbricMergedBaseCompatTransformer.LOST_SWITCH_MAPS) table.put(l.holder() + "." + l.field(), l.user());
		assertEquals(table, lost, "switch-map holders the merge replaced — the table must name exactly these");
	}

	/** The case numbering is javac's and lives only in MinecraftForge's holder class; read it back from there. */
	@Test
	void theMappingMatchesMinecraftForgesOwnHolderClass() throws Exception {
		assumeTrue(Files.isRegularFile(FORGE_PATCHED), "Forge-patched base absent (dev-only pin)");
		ClassNode holder = parse(bytesOf(FORGE_PATCHED, FURNACE.holder()));
		MethodNode clinit = holder.methods.stream().filter(m -> "<clinit>".equals(m.name)).findFirst().orElseThrow();
		Map<Integer, String> mapping = new TreeMap<>();
		// getstatic $SwitchMap; getstatic Enum.CONST; invokevirtual ordinal; iconst_k; iastore
		for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof FieldInsnNode constant) || constant.getOpcode() != Opcodes.GETSTATIC
					|| !FURNACE.enumType().equals(constant.owner)) continue;
			AbstractInsnNode ordinal = nextReal(constant), k = nextReal(ordinal), store = nextReal(k);
			assertTrue(ordinal instanceof MethodInsnNode o && "ordinal".equals(o.name), "holder shape");
			assertTrue(store != null && store.getOpcode() == Opcodes.IASTORE, "holder shape");
			int key = k.getOpcode() - Opcodes.ICONST_0;
			assertTrue(key >= 1 && key <= 5, "javac's small constants");
			mapping.put(key, constant.name);
		}
		assertEquals(new TreeMap<>(FURNACE.cases()), mapping, "the table must carry Forge's own case numbering");
	}

	@Test
	void theFurnaceSwitchIsDecidedByDirectComparison() throws Exception {
		ClassNode before = parse(bytesOf(MERGED_BASE, FURNACE.user()));
		assertEquals(1, reads(before), "premise: one read of the lost switch map");
		ClassNode after = parse(transform(bytesOf(MERGED_BASE, FURNACE.user())));
		assertEquals(0, reads(after), "the holder class is never read again");

		MethodNode get = after.methods.stream()
				.filter(m -> "getCapability".equals(m.name) && m.desc.startsWith("(Lnet/minecraftforge/common/capabilities/Capability;"))
				.findFirst().orElseThrow();
		// aload 2; getstatic Direction.UP; if_acmpeq; aload 2; getstatic Direction.DOWN; if_acmpeq; goto
		List<String> constants = new ArrayList<>();
		int compares = 0, gotos = 0;
		for (AbstractInsnNode insn = get.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && FURNACE.enumType().equals(f.owner)) {
				constants.add(f.name);
				AbstractInsnNode prev = previousReal(f), next = nextReal(f);
				assertTrue(prev instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 2, "the facing is reloaded before each compare");
				assertTrue(next instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IF_ACMPEQ, "identity compare");
				compares++;
				if (nextReal(next) instanceof JumpInsnNode g && g.getOpcode() == Opcodes.GOTO) gotos++;
			}
		}
		assertEquals(List.of("UP", "DOWN"), constants, "javac's case order, from Forge's holder");
		assertEquals(2, compares);
		assertEquals(1, gotos, "the default arm is reached by an unconditional jump after the last compare");
		for (MethodNode m : after.methods) new Analyzer<>(new BasicVerifier()).analyze(after.name, m);
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		byte[] once = transform(bytesOf(MERGED_BASE, FURNACE.user()));
		assertSame(once, new ForbricMergedBaseCompatTransformer().transform(FURNACE.user().replace('/', '.'), once, null));
	}

	/** A key the table does not name means the mapping is incomplete: stand down rather than mis-route a case. */
	@Test
	void aSwitchWithAnUnnamedCaseIsLeftAlone() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, FURNACE.user(), null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "pick", "(L" + FURNACE.enumType() + ";)I", null, null);
		mv.visitCode();
		mv.visitFieldInsn(Opcodes.GETSTATIC, FURNACE.holder(), FURNACE.field(), "[I");
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FURNACE.enumType(), "ordinal", "()I", false);
		mv.visitInsn(Opcodes.IALOAD);
		Label one = new Label(), three = new Label(), dflt = new Label();
		mv.visitLookupSwitchInsn(dflt, new int[] {1, 3}, new Label[] {one, three});
		mv.visitLabel(one);
		mv.visitInsn(Opcodes.ICONST_1);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitLabel(three);
		mv.visitInsn(Opcodes.ICONST_3);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitLabel(dflt);
		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		byte[] bytes = cw.toByteArray();
		ClassNode node = parse(bytes);
		// Call the repair directly: the whole transformer would also run its other 37 on this synthetic class.
		assertEquals(1, reads(node));
		byte[] out = new ForbricMergedBaseCompatTransformer().transform(FURNACE.user().replace('/', '.'), bytes, null);
		assertEquals(1, reads(parse(out)), "key 3 is unnamed, so the read must survive untouched");
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static int reads(ClassNode node) {
		int n = 0;
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && FURNACE.field().equals(f.name)) n++;
			}
		}
		return n;
	}

	private static byte[] transform(byte[] bytes) {
		return new ForbricMergedBaseCompatTransformer().transform(FURNACE.user().replace('/', '.'), bytes, null);
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

	private static byte[] bytesOf(Path jar, String internal) throws Exception {
		assumeTrue(Files.isRegularFile(jar), "staged jar absent: " + jar);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
			return i;
		}
		return -1;
	}

	static { assertTrue(new InsnNode(Opcodes.NOP).getOpcode() == Opcodes.NOP); }
}
