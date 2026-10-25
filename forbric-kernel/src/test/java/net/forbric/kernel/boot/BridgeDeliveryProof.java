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
 * ({@link CarrierCallGraph}, the kernel as the only root).
 *
 * <p>What the delivery posts is then, in the kernel's own methods, an event it constructs that is handed to a bus's
 * {@code post} or {@code fire} — directly, through locals, through a kernel method that posts that parameter, a kernel
 * method that returns it, or a lambda capturing it — and, in the carriers, an event a family method constructs (the family's
 * own code posts what it constructs). Until 2026-10-25 an event the kernel merely constructed counted: a drown bridge whose
 * {@code post} was taken out still "posted" MinecraftForge's drown event. And the carriers are followed for what the
 * delivery posts itself: an event's constructor and instance methods are not followed ({@link CarrierCallGraph.Reach}), so
 * what constructing one event sets off (MinecraftForge's {@code BreakEvent} asks for a harvest check) is not filed under the
 * bridge that posts it.
 */
final class BridgeDeliveryProof {
	static final String MULTIPLEXER = "net/forbric/kernel/boot/GameEventMultiplexer";
	private static final String INSTALL_DESC = "(Ljava/lang/ClassLoader;Ljava/lang/Object;Lnet/forbric/api/GameEventBridge;"
			+ "Lnet/forbric/kernel/boot/GameEventMultiplexer$Setup;)V";
	private static final String BRIDGE = "net/forbric/api/GameEventBridge";
	private static final String RUNTIME = "net/forbric/kernel/runtime/";

	private final Map<String, ClassNode> kernel = new HashMap<>();
	private final CarrierCallGraph carriers;
	private final List<Path> kernelRoots;

	BridgeDeliveryProof(List<Path> kernelRoots, List<Path> carrierJars) throws IOException {
		for (Path root : kernelRoots) {
			CarrierCallGraph.forEachClass(root, null, node -> kernel.put(node.name, node));
		}
		this.kernelRoots = List.copyOf(kernelRoots);
		this.carriers = CarrierCallGraph.of(carrierJars);
	}

	/**
	 * What the kernel as a whole posts, for a row a repair rather than one bridge delivers: every kernel method's posted
	 * constructions ({@link #postedBy}), and what the carrier methods the kernel's code reaches construct — its calls by
	 * reflection included, read as the census reads them ({@code PayloadInterop} gathers MinecraftForge's configuration tasks
	 * that way) — an event's constructor and instance methods left unfollowed.
	 */
	Set<String> postedByTheKernel() throws IOException {
		Set<String> out = new TreeSet<>();
		for (String id : allMethods()) out.addAll(postedBy(id));
		CarrierCallGraph.Body body = new CarrierCallGraph.Body();
		for (Path root : kernelRoots) carriers.readBodies(root, Set.of(), null, body, false, true);
		carriers.resolveReflection(body);
		out.addAll(carriers.reach(Map.of(body, CarrierCallGraph.KERNEL), false, true).constructed());
		return out;
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

	/**
	 * The NeoForge events a delivery listens to: event classes of NeoForge's namespace the entries name as class literals,
	 * or take as a lambda's or method reference's parameter (the forward), in the entry methods themselves.
	 */
	Set<String> listensTo(Collection<String> entries) {
		Set<String> out = new TreeSet<>();
		for (String id : entries) {
			MethodNode m = method(id);
			if (m == null) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof org.objectweb.asm.Type t && t.getSort() == org.objectweb.asm.Type.OBJECT
						&& t.getInternalName().startsWith("net/neoforged/") && carriers.isEvent(t.getInternalName())) out.add(t.getInternalName());
				if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null && indy.bsmArgs.length >= 3
						&& indy.bsmArgs[2] instanceof org.objectweb.asm.Type instantiated) {
					for (org.objectweb.asm.Type p : instantiated.getArgumentTypes()) {
						if (p.getSort() == org.objectweb.asm.Type.OBJECT && p.getInternalName().startsWith("net/neoforged/")
								&& carriers.isEvent(p.getInternalName())) out.add(p.getInternalName());
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
	 * The event types {@code entries} (kernel method ids) post: in the kernel's methods they reach through calls and method
	 * handles, each construction that flows into a post ({@link #postedBy}); and in the carrier methods those reach with the
	 * kernel as the only root, each construction, an event's own constructor and instance methods left unfollowed.
	 */
	Set<String> posts(Collection<String> entries) {
		CarrierCallGraph.Body body = new CarrierCallGraph.Body();
		Set<String> posted = new TreeSet<>(), seen = new HashSet<>();
		ArrayDeque<String> work = new ArrayDeque<>(entries);
		while (!work.isEmpty()) {
			String at = work.removeFirst();
			if (!seen.add(at)) continue;
			MethodNode m = method(at);
			if (m == null) continue;
			posted.addAll(postedBy(at));
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				// Its constructions instantiate types for the carriers' dispatch; they count as posted only through postedBy.
				CarrierCallGraph.touches(insn, body.initializes, body.constructs);
				CarrierCallGraph.refs(insn, ref -> {
					if (kernel.containsKey(ref.owner())) work.add(ref.owner() + "." + ref.nameDesc());
					else body.refs.add(ref);
				});
			}
		}
		posted.addAll(carriers.reach(Map.of(body, CarrierCallGraph.KERNEL), false, true).constructed());
		return posted;
	}

	/** Every kernel method: what the kernel as a whole posts, for a row a repair rather than one bridge delivers. */
	Set<String> allMethods() {
		Set<String> out = new TreeSet<>();
		for (ClassNode node : kernel.values()) for (MethodNode m : node.methods) if (m.instructions != null) out.add(node.name + "." + m.name + m.desc);
		return out;
	}

	// ---- What a kernel method posts: constructions that flow into a bus's post ----

	/** The bus types whose {@code post} or {@code fire} hands an event to its listeners. */
	private static final Set<String> BUSES = Set.of("net/minecraftforge/eventbus/api/bus/EventBus",
			"net/minecraftforge/eventbus/api/bus/CancellableEventBus", "net/neoforged/bus/api/IEventBus");

	private MethodNode method(String id) {
		int dot = id.indexOf('.');
		ClassNode node = kernel.get(id.substring(0, dot));
		if (node == null) return null;
		for (MethodNode m : node.methods) if ((m.name + m.desc).equals(id.substring(dot + 1)) && m.instructions != null) return m;
		return null;
	}

	private final Map<String, org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[]> frames = new HashMap<>();

	private org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames(String id, MethodNode m) {
		return frames.computeIfAbsent(id, k -> {
			try {
				return new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.SourceInterpreter())
						.analyze(id.substring(0, id.indexOf('.')), m);
			} catch (org.objectweb.asm.tree.analysis.AnalyzerException unanalyzable) {
				return null;
			}
		});
	}

	/** Where a value came from: an event constructed here ({@code type}), or the method's parameter {@code parameter}. */
	private record Origin(String type, int parameter) {
	}

	/** A method's flow summary: the parameters it posts, the parameters it returns, the constructions it returns, what it posts. */
	private record Summary(Set<Integer> postsParameters, Set<Integer> returnsParameters, Set<String> returnsConstructed,
			Set<String> posted) {
	}

	private final Map<String, Summary> summaries = new HashMap<>();
	private static final Summary EMPTY = new Summary(Set.of(), Set.of(), Set.of(), Set.of());

	/** The event types kernel method {@code id} constructs, or gets back constructed from a kernel method it calls, and posts. */
	Set<String> postedBy(String id) {
		return summary(id).posted();
	}

	private Summary summary(String id) {
		Summary done = summaries.get(id);
		if (done != null) return done;
		summaries.put(id, EMPTY); // a recursive call sees nothing yet
		MethodNode m = method(id);
		var frames = m == null ? null : frames(id, m);
		if (frames == null) return EMPTY;
		Set<Integer> postsParameters = new TreeSet<>(), returnsParameters = new TreeSet<>();
		Set<String> returnsConstructed = new TreeSet<>(), posted = new TreeSet<>();
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			var frame = frames[m.instructions.indexOf(insn)];
			if (frame == null) continue;
			if (insn.getOpcode() == Opcodes.ARETURN) {
				for (Origin o : origins(id, m, frames, frame.getStack(frame.getStackSize() - 1), new HashSet<>())) {
					if (o.type() != null) returnsConstructed.add(o.type());
					else returnsParameters.add(o.parameter());
				}
				continue;
			}
			// Each argument this call posts: a bus's post, a kernel method posting that parameter, a lambda posting a capture.
			List<Integer> postedArgs = new ArrayList<>();
			int argCount = 0;
			if (insn instanceof MethodInsnNode call) {
				argCount = org.objectweb.asm.Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
				int receiver = call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
				if (BUSES.contains(call.owner) && (call.name.equals("post") || call.name.equals("fire"))) postedArgs.add(receiver);
				else if (kernel.containsKey(call.owner)) {
					for (int p : summary(call.owner + "." + call.name + call.desc).postsParameters()) postedArgs.add(p + receiver);
				}
			} else if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null && indy.bsmArgs.length >= 2
					&& indy.bsmArgs[1] instanceof Handle impl && kernel.containsKey(impl.getOwner())) {
				argCount = org.objectweb.asm.Type.getArgumentTypes(indy.desc).length;
				int receiver = impl.getTag() == Opcodes.H_INVOKESTATIC ? 0 : 1;
				for (int p : summary(impl.getOwner() + "." + impl.getName() + impl.getDesc()).postsParameters()) {
					if (p + receiver < argCount) postedArgs.add(p + receiver);
				}
			}
			for (int arg : postedArgs) {
				for (Origin o : origins(id, m, frames, frame.getStack(frame.getStackSize() - argCount + arg), new HashSet<>())) {
					if (o.type() != null) posted.add(o.type());
					else postsParameters.add(o.parameter());
				}
			}
		}
		Summary out = new Summary(postsParameters, returnsParameters, returnsConstructed, posted);
		summaries.put(id, out);
		return out;
	}

	/**
	 * Where {@code value} came from, followed back through copies, casts, stores and loads, and through a kernel method that
	 * returns its argument or a construction of its own.
	 */
	private Set<Origin> origins(String id, MethodNode m, org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames,
			org.objectweb.asm.tree.analysis.SourceValue value, Set<AbstractInsnNode> seen) {
		Set<Origin> out = new HashSet<>();
		if (value == null) return out;
		for (AbstractInsnNode source : value.insns) {
			if (!seen.add(source)) continue;
			var frame = frames[m.instructions.indexOf(source)];
			if (frame == null) continue;
			int opcode = source.getOpcode();
			if (source instanceof org.objectweb.asm.tree.TypeInsnNode t && opcode == Opcodes.NEW) {
				out.add(new Origin(t.desc, -1));
			} else if (opcode == Opcodes.DUP || opcode == Opcodes.CHECKCAST || opcode == Opcodes.ASTORE) {
				if (frame.getStackSize() > 0) out.addAll(origins(id, m, frames, frame.getStack(frame.getStackSize() - 1), seen));
			} else if (source instanceof org.objectweb.asm.tree.VarInsnNode load && opcode == Opcodes.ALOAD) {
				var local = frame.getLocal(load.var);
				if (local.insns.isEmpty()) {
					int parameter = parameterAt(m, load.var);
					if (parameter >= 0) out.add(new Origin(null, parameter));
				} else {
					out.addAll(origins(id, m, frames, local, seen));
				}
			} else if (source instanceof MethodInsnNode call && kernel.containsKey(call.owner)) {
				Summary callee = summary(call.owner + "." + call.name + call.desc);
				for (String type : callee.returnsConstructed()) out.add(new Origin(type, -1));
				int argCount = org.objectweb.asm.Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
				int receiver = call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
				for (int p : callee.returnsParameters()) {
					out.addAll(origins(id, m, frames, frame.getStack(frame.getStackSize() - argCount + p + receiver), seen));
				}
			}
		}
		return out;
	}

	/** The index of the parameter {@code slot} holds on entry ({@code this} is -1), or -1. */
	private static int parameterAt(MethodNode m, int slot) {
		int at = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
		if (at == 1 && slot == 0) return -1;
		org.objectweb.asm.Type[] params = org.objectweb.asm.Type.getArgumentTypes(m.desc);
		for (int i = 0; i < params.length; i++) {
			if (at == slot) return i;
			at += params[i].getSize();
		}
		return -1;
	}

	/** Whether a class directory holds the kernel's compiled classes. */
	static boolean compiled(Path dir) {
		return Files.isDirectory(dir);
	}
}
