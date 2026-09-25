/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * NeoForge's {@code ScreenEvent.Opening} and {@code Closing} are posted again when a screen changes.
 *
 * <p>The merged {@code Gui.setScreen} is MinecraftForge's: it asks {@code ForgeEventFactoryClient.onScreenOpening} and
 * {@code onScreenClose} and nothing on NeoForge's side, so Controlling, JEI, Balm and PuzzlesLib — NeoForge mods that
 * replace, refuse or track screens — were never told. Three straight-line insertions next to MinecraftForge's own hooks
 * (no branch, no frame, no new local), calling {@code KernelScreenEvents}:
 *
 * <ol>
 *   <li>right after {@code onScreenOpening}, on its result: NeoForge's Opening, whose cancel lands on the body's own
 *       "MinecraftForge refused" return;</li>
 *   <li>right after that result becomes the new screen: NeoForge's replacement, if it made one;</li>
 *   <li>right after {@code onScreenClose}: NeoForge's Closing for the same screen.</li>
 * </ol>
 *
 * <p>Applied only when the body is exactly that shape and posts neither NeoForge event itself.
 * {@code -Dforbric.neoScreenEvents=off} leaves {@code Gui} as merged.
 */
public final class NeoScreenEventsInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.neoScreenEvents";
	static final String GUI = "net.minecraft.client.gui.Gui";
	static final String SCREEN = "Lnet/minecraft/client/gui/screens/Screen;";
	static final String FORGE = "net/minecraftforge/client/event/ForgeEventFactoryClient";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelScreenEvents";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-neo-screen-events"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("NeoForge's ScreenEvent.Opening and Closing left unposted with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(GUI, AnchorSet.Severity.REQUIRED,
				"NeoForge mods that replace, refuse or track screens (Controlling, JEI, Balm) are never told a screen changed"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !GUI.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (!repair(node)) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Screens] Gui.setScreen posts NeoForge's ScreenEvent.Opening and Closing after "
				+ "MinecraftForge's — the merged body asked only MinecraftForge's");
		return writer.toByteArray();
	}

	static boolean repair(ClassNode gui) {
		MethodNode set = null;
		for (MethodNode method : gui.methods) if (method.name.equals("setScreen") && method.desc.equals("(" + SCREEN + ")V")) set = method;
		if (set == null || (set.access & Opcodes.ACC_STATIC) != 0) return false;
		MethodInsnNode opening = null, closing = null;
		int openings = 0, closings = 0;
		for (AbstractInsnNode insn : set.instructions) {
			if (insn instanceof TypeInsnNode type && type.desc.startsWith("net/neoforged/neoforge/client/event/ScreenEvent")) return false;
			if (insn instanceof LdcInsnNode) continue;
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (call.owner.equals(RUNTIME)) return false;
			if (call.owner.equals(FORGE) && call.name.equals("onScreenOpening") && call.desc.equals("(" + SCREEN + SCREEN + ")" + SCREEN)) { opening = call; openings++; }
			if (call.owner.equals(FORGE) && call.name.equals("onScreenClose") && call.desc.equals("(" + SCREEN + ")V")) { closing = call; closings++; }
		}
		if (openings != 1 || closings != 1) return false;
		// ALOAD old; ALOAD 1; onScreenOpening; ASTORE r; ALOAD r; IFNONNULL; RETURN; ALOAD r; ASTORE 1
		List<AbstractInsnNode> before = real(opening, -2), after = real(opening, 8);
		if (before.size() != 2 || after.size() != 8) return false;
		if (!(before.get(0) instanceof VarInsnNode old) || old.getOpcode() != Opcodes.ALOAD
				|| !(before.get(1) instanceof VarInsnNode next) || next.getOpcode() != Opcodes.ALOAD || next.var != 1) return false;
		if (!storedFromScreenField(set, old.var)) return false;
		if (!(after.get(0) instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE
				|| !var(after.get(1), Opcodes.ALOAD, store.var) || after.get(2).getOpcode() != Opcodes.IFNONNULL
				|| after.get(3).getOpcode() != Opcodes.RETURN || !var(after.get(4), Opcodes.ALOAD, store.var)
				|| !var(after.get(5), Opcodes.ASTORE, 1)) return false;
		// ALOAD old; onScreenClose; ALOAD old; INVOKEVIRTUAL Screen.removed()V
		List<AbstractInsnNode> beforeClose = real(closing, -1), afterClose = real(closing, 2);
		if (beforeClose.size() != 1 || !var(beforeClose.get(0), Opcodes.ALOAD, old.var) || afterClose.size() != 2
				|| !var(afterClose.get(0), Opcodes.ALOAD, old.var)
				|| !(afterClose.get(1) instanceof MethodInsnNode removed) || !removed.name.equals("removed")) return false;

		InsnList p1 = new InsnList();
		p1.add(new VarInsnNode(Opcodes.ALOAD, old.var));
		p1.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "neoForgeOpening", "(" + SCREEN + SCREEN + ")" + SCREEN, false));
		set.instructions.insert(opening, p1);
		InsnList p2 = new InsnList();
		p2.add(new VarInsnNode(Opcodes.ALOAD, 1));
		p2.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "takeNeoForgeNewScreen", "(" + SCREEN + ")" + SCREEN, false));
		p2.add(new VarInsnNode(Opcodes.ASTORE, 1));
		set.instructions.insert(after.get(5), p2);
		InsnList p3 = new InsnList();
		p3.add(new VarInsnNode(Opcodes.ALOAD, old.var));
		p3.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "postNeoForgeClosing", "(" + SCREEN + ")V", false));
		set.instructions.insert(closing, p3);
		return true;
	}

	/** The slot holds the screen being replaced: {@code aload0; getfield Gui.screen; astore slot}. */
	private static boolean storedFromScreenField(MethodNode method, int slot) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.name.equals("screen")
					&& field.desc.equals(SCREEN)) {
				List<AbstractInsnNode> next = real(field, 1);
				if (next.size() == 1 && var(next.get(0), Opcodes.ASTORE, slot)) return true;
			}
		}
		return false;
	}

	/** Up to {@code count} real instructions after (positive) or before (negative) {@code from}, nearest first for after. */
	private static List<AbstractInsnNode> real(AbstractInsnNode from, int count) {
		List<AbstractInsnNode> out = new ArrayList<>();
		AbstractInsnNode insn = from;
		while (out.size() < Math.abs(count)) {
			insn = count > 0 ? insn.getNext() : insn.getPrevious();
			if (insn == null) break;
			if (insn.getOpcode() >= 0) {
				if (count > 0) out.add(insn); else out.add(0, insn);
			}
		}
		return out;
	}

	private static boolean var(AbstractInsnNode insn, int opcode, int slot) {
		return insn instanceof VarInsnNode v && v.getOpcode() == opcode && v.var == slot;
	}
}
