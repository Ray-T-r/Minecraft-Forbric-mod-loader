/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.List;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Completes a Forge-canonical client tracking body with the other native part view. Existing
 * identities in the native collection are retained once, so no game entity class needs a special case.
 * A Neo-canonical body is handled by the companion Forge map repair.
 *
 * <p>The outcome is judged by {@link #CLAIM} on the class this transformer hands back, not by whether it edited.
 * Which family's {@code onTrackingStart} the merge keeps moves with the merge tools: a Neo-canonical body already
 * registers a NeoForge mod's parts in {@code dragonParts} and never reads MinecraftForge's array, so it needs no edit
 * here, and that is not a missing repair. A body that neither tracks NeoForge's parts nor can be repaired reports
 * nothing, which is the real miss. */
public final class ClientPartTrackingInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.clientPartTracking";
	static final String CALLBACKS = "net.minecraft.client.multiplayer.ClientLevel$EntityCallbacks";
	static final String CALLBACKS_INTERNAL = "net/minecraft/client/multiplayer/ClientLevel$EntityCallbacks";
	static final String LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
	static final String ENTITY = "net/minecraft/world/entity/Entity";
	static final String DRAGON = "net/minecraft/world/entity/boss/enderdragon/EnderDragon";
	static final String TRACKING_START_DESC = "(L" + ENTITY + ";)V";
	static final String FORGE_GET_PARTS = "()[L" + ForeignType.PART_ENTITY.internal(Ecosystem.FORGE) + ";";
	static final String NEO_GET_PARTS = "()[L" + ForeignType.PART_ENTITY.internal(Ecosystem.NEOFORGE) + ";";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-client-part-tracking"; }

	static final String CLAIM = "forbric-client-part-tracking#neoForgePartsTracked";

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("the client's part tracking explicitly left as merged with -D" + PROPERTY + "=off");
		return AnchorSet.scanned("ClientLevel$EntityCallbacks, judged by the " + CLAIM + " claim");
	}

	@Override public List<Claim> claims() {
		if (!enabled()) return List.of();
		return List.of(new Claim(CLAIM, AnchorSet.of(new AnchorSet.Anchor(CALLBACKS, AnchorSet.Severity.REQUIRED,
				"a NeoForge mod's multipart entity disconnects the client with a network protocol error when it comes into view"))));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context, ClaimReporter reporter) {
		byte[] result = transform(className, bytes, context);
		if (enabled() && CALLBACKS.equals(className) && result != null && tracksNeoForgeParts(result)) reporter.hit(CLAIM);
		return result;
	}

	/**
	 * Whether {@code bytes}' {@code onTrackingStart} leaves the client tracking a NeoForge mod's multipart entity: it
	 * reads NeoForge's {@code getParts()} and reaches {@code ClientLevel.dragonParts}, where NeoForge's own client
	 * registers them, and every MinecraftForge {@code getParts()} array it reads is tested for null before anything uses
	 * it. That array is null for every NeoForge mod's entity, and dereferencing it is what disconnected the client.
	 * True on NeoForge's own body, on this repair's edit of MinecraftForge's, and on any merge that already composes the
	 * two; false on MinecraftForge's unrepaired body.
	 */
	static boolean tracksNeoForgeParts(byte[] bytes) {
		ClassNode node = new ClassNode();
		try { new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES); } catch (RuntimeException unreadable) { return false; }
		if (!CALLBACKS_INTERNAL.equals(node.name)) return false;
		MethodNode start = method(node, "onTrackingStart", TRACKING_START_DESC);
		if (start == null) return false;
		boolean neo = false, dragonParts = false;
		for (AbstractInsnNode insn : start.instructions) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(LEVEL)
					&& field.name.equals("dragonParts") && field.desc.equals("Ljava/util/List;")) dragonParts = true;
			if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(ENTITY) || !call.name.equals("getParts")) continue;
			if (call.desc.equals(NEO_GET_PARTS)) neo = true;
			else if (call.desc.equals(FORGE_GET_PARTS) && !nullTested(call)) return false;
		}
		return neo && dragonParts;
	}

	/**
	 * Whether the array {@code read} returns is tested for null before it is used: stored and then tested
	 * ({@code parts = e.getParts(); if (parts != null)}), tested as it is stored
	 * ({@code if ((parts = e.getParts()) != null)}), or replaced by an empty array when null
	 * ({@code requireNonNullElse(e.getParts(), new T[0])}).
	 */
	private static boolean nullTested(MethodInsnNode read) {
		AbstractInsnNode after = next(read);
		if (after instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE) {
			return next(store) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == store.var
					&& nullJump(next(load));
		}
		if (after != null && after.getOpcode() == Opcodes.DUP) {
			AbstractInsnNode test = next(after);
			if (test instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE) test = next(store);
			return nullJump(test);
		}
		return after != null && after.getOpcode() == Opcodes.ICONST_0 && next(after) instanceof TypeInsnNode empty
				&& empty.getOpcode() == Opcodes.ANEWARRAY && next(empty) instanceof MethodInsnNode orElse
				&& orElse.owner.equals("java/util/Objects") && orElse.name.equals("requireNonNullElse");
	}

	private static boolean nullJump(AbstractInsnNode insn) {
		return insn != null && (insn.getOpcode() == Opcodes.IFNULL || insn.getOpcode() == Opcodes.IFNONNULL);
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !CALLBACKS.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		// Expanded, so the one frame this adds is written out against whatever precedes it rather than computed by hand.
		new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
		MethodNode start = method(node, "onTrackingStart", TRACKING_START_DESC);
		int changed = start == null ? declined("ClientLevel$EntityCallbacks has no onTrackingStart(Entity)") : track(start);
		if (changed <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Entity] the client's onTrackingStart adds a multipart entity's NeoForge parts to dragonParts, "
				+ "as NeoForge's does — it read only MinecraftForge's getParts(), which a NeoForge mod's entity leaves null, "
				+ "and the client disconnected when one came into view");
		return writer.toByteArray();
	}

	/**
	 * onTrackingStart: NeoForge's registration at the head of MinecraftForge's multipart block, and MinecraftForge's loop
	 * skipped when its parts are null. 0 when the method already reads NeoForge's parts (NeoForge's own body, or this edit).
	 */
	static int track(MethodNode start) {
		MethodInsnNode forgeParts = null;
		for (AbstractInsnNode insn : start.instructions) {
			if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(ENTITY) || !call.name.equals("getParts")) continue;
			if (call.desc.equals(NEO_GET_PARTS)) return 0;
			if (!call.desc.equals(FORGE_GET_PARTS)) return declined("onTrackingStart calls getParts" + call.desc);
			if (forgeParts != null) return declined("onTrackingStart reads MinecraftForge's getParts() twice");
			forgeParts = call;
		}
		if (forgeParts == null) return declined("onTrackingStart reads no getParts()");

		// if (entity.isMultipartEntity()) { PartEntity<?>[] parts = entity.getParts(); for (...) ... }
		AbstractInsnNode load = previous(forgeParts);
		AbstractInsnNode test = previous(load);
		AbstractInsnNode multipart = previous(test);
		AbstractInsnNode receiver = previous(multipart);
		AbstractInsnNode store = next(forgeParts);
		if (!loadsEntity(load) || !(test instanceof JumpInsnNode skip) || skip.getOpcode() != Opcodes.IFEQ
				|| !(multipart instanceof MethodInsnNode asks) || !asks.owner.equals(ENTITY) || !asks.name.equals("isMultipartEntity")
				|| !asks.desc.equals("()Z") || !loadsEntity(receiver)
				|| !(store instanceof VarInsnNode parts) || parts.getOpcode() != Opcodes.ASTORE) {
			return declined("MinecraftForge's part loop is not `if (entity.isMultipartEntity()) for (part : entity.getParts())`");
		}
		// The block is entered with the method's own two locals and nothing on the stack, which is what the new frame says.
		FrameNode entry = frameBefore(receiver);
		if (entry == null || !List.of(CALLBACKS_INTERNAL, ENTITY).equals(entry.local) || entry.stack == null || !entry.stack.isEmpty()) {
			return declined("the multipart block is not entered with exactly (this, entity)");
		}
		// The dragon's own case reaches dragonParts this way; NeoForge's registration reaches it the same way.
		FieldInsnNode level = null;
		FieldInsnNode dragonParts = null;
		for (AbstractInsnNode insn : start.instructions) {
			if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD) continue;
			if (field.owner.equals(CALLBACKS_INTERNAL) && field.desc.equals("L" + LEVEL + ";")) level = field;
			if (field.owner.equals(LEVEL) && field.name.equals("dragonParts") && field.desc.equals("Ljava/util/List;")) dragonParts = field;
		}
		if (level == null || dragonParts == null) return declined("onTrackingStart does not reach ClientLevel.dragonParts");

		LabelNode forge = new LabelNode();
		InsnList neo = new InsnList();
		neo.add(new VarInsnNode(Opcodes.ALOAD, 1));
		neo.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, "getParts", NEO_GET_PARTS, false));
		neo.add(new VarInsnNode(Opcodes.ASTORE, parts.var));
		neo.add(new VarInsnNode(Opcodes.ALOAD, parts.var));
		neo.add(new JumpInsnNode(Opcodes.IFNULL, forge));
		neo.add(new VarInsnNode(Opcodes.ALOAD, 0));
		neo.add(new FieldInsnNode(Opcodes.GETFIELD, level.owner, level.name, level.desc));
		neo.add(new FieldInsnNode(Opcodes.GETFIELD, dragonParts.owner, dragonParts.name, dragonParts.desc));
		neo.add(new VarInsnNode(Opcodes.ALOAD, parts.var));
		neo.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/kernel/runtime/KernelMultipartViews", "addDistinct", "(Ljava/util/List;[L" + ENTITY + ";)V", false));
		neo.add(forge);
		neo.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {CALLBACKS_INTERNAL, ENTITY}, 0, new Object[0]));
		start.instructions.insert(test, neo);

		InsnList guard = new InsnList();
		guard.add(new VarInsnNode(Opcodes.ALOAD, parts.var));
		guard.add(new JumpInsnNode(Opcodes.IFNULL, skip.label));
		start.instructions.insert(store, guard);
		return 1;
	}

	private static boolean loadsEntity(AbstractInsnNode insn) {
		return insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 1;
	}

	/** The frame the instructions from {@code insn} on are entered with, when nothing executes between the two. */
	private static FrameNode frameBefore(AbstractInsnNode insn) {
		for (AbstractInsnNode at = insn.getPrevious(); at != null; at = at.getPrevious()) {
			if (at instanceof FrameNode frame) return frame;
			if (!(at instanceof LabelNode) && !(at instanceof LineNumberNode)) return null;
		}
		return null;
	}

	private static AbstractInsnNode previous(AbstractInsnNode insn) {
		AbstractInsnNode at = insn == null ? null : insn.getPrevious();
		while (at != null && at.getOpcode() < 0) at = at.getPrevious();
		return at;
	}

	private static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode at = insn == null ? null : insn.getNext();
		while (at != null && at.getOpcode() < 0) at = at.getNext();
		return at;
	}

	private static int declined(String reason) {
		ForbricLog.warn("[Forbric/Entity] left the client's part tracking as merged: %s — a NeoForge mod's multipart entity "
				+ "may disconnect the client", reason);
		return -1;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}
}
