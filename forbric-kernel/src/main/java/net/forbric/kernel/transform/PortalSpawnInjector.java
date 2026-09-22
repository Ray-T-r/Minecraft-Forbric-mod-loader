/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/** A single descriptor-preserving call exchange; unrecognised or already-composed methods stay intact. */
public final class PortalSpawnInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.portalSpawn";
	static final String TARGET = "net.minecraft.world.level.block.BaseFireBlock";
	static final String HOST_DESC = "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;"
			+ "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V";
	static final String HOOK_DESC = "(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;"
			+ "Ljava/util/Optional;)Ljava/util/Optional;";
	static final String NEO = "net/neoforged/neoforge/event/EventHooks";
	static final String FORGE = "net/minecraftforge/event/ForgeEventFactory";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelPortalSpawn";

	@Override public String name() { return "forbric-portal-spawn"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"the portal hook result cannot reach BaseFireBlock through an event-only forward"));
	}

	private static boolean enabled() { return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")); }

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || !TARGET.equals(className) || bytes == null || bytes.length == 0) return bytes;
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
		if (!TARGET.replace('.', '/').equals(node.name)) return bytes;
		MethodNode host = null;
		int declarations = 0;
		for (MethodNode method : node.methods) {
			if (method.name.equals("onPlace") && method.desc.equals(HOST_DESC)) { host = method; declarations++; }
		}
		if (declarations != 1 || (host.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return bytes;
		MethodInsnNode target = null;
		int calls = 0;
		for (AbstractInsnNode instruction : host.instructions) {
			if (!(instruction instanceof MethodInsnNode call) || !call.name.equals("onTrySpawnPortal")) continue;
			if (call.owner.equals(FORGE) || call.owner.equals(RUNTIME)) return bytes;
			if (call.owner.equals(NEO)) { target = call; calls++; }
		}
		if (calls != 1 || target.getOpcode() != Opcodes.INVOKESTATIC || target.itf || !target.desc.equals(HOOK_DESC)) return bytes;
		AbstractInsnNode next = target.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		if (!(next instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) return bytes;
		target.owner = RUNTIME;
		ClassWriter writer = new ClassWriter(0); node.accept(writer);
		ForbricLog.info("[Forbric/PortalSpawn] BaseFireBlock preserves portal hook return values in NeoForge then MinecraftForge order");
		return writer.toByteArray();
	}
}
