/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import java.util.LinkedHashSet;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Replaces the body of named methods with a minimal type-correct return — a general kernel stub tool.
 *
 * <p>Used to neuter genuine-loader lifecycle hooks the merged base weaves into vanilla but that are meaningless
 * without the corresponding ecosystem lifecycle (which the kernel does not run). For M1 (zero mods) this is
 * strictly loss-free: e.g. NeoForge's {@code ServerLifecycleHooks.runModifiers} applies mod-added biome/structure
 * modifiers by looking up the {@code neoforge:biome_modifier} datapack registry — with no mods there are no
 * modifiers, and the datapack registry (normally registered by NeoForge's lifecycle) is absent, so the stock code
 * throws {@code Missing registry}. Neutering it returns cleanly.
 *
 * <p>Later milestones that run native ecosystem registration will register those datapack registries and REMOVE
 * the corresponding entries here so the real behavior returns; each neutered method is therefore a tracked M1
 * concession, not a permanent stub.
 */
public final class MethodBodyNeuter implements ClassTransformer {

	/** A method to neuter, addressed by owning class (binary name), method name, and descriptor. */
	public record Target(String ownerBinaryName, String methodName, String descriptor, String reason) {}

	private final Set<Target> targets = new LinkedHashSet<>();

	public MethodBodyNeuter add(Target t) {
		targets.add(t);
		return this;
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		boolean owns = targets.stream().anyMatch(t -> t.ownerBinaryName().equals(className));
		if (!owns) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		boolean changed = false;
		for (Target t : targets) {
			if (!t.ownerBinaryName().equals(className)) continue;
			for (MethodNode m : node.methods) {
				if (m.name.equals(t.methodName()) && m.desc.equals(t.descriptor())) {
					m.instructions = returnFor(Type.getReturnType(m.desc));
					m.tryCatchBlocks = null;
					m.localVariables = null;
					m.maxStack = 2;
					changed = true;
					ForbricLog.info("[Forbric/Stub] neutered %s.%s%s (%s)", className, m.name, m.desc, t.reason());
				}
			}
		}
		if (!changed) return classBytes;

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static InsnList returnFor(Type ret) {
		InsnList out = new InsnList();
		switch (ret.getSort()) {
			case Type.VOID -> out.add(new InsnNode(Opcodes.RETURN));
			case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> {
				out.add(new InsnNode(Opcodes.ICONST_0));
				out.add(new InsnNode(Opcodes.IRETURN));
			}
			case Type.LONG -> { out.add(new InsnNode(Opcodes.LCONST_0)); out.add(new InsnNode(Opcodes.LRETURN)); }
			case Type.FLOAT -> { out.add(new InsnNode(Opcodes.FCONST_0)); out.add(new InsnNode(Opcodes.FRETURN)); }
			case Type.DOUBLE -> { out.add(new InsnNode(Opcodes.DCONST_0)); out.add(new InsnNode(Opcodes.DRETURN)); }
			default -> { out.add(new InsnNode(Opcodes.ACONST_NULL)); out.add(new InsnNode(Opcodes.ARETURN)); }
		}
		return out;
	}

	@Override
	public String name() {
		return "forbric:method-body-neuter";
	}
}
