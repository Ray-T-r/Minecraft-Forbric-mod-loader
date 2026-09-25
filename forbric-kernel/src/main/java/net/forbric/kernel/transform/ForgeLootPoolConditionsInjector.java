/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A MinecraftForge loot pool condition is honoured again, the way MinecraftForge honours it.
 *
 * <p>MinecraftForge gives a loot pool a condition two ways: {@code LootPool.Builder.when(ICondition)} for a pool built
 * in code, and a pool-level {@code "forge:condition"} in a loot table's JSON. Natively its builder passes the
 * condition into the pool (where only the encoder reads it — MinecraftForge never evaluates a code-built pool's
 * condition), and its {@code LootTable} reads pools through {@code LootPool.CONDITIONAL_CODEC}, which replaces a pool
 * whose condition is false with an empty pool in place. The merged base kept NeoForge's builder (it calls NeoForge's
 * constructor, which does not take the condition) and NeoForge's pool list codec
 * ({@code CommonHooks.lootPoolsCodec}, whose element codec is the plain {@code LootPool.CODEC}), so the first was
 * dropped and the second parsed but never judged.
 *
 * <p>Two edits: {@code Builder.build()} also stores {@code Optional.ofNullable(forge_condition)} in the pool it built,
 * as MinecraftForge's constructor would; and {@code lootPoolsCodec} takes its element codec from
 * KernelForgeConditions.poolElementCodec, which is MinecraftForge's {@code CONDITIONAL_CODEC}. It still sits inside
 * NeoForge's own conditional wrapper, so {@code neoforge:conditions} (and the kernel's {@code fabric:load_conditions})
 * are judged first and unchanged; a pool without the key decodes exactly as before. Like the kernel's other condition
 * keys, {@code forge:condition} is judged for every mod's data, whichever family shipped it — the pool list codec does
 * not know which pack a table came from. {@code lootPoolsCodec} is NeoForge's public API; its only caller in the game
 * is {@code LootTable}. The codec is built once, so {@code -Dforbric.forgePoolConditions=off} is read at launch.
 */
public final class ForgeLootPoolConditionsInjector implements ClassTransformer {
	static final String PROPERTY = "forbric.forgePoolConditions";
	static final String BUILDER = "net.minecraft.world.level.storage.loot.LootPool$Builder";
	static final String HOOKS = "net.neoforged.neoforge.common.CommonHooks";
	static final String POOL = "net/minecraft/world/level/storage/loot/LootPool";
	static final String CONDITION = "Lnet/minecraftforge/common/crafting/conditions/ICondition;";
	static final String NEO_CTOR = "(Ljava/util/List;Ljava/util/List;Ljava/util/List;Lnet/minecraft/world/level/storage/loot/providers/number/NumberProvider;"
			+ "Lnet/minecraft/world/level/storage/loot/providers/number/NumberProvider;Ljava/util/Optional;)V";
	static final String POOLS_CODEC_DESC = "(Ljava/util/function/BiConsumer;)Lcom/mojang/serialization/Codec;";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelForgeConditions";
	static final String CODEC = "Lcom/mojang/serialization/Codec;";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-forge-loot-pool-conditions"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("MinecraftForge loot pool conditions explicitly left unjudged with -D" + PROPERTY + "=off");
		return AnchorSet.of(
				new AnchorSet.Anchor(BUILDER, AnchorSet.Severity.REQUIRED, "a pool a MinecraftForge mod builds with when(ICondition) loses its condition"),
				new AnchorSet.Anchor(HOOKS, AnchorSet.Severity.REQUIRED, "a loot table pool's forge:condition is parsed but never judged"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !(BUILDER.equals(className) || HOOKS.equals(className))) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int changed = BUILDER.equals(className) ? carryCondition(node) : judgeCondition(node);
		if (changed <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info(BUILDER.equals(className)
				? "[Forbric/Loot] LootPool.Builder.build() now keeps a MinecraftForge when(ICondition) in the pool, as MinecraftForge's does"
				: "[Forbric/Loot] a loot table pool's forge:condition is now judged, as MinecraftForge's LootTable judges it");
		return writer.toByteArray();
	}

	/** {@code build()}: before its return, {@code pool.forge_condition = Optional.ofNullable(this.forge_condition)}. */
	static int carryCondition(ClassNode builder) {
		if (!hasField(builder, "forge_condition", CONDITION)) return 0;
		MethodNode build = method(builder, "build", "()L" + POOL + ";");
		if (build == null) return declined("LootPool.Builder.build() is missing");
		int constructions = 0;
		AbstractInsnNode returned = null;
		for (AbstractInsnNode insn : build.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(POOL) && call.name.equals("<init>")) {
				if (!call.desc.equals(NEO_CTOR)) return 0;   // MinecraftForge's own constructor: it takes the condition itself
				constructions++;
			}
			if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD && put.owner.equals(POOL) && put.name.equals("forge_condition")) return 0;
			if (insn.getOpcode() == Opcodes.ARETURN) {
				if (returned != null) return declined("LootPool.Builder.build() returns in more than one place");
				returned = insn;
			}
		}
		if (constructions != 1 || returned == null) return declined("LootPool.Builder.build() is not the reviewed shape");
		InsnList carry = new InsnList();
		carry.add(new InsnNode(Opcodes.DUP));
		carry.add(new VarInsnNode(Opcodes.ALOAD, 0));
		carry.add(new FieldInsnNode(Opcodes.GETFIELD, builder.name, "forge_condition", CONDITION));
		carry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "ofNullable", "(Ljava/lang/Object;)Ljava/util/Optional;", false));
		carry.add(new FieldInsnNode(Opcodes.PUTFIELD, POOL, "forge_condition", "Ljava/util/Optional;"));
		build.instructions.insertBefore(returned, carry);
		return 1;
	}

	/** {@code lootPoolsCodec}: the decoder's element codec (the first {@code LootPool.CODEC}) goes through the helper. */
	static int judgeCondition(ClassNode hooks) {
		MethodNode pools = method(hooks, "lootPoolsCodec", POOLS_CODEC_DESC);
		if (pools == null) return declined("CommonHooks.lootPoolsCodec is missing");
		FieldInsnNode element = null;
		for (AbstractInsnNode insn : pools.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(RUNTIME)) return 0;   // already done
			if (element == null && insn instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETSTATIC
					&& read.owner.equals(POOL) && read.name.equals("CODEC")) element = read;
		}
		AbstractInsnNode next = element == null ? null : element.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		if (!(next instanceof MethodInsnNode wrap) || !wrap.name.equals("createConditionalCodec")) {
			return declined("CommonHooks.lootPoolsCodec does not wrap LootPool.CODEC in NeoForge's conditional codec");
		}
		pools.instructions.insert(element, new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "poolElementCodec",
				"(" + CODEC + ")" + CODEC, false));
		return 1;
	}

	private static int declined(String reason) {
		ForbricLog.warn("[Forbric/Loot] left MinecraftForge loot pool conditions unjudged: %s", reason);
		return -1;
	}

	private static boolean hasField(ClassNode node, String name, String desc) {
		return node.fields.stream().anyMatch(field -> field.name.equals(name) && field.desc.equals(desc));
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}
}
