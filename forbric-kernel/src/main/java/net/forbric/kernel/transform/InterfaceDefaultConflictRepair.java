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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Picks a winner when a mixin-added interface and one the merged base already supplies both default the same
 * method, which the JVM refuses to do for itself.
 *
 * <p>Java's rule is that two UNRELATED superinterfaces each supplying a default for one signature is an error the
 * implementor must resolve by overriding. It is not a warning and not a load-time failure either: the class links,
 * and the {@code IncompatibleClassChangeError: Conflicting default methods} is thrown the first time that method is
 * CALLED, from whatever unrelated code happened to call it.
 *
 * <p>On one base nobody hits this, because only one of the two interfaces exists. Forbric's merged base carries all
 * three ecosystems at once AND lets a mod's mixin add interfaces on top, so the pairing is ordinary here:
 * EntityCulling's {@code BlockEntityRendererMixin} adds {@code BlockEntityRenderFabricExtension} to
 * {@code BlockEntityRenderer}, which on the merged base already extends NeoForge's
 * {@code IBlockEntityRendererExtension}, and both declare {@code default AABB getRenderBoundingBox(T)}. Neither
 * {@code CampfireRenderer} nor any other vanilla renderer overrides it, so the 97-jar client reached a lit campfire
 * and died — inside EntityCulling's own tick, on a method neither it nor NeoForge had done anything wrong with. The
 * two bodies are byte-for-byte identical.
 *
 * <p>The repair is the override the JVM asked for: a {@code default} on the common subtype that calls one of the two
 * with {@code invokespecial}, which is exactly what a human would have to write. It goes on the SUBTYPE that
 * inherits both — for the case above that is the {@code BlockEntityRenderer} interface itself, so one method fixes
 * every renderer rather than one per implementor.
 *
 * <p><b>Which one wins:</b> the declarer that is not part of the merged base. A mod that adds an interface with a
 * default added it to supply behaviour, and the base's extension interface is the one that was already there for
 * everybody. When both are the base's own (or both a mod's) the first in declaration order wins, and the choice is
 * logged either way — a silent pick is how a behavioural difference between two bodies would become invisible.
 *
 * <p><b>Runs AFTER mixins, and ONLY on a conflict weaving introduced.</b> The kernel's own chain runs before them,
 * and before them the second interface is not on the class yet. The pre-mixin interface list is the other half of
 * the condition, and it is load-bearing rather than an optimisation: two unrelated interfaces defaulting one
 * signature is a perfectly ordinary shape in a library that WORKS, because the error is only raised for a concrete
 * implementor that overrides neither — and such a library has already settled it in the classes that matter. A
 * first version without the pre-mixin check rewrote five methods of fastutil's {@code FloatList}, which inherits
 * {@code removeIf}, {@code forEach}, {@code stream} and friends from both {@code java.util.List} and
 * {@code FloatCollection}, and would have swapped fastutil's primitive-specialised bodies for the boxing ones.
 * Nothing wove fastutil; that conflict is its own and it is not a defect. Only a signature whose declarers were NOT
 * all reachable before weaving is one the merge and a mixin created together.
 *
 * <p>{@code -Dforbric.defaultConflictRepair=off} leaves the conflict in place.
 */
public final class InterfaceDefaultConflictRepair {
	public static final String SWITCH = "forbric.defaultConflictRepair";

	/** Owners whose interfaces are part of the base rather than something a mod brought. */
	private static final List<String> BASE_PACKAGES =
			List.of("net/minecraft/", "net/neoforged/", "net/minecraftforge/", "com/mojang/");

	private final Function<String, byte[]> classBytes;
	/** internal name → (name+desc → the interface that DECLARES that default, which may be an ancestor). */
	private final Map<String, Map<String, String>> defaults = new ConcurrentHashMap<>();
	private final Set<String> repaired = ConcurrentHashMap.newKeySet();

	public InterfaceDefaultConflictRepair(Function<String, byte[]> classBytes) {
		this.classBytes = classBytes;
	}

	public String name() {
		return "forbric-default-conflict";
	}

	/**
	 * Returns the repaired bytes, or {@code woven} unchanged. Never throws.
	 *
	 * @param original the bytes as they were BEFORE mixin weaving — what decides whether a conflict is new
	 * @param woven    the bytes after it
	 */
	public byte[] transform(String className, byte[] original, byte[] woven) {
		if (woven == null || classBytes == null) return woven;
		if ("off".equalsIgnoreCase(System.getProperty(SWITCH, "on"))) return woven;
		try {
			return repair(className, original, woven);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/DefaultConflict] %s left alone: %s", className, String.valueOf(t));
			return woven;
		}
	}

	/** The interfaces this class had before weaving, transitively. Empty when they cannot be read. */
	private Set<String> interfacesBefore(byte[] original) {
		Set<String> before = new LinkedHashSet<>();
		if (original == null) return before;
		ClassNode node = new ClassNode();
		try {
			new ClassReader(original).accept(node,
					ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (Throwable unreadable) {
			return before;
		}
		if (node.interfaces == null) return before;
		for (String iface : node.interfaces) {
			if (before.add(iface)) before.addAll(allSupers(iface));
		}
		return before;
	}

	private byte[] repair(String className, byte[] original, byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_CODE);
		if (node.interfaces == null || node.interfaces.size() < 2) return bytes;

		// name+desc → the interfaces that DECLARE a default for it, deduplicated. Two superinterfaces that both
		// pass down one ancestor's default are not a conflict: there is a single declaration and the JVM resolves
		// it without help. Keying this on the declarer rather than on the superinterface that carries it is what
		// tells those apart — and nearly everything reachable from a class this deep in Minecraft's hierarchy is
		// that shape, not a real contest.
		Map<String, List<String>> suppliers = new LinkedHashMap<>();
		for (String iface : node.interfaces) {
			for (Map.Entry<String, String> e : defaultsOf(iface).entrySet()) {
				List<String> declarers = suppliers.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
				if (!declarers.contains(e.getValue())) declarers.add(e.getValue());
			}
		}

		Set<String> before = interfacesBefore(original);
		List<String[]> conflicts = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : suppliers.entrySet()) {
			List<String> from = e.getValue();
			if (from.size() < 2) continue;
			// Not ours unless weaving is what brought the declarers together. See the class javadoc: a library
			// whose own interfaces collide like this is working, and rewriting it changes behaviour.
			if (before.containsAll(from)) continue;
			// Related interfaces are ordered by specificity and resolve without help — only unrelated ones are
			// the error Java makes the implementor settle.
			if (anyRelated(from)) continue;
			if (declaresLocally(node, e.getKey())) continue;
			conflicts.add(new String[] { e.getKey(), pick(from), String.join(", ", from) });
		}
		if (conflicts.isEmpty()) return bytes;

		ClassNode full = new ClassNode();
		new ClassReader(bytes).accept(full, ClassReader.SKIP_FRAMES);
		for (String[] conflict : conflicts) {
			add(full, conflict[0], conflict[1]);
			if (repaired.add(full.name + '.' + conflict[0])) {
				ForbricLog.info("[Forbric/DefaultConflict] %s inherits %s as a default from two unrelated "
						+ "interfaces (%s) and overrides neither, which is an IncompatibleClassChangeError the "
						+ "first time anything calls it — gave it one that delegates to %s (-D%s=off to leave it)",
						full.name.replace('/', '.'), conflict[0], conflict[2].replace('/', '.'),
						conflict[1].replace('/', '.'), SWITCH);
			}
		}
		ClassWriter writer = new ClassWriter(0);
		full.accept(writer);
		return writer.toByteArray();
	}

	/** {@code default X m(a…) { return Iface.super.m(a…); }} — the override the JVM asked the implementor for. */
	private static void add(ClassNode node, String nameAndDesc, String iface) {
		int split = nameAndDesc.indexOf('(');
		String name = nameAndDesc.substring(0, split);
		String desc = nameAndDesc.substring(split);
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
		m.visitVarInsn(Opcodes.ALOAD, 0);
		int slot = 1;
		for (Type arg : Type.getArgumentTypes(desc)) {
			m.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
			slot += arg.getSize();
		}
		// invokespecial on the interface: the ONLY way to name one of two competing defaults, and what
		// `Iface.super.m()` compiles to.
		m.visitMethodInsn(Opcodes.INVOKESPECIAL, iface, name, desc, true);
		m.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
		m.visitMaxs(slot + 1, slot);
		node.methods.add(m);
	}

	/** The non-base declarer, else the first: see the class javadoc for why that way round. */
	private static String pick(List<String> from) {
		for (String iface : from) {
			if (!isBase(iface)) return iface;
		}
		return from.get(0);
	}

	private static boolean isBase(String internalName) {
		for (String prefix : BASE_PACKAGES) {
			if (internalName.startsWith(prefix)) return true;
		}
		return false;
	}

	private boolean declaresLocally(ClassNode node, String nameAndDesc) {
		if (node.methods == null) return false;
		for (MethodNode m : node.methods) {
			if ((m.access & Opcodes.ACC_STATIC) != 0) continue;
			if (nameAndDesc.equals(m.name + m.desc)) return true;
		}
		return false;
	}

	/** Whether any of these interfaces is a subtype of another — then specificity already orders them. */
	private boolean anyRelated(List<String> interfaces) {
		for (String a : interfaces) {
			Set<String> supers = allSupers(a);
			for (String b : interfaces) {
				if (!a.equals(b) && supers.contains(b)) return true;
			}
		}
		return false;
	}

	private Set<String> allSupers(String iface) {
		Set<String> seen = new LinkedHashSet<>();
		collectSupers(iface, seen, 0);
		return seen;
	}

	private void collectSupers(String iface, Set<String> into, int depth) {
		if (depth > 16) return;
		byte[] bytes = classBytes.apply(iface);
		if (bytes == null) return;
		ClassNode node = new ClassNode();
		try {
			new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (Throwable unreadable) {
			return;
		}
		if (node.interfaces == null) return;
		for (String parent : node.interfaces) {
			if (into.add(parent)) collectSupers(parent, into, depth + 1);
		}
	}

	/**
	 * name+desc → the interface that DECLARES the default {@code iface} supplies for it, which may be an ancestor.
	 *
	 * <p>Inherited ones are included because the conflict is about what an implementor INHERITS — but they are
	 * recorded under the DECLARER, because two superinterfaces handing down the same ancestor's method is one
	 * declaration and no conflict at all. {@code AbstractMinecartContainer} implements vanilla's
	 * {@code ContainerEntity} and, once Lithium's mixin has run, {@code LithiumInventory}; eleven of their methods
	 * look contested and every one of them is {@code Container}'s single default reached two ways.
	 */
	private Map<String, String> defaultsOf(String iface) {
		Map<String, String> cached = defaults.get(iface);
		if (cached != null) return cached;
		Map<String, String> found = new LinkedHashMap<>();
		collectDefaults(iface, found, 0);
		defaults.put(iface, found);
		return found;
	}

	private void collectDefaults(String iface, Map<String, String> into, int depth) {
		if (depth > 16) return;
		byte[] bytes = classBytes.apply(iface);
		if (bytes == null) return;
		ClassNode node = new ClassNode();
		try {
			new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (Throwable unreadable) {
			return;
		}
		if ((node.access & Opcodes.ACC_INTERFACE) == 0) return;
		if (node.methods != null) {
			for (MethodNode m : node.methods) {
				if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_PRIVATE)) != 0) continue;
				into.putIfAbsent(m.name + m.desc, node.name);
			}
		}
		if (node.interfaces == null) return;
		for (String parent : node.interfaces) collectDefaults(parent, into, depth + 1);
	}
}
