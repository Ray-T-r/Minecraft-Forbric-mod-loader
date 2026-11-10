/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Keeps fabric-registry-sync's int-map remap tracker off a map whose int keys already follow the registry.
 *
 * <p>fabric-registry-sync's client {@code ParticleResourcesMixin} passes {@code ParticleResources.providers} — the
 * vanilla-typed field — to {@code Int2ObjectMapTracker.register(Registry, String, Int2ObjectMap)} at the end of
 * {@code <init>}. On NeoForge's base that field holds the kernel's live view of NeoForge's Identifier-keyed map
 * ({@code KernelParticleProviders}), whose keys are the particle registry's ids at every read. The tracker's
 * {@code onRemap} copies the map, clears it and re-puts each value under the change map's new id for its key, which
 * is right only for a map whose keys are still the OLD ids when {@code MappedRegistry.remap} fires the event after
 * rebuilding its ids; the view is never stale, so there is nothing to re-key, and the {@code clear()} it was asked for
 * threw "Registry remapping failed!" on every join of a server whose mods register particle types. The view answers
 * as a native Fabric map answers after its tracker ran, so the tracker is not attached to it:
 * {@code register}'s head asks {@code KernelParticleProviders.followsTheRegistry(map, name)} and returns when it
 * answers true. Every other map is tracked unchanged.
 *
 * <p>Keyed on fabric-registry-sync's tracker method by owner, name and descriptor, never on a mod; the guard decides by
 * the map object, so a raw-id map handed to the same method by anyone is tracked as on native Fabric.
 * {@code -Dforbric.particleViewDetach=off} leaves the tracker as written (the symptom returns).
 */
public final class ParticleViewTrackerInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.particleViewDetach";
	static final String TRACKER = "net/fabricmc/fabric/impl/registry/sync/trackers/Int2ObjectMapTracker";
	static final String REGISTER_DESC = "(Lnet/minecraft/core/Registry;Ljava/lang/String;Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;)V";
	static final String VIEWS = "net/forbric/kernel/runtime/KernelParticleProviders";
	static final String GUARD = "followsTheRegistry";
	static final String GUARD_DESC = "(Ljava/lang/Object;Ljava/lang/String;)Z";

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric-particle-view-tracker";
	}

	@Override
	public AnchorSet anchors() {
		return enabled()
				? AnchorSet.of(new AnchorSet.Anchor(TRACKER.replace('/', '.'), AnchorSet.Severity.REQUIRED,
						"fabric-registry-sync's remap tracker re-keys the kernel's particle provider view and throws on its "
								+ "clear() — a client cannot join a server whose mods register particle types"))
				: AnchorSet.scanned("-D" + PROPERTY + "=off");
	}

	@Override
	public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (bytes == null || !enabled() || !TRACKER.equals(className.replace('.', '/'))) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (!detach(node)) return bytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		ForbricLog.info("[Forbric/Particles] fabric-registry-sync's Int2ObjectMapTracker.register leaves a map that follows "
				+ "the registry's ids by itself (the kernel's particle provider view) untracked; every other map is tracked "
				+ "as written");
		return writer.toByteArray();
	}

	/**
	 * Inserts {@code if (KernelParticleProviders.followsTheRegistry(map, name)) return;} at the head of the static
	 * {@code register} with the reviewed descriptor. False — and the class untouched — when there is no such method,
	 * or the guard is already there.
	 */
	static boolean detach(ClassNode node) {
		if (!TRACKER.equals(node.name)) return false;
		MethodNode register = null;
		for (MethodNode method : node.methods) {
			if ("register".equals(method.name) && REGISTER_DESC.equals(method.desc)
					&& (method.access & Opcodes.ACC_STATIC) != 0) {
				register = method;
			}
		}
		if (register == null) return false;
		for (AbstractInsnNode insn = register.instructions.getFirst(); insn != null && insn.getOpcode() < 0; insn = insn.getNext()) {
			// A frame already at the entry would sit beside the one inserted below at the same offset.
			if (insn instanceof FrameNode) return false;
		}
		for (AbstractInsnNode insn : register.instructions) {
			if (insn instanceof MethodInsnNode call && VIEWS.equals(call.owner) && GUARD.equals(call.name)) return false;
		}
		LabelNode track = new LabelNode();
		InsnList guard = new InsnList();
		guard.add(new VarInsnNode(Opcodes.ALOAD, 2));
		guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
		guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, VIEWS, GUARD, GUARD_DESC, false));
		guard.add(new JumpInsnNode(Opcodes.IFEQ, track));
		guard.add(new InsnNode(Opcodes.RETURN));
		guard.add(track);
		// The method's entry frame: its three arguments, an empty stack. Any frame that follows is relative to it.
		guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		register.instructions.insert(guard);
		register.maxStack = Math.max(register.maxStack, 2);
		return true;
	}
}
