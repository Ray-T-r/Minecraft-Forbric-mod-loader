/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * Over the staged game: the methods that hand a fluid's NeoForge type straight to {@code Entity.canFluidExtinguish},
 * found by that shape across NeoForge's base and carrier, are exactly the ones {@code OwnFluidTypeClausesInjector}
 * declares and edits; and NeoForge's tracker lookup by type is the one place {@code EmptyFluidTypeTrackerInjector} needs,
 * called only by the walk that passes over a cell with no tracker.
 */
@ResourceLock("system-properties")
class OwnFluidTypeClausesInjectorTest {
	@AfterEach void reset() {
		System.clearProperty(OwnFluidTypeClausesInjector.PROPERTY);
		System.clearProperty(EmptyFluidTypeTrackerInjector.PROPERTY);
	}

	private static List<Path> game() {
		Path root = TestFixtures.stagedRoot();
		List<Path> jars = List.of(root.resolve(TestFixtures.BASE_JAR), root.resolve(TestFixtures.NEO_CARRIER_JAR));
		TestFixtures.requireFiles(Fixture.STAGED, "the base and its carrier", jars.toArray(Path[]::new));
		return jars;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static boolean contains(byte[] haystack, String needle) {
		return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
	}

	/**
	 * Every method of the game asking whether a fluid's own NeoForge getter's type puts out fire, by shape, is in a class the
	 * injector declares, and every declared class has one: today only {@code Fluid.entityInside}. Every other caller of
	 * {@code canFluidExtinguish} is listed too, so a new one that asks a fluid's type some other way fails here instead of
	 * going unanswered.
	 */
	@Test void theFireClausesAreTheOnesDeclared() throws Exception {
		TreeSet<String> found = new TreeSet<>(), classes = new TreeSet<>(), askers = new TreeSet<>();
		for (Path jar : game()) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (var entry : java.util.Collections.list(zip.entries())) {
					if (!entry.getName().endsWith(".class")) continue;
					byte[] bytes = zip.getInputStream(entry).readAllBytes();
					if (!contains(bytes, OwnFluidTypeClausesInjector.EXTINGUISH)) continue;
					ClassNode node = parse(bytes);
					for (MethodNode m : node.methods) {
						for (AbstractInsnNode insn : m.instructions) {
							if (insn instanceof MethodInsnNode call && call.name.equals(OwnFluidTypeClausesInjector.EXTINGUISH)
									&& call.desc.equals(OwnFluidTypeClausesInjector.EXTINGUISH_DESC)) askers.add(node.name + "#" + m.name + m.desc);
						}
						if (!OwnFluidTypeClausesInjector.clauses(m).isEmpty()) {
							found.add(node.name + "#" + m.name + m.desc);
							classes.add(node.name.replace('/', '.'));
						}
					}
				}
			}
		}
		String entityInside = "net/minecraft/world/level/material/Fluid#entityInside(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/InsideBlockEffectApplier;)V";
		assertEquals(new TreeSet<>(List.of(entityInside)), found);
		assertEquals(new TreeSet<>(OwnFluidTypeClausesInjector.KNOWN), classes, "the injector's anchors are the classes holding such a method");
		assertTrue(askers.contains(entityInside), askers.toString());
	}

	/** In each such method every fire clause's type goes through the kernel with its fluid, the result verifies, and it is made once. */
	@Test void eachFireClauseIsAnsweredWithTheFluidsOwnType() throws Exception {
		Path base = game().getFirst();
		for (String target : OwnFluidTypeClausesInjector.KNOWN) {
			byte[] original = NativeCoremodParityTest.read(base, target.replace('.', '/'));
			byte[] out = InjectorExecution.transform(new OwnFluidTypeClausesInjector(), target, original, EnvType.SERVER);
			assertNotSame(original, out, target);
			ClassNode node = parse(out), stock = parse(original);
			int edited = 0;
			for (MethodNode m : node.methods) {
				MethodNode before = stock.methods.stream().filter(o -> o.name.equals(m.name) && o.desc.equals(m.desc)).findFirst().orElseThrow();
				List<MethodInsnNode> clauses = OwnFluidTypeClausesInjector.clauses(before);
				if (clauses.isEmpty()) {
					assertEquals(before.instructions.size(), m.instructions.size(), target + "." + m.name + " has no fire clause and is not touched");
					continue;
				}
				edited++;
				int answered = 0;
				for (AbstractInsnNode insn : m.instructions) {
					if (!(insn instanceof MethodInsnNode call) || !call.name.equals("getFluidType") || !call.owner.equals(OwnFluidTypeClausesInjector.FLUID)) continue;
					assertEquals(Opcodes.DUP, previous(call).getOpcode(), target + ": the fluid is kept for the kernel");
					AbstractInsnNode own = next(call), ask = next(own);
					assertTrue(own instanceof MethodInsnNode o && o.owner.equals(OwnFluidTypeClausesInjector.RUNTIME) && o.name.equals("ownNeoType"),
							target + "." + m.name + ": " + call.desc + " then " + own);
					assertTrue(ask instanceof MethodInsnNode a && a.name.equals(OwnFluidTypeClausesInjector.EXTINGUISH), "then the fire question: " + ask);
					answered++;
				}
				assertEquals(clauses.size(), answered, target + "." + m.name);
				new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
			}
			assertEquals(1, edited, target + " has one method with a fire clause");
			assertSame(out, InjectorExecution.transform(new OwnFluidTypeClausesInjector(), target, out, EnvType.SERVER), "made once");
		}
	}

	@Test void switchedOffBothAreLeftAlone() throws Exception {
		System.setProperty(OwnFluidTypeClausesInjector.PROPERTY, "off");
		System.setProperty(EmptyFluidTypeTrackerInjector.PROPERTY, "off");
		Path merged = game().getFirst();
		byte[] fluid = NativeCoremodParityTest.read(merged, "net/minecraft/world/level/material/Fluid");
		assertSame(fluid, InjectorExecution.transform(new OwnFluidTypeClausesInjector(), "net.minecraft.world.level.material.Fluid", fluid, EnvType.SERVER));
		byte[] interaction = NativeCoremodParityTest.read(merged, EmptyFluidTypeTrackerInjector.OWNER);
		assertSame(interaction, InjectorExecution.transform(new EmptyFluidTypeTrackerInjector(), EmptyFluidTypeTrackerInjector.INTERACTION, interaction, EnvType.SERVER));
	}

	/**
	 * NeoForge's tracker lookup by type answers null for its empty type, first thing; only NeoForge's walk (and the lookup by
	 * fluid, which nothing calls) asks it, and the walk passes over a cell whose lookup gave no tracker — the premise that no
	 * native cell is treated differently.
	 */
	@Test void neoForgesTrackerLookupGivesTheEmptyTypeNoTracker() throws Exception {
		byte[] original = NativeCoremodParityTest.read(game().getFirst(), EmptyFluidTypeTrackerInjector.OWNER);
		ClassNode stock = parse(original);
		List<String> callers = new ArrayList<>();
		for (MethodNode m : stock.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && (call.name + call.desc).equals(EmptyFluidTypeTrackerInjector.LOOKUP)) {
					callers.add(m.name + m.desc);
					if (m.name.equals("update")) {
						AbstractInsnNode store = next(call), load = next(store);
						boolean skipped = false;
						for (AbstractInsnNode i = load; i != null && !skipped; i = i.getNext()) {
							if (i instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNULL) skipped = true;
						}
						assertTrue(skipped, "the walk passes over a cell with no tracker");
					}
				}
			}
		}
		assertEquals(List.of("update(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;)V",
				"getTrackerFor(Lnet/minecraft/world/level/material/Fluid;)Lnet/minecraft/world/entity/EntityFluidInteraction$Tracker;"), callers);

		byte[] out = InjectorExecution.transform(new EmptyFluidTypeTrackerInjector(), EmptyFluidTypeTrackerInjector.INTERACTION, original, EnvType.SERVER);
		assertNotSame(original, out);
		ClassNode node = parse(out);
		MethodNode lookup = node.methods.stream().filter(m -> (m.name + m.desc).equals(EmptyFluidTypeTrackerInjector.LOOKUP)).findFirst().orElseThrow();
		List<Integer> head = Arrays.stream(lookup.instructions.toArray()).filter(i -> i.getOpcode() >= 0).limit(5).map(AbstractInsnNode::getOpcode).toList();
		assertEquals(List.of(Opcodes.ALOAD, Opcodes.INVOKEVIRTUAL, Opcodes.IFEQ, Opcodes.ACONST_NULL, Opcodes.ARETURN), head);
		new Analyzer<>(new BasicVerifier()).analyze(node.name, lookup);
		assertSame(out, InjectorExecution.transform(new EmptyFluidTypeTrackerInjector(), EmptyFluidTypeTrackerInjector.INTERACTION, out, EnvType.SERVER), "made once");
	}

	private static AbstractInsnNode previous(AbstractInsnNode insn) {
		AbstractInsnNode p = insn.getPrevious();
		while (p != null && p.getOpcode() < 0) p = p.getPrevious();
		return p;
	}

	private static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode n = insn.getNext();
		while (n != null && n.getOpcode() < 0) n = n.getNext();
		return n;
	}
}
