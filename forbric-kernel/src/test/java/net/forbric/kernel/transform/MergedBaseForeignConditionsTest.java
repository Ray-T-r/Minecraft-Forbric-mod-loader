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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The wrap of {@code ICondition.CODEC} that keeps one ecosystem's condition dialect from failing the other's data.
 *
 * <p>What makes the insertion point load-bearing rather than stylistic: {@code LIST_CODEC} is built FROM
 * {@code CODEC} two instructions later in the same initializer, so wrapping before the {@code PUTSTATIC} covers
 * the list form — which is the one {@code neoforge:conditions} actually uses — and wrapping after it would not.
 */
class MergedBaseForeignConditionsTest {
	private static final Path NEO_CARRIER = TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);
	private static final String ENTRY = "net/neoforged/neoforge/common/conditions/ICondition.class";
	private static final String BINARY = "net.neoforged.neoforge.common.conditions.ICondition";
	private static final String KERNEL = "net/forbric/kernel/runtime/KernelNeoConditions";

	@Test
	void theCodecIsWrappedBeforeItIsStoredAndBeforeListCodecIsDerived() throws Exception {
		MethodNode clinit = clinit(repaired());
		AbstractInsnNode[] body = clinit.instructions.toArray();

		int wrap = -1;
		int store = -1;
		int list = -1;
		for (int i = 0; i < body.length; i++) {
			if (body[i] instanceof MethodInsnNode call && KERNEL.equals(call.owner) && "lenient".equals(call.name)) {
				assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
				assertEquals("(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;", call.desc,
						"a Codec in and a Codec out, or the PUTSTATIC after it is storing something else");
				wrap = i;
			} else if (body[i] instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
					&& "CODEC".equals(field.name)) {
				store = i;
			} else if (body[i] instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
					&& "LIST_CODEC".equals(field.name)) {
				list = i;
			}
		}

		assertTrue(wrap >= 0, "ICondition.CODEC is not wrapped — a Fabric mod's own condition id then fails the "
				+ "whole registry load, because NeoForge's base runs NeoForge's evaluator over every pack's data, a Fabric "
				+ "mod's included (the wrap tolerates the type only in a Fabric mod's own pack, D69)");
		assertTrue(store > wrap, "the wrap must happen before CODEC is stored");
		assertTrue(list < 0 || list > store,
				"LIST_CODEC is derived from CODEC, so it has to be built after the wrapped value is in place — "
						+ "neoforge:conditions is a LIST, and an unwrapped list codec is the whole failure back");
	}

	/**
	 * D69: the leniency is for a Fabric mod's own pack, and only the reader knows the pack. NeoForge's base
	 * {@code Resource.open()} starts by handing its {@code source} pack to {@code KernelConditionSources.reading}; nothing
	 * else of the method moves, and a second pass leaves it alone.
	 */
	@Test
	void resourceOpenTellsTheLeniencyWhichPackTheFileIsFrom() throws Exception {
		String entry = ForbricMergedBaseCompatTransformer.RESOURCE + ".class", binary = ForbricMergedBaseCompatTransformer.RESOURCE.replace('/', '.');
		byte[] raw = TestFixtures.requireEntry(Fixture.STAGED, TestFixtures.stagedBase(), entry);
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(binary, raw, null);
		MethodNode open = null;
		ClassNode node = new ClassNode();
		new ClassReader(once).accept(node, 0);
		for (MethodNode m : node.methods) if (m.name.equals("open") && m.desc.equals("()Ljava/io/InputStream;")) open = m;
		assertTrue(open != null, "Resource.open() is not there");
		java.util.List<AbstractInsnNode> code = new java.util.ArrayList<>();
		for (AbstractInsnNode insn : open.instructions) if (insn.getOpcode() >= 0) code.add(insn);
		assertEquals(Opcodes.ALOAD, code.get(0).getOpcode());
		assertTrue(code.get(1) instanceof FieldInsnNode f && f.name.equals("source") && f.getOpcode() == Opcodes.GETFIELD,
				"the second instruction reads the resource's own pack");
		assertTrue(code.get(2) instanceof MethodInsnNode call && call.owner.equals(ForbricMergedBaseCompatTransformer.KERNEL_CONDITION_SOURCES)
				&& call.name.equals("reading") && call.desc.equals("(Ljava/lang/Object;)V"), "then hands it over");
		ClassNode before = new ClassNode();
		new ClassReader(raw).accept(before, 0);
		for (MethodNode m : before.methods) {
			if (!m.name.equals("open")) continue;
			assertEquals(m.instructions.size() + 3, open.instructions.size(), "three instructions added, nothing else");
		}
		assertSame(once, new ForbricMergedBaseCompatTransformer().transform(binary, once, null), "a second pass stands down");
	}

	/**
	 * Recipes are parsed in one pass and decoded in a second, so the pack named at the open is the last file's at every
	 * decode (lens A of lane S's wave-2 review). On NeoForge's base {@code scanDirectoryWithModifier} starts a scan, records
	 * each element right after its {@code JsonParser.parseReader}, names it again right before the second loop's
	 * {@code Codec.parse} (which stays, for a guest's wrap), and ends the scan before its return; the one-pass
	 * {@code scanDirectory} clears the pack before its return. The bytecode still verifies, and a second pass stands down.
	 */
	@Test
	void recipesTwoPassScanNamesEachElementsOwnPack() throws Exception {
		String owner = "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener";
		byte[] raw = TestFixtures.requireEntry(Fixture.STAGED, TestFixtures.stagedBase(), owner + ".class");
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(owner.replace('/', '.'), raw, null);
		ClassNode node = new ClassNode();
		new ClassReader(once).accept(node, 0);
		String sources = ForbricMergedBaseCompatTransformer.KERNEL_CONDITION_SOURCES;
		MethodNode twoPass = null, onePass = null;
		for (MethodNode m : node.methods) {
			if (m.name.equals("scanDirectoryWithModifier")) twoPass = m;
			if (m.name.equals("scanDirectory") && m.desc.contains("FileToIdConverter;Lcom/mojang/serialization/DynamicOps;")) onePass = m;
		}
		assertTrue(twoPass != null && onePass != null, "both scans are there");
		java.util.List<AbstractInsnNode> code = new java.util.ArrayList<>();
		for (AbstractInsnNode insn : twoPass.instructions) if (insn.getOpcode() >= 0) code.add(insn);
		assertTrue(isCall(code.get(0), sources, "beginTwoPassScan"), "the scan starts first");
		int parse = -1, decode = -1, returns = 0, ends = 0;
		for (int i = 0; i < code.size(); i++) {
			AbstractInsnNode insn = code.get(i);
			if (insn instanceof MethodInsnNode call && call.owner.equals("com/google/gson/JsonParser")) parse = i;
			if (insn instanceof MethodInsnNode call && call.owner.equals("com/mojang/serialization/Codec") && call.name.equals("parse")) decode = i;
			if (insn.getOpcode() == Opcodes.RETURN) {
				returns++;
				if (isCall(code.get(i - 1), sources, "endTwoPassScan")) ends++;
			}
		}
		assertTrue(parse > 0 && decode > 0, "the json parse and the codec parse are both still there");
		assertEquals(Opcodes.DUP, code.get(parse + 1).getOpcode());
		assertTrue(isCall(code.get(parse + 2), sources, "parsed"), "each parsed element is recorded with its pack");
		assertEquals(Opcodes.DUP, code.get(decode - 2).getOpcode());
		assertTrue(isCall(code.get(decode - 1), sources, "decoding"), "and named again right before its decode");
		assertTrue(returns > 0 && ends == returns, "every return ends the scan");
		int oneReturns = 0, done = 0;
		java.util.List<AbstractInsnNode> one = new java.util.ArrayList<>();
		for (AbstractInsnNode insn : onePass.instructions) if (insn.getOpcode() >= 0) one.add(insn);
		for (int i = 0; i < one.size(); i++) {
			if (one.get(i).getOpcode() != Opcodes.RETURN) continue;
			oneReturns++;
			if (isCall(one.get(i - 1), sources, "doneReading")) done++;
		}
		assertTrue(oneReturns > 0 && done == oneReturns, "the one-pass scan names no pack past its return");
		for (MethodNode m : java.util.List.of(twoPass, onePass)) {
			new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(node.name, m);
		}
		ClassNode before = new ClassNode();
		new ClassReader(raw).accept(before, 0);
		for (MethodNode m : before.methods) {
			if (!m.name.equals("scanDirectoryWithModifier")) continue;
			assertEquals(m.instructions.size() + 5 + returns, twoPass.instructions.size(), "insertions only");
		}
		byte[] twice = new ForbricMergedBaseCompatTransformer().transform(owner.replace('/', '.'), once, null);
		ClassNode again = new ClassNode();
		new ClassReader(twice).accept(again, 0);
		for (MethodNode m : again.methods) {
			if (m.name.equals("scanDirectoryWithModifier")) assertEquals(twoPass.instructions.size(), m.instructions.size(), "a second pass stands down");
		}
	}

	private static boolean isCall(AbstractInsnNode insn, String owner, String name) {
		return insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name);
	}

	@Test
	void aSecondPassDoesNotWrapItTwice() throws Exception {
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(BINARY, original(), null);
		byte[] twice = new ForbricMergedBaseCompatTransformer().transform(BINARY, once, null);
		assertSame(once, twice, "wrapping the wrapper would work and would also make the log lie about how many "
				+ "conditions were ignored");
	}

	private static ClassNode repaired() throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(new ForbricMergedBaseCompatTransformer().transform(BINARY, original(), null)).accept(node, 0);
		return node;
	}

	private static byte[] original() throws IOException {
		return TestFixtures.requireEntry(Fixture.STAGED, NEO_CARRIER, ENTRY);
	}

	private static MethodNode clinit(ClassNode node) {
		for (MethodNode method : node.methods) {
			if ("<clinit>".equals(method.name)) return method;
		}
		throw new AssertionError("ICondition has no static initializer");
	}
}
