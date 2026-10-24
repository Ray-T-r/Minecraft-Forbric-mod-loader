/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.forbric.api.GameEventBridge;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * What a bridge or a repair the dead-event tables name actually posts, read from the kernel's bytecode.
 *
 * <p>A {@link DeadEventAudit#BRIDGED}, {@link DeadEventAudit#STAND_DOWN} or {@link DeadEventAudit#REPAIRED} row silences the
 * audit for its event: a mod waiting for it is never named. Until 2026-10-25 nothing checked that the row's delivery
 * exists — the event could be filed under any bridge, or under REPAIRED, and the suite stayed green. This reads the
 * delivery the row claims:
 * <ul>
 *   <li>a bridge {@link GameEventMultiplexer} installs: the {@code install(cl, bus, BRIDGE, setup)} call in its bytecode, the
 *       runtime class and entry its setup names (which must exist, be public static, and be declared to
 *       {@link KernelRuntimeClasses}, the boot-time seam check), and what that entry reaches;</li>
 *   <li>a bridge a transformer or a runtime class lands: every kernel method that records it
 *       ({@code EventBridges.installed(BRIDGE)}), the runtime classes it names, and what they reach;</li>
 * </ul>
 * and "reaches" is followed through the kernel's own methods (calls and method handles) and then through the carriers
 * ({@link CarrierCallGraph}, the kernel as the only root): the event types constructed on the way are what the delivery
 * posts.
 */
final class BridgeDeliveryProof {
	static final String MULTIPLEXER = "net/forbric/kernel/boot/GameEventMultiplexer";
	private static final String INSTALL_DESC = "(Ljava/lang/ClassLoader;Ljava/lang/Object;Lnet/forbric/api/GameEventBridge;"
			+ "Lnet/forbric/kernel/boot/GameEventMultiplexer$Setup;)V";
	private static final String BRIDGE = "net/forbric/api/GameEventBridge";
	private static final String RUNTIME = "net/forbric/kernel/runtime/";

	private final Map<String, ClassNode> kernel = new HashMap<>();
	private final CarrierCallGraph carriers;

	BridgeDeliveryProof(List<Path> kernelRoots, List<Path> carrierJars) throws IOException {
		for (Path root : kernelRoots) {
			CarrierCallGraph.forEachClass(root, null, node -> kernel.put(node.name, node));
		}
		this.carriers = CarrierCallGraph.of(carrierJars);
	}

	/** A bridge's entry: the runtime class and the method its delivery starts from. */
	record Entry(String owner, String method) {
	}

	/** Each bridge {@link GameEventMultiplexer} installs → the runtime entry its setup calls. */
	Map<GameEventBridge, Entry> multiplexerInstalls() {
		Map<GameEventBridge, Entry> out = new LinkedHashMap<>();
		ClassNode mux = kernel.get(MULTIPLEXER);
		if (mux == null) return out;
		for (MethodNode m : mux.methods) {
			if (m.instructions == null) continue;
			String bridge = null;
			Handle lambda = null;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.owner.equals(BRIDGE)) bridge = f.name;
				if (insn instanceof InvokeDynamicInsnNode indy) {
					for (Object arg : indy.bsmArgs) if (arg instanceof Handle h && h.getOwner().equals(MULTIPLEXER)) lambda = h;
				}
				if (insn instanceof MethodInsnNode call && call.owner.equals(MULTIPLEXER) && call.name.equals("install")
						&& call.desc.equals(INSTALL_DESC) && bridge != null && lambda != null) {
					Entry entry = entryOf(mux, lambda);
					if (entry != null) out.put(GameEventBridge.valueOf(bridge), entry);
					bridge = null;
					lambda = null;
				}
			}
		}
		return out;
	}

	/** The runtime class and method a setup lambda names: string constants in it and in the multiplexer helpers it calls. */
	private Entry entryOf(ClassNode mux, Handle lambda) {
		List<String> strings = new ArrayList<>();
		ArrayDeque<String> work = new ArrayDeque<>(List.of(lambda.getName() + lambda.getDesc()));
		Set<String> seen = new HashSet<>();
		while (!work.isEmpty()) {
			String at = work.removeFirst();
			if (!seen.add(at)) continue;
			for (MethodNode m : mux.methods) {
				if (!(m.name + m.desc).equals(at) || m.instructions == null) continue;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) strings.add(s);
					if (insn instanceof MethodInsnNode call && call.owner.equals(MULTIPLEXER)) work.add(call.name + call.desc);
				}
			}
		}
		for (String s : strings) {
			if (!s.startsWith(RUNTIME.replace('/', '.'))) continue;
			String owner = s.replace('.', '/');
			ClassNode runtime = kernel.get(owner);
			if (runtime == null) return new Entry(owner, null);
			for (String name : strings) {
				for (MethodNode m : runtime.methods) {
					if (m.name.equals(name) && (m.access & Opcodes.ACC_STATIC) != 0 && (m.access & Opcodes.ACC_PUBLIC) != 0) {
						return new Entry(owner, m.name + m.desc);
					}
				}
			}
			return new Entry(owner, null);
		}
		return null;
	}

	/** Each bridge a kernel method records with {@code EventBridges.installed(BRIDGE)} → those methods, the multiplexer's left out. */
	Map<GameEventBridge, Set<String>> recorders() {
		Map<GameEventBridge, Set<String>> out = new TreeMap<>();
		for (ClassNode node : kernel.values()) {
			if (node.name.equals(MULTIPLEXER)) continue;
			for (MethodNode m : node.methods) {
				if (m.instructions == null) continue;
				String bridge = null;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.owner.equals(BRIDGE)) bridge = f.name;
					if (insn instanceof MethodInsnNode call && call.owner.equals("net/forbric/api/EventBridges")
							&& call.name.equals("installed") && call.desc.equals("(L" + BRIDGE + ";)V") && bridge != null) {
						out.computeIfAbsent(GameEventBridge.valueOf(bridge), k -> new TreeSet<>()).add(node.name + "." + m.name + m.desc);
						bridge = null;
					}
				}
			}
		}
		return out;
	}

	/**
	 * The kernel entries a recorder stands for: a runtime class recording its own bridge is the bridge, every method of it;
	 * a boot-side transformer recording one routes the game into the runtime classes it names (string constants in the
	 * recording method and the methods of its class it calls), every public static method of them.
	 */
	Set<String> entriesOf(String recorder) {
		String owner = recorder.substring(0, recorder.indexOf('.'));
		Set<String> out = new TreeSet<>();
		if (owner.startsWith(RUNTIME)) {
			for (MethodNode m : kernel.get(owner).methods) out.add(owner + "." + m.name + m.desc);
			return out;
		}
		ClassNode node = kernel.get(owner);
		ArrayDeque<String> work = new ArrayDeque<>(List.of(recorder.substring(recorder.indexOf('.') + 1)));
		Set<String> seen = new HashSet<>();
		while (!work.isEmpty()) {
			String at = work.removeFirst();
			if (!seen.add(at)) continue;
			for (MethodNode m : node.methods) {
				if (!(m.name + m.desc).equals(at) || m.instructions == null) continue;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
						ClassNode runtime = kernel.get(s.replace('.', '/'));
						if (runtime != null && runtime.name.startsWith(RUNTIME)) {
							for (MethodNode r : runtime.methods) {
								if ((r.access & Opcodes.ACC_STATIC) != 0 && (r.access & Opcodes.ACC_PUBLIC) != 0) out.add(runtime.name + "." + r.name + r.desc);
							}
						}
					}
					if (insn instanceof MethodInsnNode call && call.owner.equals(owner)) work.add(call.name + call.desc);
				}
			}
		}
		return out;
	}

	/**
	 * The methods of runtime class {@code owner} a boot-side transformer routes the game into: a boot-side method holding
	 * {@code owner}'s internal name and the name of one of its public static methods. {@code KernelLootBridge.install} only
	 * puts MinecraftForge's link into the chain; the game reaches {@code loadLootTable}, where the event is posted, because
	 * {@code LootTableEventBridgeInjector} routes it there.
	 */
	Set<String> routedInto(String owner) {
		Set<String> out = new TreeSet<>();
		ClassNode runtime = kernel.get(owner);
		if (runtime == null) return out;
		for (ClassNode node : kernel.values()) {
			if (node.name.startsWith(RUNTIME)) continue;
			for (MethodNode m : node.methods) {
				if (m.instructions == null) continue;
				List<String> strings = new ArrayList<>();
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) strings.add(s);
				}
				if (!strings.contains(owner)) continue;
				for (MethodNode r : runtime.methods) {
					if ((r.access & Opcodes.ACC_STATIC) != 0 && (r.access & Opcodes.ACC_PUBLIC) != 0 && strings.contains(r.name)) {
						out.add(owner + "." + r.name + r.desc);
					}
				}
			}
		}
		return out;
	}

	/** Whether {@code entry}'s method exists on its runtime class as a public static method. */
	boolean exists(Entry entry) {
		ClassNode node = entry == null || entry.method() == null ? null : kernel.get(entry.owner());
		if (node == null) return false;
		for (MethodNode m : node.methods) {
			if ((m.name + m.desc).equals(entry.method())) return (m.access & Opcodes.ACC_STATIC) != 0 && (m.access & Opcodes.ACC_PUBLIC) != 0;
		}
		return false;
	}

	/**
	 * The types {@code entries} (kernel method ids) construct: in the kernel's methods they reach through calls and method
	 * handles, and in the carrier methods those reach with the kernel as the only root.
	 */
	Set<String> posts(Collection<String> entries) {
		CarrierCallGraph.Body body = new CarrierCallGraph.Body();
		Set<String> constructed = new TreeSet<>(), seen = new HashSet<>();
		ArrayDeque<String> work = new ArrayDeque<>(entries);
		while (!work.isEmpty()) {
			String at = work.removeFirst();
			if (!seen.add(at)) continue;
			int dot = at.indexOf('.');
			ClassNode node = kernel.get(at.substring(0, dot));
			if (node == null) continue;
			for (MethodNode m : node.methods) {
				if (!(m.name + m.desc).equals(at.substring(dot + 1)) || m.instructions == null) continue;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					CarrierCallGraph.touches(insn, body.initializes, body.constructs);
					CarrierCallGraph.refs(insn, ref -> {
						if (kernel.containsKey(ref.owner())) work.add(ref.owner() + "." + ref.nameDesc());
						else body.refs.add(ref);
					});
				}
			}
		}
		constructed.addAll(body.constructs);
		constructed.addAll(carriers.reach(Map.of(body, CarrierCallGraph.KERNEL), false).constructed());
		return constructed;
	}

	/** Whether a class directory holds the kernel's compiled classes. */
	static boolean compiled(Path dir) {
		return Files.isDirectory(dir);
	}
}
