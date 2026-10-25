/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.forbric.kernel.TestFixtures;
import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * The merged {@code EntityFluidInteraction} gets MinecraftForge's half of its update back, over the staged merged base.
 *
 * <p>As merged, MinecraftForge's queries ({@code getEyeFluid}, {@code isInFluid()}, …) read fields only the constructor
 * writes, so every MinecraftForge mod's entity-fluid question answered "no fluid", in vanilla water too — and NeoForge's
 * no-argument {@code isInFluidType()}, which the merged {@code Entity} answers with MinecraftForge's body, answered the
 * same. In game the probe measured both against native MinecraftForge and NeoForge (integ3/fluid/probe).
 */
@ResourceLock("system-properties")
class ForgeEntityFluidsInjectorTest {
	private static final String INTERACTION = "net/minecraft/world/entity/EntityFluidInteraction";

	private static Path merged() {
		return TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
	}

	@AfterEach void reset() { System.clearProperty(ForgeEntityFluidsInjector.PROPERTY); }

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow(() -> new AssertionError(name + desc));
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		return Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
	}

	private static List<String> runtimeCalls(MethodNode method) {
		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(ForgeEntityFluidsInjector.RUNTIME)) calls.add(call.name);
		}
		return calls;
	}

	@Test void theMergedInteractionRecordsMinecraftForgesHalfOfItsUpdate() throws Exception {
		byte[] original = NativeCoremodParityTest.read(merged(), INTERACTION);
		byte[] out = InjectorExecution.transform(new ForgeEntityFluidsInjector(), ForgeEntityFluidsInjector.INTERACTION, original, EnvType.SERVER);
		assertNotSame(original, out, "the reviewed shape is the staged merged base's");
		ClassNode node = parse(out);
		assertTrue(node.fields.stream().anyMatch(f -> f.name.equals(ForgeEntityFluidsInjector.FIELD)), "the record has a field");
		assertEquals(List.of("state"), runtimeCalls(method(node, "<init>", ForgeEntityFluidsInjector.CONSTRUCTOR_DESC)));

		MethodNode update = method(node, "update", ForgeEntityFluidsInjector.UPDATE_DESC);
		assertEquals(List.of("begin", "cell"), runtimeCalls(update), "reset once, then every cell the walk reads");
		List<AbstractInsnNode> code = real(update);
		assertTrue(code.get(3) instanceof MethodInsnNode first && first.name.equals("begin"), "MinecraftForge resets before anything else runs");
		int read = -1, cell = -1;
		for (int i = 0; i < code.size(); i++) {
			if (code.get(i) instanceof MethodInsnNode call && call.name.equals("getFluidState")) read = i;
			if (code.get(i) instanceof MethodInsnNode call && call.name.equals("cell")) cell = i;
		}
		VarInsnNode stored = (VarInsnNode) code.get(read + 1), level = (VarInsnNode) code.get(read - 2), pos = (VarInsnNode) code.get(read - 1);
		assertEquals(read + 7, cell, "right after the cell is stored: this, record, state, level, pos, call");
		assertEquals(stored.var, ((VarInsnNode) code.get(cell - 3)).var, "the cell's own fluid state");
		assertEquals(level.var, ((VarInsnNode) code.get(cell - 2)).var, "the level the walk reads");
		assertEquals(pos.var, ((VarInsnNode) code.get(cell - 1)).var, "the position the walk reads");

		for (String[] query : ForgeEntityFluidsInjector.FORGE_QUERIES) {
			MethodNode m = method(node, query[0], query[1]);
			assertEquals(List.of(query[2]), runtimeCalls(m), query[0] + query[1] + " answers from the record");
			assertTrue(real(m).stream().noneMatch(i -> i instanceof FieldInsnNode f && !f.name.equals(ForgeEntityFluidsInjector.FIELD)),
					"and no longer reads the fields nothing writes");
		}
		for (String[] query : ForgeEntityFluidsInjector.NEO_QUERIES) {
			MethodNode m = method(node, query[0], query[1]);
			long returns = real(m).stream().filter(i -> i.getOpcode() >= Opcodes.IRETURN && i.getOpcode() <= Opcodes.ARETURN).count();
			assertEquals(returns, runtimeCalls(m).size(), query[0] + query[1] + " passes every answer through");
			assertTrue(runtimeCalls(m).stream().allMatch(query[2]::equals));
		}
		for (MethodNode m : node.methods) new Analyzer<>(new BasicVerifier()).analyze(INTERACTION, m);
		assertSame(out, InjectorExecution.transform(new ForgeEntityFluidsInjector(), ForgeEntityFluidsInjector.INTERACTION, out, EnvType.SERVER), "made once");
	}

	/** The JVM's own verifier, frames included: the class is defined over the staged game. */
	@Test void theRepairedClassPassesTheJvmVerifier() throws Exception {
		byte[] out = InjectorExecution.transform(new ForgeEntityFluidsInjector(), ForgeEntityFluidsInjector.INTERACTION,
				NativeCoremodParityTest.read(merged(), INTERACTION), EnvType.SERVER);
		try (URLClassLoader game = net.forbric.kernel.runtime.StagedGameClassLoader.create(List.of())) {
			ClassLoader repaired = new ClassLoader(game) {
				@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
					if (!name.equals(ForgeEntityFluidsInjector.INTERACTION)) return super.loadClass(name, resolve);
					synchronized (getClassLoadingLock(name)) {
						Class<?> done = findLoadedClass(name);
						return done != null ? done : defineClass(name, out, 0, out.length);
					}
				}
			};
			Class<?> defined = Class.forName(ForgeEntityFluidsInjector.INTERACTION, true, repaired);   // verified, frames included
			assertSame(repaired, defined.getClassLoader());
		}
	}

	/** A merged base that kept MinecraftForge's update already writes its state; a second writer would disagree with it. */
	@Test void aBaseThatStillWritesMinecraftForgesStateIsLeftAlone() throws Exception {
		ClassNode node = parse(NativeCoremodParityTest.read(merged(), INTERACTION));
		MethodNode update = method(node, "update", ForgeEntityFluidsInjector.UPDATE_DESC);
		InsnList write = new InsnList();
		write.add(new VarInsnNode(Opcodes.ALOAD, 0));
		write.add(new InsnNode(Opcodes.ICONST_1));
		write.add(new FieldInsnNode(Opcodes.PUTFIELD, INTERACTION, "isInFluid", "Z"));
		update.instructions.insert(write);
		assertEquals(-1, ForgeEntityFluidsInjector.repair(node));
	}

	@Test void aWalkThatReadsTwoCellsPerStepIsLeftAlone() throws Exception {
		ClassNode node = parse(NativeCoremodParityTest.read(merged(), INTERACTION));
		MethodNode update = method(node, "update", ForgeEntityFluidsInjector.UPDATE_DESC);
		MethodInsnNode read = (MethodInsnNode) real(update).stream().filter(i -> i instanceof MethodInsnNode c && c.name.equals("getFluidState")).findFirst().orElseThrow();
		InsnList again = new InsnList();
		again.add(new InsnNode(Opcodes.ACONST_NULL));
		again.add(new InsnNode(Opcodes.ACONST_NULL));
		again.add(new MethodInsnNode(read.getOpcode(), read.owner, read.name, read.desc, read.itf));
		again.add(new InsnNode(Opcodes.POP));
		update.instructions.insert(again);
		assertEquals(-1, ForgeEntityFluidsInjector.repair(node), "which read is the cell is a guess");
	}

	@Test void offLeavesItAsMerged() throws Exception {
		System.setProperty(ForgeEntityFluidsInjector.PROPERTY, "off");
		byte[] original = NativeCoremodParityTest.read(merged(), INTERACTION);
		assertSame(original, InjectorExecution.transform(new ForgeEntityFluidsInjector(), ForgeEntityFluidsInjector.INTERACTION, original, EnvType.SERVER));
	}
}
