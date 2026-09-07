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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Lets MinecraftForge's {@code Bindings} find its service provider on the classpath instead of through a module
 * layer the kernel does not build.
 *
 * <p>{@code Bindings} is where Forge's config events, message parser and event bus are resolved, and its class
 * initializer asks {@code ServiceLoader.load(FMLLoader.getGameLayer(), IBindingsProvider.class)}. The kernel boots
 * the game from a flat classpath and never fills FML's module-layer manager, so that call throws — and every class
 * that touches Forge's config events dies with it: registering a config, loading one on a world, firing a config
 * event. Nothing had ever reached that far before, so nothing noticed.
 *
 * <p>The carrier declares the provider the ordinary way ({@code META-INF/services}), so the same lookup keyed on
 * the interface's own class loader finds exactly the implementation Forge intends. The rewrite is three
 * instructions for three, leaves the stack depth unchanged, and adds no branch — under a real module layer this
 * class would not be transformed at all, because the pattern is only replaced where it is actually found.
 */
public final class ForgeBindingsLookupInjector implements ClassTransformer {
	private static final String BINDINGS = "net.minecraftforge.fml.Bindings";
	private static final String FML_LOADER = "net/minecraftforge/fml/loading/FMLLoader";
	private static final String GAME_LAYER = "getGameLayer";
	private static final String SERVICE_LOADER = "java/util/ServiceLoader";
	private static final String LOAD = "load";
	private static final String LOAD_LAYER_DESC = "(Ljava/lang/ModuleLayer;Ljava/lang/Class;)Ljava/util/ServiceLoader;";
	private static final String LOAD_LOADER_DESC = "(Ljava/lang/Class;Ljava/lang/ClassLoader;)Ljava/util/ServiceLoader;";

	@Override
	public String name() {
		return "forbric-forge-bindings-lookup";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0 || !BINDINGS.equals(className)) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		int rewritten = 0;
		for (MethodNode m : node.methods) {
			rewritten += lookupOnTheClasspath(m);
		}
		if (rewritten == 0) return classBytes;
		ForbricLog.info("[Forbric/Forge] %s now finds its service provider on the classpath (%d lookup(s)) — it asked a "
				+ "module layer the kernel does not build, and every use of MinecraftForge's config events died with "
				+ "that call", className, rewritten);

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** {@code ServiceLoader.load(layer, X.class)} → {@code ServiceLoader.load(X.class, X.class.getClassLoader())}. */
	private static int lookupOnTheClasspath(MethodNode m) {
		int rewritten = 0;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
			MethodInsnNode load = (MethodInsnNode) insn;
			if (!SERVICE_LOADER.equals(load.owner) || !LOAD.equals(load.name) || !LOAD_LAYER_DESC.equals(load.desc)) {
				continue;
			}
			AbstractInsnNode service = previousOpcode(load);
			if (!(service instanceof LdcInsnNode)) continue;
			AbstractInsnNode layer = previousOpcode(service);
			if (layer == null || layer.getOpcode() != Opcodes.INVOKESTATIC
					|| !FML_LOADER.equals(((MethodInsnNode) layer).owner)
					|| !GAME_LAYER.equals(((MethodInsnNode) layer).name)) {
				continue;
			}
			m.instructions.remove(layer);
			InsnList loader = new InsnList();
			loader.add(new InsnNode(Opcodes.DUP));
			loader.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
					"()Ljava/lang/ClassLoader;", false));
			m.instructions.insert(service, loader);
			load.desc = LOAD_LOADER_DESC;
			rewritten++;
		}
		return rewritten;
	}

	private static AbstractInsnNode previousOpcode(AbstractInsnNode from) {
		for (AbstractInsnNode insn = from.getPrevious(); insn != null; insn = insn.getPrevious()) {
			if (insn.getOpcode() >= 0) return insn;
		}
		return null;
	}
}
