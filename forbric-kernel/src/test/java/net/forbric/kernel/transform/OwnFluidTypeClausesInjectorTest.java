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
 * Over the staged game: the merged methods that ask one fluid both families' type, found by that shape across the merged
 * base and both carriers, are exactly the ones {@code OwnFluidTypeClausesInjector} declares and edits; and NeoForge's
 * tracker lookup by type is the one place {@code EmptyFluidTypeTrackerInjector} needs, called only by the walk that passes
 * over a cell with no tracker.
 */
@ResourceLock("system-properties")
class OwnFluidTypeClausesInjectorTest {
	@AfterEach void reset() {
		System.clearProperty(OwnFluidTypeClausesInjector.PROPERTY);
		System.clearProperty(EmptyFluidTypeTrackerInjector.PROPERTY);
	}

	private static List<Path> game() {
		Path root = TestFixtures.stagedRoot();
		List<Path> jars = List.of(root.resolve("merged-base/patched-mc-merged-26.2.jar"), root.resolve("merged-base/forge-runtime-interop.jar"),
				root.resolve("neoforge-runtime/neoforge-runtime.jar"));
		TestFixtures.requireFiles(Fixture.STAGED, "the merged base and its carriers", jars.toArray(Path[]::new));
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
	 * Every method of the game asking a fluid both families' type, by shape, is in a class the injector declares, and every
	 * declared class has one: today {@code Fluid.entityInside} and {@code BucketItem.playEmptySound}. A merged base that
	 * grows another such method still gets the edit (the injector looks for the shape), and fails here until it is named.
	 */
	@Test void theMethodsAskingBothFamiliesAreTheOnesDeclared() throws Exception {
		TreeSet<String> found = new TreeSet<>(), classes = new TreeSet<>();
		for (Path jar : game()) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (var entry : java.util.Collections.list(zip.entries())) {
					if (!entry.getName().endsWith(".class")) continue;
					byte[] bytes = zip.getInputStream(entry).readAllBytes();
					if (!contains(bytes, OwnFluidTypeClausesInjector.FORGE_TYPE) || !contains(bytes, OwnFluidTypeClausesInjector.NEO_TYPE)) continue;
					ClassNode node = parse(bytes);
					for (MethodNode m : node.methods) {
						if (!OwnFluidTypeClausesInjector.getters(m, OwnFluidTypeClausesInjector.FORGE_TYPE).isEmpty()
								&& !OwnFluidTypeClausesInjector.getters(m, OwnFluidTypeClausesInjector.NEO_TYPE).isEmpty()) {
							found.add(node.name + "#" + m.name + m.desc);
							classes.add(node.name.replace('/', '.'));
						}
					}
				}
			}
		}
		assertEquals(new TreeSet<>(List.of(
				"net/minecraft/world/item/BucketItem#playEmptySound(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;)V",
				"net/minecraft/world/level/material/Fluid#entityInside(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/InsideBlockEffectApplier;)V")),
				found);
		assertEquals(new TreeSet<>(OwnFluidTypeClausesInjector.KNOWN), classes, "the injector's anchors are the classes holding such a method");
	}

	/** In each such method every getter's answer goes through the kernel with its fluid, the result verifies, and it is made once. */
	@Test void eachGetterInTheDeclaredMethodsIsAnsweredWithTheFamilysOwnType() throws Exception {
		Path merged = game().getFirst();
		for (String target : OwnFluidTypeClausesInjector.KNOWN) {
			byte[] original = NativeCoremodParityTest.read(merged, target.replace('.', '/'));
			byte[] out = InjectorExecution.transform(new OwnFluidTypeClausesInjector(), target, original, EnvType.SERVER);
			assertNotSame(original, out, target);
			ClassNode node = parse(out);
			int edited = 0;
			for (MethodNode m : node.methods) {
				List<MethodInsnNode> forge = OwnFluidTypeClausesInjector.getters(m, OwnFluidTypeClausesInjector.FORGE_TYPE);
				List<MethodInsnNode> neo = OwnFluidTypeClausesInjector.getters(m, OwnFluidTypeClausesInjector.NEO_TYPE);
				if (forge.isEmpty() || neo.isEmpty()) continue;
				edited++;
				List<MethodInsnNode> all = new ArrayList<>(forge);
				all.addAll(neo);
				for (MethodInsnNode call : all) {
					boolean isForge = forge.contains(call);
					assertEquals(Opcodes.DUP, previous(call).getOpcode(), target + ": the fluid is kept for the kernel");
					AbstractInsnNode next = next(call);
					assertTrue(next instanceof MethodInsnNode own && own.owner.equals(OwnFluidTypeClausesInjector.RUNTIME)
							&& own.name.equals(isForge ? "ownForgeType" : "ownNeoType"), target + "." + m.name + ": " + call.desc + " then " + next);
				}
				new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
			}
			assertEquals(1, edited, target + " has one method asking both families");
			assertSame(out, InjectorExecution.transform(new OwnFluidTypeClausesInjector(), target, out, EnvType.SERVER), "made once");
		}
	}

	@Test void switchedOffBothAreLeftAsMerged() throws Exception {
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
