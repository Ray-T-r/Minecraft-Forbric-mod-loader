/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import net.forbric.kernel.TestFixtures;
import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * The two edits that make NeoForge's code answer a MinecraftForge mod's fluid type (the kernel's view of it) as
 * MinecraftForge does, over the staged classes they target: NeoForge's client-extension lookup, and the two water rules
 * NeoForge's entity code has and MinecraftForge's does not.
 */
@ResourceLock("system-properties")
class ForgeFluidViewInjectorsTest {
	private static final String EXTENSIONS = "net/neoforged/neoforge/client/extensions/common/IClientFluidTypeExtensions";

	@AfterEach void reset() { System.clearProperty(ForgeFluidViewExtensionsInjector.PROPERTY); }

	private static Path merged() { return TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar"); }
	private static Path neoRuntime() { return TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar"); }

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow(() -> new AssertionError(name + desc));
	}

	/** NeoForge's lookup asks the kernel first and returns a non-null answer; null falls through to NeoForge's map. */
	@Test void neoForgesExtensionLookupAsksTheKernelFirst() throws Exception {
		byte[] original = NativeCoremodParityTest.read(neoRuntime(), EXTENSIONS);
		byte[] out = InjectorExecution.transform(new ForgeFluidViewExtensionsInjector(), ForgeFluidViewExtensionsInjector.EXTENSIONS, original, EnvType.SERVER);
		assertNotSame(original, out);
		MethodNode of = method(parse(out), "of", "(L" + ForgeFluidViewExtensionsInjector.TYPE + ";)L" + EXTENSIONS + ";");
		List<Integer> head = Arrays.stream(of.instructions.toArray()).filter(i -> i.getOpcode() >= 0).limit(8).map(AbstractInsnNode::getOpcode).toList();
		assertEquals(List.of(Opcodes.ALOAD, Opcodes.INVOKESTATIC, Opcodes.DUP, Opcodes.IFNULL, Opcodes.CHECKCAST, Opcodes.ARETURN, Opcodes.POP,
				Opcodes.GETSTATIC), head, "the kernel's answer is returned; null leaves NeoForge's lookup as it was");
		assertTrue(Arrays.stream(of.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c
				&& c.owner.equals(ForgeFluidViewExtensionsInjector.RUNTIME) && c.name.equals("neoExtensions")));
		new Analyzer<>(new BasicVerifier()).analyze(EXTENSIONS, of);
		assertSame(out, InjectorExecution.transform(new ForgeFluidViewExtensionsInjector(), ForgeFluidViewExtensionsInjector.EXTENSIONS, out, EnvType.SERVER), "made once");
	}

	/** Without views there is nothing to answer: the views' own switch leaves the lookup as merged. */
	@Test void withoutViewsTheLookupIsLeftAsMerged() throws Exception {
		System.setProperty(ForgeFluidViewExtensionsInjector.PROPERTY, "off");
		byte[] original = NativeCoremodParityTest.read(neoRuntime(), EXTENSIONS);
		assertSame(original, InjectorExecution.transform(new ForgeFluidViewExtensionsInjector(), ForgeFluidViewExtensionsInjector.EXTENSIONS, original, EnvType.SERVER));
		byte[] player = NativeCoremodParityTest.read(merged(), "net/minecraft/world/entity/player/Player");
		assertSame(player, InjectorExecution.transform(new ForgeFluidWaterRulesInjector(), ForgeFluidWaterRulesInjector.PLAYER, player, EnvType.SERVER));
	}

	/**
	 * Each rule's predicate asks the kernel instead of {@code canSwimInFluidType}, and only that predicate: NeoForge's other
	 * swim questions (starting and keeping on swimming) are the view's own {@code canSwim}.
	 */
	@Test void bothWaterRulesAskTheKernelInTheirOwnPredicate() throws Exception {
		for (String[] rule : ForgeFluidWaterRulesInjector.RULES) {
			String internal = rule[0].replace('.', '/');
			byte[] original = NativeCoremodParityTest.read(merged(), internal);
			byte[] out = InjectorExecution.transform(new ForgeFluidWaterRulesInjector(), rule[0], original, EnvType.SERVER);
			assertNotSame(original, out, rule[0] + "." + rule[1] + " is the reviewed shape");
			ClassNode before = parse(original), after = parse(out);
			MethodNode lambda = predicate(after, method(after, rule[1], rule[2]));
			assertTrue(Arrays.stream(lambda.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c
					&& c.owner.equals(ForgeFluidWaterRulesInjector.RUNTIME) && c.name.equals(rule[3]) && c.desc.equals(ForgeFluidWaterRulesInjector.RULE_DESC)),
					rule[1] + "'s predicate asks " + rule[3]);
			assertEquals(swimQuestions(before) - 1, swimQuestions(after), "exactly one canSwimInFluidType call is answered by the kernel in " + rule[0]);
			new Analyzer<>(new BasicVerifier()).analyze(internal, lambda);
			assertSame(out, InjectorExecution.transform(new ForgeFluidWaterRulesInjector(), rule[0], out, EnvType.SERVER), "made once");
		}
	}

	@Test void aRuleWhosePredicateAsksTwiceIsLeftAlone() throws Exception {
		ClassNode player = parse(NativeCoremodParityTest.read(merged(), "net/minecraft/world/entity/player/Player"));
		MethodNode lambda = predicate(player, method(player, "updateIsUnderwater", "()Z"));
		MethodInsnNode ask = (MethodInsnNode) Arrays.stream(lambda.instructions.toArray())
				.filter(i -> i instanceof MethodInsnNode c && c.name.equals(ForgeFluidWaterRulesInjector.CAN_SWIM)).findFirst().orElseThrow();
		InsnList again = new InsnList();
		again.add(new VarInsnNode(Opcodes.ALOAD, 0));
		again.add(new VarInsnNode(Opcodes.ALOAD, 1));
		again.add(new MethodInsnNode(ask.getOpcode(), ask.owner, ask.name, ask.desc, ask.itf));
		again.add(new InsnNode(Opcodes.POP));
		lambda.instructions.insert(again);
		assertEquals(-1, ForgeFluidWaterRulesInjector.repair(player, "updateIsUnderwater", "()Z", "underwaterIn"));
	}

	/** The lambda the method hands its fluid predicate: the one that asks about swimming (or already the kernel). */
	private static MethodNode predicate(ClassNode node, MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof Handle h && h.getOwner().equals(node.name)) {
				MethodNode target = method(node, h.getName(), h.getDesc());
				boolean swims = Arrays.stream(target.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c
						&& (c.name.equals(ForgeFluidWaterRulesInjector.CAN_SWIM) || c.owner.equals(ForgeFluidWaterRulesInjector.RUNTIME)));
				if (swims) return target;
			}
		}
		throw new AssertionError("no swim predicate in " + method.name);
	}

	private static long swimQuestions(ClassNode node) {
		return node.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
				.filter(i -> i instanceof MethodInsnNode c && c.name.equals(ForgeFluidWaterRulesInjector.CAN_SWIM)
						&& c.desc.equals(ForgeFluidWaterRulesInjector.CAN_SWIM_DESC)).count();
	}
}
