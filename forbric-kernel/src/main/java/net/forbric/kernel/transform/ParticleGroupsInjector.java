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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * MinecraftForge's {@code ParticleEngine.registerParticleGroup} works on the merged engine.
 *
 * <p>MinecraftForge's API method kept its body: {@code factories.putIfAbsent(type, factory)} on a static map and
 * {@code particleRenderOrder.add(type)} on a static list. The merge kept NeoForge's fields: {@code factories} is declared
 * but its initialiser was MinecraftForge's {@code <clinit>}, which lost, so it is null; and {@code particleRenderOrder}
 * is NeoForge's INSTANCE field of that name. A MinecraftForge mod registering a particle group threw a
 * NullPointerException, and past that an IncompatibleClassChangeError. And NeoForge's constructor never read either.
 *
 * <p>So: {@code <clinit>} initialises {@code factories} and a static {@code forbric$forgeRenderOrder}; the method appends
 * to that list instead; and NeoForge's constructor, right after its {@code RegisterParticleGroupsEvent}, hands both to
 * {@code KernelParticleGroups.addMinecraftForge}, which merges them into the instance collections it is building. Only on
 * the reviewed shape; {@code -Dforbric.forgeParticleGroups=off} leaves the class as merged.
 */
public final class ParticleGroupsInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.forgeParticleGroups";
	static final String ENGINE = "net.minecraft.client.particle.ParticleEngine";
	static final String ENGINE_INTERNAL = "net/minecraft/client/particle/ParticleEngine";
	static final String ORDER = "forbric$forgeRenderOrder";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelParticleGroups";
	static final String MAP = "Ljava/util/Map;", LIST = "Ljava/util/List;";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-forge-particle-groups"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("MinecraftForge particle groups explicitly left as merged with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(ENGINE, AnchorSet.Severity.REQUIRED,
				"a MinecraftForge mod registering a particle group throws"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !ENGINE.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (repair(node) <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Particles] MinecraftForge's ParticleEngine.registerParticleGroup keeps its groups where NeoForge's "
				+ "engine picks them up — it threw on a null map and a static read of NeoForge's instance field");
		return writer.toByteArray();
	}

	static int repair(ClassNode engine) {
		FieldNode factories = field(engine, "factories", MAP);
		MethodNode register = method(engine, "registerParticleGroup");
		MethodNode clinit = method(engine, "<clinit>");
		MethodNode ctor = null;
		for (MethodNode m : engine.methods) if (m.name.equals("<init>")) { if (ctor != null) return declined("ParticleEngine has more than one constructor"); ctor = m; }
		if (field(engine, ORDER, LIST) != null) return 0;
		if (factories == null || (factories.access & Opcodes.ACC_STATIC) == 0 || register == null || clinit == null || ctor == null) return 0;
		for (AbstractInsnNode insn : clinit.instructions) {
			if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC && put.name.equals("factories")) return 0;   // MinecraftForge's own
		}
		List<FieldInsnNode> orderReads = new ArrayList<>();
		for (AbstractInsnNode insn : register.instructions) {
			if (insn instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETSTATIC && read.name.equals("particleRenderOrder") && read.desc.equals(LIST)) orderReads.add(read);
		}
		FieldNode instanceOrder = field(engine, "particleRenderOrder", LIST);
		if (orderReads.size() != 1 || instanceOrder == null || (instanceOrder.access & Opcodes.ACC_STATIC) != 0) {
			return declined("registerParticleGroup does not read particleRenderOrder statically once, or the field is not NeoForge's instance field");
		}
		// NeoForge's constructor: map in slot 3, list in slot 4, both handed to RegisterParticleGroupsEvent, then posted.
		MethodInsnNode post = null;
		boolean event = false;
		for (AbstractInsnNode insn : ctor.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals("<init>") && call.owner.endsWith("/RegisterParticleGroupsEvent")) {
				AbstractInsnNode list = real(call.getPrevious()), map = list == null ? null : real(list.getPrevious());
				event = list instanceof VarInsnNode l && l.var == 4 && map instanceof VarInsnNode m && m.var == 3;
			}
			if (event && post == null && insn instanceof MethodInsnNode call && call.name.equals("postEvent")) post = call;
		}
		if (!event || post == null) return declined("NeoForge's ParticleEngine constructor is not the reviewed shape");

		engine.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, ORDER, LIST, null, null));
		InsnList init = new InsnList();
		init.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
		init.add(new InsnNode(Opcodes.DUP));
		init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
		init.add(new FieldInsnNode(Opcodes.PUTSTATIC, ENGINE_INTERNAL, "factories", MAP));
		init.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
		init.add(new InsnNode(Opcodes.DUP));
		init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
		init.add(new FieldInsnNode(Opcodes.PUTSTATIC, ENGINE_INTERNAL, ORDER, LIST));
		clinit.instructions.insert(init);

		orderReads.getFirst().name = ORDER;

		InsnList merge = new InsnList();
		merge.add(new VarInsnNode(Opcodes.ALOAD, 3));
		merge.add(new VarInsnNode(Opcodes.ALOAD, 4));
		merge.add(new FieldInsnNode(Opcodes.GETSTATIC, ENGINE_INTERNAL, "factories", MAP));
		merge.add(new FieldInsnNode(Opcodes.GETSTATIC, ENGINE_INTERNAL, ORDER, LIST));
		merge.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "addMinecraftForge", "(" + MAP + LIST + MAP + LIST + ")V", false));
		ctor.instructions.insert(post, merge);
		return 1;
	}

	private static AbstractInsnNode real(AbstractInsnNode insn) {
		while (insn != null && insn.getOpcode() < 0) insn = insn.getPrevious();
		return insn;
	}

	private static int declined(String reason) {
		ForbricLog.warn("[Forbric/Particles] left ParticleEngine as merged: %s — a MinecraftForge mod registering a particle group throws", reason);
		return -1;
	}

	private static FieldNode field(ClassNode node, String name, String desc) {
		for (FieldNode f : node.fields) if (f.name.equals(name) && f.desc.equals(desc)) return f;
		return null;
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) if (m.name.equals(name)) return m;
		return null;
	}
}
