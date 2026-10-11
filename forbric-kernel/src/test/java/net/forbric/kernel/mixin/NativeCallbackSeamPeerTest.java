/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * The extracted-call seam when a merge kept another ecosystem's own edit in front of the helper: the merged method no
 * longer reaches the helper as the source reaches its call, but exactly as that other ecosystem's native body (a peer)
 * reaches its own copy of the call — its hook re-deriving the operand first. The fixture is invented: a lamp whose final
 * {@code Bulb.shine(x)} the merge moved into a static hook, handed the level and x; the peer lamp first asks {@code Dimmer.adjust(bulb, x)} for
 * a new level, in a local slot its compiler reused, where the merged lamp keeps that local in a fresh slot.
 */
class NativeCallbackSeamPeerTest {
	private static final String LAMP = "brisk/Lamp", BULB = "brisk/Bulb", HOOKS = "brisk/LampHooks", GLOW = "(Lbrisk/Bulb;F)V";
	private static final String SHINE = "L" + BULB + ";shine(F)V", GLOW_ALL = "(Lbrisk/Bulb;FF)V";

	@Test
	void theMergedPrefixMayBeAPeersOwnWayToTheCall() {
		ClassNode source = lamp(false, -1, 2, false), peer = lamp(true, 4, 3, false), current = lamp(true, 5, 3, true);
		assertNull(derive(source, List.of(), current), "the source alone: the dimmer in front of the helper is no part of its prefix");
		NativeCallbackSeam.Plan plan = derive(source, List.of(peer), current);
		assertNotNull(plan, "the peer's own prefix, its reused slot a fresh one in the merged lamp");
		assertEquals(SHINE, plan.member());
		assertEquals(HOOKS, plan.helper());
		assertEquals(plan.sourcePrefix(), plan.currentPrefix());
		// The source's own prefix still matches by itself, with or without peers.
		ClassNode plain = lamp(false, -1, 2, true);
		assertNotNull(derive(source, List.of(), plain));
		assertNotNull(derive(source, List.of(peer), plain));
	}

	@Test
	void aPeerProvesOnlyItsOwnExactWayToTheSameCallFromTheSameWires() {
		ClassNode source = lamp(false, -1, 2, false), current = lamp(true, 5, 3, true);
		// The peer's dimmer is asked about the raw level, not the doubled one: another computation.
		ClassNode otherHook = lamp(true, 4, 2, false);
		assertNull(derive(source, List.of(otherHook), current));
		// The peer makes the call from another wire (the raw level): not the same call.
		ClassNode otherWire = lamp(true, 4, 3, false);
		MethodNode glow = NativeCallChanges.method(otherWire, "glow" + GLOW);
		for (AbstractInsnNode i : glow.instructions)
			if (i instanceof MethodInsnNode call && call.name.equals("shine")) ((VarInsnNode) call.getPrevious()).var = 2;
		assertNull(derive(source, List.of(otherWire), current));
		// Renaming is all it forgives, either way round: a peer keeping the answer apart proves a merged lamp reusing the slot.
		assertNotNull(derive(source, List.of(lamp(true, 6, 3, false)), lamp(true, 4, 3, true)));
		// A peer that is no lamp of this method, or no peer at all, proves nothing.
		ClassNode stranger = node("brisk/Torch");
		assertNull(derive(source, java.util.Arrays.asList(stranger, null), current));
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static NativeCallbackSeam.Plan derive(ClassNode source, List<ClassNode> peers, ClassNode current) {
		Map<String, ClassNode> classes = Map.of(HOOKS, hooks());
		return NativeCallbackSeam.derive(source, peers, current, NativeCallChanges.method(source, "glow" + GLOW), SHINE, classes::get);
	}

	/**
	 * {@code t = level; x = t * 2;} then, with {@code dimmed}, {@code d = Dimmer.adjust(bulb, <slot asked>); x = d.level();}
	 * keeping {@code d} in {@code answer}; then {@code bulb.shine(x)}, or, {@code merged}, {@code LampHooks.glowAll(bulb, level, x)}.
	 * The temporary {@code t} is slot 4, so an {@code answer} of 4 reuses it.
	 */
	private static ClassNode lamp(boolean dimmed, int answer, int asked, boolean merged) {
		ClassNode node = node(LAMP);
		MethodNode glow = new MethodNode(Opcodes.ACC_PUBLIC, "glow", GLOW, null, null);
		List<AbstractInsnNode> code = new ArrayList<>(List.of(new VarInsnNode(Opcodes.FLOAD, 2), new VarInsnNode(Opcodes.FSTORE, 4),
				new VarInsnNode(Opcodes.FLOAD, 4), new InsnNode(Opcodes.FCONST_2), new InsnNode(Opcodes.FMUL), new VarInsnNode(Opcodes.FSTORE, 3)));
		if (dimmed) code.addAll(List.of(new VarInsnNode(Opcodes.ALOAD, 1), new VarInsnNode(Opcodes.FLOAD, asked),
				new MethodInsnNode(Opcodes.INVOKESTATIC, "brisk/Dimmer", "adjust", "(Lbrisk/Bulb;F)Lbrisk/Dimmer;", false),
				new VarInsnNode(Opcodes.ASTORE, answer), new VarInsnNode(Opcodes.ALOAD, answer),
				new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "brisk/Dimmer", "level", "()F", false), new VarInsnNode(Opcodes.FSTORE, 3)));
		if (merged) code.addAll(List.of(new VarInsnNode(Opcodes.ALOAD, 1), new VarInsnNode(Opcodes.FLOAD, 2), new VarInsnNode(Opcodes.FLOAD, 3),
				new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "glowAll", GLOW_ALL, false), new InsnNode(Opcodes.RETURN)));
		else code.addAll(List.of(new VarInsnNode(Opcodes.ALOAD, 1), new VarInsnNode(Opcodes.FLOAD, 3),
				new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BULB, "shine", "(F)V", false), new InsnNode(Opcodes.RETURN)));
		for (AbstractInsnNode instruction : code) glow.instructions.add(instruction);
		glow.maxStack = 3;
		glow.maxLocals = 8;
		node.methods.add(glow);
		return node;
	}

	/** The hook: the bulb shines with x first and unconditionally, then the hook's own work. */
	private static ClassNode hooks() {
		ClassNode node = node(HOOKS);
		MethodNode glowAll = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "glowAll", GLOW_ALL, null, null);
		for (AbstractInsnNode instruction : List.of(new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.FLOAD, 2),
				new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BULB, "shine", "(F)V", false),
				new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "flicker", "()V", false), new InsnNode(Opcodes.RETURN)))
			glowAll.instructions.add(instruction);
		glowAll.maxStack = 2;
		glowAll.maxLocals = 3;
		node.methods.add(glowAll);
		return node;
	}

	private static ClassNode node(String name) {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
		return node;
	}
}
