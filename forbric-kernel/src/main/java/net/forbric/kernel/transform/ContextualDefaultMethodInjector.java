/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.function.Function;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import net.forbric.kernel.mixin.DefaultMethodOverloadBridge;

/** Pure interface defaults retain the richest proved context available from their own argument contract. */
public final class ContextualDefaultMethodInjector implements ClassTransformer {
	private final Function<String, byte[]> resources;

	public ContextualDefaultMethodInjector(Function<String, byte[]> resources) {
		this.resources = resources;
	}

	@Override public AnchorSet anchors() {
		return AnchorSet.scanned("pure interface default overload delegation, proved from its operands and hierarchy");
	}

	@Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
		if (bytes == null) return null;
		ClassReader reader = new ClassReader(bytes);
		if ((reader.getAccess() & Opcodes.ACC_INTERFACE) == 0) return bytes;
		ClassNode node = new ClassNode();
		// Only a straight-line delegate is edited. Preserve every other method's stack-map frames.
		reader.accept(node, 0);
		int changed = DefaultMethodOverloadBridge.adapt(node, owner -> {
			if (owner.equals(node.name)) return node;
			byte[] source = resources.apply(owner + ".class");
			if (source == null) return null;
			ClassNode other = new ClassNode();
			new ClassReader(source).accept(other, ClassReader.SKIP_FRAMES);
			return other;
		});
		if (changed == 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}
}
