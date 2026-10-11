/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The merged actuallyHurt reads its damage before armour again, ahead of MinecraftForge's Hurt seam — on the real
 * LivingEntity and Player, with the seams applied first as the chain applies them.
 */
@ResourceLock("system-properties")
class VanillaDamageReadInjectorTest {
	private static final Path MERGED = TestFixtures.stagedRoot()
			.resolve("merged-base/patched-mc-merged-26.2.jar");

	@AfterEach void reset() { System.clearProperty(VanillaDamageReadInjector.PROPERTY); }

	/**
	 * MinecraftForge's Hurt sits in this body either as the kernel's seam or, on a merge that keeps both sides' edits,
	 * as Forge's own {@code ForgeHooks.onLivingHurt} right after the invulnerability check. Either way the vanilla read
	 * must come first, and the first {@code fload 3} — the one a mod's injector binds to — must be the read's.
	 */
	@Test void livingEntityReadsBeforeArmourAndBeforeTheForgeSeam() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED), "merged base not staged: " + MERGED);
		byte[] original = NativeCoremodParityTest.read(MERGED, "net/minecraft/world/entity/LivingEntity");
		byte[] seamed = new ForgeDamageSeamsInjector().transform("net.minecraft.world.entity.LivingEntity", original, null);
		assertTrue(forgeHurt(code(hurt(seamed))) >= 0, "premise: MinecraftForge's Hurt is in actuallyHurt, natively or by the seam");
		byte[] out = new VanillaDamageReadInjector().transform("net.minecraft.world.entity.LivingEntity", seamed, null);
		assertNotSame(seamed, out, "NeoForge's body armours the container: the read must be placed");
		MethodNode hurt = hurt(out);
		List<AbstractInsnNode> code = code(hurt);
		int firstRead = firstIndex(code, i -> i instanceof VarInsnNode v && v.getOpcode() == Opcodes.FLOAD && v.var == 3);
		int armour = firstIndex(code, i -> i instanceof MethodInsnNode c && c.name.equals("getDamageAfterArmorAbsorb"));
		int read = firstIndex(code, i -> i instanceof MethodInsnNode c && c.name.equals("vanillaRead"));
		int seam = forgeHurt(code);
		assertTrue(firstRead >= 0 && firstRead < armour, "first read " + firstRead + " vs armour " + armour);
		assertTrue(read > firstRead && read < seam, "vanillaRead " + read + " must come before MinecraftForge's Hurt " + seam);
		assertTrue(code.get(read - 2) == code.get(firstRead), "the first fload 3 is the read's own argument");
		assertEquals(Opcodes.IFNE, code.get(4).getOpcode());
		assertTrue(code.get(5) instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0, "placed right after the invulnerability check");
		new Analyzer<>(new BasicVerifier()).analyze("net/minecraft/world/entity/LivingEntity", hurt);
		assertSame(out, new VanillaDamageReadInjector().transform("net.minecraft.world.entity.LivingEntity", out, null), "a second pass adds nothing");
		System.setProperty(VanillaDamageReadInjector.PROPERTY, "off");
		assertSame(seamed, new VanillaDamageReadInjector().transform("net.minecraft.world.entity.LivingEntity", seamed, null));
	}

	@Test void playerOverridesTheSameWayAndGetsTheSameRead() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED), "merged base not staged: " + MERGED);
		byte[] original = NativeCoremodParityTest.read(MERGED, "net/minecraft/world/entity/player/Player");
		byte[] out = new VanillaDamageReadInjector().transform("net.minecraft.world.entity.player.Player", original, null);
		assertNotSame(original, out);
		MethodNode hurt = hurt(out);
		List<AbstractInsnNode> code = code(hurt);
		assertTrue(firstIndex(code, i -> i instanceof VarInsnNode v && v.getOpcode() == Opcodes.FLOAD && v.var == 3)
				< firstIndex(code, i -> i instanceof MethodInsnNode c && c.name.equals("getDamageAfterArmorAbsorb")));
		new Analyzer<>(new BasicVerifier()).analyze("net/minecraft/world/entity/player/Player", hurt);
		assertSame(original, new VanillaDamageReadInjector().transform("net.minecraft.world.entity.monster.Zombie", original, null));
	}

	/**
	 * What decides it is what the armour call takes, not whether the parameter is read before it: vanilla's flow
	 * (armour takes {@code fload 3}) needs nothing; a container-armouring body does, also when MinecraftForge's own
	 * Hurt hook reads the parameter first (the edit-splicing merge's shape), and the read then lands ahead of it.
	 */
	@Test void theArmourCallsArgumentDecidesNotAnEarlierRead() {
		assertFalse(VanillaDamageReadInjector.repair(body(false, false)), "vanilla's flow: armour takes the parameter");
		assertFalse(VanillaDamageReadInjector.repair(body(false, true)), "MinecraftForge's flow: the hook's result is armoured");
		MethodNode neo = body(true, false);
		assertTrue(VanillaDamageReadInjector.repair(neo), "NeoForge's flow: armour takes the container's amount");
		MethodNode spliced = body(true, true);
		assertTrue(VanillaDamageReadInjector.repair(spliced), "a pre-armour read that feeds only Forge's hook is not vanilla's");
		List<AbstractInsnNode> code = code(spliced);
		assertTrue(firstIndex(code, i -> i instanceof MethodInsnNode c && c.name.equals("vanillaRead"))
				< firstIndex(code, i -> i instanceof MethodInsnNode c && c.name.equals("onLivingHurt")), "the read precedes Forge's Hurt");
		assertFalse(VanillaDamageReadInjector.repair(spliced), "a second pass adds nothing");
	}

	/** {@code actuallyHurt}'s opening: the invulnerability check, optionally Forge's Hurt, then the armour call. */
	private static MethodNode body(boolean containerArmour, boolean forgeHurt) {
		String living = VanillaDamageReadInjector.LIVING, source = "Lnet/minecraft/world/damagesource/DamageSource;";
		MethodNode m = new MethodNode(Opcodes.ACC_PROTECTED, "actuallyHurt", VanillaDamageReadInjector.HURT_DESC, null, null);
		org.objectweb.asm.tree.LabelNode end = new org.objectweb.asm.tree.LabelNode();
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, living, "isInvulnerableTo", "(Lnet/minecraft/server/level/ServerLevel;" + source + ")Z", false));
		m.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFNE, end));
		if (forgeHurt) {
			m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
			m.instructions.add(new VarInsnNode(Opcodes.FLOAD, 3));
			m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraftforge/common/ForgeHooks", "onLivingHurt", "(L" + living + ";" + source + "F)F", false));
			m.instructions.add(new VarInsnNode(Opcodes.FSTORE, 3));
		}
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, living, "damageContainers", "Ljava/util/Stack;"));
		m.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		if (containerArmour) {
			m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			m.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, living, "damageContainers", "Ljava/util/Stack;"));
			m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Stack", "peek", "()Ljava/lang/Object;", false));
			m.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, ForgeDamageSeamsInjector.CONTAINER));
			m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ForgeDamageSeamsInjector.CONTAINER, "getNewDamage", "()F", false));
		} else {
			m.instructions.add(new VarInsnNode(Opcodes.FLOAD, 3));
		}
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, living, "getDamageAfterArmorAbsorb", "(" + source + "F)F", false));
		m.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
		m.instructions.add(end);
		m.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
		return m;
	}

	/** MinecraftForge's Hurt: the kernel's seam, or the merged body's own ForgeHooks.onLivingHurt. */
	private static int forgeHurt(List<AbstractInsnNode> code) {
		return firstIndex(code, i -> i instanceof MethodInsnNode c && (c.owner.equals(ForgeDamageSeamsInjector.RUNTIME) && c.name.equals("hurt")
				|| c.owner.equals("net/minecraftforge/common/ForgeHooks") && c.name.equals("onLivingHurt")));
	}

	private static MethodNode hurt(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node.methods.stream().filter(m -> m.name.equals("actuallyHurt") && m.desc.equals(VanillaDamageReadInjector.HURT_DESC))
				.findFirst().orElseThrow();
	}

	private static List<AbstractInsnNode> code(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) out.add(insn);
		return out;
	}

	private static int firstIndex(List<AbstractInsnNode> code, java.util.function.Predicate<AbstractInsnNode> test) {
		for (int i = 0; i < code.size(); i++) if (test.test(code.get(i))) return i;
		return -1;
	}
}
