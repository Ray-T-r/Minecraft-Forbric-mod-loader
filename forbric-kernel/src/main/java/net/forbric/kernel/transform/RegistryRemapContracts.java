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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.mixin.MixinFit;
import net.forbric.kernel.util.Caught;

/**
 * The remap contracts mods' mixins add to registry classes, as the kernel's mixin discovery read them.
 *
 * <p>fabric-api's registry sync puts a method on every {@code MappedRegistry} that takes the server's ids and remaps the
 * registry's own entry storage, and an argument-free one that undoes it; whatever the interface and the methods are
 * called, they are recognised by what the mixin does ({@link RemapContract}). Two passes need them, and neither is about
 * MinecraftForge: {@code KernelRegistryRevert} calls every undo on the plain registries before NeoForge's snapshot goes
 * back on a disconnect, and {@link RegistrySyncParityInjector} hands a contract on to MinecraftForge's registry wrapper,
 * which inherits empty copies of the storage it works on.
 *
 * <p>Lived inside {@link RegistrySyncParityInjector} until that injector had to be deletable on its own: on a base
 * without MinecraftForge there is no wrapper to give a contract to, but fabric-api's {@code unmap()} still has to run on
 * every registry before the snapshot is re-applied, and the revert finds it here.
 */
public final class RegistryRemapContracts {
	/** The server's ids, by entry name, as a remap contract takes them. */
	private static final String IDS = "Lit/unimi/dsi/fastutil/objects/Object2IntMap;";
	private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";

	/**
	 * A remap a mod's mixin adds to a registry class: a public {@code void} method the mixin implements for one of its
	 * interfaces ({@code owner}), that takes the server's ids as an {@code Object2IntMap} (argument {@code idsArgument})
	 * and works on the registry's own entry storage through the mixin's {@code @Shadow} fields. fabric-api's registry
	 * sync is one; whatever its interface and method are called, the wrapper that inherits it from {@code target} has
	 * empty copies of those fields and needs its own. {@code undo}, when the same interface declares one, is the
	 * argument-free {@code void} method the mixin implements over the same storage: what puts the registry back after a
	 * remap (null when there is none, or several). Internal names.
	 */
	public record RemapContract(String target, String owner, String name, String desc, int idsArgument, String undo) {
	}

	/** Per mixin config, the remap contracts its mixins add, as the kernel's mixin discovery read them. */
	private static final Map<String, List<RemapContract>> DISCOVERED = new ConcurrentHashMap<>();

	private RegistryRemapContracts() {
	}

	/** Records the remap contracts {@code mixin}, of {@code config}, adds; {@code resources} reads its interfaces. */
	public static void noteMixin(String config, ClassNode mixin, Function<String, byte[]> resources) {
		List<RemapContract> found = remapContracts(mixin, resources);
		if (!found.isEmpty()) DISCOVERED.computeIfAbsent(config, ignored -> new CopyOnWriteArrayList<>()).addAll(found);
	}

	/** Forgets what {@code config}'s mixins were seen to add, before it is read again. */
	public static void forgetMixins(String config) {
		DISCOVERED.remove(config);
	}

	/** Every remap contract the kernel's mixin discovery noted, for whichever registry class. */
	public static List<RemapContract> discoveredContracts() {
		return discovered(null);
	}

	/** The discovered remap contracts mixins add to {@code target}; every one of them for {@code null}. */
	static List<RemapContract> discovered(String target) {
		List<RemapContract> out = new ArrayList<>();
		for (List<RemapContract> contracts : DISCOVERED.values()) {
			for (RemapContract contract : contracts) if (target == null || contract.target().equals(target)) out.add(contract);
		}
		return out;
	}

	/**
	 * The remap contracts {@code mixin} adds to each of its targets (see {@link RemapContract}). Read off the mixin class
	 * and the interfaces it implements ({@code resources}: class files by resource path); whose mixin it is plays no part.
	 */
	public static List<RemapContract> remapContracts(ClassNode mixin, Function<String, byte[]> resources) {
		if (mixin == null || mixin.interfaces == null || mixin.interfaces.isEmpty() || resources == null) return List.of();
		List<String> targets = MixinFit.mixinTargets(mixin);
		if (targets.isEmpty()) return List.of();
		java.util.Set<String> shadows = new java.util.HashSet<>();
		for (FieldNode field : mixin.fields) {
			if ((field.access & Opcodes.ACC_STATIC) == 0 && (annotated(field.visibleAnnotations) || annotated(field.invisibleAnnotations))) shadows.add(field.name);
		}
		if (shadows.isEmpty()) return List.of();
		List<RemapContract> out = new ArrayList<>();
		for (MethodNode method : mixin.methods) {
			if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != Opcodes.ACC_PUBLIC
					|| method.name.startsWith("<") || !method.desc.endsWith(")V")) continue;
			Type[] arguments = Type.getArgumentTypes(method.desc);
			int ids = -1;
			for (int i = 0; i < arguments.length; i++) {
				if (!arguments[i].getDescriptor().equals(IDS)) continue;
				if (ids >= 0) { ids = -2; break; }
				ids = i;
			}
			if (ids < 0) continue;
			if (!touchesShadows(mixin, method, shadows)) continue;
			String owner = declaring(mixin.interfaces, method.name, method.desc, resources, new java.util.HashSet<>());
			if (owner == null) continue;
			String undo = null;
			for (MethodNode other : mixin.methods) {
				if ((other.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != Opcodes.ACC_PUBLIC
						|| !other.desc.equals("()V") || !touchesShadows(mixin, other, shadows)
						|| !owner.equals(declaring(mixin.interfaces, other.name, other.desc, resources, new java.util.HashSet<>()))) continue;
				undo = undo == null ? other.name : "";
			}
			if ("".equals(undo)) undo = null;
			for (String target : targets) out.add(new RemapContract(target, owner, method.name, method.desc, ids, undo));
		}
		return out;
	}

	/** Whether {@code method} reads or writes one of {@code mixin}'s {@code @Shadow} instance fields. */
	private static boolean touchesShadows(ClassNode mixin, MethodNode method, java.util.Set<String> shadows) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode field && field.owner.equals(mixin.name) && shadows.contains(field.name)) return true;
		}
		return false;
	}

	private static boolean annotated(List<org.objectweb.asm.tree.AnnotationNode> annotations) {
		if (annotations == null) return false;
		for (var annotation : annotations) if (SHADOW.equals(annotation.desc)) return true;
		return false;
	}

	/** The interface among {@code interfaces} (or their superinterfaces) that declares {@code name desc}, or null. */
	private static String declaring(List<String> interfaces, String name, String desc, Function<String, byte[]> resources, java.util.Set<String> seen) {
		for (String contract : interfaces) {
			if (!seen.add(contract)) continue;
			byte[] bytes;
			try { bytes = resources.apply(contract + ".class"); } catch (RuntimeException unreadable) { Caught.warn(unreadable, "[Forbric/RegistrySync] could not read %s; it is not checked as a remap contract", contract); bytes = null; }
			if (bytes == null) continue;
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			if ((node.access & Opcodes.ACC_INTERFACE) == 0) continue;
			for (MethodNode method : node.methods) {
				if (method.name.equals(name) && method.desc.equals(desc) && (method.access & Opcodes.ACC_STATIC) == 0) return node.name;
			}
			String inherited = declaring(node.interfaces, name, desc, resources, seen);
			if (inherited != null) return inherited;
		}
		return null;
	}
}
