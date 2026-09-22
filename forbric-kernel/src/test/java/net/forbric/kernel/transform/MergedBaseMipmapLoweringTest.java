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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * Lowering an atlas's mip level to fit its smallest sprite is vanilla behaviour. MinecraftForge patches
 * {@code SpriteLoader.stitch} to gate it on {@code ForgeConfig.CLIENT.allowMipmapLowering()}, whose default is
 * FALSE, and the byte merge kept that half — so one ecosystem's opt-out bound all three.
 *
 * <p>What it cost: the Logistics mod's own atlas holds an 8x8 sprite, the GPU refused the upload
 * ("mipLevels must be at most 4 for a texture of width 8 and height 8"), the FIRST resource reload died, vanilla
 * dropped every pack and reloaded into the same failure, and the client rendered a black screen for the rest of
 * the run — no crash report and no further log line.
 */
@ResourceLock("system-properties")
class MergedBaseMipmapLoweringTest {
	private static final Path MERGED_BASE =
			Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final String SPRITE_LOADER = "net/minecraft/client/renderer/texture/SpriteLoader";
	private static final String FORGE_CLIENT = "net/minecraftforge/common/ForgeConfig$Client";

	@Test
	void theRealMergedBaseHasTheGateAndLosesItAndStaysVerifiable() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		ClassNode before = parse(readClass(SPRITE_LOADER + ".class"));
		assertEquals(1, gateCount(before),
				"the merged base must still carry MinecraftForge's opt-in gate — if it stopped, re-derive this test");

		byte[] out = transform(readClass(SPRITE_LOADER + ".class"));
		ClassNode after = parse(out);
		assertEquals(0, gateCount(after), "no call to allowMipmapLowering may survive");
		assertFalse(namesForgeClientConfig(after), "and nothing may still read ForgeConfig.CLIENT for it");

		for (MethodNode method : after.methods) new Analyzer<>(new BasicVerifier()).analyze(after.name, method);
	}

	@Test
	void theGateBecomesAConstantTrueSoTheBranchAlwaysLowers() {
		byte[] out = transform(spriteLoaderWithGate());
		MethodNode stitch = method(parse(out), "stitch", "()I");
		assertNotNull(stitch);
		// ICONST_1 where the two-instruction read used to be: same stack shape at the branch, no new jump, so the
		// frames this transformer does not recompute still describe the method.
		AbstractInsnNode first = firstReal(stitch);
		assertEquals(Opcodes.ICONST_1, first.getOpcode(), "the gate must be a constant true");
		assertEquals(Opcodes.IFEQ, first.getNext().getOpcode(), "and MinecraftForge's own branch must still be there");
	}

	@Test
	void theSwitchLeavesMinecraftForgesGateInPlace() {
		byte[] in = spriteLoaderWithGate();
		System.setProperty(ForbricMergedBaseCompatTransformer.MIPMAP_PROPERTY, "off");
		try {
			assertSame(in, transform(in), "-Dforbric.mipmapLowering=off must hand the decision back to the config");
		} finally {
			System.clearProperty(ForbricMergedBaseCompatTransformer.MIPMAP_PROPERTY);
		}
	}

	@Test
	void aSecondPassLeavesTheRepairedClassAlone() {
		byte[] once = transform(spriteLoaderWithGate());
		assertSame(once, transform(once), "there is no gate left to force");
	}

	@Test
	void otherClassesAreNotTouched() {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/minecraft/client/renderer/texture/NotSpriteLoader", null,
				"java/lang/Object", null);
		node.methods.add(gatedStitch());
		byte[] in = write(node);
		assertSame(in, new ForbricMergedBaseCompatTransformer().transform(node.name.replace('/', '.'), in, null));
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	private static int gateCount(ClassNode node) {
		int count = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && FORGE_CLIENT.equals(call.owner)
						&& "allowMipmapLowering".equals(call.name)) {
					count++;
				}
			}
		}
		return count;
	}

	private static boolean namesForgeClientConfig(ClassNode node) {
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode field && field.desc.contains("ForgeConfig$Client")) return true;
			}
		}
		return false;
	}

	/** {@code if (ForgeConfig.CLIENT.allowMipmapLowering()) return 3; return 4;} — MinecraftForge's shape. */
	private static MethodNode gatedStitch() {
		MethodNode stitch = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "stitch", "()I", null, null);
		LabelNode skip = new LabelNode();
		stitch.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraftforge/common/ForgeConfig",
				"CLIENT", "L" + FORGE_CLIENT + ";"));
		stitch.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FORGE_CLIENT, "allowMipmapLowering",
				"()Z", false));
		stitch.instructions.add(new JumpInsnNode(Opcodes.IFEQ, skip));
		stitch.instructions.add(new InsnNode(Opcodes.ICONST_3));
		stitch.instructions.add(new InsnNode(Opcodes.IRETURN));
		stitch.instructions.add(skip);
		stitch.instructions.add(new InsnNode(Opcodes.ICONST_4));
		stitch.instructions.add(new InsnNode(Opcodes.IRETURN));
		stitch.maxStack = 1;
		stitch.maxLocals = 0;
		return stitch;
	}

	private static byte[] spriteLoaderWithGate() {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, SPRITE_LOADER, null, "java/lang/Object", null);
		node.methods.add(gatedStitch());
		return write(node);
	}

	private static AbstractInsnNode firstReal(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) return insn;
		throw new AssertionError("no instructions");
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	private static byte[] transform(byte[] bytes) {
		return new ForbricMergedBaseCompatTransformer().transform(SPRITE_LOADER.replace('/', '.'), bytes, null);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] write(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] readClass(String entry) throws Exception {
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			assertNotNull(found, entry + " is not in the merged base");
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}
