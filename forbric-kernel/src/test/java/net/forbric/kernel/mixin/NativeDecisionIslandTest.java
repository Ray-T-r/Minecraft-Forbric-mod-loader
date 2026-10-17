/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * What a native decision island refuses, on methods assembled here with names that belong to nothing: a wanted call the native
 * stretch makes only after it changed state, a merged stretch that jumps out of itself, and a body that differs in no single
 * stretch. And what it holds when it may: the decisions up to a statement boundary, with only the static call the carrier asks
 * again relayed.
 */
class NativeDecisionIslandTest {
	private static final String OWNER = "org/quarry/Pick", ASK = "Lorg/quarry/Lore;ask(Lorg/quarry/Pick;)Z";

	private static ClassNode owner() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.name = OWNER;
		node.superName = "java/lang/Object";
		return node;
	}

	private static MethodNode method(String name) {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "()V", null, null);
		method.maxLocals = 2;
		method.maxStack = 4;
		return method;
	}

	/** Natively: {@code if (this.ready() && Lore.ask(this)) this.swing(); this.done();} */
	private static MethodNode nativeDecisions() {
		MethodNode m = method("strike");
		LabelNode skip = new LabelNode();
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "ready", "()Z", false));
		m.instructions.add(new JumpInsnNode(Opcodes.IFEQ, skip));
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/quarry/Lore", "ask", "(Lorg/quarry/Pick;)Z", false));
		m.instructions.add(new JumpInsnNode(Opcodes.IFEQ, skip));
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "swing", "()V", false));
		m.instructions.add(skip);
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "done", "()V", false));
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		return m;
	}

	/** As merged: {@code Mine.strike(this); this.done();} — the carrier's hook asks {@code Lore.ask} itself. */
	private static MethodNode mergedHook() {
		MethodNode m = method("strike");
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/quarry/Mine", "strike", "(Lorg/quarry/Pick;)V", false));
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "done", "()V", false));
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		return m;
	}

	private static ClassNode mine() {
		ClassNode node = new ClassNode();
		node.name = "org/quarry/Mine";
		MethodNode strike = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "strike", "(Lorg/quarry/Pick;)V", null, null);
		strike.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		strike.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/quarry/Lore", "ask", "(Lorg/quarry/Pick;)Z", false));
		strike.instructions.add(new InsnNode(Opcodes.POP));
		strike.instructions.add(new InsnNode(Opcodes.RETURN));
		node.methods.add(strike);
		return node;
	}

	@Test void theDecisionsComeBackWithTheCarriersSharedStaticCallRelayed() {
		NativeDecisionIsland.Outcome outcome = NativeDecisionIsland.derive(owner(), mergedHook(), owner(), nativeDecisions(), Set.of(ASK),
				name -> name.equals("org/quarry/Mine") ? mine() : null);
		assertNotNull(outcome.island(), String.valueOf(outcome.declined()));
		assertEquals(Set.of("L" + OWNER + ";ready()Z", ASK), outcome.island().members(), "the swing, which changes state, is not copied");
		assertEquals(Map.of(4, new NativeDecisionIsland.Relay("org/quarry/Lore", "ask", "(Lorg/quarry/Pick;)Z")), outcome.island().relays());
	}

	@Test void aCallTheCarrierDoesNotAskAgainIsNotRelayed() {
		NativeDecisionIsland.Outcome outcome = NativeDecisionIsland.derive(owner(), mergedHook(), owner(), nativeDecisions(), Set.of(ASK), name -> null);
		assertNotNull(outcome.island(), String.valueOf(outcome.declined()));
		assertTrue(outcome.island().relays().isEmpty(), "no carrier method is at hand that asks it");
	}

	@Test void aWantedCallAfterStateChangesIsRefused() {
		MethodNode was = method("strike");
		LabelNode skip = new LabelNode();
		was.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		was.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "swing", "()V", false));
		was.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		was.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/quarry/Lore", "ask", "(Lorg/quarry/Pick;)Z", false));
		was.instructions.add(new JumpInsnNode(Opcodes.IFEQ, skip));
		was.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		was.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "swing", "()V", false));
		was.instructions.add(skip);
		was.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		was.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "done", "()V", false));
		was.instructions.add(new InsnNode(Opcodes.RETURN));
		NativeDecisionIsland.Outcome outcome = NativeDecisionIsland.derive(owner(), mergedHook(), owner(), was, Set.of(ASK), name -> mine());
		assertNull(outcome.island());
		assertTrue(outcome.declined().contains("changes state"), outcome.declined());
	}

	@Test void aMergedStretchThatLeavesBeforeItsEndIsRefused() {
		MethodNode host = method("strike");
		LabelNode out = new LabelNode();
		host.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		host.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/quarry/Mine", "strike", "(Lorg/quarry/Pick;)V", false));
		host.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		host.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "stuck", "()Z", false));
		host.instructions.add(new JumpInsnNode(Opcodes.IFNE, out));
		host.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		host.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "done", "()V", false));
		host.instructions.add(out);
		host.instructions.add(new InsnNode(Opcodes.RETURN));
		NativeDecisionIsland.Outcome outcome = NativeDecisionIsland.derive(owner(), host, owner(), nativeDecisions(), Set.of(ASK), name -> mine());
		assertNull(outcome.island());
		assertNotNull(outcome.declined());
	}

	@Test void nothingMissingIsNoIsland() {
		NativeDecisionIsland.Outcome outcome = NativeDecisionIsland.derive(owner(), nativeDecisions(), owner(), nativeDecisions(), Set.of(ASK), name -> null);
		assertNull(outcome.island());
	}
}
