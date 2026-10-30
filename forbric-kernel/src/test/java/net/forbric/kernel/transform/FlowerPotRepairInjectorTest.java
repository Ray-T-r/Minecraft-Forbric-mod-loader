/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;

/**
 * FlowerPotRepairInjector on the real classes of both bases. Its constructor edit is not a merge repair — NeoForge's own
 * {@code FlowerPotBlock(Block, Properties)} stores null into {@code potted} on the genuine base too — so it must land
 * there; only the two edits of MinecraftForge's half may stand down, and nothing either base gets may dangle.
 */
class FlowerPotRepairInjectorTest {
	private static final String POT = FlowerPotRepairInjector.OWNER;
	private static final String FULL_POT = "(L" + FlowerPotRepairInjector.BLOCK + ";)L" + FlowerPotRepairInjector.BLOCK + ";";

	@Test void onTheMergedBaseAllThreeEditsLand() throws Exception {
		Path merged = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		byte[] original = NativeCoremodParityTest.read(merged, POT);
		ClassNode pot = node(new FlowerPotRepairInjector().transform(FlowerPotRepairInjector.TARGET, original, null));
		assertTrue(storesThePlant(pot), "the vanilla-shaped constructor stores its plant");
		assertTrue(calls(method(pot, "getFullPot", FULL_POT), FlowerPotRepairInjector.RUNTIME),
				"the pot lookup answers from every family's pots");
		assertTrue(reads(method(pot, "addPlant", FlowerPotRepairInjector.ADD_PLANT), "fullPots"), "addPlant records into fullPots");
		for (MethodNode m : pot.methods) new Analyzer<>(new BasicVerifier()).analyze(POT, m);
	}

	@Test void onTheGenuineBaseTheConstructorEditLandsAloneAndLinks() throws Exception {
		List<Path> genuine = ChainReferences.genuineJars();
		byte[] original = ChainReferences.read(genuine, POT);
		ClassNode before = node(original);
		assertTrue(before.fields.stream().noneMatch(f -> f.name.equals("fullPots")), "premise: NeoForge's class has no MinecraftForge half");
		assertFalse(storesThePlant(before), "premise: and NeoForge's own constructor stores null into potted");

		byte[] out = new FlowerPotRepairInjector().transform(FlowerPotRepairInjector.TARGET, original, null);
		assertNotSame(original, out, "the repair is not a merge artifact, so it lands on the genuine base");
		ClassNode pot = node(out);
		assertTrue(storesThePlant(pot), "the vanilla-shaped constructor stores its plant into potted");
		MethodNode ctor = method(pot, "<init>", FlowerPotRepairInjector.BLOCK_CTOR);
		assertTrue(Arrays.stream(ctor.instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode f && f.name.equals("POTTED_BY_CONTENT")),
				"and registers itself in POTTED_BY_CONTENT, as vanilla's does — Fabric mods read both");
		assertFalse(calls(method(pot, "getFullPot", FULL_POT), FlowerPotRepairInjector.RUNTIME)
				|| calls(method(pot, "useItemOn", FlowerPotRepairInjector.USE_ITEM_ON), FlowerPotRepairInjector.RUNTIME),
				"the lookups serve MinecraftForge's fullPots, which this class does not have");
		for (MethodNode m : pot.methods) {
			assertFalse(reads(m, "fullPots"), m.name + " names no fullPots the class does not have");
			new Analyzer<>(new BasicVerifier()).analyze(POT, m);
		}
		ChainReferences.assertNoneDangling(Map.of(POT, original), Map.of(POT, out), genuine);
		assertSame(out, new FlowerPotRepairInjector().transform(FlowerPotRepairInjector.TARGET, out, null), "a second pass changes nothing");
	}

	@Test void vanillasClassIsNotThisRepairs() throws Exception {
		byte[] vanilla = NativeCoremodParityTest.read(TestFixtures.vanillaJar(), POT);
		assertSame(vanilla, new FlowerPotRepairInjector().transform(FlowerPotRepairInjector.TARGET, vanilla, null));
	}

	/** The constructor's one store into potted takes its plant argument, not null. */
	private static boolean storesThePlant(ClassNode pot) {
		MethodNode ctor = method(pot, "<init>", FlowerPotRepairInjector.BLOCK_CTOR);
		for (AbstractInsnNode insn : ctor.instructions) {
			if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD && put.name.equals("potted")) {
				return put.getPrevious() instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 1;
			}
		}
		return false;
	}

	private static boolean calls(MethodNode method, String owner) {
		return Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.owner.equals(owner));
	}

	private static boolean reads(MethodNode method, String field) {
		return Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode f && f.name.equals(field));
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
				.orElseThrow(() -> new AssertionError(name + desc));
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
