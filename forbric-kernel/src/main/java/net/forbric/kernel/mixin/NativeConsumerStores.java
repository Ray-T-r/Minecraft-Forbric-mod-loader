/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Moves a value modifier written for the one store of a local, where the carrier stores that local several times on paths of
 * its own, to the read the native code makes of it right after that store — so the modifier runs once per execution, on the
 * value that read sees, and its answer is what that read gets, as natively.
 *
 * <p>Vanilla's {@code BucketItem.playEmptySound} stores its sound once — lava's or water's — and hands it straight to
 * {@code playSound}; a {@code @ModifyVariable} at that {@code STORE} decides which sound plays. The merged method asks the
 * fluid type of one carrier first and stores that, stores vanilla's choice only when the type has none, then stores the other
 * carrier's custom sound when it has one: three stores, two or three of them on every use, so the modifier ran two or three
 * times on values vanilla never stores. Every one of those paths reaches the same {@code playSound} read.
 *
 * <p>Nothing here knows a mod, a class or a method by name. A modifier moves when its injector is a {@code @ModifyVariable}
 * with one {@code @At("STORE")} — no ordinal, slice, shift — whose local it names ({@code name} or {@code index}), and:
 * <ul>
 *   <li>the method its selectors bind in the class the mod was compiled against stores that local exactly once, and from the
 *       store to the first read of it there are only reads of other locals and constants (nothing that could look at it);</li>
 *   <li>the same method in the merged class stores the local more often, and holds exactly one read of it inside the same
 *       instructions as the native read — the same reads before it, up to the instruction that consumes it;</li>
 *   <li>that read comes after every merged store, on every path the local holds a value there, and nothing jumps back over
 *       it.</li>
 * </ul>
 * Its point becomes {@code @At("LOAD")} with the ordinal of that read. {@code -Dforbric.nativeConsumerStores=off} moves
 * nothing.
 */
public final class NativeConsumerStores {
	public static final String PROPERTY = "forbric.nativeConsumerStores";
	private static final String MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";

	private NativeConsumerStores() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	public static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives) {
		if (!enabled() || mixin == null || mixin.methods == null || code == null || natives == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		List<String> owners = MixinOverloadPin.targetsOf(mixin);
		if (ecosystem == null || owners.size() != 1) return 0;
		ClassNode original = natives.apply(ecosystem, owners.getFirst());
		if (original == null) return 0;
		ClassNode current = null;
		int moved = 0;
		for (MethodNode handler : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null || !MODIFY_VARIABLE.equals(injector.desc) || MixinFit.value(injector, "slice") != null
					|| MixinFit.groupOf(handler) != null || CurrentBodyOrdinals.counted(handler)) continue;
			List<AnnotationNode> ats = MixinFit.atNodes(injector);
			if (ats.size() != 1) continue;
			AnnotationNode at = ats.getFirst();
			if (!"STORE".equals(MixinFit.asString(MixinFit.value(at, "value"))) || MixinFit.value(at, "ordinal") != null
					|| MixinFit.value(at, "shift") != null || MixinFit.value(at, "by") != null || MixinFit.value(at, "slice") != null) continue;
			if (current == null) current = code.apply(owners.getFirst());
			if (current == null) return moved;
			MethodNode was = MixinTargetSelectors.one(handler, original), host = MixinTargetSelectors.one(handler, current);
			if (was == null || host == null || !was.name.equals(host.name) || !was.desc.equals(host.desc)) continue;
			int nativeSlot = slot(injector, was), mergedSlot = slot(injector, host);
			if (nativeSlot < 0 || mergedSlot < 0) continue;
			int ordinal = consumer(was, nativeSlot, host, mergedSlot, current.name, original.name);
			if (ordinal < 0) continue;
			set(at, "value", "LOAD");
			set(at, "ordinal", ordinal);
			CurrentBodyOrdinals.mark(handler);
			moved++;
			ForbricLog.info("[Forbric/Mixin] %s.%s: its local's one native store is stored again on the merged %s.%s's own paths — the "
					+ "modifier moves to the read the native code makes right after that store (read %d), so it runs once per call, on the "
					+ "value that read sees", mixin.name.replace('/', '.'), handler.name, current.name.replace('/', '.'), host.name, ordinal);
		}
		return moved;
	}

	/** The slot the modifier names in {@code method}: by its {@code index}, or by its one {@code name} in the local variable table. */
	static int slot(AnnotationNode injector, MethodNode method) {
		if (MixinFit.value(injector, "index") instanceof Integer index && index >= 0) return index;
		List<String> names = MixinFit.stringList(MixinFit.value(injector, "name"));
		if (names.size() != 1 || method.localVariables == null) return -1;
		int slot = -1;
		for (LocalVariableNode local : method.localVariables) {
			if (!local.name.equals(names.getFirst())) continue;
			if (slot >= 0 && slot != local.index) return -1;
			slot = local.index;
		}
		return slot;
	}

	/**
	 * The ordinal, among the merged method's reads of {@code mergedSlot}, of the read that is the native method's first read
	 * after its one store of {@code nativeSlot}; -1 where the proof fails or nothing needs moving.
	 */
	static int consumer(MethodNode was, int nativeSlot, MethodNode host, int mergedSlot, String owner, String nativeOwner) {
		List<AbstractInsnNode> n = real(was), m = real(host);
		List<Integer> nativeStores = stores(n, nativeSlot), mergedStores = stores(m, mergedSlot);
		if (nativeStores.size() != 1 || mergedStores.size() <= 1) return -1;
		int store = nativeStores.getFirst(), read = -1;
		for (int i = store + 1; i < n.size(); i++) {
			AbstractInsnNode insn = n.get(i);
			if (insn instanceof VarInsnNode variable && variable.var == nativeSlot) {
				if (load(variable)) read = i;
				break;
			}
			if (!(insn instanceof VarInsnNode variable && load(variable)) && !constant(insn)) break;
		}
		if (read < 0) return -1;
		// The native window: the reads from the store to the read, then on to the instruction that consumes it.
		int consumerAt = consumes(was, nativeOwner, n, read);
		if (consumerAt < 0) return -1;
		List<AbstractInsnNode> window = n.subList(store + 1, consumerAt + 1);
		int offset = read - store - 1, found = -1, ordinal = -1, reads = 0;
		for (int i = 0; i < m.size(); i++) {
			if (!(m.get(i) instanceof VarInsnNode variable && variable.var == mergedSlot && load(variable))) continue;
			int start = i - offset;
			if (start >= 0 && start + window.size() <= m.size() && matches(window, m.subList(start, start + window.size()), nativeSlot, mergedSlot)) {
				if (found >= 0) return -1;
				found = i;
				ordinal = reads;
			}
			reads++;
		}
		if (found < 0) return -1;
		for (int s : mergedStores) if (s > found) return -1;
		// Nothing jumps back over the read (a loop would read it more than once per store pass).
		java.util.Map<LabelNode, Integer> labels = labels(host, m);
		for (int i = found; i < m.size(); i++) for (int t : targets(m.get(i), labels)) if (t <= found) return -1;
		// Every path that reaches the read stored the local first.
		Frame<BasicValue>[] frames = frames(owner, host);
		if (frames == null) return -1;
		Frame<BasicValue> at = frames[host.instructions.indexOf(m.get(found))];
		if (at == null || mergedSlot >= at.getLocals() || at.getLocal(mergedSlot) == BasicValue.UNINITIALIZED_VALUE) return -1;
		return ordinal;
	}

	/** The index of the instruction that pops the value {@code read} pushed, scanning straight on; -1 if it cannot be told. */
	private static int consumes(MethodNode method, String owner, List<AbstractInsnNode> real, int read) {
		Frame<BasicValue>[] frames = frames(owner, method);
		if (frames == null) return -1;
		Frame<BasicValue> after = frames[method.instructions.indexOf(real.get(read))];
		if (after == null) return -1;
		int depth = after.getStackSize() + 1;   // the read's value sits at this depth once it is pushed
		for (int i = read + 1; i < real.size(); i++) {
			Frame<BasicValue> frame = frames[method.instructions.indexOf(real.get(i))];
			if (frame == null) return -1;
			if (i + 1 < real.size()) {
				Frame<BasicValue> next = frames[method.instructions.indexOf(real.get(i + 1))];
				if (next == null) return -1;
				if (next.getStackSize() < depth) return i;
			} else return i;
			if (real.get(i) instanceof JumpInsnNode || real.get(i) instanceof TableSwitchInsnNode || real.get(i) instanceof LookupSwitchInsnNode) return i;
		}
		return -1;
	}

	private static boolean matches(List<AbstractInsnNode> window, List<AbstractInsnNode> merged, int nativeSlot, int mergedSlot) {
		for (int k = 0; k < window.size(); k++) {
			AbstractInsnNode a = window.get(k), b = merged.get(k);
			if (a instanceof VarInsnNode x && b instanceof VarInsnNode y) {
				if (x.getOpcode() != y.getOpcode()) return false;
				if (x.var == nativeSlot ? y.var != mergedSlot : x.var != y.var) return false;
			} else if (!NativeDecisionIsland.same(a, b)) return false;
		}
		return true;
	}

	private static List<Integer> stores(List<AbstractInsnNode> real, int slot) {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < real.size(); i++)
			if (real.get(i) instanceof VarInsnNode variable && variable.var == slot && variable.getOpcode() >= Opcodes.ISTORE && variable.getOpcode() <= Opcodes.ASTORE)
				out.add(i);
		return out;
	}

	private static boolean load(VarInsnNode variable) {
		return variable.getOpcode() >= Opcodes.ILOAD && variable.getOpcode() <= Opcodes.ALOAD;
	}

	private static boolean constant(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		return op >= Opcodes.ACONST_NULL && op <= Opcodes.DCONST_1 || op == Opcodes.BIPUSH || op == Opcodes.SIPUSH || op == Opcodes.LDC;
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		return NativeDecisionIsland.real(method);
	}

	private static java.util.Map<LabelNode, Integer> labels(MethodNode method, List<AbstractInsnNode> real) {
		java.util.Map<LabelNode, Integer> out = new java.util.IdentityHashMap<>();
		List<LabelNode> pending = new ArrayList<>();
		int index = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof LabelNode label) pending.add(label);
			else if (insn.getOpcode() >= 0) {
				for (LabelNode label : pending) out.put(label, index);
				pending.clear();
				index++;
			}
		}
		for (LabelNode label : pending) out.put(label, real.size());
		return out;
	}

	private static List<Integer> targets(AbstractInsnNode insn, java.util.Map<LabelNode, Integer> labels) {
		List<Integer> out = new ArrayList<>();
		if (insn instanceof JumpInsnNode jump) out.add(labels.getOrDefault(jump.label, -1));
		else if (insn instanceof TableSwitchInsnNode table) {
			out.add(labels.getOrDefault(table.dflt, -1));
			for (LabelNode label : table.labels) out.add(labels.getOrDefault(label, -1));
		} else if (insn instanceof LookupSwitchInsnNode lookup) {
			out.add(labels.getOrDefault(lookup.dflt, -1));
			for (LabelNode label : lookup.labels) out.add(labels.getOrDefault(label, -1));
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Frame<BasicValue>[] frames(String owner, MethodNode method) {
		try {
			return new Analyzer<>(new BasicInterpreter()).analyze(owner, method);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			return null;
		}
	}

	private static void set(AnnotationNode at, String key, Object value) {
		if (at.values == null) at.values = new ArrayList<>();
		for (int i = 0; i + 1 < at.values.size(); i += 2) {
			if (key.equals(at.values.get(i))) {
				at.values.set(i + 1, value);
				return;
			}
		}
		at.values.add(key);
		at.values.add(value);
	}
}
