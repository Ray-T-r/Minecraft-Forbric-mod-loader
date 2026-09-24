/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.List;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Makes each of {@code LootPool}'s two constructors fill the other family's fields too.
 *
 * <p>The merged {@code LootPool} carries both families' state: NeoForge's {@code String name} and MinecraftForge's
 * {@code Optional<String> name} and {@code Optional<ICondition> forge_condition}. Its codec is MinecraftForge's
 * seven-field one, which reads NeoForge's {@code name} and MinecraftForge's {@code forge_condition}. NeoForge's
 * six-argument constructor — the one {@code LootPool.Builder.build()} calls, so every pool a mod builds — sets only
 * NeoForge's field, and encoding such a pool dies on {@code forge_condition} being null: Corail Tombstone encodes
 * the pool it adds in its loot-table listener, and on the popular pack that stopped every datapack load. The
 * codec's own decoder goes through MinecraftForge's seven-argument constructor, which sets only MinecraftForge's
 * fields, so a decoded pool lost its name to {@code getName()}.
 *
 * <p>Straight-line writes before each constructor's return, no branch: NeoForge's constructor also sets
 * {@code forge_condition} empty and the {@code Optional} name from its {@code String}; MinecraftForge's also sets
 * the {@code String} name from its {@code Optional}. A class without all three fields, or a constructor that
 * already writes the other family's field, is left alone.
 */
public final class LootPoolFieldsInjector implements ClassTransformer {
	static final String PROPERTY = "forbric.lootPoolFields";
	static final String TARGET = "net.minecraft.world.level.storage.loot.LootPool";
	static final String OPTIONAL = "Ljava/util/Optional;", STRING = "Ljava/lang/String;";
	static final String HEAD = "(Ljava/util/List;Ljava/util/List;Ljava/util/List;"
			+ "Lnet/minecraft/world/level/storage/loot/providers/number/NumberProvider;"
			+ "Lnet/minecraft/world/level/storage/loot/providers/number/NumberProvider;";
	static final String NEO_CTOR = HEAD + OPTIONAL + ")V", FORGE_CTOR = HEAD + OPTIONAL + OPTIONAL + ")V";

	static boolean enabled() { return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")); }

	@Override public String name() { return "forbric-loot-pool-fields"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("loot pool field bridging explicitly disabled with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"a loot pool built by a mod cannot be encoded, and a decoded pool has no name"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || !TARGET.equals(className) || bytes == null || bytes.length == 0) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		String owner = node.name;
		boolean stringName = field(node, "name", STRING), optionalName = field(node, "name", OPTIONAL), condition = field(node, "forge_condition", OPTIONAL);
		if (!stringName || !optionalName || !condition) return bytes;   // not the two-family class this is about
		MethodNode neo = method(node, NEO_CTOR), forge = method(node, FORGE_CTOR);
		if (neo == null || forge == null) return declined(bytes, "the two constructors are not the reviewed ones");
		int changed = 0;
		if (!writes(neo, "forge_condition", OPTIONAL)) {
			InsnList add = new InsnList();
			add.add(new VarInsnNode(Opcodes.ALOAD, 0));
			add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false));
			add.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "forge_condition", OPTIONAL));
			add.add(new VarInsnNode(Opcodes.ALOAD, 0));
			add.add(new VarInsnNode(Opcodes.ALOAD, 0));
			add.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "name", STRING));
			add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "ofNullable", "(Ljava/lang/Object;)Ljava/util/Optional;", false));
			add.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "name", OPTIONAL));
			changed += beforeReturns(neo, add);
		}
		if (!writes(forge, "name", STRING)) {
			InsnList add = new InsnList();
			add.add(new VarInsnNode(Opcodes.ALOAD, 0));
			add.add(new VarInsnNode(Opcodes.ALOAD, 6));
			add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false));
			add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNullElse", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
			add.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Optional"));
			add.add(new InsnNode(Opcodes.ACONST_NULL));
			add.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
			add.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
			add.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "name", STRING));
			changed += beforeReturns(forge, add);
		}
		if (changed == 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/MergedBaseCompat] LootPool's constructors now fill both families' fields — a pool a mod "
				+ "built had no forge_condition, so encoding it failed, and a decoded pool had no name");
		return writer.toByteArray();
	}

	private static boolean field(ClassNode node, String name, String desc) {
		return node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(desc) && (f.access & Opcodes.ACC_STATIC) == 0);
	}

	private static MethodNode method(ClassNode node, String desc) {
		List<MethodNode> found = node.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(desc)).toList();
		return found.size() == 1 ? found.getFirst() : null;
	}

	private static boolean writes(MethodNode method, String name, String desc) {
		for (AbstractInsnNode insn : method.instructions)
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals(name) && f.desc.equals(desc)) return true;
		return false;
	}

	private static int beforeReturns(MethodNode method, InsnList template) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (insn.getOpcode() != Opcodes.RETURN) continue;
			method.instructions.insertBefore(insn, copy(template));
			n++;
		}
		return n;
	}

	private static InsnList copy(InsnList template) {
		InsnList out = new InsnList();
		for (AbstractInsnNode insn : template) out.add(insn.clone(java.util.Map.of()));
		return out;
	}

	private static byte[] declined(byte[] bytes, String reason) {
		ForbricLog.warn("[Forbric/MergedBaseCompat] left LootPool's constructors alone: %s", reason);
		return bytes;
	}
}
