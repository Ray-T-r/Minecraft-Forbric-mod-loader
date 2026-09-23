/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.mixin.MixinInstructionFingerprint;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/** Descriptor-preserving portal composition, including a narrowly proved restored direct-call pair. */
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
	static final String NEO_ONLY = "onTrySpawnPortalNeoOnly";
	// Actual pinned 26.2 merged onPlace: both the Optional consumer and every outer branch are reviewed.
	private static final String NATIVE_BODY = "6825b97e76072ed5a5ddcfe131fc7fb8609f41adf31011bffcc8113c5401b40f";

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
		int calls = 0, forgeCalls = 0;
		for (AbstractInsnNode instruction : host.instructions) {
			if (!(instruction instanceof MethodInsnNode call)) continue;
			if (call.owner.equals(RUNTIME) && (call.name.equals("onTrySpawnPortal") || call.name.equals(NEO_ONLY))) return bytes;
			if (!call.name.equals("onTrySpawnPortal")) continue;
			if (call.owner.equals(FORGE)) forgeCalls++;
			if (call.owner.equals(NEO)) { target = call; calls++; }
		}
		if (calls > 0 && forgeCalls > 0) {
			if (calls != 1 || forgeCalls != 1 || !provedDirectPair(host)) {
				String reason = "The portal caller contains both native hooks, but their order, cancellation barriers and consumed result are not proved; the caller and legacy bridge remain unchanged and duplicate delivery has not been ruled out.";
				CompatibilityFindings.record(new CompatibilityFinding("portal-direct-composition", "forbric", "Portal creation",
						"PortalSpawnInjector", CompatibilityFinding.Confidence.SUSPECTED, false, reason,
						List.of(TARGET + "#onPlace" + HOST_DESC, reason)));
				return bytes;
			}
			target.owner = RUNTIME;
			target.name = NEO_ONLY;
			ClassWriter writer = new ClassWriter(0); node.accept(writer);
			ForbricLog.info("[Forbric/PortalSpawn] proved direct NeoForge then MinecraftForge portal calls; suppressing the legacy forward only inside that NeoForge call");
			return writer.toByteArray();
		}
		if (forgeCalls > 0) return bytes;
		if (calls != 1 || target.getOpcode() != Opcodes.INVOKESTATIC || target.itf || !target.desc.equals(HOOK_DESC)) return bytes;
		AbstractInsnNode next = target.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		if (!(next instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) return bytes;
		target.owner = RUNTIME;
		ClassWriter writer = new ClassWriter(0); node.accept(writer);
		ForbricLog.info("[Forbric/PortalSpawn] BaseFireBlock preserves portal hook return values in NeoForge then MinecraftForge order");
		return writer.toByteArray();
	}

	/**
	 * Accept one insertion into the reviewed native caller: after Neo's existing nonempty guard, invoke
	 * Forge with that same result, store its answer, and repeat the same guard before the original consumer.
	 * Restoring the reviewed executable fingerprint proves the enclosing branches as well. Merely finding
	 * both symbols (or even the right adjacent loads) cannot justify disabling an event forward.
	 */
	private static boolean provedDirectPair(MethodNode host) {
		if ((host.access & Opcodes.ACC_SYNCHRONIZED) != 0 || !host.tryCatchBlocks.isEmpty()) return false;
		MethodNode copy = new MethodNode(host.access, host.name, host.desc, host.signature, host.exceptions.toArray(String[]::new)); host.accept(copy);
		List<AbstractInsnNode> code = new ArrayList<>();
		for (AbstractInsnNode instruction : copy.instructions) if (instruction.getOpcode() >= 0) code.add(instruction);
		int at = -1;
		for (int i = 0; i < code.size(); i++) if (call(code.get(i), Opcodes.INVOKESTATIC, NEO, "onTrySpawnPortal", HOOK_DESC)) at = i;
		if (at < 3 || at + 18 >= code.size() || !(code.get(at + 1) instanceof VarInsnNode store)
				|| store.getOpcode() != Opcodes.ASTORE) return false;
		int slot = store.var;
		if (!variable(code.get(at - 3), Opcodes.ALOAD, 2) || !variable(code.get(at - 2), Opcodes.ALOAD, 3)
				|| !variable(code.get(at - 1), Opcodes.ALOAD, slot)
				|| !variable(code.get(at + 2), Opcodes.ALOAD, slot)
				|| !call(code.get(at + 3), Opcodes.INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z")
				|| !(code.get(at + 4) instanceof JumpInsnNode neoGuard) || neoGuard.getOpcode() != Opcodes.IFEQ
				|| !variable(code.get(at + 5), Opcodes.ALOAD, 2) || !variable(code.get(at + 6), Opcodes.ALOAD, 3)
				|| !variable(code.get(at + 7), Opcodes.ALOAD, slot)
				|| !call(code.get(at + 8), Opcodes.INVOKESTATIC, FORGE, "onTrySpawnPortal", HOOK_DESC)
				|| !variable(code.get(at + 9), Opcodes.ASTORE, slot)
				|| !variable(code.get(at + 10), Opcodes.ALOAD, slot)
				|| !call(code.get(at + 11), Opcodes.INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z")
				|| !(code.get(at + 12) instanceof JumpInsnNode forgeGuard) || forgeGuard.getOpcode() != Opcodes.IFEQ
				|| nextCode(neoGuard.label) != nextCode(forgeGuard.label)
				|| !variable(code.get(at + 13), Opcodes.ALOAD, slot)
				|| !call(code.get(at + 14), Opcodes.INVOKEVIRTUAL, "java/util/Optional", "get", "()Ljava/lang/Object;")
				|| !(code.get(at + 15) instanceof TypeInsnNode cast) || cast.getOpcode() != Opcodes.CHECKCAST
				|| !cast.desc.equals("net/minecraft/world/level/portal/PortalShape")
				|| !variable(code.get(at + 16), Opcodes.ALOAD, 2)
				|| !call(code.get(at + 17), Opcodes.INVOKEVIRTUAL, cast.desc, "createPortalBlocks", "(Lnet/minecraft/world/level/LevelAccessor;)V")
				|| code.get(at + 18).getOpcode() != Opcodes.RETURN) return false;
		List<AbstractInsnNode> inserted = code.subList(at + 5, at + 13);
		// No outside entry into the insertion may disappear when we normalize it away for the fingerprint.
		for (AbstractInsnNode instruction : copy.instructions) {
			if (instruction instanceof JumpInsnNode jump && inserted.contains(nextCode(jump.label))) return false;
			if (instruction instanceof TableSwitchInsnNode table
					&& (inserted.contains(nextCode(table.dflt)) || table.labels.stream().anyMatch(l -> inserted.contains(nextCode(l))))) return false;
			if (instruction instanceof LookupSwitchInsnNode lookup
					&& (inserted.contains(nextCode(lookup.dflt)) || lookup.labels.stream().anyMatch(l -> inserted.contains(nextCode(l))))) return false;
		}
		for (AbstractInsnNode instruction : inserted) copy.instructions.remove(instruction);
		return NATIVE_BODY.equals(MixinInstructionFingerprint.hash(copy));
	}

	private static AbstractInsnNode nextCode(LabelNode label) {
		AbstractInsnNode next = label;
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}

	private static boolean variable(AbstractInsnNode instruction, int opcode, int slot) {
		return instruction instanceof VarInsnNode variable && variable.getOpcode() == opcode && variable.var == slot;
	}

	private static boolean call(AbstractInsnNode instruction, int opcode, String owner, String name, String descriptor) {
		return instruction instanceof MethodInsnNode call && call.getOpcode() == opcode && !call.itf
				&& call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor);
	}
}
