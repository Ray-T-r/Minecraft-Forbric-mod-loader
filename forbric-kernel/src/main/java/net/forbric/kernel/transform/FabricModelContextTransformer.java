/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.ForbricLog;

/**
 * Keeps NeoForge's contextual model geometry when Fabric's renderer emits an otherwise native model.
 *
 * <p>Keyed on the Fabric Rendering API's public {@code FabricBlockStateModel} default and NeoForge's model extension, never on
 * a mod. {@code -Dforbric.fabricChunkRendering=off} leaves the default as written.
 */
public final class FabricModelContextTransformer implements ClassTransformer {
	public static final String PROPERTY = "forbric.fabricChunkRendering";
	static final String TARGET = "net/fabricmc/fabric/api/client/renderer/v1/model/FabricBlockStateModel";
	static final String EXTENSION = "net/neoforged/neoforge/client/extensions/BlockStateModelExtension";
	static final String MODEL = "net/minecraft/client/renderer/block/dispatch/BlockStateModel";
	static final String CONTEXT = "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/world/level/block/state/BlockState;";
	static final String PARTS = "Lnet/minecraft/util/RandomSource;Ljava/util/List;)V";
	static final String EMIT = "(Lnet/fabricmc/fabric/api/client/renderer/v1/mesh/QuadEmitter;" + CONTEXT
			+ "Lnet/minecraft/util/RandomSource;Ljava/util/function/Predicate;)V";
	private final Function<String, byte[]> classes;

	public FabricModelContextTransformer(Function<String, byte[]> classes) { this.classes = classes; }

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-fabric-model-context"; }

	@Override public AnchorSet anchors() {
		return enabled()
				? AnchorSet.of(new AnchorSet.Anchor(TARGET.replace('/', '.'), AnchorSet.Severity.REQUIRED,
						"Fabric's default quad emission must retain NeoForge's level, position and state when collecting native geometry"))
				: AnchorSet.scanned("disabled by " + PROPERTY);
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || !TARGET.equals(className.replace('.', '/'))) return bytes;
		byte[] modelBytes = classes.apply(MODEL + ".class");
		if (modelBytes == null) return bytes;
		ClassNode model = new ClassNode();
		new ClassReader(modelBytes).accept(model, ClassReader.SKIP_CODE);
		if (!model.interfaces.contains(EXTENSION)) return bytes;
		byte[] extensionBytes = classes.apply(EXTENSION + ".class");
		if (extensionBytes == null) return bytes;
		ClassNode extension = new ClassNode();
		new ClassReader(extensionBytes).accept(extension, ClassReader.SKIP_CODE);
		if (extension.methods.stream().noneMatch(m -> "collectParts".equals(m.name) && ("(" + CONTEXT + PARTS).equals(m.desc))) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		for (MethodNode method : node.methods) {
			if (!"emitQuads".equals(method.name) || !EMIT.equals(method.desc)) continue;
			MethodInsnNode found = null;
			for (var insn : method.instructions) if (insn instanceof MethodInsnNode call
					&& MODEL.equals(call.owner) && "collectParts".equals(call.name) && ("(" + PARTS).equals(call.desc)) {
				if (found != null) return bytes;
				found = call;
			}
			if (found == null) return bytes;
			AbstractInsnNode list = previous(found), random = previous(list);
			if (!(list instanceof VarInsnNode l) || l.getOpcode() != Opcodes.ALOAD
					|| !(random instanceof VarInsnNode r) || r.getOpcode() != Opcodes.ALOAD || r.var != 5) return bytes;
			InsnList args = new InsnList();
			for (int slot : new int[] {2, 3, 4}) args.add(new VarInsnNode(Opcodes.ALOAD, slot));
			method.instructions.insertBefore(random, args);
			found.desc = "(" + CONTEXT + PARTS;
			method.maxStack += 3;
			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			ForbricLog.info("[Forbric/Renderer] Fabric's default quad emission retains the native model's level, position and state");
			return writer.toByteArray();
		}
		return bytes;
	}

	private static AbstractInsnNode previous(AbstractInsnNode node) {
		if (node == null) return null;
		do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0);
		return node;
	}
}
