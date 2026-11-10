/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * Lane K4 (D75): on NeoForge's base, {@code PoiTypes.registerBlockStates} first asks {@code KernelPoiStates} whether the
 * call is a Fabric mod's, and returns when it is; vanilla's body is otherwise untouched. Read from the staged genuine base,
 * whose {@code TYPE_BY_STATE} is NeoForge's {@code BLOCKSTATE_TO_POI_TYPE_MAP} and whose {@code register} still calls
 * {@code registerBlockStates} after {@code Registry.register} (so the add-callback and vanilla's call both map a state).
 */
class MergedBasePoiStatesTest {
	private static final String POI_TYPES = ForbricMergedBaseCompatTransformer.POI_TYPES;

	private static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode registerBlockStates(ClassNode node) {
		for (MethodNode m : node.methods) {
			if (m.name.equals("registerBlockStates") && m.desc.equals(ForbricMergedBaseCompatTransformer.REGISTER_BLOCK_STATES_DESC)) return m;
		}
		return null;
	}

	private static List<AbstractInsnNode> code(MethodNode m) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : m.instructions) if (insn.getOpcode() >= 0) out.add(insn);
		return out;
	}

	@Test
	void theBaseStillMapsTwiceSoTheRepairHasSomethingToDo() throws Exception {
		ClassNode raw = read(TestFixtures.requireEntry(Fixture.STAGED, TestFixtures.stagedBase(), POI_TYPES + ".class"));
		boolean neoTable = false;
		for (MethodNode m : raw.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (m.name.equals("<clinit>") && insn instanceof MethodInsnNode call
						&& call.owner.equals("net/neoforged/neoforge/registries/GameData")
						&& call.name.equals("getBlockStatePointOfInterestTypeMap")) neoTable = true;
			}
		}
		assertTrue(neoTable, "TYPE_BY_STATE is NeoForge's callback map on this base — if it is not, the double mapping this "
				+ "repair answers is gone and the repair should be re-examined (C3 checks)");
	}

	@Test
	void registerBlockStatesAsksTheKernelFirstAndOtherwiseRunsVanillasBody() throws Exception {
		byte[] raw = TestFixtures.requireEntry(Fixture.STAGED, TestFixtures.stagedBase(), POI_TYPES + ".class");
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(POI_TYPES.replace('/', '.'), raw, null);
		ClassNode node = read(once);
		MethodNode method = registerBlockStates(node);
		assertNotNull(method, "PoiTypes.registerBlockStates(Holder, Set) is not there");
		List<AbstractInsnNode> code = code(method);
		assertTrue(code.get(0) instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.owner.equals(POI_TYPES)
				&& f.name.equals("TYPE_BY_STATE"), "without the repair a Fabric mod's call throws \"defined in more than one PoI "
						+ "type\" on the states NeoForge's callback mapped for its own type (Waystones-fabric's main entrypoint)");
		assertTrue(code.get(1) instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0);
		assertTrue(code.get(2) instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 1);
		assertTrue(code.get(3) instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
				&& call.owner.equals(ForbricMergedBaseCompatTransformer.KERNEL_POI_STATES) && call.name.equals("registerBlockStates")
				&& call.desc.equals(ForbricMergedBaseCompatTransformer.KERNEL_POI_STATES_DESC));
		assertTrue(code.get(4) instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ);
		assertEquals(Opcodes.RETURN, code.get(5).getOpcode());

		List<AbstractInsnNode> vanilla = code(registerBlockStates(read(raw)));
		assertEquals(vanilla.size() + 6, code.size(), "six instructions added at the head, nothing else");
		for (int i = 0; i < vanilla.size(); i++) assertEquals(vanilla.get(i).getOpcode(), code.get(i + 6).getOpcode());
		new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
		assertSame(once, new ForbricMergedBaseCompatTransformer().transform(POI_TYPES.replace('/', '.'), once, null), "a second pass stands down");
	}

	@Test
	void theInsertedCallNamesTheRuntimeHelpersRealMethod() throws Exception {
		java.net.URL boot = net.forbric.kernel.util.ForbricLog.class.getProtectionDomain().getCodeSource().getLocation();
		try (java.net.URLClassLoader game = net.forbric.kernel.runtime.StagedGameClassLoader.create(List.of(boot))) {
			Method helper = Class.forName(ForbricMergedBaseCompatTransformer.KERNEL_POI_STATES.replace('/', '.'), false, game)
					.getMethod("registerBlockStates", Map.class, Object.class, Set.class);
			assertTrue(Modifier.isStatic(helper.getModifiers()) && Modifier.isPublic(helper.getModifiers()));
			assertEquals(ForbricMergedBaseCompatTransformer.KERNEL_POI_STATES_DESC, Type.getMethodDescriptor(helper));
		}
	}
}
