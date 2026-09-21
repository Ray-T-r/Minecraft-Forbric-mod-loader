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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Covers restoring the radians-to-degrees constant vanilla folded.
 *
 * <p>The decisive assertion is the cross-check against the vanilla jar itself: after the repair, the merged
 * {@code Entity} must carry the SAME literal vanilla carries, and neither must carry the other's. Asserting
 * "57.2957763671875 is now there" alone would only pin the number this test and the transformer both hard-code,
 * and the whole point of the defect is that a plausible-looking number was the wrong one.
 */
class MergedBaseDegreeConstantTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final Path VANILLA = Path.of(System.getProperty("user.home"),
			"Library", "Application Support", "minecraft", "versions", "26.2", "26.2.jar");
	private static final String ENTITY = "net/minecraft/world/entity/Entity";
	private static final double HALF_TURN = 180.0;
	private static final double PI_AS_FLOAT = (double) (float) Math.PI;
	private static final double FOLDED = (double) (float) (180.0F / (float) Math.PI);
	private static final String FIXTURE = "net/minecraft/world/entity/ForbricDegreesFixture";

	@Test
	void theFoldedConstantIsTheOneVanillaActuallyCarries() {
		assertEquals(57.2957763671875, FOLDED, 0.0,
				"the repair's constant must be the float-folded one, not the double quotient");
		assertNotEquals(HALF_TURN / PI_AS_FLOAT, FOLDED,
				"if these were equal there would be no defect; the merged base's run-time quotient is 57.29577791868205");
	}

	@Test
	void theMergedEntityDividesByPiAndTheRepairFoldsItBack() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "merged base not staged: " + MERGED_BASE);
		byte[] before = entry(MERGED_BASE, ENTITY + ".class");
		assertTrue(sites(read(before)) > 0, "the merged Entity no longer divides by pi at run time — if the build "
				+ "pipeline started folding this, delete the repair rather than leaving a claim on a shape that is gone");

		ClassNode after = read(new ForbricMergedBaseCompatTransformer().transform(ENTITY.replace('/', '.'), before, null));
		assertEquals(0, sites(after), "Entity still divides by pi after the repair");
		assertTrue(carries(after, FOLDED), "Entity carries neither form after the repair — the multiply was removed "
				+ "rather than re-based");
	}

	@Test
	void vanillaCarriesTheSameLiteralAndNeverTheExpression() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "merged base not staged: " + MERGED_BASE);
		assumeTrue(Files.isRegularFile(VANILLA), "no vanilla 26.2 jar at " + VANILLA);
		ClassNode vanilla = read(entry(VANILLA, ENTITY + ".class"));
		assertEquals(0, sites(vanilla), "vanilla does not carry the unfolded expression; if it does, the premise "
				+ "of this repair is wrong");
		assertTrue(carries(vanilla, FOLDED), "vanilla's Entity must carry the folded literal this repair restores");
	}

	@Test
	void theRepairedBodyComputesWhatVanillaComputes() throws Throwable {
		MethodHandle repaired = compiled(new ForbricMergedBaseCompatTransformer()
				.transform(FIXTURE.replace('/', '.'), dividingFixture(), null));
		MethodHandle merged = compiled(dividingFixture());
		double[] radians = { 0.0, 0.5, 1.0, -1.0, Math.PI, Math.PI / 4, 3.7 };
		int differedBefore = 0;
		for (double value : radians) {
			assertEquals(value * FOLDED, (double) repaired.invokeExact(value), 0.0,
					"repaired fixture must reproduce vanilla's multiply exactly for " + value);
			if ((double) merged.invokeExact(value) != value * FOLDED) differedBefore++;
		}
		assertTrue(differedBefore >= 5, "the unrepaired fixture must actually differ, or this test passes vacuously; "
				+ "it differed on " + differedBefore + " of " + radians.length + " inputs");
	}

	@Test
	void itLeavesAnUnrelatedDivisionAlone() {
		byte[] unrelated = fixture(2.0, FIXTURE);
		assertSame(unrelated, new ForbricMergedBaseCompatTransformer()
				.transform(FIXTURE.replace('/', '.'), unrelated, null),
				"the repair matches `* 180.0 / (double)(float)PI`, not any double division");
	}

	@Test
	void itLeavesClassesOutsideTheGameAlone() {
		byte[] outside = fixture(PI_AS_FLOAT, "net/forbric/other/DegreesFixture");
		assertSame(outside, new ForbricMergedBaseCompatTransformer()
				.transform("net.forbric.other.DegreesFixture", outside, null));
	}

	/** How many `ldc2_w 180.0; dmul; ldc2_w (double)(float)PI; ddiv` chains the class still carries. */
	private static int sites(ClassNode node) {
		int found = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof LdcInsnNode a) || !Double.valueOf(HALF_TURN).equals(a.cst)) continue;
				AbstractInsnNode multiply = real(insn);
				if (multiply == null || multiply.getOpcode() != Opcodes.DMUL) continue;
				AbstractInsnNode pi = real(multiply);
				if (!(pi instanceof LdcInsnNode b) || !Double.valueOf(PI_AS_FLOAT).equals(b.cst)) continue;
				AbstractInsnNode divide = real(pi);
				if (divide != null && divide.getOpcode() == Opcodes.DDIV) found++;
			}
		}
		return found;
	}

	private static boolean carries(ClassNode node, double constant) {
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof LdcInsnNode ldc && Double.valueOf(constant).equals(ldc.cst)) return true;
			}
		}
		return false;
	}

	private static AbstractInsnNode real(AbstractInsnNode cursor) {
		AbstractInsnNode next = cursor == null ? null : cursor.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}

	private static byte[] dividingFixture() {
		return fixture(PI_AS_FLOAT, FIXTURE);
	}

	/** {@code static double degrees(double radians)} built as the pipeline emits it: multiply, then divide. */
	private static byte[] fixture(double divisor, String name) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		MethodVisitor degrees = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "degrees", "(D)D", null, null);
		degrees.visitCode();
		degrees.visitVarInsn(Opcodes.DLOAD, 0);
		degrees.visitLdcInsn(HALF_TURN);
		degrees.visitInsn(Opcodes.DMUL);
		degrees.visitLdcInsn(divisor);
		degrees.visitInsn(Opcodes.DDIV);
		degrees.visitInsn(Opcodes.DRETURN);
		degrees.visitMaxs(4, 2);
		degrees.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static MethodHandle compiled(byte[] bytes) throws Exception {
		ClassNode node = read(bytes);
		Class<?> defined = new ClassLoader(MergedBaseDegreeConstantTest.class.getClassLoader()) {
			Class<?> define() {
				return defineClass(node.name.replace('/', '.'), bytes, 0, bytes.length);
			}
		}.define();
		return MethodHandles.publicLookup().findStatic(defined, "degrees", MethodType.methodType(double.class, double.class));
	}

	private static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] entry(Path jarPath, String path) throws Exception {
		try (ZipFile jar = new ZipFile(jarPath.toFile())) {
			ZipEntry found = jar.getEntry(path);
			assertTrue(found != null, path + " is absent from " + jarPath);
			try (InputStream in = jar.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}
