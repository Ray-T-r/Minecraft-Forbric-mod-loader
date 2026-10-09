/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

/**
 * Proves, by abstract interpretation of the bytecode, that a wrap of vanilla's {@code activeEffects.clear()} in
 * {@code LivingEntity.removeAllEffects} is a per-effect veto: whatever the handler does to the map is, effect by effect,
 * "keep it or not", decided from that effect alone. Then running the handler on a map holding one effect answers the
 * question NeoForge's per-effect hook asks — which is how {@link FabricEntityMixinAnchors} re-hosts it.
 *
 * <p>What is proved, not what it looks like. The handler may stream and filter, loop over a snapshot, {@code removeIf},
 * keep the retained effects in its own map, a set or a list, call or skip the original clear, capture any number of the
 * host's map locals, and be written by javac or kotlinc. It is rejected when anything could observe more than one
 * effect at once or happen once per clear rather than once per effect:
 * <ul>
 *   <li>a call the model does not know (the question, a listener, a mod's API) outside the per-effect parts — a lambda
 *       run over the original effects, or a loop over an iterator of them;</li>
 *   <li>any such call in the bookkeeping over the decided effects (the retained map's own loops and lambdas);</li>
 *   <li>a value carried from one effect to the next (a local written in a loop and live at its head, an element of an
 *       array made outside the loop and written in it), an aggregate
 *       ({@code size}, {@code isEmpty}, {@code count}, {@code anyMatch}) that reaches a decision, a collection handed
 *       to an unknown call, a field write, a throw, a try block;</li>
 *   <li>an effect that is not the one being decided: one taken from an iterator outside the loop that iterates it
 *       (the first effect, {@code iterator().next()}), a second one taken in the same round, one taken inside a lambda
 *       run per effect — used for anything at all, a comparison, a test, a lookup, a removal through its iterator;
 *       and whether more effects follow ({@code hasNext()}) deciding anything but the end of its own loop, which may
 *       be left no other way ({@code break} or {@code return} out of it decides later effects from earlier ones);</li>
 *   <li>anything put into the effect map that is not one of its own original entries, any growth of a host local, and
 *       the original operation called other than once, outside loops, on the effect map itself.</li>
 * </ul>
 * The JDK collection and stream operations, Kotlin's null checks, Guava's copying factories and
 * {@code MobEffectInstance.getEffect} (an effect's own key) are modelled; nothing is recognised by a mod's name or its
 * instruction sequence.
 */
final class ClearVetoProof {
	private static final String OP = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String ENTRY = "java/util/Map$Entry";

	private ClearVetoProof() {
	}

	/**
	 * The proof's answer.
	 *
	 * @param why       null when proved, else the first reason it is not
	 * @param predicate when the whole question is one static filter predicate over the effect map's entries whose true
	 *                  means "keep" and whose captures are all the entity, that predicate; else null (replay the handler)
	 * @param opCalls   the original-operation calls to turn into a plain {@code clear()} of their map
	 */
	record Verdict(String why, Handle predicate, List<MethodInsnNode> opCalls) {
		boolean proved() {
			return why == null;
		}
	}

	/**
	 * Kinds of abstract value. {@code PICK} is anything computed from an effect that is not the one being decided (one
	 * taken once per clear, or a second one in a round), reference or primitive; {@code MORE} is an iterator's
	 * {@code hasNext()}, which may decide only the end of that iterator's own loop.
	 */
	enum K { UNINIT, PRIM, SIZE, THIS, OPQ, OP, SRC, TMAP, TCOLL, VIEW, ITER, STREAM, ELEM, ARRAY, LAMBDA, COLLECTOR, PICK, MORE, TOP }

	enum Part { ENTRY, KEY, VALUE }

	enum Dom { SOURCE, DECIDED }

	enum Ctx { MAIN, QUESTION, PLUMBING, PROJECTION }

	/** An abstract value. {@code site} identifies allocations; {@code extra} carries a base collection, captures or lambdas. */
	record V(K kind, int size, Object site, Part part, Dom dom, List<Object> extra) implements Value {
		@Override public int getSize() {
			return size;
		}

		static V of(K kind) {
			return new V(kind, 1, null, null, null, List.of());
		}

		static V prim(int size) {
			return new V(K.PRIM, size, null, null, null, List.of());
		}

		static V elem(Part part) {
			return new V(K.ELEM, 1, null, part, null, List.of());
		}

		static V pick(int size) {
			return new V(K.PICK, size, null, null, null, List.of());
		}

		/** Site identity by instruction object, not by its (mutable) contents. */
		@Override public boolean equals(Object o) {
			return o instanceof V v && kind == v.kind && size == v.size && sameSite(site, v.site) && part == v.part && dom == v.dom && extra.equals(v.extra);
		}

		@Override public int hashCode() {
			return Objects.hash(kind, size, site instanceof AbstractInsnNode ? System.identityHashCode(site) : Objects.hashCode(site), part, dom, extra);
		}

		private static boolean sameSite(Object a, Object b) {
			return a instanceof AbstractInsnNode || b instanceof AbstractInsnNode ? a == b : Objects.equals(a, b);
		}
	}

	/** A collection the handler made: a copy of the original effects, or one it filled from its decisions. */
	static final class Temp {
		final boolean map;
		Dom dom = Dom.DECIDED;
		Part part;
		List<Handle> filters = List.of();

		Temp(boolean map) {
			this.map = map;
		}
	}

	/**
	 * The handler's control flow, known before its values are: each instruction's strongly connected component, whether
	 * it lies on a loop, and how many {@code Iterator.next()} calls each loop makes.
	 */
	record Cfg(List<List<Integer>> succ, int[] comp, boolean[] cyclic, Map<Integer, Integer> nexts) {
		static Cfg of(String owner, MethodNode m) throws AnalyzerException {
			int n = m.instructions.size();
			List<List<Integer>> succ = new ArrayList<>();
			for (int i = 0; i < n; i++) succ.add(new ArrayList<>());
			new Analyzer<BasicValue>(new BasicInterpreter()) {
				@Override protected void newControlFlowEdge(int from, int to) {
					succ.get(from).add(to);
				}

				@Override protected boolean newControlFlowExceptionEdge(int from, int to) {
					succ.get(from).add(to);
					return true;
				}
			}.analyze(owner, m);
			int[] comp = scc(succ, n);
			Map<Integer, Integer> sizes = new HashMap<>();
			for (int c : comp) sizes.merge(c, 1, Integer::sum);
			boolean[] cyclic = new boolean[n];
			for (int i = 0; i < n; i++) {
				cyclic[i] = sizes.get(comp[i]) > 1;
				for (int s : succ.get(i)) if (s == i) cyclic[i] = true;
			}
			Map<Integer, Integer> nexts = new HashMap<>();
			for (int i = 0; i < n; i++)
				if (cyclic[i] && m.instructions.get(i) instanceof MethodInsnNode c && c.owner.equals("java/util/Iterator") && c.name.equals("next"))
					nexts.merge(comp[i], 1, Integer::sum);
			return new Cfg(succ, comp, cyclic, nexts);
		}

		/** Whether a {@code next()} here takes the effect of a round: it is the one {@code next()} of a loop. */
		boolean round(int i) {
			return i >= 0 && cyclic[i] && nexts.getOrDefault(comp[i], 0) == 1;
		}
	}

	/** What one proof run learned, across the handler and every lambda it analysed. */
	static final class Facts {
		final ClassNode mixin;
		final MethodNode main;
		final Cfg cfg;
		final Set<String> violations = new LinkedHashSet<>();
		final Map<AbstractInsnNode, Temp> temps = new IdentityHashMap<>();
		final Map<AbstractInsnNode, Set<V>> arrays = new IdentityHashMap<>();
		final Map<AbstractInsnNode, Set<AbstractInsnNode>> arrayStores = new IdentityHashMap<>();
		final Set<AbstractInsnNode> opaqueMain = Collections.newSetFromMap(new IdentityHashMap<>());
		final Set<AbstractInsnNode> startsMain = Collections.newSetFromMap(new IdentityHashMap<>());
		/** The handler's round {@code next()} calls, each with the iterator it advances. */
		final Map<AbstractInsnNode, V> rounds = new IdentityHashMap<>();
		/** The handler's jumps on an iterator's {@code hasNext()}, each with that iterator. */
		final Map<AbstractInsnNode, V> moreJumps = new IdentityHashMap<>();
		/** The handler's {@code Iterator.remove()} calls, each with the iterator it removes through. */
		final Map<AbstractInsnNode, V> removals = new IdentityHashMap<>();
		/** The arrays the handler's own body makes, and its stores into arrays, each with the array stored into. */
		final Set<AbstractInsnNode> mainArrays = Collections.newSetFromMap(new IdentityHashMap<>());
		final Map<AbstractInsnNode, V> arrayWrites = new IdentityHashMap<>();
		final Set<MethodInsnNode> opCalls = Collections.newSetFromMap(new IdentityHashMap<>());
		/** Mutations of the effect map: kind and the instruction (and for putAll the temp site it restores). */
		final Set<List<Object>> recv = new LinkedHashSet<>();
		/** Every filter applied, with the stream it applied to: [handle, captures, dom, part, base kind]. */
		final Set<List<Object>> filters = new LinkedHashSet<>();
		/** Question lambdas whose bodies were taken as the question, and those analysed. */
		final Set<Handle> askedOpaque = new LinkedHashSet<>();
		int askedAnalysed;
		final Deque<Handle> analysing = new ArrayDeque<>();

		Facts(ClassNode mixin, MethodNode main, Cfg cfg) {
			this.mixin = mixin;
			this.main = main;
			this.cfg = cfg;
		}

		void no(String why) {
			violations.add(why);
		}
	}

	/** Proves {@code handler} (the guest's clear() wrap, not static, no try blocks) or says why not. */
	static Verdict prove(ClassNode mixin, MethodNode handler) {
		Type[] params = Type.getArgumentTypes(handler.desc);
		if (params.length < 2 || !params[0].getDescriptor().equals("Ljava/util/Map;") || !params[1].getInternalName().equals(OP)
				|| Type.getReturnType(handler.desc).getSort() != Type.VOID || (handler.access & Opcodes.ACC_STATIC) != 0)
			return new Verdict("not a void instance wrap of a map operation", null, List.of());
		for (int i = 2; i < params.length; i++)
			if (!params[i].getDescriptor().equals("Ljava/util/Map;")) return new Verdict("captures a host local that is not a map", null, List.of());
		if (handler.tryCatchBlocks != null && !handler.tryCatchBlocks.isEmpty()) return new Verdict("has a try block", null, List.of());
		Facts facts;
		try {
			// The loops first: whether a next() takes a round's effect is known where its value is made.
			facts = new Facts(mixin, handler, Cfg.of(mixin.name, handler));
			List<V> values = new ArrayList<>();
			values.add(V.of(K.THIS));
			values.add(new V(K.SRC, 1, 1, null, Dom.SOURCE, List.of()));
			values.add(V.of(K.OP));
			for (int i = 2; i < params.length; i++) values.add(new V(K.SRC, 1, i + 1, null, Dom.SOURCE, List.of()));
			new Analyzer<>(new Interp(facts, Ctx.MAIN, handler, values)).analyze(mixin.name, handler);
		} catch (AnalyzerException | RuntimeException unreadable) {
			return new Verdict("unreadable: " + unreadable.getMessage(), null, List.of());
		}
		loops(facts, handler);
		if (!facts.violations.isEmpty()) return new Verdict(facts.violations.iterator().next(), null, List.of());
		if (facts.opCalls.size() > 1) return new Verdict("calls the original operation more than once", null, List.of());
		return new Verdict(null, direct(facts), List.copyOf(facts.opCalls));
	}

	/**
	 * The handler's whole question is one static filter over the effect map's entries, collected into a map of the
	 * entries it keeps, which goes back into the effect map after the one clear: true means keep.
	 */
	private static Handle direct(Facts f) {
		if (f.filters.size() != 1 || f.askedAnalysed != 0 || !f.opaqueMain.isEmpty() || f.askedOpaque.size() != 1) return null;
		List<Object> filter = f.filters.iterator().next();
		Handle h = (Handle) filter.get(0);
		@SuppressWarnings("unchecked") List<Object> captures = (List<Object>) filter.get(1);
		if (filter.get(2) != Dom.SOURCE || filter.get(3) != Part.ENTRY || filter.get(4) != K.SRC || !f.askedOpaque.contains(h)
				|| h.getTag() != Opcodes.H_INVOKESTATIC || !h.getOwner().equals(f.mixin.name)) return null;
		Type[] args = Type.getArgumentTypes(h.getDesc());
		if (args.length != captures.size() + 1 || !args[args.length - 1].getInternalName().equals(ENTRY)
				|| Type.getReturnType(h.getDesc()).getSort() != Type.BOOLEAN) return null;
		for (int i = 0; i < captures.size(); i++)
			if (((V) captures.get(i)).kind() != K.THIS || !args[i].getInternalName().equals(LIVING)) return null;
		AbstractInsnNode restored = null;
		int clears = 0;
		for (List<Object> m : f.recv) {
			if (m.get(0).equals("clear")) clears++;
			else if (m.get(0).equals("putAll") && restored == null) restored = (AbstractInsnNode) m.get(2);
			else return null;
		}
		if (clears != 1 || restored == null) return null;
		Temp temp = f.temps.get(restored);
		return temp != null && temp.map && temp.filters.equals(List.of(h)) ? h : null;
	}

	/**
	 * Per-effect parts of the handler body: every unknown call sits in a loop that iterates the original effects (and no
	 * decided ones), no iteration starts and no original operation inside a loop, and no local carries a value from one
	 * round to the next. A loop that takes a round's effect is left only when its iterator runs out: its one test of
	 * {@code hasNext()} is the only edge out of it, and no other test of {@code hasNext()} decides anything; an
	 * {@code Iterator.remove()} removes the round's effect of the loop it is in.
	 */
	private static void loops(Facts f, MethodNode m) {
		int n = m.instructions.size();
		List<List<Integer>> succ = f.cfg.succ();
		int[] comp = f.cfg.comp();
		boolean[] cyclic = f.cfg.cyclic();
		// Each loop that takes a round's effect, with the iterator it advances (one per loop: it makes one next()).
		Map<Integer, V> roundOf = new HashMap<>();
		for (var e : f.rounds.entrySet()) roundOf.put(comp[m.instructions.indexOf(e.getKey())], e.getValue());
		for (AbstractInsnNode insn : f.opaqueMain) {
			int i = m.instructions.indexOf(insn);
			V round = cyclic[i] ? roundOf.get(comp[i]) : null;
			if (round == null || round.dom() != Dom.SOURCE) f.no("calls " + describe(insn) + " outside a per-effect part");
		}
		for (AbstractInsnNode insn : f.startsMain) if (cyclic[m.instructions.indexOf(insn)]) f.no("iterates a collection inside a loop");
		for (MethodInsnNode insn : f.opCalls) if (cyclic[m.instructions.indexOf(insn)]) f.no("calls the original operation inside a loop");
		Map<Integer, Integer> tests = new HashMap<>();
		for (var e : f.moreJumps.entrySet()) {
			int i = m.instructions.indexOf(e.getKey());
			V round = cyclic[i] ? roundOf.get(comp[i]) : null;
			boolean leaves = false;
			for (int s : succ.get(i)) if (comp[s] != comp[i]) leaves = true;
			if (round == null || !round.equals(e.getValue()) || !leaves) f.no("decides on whether more effects follow other than to end their loop");
			else tests.merge(comp[i], 1, Integer::sum);
		}
		for (var e : roundOf.entrySet())
			if (tests.getOrDefault(e.getKey(), 0) != 1) f.no("ends a loop over effects other than by one test of whether more follow");
		for (int i = 0; i < n; i++) {
			V round = cyclic[i] ? roundOf.get(comp[i]) : null;
			if (round == null) continue;
			for (int s : succ.get(i)) {
				if (comp[s] == comp[i]) continue;
				V tested = f.moreJumps.get(m.instructions.get(i));
				if (tested == null || !tested.equals(round)) f.no("leaves a loop over effects before they run out");
			}
		}
		for (var e : f.removals.entrySet()) {
			int i = m.instructions.indexOf(e.getKey());
			V round = cyclic[i] ? roundOf.get(comp[i]) : null;
			if (round == null || !round.equals(e.getValue())) f.no("removes through an iterator an effect that is not the round's");
		}
		// The heap: an array element written in a loop outlives the round unless the array was made in that round.
		for (var e : f.arrayWrites.entrySet()) {
			int i = m.instructions.indexOf(e.getKey());
			if (!cyclic[i]) continue;
			V array = e.getValue();
			AbstractInsnNode made = array.kind() == K.ARRAY ? (AbstractInsnNode) array.site() : null;
			int at = made != null && f.mainArrays.contains(made) ? m.instructions.indexOf(made) : -1;
			if (at < 0 || !cyclic[at] || comp[at] != comp[i]) f.no("carries a value from one round to the next through an array");
		}
		// Liveness: a local written in a loop and live where the loop is entered carries a value between rounds.
		List<Set<Integer>> live = liveIn(m, succ);
		Map<Integer, Set<Integer>> written = new HashMap<>();
		for (int i = 0; i < n; i++) {
			if (!cyclic[i]) continue;
			AbstractInsnNode insn = m.instructions.get(i);
			int slot = insn instanceof VarInsnNode v && v.getOpcode() >= Opcodes.ISTORE && v.getOpcode() <= Opcodes.ASTORE ? v.var
					: insn instanceof IincInsnNode inc ? inc.var : -1;
			if (slot >= 0) written.computeIfAbsent(comp[i], c -> new HashSet<>()).add(slot);
		}
		for (int i = 0; i < n; i++) {
			if (!cyclic[i]) continue;
			boolean entry = i == 0;
			for (int p = 0; p < n && !entry; p++) if (comp[p] != comp[i] && succ.get(p).contains(i)) entry = true;
			if (!entry) continue;
			Set<Integer> carried = new HashSet<>(written.getOrDefault(comp[i], Set.of()));
			carried.retainAll(live.get(i));
			if (!carried.isEmpty()) f.no("carries local " + carried + " from one round of a loop to the next");
		}
	}

	private static List<Set<Integer>> liveIn(MethodNode m, List<List<Integer>> succ) {
		int n = m.instructions.size();
		List<Set<Integer>> in = new ArrayList<>();
		for (int i = 0; i < n; i++) in.add(new HashSet<>());
		for (boolean changed = true; changed;) {
			changed = false;
			for (int i = n - 1; i >= 0; i--) {
				Set<Integer> out = new HashSet<>();
				for (int s : succ.get(i)) out.addAll(in.get(s));
				AbstractInsnNode insn = m.instructions.get(i);
				if (insn instanceof VarInsnNode v && v.getOpcode() >= Opcodes.ISTORE && v.getOpcode() <= Opcodes.ASTORE) out.remove(v.var);
				if (insn instanceof VarInsnNode v && (v.getOpcode() <= Opcodes.ALOAD || v.getOpcode() == Opcodes.RET)) out.add(v.var);
				if (insn instanceof IincInsnNode inc) out.add(inc.var);
				if (!out.equals(in.get(i))) {
					in.set(i, out);
					changed = true;
				}
			}
		}
		return in;
	}

	/** Tarjan, iteratively: the strongly connected component of each instruction. */
	private static int[] scc(List<List<Integer>> succ, int n) {
		int[] index = new int[n], low = new int[n], comp = new int[n];
		Arrays.fill(index, -1);
		boolean[] on = new boolean[n];
		Deque<Integer> stack = new ArrayDeque<>();
		int counter = 0, comps = 0;
		for (int root = 0; root < n; root++) {
			if (index[root] >= 0) continue;
			Deque<int[]> work = new ArrayDeque<>();
			work.push(new int[] {root, 0});
			while (!work.isEmpty()) {
				int[] top = work.peek();
				int v = top[0];
				if (top[1] == 0 && index[v] < 0) {
					index[v] = low[v] = counter++;
					stack.push(v);
					on[v] = true;
				}
				List<Integer> next = succ.get(v);
				if (top[1] < next.size()) {
					int w = next.get(top[1]++);
					if (index[w] < 0) work.push(new int[] {w, 0});
					else if (on[w]) low[v] = Math.min(low[v], index[w]);
					continue;
				}
				work.pop();
				if (!work.isEmpty()) low[work.peek()[0]] = Math.min(low[work.peek()[0]], low[v]);
				if (low[v] == index[v]) {
					for (int w;;) {
						w = stack.pop();
						on[w] = false;
						comp[w] = comps;
						if (w == v) break;
					}
					comps++;
				}
			}
		}
		return comp;
	}

	private static String describe(AbstractInsnNode insn) {
		if (insn instanceof MethodInsnNode c) return c.owner.replace('/', '.') + "." + c.name;
		if (insn instanceof InvokeDynamicInsnNode d) return "a lambda's " + d.name;
		return "an operation";
	}

	/** The abstract interpreter, for the handler and for each lambda it hands its effects to. */
	static final class Interp extends Interpreter<V> {
		final Facts f;
		final Ctx ctx;
		final MethodNode method;
		final Map<Integer, V> params = new HashMap<>();
		V returned;

		Interp(Facts f, Ctx ctx, MethodNode method, List<V> args) {
			super(Opcodes.ASM9);
			this.f = f;
			this.ctx = ctx;
			this.method = method;
			int slot = 0;
			Type[] types = Type.getArgumentTypes(method.desc);
			int i = 0;
			if ((method.access & Opcodes.ACC_STATIC) == 0) params.put(slot++, args.get(i++));
			for (Type t : types) {
				V v = i < args.size() ? args.get(i) : V.of(K.TOP);
				params.put(slot, t.getSize() == 2 ? V.prim(2) : v);
				slot += t.getSize();
				i++;
			}
		}

		@Override public V newValue(Type type) {
			if (type == null) return V.of(K.UNINIT);
			if (type.getSort() == Type.VOID) return null;
			if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) return V.of(K.OPQ);
			return V.prim(type.getSize());
		}

		@Override public V newParameterValue(boolean isInstanceMethod, int local, Type type) {
			V v = params.get(local);
			return v != null ? v : newValue(type);
		}

		@Override public V newEmptyValue(int local) {
			return V.of(K.UNINIT);
		}

		@Override public V newExceptionValue(TryCatchBlockNode tryCatch, Frame<V> handlerFrame, Type exceptionType) {
			return V.of(K.OPQ);
		}

		@Override public V newOperation(AbstractInsnNode insn) {
			switch (insn.getOpcode()) {
				case Opcodes.ACONST_NULL: return V.of(K.OPQ);
				case Opcodes.LCONST_0: case Opcodes.LCONST_1: case Opcodes.DCONST_0: case Opcodes.DCONST_1: return V.prim(2);
				case Opcodes.LDC: {
					Object c = ((LdcInsnNode) insn).cst;
					if (c instanceof Long || c instanceof Double) return V.prim(2);
					if (c instanceof Number) return V.prim(1);
					return V.of(K.OPQ);
				}
				case Opcodes.GETSTATIC: return newValue(Type.getType(((FieldInsnNode) insn).desc));
				case Opcodes.NEW: {
					String type = ((TypeInsnNode) insn).desc;
					if (MAPS.contains(type)) {
						f.temps.computeIfAbsent(insn, k -> new Temp(true));
						return new V(K.TMAP, 1, insn, null, null, List.of());
					}
					if (COLLECTIONS.contains(type)) {
						f.temps.computeIfAbsent(insn, k -> new Temp(false));
						return new V(K.TCOLL, 1, insn, null, null, List.of());
					}
					return V.of(K.OPQ);
				}
				case Opcodes.JSR: f.no("uses a subroutine"); return V.of(K.TOP);
				default: return V.prim(1);
			}
		}

		@Override public V copyOperation(AbstractInsnNode insn, V value) {
			return value;
		}

		@Override public V unaryOperation(AbstractInsnNode insn, V value) {
			int op = insn.getOpcode();
			switch (op) {
				case Opcodes.IFEQ: case Opcodes.IFNE: case Opcodes.IFLT: case Opcodes.IFGE: case Opcodes.IFGT: case Opcodes.IFLE:
				case Opcodes.TABLESWITCH: case Opcodes.LOOKUPSWITCH:
					decide(insn, value);
					return null;
				case Opcodes.IFNULL: case Opcodes.IFNONNULL:
					if (value.kind() == K.TOP) f.no("tests a value of two kinds");
					if (value.kind() == K.PICK) f.no("tests an effect taken once per clear, not the one being decided");
					return null;
				case Opcodes.IRETURN: case Opcodes.LRETURN: case Opcodes.FRETURN: case Opcodes.DRETURN: case Opcodes.ARETURN:
					if (value.kind() == K.SIZE || value.kind() == K.TOP || value.kind() == K.MORE) f.no("returns an aggregate of the effects");
					if (value.kind() == K.PICK) f.no("returns an effect taken once per clear, not the one being decided");
					return null;
				case Opcodes.PUTSTATIC: f.no("writes a static field"); return null;
				case Opcodes.GETFIELD:
					if (!inert(value)) f.no("reads a field of a collection");
					return newValue(Type.getType(((FieldInsnNode) insn).desc));
				case Opcodes.NEWARRAY: case Opcodes.ANEWARRAY:
					if (tainted(value)) f.no("sizes an array by an aggregate of the effects");
					f.arrays.computeIfAbsent(insn, k -> new LinkedHashSet<>());
					if (method == f.main) f.mainArrays.add(insn);
					return new V(K.ARRAY, 1, insn, null, null, List.of());
				case Opcodes.ARRAYLENGTH: return V.prim(1);
				case Opcodes.ATHROW: f.no("throws"); return null;
				case Opcodes.CHECKCAST: return value;
				case Opcodes.INSTANCEOF: return value.kind() == K.PICK ? V.pick(1) : value.kind() == K.SIZE ? value : V.prim(1);
				case Opcodes.MONITORENTER: case Opcodes.MONITOREXIT: f.no("synchronizes"); return null;
				case Opcodes.I2L: case Opcodes.I2D: case Opcodes.F2L: case Opcodes.F2D: case Opcodes.LNEG: case Opcodes.DNEG:
				case Opcodes.L2D: case Opcodes.D2L:
					return taint(value, 2);
				default:
					return taint(value, 1);
			}
		}

		@Override public V binaryOperation(AbstractInsnNode insn, V a, V b) {
			int op = insn.getOpcode();
			switch (op) {
				case Opcodes.AALOAD:
					if (tainted(b)) f.no("indexes an array by an aggregate of the effects");
					if (a.kind() != K.ARRAY && a.kind() != K.OPQ || a.kind() == K.ARRAY && !f.arrays.get((AbstractInsnNode) a.site()).stream().allMatch(this::inert))
						f.no("reads an element of an array of collections");
					return V.of(K.OPQ);
				case Opcodes.IALOAD: case Opcodes.BALOAD: case Opcodes.CALOAD: case Opcodes.SALOAD: case Opcodes.FALOAD:
				case Opcodes.LALOAD: case Opcodes.DALOAD:
					if (tainted(b)) f.no("indexes an array by an aggregate of the effects");
					return V.prim(op == Opcodes.LALOAD || op == Opcodes.DALOAD ? 2 : 1);
				case Opcodes.IF_ICMPEQ: case Opcodes.IF_ICMPNE: case Opcodes.IF_ICMPLT: case Opcodes.IF_ICMPGE: case Opcodes.IF_ICMPGT:
				case Opcodes.IF_ICMPLE:
					decide(insn, a);
					decide(insn, b);
					return null;
				case Opcodes.IF_ACMPEQ: case Opcodes.IF_ACMPNE:
					if (a.kind() == K.TOP || b.kind() == K.TOP) f.no("compares a value of two kinds");
					if (a.kind() == K.PICK || b.kind() == K.PICK) f.no("compares an effect taken once per clear, not the one being decided");
					return null;
				case Opcodes.PUTFIELD: f.no("writes a field"); return null;
				case Opcodes.LADD: case Opcodes.LSUB: case Opcodes.LMUL: case Opcodes.LDIV: case Opcodes.LREM: case Opcodes.LSHL:
				case Opcodes.LSHR: case Opcodes.LUSHR: case Opcodes.LAND: case Opcodes.LOR: case Opcodes.LXOR: case Opcodes.DADD:
				case Opcodes.DSUB: case Opcodes.DMUL: case Opcodes.DDIV: case Opcodes.DREM:
					return taint(strongest(a, b), 2);
				default:
					return taint(strongest(a, b), 1);
			}
		}

		/** The operand whose kind the result of an operation on both carries: two kinds, a taken effect, an aggregate. */
		private static V strongest(V a, V b) {
			for (K k : List.of(K.TOP, K.PICK, K.SIZE, K.MORE)) {
				if (a.kind() == k) return a;
				if (b.kind() == k) return b;
			}
			return a;
		}

		/**
		 * An array store. Its index and a primitive value must not come from an aggregate (a load hands back a plain
		 * value). The handler's own stores are judged by {@link #loops}: one inside a loop must go to an array made in the
		 * same round. A lambda run per effect may write only an array it made itself.
		 */
		@Override public V ternaryOperation(AbstractInsnNode insn, V array, V index, V value) {
			if (tainted(index)) f.no("indexes an array by an aggregate of the effects");
			if (insn.getOpcode() == Opcodes.AASTORE) {
				if (array.kind() == K.ARRAY) {
					f.arrays.get((AbstractInsnNode) array.site()).add(value);
					f.arrayStores.computeIfAbsent((AbstractInsnNode) array.site(), k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(insn);
				} else if (!inert(value)) f.no("stores a collection into an array");
			} else if (tainted(value)) f.no("stores an aggregate of the effects into an array");
			if (method == f.main) f.arrayWrites.put(insn, array);
			else if (array.kind() != K.ARRAY || !method.instructions.contains((AbstractInsnNode) array.site()))
				f.no("writes an array it did not make inside a per-effect part");
			return null;
		}

		/** A value drawn from more than the effect being decided, or of two kinds. */
		private static boolean tainted(V v) {
			return v.kind() == K.SIZE || v.kind() == K.MORE || v.kind() == K.PICK || v.kind() == K.TOP;
		}

		@Override public V naryOperation(AbstractInsnNode insn, List<? extends V> values) {
			if (insn instanceof InvokeDynamicInsnNode indy) return indy(indy, values);
			if (insn.getOpcode() == Opcodes.MULTIANEWARRAY) {
				if (values.stream().anyMatch(Interp::tainted)) f.no("sizes an array by an aggregate of the effects");
				return V.of(K.OPQ);
			}
			MethodInsnNode c = (MethodInsnNode) insn;
			return call(insn, c.owner, c.name, c.desc, c.getOpcode() == Opcodes.INVOKESTATIC, new ArrayList<>(values), ctx);
		}

		@Override public void returnOperation(AbstractInsnNode insn, V value, V expected) {
			returned = returned == null ? value : merge(returned, value);
		}

		@Override public V merge(V a, V b) {
			if (a.equals(b)) return a;
			// Primitives: an aggregate wins, and a hasNext() that meets anything else is no longer just its loop's test.
			boolean pa = a.kind() == K.PRIM || a.kind() == K.SIZE || a.kind() == K.MORE, pb = b.kind() == K.PRIM || b.kind() == K.SIZE || b.kind() == K.MORE;
			if (pa && pb && a.size() == b.size()) return a.kind() == K.PRIM && b.kind() == K.PRIM ? a : new V(K.SIZE, a.size(), null, null, null, List.of());
			return new V(K.TOP, Math.min(a.size(), b.size()), null, null, null, List.of());
		}

		private V taint(V v, int size) {
			if (v.kind() == K.TOP) f.no("computes with a value of two kinds");
			return switch (v.kind()) {
				case SIZE, MORE -> new V(K.SIZE, size, null, null, null, List.of());
				case PICK -> V.pick(size);
				default -> V.prim(size);
			};
		}

		/**
		 * A branch on {@code v}. Only the handler's own {@code IFEQ}/{@code IFNE} on an iterator's {@code hasNext()} is
		 * kept for {@link #loops} to judge: it may be the end of that iterator's loop and nothing else.
		 */
		private void decide(AbstractInsnNode insn, V v) {
			switch (v.kind()) {
				case SIZE -> f.no("decides on an aggregate of the effects");
				case TOP -> f.no("decides on a value of two kinds");
				case PICK -> f.no("decides on an effect taken once per clear, not the one being decided");
				case MORE -> {
					if (method == f.main && ctx == Ctx.MAIN && (insn.getOpcode() == Opcodes.IFEQ || insn.getOpcode() == Opcodes.IFNE))
						f.moreJumps.put(insn, (V) v.extra().getFirst());
					else f.no("decides on whether more effects follow other than to end their loop");
				}
				default -> { }
			}
		}

		/** A value an unknown call may see: nothing that holds or iterates the effects. */
		boolean inert(V v) {
			return switch (v.kind()) {
				case THIS, OPQ, PRIM, ELEM, UNINIT -> true;
				case ARRAY -> f.arrays.getOrDefault((AbstractInsnNode) v.site(), Set.of()).stream().allMatch(this::inert);
				default -> false;
			};
		}

		Dom dom(V v) {
			return switch (v.kind()) {
				case SRC -> Dom.SOURCE;
				case TMAP, TCOLL -> f.temps.get((AbstractInsnNode) v.site()).dom;
				default -> v.dom() == null ? Dom.DECIDED : v.dom();
			};
		}

		Part part(V v) {
			return switch (v.kind()) {
				case TCOLL -> f.temps.get((AbstractInsnNode) v.site()).part;
				default -> v.part();
			};
		}

		private Ctx role(Dom dom) {
			return dom == Dom.SOURCE ? Ctx.QUESTION : Ctx.PLUMBING;
		}

		/** An iteration of a whole collection begins here: only in the handler's straight-line part. */
		private void starts(AbstractInsnNode site, Ctx in) {
			if (in != Ctx.MAIN) f.no("iterates a collection inside a per-effect part");
			else if (method == f.main) f.startsMain.add(site);
		}

		private void recv(V target, String kind, AbstractInsnNode site, Object detail) {
			if (target.kind() == K.SRC && Integer.valueOf(1).equals(target.site())) f.recv.add(Arrays.asList(kind, site, detail));
		}

		private V indy(InvokeDynamicInsnNode indy, List<? extends V> values) {
			String bsm = indy.bsm.getOwner();
			if (bsm.equals("java/lang/invoke/LambdaMetafactory") && indy.bsmArgs.length >= 3 && indy.bsmArgs[1] instanceof Handle)
				return new V(K.LAMBDA, 1, indy, null, null, List.copyOf(values));
			if (bsm.equals("java/lang/invoke/StringConcatFactory")) {
				for (V v : values) if (!inert(v)) f.no("formats a collection");
				return V.of(K.OPQ);
			}
			f.no("uses an unknown invokedynamic");
			return V.of(K.TOP);
		}

		/** Runs a lambda over elements in a role; its result, or null for void. */
		V consume(V lambda, Ctx role, List<V> elems, AbstractInsnNode site) {
			if (lambda.kind() != K.LAMBDA) {
				f.no("hands its effects to a function it did not make");
				return V.of(K.TOP);
			}
			InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) lambda.site();
			Handle h = (Handle) indy.bsmArgs[1];
			List<V> args = new ArrayList<>();
			for (Object o : lambda.extra()) args.add((V) o);
			args.addAll(elems);
			boolean isStatic = h.getTag() == Opcodes.H_INVOKESTATIC;
			if (h.getTag() == Opcodes.H_NEWINVOKESPECIAL || h.getTag() < Opcodes.H_INVOKEVIRTUAL) {
				f.no("hands its effects to a constructor or field reference");
				return V.of(K.TOP);
			}
			if (h.getOwner().equals(f.mixin.name)) {
				MethodNode impl = null;
				for (MethodNode m : f.mixin.methods) if (m.name.equals(h.getName()) && m.desc.equals(h.getDesc())) impl = m;
				if (impl == null || impl.instructions.size() == 0) {
					f.no("hands its effects to a method it does not declare");
					return V.of(K.TOP);
				}
				boolean inertCaptures = lambda.extra().stream().allMatch(o -> inert((V) o));
				if (role == Ctx.QUESTION && inertCaptures) {
					// The question itself: called once per effect with that effect and the entity, whatever it does.
					f.askedOpaque.add(h);
					return newValue(Type.getReturnType(h.getDesc()));
				}
				if (f.analysing.contains(h) || f.analysing.size() > 4) {
					f.no("recurses through its lambdas");
					return V.of(K.TOP);
				}
				if (role == Ctx.QUESTION) f.askedAnalysed++;
				if (impl.tryCatchBlocks != null && !impl.tryCatchBlocks.isEmpty()) f.no("has a try block in a lambda");
				f.analysing.push(h);
				try {
					Interp inner = new Interp(f, role, impl, args);
					new Analyzer<>(inner).analyze(f.mixin.name, impl);
					Type ret = Type.getReturnType(impl.desc);
					return ret.getSort() == Type.VOID ? null : inner.returned == null ? V.of(K.TOP) : inner.returned;
				} catch (AnalyzerException | RuntimeException unreadable) {
					f.no("has an unreadable lambda");
					return V.of(K.TOP);
				} finally {
					f.analysing.pop();
				}
			}
			// A method reference: the call it names, as if made here. Its answer is the per-effect answer, unread by any
			// instruction here, so an answer drawn from more than the one effect is refused now.
			V answer = call(site, h.getOwner(), h.getName(), h.getDesc(), isStatic, args, role);
			if (answer != null && (answer.kind() == K.PICK || answer.kind() == K.SIZE || answer.kind() == K.MORE))
				f.no("answers for each effect from more than that effect");
			return answer;
		}

		V call(AbstractInsnNode site, String owner, String name, String desc, boolean isStatic, List<V> args, Ctx in) {
			for (V v : args) if (v.kind() == K.TOP) {
				f.no("uses a value of two kinds");
				return result(desc);
			}
			if (args.stream().anyMatch(v -> v.kind() == K.PICK)) return picked(owner, name, desc, isStatic, args);
			V recv = isStatic || args.isEmpty() ? null : args.get(0);
			// The original operation.
			if (recv != null && recv.kind() == K.OP) {
				if (!name.equals("call") || in != Ctx.MAIN || method != f.main || args.size() != 2 || args.get(1).kind() != K.ARRAY) {
					f.no("uses the original operation other than to call it on the effect map");
					return result(desc);
				}
				AbstractInsnNode array = (AbstractInsnNode) args.get(1).site();
				Set<V> contents = f.arrays.get(array);
				if (contents.size() != 1 || f.arrayStores.getOrDefault(array, Set.of()).size() != 1
						|| !contents.contains(new V(K.SRC, 1, 1, null, Dom.SOURCE, List.of())))
					f.no("calls the original operation on something other than the effect map");
				f.opCalls.add((MethodInsnNode) site);
				f.recv.add(Arrays.asList("clear", site, null));
				return V.of(K.OPQ);
			}
			if (name.equals("<init>") && recv != null && (recv.kind() == K.TMAP || recv.kind() == K.TCOLL)) {
				Temp temp = f.temps.get((AbstractInsnNode) recv.site());
				for (int i = 1; i < args.size(); i++) {
					V a = args.get(i);
					if (a.kind() == K.SRC || a.kind() == K.TMAP || a.kind() == K.VIEW || a.kind() == K.TCOLL) {
						temp.dom = dom(a);
						temp.part = a.kind() == K.SRC || a.kind() == K.TMAP ? null : part(a);
					} else if (a.kind() != K.PRIM && a.kind() != K.SIZE) f.no("builds a collection from something it cannot see");
				}
				return null;
			}
			if (recv != null && (recv.kind() == K.SRC || recv.kind() == K.TMAP) && isMap(owner)) return mapCall(site, recv, name, desc, args, in);
			if (recv != null && (recv.kind() == K.VIEW || recv.kind() == K.TCOLL) && isCollection(owner)) return collectionCall(site, recv, name, desc, args, in);
			if (recv != null && recv.kind() == K.ITER && owner.equals("java/util/Iterator")) return iteratorCall(site, recv, name, args, in);
			if (recv != null && recv.kind() == K.STREAM && owner.equals("java/util/stream/Stream")) return streamCall(site, recv, name, desc, args, in);
			if (recv != null && recv.kind() == K.ELEM && recv.part() == Part.ENTRY && owner.equals(ENTRY)) {
				if (name.equals("getKey")) return V.elem(Part.KEY);
				if (name.equals("getValue")) return V.elem(Part.VALUE);
				if (name.equals("equals") || name.equals("hashCode")) return result(desc);
				f.no("changes an effect entry");
				return result(desc);
			}
			if (recv != null && recv.kind() == K.ELEM && recv.part() == Part.VALUE && owner.equals("net/minecraft/world/effect/MobEffectInstance")
					&& name.equals("getEffect") && desc.equals("()Lnet/minecraft/core/Holder;"))
				return V.elem(Part.KEY);
			if (isStatic) {
				V[] known = staticCall(site, owner, name, desc, args, in);
				if (known != null) return known[0];
			}
			boolean inertArgs = args.stream().allMatch(this::inert);
			if (pure(owner) && inertArgs) return result(desc);
			if (owner.startsWith("java/util/") || owner.equals("java/lang/Iterable")) {
				f.no("uses " + owner.replace('/', '.') + "." + name + " on a collection it cannot see");
				return result(desc);
			}
			if (!inertArgs) {
				f.no("hands a collection to " + owner.replace('/', '.') + "." + name);
				return result(desc);
			}
			// A call the model does not know: the question, a listener, anything. Allowed only per effect.
			switch (in) {
				case QUESTION -> { }
				case MAIN -> {
					if (method == f.main) f.opaqueMain.add(site);
				}
				default -> f.no("calls " + owner.replace('/', '.') + "." + name + " in the bookkeeping of decided effects");
			}
			return result(desc);
		}

		private V result(String desc) {
			return newValue(Type.getReturnType(desc));
		}

		/**
		 * A call handed an effect taken once per clear. The effect's own accessors and pure functions pass the taint on to
		 * their result, for a branch or a comparison to refuse; anything else that would see it is refused here.
		 */
		private V picked(String owner, String name, String desc, boolean isStatic, List<V> args) {
			boolean accessor = !isStatic && args.size() == 1 && args.getFirst().kind() == K.PICK
					&& (owner.equals(ENTRY) && (name.equals("getKey") || name.equals("getValue") || name.equals("hashCode"))
							|| owner.equals("net/minecraft/world/effect/MobEffectInstance") && name.equals("getEffect"));
			if (accessor || pure(owner)) {
				Type ret = Type.getReturnType(desc);
				return ret.getSort() == Type.VOID ? null : V.pick(ret.getSize());
			}
			f.no("hands an effect taken once per clear to " + owner.replace('/', '.') + "." + name);
			return result(desc);
		}

		private V mapCall(AbstractInsnNode site, V map, String name, String desc, List<V> args, Ctx in) {
			boolean local = map.kind() == K.SRC && !Integer.valueOf(1).equals(map.site());
			boolean temp = map.kind() == K.TMAP;
			switch (name) {
				case "entrySet": return new V(K.VIEW, 1, null, Part.ENTRY, dom(map), List.of(map));
				case "keySet": return new V(K.VIEW, 1, null, Part.KEY, dom(map), List.of(map));
				case "values": return new V(K.VIEW, 1, null, Part.VALUE, dom(map), List.of(map));
				case "get": case "getOrDefault": case "containsKey":
					if (!key(args.get(1))) f.no("looks up a key that is not the effect's own");
					return name.equals("containsKey") ? V.prim(1) : V.elem(Part.VALUE);
				case "containsValue":
					if (!(args.get(1).kind() == K.ELEM && args.get(1).part() == Part.VALUE)) f.no("looks up a value that is not the effect's own");
					return V.prim(1);
				case "remove":
					if (!key(args.get(1))) f.no("removes a key that is not the effect's own");
					recv(map, "remove", site, null);
					markDecided(map);
					return desc.endsWith(")Z") ? V.prim(1) : V.elem(Part.VALUE);
				case "put": case "putIfAbsent":
					if (local) f.no("adds to a host local");
					if (!key(args.get(1)) || !(args.get(2).kind() == K.ELEM && args.get(2).part() == Part.VALUE))
						f.no("puts something other than an effect's own entry");
					recv(map, "put", site, null);
					if (temp) f.temps.get((AbstractInsnNode) map.site()).dom = Dom.DECIDED;
					return V.elem(Part.VALUE);
				case "putAll":
					if (local) f.no("adds to a host local");
					V from = args.get(1);
					if (from.kind() != K.TMAP && from.kind() != K.SRC) f.no("puts back something other than effect entries");
					recv(map, "putAll", site, from.kind() == K.TMAP ? from.site() : null);
					return null;
				case "clear":
					if (local) f.no("clears a host local");
					recv(map, "clear", site, null);
					markDecided(map);
					return null;
				case "forEach":
					starts(site, in);
					consume(args.get(1), role(dom(map)), List.of(V.elem(Part.KEY), V.elem(Part.VALUE)), site);
					return null;
				case "size": case "isEmpty": return new V(K.SIZE, 1, null, null, null, List.of());
				default:
					f.no("uses Map." + name);
					return result(desc);
			}
		}

		private boolean key(V v) {
			return v.kind() == K.ELEM && v.part() == Part.KEY;
		}

		private V base(V view) {
			return view.kind() == K.VIEW ? (V) view.extra().getFirst() : view;
		}

		private V collectionCall(AbstractInsnNode site, V coll, String name, String desc, List<V> args, Ctx in) {
			Part part = part(coll);
			V base = base(coll);
			boolean localView = base.kind() == K.SRC && !Integer.valueOf(1).equals(base.site());
			switch (name) {
				case "iterator":
					starts(site, in);
					return new V(K.ITER, 1, site, part, dom(coll), List.of(base));
				case "stream":
					starts(site, in);
					return new V(K.STREAM, 1, null, part, dom(coll), List.of(base, List.of()));
				case "forEach":
					starts(site, in);
					consume(args.get(1), role(dom(coll)), List.of(V.elem(part)), site);
					return null;
				case "removeIf":
					starts(site, in);
					consume(args.get(1), role(dom(coll)), List.of(V.elem(part)), site);
					recv(base, "removeIf", site, null);
					markDecided(base);
					return V.prim(1);
				case "contains":
					if (!(args.get(1).kind() == K.ELEM && args.get(1).part() == part)) f.no("looks for something other than the effect itself");
					return V.prim(1);
				case "remove":
					if (!(args.get(1).kind() == K.ELEM && args.get(1).part() == part)) f.no("removes something other than the effect itself");
					recv(base, "remove", site, null);
					markDecided(base);
					return V.prim(1);
				case "removeAll": case "retainAll": {
					V other = args.get(1);
					if ((other.kind() != K.VIEW && other.kind() != K.TCOLL) || part(other) != part) f.no("removes a collection of something else");
					starts(site, in);
					recv(base, name, site, null);
					markDecided(base);
					return V.prim(1);
				}
				case "add":
					if (coll.kind() != K.TCOLL) {
						f.no("adds to a view");
						return V.prim(1);
					}
					Temp t = f.temps.get((AbstractInsnNode) coll.site());
					V e = args.get(1);
					if (e.kind() != K.ELEM || t.part != null && t.part != e.part()) f.no("collects something other than the effects");
					else t.part = e.part();
					t.dom = Dom.DECIDED;
					return V.prim(1);
				case "addAll":
					if (coll.kind() != K.TCOLL || args.get(1).kind() != K.VIEW && args.get(1).kind() != K.TCOLL) {
						f.no("adds a collection to something it cannot see");
						return V.prim(1);
					}
					Temp into = f.temps.get((AbstractInsnNode) coll.site());
					into.part = part(args.get(1));
					return V.prim(1);
				case "size": case "isEmpty": return new V(K.SIZE, 1, null, null, null, List.of());
				default:
					if (localView) f.no("uses " + name + " on a host local");
					else f.no("uses Collection." + name);
					return result(desc);
			}
		}

		private void markDecided(V base) {
			if (base.kind() == K.TMAP || base.kind() == K.TCOLL) f.temps.get((AbstractInsnNode) base.site()).dom = Dom.DECIDED;
		}

		/**
		 * An iterator: its {@code next()} is the round's effect only as the one {@code next()} of a loop in the handler's
		 * own body; anywhere else (before or after the loop, a second one in the round, inside a lambda run per effect) it
		 * is an effect taken once per clear, not the one being decided.
		 */
		private V iteratorCall(AbstractInsnNode site, V it, String name, List<V> args, Ctx in) {
			boolean own = method == f.main && in == Ctx.MAIN;
			switch (name) {
				case "hasNext": return new V(K.MORE, 1, null, null, null, List.of(it));
				case "next":
					if (own && f.cfg.round(f.main.instructions.indexOf(site))) {
						f.rounds.put(site, it);
						return V.elem(it.part());
					}
					return V.pick(1);
				case "remove":
					if (own) f.removals.put(site, it);
					else f.no("removes through an iterator inside a per-effect part");
					recv((V) it.extra().getFirst(), "remove", site, null);
					markDecided((V) it.extra().getFirst());
					return null;
				default:
					f.no("uses Iterator." + name);
					return V.of(K.TOP);
			}
		}

		@SuppressWarnings("unchecked")
		private V streamCall(AbstractInsnNode site, V stream, String name, String desc, List<V> args, Ctx in) {
			Part part = stream.part();
			Dom dom = stream.dom();
			V base = (V) stream.extra().get(0);
			List<Object> filters = (List<Object>) stream.extra().get(1);
			switch (name) {
				case "filter": {
					V lambda = args.get(1);
					consume(lambda, role(dom), List.of(V.elem(part)), site);
					if (lambda.kind() == K.LAMBDA) {
						Handle h = (Handle) ((InvokeDynamicInsnNode) lambda.site()).bsmArgs[1];
						f.filters.add(Arrays.asList(h, lambda.extra(), dom, part, base.kind()));
						List<Object> more = new ArrayList<>(filters);
						more.add(h);
						return new V(K.STREAM, 1, null, part, dom, List.of(base, List.copyOf(more)));
					}
					return V.of(K.TOP);
				}
				case "map": {
					V r = consume(args.get(1), Ctx.PROJECTION, List.of(V.elem(part)), site);
					if (r == null || r.kind() != K.ELEM) {
						f.no("maps an effect to something else");
						return V.of(K.TOP);
					}
					List<Object> mapped = new ArrayList<>(filters);
					mapped.add("map");
					return new V(K.STREAM, 1, null, r.part(), dom, List.of(base, List.copyOf(mapped)));
				}
				case "forEach": case "forEachOrdered":
					consume(args.get(1), role(dom), List.of(V.elem(part)), site);
					return null;
				case "collect": {
					V collector = args.get(1);
					if (collector.kind() != K.COLLECTOR) {
						f.no("collects with an unknown collector");
						return V.of(K.TOP);
					}
					String shape = (String) collector.extra().getFirst();
					Temp t = f.temps.computeIfAbsent(site, k -> new Temp(shape.equals("toMap")));
					t.dom = filters.isEmpty() && dom == Dom.SOURCE ? Dom.SOURCE : Dom.DECIDED;
					t.filters = filters.stream().filter(o -> o instanceof Handle).map(o -> (Handle) o).toList();
					if (filters.contains("map")) t.filters = List.of();
					if (shape.equals("toMap")) {
						V k = consume((V) collector.extra().get(1), Ctx.PROJECTION, List.of(V.elem(part)), site);
						V v = consume((V) collector.extra().get(2), Ctx.PROJECTION, List.of(V.elem(part)), site);
						if (k == null || !key(k) || v == null || v.kind() != K.ELEM || v.part() != Part.VALUE)
							f.no("collects a map of something other than effect entries");
						return new V(K.TMAP, 1, site, null, null, List.of());
					}
					t.part = part;
					return new V(K.TCOLL, 1, site, null, null, List.of());
				}
				case "toList": {
					Temp t = f.temps.computeIfAbsent(site, k -> new Temp(false));
					t.dom = filters.isEmpty() && dom == Dom.SOURCE ? Dom.SOURCE : Dom.DECIDED;
					t.part = part;
					return new V(K.TCOLL, 1, site, null, null, List.of());
				}
				default:
					f.no("uses Stream." + name);
					return V.of(K.TOP);
			}
		}

		/** The modelled static call's result (the array's one element, null for void), or null when it is not modelled. */
		private V[] staticCall(AbstractInsnNode site, String owner, String name, String desc, List<V> args, Ctx in) {
			if (owner.equals("java/util/stream/Collectors")) {
				switch (name) {
					case "toMap":
						if (args.size() != 2) {
							f.no("collects with a merge function");
							return new V[] {V.of(K.TOP)};
						}
						return new V[] {new V(K.COLLECTOR, 1, site, null, null, List.of("toMap", args.get(0), args.get(1)))};
					case "toList": case "toSet": case "toUnmodifiableList": case "toUnmodifiableSet":
						return new V[] {new V(K.COLLECTOR, 1, site, null, null, List.of("toList"))};
					default:
						f.no("collects with Collectors." + name);
						return new V[] {V.of(K.TOP)};
				}
			}
			if (owner.equals("java/util/Map") && name.equals("entry") && args.size() == 2) {
				if (!key(args.get(0)) || !(args.get(1).kind() == K.ELEM && args.get(1).part() == Part.VALUE)) f.no("makes an entry of something else");
				return new V[] {V.elem(Part.ENTRY)};
			}
			if ((owner.equals("java/util/Set") || owner.equals("java/util/List")) && name.equals("copyOf")
					|| owner.equals("com/google/common/collect/Lists") && name.equals("newArrayList") && args.size() == 1
					|| owner.equals("com/google/common/collect/Sets") && (name.equals("newHashSet") || name.equals("newLinkedHashSet")) && args.size() == 1) {
				V from = args.getFirst();
				if (from.kind() != K.VIEW && from.kind() != K.TCOLL) {
					f.no("copies something it cannot see");
					return new V[] {V.of(K.TOP)};
				}
				Temp t = f.temps.computeIfAbsent(site, k -> new Temp(false));
				t.dom = dom(from);
				t.part = part(from);
				return new V[] {new V(K.TCOLL, 1, site, null, null, List.of())};
			}
			if (owner.equals("java/util/Map") && name.equals("copyOf")
					|| owner.equals("com/google/common/collect/Maps") && (name.equals("newHashMap") || name.equals("newLinkedHashMap")) && args.size() == 1) {
				V from = args.getFirst();
				if (from.kind() != K.SRC && from.kind() != K.TMAP) {
					f.no("copies something it cannot see");
					return new V[] {V.of(K.TOP)};
				}
				Temp t = f.temps.computeIfAbsent(site, k -> new Temp(true));
				t.dom = dom(from);
				return new V[] {new V(K.TMAP, 1, site, null, null, List.of())};
			}
			if (owner.equals("com/google/common/collect/Maps") && (name.equals("newHashMap") || name.equals("newLinkedHashMap")
					|| name.equals("newHashMapWithExpectedSize")) && args.size() <= 1) {
				f.temps.computeIfAbsent(site, k -> new Temp(true));
				return new V[] {new V(K.TMAP, 1, site, null, null, List.of())};
			}
			if (owner.equals("java/util/Objects") && name.equals("requireNonNull")) return new V[] {args.getFirst()};
			if (owner.equals("kotlin/jvm/internal/Intrinsics") && name.startsWith("check")) return new V[] {result(desc)};
			if (owner.equals("kotlin/collections/MapsKt") && name.equals("mapCapacity")) return new V[] {args.getFirst()};
			return null;
		}

		private boolean isMap(String owner) {
			return owner.equals("java/util/Map") || MAPS.contains(owner) || owner.equals("java/util/AbstractMap")
					|| owner.equals("java/util/SortedMap") || owner.equals("java/util/NavigableMap") || owner.equals("java/util/SequencedMap");
		}

		private boolean isCollection(String owner) {
			return owner.equals("java/util/Collection") || owner.equals("java/util/Set") || owner.equals("java/util/List")
					|| owner.equals("java/lang/Iterable") || owner.equals("java/util/AbstractCollection") || owner.equals("java/util/SequencedCollection")
					|| owner.equals("java/util/SequencedSet") || owner.equals("java/util/Queue") || owner.equals("java/util/Deque")
					|| COLLECTIONS.contains(owner);
		}

		/** Calls without effects beyond their result: boxing, strings, arithmetic. */
		private boolean pure(String owner) {
			return owner.equals("java/lang/Boolean") || owner.equals("java/lang/Integer") || owner.equals("java/lang/Long")
					|| owner.equals("java/lang/Short") || owner.equals("java/lang/Byte") || owner.equals("java/lang/Character")
					|| owner.equals("java/lang/Float") || owner.equals("java/lang/Double") || owner.equals("java/lang/Number")
					|| owner.equals("java/lang/Math") || owner.equals("java/lang/StrictMath") || owner.equals("java/lang/String")
					|| owner.equals("java/lang/Object") || owner.equals("java/util/Objects");
		}
	}

	static final Set<String> MAPS = Set.of("java/util/HashMap", "java/util/LinkedHashMap", "java/util/IdentityHashMap",
			"java/util/TreeMap", "java/util/WeakHashMap", "java/util/EnumMap");
	static final Set<String> COLLECTIONS = Set.of("java/util/ArrayList", "java/util/LinkedList", "java/util/HashSet",
			"java/util/LinkedHashSet", "java/util/TreeSet", "java/util/ArrayDeque");
}
