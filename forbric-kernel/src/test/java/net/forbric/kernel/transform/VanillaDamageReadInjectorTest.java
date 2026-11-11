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
 * NeoForge's actuallyHurt reads its damage parameter before armour again, as vanilla's does — on the real LivingEntity
 * and Player of the staged base, and on synthetic bodies for the rule that decides it.
 */
@ResourceLock("system-properties")
class VanillaDamageReadInjectorTest {
	private static final Path MERGED = TestFixtures.stagedRoot()
			.resolve(TestFixtures.BASE_JAR);

	@AfterEach void reset() { System.clearProperty(VanillaDamageReadInjector.PROPERTY); }

	/**
	 * The vanilla read is placed right after the invulnerability check, ahead of armour, and the first {@code fload 3} —
	 * the one a mod's injector binds to — is the read's. (MinecraftForge's Hurt seam, which the read used to be placed
	 * ahead of, went with MinecraftForge.)
	 */
	@Test void livingEntityReadsBeforeArmour() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, MERGED, "base not staged: " + MERGED);
		byte[] original = NativeCoremodParityTest.read(MERGED, "net/minecraft/world/entity/LivingEntity");
		byte[] seamed = original;
		byte[] out = new VanillaDamageReadInjector().transform("net.minecraft.world.entity.LivingEntity", seamed, null);
		assertNotSame(seamed, out, "NeoForge's body armours the container: the read must be placed");
		MethodNode hurt = hurt(out);
		List<AbstractInsnNode> code = code(hurt);
		int firstRead = firstIndex(code, i -> i instanceof VarInsnNode v && v.getOpcode() == Opcodes.FLOAD && v.var == 3);
		int armour = firstIndex(code, i -> i instanceof MethodInsnNode c && c.name.equals("getDamageAfterArmorAbsorb"));
		int read = firstIndex(code, i -> i instanceof MethodInsnNode c && c.name.equals("vanillaRead"));
		assertTrue(firstRead >= 0 && firstRead < armour, "first read " + firstRead + " vs armour " + armour);
		assertTrue(read > firstRead && read < armour, "vanillaRead " + read + " must come before armour " + armour);
		assertTrue(code.get(read - 2) == code.get(firstRead), "the first fload 3 is the read's own argument");
		assertEquals(Opcodes.IFNE, code.get(4).getOpcode());
		assertTrue(code.get(5) instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0, "placed right after the invulnerability check");
		new Analyzer<>(new BasicVerifier()).analyze("net/minecraft/world/entity/LivingEntity", hurt);
		assertSame(out, new VanillaDamageReadInjector().transform("net.minecraft.world.entity.LivingEntity", out, null), "a second pass adds nothing");
		System.setProperty(VanillaDamageReadInjector.PROPERTY, "off");
		assertSame(seamed, new VanillaDamageReadInjector().transform("net.minecraft.world.entity.LivingEntity", seamed, null));
	}

	@Test void playerOverridesTheSameWayAndGetsTheSameRead() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, MERGED, "base not staged: " + MERGED);
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
	 * (armour takes {@code fload 3}) needs nothing, also when an earlier hook rewrites the parameter first; a
	 * container-armouring body does, also when an earlier hook reads the parameter first (the shape the old merged base
	 * had, with MinecraftForge's Hurt hook there), and the read then lands ahead of that hook.
	 */
	@Test void theArmourCallsArgumentDecidesNotAnEarlierRead() {
		assertFalse(VanillaDamageReadInjector.repair(body(false, false)), "vanilla's flow: armour takes the parameter");
		assertFalse(VanillaDamageReadInjector.repair(body(false, true)), "an earlier hook's result is what gets armoured");
		MethodNode neo = body(true, false);
		assertTrue(VanillaDamageReadInjector.repair(neo), "NeoForge's flow: armour takes the container's amount");
		MethodNode hooked = body(true, true);
		assertTrue(VanillaDamageReadInjector.repair(hooked), "a pre-armour read that feeds only another hook is not vanilla's");
		List<AbstractInsnNode> code = code(hooked);
		assertTrue(firstIndex(code, i -> i instanceof MethodInsnNode c && c.name.equals("vanillaRead"))
				< firstIndex(code, i -> i instanceof MethodInsnNode c && c.name.equals(EARLIER_HOOK)), "the read precedes the earlier hook");
		assertFalse(VanillaDamageReadInjector.repair(hooked), "a second pass adds nothing");
	}

	/** A static hook ahead of armour that takes the damage and answers a new amount; no ecosystem's, any would do. */
	private static final String EARLIER_HOOK_OWNER = "org/example/damage/EarlierHooks", EARLIER_HOOK = "onHurt";

	/** {@code actuallyHurt}'s opening: the invulnerability check, optionally an earlier hook, then the armour call. */
	private static MethodNode body(boolean containerArmour, boolean earlierHook) {
		String living = VanillaDamageReadInjector.LIVING, source = "Lnet/minecraft/world/damagesource/DamageSource;";
		MethodNode m = new MethodNode(Opcodes.ACC_PROTECTED, "actuallyHurt", VanillaDamageReadInjector.HURT_DESC, null, null);
		org.objectweb.asm.tree.LabelNode end = new org.objectweb.asm.tree.LabelNode();
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, living, "isInvulnerableTo", "(Lnet/minecraft/server/level/ServerLevel;" + source + ")Z", false));
		m.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFNE, end));
		if (earlierHook) {
			m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
			m.instructions.add(new VarInsnNode(Opcodes.FLOAD, 3));
			m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EARLIER_HOOK_OWNER, EARLIER_HOOK, "(L" + living + ";" + source + "F)F", false));
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
			m.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, VanillaDamageReadInjector.CONTAINER));
			m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VanillaDamageReadInjector.CONTAINER, "getNewDamage", "()F", false));
		} else {
			m.instructions.add(new VarInsnNode(Opcodes.FLOAD, 3));
		}
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, living, "getDamageAfterArmorAbsorb", "(" + source + "F)F", false));
		m.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
		m.instructions.add(end);
		m.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
		return m;
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
