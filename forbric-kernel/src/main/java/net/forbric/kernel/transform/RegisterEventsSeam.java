/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * NeoForge's own {@code GameData.postRegisterEvents} posts {@code RegisterEvent}, with the kernel's three needs met at its
 * own call sites.
 *
 * <p>The kernel used to post {@code RegisterEvent} itself and copy the method's tail, so the genuine method never ran and a
 * mod that hooks it was woven into a method nobody calls: supermartijn642corelib fills every {@code @RegistryEntryAcceptor}
 * field from a {@code @ModifyVariable} on the event local and an {@code @Inject} after the
 * {@code ModLoader.postEventWrapContainerInModOrder} call, so packedup's menu type stayed null and its client died at startup
 * ("Container screen registered with null menu type!"). Now the kernel calls the method, and this runs on its bytes AFTER
 * Mixin, so every mixin aimed at it has already bound to the call sites it names:
 * <ul>
 *   <li>the per-registry {@code ModLoader.postEventWrapContainerInModOrder(event)} goes to
 *       {@code KernelLifecycle.onRegisterEvent}: the same delivery in mod order, priority phase by phase with the mod's
 *       container active, except that a mod whose listener throws costs that mod the registry and not every mod after it
 *       (NeoForge turns that throw into its loading-error screen, which the kernel does not have);</li>
 *   <li>where the dispatch loop ends and the tail begins (the target of the {@code getSuppressed().length <= 0} test),
 *       {@code KernelLifecycle.onRegisterEventsDispatched} runs: the Fabric main entrypoints on a dedicated server, with the
 *       root open, as they ran between the kernel's own dispatch and its copy of the tail;</li>
 *   <li>each tail step — a no-argument static call, or {@code ModLoader.postEvent} — goes through
 *       {@code KernelLifecycle.onRegisterEventsTailStep} / {@code onRegisterEventsModBusEvent}, which call the genuine step
 *       and report a failure at that step's own catch, so one failing step does not take the rest of the tail.</li>
 * </ul>
 * Calls a mixin already redirected or wrapped no longer name their original owner and are left alone; so are the class's
 * own (merged) methods. Applied only when the method has exactly one dispatch call and the tail test is where it is on
 * NeoForge 26.2; otherwise the class is left as woven, the kernel says so, and NeoForge's method runs unchanged.
 */
public final class RegisterEventsSeam {
	public static final String GAME_DATA = "net/neoforged/neoforge/registries/GameData";
	static final String METHOD = "postRegisterEvents";
	static final String MOD_LOADER = "net/neoforged/fml/ModLoader";
	static final String EVENT_DESC = "(Lnet/neoforged/bus/api/Event;)V";
	static final String DISPATCH = "postEventWrapContainerInModOrder";
	static final String POST_EVENT = "postEvent";
	static final String HOOKS = "net/forbric/kernel/boot/KernelLifecycle";
	static final String ON_EVENT = "onRegisterEvent";
	static final String ON_DISPATCHED = "onRegisterEventsDispatched";
	static final String ON_STEP = "onRegisterEventsTailStep";
	static final String ON_MOD_BUS_EVENT = "onRegisterEventsModBusEvent";

	/** Whether the GameData this process defined carries the seam; read by the kernel before it calls the method. */
	private static volatile boolean applied;

	private RegisterEventsSeam() {
	}

	public static boolean applied() {
		return applied;
	}

	/** The post-Mixin step: {@code bytes} unchanged for every class but GameData, and for a GameData of another shape. */
	public static byte[] afterMixin(String name, byte[] bytes) {
		if (bytes == null || !GAME_DATA.equals(name.replace('.', '/'))) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		Edit edit = apply(node);
		if (edit == null) {
			ForbricLog.warn("[Forbric/Lifecycle] NeoForge's GameData.postRegisterEvents is not the shape the kernel knows "
					+ "(one ModLoader.postEventWrapContainerInModOrder call, then the getSuppressed() test before the tail) — "
					+ "it runs exactly as NeoForge ships it: one mod's failing RegisterEvent listener rolls the registries back, "
					+ "and the Fabric main entrypoints of a dedicated server run after its tail instead of before it");
			return bytes;
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		applied = true;
		ForbricLog.info("[Forbric/Lifecycle] NeoForge's GameData.postRegisterEvents posts RegisterEvent itself, every mixin on "
				+ "it applied: its dispatch goes to each mod in order with a failing mod contained, the Fabric main entrypoints "
				+ "run where its loop ends, and %d tail step(s) each report their own failure", edit.steps());
		return writer.toByteArray();
	}

	/** What the seam changed: how many tail steps it routed. */
	public record Edit(int steps) {
	}

	/** Edits {@code postRegisterEvents} in place; null (and no change) when the method is not the known shape. */
	public static Edit apply(ClassNode node) {
		MethodNode method = null;
		for (MethodNode m : node.methods) {
			if (m.name.equals(METHOD) && m.desc.equals("()V")) method = m;
		}
		if (method == null) return null;
		List<MethodInsnNode> dispatch = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(MOD_LOADER)
					&& call.name.equals(DISPATCH) && call.desc.equals(EVENT_DESC)) dispatch.add(call);
		}
		LabelNode tail = tailStart(method);
		if (dispatch.size() != 1 || tail == null) return null;

		MethodInsnNode post = dispatch.get(0);
		method.instructions.set(post, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, ON_EVENT, "(Ljava/lang/Object;)V", false));

		int steps = 0;
		List<MethodInsnNode> calls = new ArrayList<>();
		for (AbstractInsnNode insn = tail; insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) calls.add(call);
		}
		for (MethodInsnNode call : calls) {
			if (call.owner.equals(node.name) || call.owner.startsWith("net/forbric/")) continue;
			if (call.owner.equals(MOD_LOADER) && call.name.equals(POST_EVENT) && call.desc.equals(EVENT_DESC)) {
				method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, ON_MOD_BUS_EVENT,
						"(Ljava/lang/Object;)V", false));
				steps++;
			} else if (call.desc.equals("()V")) {
				InsnList step = new InsnList();
				step.add(new LdcInsnNode(Type.getObjectType(call.owner)));
				step.add(new LdcInsnNode(call.name));
				step.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, ON_STEP, "(Ljava/lang/Class;Ljava/lang/String;)V", false));
				method.instructions.insertBefore(call, step);
				method.instructions.remove(call);
				steps++;
			}
		}
		// After the label and any frame or line number on it, so the frame still describes the label's own position.
		AbstractInsnNode at = tail;
		while (at.getNext() != null && at.getNext().getOpcode() < 0) at = at.getNext();
		method.instructions.insert(at, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, ON_DISPATCHED, "()V", false));
		return new Edit(steps);
	}

	/**
	 * The first instruction of the tail: NeoForge's loop ends with {@code if (suppressed.getSuppressed().length > 0)} —
	 * {@code INVOKEVIRTUAL RuntimeException.getSuppressed; ARRAYLENGTH; IFLE tail} — and the jump target is where the tail
	 * starts. Exactly one such test, or null.
	 */
	static LabelNode tailStart(MethodNode method) {
		LabelNode found = null;
		for (AbstractInsnNode insn : method.instructions) {
			if (!(insn instanceof MethodInsnNode call) || !call.name.equals("getSuppressed")
					|| !call.owner.equals("java/lang/RuntimeException")) continue;
			AbstractInsnNode length = real(call.getNext());
			AbstractInsnNode test = length == null ? null : real(length.getNext());
			if (!(length instanceof InsnNode) || length.getOpcode() != Opcodes.ARRAYLENGTH
					|| !(test instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFLE) return null;
			if (found != null) return null;
			found = jump.label;
		}
		return found;
	}

	private static AbstractInsnNode real(AbstractInsnNode insn) {
		while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		return insn;
	}
}
