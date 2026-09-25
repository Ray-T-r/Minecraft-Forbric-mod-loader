/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * NeoForge's {@code LivingConversionEvent.Post} is posted on the conversions the merge gave MinecraftForge's lambdas.
 *
 * <p>The merged {@code Zombie} keeps both families' conversion lambdas and calls MinecraftForge's, which post only
 * MinecraftForge's event: drowning, a husk's conversion and a villager's zombification never told NeoForge mods, so
 * what they carry over on it was lost. Each {@code ForgeEventFactory.onLivingConvert} call there goes to
 * {@code KernelConversions.onLivingConvert} instead, which tells both, each once. Same descriptor, one instruction:
 * nothing else in the lambda moves. {@code -Dforbric.neoConversionPost=off} leaves {@code Zombie} as merged.
 */
public final class NeoConversionPostInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.neoConversionPost";
	static final String ZOMBIE = "net.minecraft.world.entity.monster.zombie.Zombie";
	static final String FORGE = "net/minecraftforge/event/ForgeEventFactory";
	static final String DESC = "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/LivingEntity;)V";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-neo-conversion-post"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("NeoForge's conversion Post left unposted on Zombie with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(ZOMBIE, AnchorSet.Severity.REQUIRED,
				"NeoForge mods are never told a zombie drowned, a husk converted or a villager was zombified"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !ZOMBIE.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int moved = repair(node);
		if (moved == 0) return bytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		ForbricLog.info("[Forbric/Conversions] %s: %d MinecraftForge conversion Post(s) now post NeoForge's too — the merge "
				+ "kept MinecraftForge's lambdas there, which told only MinecraftForge", className, moved);
		return writer.toByteArray();
	}

	static int repair(ClassNode node) {
		int moved = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(FORGE) && call.name.equals("onLivingConvert")
						&& call.desc.equals(DESC)) {
					call.owner = "net/forbric/kernel/runtime/KernelConversions";
					moved++;
				}
			}
		}
		return moved;
	}
}
