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
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * Covers the item-attribute repair, whose visible symptom was that elytra flight did not work.
 *
 * <p>The merge split one mechanism in half: it took NeoForge's {@code LivingEntity.canGlide}, which decides
 * gliding from the {@code neoforge:gliding_flight} attribute and never looks at the item, and vanilla's
 * {@code ItemStack.forEachModifier}, which reads the raw component. NeoForge's version of that method calls
 * {@code getAttributeModifiers()}, which posts {@code ItemAttributeModifierEvent} — and NeoForge's own listener
 * on that event is the only thing anywhere that raises the gliding attribute. Producer on one side, consumer on
 * the other, and the attribute sat at its {@code false} default forever.
 */
class MergedBaseItemAttributesTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final Path NEO_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "neoforge-patched",
					"patched-mc-neoforge-26.2.jar").normalize();

	private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";

	@Test
	void theStagedBaseStillReadsTheRawComponent() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		ClassNode node = parse(readClass(MERGED_BASE));
		List<MethodNode> each = overloads(node);
		assertEquals(2, each.size(), "both forEachModifier overloads must still be there");
		for (MethodNode m : each) {
			assertTrue(readsRawComponent(m), "the base must still need the repair in " + m.desc
					+ " — if upstream changed it, re-derive this test");
			assertTrue(!callsNeoForge(m), "and must not already ask NeoForge");
		}
	}

	/** What the repair produces has to be what the family that owns the consumer actually does. */
	@Test
	void theNeoForgeBaseIsWhatTheRepairReproduces() throws Exception {
		assumeTrue(Files.isRegularFile(NEO_BASE), "staged NeoForge base absent");
		for (MethodNode m : overloads(parse(readClass(NEO_BASE)))) {
			assertTrue(callsNeoForge(m), "NeoForge's own base must call getAttributeModifiers in " + m.desc);
			assertTrue(!readsRawComponent(m), "and must not read the raw component");
		}
	}

	@Test
	void bothOverloadsAskNeoForgeAfterTheRepair() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] in = readClass(MERGED_BASE);
		byte[] out = transform(in);
		assertTrue(out != in, "the staged base must still need the repair");

		ClassNode node = parse(out);
		List<MethodNode> each = overloads(node);
		assertEquals(2, each.size());
		for (MethodNode m : each) {
			assertTrue(callsNeoForge(m), "every overload must ask NeoForge, not just the first: " + m.desc);
			assertTrue(!readsRawComponent(m), "the raw read must be gone from " + m.desc);
			new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		}
	}

	/**
	 * Nothing else in the class is touched.
	 *
	 * <p>{@code ATTRIBUTE_MODIFIERS} is read in several places in {@code ItemStack}, and only the two inside
	 * {@code forEachModifier} are the ones NeoForge routes through its event — rewriting a tooltip's read would
	 * change what a tooltip says, not what an entity can do.
	 */
	@Test
	void readsOutsideForEachModifierAreLeftAlone() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		ClassNode before = parse(readClass(MERGED_BASE));
		ClassNode after = parse(transform(readClass(MERGED_BASE)));
		int removed = rawReads(before) - rawReads(after);
		assertEquals(2, removed, "exactly the two reads in forEachModifier, and no others");
	}

	@Test
	void aSecondPassLeavesTheRepairedClassAlone() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] once = transform(readClass(MERGED_BASE));
		assertSame(once, transform(once), "a body that already asks NeoForge must not be rewritten");
	}

	@Test
	void anyOtherClassIsHandedBackUntouched() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] other = readEntry(MERGED_BASE, "net/minecraft/world/entity/LivingEntity.class");
		assertSame(other, new ForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.world.entity.LivingEntity", other, null));
	}

	// --- helpers ---------------------------------------------------------------------------------------------

	private static List<MethodNode> overloads(ClassNode node) {
		List<MethodNode> out = new ArrayList<>();
		for (MethodNode m : node.methods) {
			if ("forEachModifier".equals(m.name)) out.add(m);
		}
		return out;
	}

	private static boolean readsRawComponent(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
					&& "net/minecraft/core/component/DataComponents".equals(f.owner)
					&& "ATTRIBUTE_MODIFIERS".equals(f.name)) {
				return true;
			}
		}
		return false;
	}

	private static boolean callsNeoForge(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode m && "getAttributeModifiers".equals(m.name)) return true;
		}
		return false;
	}

	private static int rawReads(ClassNode node) {
		int n = 0;
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
						&& "net/minecraft/core/component/DataComponents".equals(f.owner)
						&& "ATTRIBUTE_MODIFIERS".equals(f.name)) {
					n++;
				}
			}
		}
		return n;
	}

	private static byte[] transform(byte[] bytes) {
		return new ForbricMergedBaseCompatTransformer()
				.transform(ITEM_STACK.replace('/', '.'), bytes, null);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] readClass(Path jar) throws Exception {
		return readEntry(jar, ITEM_STACK + ".class");
	}

	private static byte[] readEntry(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry e = zip.getEntry(entry);
			assertNotNull(e, entry + " must be in " + jar.getFileName());
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}
}
