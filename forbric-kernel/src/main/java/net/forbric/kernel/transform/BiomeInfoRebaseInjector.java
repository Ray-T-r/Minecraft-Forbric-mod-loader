/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * NeoForge's biome modifier pass starts from the biome's current climate and effects, not its constructor's.
 *
 * <p>The half of the biome repair NativeCoremodParity cannot do alone. Once biome reads go through NeoForge's
 * modified view, the view is all that counts — and NeoForge builds it from the biome's original info. A Fabric mod
 * (fabric-biome-api weather modifications) replaces {@code Biome.climateSettings} before the pass; built from the
 * original, the view would drop that. One call after {@code getOriginalBiomeInfo()} in {@code applyBiomeModifiers}
 * hands the pass KernelBiomeView.startFrom instead, which is the original unless the biome now holds something else.
 * Same switch as the read rewrite ({@code -Dforbric.biomeModifiedView=off}), so the two never apply apart;
 * {@code -Dforbric.biomeRebase=off} turns off this half alone, which gate M42 uses to show it is what keeps the
 * Fabric climate — it is a diagnostic control, not a setting.
 */
public final class BiomeInfoRebaseInjector implements ClassTransformer {
	static final String TARGET = "net.neoforged.neoforge.common.world.ModifiableBiomeInfo";
	static final String OWNER = "net/neoforged/neoforge/common/world/ModifiableBiomeInfo";
	static final String INFO = "net/neoforged/neoforge/common/world/ModifiableBiomeInfo$BiomeInfo";
	static final String APPLY_DESC = "(Lnet/minecraft/core/Holder;Ljava/util/List;Lnet/minecraft/core/RegistryAccess;)Z";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelBiomeView";
	static final String START_FROM = "(L" + INFO + ";Lnet/minecraft/core/Holder;)L" + INFO + ";";

	static final String DIAGNOSTIC = "forbric.biomeRebase";

	static boolean enabled() {
		return NativeCoremodParity.on(NativeCoremodParity.BIOME) && !"off".equalsIgnoreCase(System.getProperty(DIAGNOSTIC, "on"));
	}

	@Override public String name() { return "forbric-biome-info-rebase"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("biome modified view explicitly disabled with -D" + NativeCoremodParity.BIOME + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"a Fabric mod's biome climate is dropped once biome reads go through NeoForge's modified view"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || !TARGET.equals(className) || bytes == null || bytes.length == 0) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		MethodNode apply = null;
		for (MethodNode method : node.methods) {
			if (method.name.equals("applyBiomeModifiers") && method.desc.equals(APPLY_DESC)) apply = method;
		}
		if (apply == null) return declined(bytes, "applyBiomeModifiers is missing");
		MethodInsnNode original = null;
		int originals = 0;
		for (AbstractInsnNode insn : apply.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(RUNTIME)) return bytes;   // already rebased
			if (insn instanceof MethodInsnNode call && call.owner.equals(OWNER) && call.name.equals("getOriginalBiomeInfo")
					&& call.desc.equals("()L" + INFO + ";")) {
				original = call;
				originals++;
			}
		}
		if (originals != 1) return declined(bytes, "expected one getOriginalBiomeInfo() call, found " + originals);
		InsnList rebase = new InsnList();
		rebase.add(new VarInsnNode(Opcodes.ALOAD, 1));
		rebase.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "startFrom", START_FROM, false));
		apply.instructions.insert(original, rebase);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Worldgen] NeoForge's biome modifier pass now starts from each biome's current climate and "
				+ "effects, so a Fabric mod's weather change survives the modified view");
		return writer.toByteArray();
	}

	private static byte[] declined(byte[] bytes, String reason) {
		ForbricLog.warn("[Forbric/Worldgen] left NeoForge's biome modifier pass as it is: %s — with the modified view on, a "
				+ "Fabric mod's climate change is dropped", reason);
		return bytes;
	}
}
