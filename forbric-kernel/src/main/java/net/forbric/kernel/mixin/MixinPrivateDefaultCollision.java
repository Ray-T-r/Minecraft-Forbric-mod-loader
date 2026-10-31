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

package net.forbric.kernel.mixin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Renames a guest mixin's private method that would hide, at run time, a default method of an interface its target
 * implements on this base — what Mixin's {@code @Unique} does for a clash with the target's own members.
 *
 * <p>A private instance method Mixin merges into a class keeps its name. When an interface of that class's hierarchy
 * declares a method of the same name and descriptor with a default body, HotSpot no longer selects the default for any
 * class below it: its default-method pass looks the name up in the class hierarchy without skipping private methods,
 * finds the merged one and adds no default, while interface dispatch does skip private methods, finds nothing and throws
 * {@code AbstractMethodError}. Measured on NeoForge's base: fabric-entity-events-v1's
 * {@code effect.LivingEntityMixin} declares {@code private LivingEntity self()} for its own handlers, NeoForge's
 * {@code ILivingEntityExtension} (which {@code LivingEntity} implements) defaults {@code LivingEntity self()}, and the
 * integrated server stopped on its first axolotl tick: "AbstractMethodError: Receiver class ...Axolotl does not define or
 * inherit an implementation of the resolved method 'LivingEntity self()' of interface ...ILivingEntityExtension"
 * (from {@code canDrownInFluidType}). On vanilla no such interface exists, which is why the mod could name it so; on the
 * merged base {@code LivingEntity} declared {@code self()} itself.
 *
 * <p>The method and every call the mixin makes to it, including method handles, take a name of its own; nothing outside
 * the mixin can name a private method, so its behaviour is unchanged and the interface's default is the one every caller
 * of the interface gets, as on NeoForge. Only private, non-static, concrete methods that are neither injectors nor
 * {@code @Shadow}/{@code @Overwrite} members, and only against interfaces the target's hierarchy declares in the game or
 * a carrier. {@code -Dforbric.mixinPrivateDefaults=off} leaves the names as written.
 */
public final class MixinPrivateDefaultCollision {
	public static final String PROPERTY = "forbric.mixinPrivateDefaults";
	private static final Set<String> MEMBER_ANNOTATIONS = Set.of("Lorg/spongepowered/asm/mixin/Shadow;",
			"Lorg/spongepowered/asm/mixin/Overwrite;");

	private MixinPrivateDefaultCollision() { }

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** @return the number of private methods renamed */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> classes) {
		if (!enabled() || (mixin.access & Opcodes.ACC_INTERFACE) != 0) return 0;
		List<String> targets = MixinFit.mixinTargets(mixin);
		if (targets.isEmpty()) return 0;
		Map<String, String> defaults = null;   // name+desc -> the interface declaring it, read only when a candidate exists
		Map<String, String> renamed = new HashMap<>();
		for (MethodNode m : mixin.methods) {
			if (!candidate(m)) continue;
			if (defaults == null) {
				defaults = new HashMap<>();
				for (String target : targets) interfaceMethods(target, classes, defaults);
			}
			String owner = defaults.get(m.name + m.desc);
			if (owner == null) continue;
			String name = m.name + "$forbric$" + Integer.toHexString(mixin.name.hashCode());
			ForbricLog.info("[Forbric/Mixin] guest mixin %s's private %s%s is now %s — %s declares a default of the same name "
					+ "for its target, and a private method merged under that name hides it from every subclass at run time "
					+ "(AbstractMethodError)", mixin.name.replace('/', '.'), m.name, m.desc, name, owner.replace('/', '.'));
			renamed.put(m.name + m.desc, name);
			m.name = name;
		}
		if (renamed.isEmpty()) return 0;
		for (MethodNode m : mixin.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(mixin.name)) {
					String name = renamed.get(call.name + call.desc);
					if (name != null) call.name = name;
				} else if (insn instanceof InvokeDynamicInsnNode indy) {
					for (int i = 0; i < indy.bsmArgs.length; i++) {
						if (indy.bsmArgs[i] instanceof Handle h && h.getOwner().equals(mixin.name)) {
							String name = renamed.get(h.getName() + h.getDesc());
							if (name != null) indy.bsmArgs[i] = new Handle(h.getTag(), h.getOwner(), name, h.getDesc(), h.isInterface());
						}
					}
				}
			}
		}
		return renamed.size();
	}

	/** A private, non-static, concrete method Mixin merges under its own name. */
	static boolean candidate(MethodNode m) {
		if ((m.access & Opcodes.ACC_PRIVATE) == 0 || (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0
				|| m.name.startsWith("<") || MixinFit.injectorOf(m) != null) return false;
		for (List<AnnotationNode> list : List.of(nonNull(m.visibleAnnotations), nonNull(m.invisibleAnnotations))) {
			for (AnnotationNode a : list) if (MEMBER_ANNOTATIONS.contains(a.desc)) return false;
		}
		return true;
	}

	/**
	 * Every default method of every interface the class's hierarchy implements that no class of that hierarchy declares
	 * itself, by name+desc, into {@code out}: the methods only the interface's default answers for.
	 */
	static void interfaceMethods(String className, Function<String, ClassNode> classes, Map<String, String> out) {
		Set<String> seen = new HashSet<>();
		Set<String> declared = new HashSet<>();
		Deque<String> interfaces = new ArrayDeque<>();
		for (String c = className; c != null && seen.add(c); ) {
			ClassNode node = classes.apply(c);
			if (node == null) break;
			for (MethodNode m : node.methods) declared.add(m.name + m.desc);
			if (node.interfaces != null) interfaces.addAll(node.interfaces);
			c = node.superName;
		}
		while (!interfaces.isEmpty()) {
			String name = interfaces.pop();
			if (!seen.add(name)) continue;
			ClassNode node = classes.apply(name);
			if (node == null) continue;
			for (MethodNode m : node.methods) {
				if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT)) == 0 && !m.name.startsWith("<")
						&& !declared.contains(m.name + m.desc)) out.putIfAbsent(m.name + m.desc, name);
			}
			if (node.interfaces != null) interfaces.addAll(node.interfaces);
		}
	}

	private static <T> List<T> nonNull(List<T> list) {
		return list == null ? List.of() : list;
	}
}
