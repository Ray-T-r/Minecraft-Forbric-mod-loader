/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.Arrays;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * NeoForge's structure modifiers apply again when a Fabric mod adds a worldgen feature: NeoForge's
 * {@code ChunkGenerator.refreshFeaturesPerStep} hands a supplier that is not NeoForge's own {@code Lazy} to
 * {@code KernelFeaturesPerStep.refresh}, which rebuilds it, instead of casting it.
 *
 * <p>NeoForge's base keeps {@code ChunkGenerator.featuresPerStep} as {@code private final Supplier} and fills it with
 * {@code Lazy.of(...)} in the constructor; {@code refreshFeaturesPerStep()} is {@code getfield featuresPerStep;
 * checkcast net/neoforged/neoforge/common/util/Lazy; invokevirtual Lazy.invalidate()}. fabric-biome-api (18.0.6 in
 * fabric-api 0.155.2) widens the field to public and mutable, and when any Fabric biome modifier adds or removes a
 * feature its {@code BiomeModificationImpl.finalizeWorldGen} — run from its {@code MinecraftServer.<init>} RETURN
 * mixin — writes {@code Suppliers.memoize(...)} (Guava) into every level stem's generator. NeoForge's
 * {@code ServerLifecycleHooks.runModifiers} applies the biome modifiers, then calls {@code refreshFeaturesPerStep()} on
 * every level stem's generator, then applies the structure modifiers: the {@code checkcast} throws
 * {@code ClassCastException} on the Guava supplier and no structure modifier applies ({@code KernelNeoWorldgen}'s guard
 * catches it: "NeoForge's biome/structure modifiers did not apply"). The merged base of Forbric 0.3.x and earlier guarded
 * the cast; NeoForge's own base does not, because native NeoForge never meets fabric-biome-api.
 *
 * <p>The {@code checkcast; invokevirtual} pair becomes {@code aload_0; invokestatic
 * KernelFeaturesPerStep.refresh(Supplier, ChunkGenerator)}: straight-line, no branch, no frame. The helper
 * invalidates a NeoForge {@code Lazy} exactly as the base does, and replaces any other supplier with a fresh NeoForge
 * {@code Lazy} of the computation the constructor holds. Fabric's memoized supplier computes the same list from the
 * same biome source and generation settings, so the fresh one answers what Fabric's would have, with NeoForge's
 * modifiers included even if Fabric's had already been read. All or nothing, on the reviewed shape only; a
 * Fabric-on-NeoForge adaptation (decision D52). {@code -Dforbric.featuresPerStepRefresh=off} leaves the class as the base
 * has it.
 */
public final class FeaturesPerStepRefreshInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.featuresPerStepRefresh";
	static final String GENERATOR = "net.minecraft.world.level.chunk.ChunkGenerator";
	static final String GENERATOR_INTERNAL = "net/minecraft/world/level/chunk/ChunkGenerator";
	static final String LAZY = "net/neoforged/neoforge/common/util/Lazy";
	static final String SUPPLIER_DESC = "Ljava/util/function/Supplier;";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelFeaturesPerStep";
	static final String HELPER = "refresh";
	static final String HELPER_DESC = "(" + SUPPLIER_DESC + "L" + GENERATOR_INTERNAL + ";)V";

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-features-per-step-refresh"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("NeoForge's refreshFeaturesPerStep cast left as the base has it with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(GENERATOR, AnchorSet.Severity.REQUIRED,
				"a Fabric mod that adds a worldgen feature makes NeoForge's modifier pass throw at refreshFeaturesPerStep, "
						+ "and no NeoForge structure modifier applies"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || !GENERATOR.equals(className) || bytes == null || bytes.length == 0) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (!rewrite(node)) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Worldgen] %s.refreshFeaturesPerStep rebuilds a feature list a Fabric mod replaced instead of "
				+ "casting it to NeoForge's Lazy, so NeoForge's structure modifiers still apply", className);
		return writer.toByteArray();
	}

	/** True when {@code refreshFeaturesPerStep} was rewritten; false (with a warning unless it already was) otherwise. */
	static boolean rewrite(ClassNode generator) {
		MethodNode refresh = null;
		for (MethodNode m : generator.methods) if (m.name.equals("refreshFeaturesPerStep") && m.desc.equals("()V")) refresh = m;
		if (refresh == null) return declined("refreshFeaturesPerStep() is missing");
		AbstractInsnNode[] code = Arrays.stream(refresh.instructions.toArray()).filter(i -> i.getOpcode() >= 0)
				.toArray(AbstractInsnNode[]::new);
		for (AbstractInsnNode insn : code) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(RUNTIME) && call.name.equals(HELPER)) return false;
		}
		// The reviewed shape, exactly: aload_0; getfield featuresPerStep; checkcast Lazy; invokevirtual Lazy.invalidate()V; return.
		boolean shape = code.length == 5
				&& code[0] instanceof VarInsnNode self && self.getOpcode() == Opcodes.ALOAD && self.var == 0
				&& code[1] instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
				&& field.owner.equals(GENERATOR_INTERNAL) && field.name.equals("featuresPerStep") && field.desc.equals(SUPPLIER_DESC)
				&& code[2] instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST && cast.desc.equals(LAZY)
				&& code[3] instanceof MethodInsnNode invalidate && invalidate.getOpcode() == Opcodes.INVOKEVIRTUAL
				&& invalidate.owner.equals(LAZY) && invalidate.name.equals("invalidate") && invalidate.desc.equals("()V")
				&& code[4].getOpcode() == Opcodes.RETURN;
		if (!shape) return declined("its body is not getfield featuresPerStep; checkcast Lazy; invokevirtual Lazy.invalidate()");
		InsnList replacement = new InsnList();
		replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
		replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, HELPER, HELPER_DESC, false));
		refresh.instructions.insert(code[3], replacement);
		refresh.instructions.remove(code[2]);
		refresh.instructions.remove(code[3]);
		return true;
	}

	private static boolean declined(String reason) {
		ForbricLog.warn("[Forbric/Worldgen] left %s as the base has it: %s — a Fabric mod that adds a worldgen feature will make "
				+ "NeoForge's modifier pass skip every structure modifier", GENERATOR, reason);
		return false;
	}
}
