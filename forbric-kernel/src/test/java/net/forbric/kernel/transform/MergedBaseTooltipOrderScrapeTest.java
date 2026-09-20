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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/**
 * fabric-item-api-v1's scrape, re-implemented exactly as {@code VanillaTooltipProviderOrder.scrapeVanillaOrder}
 * does it, run over the transformed merged {@code ItemStack} and over stock 26.2's: the two lists must be equal.
 */
class MergedBaseTooltipOrderScrapeTest {
	private static final Path MERGED_BASE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";

	@AfterEach
	void reset() {
		System.clearProperty(TooltipOrderScrapeInjector.PROPERTY);
	}

	@Test
	void theScrapeOfTheTransformedMergedItemStackEqualsVanillas() throws Exception {
		Path vanilla = vanillaJar();
		assumeTrue(Files.isRegularFile(MERGED_BASE) && Files.isRegularFile(vanilla), "staged merged base or stock 26.2 absent");
		List<String> expected = scrape(parse(bytesOf(vanilla, ITEM_STACK)));
		assertTrue(expected.size() >= 20 && expected.contains("ATTRIBUTE_MODIFIERS"), "premise: vanilla's order is scrapeable: " + expected);
		assertEquals(List.of(), scrape(parse(bytesOf(MERGED_BASE, ITEM_STACK))), "premise: the merged body has nothing to scrape");

		byte[] out = new TooltipOrderScrapeInjector().transform(ITEM_STACK.replace('/', '.'), bytesOf(MERGED_BASE, ITEM_STACK), null);
		assertNotSame(bytesOf(MERGED_BASE, ITEM_STACK), out);
		assertEquals(expected, scrape(parse(out)), "element for element, in vanilla's order");
	}

	@Test
	void theInsertedHeadIsOnlyGetstaticPopPairsAndTheRestIsUntouched() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] original = bytesOf(MERGED_BASE, ITEM_STACK);
		ClassNode before = parse(original), after = parse(new TooltipOrderScrapeInjector().transform(ITEM_STACK.replace('/', '.'), original, null));
		MethodNode was = find(before, TooltipOrderScrapeInjector.METHOD, TooltipOrderScrapeInjector.DESC);
		MethodNode now = find(after, TooltipOrderScrapeInjector.METHOD, TooltipOrderScrapeInjector.DESC);
		AbstractInsnNode insn = now.instructions.getFirst();
		while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		assertTrue(insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC, "the first instruction is a GETSTATIC — the order sits at the HEAD");
		int pairs = 0;
		while (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && TooltipOrderScrapeInjector.DATA_COMPONENTS.equals(f.owner)) {
			AbstractInsnNode pop = insn.getNext();
			assertTrue(pop != null && pop.getOpcode() == Opcodes.POP, "each GETSTATIC is followed by its POP");
			pairs++;
			insn = pop.getNext();
			while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		}
		assertTrue(pairs >= 20, "pairs: " + pairs);
		assertEquals(scrape(after).size(), pairs, "one pair per component type: the head carries no duplicates");
		// From here on, the original method, instruction for instruction.
		assertEquals(listingFrom(was, firstReal(was)), listingFrom(now, insn), "everything after the head is byte-identical");
		for (MethodNode m : after.methods) new Analyzer<>(new BasicVerifier()).analyze(after.name, m);
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] once = new TooltipOrderScrapeInjector().transform(ITEM_STACK.replace('/', '.'), bytesOf(MERGED_BASE, ITEM_STACK), null);
		assertSame(once, new TooltipOrderScrapeInjector().transform(ITEM_STACK.replace('/', '.'), once, null));
	}

	/** Without the renamed body there is nothing to copy: the class comes back untouched. */
	@Test
	void anItemStackWithoutTheRenamedBodyIsLeftAlone() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, ITEM_STACK, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, TooltipOrderScrapeInjector.METHOD, TooltipOrderScrapeInjector.DESC, null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		byte[] bytes = cw.toByteArray();
		assertSame(bytes, new TooltipOrderScrapeInjector().transform(ITEM_STACK.replace('/', '.'), bytes, null));
	}

	@Test
	void switchedOffItStandsDownAndDeclaresNoAnchor() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		System.setProperty(TooltipOrderScrapeInjector.PROPERTY, "off");
		byte[] bytes = bytesOf(MERGED_BASE, ITEM_STACK);
		assertSame(bytes, new TooltipOrderScrapeInjector().transform(ITEM_STACK.replace('/', '.'), bytes, null));
		assertTrue(new TooltipOrderScrapeInjector().anchors().anchors().isEmpty());
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** fabric's scrape: GETSTATIC DataComponents.*:DataComponentType in order, set-deduplicated, the attribute call as ATTRIBUTE_MODIFIERS. */
	static List<String> scrape(ClassNode node) {
		MethodNode method = find(node, TooltipOrderScrapeInjector.METHOD, TooltipOrderScrapeInjector.DESC);
		Set<String> seen = new LinkedHashSet<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && TooltipOrderScrapeInjector.DATA_COMPONENTS.equals(f.owner)
					&& TooltipOrderScrapeInjector.DATA_COMPONENT_TYPE.equals(f.desc)) {
				seen.add(f.name);
			} else if (insn instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKEVIRTUAL && ITEM_STACK.equals(c.owner)
					&& TooltipOrderScrapeInjector.ATTRIBUTE_TOOLTIPS.equals(c.name) && TooltipOrderScrapeInjector.ATTRIBUTE_TOOLTIPS_DESC.equals(c.desc)) {
				seen.add("ATTRIBUTE_MODIFIERS");
			}
		}
		return new ArrayList<>(seen);
	}

	private static MethodNode find(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		throw new AssertionError(name + desc);
	}

	private static AbstractInsnNode firstReal(MethodNode m) {
		AbstractInsnNode insn = m.instructions.getFirst();
		while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		return insn;
	}

	private static String listingFrom(MethodNode m, AbstractInsnNode from) {
		Textifier text = new Textifier();
		TraceMethodVisitor tracer = new TraceMethodVisitor(text);
		for (AbstractInsnNode insn = from; insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() < 0) continue;    // labels and line numbers differ by construction
			insn.accept(tracer);
		}
		StringBuilder sb = new StringBuilder();
		for (Object line : text.getText()) sb.append(line);
		return sb.toString();
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(Path jar, String internal) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}

	private static Path vanillaJar() {
		String env = System.getenv("MC_DIR");
		return Path.of(env != null && !env.isBlank() ? env : System.getProperty("user.home") + "/Library/Application Support/minecraft")
				.resolve("versions/26.2/26.2.jar");
	}
}
