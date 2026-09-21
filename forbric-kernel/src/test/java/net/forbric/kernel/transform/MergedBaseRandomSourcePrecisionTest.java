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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * Covers putting the game's random sources back in double precision.
 *
 * <p>Two subjects on purpose. The REAL merged base proves the shape this repair reacts to is the shape the other
 * repository's merge actually emits — a synthetic fixture alone would only assert that the test author and the
 * transformer agree about a bug neither of them observed. A synthetic fixture proves the REPAIRED bytes compute
 * what vanilla computes, which reading the merged base cannot show: {@code XoroshiroRandomSource} cannot be
 * loaded here, and "the opcodes look right" is not "the number is right".
 *
 * <p>What it is protecting: {@code ImprovedNoise}'s constructor spends three {@code nextDouble() * 256.0} calls on
 * its origin, so a float-rounded {@code nextDouble()} displaces every Perlin octave in every world. Measured
 * against pure vanilla 26.2 on one seed: biomes differed in 11 of 1764 chunks and heightmaps in 90 of 400 fully
 * generated ones, where vanilla against itself differed in 0 and 10.
 */
class MergedBaseRandomSourcePrecisionTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final List<String> SOURCES = List.of(
			"net/minecraft/world/level/levelgen/XoroshiroRandomSource",
			"net/minecraft/world/level/levelgen/BitRandomSource");
	/** 2^-53 in each width. Exact in both, which is why only the l2f narrowing is the defect. */
	private static final float UNIT_AS_FLOAT = (float) 0x1.0p-53;
	private static final double UNIT = 0x1.0p-53;
	private static final String FIXTURE = "net/minecraft/world/level/levelgen/ForbricRandomUnitFixture";

	@Test
	void theMergedBaseScalesItsRandomBitsInFloatAndTheRepairPutsItBackInDouble() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "merged base not staged: " + MERGED_BASE);
		for (String source : SOURCES) {
			byte[] before = entry(source + ".class");
			assertTrue(scalesInFloat(read(before)), source + " no longer carries the float-rounded nextDouble() this "
					+ "repair exists for. If the merge pipeline fixed it, delete the repair — do not leave a claim "
					+ "asserting a shape that is gone");

			byte[] after = new ForbricMergedBaseCompatTransformer().transform(source.replace('/', '.'), before, null);
			ClassNode repaired = read(after);
			assertFalse(scalesInFloat(repaired), source + " still rounds through float after the repair");
			assertTrue(scalesInDouble(repaired), source + " scales by neither width after the repair — the multiply "
					+ "was removed rather than widened");
		}
	}

	@Test
	void theRepairedBodyReturnsExactlyWhatVanillaReturns() throws Throwable {
		MethodHandle repaired = compiled(new ForbricMergedBaseCompatTransformer()
				.transform(FIXTURE.replace('/', '.'), floatScalingFixture(), null));
		MethodHandle merged = compiled(floatScalingFixture());

		// 53-bit patterns: the two ends, the value that exposes the lost mantissa, and the first bits that
		// round to exactly 1.0 in float. nextBits(53) produces every one of them.
		long[] bits = { 0L, 1L, 6004799503160661L, (1L << 52), 9007198986305536L, (1L << 53) - 1 };
		int differedBefore = 0;
		for (long value : bits) {
			double vanilla = value * UNIT;
			assertEquals(vanilla, (double) repaired.invokeExact(value), 0.0,
					"repaired fixture must reproduce vanilla's double scaling exactly for bits=" + value);
			assertTrue((double) repaired.invokeExact(value) < 1.0,
					"nextDouble() owes its callers [0,1) for bits=" + value);
			if ((double) merged.invokeExact(value) != vanilla) differedBefore++;
		}
		assertTrue(differedBefore >= 3, "the unrepaired fixture must actually be wrong, or this test passes "
				+ "vacuously; it differed on " + differedBefore + " of " + bits.length + " patterns");
		assertEquals(1.0, (double) merged.invokeExact(9007198986305536L), 0.0,
				"the float body's out-of-range return is the second half of this defect and must stay demonstrated");
	}

	@Test
	void itLeavesAFloatMultiplyThatIsNotTheRandomUnitAlone() {
		byte[] unrelated = fixture(0.5f);
		assertSame(unrelated, new ForbricMergedBaseCompatTransformer()
				.transform(FIXTURE.replace('/', '.'), unrelated, null),
				"the repair matches a long scaled by 2^-53, not any long-to-float multiply");
	}

	@Test
	void theSwitchStandsTheRepairDownSoTheGateCanShowItsTeeth() {
		String property = ForbricMergedBaseCompatTransformer.RANDOM_PRECISION_PROPERTY;
		String previous = System.getProperty(property);
		System.setProperty(property, "off");
		try {
			byte[] fixture = floatScalingFixture();
			assertSame(fixture, new ForbricMergedBaseCompatTransformer()
					.transform(FIXTURE.replace('/', '.'), fixture, null),
					"-D" + property + "=off must leave the float-rounded body exactly as it was; gate-m31's RED "
							+ "demonstration is that world, and a switch that half-works demonstrates nothing");
		} finally {
			if (previous == null) System.clearProperty(property);
			else System.setProperty(property, previous);
		}
	}

	@Test
	void itLeavesClassesOutsideTheGameAlone() {
		byte[] outside = fixture(UNIT_AS_FLOAT, "net/forbric/other/RandomUnitFixture");
		assertSame(outside, new ForbricMergedBaseCompatTransformer()
				.transform("net.forbric.other.RandomUnitFixture", outside, null));
	}

	private static boolean scalesInFloat(ClassNode node) {
		return carries(node, Opcodes.L2F, Opcodes.FMUL, UNIT_AS_FLOAT);
	}

	private static boolean scalesInDouble(ClassNode node) {
		return carries(node, Opcodes.L2D, Opcodes.DMUL, UNIT);
	}

	private static boolean carries(ClassNode node, int widen, int multiply, Number unit) {
		for (MethodNode method : node.methods) {
			boolean sawWiden = false, sawUnit = false;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn.getOpcode() == widen) sawWiden = true;
				if (insn instanceof LdcInsnNode ldc && unit.equals(ldc.cst)) sawUnit = true;
				if (insn.getOpcode() == multiply && sawWiden && sawUnit) return true;
			}
		}
		return false;
	}

	private static byte[] floatScalingFixture() {
		return fixture(UNIT_AS_FLOAT);
	}

	private static byte[] fixture(float unit) {
		return fixture(unit, FIXTURE);
	}

	/** {@code static double scale(long bits)} built as the merge emits it: narrow, multiply, widen back. */
	private static byte[] fixture(float unit, String name) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		MethodVisitor scale = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "scale", "(J)D", null, null);
		scale.visitCode();
		scale.visitVarInsn(Opcodes.LLOAD, 0);
		scale.visitInsn(Opcodes.L2F);
		scale.visitLdcInsn(unit);
		scale.visitInsn(Opcodes.FMUL);
		scale.visitInsn(Opcodes.F2D);
		scale.visitInsn(Opcodes.DRETURN);
		scale.visitMaxs(2, 2);
		scale.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static MethodHandle compiled(byte[] bytes) throws Exception {
		ClassNode node = read(bytes);
		Class<?> defined = new ClassLoader(MergedBaseRandomSourcePrecisionTest.class.getClassLoader()) {
			Class<?> define() {
				return defineClass(node.name.replace('/', '.'), bytes, 0, bytes.length);
			}
		}.define();
		return MethodHandles.publicLookup().findStatic(defined, "scale", MethodType.methodType(double.class, long.class));
	}

	private static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] entry(String path) throws Exception {
		try (ZipFile jar = new ZipFile(MERGED_BASE.toFile())) {
			java.util.zip.ZipEntry found = jar.getEntry(path);
			assertTrue(found != null, path + " is absent from the merged base");
			try (InputStream in = jar.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}
