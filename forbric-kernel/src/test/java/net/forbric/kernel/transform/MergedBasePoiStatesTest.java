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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * Lane K4 (D75): on NeoForge's base, {@code PoiTypes.registerBlockStates} first calls
 * {@code KernelPoiStates.beforeRegisterBlockStates}, and vanilla's body then runs unchanged. Read from the staged genuine
 * base, whose {@code TYPE_BY_STATE} is NeoForge's {@code BLOCKSTATE_TO_POI_TYPE_MAP}, whose {@code register} still calls
 * {@code registerBlockStates} after {@code Registry.register}, and whose carrier attaches NeoForge's PoI type callback to
 * {@code POINT_OF_INTEREST_TYPE} (so the callback and vanilla's call both map a state a mod's type lists).
 */
class MergedBasePoiStatesTest {
	private static final String POI_TYPES = ForbricMergedBaseCompatTransformer.POI_TYPES;
	private static final String REGISTRIES_SETUP = "net/neoforged/neoforge/registries/NeoForgeRegistriesSetup";
	private static final String POI_CALLBACKS = "net/neoforged/neoforge/registries/NeoForgeRegistryCallbacks$PoiTypeCallbacks";

	private static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m;
		}
		return null;
	}

	private static MethodNode registerBlockStates(ClassNode node) {
		return method(node, "registerBlockStates", ForbricMergedBaseCompatTransformer.REGISTER_BLOCK_STATES_DESC);
	}

	private static List<AbstractInsnNode> code(MethodNode m) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : m.instructions) if (insn.getOpcode() >= 0) out.add(insn);
		return out;
	}

	private static int indexOf(List<AbstractInsnNode> code, String owner, String name) {
		for (int i = 0; i < code.size(); i++) {
			if (code.get(i) instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return i;
		}
		return -1;
	}

	@Test
	void theCallbackAndVanillasRegisterBothMapATypesStatesOnThisBase() throws Exception {
		ClassNode raw = read(TestFixtures.requireEntry(Fixture.STAGED, TestFixtures.stagedBase(), POI_TYPES + ".class"));
		List<AbstractInsnNode> clinit = code(method(raw, "<clinit>", "()V"));
		int neoMap = indexOf(clinit, "net/neoforged/neoforge/registries/GameData", "getBlockStatePointOfInterestTypeMap");
		assertTrue(neoMap >= 0 && clinit.get(neoMap + 1) instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC
				&& put.name.equals("TYPE_BY_STATE"), "TYPE_BY_STATE is NeoForge's callback map on this base — if it is not, the "
						+ "double mapping this repair answers is gone and the repair should be re-examined (C3 checks)");

		MethodNode register = null;
		for (MethodNode m : raw.methods) {
			if (m.name.equals("register") && indexOf(code(m), POI_TYPES, "registerBlockStates") >= 0) register = m;
		}
		assertNotNull(register, "PoiTypes.register no longer calls registerBlockStates — vanilla's second step is gone");
		List<AbstractInsnNode> steps = code(register);
		int add = indexOf(steps, "net/minecraft/core/Registry", "register");
		assertTrue(add >= 0 && add < indexOf(steps, POI_TYPES, "registerBlockStates"),
				"register adds the type (the callback fires) and only then maps its states itself");

		ClassNode setup = read(TestFixtures.requireEntry(Fixture.STAGED, TestFixtures.neoCarrier(), REGISTRIES_SETUP + ".class"));
		boolean attached = false;
		for (MethodNode m : setup.methods) {
			List<AbstractInsnNode> c = code(m);
			for (int i = 0; i + 2 < c.size(); i++) {
				if (c.get(i) instanceof FieldInsnNode registry && registry.name.equals("POINT_OF_INTEREST_TYPE")
						&& c.get(i + 1) instanceof FieldInsnNode callback && callback.owner.equals(POI_CALLBACKS)
						&& c.get(i + 2) instanceof MethodInsnNode call && call.name.equals("addCallback")) attached = true;
			}
		}
		assertTrue(attached, "NeoForgeRegistriesSetup no longer attaches PoiTypeCallbacks to POINT_OF_INTEREST_TYPE — "
				+ "nothing maps a type's states on add, and the repair should be re-examined");
	}

	@Test
	void registerBlockStatesCallsTheKernelFirstAndThenRunsVanillasBody() throws Exception {
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
				&& call.owner.equals(ForbricMergedBaseCompatTransformer.KERNEL_POI_STATES)
				&& call.name.equals("beforeRegisterBlockStates")
				&& call.desc.equals(ForbricMergedBaseCompatTransformer.KERNEL_POI_STATES_DESC));

		List<AbstractInsnNode> vanilla = code(registerBlockStates(read(raw)));
		assertEquals(vanilla.size() + 4, code.size(), "four instructions added at the head, nothing else: vanilla's body runs "
				+ "for every caller, with any mixin a mod put on it");
		for (int i = 0; i < vanilla.size(); i++) assertEquals(vanilla.get(i).getOpcode(), code.get(i + 4).getOpcode());
		new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
		assertSame(once, new ForbricMergedBaseCompatTransformer().transform(POI_TYPES.replace('/', '.'), once, null), "a second pass stands down");
	}

	@Test
	void theInsertedCallNamesTheRuntimeHelpersRealMethod() throws Exception {
		java.net.URL boot = net.forbric.kernel.util.ForbricLog.class.getProtectionDomain().getCodeSource().getLocation();
		try (java.net.URLClassLoader game = net.forbric.kernel.runtime.StagedGameClassLoader.create(List.of(boot))) {
			Method helper = Class.forName(ForbricMergedBaseCompatTransformer.KERNEL_POI_STATES.replace('/', '.'), false, game)
					.getMethod("beforeRegisterBlockStates", Map.class, Object.class, Set.class);
			assertTrue(Modifier.isStatic(helper.getModifiers()) && Modifier.isPublic(helper.getModifiers()));
			assertEquals(ForbricMergedBaseCompatTransformer.KERNEL_POI_STATES_DESC, Type.getMethodDescriptor(helper));
		}
	}
}
