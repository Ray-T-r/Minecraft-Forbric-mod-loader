/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.List;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.boot.EventChainAudit;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Reports every dispatch of NeoForge's bus and MinecraftForge's buses to {@link EventChainAudit}, which works out
 * from their nesting which events one family forwards to the other and whether each forward happened exactly once
 * and carried its cancel.
 *
 * <p>Each dispatch method is renamed and replaced by a wrapper that reports entry, calls the original, and reports
 * the exit, the cancel state and any throwable, which it rethrows. Nothing else about the dispatch changes. The
 * three methods wrapped are NeoForge's {@code EventBus.post(Event, EventListener[])} (the one loop every NeoForge
 * post runs) and MinecraftForge's {@code post} on its two bus records; {@code fire} is rewritten to report through
 * the same original {@code post}, which its body already equals (run the invoker, return the event).
 *
 * <p>Inert unless {@code -Dforbric.eventChainAudit=<report.json>}: a diagnostic for gates, never on in play.
 */
public final class EventChainAuditInjector implements ClassTransformer {
	static final String NEO_BUS = "net.neoforged.bus.EventBus";
	static final String FORGE_CANCELLABLE = "net.minecraftforge.eventbus.internal.CancellableEventBusImpl";
	static final String FORGE_PLAIN = "net.minecraftforge.eventbus.internal.EventBusImpl";
	static final String NEO_EVENT_TYPE = ForeignType.EVENT.internal(Ecosystem.NEOFORGE), FORGE_EVENT_TYPE = ForeignType.EVENT.internal(Ecosystem.FORGE);
	static final String NEO_EVENT = "L" + NEO_EVENT_TYPE + ";";
	static final String NEO_POST = "(" + NEO_EVENT + "[Lnet/neoforged/bus/api/EventListener;)" + NEO_EVENT;
	static final String FORGE_EVENT = "L" + FORGE_EVENT_TYPE + ";";
	static final String FORGE_POST = "(" + FORGE_EVENT + ")Z";
	static final String FORGE_FIRE = "(" + FORGE_EVENT + ")" + FORGE_EVENT;
	static final String PREFIX = "forbric$audited$";
	private static final String AUDIT = Type.getInternalName(EventChainAudit.class);

	@Override public String name() { return "forbric-event-chain-audit"; }

	@Override public AnchorSet anchors() {
		if (!EventChainAudit.enabled()) return AnchorSet.scanned("event-chain audit is off (-D" + EventChainAudit.PROPERTY + " unset)");
		String cost = "the event-chain audit cannot see this bus, so its report under-counts forwards";
		return AnchorSet.of(new AnchorSet.Anchor(NEO_BUS, AnchorSet.Severity.REQUIRED, cost),
				new AnchorSet.Anchor(FORGE_CANCELLABLE, AnchorSet.Severity.REQUIRED, cost),
				new AnchorSet.Anchor(FORGE_PLAIN, AnchorSet.Severity.REQUIRED, cost));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (bytes == null || bytes.length == 0 || !EventChainAudit.enabled()) return bytes;
		boolean neo = NEO_BUS.equals(className), cancellable = FORGE_CANCELLABLE.equals(className);
		if (!neo && !cancellable && !FORGE_PLAIN.equals(className)) return bytes;
		return rewrite(bytes, neo, cancellable);
	}

	/** Visible for tests: the wrapped class, or the input unchanged when its shape is not the reviewed one. */
	public static byte[] rewrite(byte[] bytes, boolean neo, boolean cancellable) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (node.methods.stream().anyMatch(m -> m.name.startsWith(PREFIX))) return bytes;
		if (neo) {
			MethodNode post = find(node, "post", NEO_POST);
			if (post == null || (post.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return declined(bytes, node.name, "post(Event, EventListener[])");
			markListeners(post);
			node.methods.add(neoWrapper(node.name, rename(post)));
		} else {
			MethodNode post = find(node, "post", FORGE_POST), fire = find(node, "fire", FORGE_FIRE);
			if (post == null || fire == null || ((post.access | fire.access) & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0)
				return declined(bytes, node.name, "post/fire(Event)");
			MethodNode original = rename(post), originalFire = rename(fire);
			node.methods.add(forgeWrapper(node.name, original, original.access, original.signature, false, cancellable));
			node.methods.add(forgeWrapper(node.name, original, originalFire.access, originalFire.signature, true, cancellable));
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * Tells the audit which listener the loop is calling, so two listeners forwarding one event can be told from one
	 * listener that fans out. The reviewed loop is {@code listeners[i].invoke(event)}: {@code iload i; aaload;
	 * aload event; invokevirtual EventListener.invoke}. Any other shape leaves every forward credited to one
	 * listener, which only weakens the double-delivery check.
	 */
	private static void markListeners(MethodNode post) {
		MethodInsnNode invoke = null; int count = 0;
		for (AbstractInsnNode instruction : post.instructions)
			if (instruction instanceof MethodInsnNode call && call.owner.equals("net/neoforged/bus/api/EventListener") && call.name.equals("invoke")) { invoke = call; count++; }
		if (count != 1) { ForbricLog.warn("[Forbric/EventChain] NeoForge's dispatch loop has %d listener calls; listeners are not told apart", count); return; }
		AbstractInsnNode event = invoke.getPrevious(), load = event == null ? null : event.getPrevious(), index = load == null ? null : load.getPrevious();
		if (!(event instanceof VarInsnNode e && e.getOpcode() == Opcodes.ALOAD) || load == null || load.getOpcode() != Opcodes.AALOAD
				|| !(index instanceof VarInsnNode i && i.getOpcode() == Opcodes.ILOAD)) {
			ForbricLog.warn("[Forbric/EventChain] NeoForge's dispatch loop is not listeners[i].invoke(event); listeners are not told apart");
			return;
		}
		InsnList before = new InsnList();
		before.add(new VarInsnNode(Opcodes.ILOAD, ((VarInsnNode) index).var));
		before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AUDIT, "neoListener", "(I)V", false));
		post.instructions.insertBefore(invoke, before);
		post.instructions.insert(invoke, new MethodInsnNode(Opcodes.INVOKESTATIC, AUDIT, "neoListenerDone", "()V", false));
	}

	private static MethodNode find(ClassNode node, String name, String desc) {
		List<MethodNode> matches = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).toList();
		return matches.size() == 1 ? matches.getFirst() : null;
	}

	/** Keeps the original body under a private name; returns its ORIGINAL access and signature under the new name. */
	private static MethodNode rename(MethodNode method) {
		MethodNode identity = new MethodNode(method.access, method.name, method.desc, method.signature, null);
		method.name = PREFIX + method.name;
		method.access = (method.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
		return new MethodNode(identity.access, method.name, method.desc, identity.signature, null);
	}

	/** {@code post(event, listeners)}: report entry, run the loop, report exit or the throwable and rethrow it. */
	private static MethodNode neoWrapper(String owner, MethodNode original) {
		MethodNode wrapper = new MethodNode(original.access, "post", NEO_POST, original.signature, null);
		LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
		InsnList code = wrapper.instructions;
		code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AUDIT, "neoEnter", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
		code.add(start);
		code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1)); code.add(new VarInsnNode(Opcodes.ALOAD, 2));
		code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, PREFIX + "post", NEO_POST, false));
		code.add(new VarInsnNode(Opcodes.ASTORE, 3));
		code.add(new VarInsnNode(Opcodes.ALOAD, 1)); code.add(new InsnNode(Opcodes.ACONST_NULL));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AUDIT, "neoExit", "(Ljava/lang/Object;Ljava/lang/Throwable;)V", false));
		code.add(new VarInsnNode(Opcodes.ALOAD, 3)); code.add(new InsnNode(Opcodes.ARETURN));
		code.add(end);
		code.add(handler);
		code.add(new FrameNode(Opcodes.F_FULL, 3, new Object[] {owner, NEO_EVENT_TYPE, "[Lnet/neoforged/bus/api/EventListener;"},
				1, new Object[] {"java/lang/Throwable"}));
		code.add(new VarInsnNode(Opcodes.ASTORE, 3));
		code.add(new VarInsnNode(Opcodes.ALOAD, 1)); code.add(new VarInsnNode(Opcodes.ALOAD, 3));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AUDIT, "neoExit", "(Ljava/lang/Object;Ljava/lang/Throwable;)V", false));
		code.add(new VarInsnNode(Opcodes.ALOAD, 3)); code.add(new InsnNode(Opcodes.ATHROW));
		wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
		return wrapper;
	}

	/**
	 * {@code post(event)} or {@code fire(event)}: both run the original {@code post}, whose boolean is the cancel
	 * on the cancellable bus; {@code fire} then returns the event, exactly as its own body did.
	 */
	private static MethodNode forgeWrapper(String owner, MethodNode originalPost, int access, String signature, boolean fire, boolean cancellable) {
		MethodNode wrapper = new MethodNode(access, fire ? "fire" : "post", fire ? FORGE_FIRE : FORGE_POST, signature, null);
		LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
		InsnList code = wrapper.instructions;
		String exitDesc = "(Ljava/lang/Object;ZZLjava/lang/Throwable;)V";
		code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AUDIT, "forgeEnter", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
		code.add(start);
		code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, originalPost.name, FORGE_POST, false));
		code.add(new VarInsnNode(Opcodes.ISTORE, 2));
		code.add(new VarInsnNode(Opcodes.ALOAD, 1)); code.add(new InsnNode(cancellable ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
		code.add(new VarInsnNode(Opcodes.ILOAD, 2)); code.add(new InsnNode(Opcodes.ACONST_NULL));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AUDIT, "forgeExit", exitDesc, false));
		if (fire) { code.add(new VarInsnNode(Opcodes.ALOAD, 1)); code.add(new InsnNode(Opcodes.ARETURN)); }
		else { code.add(new VarInsnNode(Opcodes.ILOAD, 2)); code.add(new InsnNode(Opcodes.IRETURN)); }
		code.add(end);
		code.add(handler);
		code.add(new FrameNode(Opcodes.F_FULL, 2, new Object[] {owner, FORGE_EVENT_TYPE},
				1, new Object[] {"java/lang/Throwable"}));
		code.add(new VarInsnNode(Opcodes.ASTORE, 2));
		code.add(new VarInsnNode(Opcodes.ALOAD, 1)); code.add(new InsnNode(cancellable ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
		code.add(new InsnNode(Opcodes.ICONST_0)); code.add(new VarInsnNode(Opcodes.ALOAD, 2));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AUDIT, "forgeExit", exitDesc, false));
		code.add(new VarInsnNode(Opcodes.ALOAD, 2)); code.add(new InsnNode(Opcodes.ATHROW));
		wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
		return wrapper;
	}

	private static byte[] declined(byte[] bytes, String owner, String what) {
		ForbricLog.warn("[Forbric/EventChain] %s has no single %s of the reviewed shape; the audit does not see this bus", owner, what);
		return bytes;
	}
}
