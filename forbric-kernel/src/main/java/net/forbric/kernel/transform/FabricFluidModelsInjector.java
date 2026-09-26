/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * NeoForge's "Missing FluidModel" check counts the fluid models fabric-rendering-fluids adds after it.
 *
 * <p>{@code ClientHooks.gatherFluidModels} warns for every fluid its map lacks, from inside the {@code bake} that
 * Fabric's {@code @WrapMethod} wraps, i.e. before Fabric adds its own registered models. Traveler's Backpack's two
 * potion fluids were reported missing on every client boot and rendered fine. The check's one {@code Map.containsKey}
 * (the one the warning's text follows) becomes {@code KernelFabricFluidModels.hasModel}, which also asks Fabric's
 * registry. Same stack shape, no new branch, so the frames stand. {@code -Dforbric.fabricFluidModels=off} leaves the
 * method as shipped.
 */
public final class FabricFluidModelsInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.fabricFluidModels";
	static final String HOOKS = "net.neoforged.neoforge.client.ClientHooks";
	static final String HOOK_OWNER = "net/forbric/kernel/runtime/KernelFabricFluidModels";
	static final String HOOK_DESC = "(Ljava/util/Map;Ljava/lang/Object;)Z";
	/** NeoForge's warning, which marks the check this repair may touch. */
	static final String WARNING = "Missing FluidModel for fluid";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-fabric-fluid-models"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("NeoForge's fluid-model check left as shipped with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(HOOKS, AnchorSet.Severity.REQUIRED,
				"a fluid a Fabric mod gives a model through FluidRenderingRegistry is reported as \"Missing FluidModel\" "
						+ "on every client boot, although Fabric's own wrapper of the bake adds it"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !HOOKS.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (!repair(node)) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/FluidModels] ClientHooks.gatherFluidModels counts the fluid models fabric-rendering-"
				+ "fluids adds after it — Fabric's wrapper of FluidStateModelSet.bake adds them once NeoForge's "
				+ "\"Missing FluidModel\" check has already run");
		return writer.toByteArray();
	}

	/** Points the completeness check of {@code gatherFluidModels} at the hook; false when there is nothing to do. */
	static boolean repair(ClassNode hooks) {
		for (MethodNode method : hooks.methods) {
			if (!method.name.equals("gatherFluidModels") || (method.access & Opcodes.ACC_STATIC) == 0) continue;
			MethodInsnNode check = null;
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (call.owner.equals(HOOK_OWNER)) return false;
				if (call.getOpcode() == Opcodes.INVOKEINTERFACE && call.owner.equals("java/util/Map")
						&& call.name.equals("containsKey") && warnsNext(call)) {
					if (check != null) return false;
					check = call;
				}
			}
			if (check == null) return false;
			method.instructions.set(check, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "hasModel", HOOK_DESC, false));
			return true;
		}
		return false;
	}

	/** Whether NeoForge's warning text is among the next few instructions, i.e. this is the check that guards it. */
	private static boolean warnsNext(AbstractInsnNode check) {
		AbstractInsnNode insn = check;
		for (int i = 0; i < 4 && insn != null; i++) {
			insn = insn.getNext();
			while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
			if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) return text.startsWith(WARNING);
		}
		return false;
	}
}
