/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.kernel.boot.KernelLifecycle;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * NeoForge's tooltip registration event goes to each mod on its own.
 *
 * <p>{@code ItemTooltipHandler.init} hands its {@code RegisterTooltipAppendersEvent} to {@code ModLoader.postEvent},
 * which rethrows the first listener failure — one mod registering an appender twice cost every mod after it its
 * tooltip lines — and posts nothing at all once any mod has failed to load. The one call goes to
 * {@code KernelNeoTooltips.postRegisterAppenders}, the kernel's per-container delivery; the event is the one
 * {@code init} just built, so nothing else in the method moves. Applied only when {@code init} posts exactly one event
 * and it is that one. {@code -Dforbric.neoTooltipAppenders=off} leaves the class as shipped (and the kernel does not
 * build the appenders at all).
 *
 * <p>Fabric's component tooltip providers ({@code ItemComponentTooltipProviderRegistry}) are placed first, last, and
 * before or after a vanilla component's lines. Each vanilla component appender goes into NeoForge's middle list
 * through {@code KernelNeoTooltips.around}, which draws Fabric's before and after lines around it: one more
 * instruction pair between the appender's lookup and the {@code List.add}. With {@code -Dforbric.fabricTooltipBridge=off}
 * only the event's delivery changes.
 */
public final class NeoTooltipAppendersInjector implements ClassTransformer {
	static final String HANDLER = "net.neoforged.neoforge.common.tooltip.ItemTooltipHandler";
	static final String EVENT = "net/neoforged/neoforge/event/RegisterTooltipAppendersEvent";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelNeoTooltips";
	static final String APPENDER = "net/neoforged/neoforge/common/tooltip/TooltipAppender";
	static final String AROUND_DESC = "(L" + APPENDER + ";Lnet/minecraft/core/component/DataComponentType;)L" + APPENDER + ";";
	private static volatile boolean around;

	/** Whether the defined ItemTooltipHandler hands each component appender to KernelNeoTooltips.around. */
	public static boolean aroundSpliced() {
		return around;
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(KernelLifecycle.NEO_TOOLTIP_APPENDERS, "on"));
	}

	@Override public String name() { return "forbric-neo-tooltip-appenders"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("NeoForge's tooltip appenders left unbuilt with -D" + KernelLifecycle.NEO_TOOLTIP_APPENDERS + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(HANDLER, AnchorSet.Severity.REQUIRED,
				"one mod's failing tooltip registration costs every later mod its tooltip lines"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !HANDLER.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		boolean delivered = repair(node);
		// Fabric's first and last are registered in the delivery, before/after in the splice: both or neither, so the
		// bridge never draws half of what the pruned injectors drew.
		boolean delivers = delivered || calls(node, "init", "postRegisterAppenders");
		boolean spliced = delivers && GuestInjectorPruner.fabricTooltipBridgeOn() && wrapComponentAppenders(node);
		if (spliced) around = true;
		if (!delivered && !spliced) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		if (delivered) ForbricLog.info("[Forbric/Tooltips] ItemTooltipHandler.init posts its RegisterTooltipAppendersEvent to "
				+ "each mod on its own — ModLoader.postEvent stopped at the first mod that threw");
		if (spliced) ForbricLog.info("[Forbric/Tooltips] ItemTooltipHandler hands each component appender to "
				+ "KernelNeoTooltips.around — Fabric's before/after tooltip providers draw around vanilla's lines");
		return writer.toByteArray();
	}

	/**
	 * In {@code addDataComponentAppenders}: {@code MIDDLE_APPENDERS.add((TooltipAppender) appenders.get(type))} becomes
	 * {@code MIDDLE_APPENDERS.add(KernelNeoTooltips.around((TooltipAppender) appenders.get(type), type))}. Only when that
	 * is the method's one {@code add} and the key is a plain local.
	 */
	static boolean wrapComponentAppenders(ClassNode handler) {
		for (MethodNode method : handler.methods) {
			if (!method.name.equals("addDataComponentAppenders")) continue;
			TypeInsnNode cast = null;
			int adds = 0;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(RUNTIME)) { around = true; return false; }
				if (insn instanceof MethodInsnNode call && call.name.equals("add") && call.owner.equals("java/util/List")) {
					adds++;
					AbstractInsnNode before = real(call.getPrevious());
					if (before instanceof TypeInsnNode type && type.getOpcode() == Opcodes.CHECKCAST && type.desc.equals(APPENDER)) cast = type;
				}
			}
			if (adds != 1 || cast == null) return false;
			// ... GETSTATIC MIDDLE_APPENDERS; ALOAD map; ALOAD key; INVOKEINTERFACE SequencedMap.get; CHECKCAST
			AbstractInsnNode get = real(cast.getPrevious());
			AbstractInsnNode key = get == null ? null : real(get.getPrevious());
			AbstractInsnNode map = key == null ? null : real(key.getPrevious());
			AbstractInsnNode list = map == null ? null : real(map.getPrevious());
			if (!(get instanceof MethodInsnNode lookup) || !lookup.name.equals("get") || !lookup.owner.equals("java/util/SequencedMap")
					|| !(key instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
					|| !(map instanceof VarInsnNode) || !(list instanceof FieldInsnNode field) || !field.name.equals("MIDDLE_APPENDERS")) {
				return false;
			}
			InsnList wrap = new InsnList();
			wrap.add(new VarInsnNode(Opcodes.ALOAD, load.var));
			wrap.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "around", AROUND_DESC, false));
			method.instructions.insert(cast, wrap);
			return true;
		}
		return false;
	}

	private static boolean calls(ClassNode node, String method, String name) {
		for (MethodNode m : node.methods) {
			if (!m.name.equals(method)) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(RUNTIME) && call.name.equals(name)) return true;
			}
		}
		return false;
	}

	private static AbstractInsnNode real(AbstractInsnNode insn) {
		while (insn != null && insn.getOpcode() < 0) insn = insn.getPrevious();
		return insn;
	}

	static boolean repair(ClassNode handler) {
		for (MethodNode method : handler.methods) {
			if (!method.name.equals("init") || !method.desc.equals("()V") || (method.access & Opcodes.ACC_STATIC) == 0) continue;
			MethodInsnNode post = null;
			int posts = 0;
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (call.owner.equals(RUNTIME)) return false;
				if (call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals("net/neoforged/fml/ModLoader")
						&& call.name.equals("postEvent") && call.desc.equals("(Lnet/neoforged/bus/api/Event;)V")) {
					post = call;
					posts++;
				}
			}
			if (posts != 1) return false;
			AbstractInsnNode previous = post.getPrevious();
			while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
			if (!(previous instanceof MethodInsnNode built) || built.getOpcode() != Opcodes.INVOKESPECIAL
					|| !built.owner.equals(EVENT) || !built.name.equals("<init>")) return false;
			post.owner = RUNTIME;
			post.name = "postRegisterAppenders";
			post.desc = "(L" + EVENT + ";)V";
			return true;
		}
		return false;
	}
}
