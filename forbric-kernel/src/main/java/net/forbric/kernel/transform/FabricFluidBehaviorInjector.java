/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;
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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A fluid tag a Fabric mod gave a fluid behaviour has a fluid type the merged {@code EntityFluidInteraction} knows.
 *
 * <p>fabric-content-registries tracks the tags registered with {@code EntityFluidInteractionRegistry} next to water and
 * lava, and every tick asks the entity's {@code EntityFluidInteraction.isInFluid(tag)} for each; its behaviours read
 * {@code getFluidHeight(tag)} and push through {@code applyCurrentTo(tag, …)}, and its HUD mixin asks
 * {@code isEyeInFluid(tag)} for the air bubbles every frame. The merged class is NeoForge's, which tracks by fluid type
 * and turns a tag into one in {@code getFluidTypeByTag} — water, lava, or {@code IllegalArgumentException}. So the
 * first entity to tick after a Fabric mod registered a behaviour threw "Ticking entity" and stopped the server.
 *
 * <p>Before that throw, the method now asks {@code KernelFluidTypes.byTag}: the type of a tag a Fabric behaviour is
 * registered for — the same type a foreign fluid in that tag reports ({@code KernelFabricFluidBehaviors}), so the tag
 * questions read the tracker the fluid fills. Any other tag still throws, as NeoForge does. On the reviewed shape only:
 * the static method returns water or lava and ends in exactly one {@code IllegalArgumentException}.
 * {@code -Dforbric.fabricFluidBehavior=off} leaves it as merged (and the runtime then reports no behaviour type either).
 */
public final class FabricFluidBehaviorInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.fabricFluidBehavior";
	static final String INTERACTION = "net.minecraft.world.entity.EntityFluidInteraction";
	static final String NAME = "getFluidTypeByTag";
	static final String TYPE = ForeignType.FLUID_TYPE.internal(Ecosystem.NEOFORGE);
	static final String DESC = "(Lnet/minecraft/tags/TagKey;)L" + TYPE + ";";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelFluidTypes";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-fabric-fluid-behavior"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("Fabric fluid behaviours left without a fluid type with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(INTERACTION, AnchorSet.Severity.REQUIRED,
				"a Fabric mod's fluid behaviour throws on the first entity tick and stops the server"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !INTERACTION.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (repair(node) <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Fluid] EntityFluidInteraction.getFluidTypeByTag answers a Fabric fluid-behaviour tag with that "
				+ "behaviour's fluid type instead of throwing — fabric-api asks it for every registered tag on every entity tick");
		return writer.toByteArray();
	}

	static int repair(ClassNode interaction) {
		MethodNode method = null;
		for (MethodNode m : interaction.methods) {
			if (m.name.equals(NAME) && m.desc.equals(DESC) && (m.access & Opcodes.ACC_STATIC) != 0) method = m;
		}
		if (method == null) return declined("getFluidTypeByTag(TagKey) is missing");
		TypeInsnNode thrown = null;
		int throwsCount = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(RUNTIME)) return 0;   // already done
			if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
				if (!type.desc.equals("java/lang/IllegalArgumentException")) return declined("getFluidTypeByTag creates a " + type.desc);
				thrown = type;
			}
			if (insn.getOpcode() == Opcodes.ATHROW) throwsCount++;
		}
		if (thrown == null || throwsCount != 1) return declined("getFluidTypeByTag does not end in NeoForge's one IllegalArgumentException");
		// Where NeoForge gives up on the tag: ask the kernel; non-null is returned, null falls through to the throw.
		LabelNode unknown = new LabelNode();
		InsnList ask = new InsnList();
		ask.add(new VarInsnNode(Opcodes.ALOAD, 0));
		ask.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "byTag", DESC, false));
		ask.add(new InsnNode(Opcodes.DUP));
		ask.add(new JumpInsnNode(Opcodes.IFNULL, unknown));
		ask.add(new InsnNode(Opcodes.ARETURN));
		ask.add(unknown);
		ask.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] { TYPE }));
		ask.add(new InsnNode(Opcodes.POP));
		method.instructions.insertBefore(thrown, ask);
		return 1;
	}

	private static int declined(String reason) {
		ForbricLog.warn("[Forbric/Fluid] left EntityFluidInteraction.getFluidTypeByTag as merged: %s — a Fabric mod's fluid "
				+ "behaviour throws on the first entity tick", reason);
		return -1;
	}
}
