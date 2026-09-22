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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The census that found the two defects nothing else could see, kept runnable.
 *
 * <p>The merged base's Minecraft half comes out of NeoForge's decompile-recompile pipeline, so it is not vanilla's
 * bytecode even where nobody patched anything. {@code merge-conflicts.txt} reports conflicts by REFERENCE — which
 * is the right signal for a merge, and structurally blind to a method that names no ecosystem class and came back
 * computing something else. Two did: {@code nextDouble()} rounding through float, and the radians-to-degrees
 * constant being divided out at run time instead of folded.
 *
 * <p>So this compares every method the two jars share, normalised for everything a round trip may legally change
 * — local variable slots, {@code goto}-versus-fallthrough, {@code ldc} widths, which int-push opcode was chosen,
 * branch offsets, line numbers, frames — and keeps the methods whose remaining difference is nothing but
 * conversions, typed arithmetic and numeric literals. Every one of those must be a method the kernel repairs:
 * it runs the merged class through the transformer and asks whether the method now matches vanilla's.
 *
 * <p>That is deliberately not a list of known families. A list would pass the day the pipeline invents a third
 * one. ~1s over 94k methods.
 */
class MergedBasePipelineDriftTest {
	private static final Path MERGED_BASE =
			Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final Path VANILLA = Path.of(System.getProperty("user.home"),
			"Library", "Application Support", "minecraft", "versions", "26.2", "26.2.jar");

	/**
	 * Numeric differences that are real and are NOT pipeline drift, each with why.
	 *
	 * <p>Attributed by reading all four jars — vanilla, {@code patched-mc-forge}, {@code patched-mc-neoforge} and
	 * the merge — rather than by deciding from the name. A difference only NeoForge's patched jar carries is a
	 * NeoForge patch; one BOTH patched jars carry is the shared decompile-recompile pipeline.
	 *
	 * <ul>
	 * <li>{@code Goat.finalizeSpawn} compares a float against 0.1f. Vanilla widens both sides and compares in
	 * double ({@code f2d; ldc2_w 0.10000000149011612; dcmpg}); the merged base compares in float
	 * ({@code ldc 0.1f; fcmpg}). {@code f2d} is exact and order-preserving, {@code 0.10000000149011612} IS
	 * {@code (double)0.1f}, and {@code dcmpg} and {@code fcmpg} agree on NaN — identical for every input.</li>
	 * <li>{@code ServerboundSelectKnownPacks.<clinit>} 64 → 1024 and {@code ModelDiscovery$ModelWrapper} 7 → 8
	 * are in NeoForge's patched jar and NOT in MinecraftForge's: deliberate NeoForge patches (a bigger
	 * known-packs limit, one more model slot), which is what running NeoForge means.</li>
	 * <li>{@code CompoundTag.getBooleanOr} and {@code ChunkMap.markPosition} gain an {@code i2b} that BOTH
	 * patched jars carry, so it is the pipeline making an implicit narrowing explicit. The values reaching it are
	 * only ever -1, 0 or 1 (read the bodies: {@code iconst_m1}/{@code iconst_1} into
	 * {@code Long2ByteMap.put(JB)B}, and {@code 1}/{@code 0} into {@code getByteOr(String,B)}), and {@code i2b}
	 * on a value already in byte range is the identity.</li>
	 * </ul>
	 */
	private static final Set<String> ARGUED = Set.of(
			"net/minecraft/world/entity/animal/goat/Goat.finalizeSpawn(Lnet/minecraft/world/level/ServerLevelAccessor;"
					+ "Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/EntitySpawnReason;"
					+ "Lnet/minecraft/world/entity/SpawnGroupData;)Lnet/minecraft/world/entity/SpawnGroupData;",
			"net/minecraft/network/protocol/configuration/ServerboundSelectKnownPacks.<clinit>()V",
			"net/minecraft/client/resources/model/ModelDiscovery$ModelWrapper.slot(I)"
					+ "Lnet/minecraft/client/resources/model/ModelDiscovery$Slot;",
			"net/minecraft/client/resources/model/ModelDiscovery$ModelWrapper.<init>(Lnet/minecraft/resources/Identifier;"
					+ "Lnet/minecraft/client/resources/model/UnbakedModel;Z)V",
			"net/minecraft/nbt/CompoundTag.getBooleanOr(Ljava/lang/String;Z)Z",
			"net/minecraft/server/level/ChunkMap.markPosition(Lnet/minecraft/world/level/ChunkPos;"
					+ "Lnet/minecraft/world/level/chunk/status/ChunkType;)B");

	private static final Set<String> NUMERIC = Set.of(
			"I2L", "I2F", "I2D", "L2I", "L2F", "L2D", "F2I", "F2L", "F2D", "D2I", "D2L", "D2F", "I2B", "I2C", "I2S",
			"IADD", "LADD", "FADD", "DADD", "ISUB", "LSUB", "FSUB", "DSUB",
			"IMUL", "LMUL", "FMUL", "DMUL", "IDIV", "LDIV", "FDIV", "DDIV",
			"IREM", "LREM", "FREM", "DREM", "INEG", "LNEG", "FNEG", "DNEG",
			"FCMPL", "FCMPG", "DCMPL", "DCMPG", "LCMP");

	@Test
	void everyNumericDifferenceFromVanillaIsOneTheKernelRepairs() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "merged base not staged: " + MERGED_BASE);
		assumeTrue(Files.isRegularFile(VANILLA), "no vanilla 26.2 jar at " + VANILLA);

		List<String> unrepaired = new ArrayList<>();
		List<String> repaired = new ArrayList<>();
		int methodsCompared = 0;
		try (ZipFile v = new ZipFile(VANILLA.toFile()); ZipFile m = new ZipFile(MERGED_BASE.toFile())) {
			for (ZipEntry entry : v.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
				ZipEntry mirror = m.getEntry(entry.getName());
				if (mirror == null) continue;
				byte[] mergedBytes = bytes(m, mirror);
				ClassNode vanillaClass = read(bytes(v, entry)), mergedClass = read(mergedBytes);
				if (vanillaClass == null || mergedClass == null) continue;
				Map<String, MethodNode> mergedMethods = index(mergedClass);
				ClassNode afterRepair = null;
				for (MethodNode vanillaMethod : vanillaClass.methods) {
					// javac numbers lambda bodies by position in the source it is compiling, so
					// `lambda$static$306` in one jar and in the other are not the same method — comparing them
					// by name compares unrelated code, and the first run of this census reported eleven
					// "differences" in Blocks that were exactly that. Synthetic members have no stable identity
					// across a recompile, so they are out of scope. This IS a blind spot: drift inside a lambda
					// body would not be seen here. Closing it needs structural matching, not a name.
					if ((vanillaMethod.access & Opcodes.ACC_SYNTHETIC) != 0 || vanillaMethod.name.startsWith("lambda$")) {
						continue;
					}
					MethodNode mergedMethod = mergedMethods.get(vanillaMethod.name + vanillaMethod.desc);
					if (mergedMethod == null) continue;
					methodsCompared++;
					List<String> want = normalise(vanillaMethod), got = normalise(mergedMethod);
					if (want.equals(got) || !numericOnly(want, got)) continue;
					String where = vanillaClass.name + "." + vanillaMethod.name + vanillaMethod.desc;
					if (ARGUED.contains(where)) continue;

					if (afterRepair == null) {
						afterRepair = read(new ForbricMergedBaseCompatTransformer()
								.transform(vanillaClass.name.replace('/', '.'), mergedBytes, null));
					}
					MethodNode fixed = index(afterRepair).get(vanillaMethod.name + vanillaMethod.desc);
					if (fixed != null && want.equals(normalise(fixed))) repaired.add(where);
					else unrepaired.add(where + "\n        vanilla: " + sample(want, got)
							+ "\n        merged : " + sample(got, want));
				}
			}
		}
		assertTrue(methodsCompared > 50_000, "only " + methodsCompared + " methods were compared — the two jars did "
				+ "not line up, and a census that inspected nothing reports nothing");
		assertTrue(repaired.size() >= 2, "the census found " + repaired.size() + " repaired numeric differences; the "
				+ "two known ones (nextDouble and the radians-to-degrees constant) must still be found, or this "
				+ "test has stopped looking at what it was written for");
		assertEquals(List.of(), unrepaired, "the merged base computes something vanilla does not, in a method no "
				+ "repair covers. Either add a repair, or add it to ARGUED with the argument for why "
				+ "it is not pipeline drift");
	}

	/** True when everything that differs between the two bodies is a conversion, typed arithmetic or a literal. */
	private static boolean numericOnly(List<String> want, List<String> got) {
		Map<String, Integer> delta = new TreeMap<>();
		for (String token : want) delta.merge(token, 1, Integer::sum);
		for (String token : got) delta.merge(token, -1, Integer::sum);
		delta.values().removeIf(count -> count == 0);
		if (delta.isEmpty()) return false;
		for (String token : delta.keySet()) {
			if (!NUMERIC.contains(token) && !token.startsWith("CONST:") && !token.startsWith("CONST_I:")) return false;
		}
		return true;
	}

	private static String sample(List<String> from, List<String> against) {
		StringBuilder out = new StringBuilder();
		int shown = 0;
		for (int i = 0; i < from.size() && shown < 8; i++) {
			if (i < against.size() && from.get(i).equals(against.get(i))) continue;
			out.append(from.get(i)).append(' ');
			shown++;
		}
		return out.toString().trim();
	}

	/** One token per real instruction, with everything a recompile may legally change erased. */
	private static List<String> normalise(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			int op = insn.getOpcode();
			if (op < 0 || op == Opcodes.GOTO || op == Opcodes.NOP) continue;
			switch (insn) {
				case VarInsnNode ignored -> out.add(name(op).replaceAll("_\\d$", ""));
				case IincInsnNode i -> out.add("IINC:" + i.incr);
				case IntInsnNode i -> out.add(op == Opcodes.NEWARRAY ? "NEWARRAY:" + i.operand : "CONST_I:" + i.operand);
				case LdcInsnNode l -> out.add("CONST:" + l.cst.getClass().getSimpleName() + ":" + l.cst);
				case MethodInsnNode c -> out.add("INVOKE:" + name(op) + ":" + c.owner + "." + c.name + c.desc);
				case FieldInsnNode f -> out.add("FIELD:" + name(op) + ":" + f.owner + "." + f.name + ":" + f.desc);
				case TypeInsnNode t -> out.add(name(op) + ":" + t.desc);
				case MultiANewArrayInsnNode a -> out.add("MULTIANEWARRAY:" + a.desc + ":" + a.dims);
				case InvokeDynamicInsnNode d -> out.add("INDY:" + d.name + d.desc + ":" + d.bsm.getName());
				case JumpInsnNode ignored -> out.add("JUMP:" + name(op));
				case TableSwitchInsnNode t -> out.add("TABLESWITCH:" + t.min + "-" + t.max);
				case LookupSwitchInsnNode l -> out.add("LOOKUPSWITCH:" + l.keys);
				default -> {
					String n = name(op);
					if (n.matches("ICONST_M1|ICONST_\\d")) out.add("CONST_I:" + (op - Opcodes.ICONST_0));
					else out.add(n);
				}
			}
		}
		return out;
	}

	private static final String[] NAMES = new String[256];
	static {
		for (java.lang.reflect.Field field : Opcodes.class.getFields()) {
			String n = field.getName();
			if (field.getType() != int.class || n.startsWith("V") || n.startsWith("ACC_") || n.startsWith("T_")
					|| n.startsWith("H_") || n.startsWith("F_") || n.startsWith("ASM")) {
				continue;
			}
			try {
				int value = field.getInt(null);
				if (value >= 0 && value < 256 && NAMES[value] == null) NAMES[value] = n;
			} catch (IllegalAccessException ignored) {
				// A constant this census cannot read is a constant it does not name; name(int) falls back.
			}
		}
	}

	private static String name(int opcode) {
		String n = opcode >= 0 && opcode < 256 ? NAMES[opcode] : null;
		return n == null ? "OP" + opcode : n;
	}

	private static Map<String, MethodNode> index(ClassNode node) {
		Map<String, MethodNode> out = new HashMap<>();
		for (MethodNode method : node.methods) out.put(method.name + method.desc, method);
		return out;
	}

	private static byte[] bytes(ZipFile jar, ZipEntry entry) throws Exception {
		try (InputStream in = jar.getInputStream(entry)) {
			return in.readAllBytes();
		}
	}

	private static ClassNode read(byte[] bytes) {
		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return node;
		} catch (RuntimeException e) {
			return null;
		}
	}
}
