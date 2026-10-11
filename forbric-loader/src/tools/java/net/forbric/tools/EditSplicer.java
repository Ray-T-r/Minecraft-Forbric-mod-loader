/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SimpleVerifier;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Keeps BOTH ecosystems' edits to a method both of them patched, instead of keeping one body.
 *
 * <h2>The fact this works from</h2>
 *
 * <p>Every instruction of a patched method carries the patched-source line it was compiled from, and
 * {@link PatchLineProvenance} turns that line into "vanilla line N" or "added by this ecosystem's patch". So each
 * body splits exactly into the vanilla code the ecosystem left alone and the runs of instructions its patch added.
 * The vanilla parts of the two bodies are two compiles of the same vanilla statements and align instruction for
 * instruction; each added run of the OTHER side then has a precise place in the kept (base) body: just before the
 * base's counterpart of the vanilla instruction that follows it.
 *
 * <h2>What a run must satisfy to be carried over</h2>
 *
 * <ul>
 *   <li>It sits at a statement boundary on both sides: the operand stack is empty where it starts and where its
 *       anchor starts, in the other body and at the insertion point of the base body.</li>
 *   <li>Its control flow is self-contained: every jump out of it lands on its anchor or on a vanilla instruction
 *       the alignment maps; nothing outside jumps into its middle; its exception regions lie wholly inside it; and
 *       the exception regions covering it are the same, by type and count, in its own body and at the place it is
 *       inserted in the base. That place is chosen among the labels in front of the anchor, never past one that a
 *       jump or a handler lands on, because an instruction inserted before a region's end label is inside it.</li>
 *   <li>A base jump retargeted into it (its counterpart enters the run in the other body) landed on the very
 *       instruction the run is inserted before, so no base code it used to skip is now on its path; and every base
 *       instruction that can reach it in the result has a counterpart that can reach it in its own body.</li>
 *   <li>Every local it reads that it did not declare itself is found in the base body by name and descriptor,
 *       live at the insertion point. Locals it declares get fresh slots. A base local it writes that its own body
 *       reads afterwards is still read afterwards in the result: a hook result nothing reads is a lost hook.</li>
 *   <li>Something in the result reaches it.</li>
 *   <li>In a constructor it comes after the call that initializes {@code this}, on both sides.</li>
 * </ul>
 *
 * An insertion-only run is carried statement by statement (boundaries where the stack is empty, the line changes
 * and no jump or region crosses), so a statement the base already inserted at the same point is kept once while
 * the rest of the run is carried, on the side of the base's copy that its own order puts it; a statement after one
 * that could not be carried is not carried either. A copy counts only when the lambdas it captures have the same
 * bodies on both sides.
 *
 * <p>A lambda a carried run captures keeps its name only when the base side's own lambda of that name does the same
 * thing, the lambdas it captures in turn included, or the base side has no lambda of that name and the merged class
 * holds the other side's own; otherwise it is carried under a fresh name. Where the base side has the name, the
 * merged class's current method of that name is never the reference: the per-method merge may have put the other
 * side's body there, and the merge puts it back on the base's side once the class is merged.
 *
 * <p>A run that replaced vanilla lines replaces the base's copy of those lines only when the base left them vanilla.
 * When the base changed the same lines too, the two edits are kept once when they are the same instructions, and
 * otherwise the run is reported as a conflict and the base's edit stands. When both patches added a member of the
 * same name (vanilla's hierarchy lacks it, both patched hierarchies have it) and both edits of this method act on
 * it, the two sides reimplemented one feature and running both runs it twice: a conflict, the base's edit stands.
 * A run inserted where the base inserted its own run goes first when it is MinecraftForge's, so on every shared
 * insertion point MinecraftForge's code runs before NeoForge's.
 *
 * <p>Runs that belong together are carried together or not at all: when one copy of the same code (javac
 * duplicates a finally block into every exit), one call on an object a static field holds (a begin/end pair) or a
 * statement reading what an earlier statement of its run computed could not be carried, the planned runs that pair
 * with it are withheld and the method is planned again without them. Nothing here keys on a class, method or mod
 * name.
 *
 * <p>The result is verified ({@link SimpleVerifier} over the merge's declaration graph) with frames recomputed for
 * this method alone; a method that does not verify is refused whole and the caller keeps its existing decision.
 */
final class EditSplicer {

	/** One carried-over or refused run, for the report. */
	record Outcome(String kind, int firstLine, int lastLine, String detail) { }

	record Result(MethodNode method, List<MethodNode> addedMethods, List<Outcome> spliced, List<Outcome> conflicts,
			List<Outcome> refused, String reason) {
		boolean accepted() {
			return method != null;
		}
	}

	/**
	 * Where a member that an edit acts on came from. Two edits that act on a member both patches added under the
	 * same name are two implementations of one feature, not two independent hooks.
	 */
	interface MemberOrigin {
		/**
		 * Whether {@code name} on {@code owner} is added by both patches: no class of the vanilla hierarchy of
		 * {@code owner} declares it, and both the MinecraftForge-patched and the NeoForge-patched hierarchy of
		 * {@code owner} declare it (by name, any descriptor).
		 */
		boolean addedByBoth(String owner, String name, boolean method);

		/**
		 * Whether method {@code name desc} of {@code owner}, as a patched game declares it, is a private step of the
		 * class's own implementation: declared on {@code owner} itself as private (nothing outside the class calls or
		 * overrides it), with a descriptor that names no platform type (a type only the patched games and their
		 * runtimes have; a helper that takes or returns a platform's objects serves that platform alone).
		 */
		default boolean privateImplementation(String owner, String name, String desc) {
			return false;
		}

		MemberOrigin NONE = (owner, name, method) -> false;
	}

	/** Both sides of one method, with everything a splice needs to read. */
	record Input(ClassNode owner, MethodNode base, MethodNode other, String sourcePath,
			PatchLineProvenance baseProvenance, PatchLineProvenance otherProvenance, boolean otherIsForge,
			Map<String, MethodNode> ownerMethods, Map<String, MethodNode> otherMethods,
			Map<String, MethodNode> baseMethods, MemberOrigin members) {
		Input {
			if (baseMethods == null) baseMethods = ownerMethods;
			if (members == null) members = MemberOrigin.NONE;
		}

		/** Without a member oracle: no member counts as added by both, and the merged class is the base side. */
		Input(ClassNode owner, MethodNode base, MethodNode other, String sourcePath,
				PatchLineProvenance baseProvenance, PatchLineProvenance otherProvenance, boolean otherIsForge,
				Map<String, MethodNode> ownerMethods, Map<String, MethodNode> otherMethods) {
			this(owner, base, other, sourcePath, baseProvenance, otherProvenance, otherIsForge, ownerMethods, otherMethods,
					ownerMethods, MemberOrigin.NONE);
		}
	}

	/**
	 * {@link MemberOrigin} over the three class sets themselves. Each side is the classes a hierarchy is read from:
	 * the vanilla game; the MinecraftForge-patched game and its runtime; the NeoForge-patched game and its runtime
	 * (the runtime holds the extension interfaces a patched class implements).
	 */
	static final class PatchedHierarchies implements MemberOrigin {
		private final Side vanilla, forge, neo;

		PatchedHierarchies(List<Map<String, byte[]>> vanilla, List<Map<String, byte[]>> forge, List<Map<String, byte[]>> neo) {
			this.vanilla = new Side(vanilla);
			this.forge = new Side(forge);
			this.neo = new Side(neo);
		}

		@Override
		public synchronized boolean addedByBoth(String owner, String name, boolean method) {
			String key = (method ? "m " : "f ") + name;
			return !vanilla.names(owner).contains(key) && forge.names(owner).contains(key) && neo.names(owner).contains(key);
		}

		@Override
		public synchronized boolean privateImplementation(String owner, String name, String desc) {
			boolean declared = false;
			for (Side side : List.of(forge, neo)) {
				ClassNode node = side.header(owner);
				if (node == null) continue;
				for (MethodNode m : node.methods) {
					if (!m.name.equals(name) || !m.desc.equals(desc)) continue;
					if ((m.access & Opcodes.ACC_PRIVATE) == 0) return false;
					declared = true;
				}
			}
			if (!declared) return false;
			Type type = Type.getMethodType(desc);
			List<Type> named = new ArrayList<>(List.of(type.getArgumentTypes()));
			named.add(type.getReturnType());
			for (Type t : named) {
				while (t.getSort() == Type.ARRAY) t = t.getElementType();
				if (t.getSort() == Type.OBJECT && platformType(t.getInternalName())) return false;
			}
			return true;
		}

		/** A class only the patched games and their runtimes have. */
		private boolean platformType(String name) {
			return vanilla.header(name) == null && (forge.header(name) != null || neo.header(name) != null);
		}

		private static final class Side {
			private final List<Map<String, byte[]>> classes;
			private final Map<String, java.util.Optional<ClassNode>> headers = new HashMap<>();
			private final Map<String, Set<String>> names = new HashMap<>();

			Side(List<Map<String, byte[]>> classes) {
				this.classes = classes;
			}

			/** Every member name the class and its supertypes declare, as "m name" / "f name". */
			Set<String> names(String owner) {
				Set<String> known = names.get(owner);
				if (known != null) return known;
				names.put(owner, Set.of()); // a cyclic hierarchy (malformed input) ends here
				Set<String> out = new HashSet<>();
				ClassNode node = header(owner);
				if (node != null) {
					for (MethodNode m : node.methods) out.add("m " + m.name);
					for (FieldNode f : node.fields) out.add("f " + f.name);
					if (node.superName != null) out.addAll(names(node.superName));
					for (String itf : node.interfaces) out.addAll(names(itf));
				}
				names.put(owner, out);
				return out;
			}

			ClassNode header(String name) {
				return headers.computeIfAbsent(name, n -> {
					for (Map<String, byte[]> set : classes) {
						byte[] bytes = set.get(n);
						if (bytes == null) continue;
						try {
							ClassNode node = new ClassNode();
							new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
							return java.util.Optional.of(node);
						} catch (RuntimeException unreadable) {
							return java.util.Optional.empty();
						}
					}
					return java.util.Optional.empty();
				}).orElse(null);
			}
		}
	}

	private final ContractGraph graph;

	EditSplicer(ContractGraph graph) {
		this.graph = graph;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Annotation: real instructions, their source line, origin and alignment key

	private static final class Annotated {
		final MethodNode method;
		final List<AbstractInsnNode> code = new ArrayList<>();
		final Map<AbstractInsnNode, Integer> position = new IdentityHashMap<>();
		final List<PatchLineProvenance.Origin> origin = new ArrayList<>();
		final List<Integer> line = new ArrayList<>();
		final List<String> key = new ArrayList<>();
		Frame<BasicValue>[] frames;

		Annotated(MethodNode method) {
			this.method = method;
		}

		boolean added(int i) {
			PatchLineProvenance.Origin o = origin.get(i);
			return o != null && o.added();
		}

		/** Index of the first real instruction at or after this label, or -1. */
		int at(LabelNode label) {
			for (AbstractInsnNode n = label; n != null; n = n.getNext()) {
				Integer p = position.get(n);
				if (p != null) return p;
			}
			return -1;
		}

		int stack(int i) {
			if (frames == null) return -1;
			Frame<BasicValue> f = frames[method.instructions.indexOf(code.get(i))];
			return f == null ? -1 : f.getStackSize();
		}
	}

	private Annotated annotate(String owner, MethodNode method, PatchLineProvenance provenance, String source)
			throws AnalyzerException {
		Annotated a = new Annotated(method);
		int line = 0;
		for (AbstractInsnNode n = method.instructions.getFirst(); n != null; n = n.getNext()) {
			if (n instanceof LineNumberNode ln) line = ln.line;
			if (n.getOpcode() < 0) continue;
			a.position.put(n, a.code.size());
			a.code.add(n);
			a.line.add(line);
			a.origin.add(source == null ? null : provenance.origin(source, line));
		}
		for (AbstractInsnNode n : a.code) a.key.add(key(n, method, a));
		a.frames = new Analyzer<>(new BasicInterpreter()).analyze(owner, method);
		return a;
	}

	/** Opcode and operands; locals by name (slots shift between compiles), jumps by opcode, lambdas without number. */
	private static String key(AbstractInsnNode n, MethodNode m, Annotated a) {
		StringBuilder sb = new StringBuilder().append(n.getOpcode()).append(' ');
		if (n instanceof VarInsnNode v) sb.append(localName(m, v.var, n, v.getOpcode() >= Opcodes.ISTORE));
		else if (n instanceof IincInsnNode inc) sb.append(localName(m, inc.var, n, false)).append(' ').append(inc.incr);
		else if (n instanceof MethodInsnNode c) sb.append(c.owner).append('.').append(c.name).append(c.desc);
		else if (n instanceof FieldInsnNode f) sb.append(f.owner).append('.').append(f.name).append(f.desc);
		else if (n instanceof TypeInsnNode t) sb.append(t.desc);
		else if (n instanceof LdcInsnNode l) sb.append(l.cst instanceof Type t ? t.getDescriptor() : String.valueOf(l.cst));
		else if (n instanceof IntInsnNode i) sb.append(i.operand);
		else if (n instanceof MultiANewArrayInsnNode mn) sb.append(mn.desc).append(mn.dims);
		else if (n instanceof TableSwitchInsnNode t) sb.append(t.min).append(':').append(t.max);
		else if (n instanceof LookupSwitchInsnNode l) sb.append(l.keys);
		else if (n instanceof InvokeDynamicInsnNode d) {
			sb.append(d.name).append(d.desc);
			for (Object arg : d.bsmArgs) if (arg instanceof Handle h) sb.append('|').append(h.getOwner()).append('.')
					.append(h.getName().replaceAll("\\$\\d+$", "")).append(h.getDesc());
		}
		return sb.toString();
	}

	/** The local's name at this instruction (a store names the variable whose scope starts after it). */
	private static String localName(MethodNode m, int slot, AbstractInsnNode at, boolean store) {
		LocalVariableNode lv = local(m, slot, store ? next(at) : at);
		return lv != null ? lv.name + ":" + lv.desc : "#" + slot;
	}

	private static AbstractInsnNode next(AbstractInsnNode n) {
		AbstractInsnNode x = n.getNext();
		return x == null ? n : x;
	}

	private static LocalVariableNode local(MethodNode m, int slot, AbstractInsnNode at) {
		if (m.localVariables == null) return null;
		int p = m.instructions.indexOf(at);
		LocalVariableNode best = null;
		for (LocalVariableNode lv : m.localVariables) {
			if (lv.index != slot) continue;
			int s = m.instructions.indexOf(lv.start), e = m.instructions.indexOf(lv.end);
			if (p >= s && p < e && (best == null || s >= m.instructions.indexOf(best.start))) best = lv;
		}
		return best;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Alignment of the vanilla parts (Myers diff over keys)

	/** other code index -> base code index, for vanilla instructions the two compiles share. */
	private static Map<Integer, Integer> align(Annotated o, Annotated b) {
		List<Integer> vo = new ArrayList<>(), vb = new ArrayList<>();
		for (int i = 0; i < o.code.size(); i++) if (!o.added(i)) vo.add(i);
		for (int i = 0; i < b.code.size(); i++) if (!b.added(i)) vb.add(i);
		int n = vo.size(), m = vb.size(), max = n + m, offset = max;
		int[] v = new int[2 * max + 2];
		List<int[]> trace = new ArrayList<>();
		Map<Integer, Integer> result = new HashMap<>();
		outer:
		for (int d = 0; d <= max; d++) {
			if (d > 1000) return result;
			trace.add(v.clone());
			for (int k = -d; k <= d; k += 2) {
				int x = (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) ? v[offset + k + 1] : v[offset + k - 1] + 1;
				int y = x - k;
				while (x < n && y < m && o.key.get(vo.get(x)).equals(b.key.get(vb.get(y)))) { x++; y++; }
				v[offset + k] = x;
				if (x >= n && y >= m) {
					// backtrack
					int cx = n, cy = m;
					for (int dd = d; dd > 0; dd--) {
						int[] pv = trace.get(dd);
						int kk = cx - cy;
						int prevK = (kk == -dd || (kk != dd && pv[offset + kk - 1] < pv[offset + kk + 1])) ? kk + 1 : kk - 1;
						int px = pv[offset + prevK], py = px - prevK;
						while (cx > px && cy > py) { cx--; cy--; result.put(vo.get(cx), vb.get(cy)); }
						cx = px; cy = py;
					}
					while (cx > 0 && cy > 0) { cx--; cy--; result.put(vo.get(cx), vb.get(cy)); }
					break outer;
				}
			}
		}
		return result;
	}

	// ---------------------------------------------------------------------------------------------------------

	/**
	 * A stretch of the other body's added code that is planned as one unit: a whole run, or one statement of an
	 * insertion-only run (then {@code parentStart..parentEnd} is the whole run, which the statement shares its
	 * anchor with).
	 */
	private record Run(int start, int end, Set<PatchLineProvenance.Block> blocks, int parentStart, int parentEnd) {
		Run(int start, int end, Set<PatchLineProvenance.Block> blocks) {
			this(start, end, blocks, start, end);
		}

		boolean replacement() {
			return blocks.stream().anyMatch(b -> !b.insertionOnly());
		}

		Span span() {
			return new Span(start, end);
		}
	}

	/** A unit's identity across planning rounds: the other body never changes, so its code indices do not. */
	private record Span(int start, int end) { }

	/** A base local a unit writes: its slot in the other body, the base slot it maps to, and its name. */
	private record Store(int otherSlot, int baseSlot, String name) { }

	private record Plan(String kind, Run run, AbstractInsnNode insertBefore, AbstractInsnNode anchorReal,
			List<AbstractInsnNode> removeFromBase, Map<LabelNode, LabelNode> labels, LabelNode anchorLabel,
			LabelNode runStart, Map<AbstractInsnNode, Integer> slotFor, List<JumpRetarget> entryRetargets,
			Map<String, String> lambdaRenames, List<Store> stores) { }

	/** A base jump that must enter the inserted run rather than skip to the anchor. */
	private record JumpRetarget(AbstractInsnNode jump, LabelNode from) { }

	/** A member an edit acts on; which two count as one is {@link #sameMember}'s question. */
	private record Member(String owner, String name, boolean method, String desc) { }

	/** Everything one planning round shares between the runs it plans. */
	private static final class Planning {
		final Input in;
		final Annotated o, b;
		final Map<Integer, Integer> match;
		final int oInit, bInit;
		final Map<LocalVariableNode, Integer> declared = new IdentityHashMap<>();
		final int[] nextSlot;
		final Map<String, MethodNode> addedMethods = new LinkedHashMap<>();
		final Set<AbstractInsnNode> claimed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		/** Labels a base jump, switch or handler lands on: an insertion never moves past one. */
		final Set<LabelNode> pinned = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		/** Base labels the inserted runs' own jumps land on: pinned the same way. */
		final Set<LabelNode> runTargets = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		private Set<Member> baseActsOn;
		private Frame<SourceValue>[] otherSources;
		private boolean otherSourcesTried;
		private Flow otherFlow;

		Planning(Input in, Annotated o, Annotated b, Map<Integer, Integer> match, MethodNode base) {
			this.in = in;
			this.o = o;
			this.b = b;
			this.match = match;
			this.oInit = initCall(o);
			this.bInit = initCall(b);
			this.nextSlot = new int[] { Math.max(base.maxLocals, maxSlot(base) + 1) };
			for (AbstractInsnNode n : base.instructions) pinned.addAll(targets(n));
			for (TryCatchBlockNode t : base.tryCatchBlocks) pinned.add(t.handler);
		}

		/** What the base's own edits in this method act on, its lambdas' bodies included. */
		Set<Member> baseActsOn() {
			if (baseActsOn == null) {
				baseActsOn = new HashSet<>();
				List<AbstractInsnNode> added = new ArrayList<>();
				for (int i = 0; i < b.code.size(); i++) if (b.added(i)) added.add(b.code.get(i));
				if (!added.isEmpty()) actsOn(b.method, added, sources(in.owner().name, b.method), baseActsOn, in.baseMethods(),
						in.owner().name, new HashSet<>(), 0);
			}
			return baseActsOn;
		}

		Frame<SourceValue>[] otherSources() {
			if (!otherSourcesTried) {
				otherSourcesTried = true;
				otherSources = sources(in.owner().name, o.method);
			}
			return otherSources;
		}

		Flow otherFlow() {
			if (otherFlow == null) otherFlow = new Flow(o.method);
			return otherFlow;
		}
	}

	/** One planning round's outcome: a final result, or the units to leave out of the next round. */
	private record Attempt(Result result, Map<Span, Outcome> exclude) {
		static Attempt done(Result result) {
			return new Attempt(result, Map.of());
		}
	}

	private static final int MAX_ROUNDS = 256;

	/** Never throws: a defect in the splice is the method's refusal, and the merge keeps its existing decision. */
	Result splice(Input in) {
		try {
			return spliceUnchecked(in);
		} catch (RuntimeException e) {
			return refuse("splicer error: " + e);
		}
	}

	private Result spliceUnchecked(Input in) {
		MethodNode base = in.base(), other = in.other();
		if (!base.desc.equals(other.desc) || (base.access & Opcodes.ACC_STATIC) != (other.access & Opcodes.ACC_STATIC))
			return refuse("descriptor or static flag differs");
		if (in.sourcePath() == null) return refuse("class names no source file");
		if (base.instructions.size() == 0 || other.instructions.size() == 0) return refuse("no code");
		if (!hasLines(base) || !hasLines(other)) return refuse("no line numbers");
		// Every round plans from scratch without the units earlier rounds left out, so nothing a left-out unit
		// planned (its declared locals, carried lambdas, claimed base lines) survives into the result.
		Map<Span, Outcome> excluded = new LinkedHashMap<>();
		for (int round = 0; round < MAX_ROUNDS; round++) {
			Attempt attempt = attempt(in, excluded);
			if (attempt.result() != null) return attempt.result();
			if (attempt.exclude().isEmpty()) throw new IllegalStateException("a planning round neither finished nor left anything out");
			excluded.putAll(attempt.exclude());
		}
		return refuse("leaving runs out did not settle");
	}

	private Attempt attempt(Input in, Map<Span, Outcome> excluded) {
		List<Outcome> spliced = new ArrayList<>(), conflicts = new ArrayList<>(), refused = new ArrayList<>();
		MethodNode base = copy(in.base());
		MethodNode other = in.other();
		// A missing local variable table only limits which runs can be carried (locals are matched by name).
		if (base.localVariables == null) base.localVariables = new ArrayList<>();
		Annotated o, b;
		try {
			o = annotate(in.owner().name, other, in.otherProvenance(), in.sourcePath());
			b = annotate(in.owner().name, base, in.baseProvenance(), in.sourcePath());
		} catch (AnalyzerException e) {
			return Attempt.done(refuse("analysis failed: " + e.getMessage()));
		}
		Map<Integer, Integer> match = align(o, b);
		if (match.isEmpty()) return Attempt.done(refuse("no shared vanilla code"));
		long vanillaO = o.code.size() - countAdded(o), vanillaB = b.code.size() - countAdded(b);
		if (match.size() < 0.85 * Math.min(vanillaO, vanillaB))
			return Attempt.done(refuse("vanilla parts do not align (" + match.size() + "/" + Math.min(vanillaO, vanillaB) + ")"));

		List<Run> runs = runs(o);
		if (runs.isEmpty()) return Attempt.done(refuse("the other side added nothing here"));
		Planning st = new Planning(in, o, b, match, base);
		List<Plan> plans = new ArrayList<>();
		// Units that were not carried (refused, left out, or a conflict other than the base already having them):
		// a planned unit that pairs with one of them is withheld.
		List<Run> lost = new ArrayList<>();
		// Statements that could be carried on their own but follow one of their run that was not.
		Map<Span, Outcome> orphaned = new LinkedHashMap<>();

		for (Run run : runs) {
			boolean predecessorLost = false;
			for (Run unit : run.replacement() ? List.of(run) : statements(o, run)) {
				int first = o.line.get(unit.start()), last = o.line.get(unit.end());
				String kind = unit.replacement() ? "replace" : "insert";
				Outcome left = excluded.get(unit.span());
				if (left != null) {
					refused.add(left);
					lost.add(unit);
					predecessorLost = true;
					continue;
				}
				// Every statement is planned on its own merits first, so that its own reason is what the report says
				// when it has one; only a statement that could be carried is left out for the one before it.
				int conflictsBefore = conflicts.size();
				try {
					Plan plan = plan(st, unit, conflicts);
					if (plan != null) {
						plans.add(plan);
						// A statement runs after the one before it in its own body; carried without it, it runs in a
						// state its own body never produces. Left out in the next round, which plans without it.
						if (predecessorLost) orphaned.put(unit.span(), new Outcome(kind, first, last,
								"the statement before it in the same run was not carried"));
					} else if (conflicts.size() > conflictsBefore && !conflicts.get(conflicts.size() - 1).kind().equals("same-edit")) {
						lost.add(unit);
						predecessorLost = true;
					}
				} catch (Refusal r) {
					refused.add(new Outcome(kind, first, last, r.getMessage()));
					lost.add(unit);
					predecessorLost = true;
				}
			}
		}
		if (!orphaned.isEmpty()) return new Attempt(null, orphaned);
		if (plans.isEmpty()) return Attempt.done(new Result(null, List.of(), spliced, conflicts, refused, "no run could be carried over"));
		Map<Span, Outcome> withheld = pairedWithholding(st, plans, lost);
		if (!withheld.isEmpty()) return new Attempt(null, withheld);
		Map<Span, Outcome> misplaced = passedRunTargets(st, plans);
		if (!misplaced.isEmpty()) return new Attempt(null, misplaced);

		// Base code reachable before the splice must stay reachable: a carried run that ends in a return or throw
		// would otherwise silently cut off what the base does after it.
		Set<AbstractInsnNode> reachable = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		for (int i = 0; i < b.code.size(); i++) if (b.stack(i) >= 0) reachable.add(b.code.get(i));
		for (Plan p : plans) apply(base, other, p);
		base.maxLocals = Math.max(base.maxLocals, st.nextSlot[0]);
		base.maxStack = Math.max(base.maxStack, other.maxStack) + 4;
		try {
			Frame<BasicValue>[] after = new Analyzer<>(new BasicInterpreter()).analyze(in.owner().name, base);
			for (int i = 0; i < after.length; i++) {
				AbstractInsnNode n = base.instructions.get(i);
				if (after[i] == null && reachable.contains(n))
					return Attempt.done(new Result(null, List.of(), List.of(), conflicts, refused, "splice would make base code unreachable"));
			}
			// A carried unit nothing in the result reaches is a hook that silently never runs.
			Map<Span, Outcome> stranded = new LinkedHashMap<>();
			for (Plan p : plans) {
				if (o.stack(p.run().start()) < 0 || after[base.instructions.indexOf(firstReal(p.runStart()))] != null) continue;
				stranded.put(p.run().span(), refusal(st, p, "nothing in the base reaches it where it would go"));
			}
			if (!stranded.isEmpty()) return new Attempt(null, stranded);
		} catch (AnalyzerException e) {
			return Attempt.done(new Result(null, List.of(), List.of(), conflicts, refused, "spliced body does not analyze: " + e.getMessage()));
		}
		Map<Span, Outcome> flow = flowChecks(st, plans, base);
		if (!flow.isEmpty()) return new Attempt(null, flow);

		for (Plan p : plans) spliced.add(new Outcome(p.kind(), o.line.get(p.run().start()), o.line.get(p.run().end()), "carried over"));
		for (var e : st.declared.entrySet()) {
			LocalVariableNode lv = e.getKey();
			// Declared locals keep their names for debuggers and @Local captures; their scope is the whole method,
			// which is wider than the source scope but never wrong for a slot nothing else uses.
			base.localVariables.add(new LocalVariableNode(lv.name, lv.desc, null, firstLabel(base), lastLabel(base), e.getValue()));
		}
		MethodNode finished = recomputeFrames(in.owner(), base);
		if (finished == null) return Attempt.done(new Result(null, List.of(), List.of(), conflicts, refused, "frames could not be computed"));
		String verify = verify(in.owner(), finished);
		if (verify != null) return Attempt.done(new Result(null, List.of(), List.of(), conflicts, refused, "does not verify: " + verify));
		return Attempt.done(new Result(finished, List.copyOf(st.addedMethods.values()), spliced, conflicts, refused, "spliced"));
	}

	private static final class Refusal extends RuntimeException {
		Refusal(String message) {
			super(message, null, false, false);
		}
	}

	private static Result refuse(String why) {
		return new Result(null, List.of(), List.of(), List.of(), List.of(), why);
	}

	private static long countAdded(Annotated a) {
		long n = 0;
		for (int i = 0; i < a.code.size(); i++) if (a.added(i)) n++;
		return n;
	}

	private static List<Run> runs(Annotated o) {
		List<Run> runs = new ArrayList<>();
		int i = 0;
		while (i < o.code.size()) {
			if (!o.added(i)) { i++; continue; }
			int s = i;
			Set<PatchLineProvenance.Block> blocks = new LinkedHashSet<>();
			while (i < o.code.size() && o.added(i)) { blocks.add(o.origin.get(i).block()); i++; }
			runs.add(new Run(s, i - 1, blocks));
		}
		return runs;
	}

	/**
	 * An insertion-only run cut into its statements. A boundary is where the other body's operand stack is empty,
	 * the source line changes, and no jump, switch or exception region of the run crosses it (a jump from the
	 * statement before the boundary to the boundary itself ends that statement and is not a crossing).
	 */
	private static List<Run> statements(Annotated o, Run run) {
		List<Run> out = new ArrayList<>();
		int start = run.start();
		for (int cut = run.start() + 1; cut <= run.end(); cut++) {
			if (o.stack(cut) != 0 || o.line.get(cut).equals(o.line.get(cut - 1)) || crossed(o, run, cut)) continue;
			out.add(statement(o, run, start, cut - 1));
			start = cut;
		}
		out.add(statement(o, run, start, run.end()));
		return out;
	}

	private static Run statement(Annotated o, Run run, int start, int end) {
		Set<PatchLineProvenance.Block> blocks = new LinkedHashSet<>();
		for (int i = start; i <= end; i++) blocks.add(o.origin.get(i).block());
		return new Run(start, end, blocks, run.start(), run.end());
	}

	private static boolean crossed(Annotated o, Run run, int cut) {
		int s = run.start(), e = run.end();
		for (int i = 0; i < o.code.size(); i++) {
			for (LabelNode target : targets(o.code.get(i))) {
				int t = o.at(target);
				boolean iIn = i >= s && i <= e, tIn = t >= s && t <= e;
				if (!iIn && !tIn) continue;
				if (t == cut) {
					if (iIn && i < cut) continue;
					return true;
				}
				if ((i < cut) != (t < cut)) return true;
			}
		}
		for (TryCatchBlockNode tcb : o.method.tryCatchBlocks) {
			int rs = o.at(tcb.start), re = o.at(tcb.end), h = o.at(tcb.handler);
			if (re < 0) re = o.code.size();
			boolean touches = (rs >= s && rs <= e) || (re - 1 >= s && re - 1 <= e) || (h >= s && h <= e);
			if (touches && Math.min(rs, h) < cut && cut <= Math.max(re - 1, h)) return true;
		}
		return false;
	}

	/** The code index of the call that initializes {@code this} in a constructor, or -1. */
	private static int initCall(Annotated a) {
		if (!a.method.name.equals("<init>")) return -1;
		int depth = 0;
		for (int i = 0; i < a.code.size(); i++) {
			AbstractInsnNode n = a.code.get(i);
			if (n.getOpcode() == Opcodes.NEW) depth++;
			if (n instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKESPECIAL && c.name.equals("<init>")) {
				if (depth == 0) return i;
				depth--;
			}
		}
		return Integer.MAX_VALUE;
	}

	private Plan plan(Planning st, Run run, List<Outcome> conflicts) {
		Input in = st.in;
		Annotated o = st.o, b = st.b;
		Map<Integer, Integer> match = st.match;
		// The run's neighbours in the other body that the alignment maps. Normally the instructions right next to it;
		// when the one after it has no counterpart (a recompile difference, or a vanilla line the base rewrote), an
		// insertion can still be placed right after the base's counterpart of the one before it. A statement shares
		// its whole run's neighbours: its statements go to one place, in order.
		int nextV = run.parentEnd() + 1, after = run.end() + 1;
		int nextM = nextV;
		while (nextM < o.code.size() && !match.containsKey(nextM)) nextM++;
		int prevM = run.parentStart() - 1;
		while (prevM >= 0 && !match.containsKey(prevM)) prevM--;
		for (int i = prevM + 1; i < run.parentStart(); i++) if (o.added(i)) throw new Refusal("another run of the other side is adjacent and unplaced");
		boolean gapAfter = nextM != nextV, endsMethod = nextM >= o.code.size();
		int bPrev = prevM < 0 ? -1 : match.get(prevM);
		int bNext;
		if (endsMethod && run.replacement() && nextV >= o.code.size()) bNext = b.code.size();
		else if (!gapAfter && !endsMethod) bNext = match.get(nextM);
		else if (!run.replacement() && prevM >= 0 && prevM == run.parentStart() - 1 && bPrev + 1 < b.code.size()) bNext = -1; // placed after bPrev below
		else throw new Refusal(endsMethod ? "run ends the method" : "the vanilla instruction after the run has no counterpart");
		if (bNext >= 0 && bPrev >= bNext) throw new Refusal("alignment out of order");
		if (st.oInit >= 0 && run.start() <= st.oInit) throw new Refusal("run precedes this() initialization");
		if (st.bInit >= 0 && (bNext >= 0 ? bNext : bPrev + 1) <= st.bInit) throw new Refusal("anchor precedes this() initialization in the base");

		List<Integer> baseAdded = new ArrayList<>(), baseVanilla = new ArrayList<>();
		if (bNext >= 0) for (int i = bPrev + 1; i < bNext; i++) (b.added(i) ? baseAdded : baseVanilla).add(i);
		List<String> runKeys = o.key.subList(run.start(), run.end() + 1);
		int first = o.line.get(run.start()), last = o.line.get(run.end());

		AbstractInsnNode insertBefore = null;
		Shared shared = null;
		int anchor;
		String kind = run.replacement() ? "replace" : "insert";
		List<AbstractInsnNode> remove = new ArrayList<>();
		if (!run.replacement()) {
			if (!baseAdded.isEmpty() && indexOfCopy(st, baseAdded, run.start(), run.end(), 0) >= 0) {
				conflicts.add(new Outcome("same-edit", first, last, "the base already has this inserted code"));
				return null;
			}
			if (reimplemented(st, run, first, last, conflicts)) return null;
			if (bNext >= 0) {
				// Between the base's copies of the statements of this run that the base inserted too: after the one
				// before it, before the one after it, so that the run's own order holds around what both share.
				shared = shared(st, run, baseAdded, bPrev, bNext);
				if (shared == null) throw new Refusal("the base has the statements it shares with this run in another order");
				anchor = shared.hi();
				// MinecraftForge's code goes before NeoForge's on a shared insertion point.
				if (in.otherIsForge()) while (anchor - 1 >= shared.lo() && b.added(anchor - 1)) anchor--;
			} else {
				anchor = bPrev + 1;
				if (!in.otherIsForge()) while (anchor < b.code.size() && b.added(anchor)) anchor++;
				if (anchor >= b.code.size()) throw new Refusal("nothing follows the insertion point in the base");
			}
		} else {
			// The base's own edits in this stretch: an insertion next to the replaced lines is the base's and stays;
			// a base edit that removed any of the same vanilla lines is a competing rewrite of them.
			// The two decompiles number vanilla lines independently (their headers differ), so the other side's removed
			// lines are moved into the base's numbering by the offset the aligned vanilla instructions around the run show.
			int shift = lineShift(o, b, match, prevM, nextM);
			Set<Integer> replacedLines = new java.util.HashSet<>();
			for (PatchLineProvenance.Block block : run.blocks()) for (int line : block.removedVanilla()) replacedLines.add(line + shift);
			List<Integer> competing = new ArrayList<>();
			for (int i : baseAdded) {
				PatchLineProvenance.Block block = b.origin.get(i).block();
				if (block.removedVanilla().stream().anyMatch(replacedLines::contains)) competing.add(i);
			}
			if (!competing.isEmpty()) {
				if (baseVanilla.isEmpty() && competing.stream().map(b.key::get).toList().equals(runKeys)
						&& sameLambdas(st, run.start(), run.end(), competing, 0)) {
					conflicts.add(new Outcome("same-edit", first, last, "the base made the same replacement"));
					return null;
				}
				// Not composed, even when the two rewrites differ only in the hook they call: each hook computes the
				// vanilla default itself (a game rule, a vanilla predicate) and may override it either way, so neither
				// chaining the checks as AND nor as OR keeps both families' overrides. The base's rewrite stands.
				conflicts.add(new Outcome("both-changed", first, last, "the base changed the same vanilla lines differently; lost "
						+ calls(o, run.start(), run.end()) + ", kept " + calls(b, competing)));
				return null;
			} else {
				if (baseVanilla.isEmpty()) {
					conflicts.add(new Outcome("base-deleted", first, last, "the base removed the vanilla lines this run replaces"));
					return null;
				}
				if (reimplemented(st, run, first, last, conflicts)) return null;
				// Only the vanilla lines the run replaced may go: every removed base instruction must come from one of
				// them, and they must be one contiguous stretch of the base body.
				for (int i : baseVanilla) {
					PatchLineProvenance.Origin origin = b.origin.get(i);
					if (origin == null || !replacedLines.contains(origin.vanilla()))
						throw new Refusal("the base lines in place of the run are not the lines it replaced");
				}
				if (baseVanilla.get(baseVanilla.size() - 1) - baseVanilla.get(0) + 1 != baseVanilla.size())
					throw new Refusal("the replaced base lines are interleaved with base edits");
				for (int i : baseVanilla) remove.add(b.code.get(i));
				int s = baseVanilla.get(0), e = baseVanilla.get(baseVanilla.size() - 1);
				if (b.stack(s) != 0 || (bNext < b.code.size() && b.stack(bNext) != 0)) throw new Refusal("replaced base lines are not a statement");
				// Nothing may jump into the middle of what is removed, and no exception region may start/end inside it.
				for (AbstractInsnNode n : b.method.instructions) {
					for (LabelNode target : targets(n)) {
						int t = b.at(target);
						if (t > s && t <= e) throw new Refusal("a base jump lands inside the replaced lines");
					}
				}
				for (TryCatchBlockNode tcb : b.method.tryCatchBlocks) {
					for (LabelNode l : List.of(tcb.start, tcb.end, tcb.handler)) {
						int t = b.at(l);
						if (t > s && t <= e) throw new Refusal("an exception region boundary lies inside the replaced lines");
					}
				}
				insertBefore = b.code.get(s);
				anchor = s;
			}
		}
		// Statement boundaries.
		if (o.stack(run.start()) != 0 || (nextV < o.code.size() && o.stack(nextV) != 0))
			throw new Refusal("run is not a statement in the other body");
		if (!run.replacement() && b.stack(anchor) != 0)
			throw new Refusal("insertion point is not a statement boundary");

		// Control flow of the run.
		Map<LabelNode, LabelNode> labels = new HashMap<>();
		LabelNode anchorLabel = new LabelNode();
		List<JumpRetarget> entries = new ArrayList<>();
		for (int i = run.start(); i <= run.end(); i++) {
			for (LabelNode target : targets(o.code.get(i))) {
				int t = o.at(target);
				if (t >= run.start() && t <= run.end()) continue;
				if (t == after) { labels.put(target, anchorLabel); continue; }
				Integer bt = match.get(t);
				if (bt == null || o.added(t)) throw new Refusal("run jumps to code the base cannot locate");
				LabelNode into = labelBefore(b, b.code.get(bt));
				st.runTargets.add(into);
				labels.put(target, into);
			}
		}
		for (int i = 0; i < o.code.size(); i++) {
			if (i >= run.start() && i <= run.end()) continue;
			Integer bj = match.get(i);
			if (i >= run.parentStart() && i <= run.parentEnd()) {
				// Another statement of the same run. One that is carried reaches this one through its own end, which
				// is placed right before this statement's copy; one the base inserted too reaches it through the
				// base's copy, whose jump is the counterpart.
				if (shared != null && shared.uncopied().contains(i) && !targets(o.code.get(i)).isEmpty())
					throw new Refusal("a statement the base has out of this run's order jumps into it");
				bj = shared == null ? null : shared.copyOf().get(i);
				if (bj == null) continue;
			}
			for (LabelNode target : targets(o.code.get(i))) {
				int t = o.at(target);
				if (t > run.start() && t <= run.end()) throw new Refusal("other code jumps into the middle of the run");
				if (t == run.start()) {
					// An entry into the run: its base counterpart must enter the inserted copy too. It may only have
					// landed on the instruction the run now precedes: one that landed further on skipped base code
					// that the run, which falls into that code, would now put on its path.
					if (bj == null) throw new Refusal("a jump into the run has no base counterpart");
					AbstractInsnNode bJump = b.code.get(bj);
					LabelNode from = null;
					boolean skipped = false;
					for (LabelNode l : targets(bJump)) {
						int at = b.at(l);
						if (at == anchor) from = l;
						else if (bNext >= 0 && at == bNext) skipped = true;
					}
					if (from == null) throw new Refusal(skipped ? "the run routes base code into base code that could not reach it"
							: "a jump into the run maps to a base jump elsewhere");
					entries.add(new JumpRetarget(bJump, from));
				}
			}
		}
		for (TryCatchBlockNode tcb : o.method.tryCatchBlocks) {
			int s = o.at(tcb.start), e = o.at(tcb.end), h = o.at(tcb.handler);
			boolean sIn = s >= run.start() && s <= run.end(), hIn = h >= run.start() && h <= run.end();
			boolean eIn = e > run.start() && e <= run.end() + 1;
			if (sIn != hIn || (sIn && !eIn)) throw new Refusal("an exception region straddles the run");
			// A region covering the run's start that ends inside it covers only part of it.
			if (!sIn && s <= run.start() && e > run.start() && e <= run.end()) throw new Refusal("an exception region straddles the run");
		}
		// Exception regions: the run must be covered by the same regions, by caught type and count, in the base as in
		// its own body. An instruction inserted before node X is inside [start, end) iff start is before X and end is
		// not before X, so a region whose end label sits in front of the anchor contains whatever is inserted before
		// that label. The insertion point is therefore chosen among the labels in front of the anchor.
		Map<String, Integer> want = coverage(o.method, o.code.get(run.start()));
		if (!run.replacement()) {
			insertBefore = insertionPoint(st, b.code.get(anchor), want);
			if (insertBefore == null) throw new Refusal("the run is covered by different exception regions in the two bodies");
		} else if (!coverageBefore(b.method, insertBefore).equals(want)) {
			throw new Refusal("the run is covered by different exception regions in the two bodies");
		}

		// Locals: one base slot per instruction. A local the run (or an earlier carried run) declared gets a fresh
		// slot; any other is found in the base by name and descriptor, live where the run goes.
		Map<AbstractInsnNode, Integer> slotFor = new IdentityHashMap<>();
		Map<LocalVariableNode, Integer> declaredHere = new IdentityHashMap<>();
		Map<Integer, Integer> temporaries = new HashMap<>();
		List<Store> stores = new ArrayList<>();
		int params = paramSlots(o.method);
		int[] cursor = { st.nextSlot[0] };
		for (int i = run.start(); i <= run.end(); i++) {
			AbstractInsnNode n = o.code.get(i);
			int slot = n instanceof VarInsnNode v ? v.var : n instanceof IincInsnNode inc ? inc.var : -1;
			if (slot < 0) continue;
			boolean store = n.getOpcode() >= Opcodes.ISTORE && n.getOpcode() <= Opcodes.ASTORE;
			LocalVariableNode lv = local(o.method, slot, store ? next(n) : n);
			int mapped;
			// Whether the slot is one the base itself has (a parameter, or a local found by name): only a write into
			// such a slot is a result the base's own code may or may not go on to read.
			boolean baseLocal = false;
			if (lv != null && st.declared.containsKey(lv)) {
				mapped = st.declared.get(lv);
			} else if (lv != null && declaredInside(o, lv, run)) {
				mapped = declaredHere.computeIfAbsent(lv, x -> { int f = cursor[0]; cursor[0] += Type.getType(x.desc).getSize(); return f; });
			} else if (lv == null && slot < params) {
				mapped = slot;
				baseLocal = true;
			} else if (lv == null) {
				// A store with no table entry: a compiler temporary that lives only inside the run.
				if (!store && !temporaries.containsKey(slot)) throw new Refusal("run reads a local with no name");
				mapped = temporaries.computeIfAbsent(slot, x -> { int f = cursor[0]; cursor[0] += 2; return f; });
			} else {
				LocalVariableNode bl = liveByName(b.method, lv.name, lv.desc, insertBefore);
				if (bl == null && slot < params) mapped = slot;
				else if (bl == null) throw new Refusal("local '" + lv.name + "' is not live at the insertion point in the base");
				else mapped = bl.index;
				baseLocal = true;
			}
			slotFor.put(n, mapped);
			if ((store || n instanceof IincInsnNode) && baseLocal)
				stores.add(new Store(slot, mapped, lv != null ? lv.name : "#" + slot));
		}

		// Methods and classes the run reaches.
		Map<String, String> lambdaRenames = new HashMap<>();
		for (int i = run.start(); i <= run.end(); i++) {
			AbstractInsnNode n = o.code.get(i);
			if (n instanceof InvokeDynamicInsnNode d) {
				for (Object arg : d.bsmArgs) {
					if (!(arg instanceof Handle h) || !h.getOwner().equals(in.owner().name)) continue;
					carryOwnMethod(in, h.getName(), h.getDesc(), lambdaRenames, st.addedMethods, 0);
				}
			}
			String touched = n instanceof TypeInsnNode t ? t.desc : n instanceof MethodInsnNode c ? c.owner : null;
			if (touched != null && isAnonymousSibling(in.owner().name, touched))
				throw new Refusal("run reaches an anonymous class whose numbering is pipeline-specific: " + touched);
			if (n instanceof MethodInsnNode c && c.owner.equals(in.owner().name) && c.name.startsWith("lambda$"))
				throw new Refusal("run calls a lambda body directly");
		}
		for (AbstractInsnNode n : remove) if (!st.claimed.add(n)) throw new Refusal("base lines already replaced by another run");
		st.nextSlot[0] = cursor[0];
		st.declared.putAll(declaredHere);
		AbstractInsnNode anchorReal = anchor < b.code.size() ? b.code.get(anchor) : insertBefore;
		return new Plan(kind, run, insertBefore, anchorReal, remove, labels, anchorLabel, new LabelNode(), slotFor, entries,
				lambdaRenames, stores);
	}

	/**
	 * What the base inserted of a statement's own run, around the statement. {@code lo..hi} (code indices) is where
	 * in the base's inserted code the statement may go: after the base's copy of the last earlier statement of its
	 * run that the base inserted as well, before the copy of the first later one. {@code copyOf} maps each
	 * instruction of a statement the base has a copy of to that copy's instruction; {@code uncopied} holds the
	 * instructions of a statement the base has, but out of the run's order.
	 */
	private record Shared(int lo, int hi, Map<Integer, Integer> copyOf, Set<Integer> uncopied) { }

	/** Copies are matched in the run's order; null when they leave no room for the statement. */
	private static Shared shared(Planning st, Run run, List<Integer> baseAdded, int bPrev, int bNext) {
		int lo = bPrev + 1, hi = bNext;
		Map<Integer, Integer> copyOf = new HashMap<>();
		Set<Integer> uncopied = new HashSet<>();
		if (run.start() == run.parentStart() && run.end() == run.parentEnd() || bNext < 0) return new Shared(lo, hi, copyOf, uncopied);
		int cursor = 0;
		for (Run s : statements(st.o, new Run(run.parentStart(), run.parentEnd(), Set.of()))) {
			if (s.start() == run.start()) continue;
			int at = indexOfCopy(st, baseAdded, s.start(), s.end(), cursor);
			if (at < 0) {
				if (indexOfCopy(st, baseAdded, s.start(), s.end(), 0) >= 0) for (int i = s.start(); i <= s.end(); i++) uncopied.add(i);
				continue;
			}
			cursor = at + s.end() - s.start() + 1;
			for (int i = s.start(); i <= s.end(); i++) copyOf.put(i, baseAdded.get(at + i - s.start()));
			int copyStart = baseAdded.get(at), copyEnd = baseAdded.get(cursor - 1) + 1;
			if (s.start() < run.start()) lo = Math.max(lo, copyEnd);
			else hi = Math.min(hi, copyStart);
		}
		return lo <= hi ? new Shared(lo, hi, copyOf, uncopied) : null;
	}

	/**
	 * Both patches reimplemented one feature: the unit acts on a member both patches added under one name, and the
	 * base's own edits in this method act on a member of the same owner and name. Running both implementations runs
	 * the feature twice (a block removed twice, a shared list cleared and filled twice, a capture closed twice), so
	 * the unit is a conflict and the base's implementation stands.
	 *
	 * <p>Acting on a member is what can run a feature twice: invoking a method, writing a field, or invoking a
	 * method on the value a member gave (a shared collection a field holds, or one a capture handed back into a
	 * local, being cleared, filled or spawned). Reading a constant or passing a method reference along is not.
	 * Which two members are one is {@link #sameMember}'s question. Hooks that each side keeps on its own platform
	 * classes never meet here: their owners differ.
	 */
	private static boolean reimplemented(Planning st, Run run, int first, int last, List<Outcome> conflicts) {
		Set<Member> ours = st.baseActsOn();
		if (ours.isEmpty()) return false;
		Annotated o = st.o;
		Set<Member> theirs = new LinkedHashSet<>();
		actsOn(o.method, o.code.subList(run.start(), run.end() + 1), st.otherSources(), theirs, st.in.otherMethods(),
				st.in.owner().name, new HashSet<>(), 0);
		MemberOrigin origin = st.in.members();
		for (Member m : theirs) {
			Member same = null;
			for (Member u : ours) if (sameMember(origin, m, u)) { same = u; break; }
			if (same == null || !origin.addedByBoth(m.owner(), m.name(), m.method())) continue;
			conflicts.add(new Outcome("both-reimplemented", first, last, "both patches added "
					+ m.owner().substring(m.owner().lastIndexOf('/') + 1) + "." + m.name() + " and both edits here act on it; lost "
					+ calls(o, run.start(), run.end())));
			return true;
		}
		return false;
	}

	/**
	 * Whether two acted-on members are one: the same field (a second descriptor is a second field of the merged
	 * class), the same method, or two methods of one name that are both private steps of the class's own
	 * implementation (each side's helper for the same step, retyped to what that side passes along). A method of
	 * any other kind that each side has in its own descriptor is that side's API, which its own mods call or
	 * override: each edit calling its own platform's version is that platform's hook, and the two do not meet.
	 */
	private static boolean sameMember(MemberOrigin origin, Member a, Member b) {
		if (!a.owner().equals(b.owner()) || !a.name().equals(b.name()) || a.method() != b.method()) return false;
		if (a.desc().equals(b.desc())) return true;
		return a.method() && origin.privateImplementation(a.owner(), a.name(), a.desc())
				&& origin.privateImplementation(b.owner(), b.name(), b.desc());
	}

	/**
	 * The members the given instructions of {@code m} act on: the methods they invoke, the fields they write, and the
	 * fields whose value they invoke a method on ({@code frames} are {@code m}'s source frames, or null when it did
	 * not analyze). A lambda of the owner the code captures stands for its body, not its name: the compiler numbers
	 * lambdas, so two compiles' {@code lambda$m$0} share a name by accident.
	 */
	private static void actsOn(MethodNode m, List<AbstractInsnNode> code, Frame<SourceValue>[] frames, Set<Member> out,
			Map<String, MethodNode> methods, String owner, Set<String> seen, int depth) {
		for (AbstractInsnNode n : code) {
			if (n instanceof MethodInsnNode c) {
				out.add(new Member(c.owner, c.name, true, c.desc));
				Member holder = c.getOpcode() == Opcodes.INVOKESTATIC || frames == null ? null : receiverMember(frames, m, c);
				if (holder != null) out.add(holder);
			} else if (n instanceof FieldInsnNode f && (f.getOpcode() == Opcodes.PUTFIELD || f.getOpcode() == Opcodes.PUTSTATIC)) {
				out.add(new Member(f.owner, f.name, false, f.desc));
			} else if (n instanceof InvokeDynamicInsnNode d) {
				for (Object arg : d.bsmArgs) {
					if (!(arg instanceof Handle h) || !h.getOwner().equals(owner)) continue;
					MethodNode body = methods.get(h.getName() + h.getDesc());
					if (body == null || (body.access & Opcodes.ACC_SYNTHETIC) == 0 || body.instructions.size() == 0) continue;
					if (depth >= 8 || !seen.add(h.getName() + h.getDesc())) continue;
					List<AbstractInsnNode> inner = new ArrayList<>();
					for (AbstractInsnNode x : body.instructions) if (x.getOpcode() >= 0) inner.add(x);
					actsOn(body, inner, sources(owner, body), out, methods, owner, seen, depth + 1);
				}
			}
		}
	}

	/** The field a call's receiver was read from, when every path loads it from that one field. */
	private static FieldInsnNode receiverField(Frame<SourceValue>[] frames, MethodNode m, MethodInsnNode c) {
		SourceValue receiver = receiver(frames, m, c);
		if (receiver == null) return null;
		FieldInsnNode field = null;
		for (AbstractInsnNode src : receiver.insns) {
			if (!(src instanceof FieldInsnNode g) || (g.getOpcode() != Opcodes.GETFIELD && g.getOpcode() != Opcodes.GETSTATIC)) return null;
			if (field != null && !(field.owner.equals(g.owner) && field.name.equals(g.name) && field.desc.equals(g.desc))) return null;
			field = g;
		}
		return field;
	}

	/**
	 * The member whose value a call's receiver is, when every path produces it from that one member: a field read,
	 * or a local every reaching store of which stores what one invocation or field read returned (the collection a
	 * capture hands back, kept in a local and then filled or emptied).
	 */
	private static Member receiverMember(Frame<SourceValue>[] frames, MethodNode m, MethodInsnNode c) {
		SourceValue receiver = receiver(frames, m, c);
		if (receiver == null) return null;
		Member found = null;
		for (AbstractInsnNode src : receiver.insns) {
			Member here;
			if (src instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
				Frame<SourceValue> at = frames[m.instructions.indexOf(load)];
				if (at == null) return null;
				here = null;
				for (AbstractInsnNode store : at.getLocal(load.var).insns) {
					if (!(store instanceof VarInsnNode v) || v.getOpcode() != Opcodes.ASTORE) return null;
					Frame<SourceValue> before = frames[m.instructions.indexOf(store)];
					if (before == null || before.getStackSize() == 0) return null;
					Member stored = null;
					for (AbstractInsnNode producer : before.getStack(before.getStackSize() - 1).insns) {
						Member p = produced(producer);
						if (p == null || (stored != null && !stored.equals(p))) return null;
						stored = p;
					}
					if (stored == null || (here != null && !here.equals(stored))) return null;
					here = stored;
				}
			} else {
				here = produced(src);
			}
			if (here == null || (found != null && !found.equals(here))) return null;
			found = here;
		}
		return found;
	}

	/** The member an instruction reads a value from: an invocation's result, or a field. */
	private static Member produced(AbstractInsnNode n) {
		if (n instanceof MethodInsnNode c) return new Member(c.owner, c.name, true, c.desc);
		if (n instanceof FieldInsnNode f && (f.getOpcode() == Opcodes.GETFIELD || f.getOpcode() == Opcodes.GETSTATIC))
			return new Member(f.owner, f.name, false, f.desc);
		return null;
	}

	private static SourceValue receiver(Frame<SourceValue>[] frames, MethodNode m, MethodInsnNode c) {
		Frame<SourceValue> f = frames[m.instructions.indexOf(c)];
		if (f == null) return null;
		int depth = f.getStackSize() - 1 - Type.getArgumentTypes(c.desc).length;
		return depth < 0 ? null : f.getStack(depth);
	}

	private static Frame<SourceValue>[] sources(String owner, MethodNode m) {
		try {
			return new Analyzer<>(new SourceInterpreter()).analyze(owner, m);
		} catch (AnalyzerException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Planned units that belong with a unit that was not carried, by the code itself: the same instructions (locals
	 * the compiler did not name left out, so javac's copies of one finally block compare equal), a call on an object
	 * loaded from the same static field (the two halves of a begin/end pair), or, within one run, a statement whose
	 * result a later statement reads (a hook fired by one statement and decided on by the next). Carrying such a unit
	 * alone runs a hook on some paths but not others, ends what was never begun, or fires a hook whose answer is
	 * dropped.
	 */
	private static Map<Span, Outcome> pairedWithholding(Planning st, List<Plan> plans, List<Run> lost) {
		if (lost.isEmpty()) return Map.of();
		Annotated o = st.o;
		Frame<SourceValue>[] sources = st.otherSources();
		Map<List<String>, Run> lostCode = new HashMap<>();
		Map<String, Run> lostReceivers = new HashMap<>();
		for (Run r : lost) {
			List<String> keys = normalizedKeys(o, r);
			if (!keys.isEmpty()) lostCode.putIfAbsent(keys, r);
			if (sources != null) for (String field : staticReceivers(sources, o, r)) lostReceivers.putIfAbsent(field, r);
		}
		Map<Span, Outcome> out = new LinkedHashMap<>();
		for (Plan p : plans) {
			Run u = p.run();
			String why = null;
			Run twin = lostCode.get(normalizedKeys(o, u));
			if (twin != null) {
				why = "a copy of the same code (L" + o.line.get(twin.start()) + "-" + o.line.get(twin.end())
						+ ") was not carried, so this one would run on only some paths";
			} else if (sources != null) {
				for (String field : staticReceivers(sources, o, u)) {
					Run pair = lostReceivers.get(field);
					if (pair == null) continue;
					why = "it calls on " + field.substring(field.lastIndexOf('/') + 1) + ", as L" + o.line.get(pair.start()) + "-"
							+ o.line.get(pair.end()) + " does, which was not carried";
					break;
				}
			}
			if (why == null && sources != null) {
				for (Run r : lost) {
					if (r.parentStart() != u.parentStart() || r.start() <= u.end() || !readsWhatItWrites(sources, o, r, u)) continue;
					why = "L" + o.line.get(r.start()) + "-" + o.line.get(r.end()) + " of the same run reads what it computes, and was not carried";
					break;
				}
			}
			if (why != null) out.put(u.span(), new Outcome("withheld", o.line.get(u.start()), o.line.get(u.end()), why));
		}
		return out;
	}

	/** Whether a load in {@code reader} reads a value a store in {@code writer} put in the local. */
	private static boolean readsWhatItWrites(Frame<SourceValue>[] frames, Annotated o, Run reader, Run writer) {
		for (int i = reader.start(); i <= reader.end(); i++) {
			AbstractInsnNode n = o.code.get(i);
			int slot = n instanceof VarInsnNode v && v.getOpcode() < Opcodes.ISTORE ? v.var : n instanceof IincInsnNode inc ? inc.var : -1;
			if (slot < 0) continue;
			Frame<SourceValue> f = frames[o.method.instructions.indexOf(n)];
			if (f == null) continue;
			for (AbstractInsnNode store : f.getLocal(slot).insns) {
				Integer at = o.position.get(store);
				if (at != null && at >= writer.start() && at <= writer.end()) return true;
			}
		}
		return false;
	}

	/** The unit's keys without its accesses to locals the compiler did not name. */
	private static List<String> normalizedKeys(Annotated o, Run u) {
		List<String> out = new ArrayList<>();
		for (int i = u.start(); i <= u.end(); i++) {
			AbstractInsnNode n = o.code.get(i);
			if ((n instanceof VarInsnNode || n instanceof IincInsnNode) && o.key.get(i).startsWith(n.getOpcode() + " #")) continue;
			out.add(o.key.get(i));
		}
		return out;
	}

	/** The static fields whose value is the receiver of a call in the unit, as "owner.name:desc". */
	private static Set<String> staticReceivers(Frame<SourceValue>[] frames, Annotated o, Run u) {
		Set<String> out = new LinkedHashSet<>();
		for (int i = u.start(); i <= u.end(); i++) {
			if (!(o.code.get(i) instanceof MethodInsnNode c) || c.getOpcode() == Opcodes.INVOKESTATIC) continue;
			FieldInsnNode g = receiverField(frames, o.method, c);
			if (g != null && g.getOpcode() == Opcodes.GETSTATIC) out.add(g.owner + "." + g.name + ":" + g.desc);
		}
		return out;
	}

	/**
	 * A run inserted past a label that another inserted run jumps to would be entered by that jump. Labels found or
	 * made for later runs' jumps are only known once every run is planned, so this is checked last.
	 */
	private static Map<Span, Outcome> passedRunTargets(Planning st, List<Plan> plans) {
		Map<Span, Outcome> out = new LinkedHashMap<>();
		for (Plan p : plans) {
			if (p.run().replacement()) continue;
			for (AbstractInsnNode n = labelBlockStart(p.anchorReal()); n != p.insertBefore() && n != null; n = n.getNext()) {
				if (!(n instanceof LabelNode l) || !st.runTargets.contains(l)) continue;
				out.put(p.run().span(), new Outcome(p.kind(), st.o.line.get(p.run().start()), st.o.line.get(p.run().end()),
						"another carried run jumps to where this run would go"));
				break;
			}
		}
		return out;
	}

	/**
	 * What only the finished body can show, per group of adjacent units of one run.
	 *
	 * <p>Dead output: a local the run writes that its own body reads after it must be read after it in the result
	 * too. Otherwise a hook's answer is computed and dropped (the kept body overwrites the local before reading it,
	 * or reads its own source of the value instead).
	 *
	 * <p>Reach-back: every base instruction the alignment maps that can reach the run in the result must map to an
	 * instruction that can reach the run in the other body. Otherwise the base routes code into the run that could
	 * not reach it there (a run that lived in one branch, placed where every branch meets).
	 */
	private Map<Span, Outcome> flowChecks(Planning st, List<Plan> plans, MethodNode result) {
		Annotated o = st.o, b = st.b;
		Flow theirs = st.otherFlow(), ours = new Flow(result);
		Map<Integer, Integer> back = new HashMap<>();
		st.match.forEach((oi, bi) -> back.put(bi, oi));
		Map<Span, Outcome> out = new LinkedHashMap<>();
		for (List<Plan> group : groups(plans)) {
			Plan head = group.get(0), tail = group.get(group.size() - 1);
			int from = ours.index(head.runStart()), to = ours.index(tail.anchorLabel());
			BitSet liveAfterUs = ours.liveAtExits(from, to);
			int theirFrom = theirs.index(pseudoBlockStart(o.code.get(head.run().start())));
			int theirTo = theirs.index(o.code.get(tail.run().end())) + 1;
			BitSet liveAfterThem = theirs.liveAtExits(theirFrom, theirTo);
			for (Plan p : group) {
				for (Store s : p.stores()) {
					if (!liveAfterThem.get(s.otherSlot()) || liveAfterUs.get(s.baseSlot())) continue;
					out.put(p.run().span(), refusal(st, p, "local '" + s.name()
							+ "' it writes is read after it in its own body, but its result reaches nothing in the base"));
					break;
				}
			}
			if (out.containsKey(head.run().span())) continue;
			BitSet reachUs = ours.canReach(ours.index(firstReal(head.runStart())));
			BitSet reachThem = theirs.canReach(theirs.index(o.code.get(head.run().start())));
			for (int i = reachUs.nextSetBit(0); i >= 0; i = reachUs.nextSetBit(i + 1)) {
				Integer bi = b.position.get(ours.nodes[i]);
				Integer oi = bi == null ? null : back.get(bi);
				if (oi == null || reachThem.get(theirs.index(o.code.get(oi)))) continue;
				out.put(head.run().span(), refusal(st, head, "the base routes code into the run that could not reach it"));
				break;
			}
		}
		return out;
	}

	private static Outcome refusal(Planning st, Plan p, String why) {
		return new Outcome(p.kind(), st.o.line.get(p.run().start()), st.o.line.get(p.run().end()), why);
	}

	/** Adjacent copies of units of one run: what the run as a whole became in the result. */
	private static List<List<Plan>> groups(List<Plan> plans) {
		List<List<Plan>> out = new ArrayList<>();
		for (Plan p : plans) {
			List<Plan> group = out.isEmpty() ? null : out.get(out.size() - 1);
			Plan prev = group == null ? null : group.get(group.size() - 1);
			if (prev != null && prev.run().parentStart() == p.run().parentStart() && prev.anchorLabel().getNext() == p.runStart()) group.add(p);
			else out.add(new ArrayList<>(List.of(p)));
		}
		return out;
	}

	private static AbstractInsnNode firstReal(AbstractInsnNode n) {
		while (n != null && n.getOpcode() < 0) n = n.getNext();
		if (n == null) throw new IllegalStateException("an inserted run without instructions");
		return n;
	}

	/** Successors, liveness and reachability over every node of a method, exception edges included. */
	private static final class Flow {
		final AbstractInsnNode[] nodes;
		private final Map<AbstractInsnNode, Integer> index = new IdentityHashMap<>();
		private final int[][] normal, handlers;
		private BitSet[] liveIn;

		Flow(MethodNode m) {
			nodes = m.instructions.toArray();
			for (int i = 0; i < nodes.length; i++) index.put(nodes[i], i);
			normal = new int[nodes.length][];
			for (int i = 0; i < nodes.length; i++) normal[i] = successors(i);
			List<List<Integer>> h = new ArrayList<>();
			for (int i = 0; i < nodes.length; i++) h.add(new ArrayList<>());
			for (TryCatchBlockNode t : m.tryCatchBlocks) {
				int s = index(t.start), e = index(t.end), handler = index(t.handler);
				for (int i = s; i < e; i++) if (nodes[i].getOpcode() >= 0) h.get(i).add(handler);
			}
			handlers = new int[nodes.length][];
			for (int i = 0; i < nodes.length; i++) handlers[i] = h.get(i).stream().mapToInt(Integer::intValue).toArray();
		}

		int index(AbstractInsnNode n) {
			Integer i = index.get(n);
			if (i == null) throw new IllegalStateException("node not in this method");
			return i;
		}

		private int[] successors(int i) {
			AbstractInsnNode n = nodes[i];
			int op = n.getOpcode();
			int fall = i + 1 < nodes.length ? i + 1 : -1;
			if (n instanceof JumpInsnNode j) {
				int t = index(j.label);
				return op == Opcodes.GOTO || fall < 0 ? new int[] { t } : new int[] { t, fall };
			}
			if (n instanceof TableSwitchInsnNode || n instanceof LookupSwitchInsnNode)
				return targets(n).stream().mapToInt(this::index).toArray();
			if ((op >= Opcodes.IRETURN && op <= Opcodes.RETURN) || op == Opcodes.ATHROW || op == Opcodes.RET) return new int[0];
			return fall < 0 ? new int[0] : new int[] { fall };
		}

		/** Which locals are live at the targets of the edges that leave nodes [from, to). */
		BitSet liveAtExits(int from, int to) {
			BitSet[] in = liveIn();
			BitSet live = new BitSet();
			for (int i = from; i < to; i++) for (int s : normal[i]) if (s < from || s >= to) live.or(in[s]);
			return live;
		}

		private BitSet[] liveIn() {
			if (liveIn != null) return liveIn;
			int n = nodes.length;
			int[] use = new int[n], def = new int[n];
			for (int i = 0; i < n; i++) {
				use[i] = def[i] = -1;
				if (nodes[i] instanceof VarInsnNode v) {
					if (v.getOpcode() >= Opcodes.ISTORE && v.getOpcode() <= Opcodes.ASTORE) def[i] = v.var; else use[i] = v.var;
				} else if (nodes[i] instanceof IincInsnNode inc) {
					use[i] = def[i] = inc.var;
				}
			}
			BitSet[] in = new BitSet[n];
			for (int i = 0; i < n; i++) in[i] = new BitSet();
			boolean changed = true;
			while (changed) {
				changed = false;
				for (int i = n - 1; i >= 0; i--) {
					BitSet live = new BitSet();
					for (int s : normal[i]) live.or(in[s]);
					if (def[i] >= 0) live.clear(def[i]);
					// An exception can leave before the instruction's own write: what the handler reads stays live.
					for (int h : handlers[i]) live.or(in[h]);
					if (use[i] >= 0) live.set(use[i]);
					if (!live.equals(in[i])) { in[i] = live; changed = true; }
				}
			}
			return liveIn = in;
		}

		/** Every node from which {@code target} can be reached by at least one edge. */
		BitSet canReach(int target) {
			List<List<Integer>> preds = new ArrayList<>();
			for (int i = 0; i < nodes.length; i++) preds.add(new ArrayList<>());
			for (int i = 0; i < nodes.length; i++) {
				for (int s : normal[i]) preds.get(s).add(i);
				for (int h : handlers[i]) preds.get(h).add(i);
			}
			BitSet seen = new BitSet();
			java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>(preds.get(target));
			while (!work.isEmpty()) {
				int i = work.pop();
				if (seen.get(i)) continue;
				seen.set(i);
				work.addAll(preds.get(i));
			}
			return seen;
		}
	}

	/**
	 * A method of the owner the run names through a method handle.
	 *
	 * <p>A lambda is named, not called, and its final body is the one its capturing method's side has: the merge
	 * puts every captured lambda back on its capturer's side once the class is merged, and the base's own code in
	 * the spliced method captures the base's lambdas. So the run's lambda is compared with the BASE SIDE's own lambda
	 * of that name, never with what the merged class holds while its methods are still being merged (the
	 * per-method merge may have put the other side's body under that name, which a comparison with the merged class
	 * then finds equal). The name is shared only when that is safe ({@link #shareable}); otherwise the other side's
	 * body is carried under a fresh name, with what it captures carried the same way.
	 *
	 * <p>A named method is merged on its own, by its name, and the run calls whatever the merged class keeps under
	 * it: that must be the run's own body, or the run is refused.
	 */
	private void carryOwnMethod(Input in, String name, String desc, Map<String, String> renames,
			Map<String, MethodNode> addedMethods, int depth) {
		if (depth > 8) throw new Refusal("lambda nesting too deep");
		String key = name + desc;
		if (renames.containsKey(key)) return;
		MethodNode theirs = in.otherMethods().get(key);
		if (theirs == null) {
			if (in.ownerMethods().containsKey(key)) return; // a method of the merged class, not a lambda of theirs
			throw new Refusal("run names a method the other side does not declare: " + key);
		}
		String owner = in.owner().name;
		if (name.startsWith("lambda$") && (theirs.access & Opcodes.ACC_SYNTHETIC) != 0) {
			if (shareable(in, key, 0)) { renames.put(key, name); return; }
		} else {
			MethodNode ours = in.ownerMethods().get(key);
			if (ours != null && sameStatic(ours, theirs) && bodyKey(ours).equals(bodyKey(theirs))) { renames.put(key, name); return; }
			throw new Refusal(ours == null ? "run names a method the merged class lacks: " + key
					: "run names a method whose body differs in the base: " + key);
		}
		String fresh = name + "$forbric" + (in.otherIsForge() ? "forge" : "neo");
		renames.put(key, fresh);
		MethodNode carried = copy(theirs);
		carried.name = fresh;
		addedMethods.put(fresh + desc, carried);
		for (AbstractInsnNode n : carried.instructions) {
			if (n instanceof InvokeDynamicInsnNode d) for (Object arg : d.bsmArgs)
				if (arg instanceof Handle h && h.getOwner().equals(owner)) carryOwnMethod(in, h.getName(), h.getDesc(), renames, addedMethods, depth + 1);
		}
		renameHandles(carried, owner, renames);
	}

	/**
	 * Whether a carried run may name the other side's lambda {@code key} as it is. When the base side has a lambda of
	 * that name, only if the two do the same thing ({@link #sameBody}): the merge leaves the base's under that name.
	 * When the base side has none, the merged class holds the other side's own lambda there (it was added whole), and
	 * the name is shared when that is the body the run captures and every lambda it captures in turn is shareable by
	 * the same rule; the other side's mods then still find it under the name their own platform gave it.
	 */
	private static boolean shareable(Input in, String key, int depth) {
		MethodNode theirs = in.otherMethods().get(key);
		if (theirs == null) return false;
		String owner = in.owner().name;
		MethodNode ours = in.baseMethods().get(key);
		if (ours != null) return sameBody(owner, in.otherMethods(), theirs, in.baseMethods(), ours);
		MethodNode merged = in.ownerMethods().get(key);
		if (merged == null || depth >= 8 || !sameStatic(merged, theirs) || !bodyKey(merged).equals(bodyKey(theirs))) return false;
		List<Handle> a = ownHandles(theirs, owner), b = ownHandles(merged, owner);
		if (!a.equals(b)) return false;
		for (Handle h : a) if (!shareable(in, h.getName() + h.getDesc(), depth + 1)) return false;
		return true;
	}

	/** The method handles into {@code owner} a method's call sites capture, in order. */
	private static List<Handle> ownHandles(MethodNode m, String owner) {
		List<Handle> out = new ArrayList<>();
		for (AbstractInsnNode n : m.instructions) {
			if (n instanceof InvokeDynamicInsnNode d) for (Object arg : d.bsmArgs)
				if (arg instanceof Handle h && h.getOwner().equals(owner)) out.add(h);
		}
		return out;
	}

	/**
	 * Whether two methods of {@code owner} do the same thing: the same static flag, the same instructions (lambdas
	 * without their number, locals by name), and through every method handle into the class, the same kind of
	 * handle to a method that again does the same thing. The left method's handles resolve among
	 * {@code leftMethods}, the right one's among {@code rightMethods}: the two compiles number their lambdas
	 * independently, so {@code lambda$m$0} on one side and {@code lambda$m$0} on the other share a name by accident.
	 */
	static boolean sameBody(String owner, Map<String, MethodNode> leftMethods, MethodNode left,
			Map<String, MethodNode> rightMethods, MethodNode right) {
		return sameBody(owner, leftMethods, left, rightMethods, right, 0);
	}

	private static boolean sameBody(String owner, Map<String, MethodNode> leftMethods, MethodNode left,
			Map<String, MethodNode> rightMethods, MethodNode right, int depth) {
		if (!sameStatic(left, right) || !bodyKey(left).equals(bodyKey(right))) return false;
		List<InvokeDynamicInsnNode> l = new ArrayList<>(), r = new ArrayList<>();
		for (AbstractInsnNode x : left.instructions) if (x instanceof InvokeDynamicInsnNode d) l.add(d);
		for (AbstractInsnNode x : right.instructions) if (x instanceof InvokeDynamicInsnNode d) r.add(d);
		if (l.size() != r.size()) return false;
		for (int j = 0; j < l.size(); j++) if (!sameCaptures(owner, leftMethods, l.get(j), rightMethods, r.get(j), depth)) return false;
		return true;
	}

	/** Whether two call sites capture, handle by handle, methods of {@code owner} that do the same thing. */
	private static boolean sameCaptures(String owner, Map<String, MethodNode> leftMethods, InvokeDynamicInsnNode left,
			Map<String, MethodNode> rightMethods, InvokeDynamicInsnNode right, int depth) {
		for (int k = 0; k < left.bsmArgs.length && k < right.bsmArgs.length; k++) {
			if (!(left.bsmArgs[k] instanceof Handle lh) || !(right.bsmArgs[k] instanceof Handle rh)) continue;
			if (lh.getTag() != rh.getTag()) return false;
			if (!lh.getOwner().equals(owner) || !rh.getOwner().equals(owner)) continue;
			MethodNode lb = leftMethods.get(lh.getName() + lh.getDesc()), rb = rightMethods.get(rh.getName() + rh.getDesc());
			if (lb == null || rb == null) {
				if (lb != rb) return false;
				continue;
			}
			if (depth >= 8 || !sameBody(owner, leftMethods, lb, rightMethods, rb, depth + 1)) return false;
		}
		return true;
	}

	private static boolean sameStatic(MethodNode a, MethodNode b) {
		return (a.access & Opcodes.ACC_STATIC) == (b.access & Opcodes.ACC_STATIC);
	}

	private static void renameHandles(MethodNode m, String owner, Map<String, String> renames) {
		for (AbstractInsnNode n : m.instructions) {
			if (!(n instanceof InvokeDynamicInsnNode d)) continue;
			for (int i = 0; i < d.bsmArgs.length; i++) {
				if (d.bsmArgs[i] instanceof Handle h && h.getOwner().equals(owner)) {
					String to = renames.get(h.getName() + h.getDesc());
					if (to != null && !to.equals(h.getName()))
						d.bsmArgs[i] = new Handle(h.getTag(), h.getOwner(), to, h.getDesc(), h.isInterface());
				}
			}
		}
	}

	private static String bodyKey(MethodNode m) {
		StringBuilder sb = new StringBuilder();
		Annotated a = new Annotated(m);
		for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) if (n.getOpcode() >= 0) sb.append(key(n, m, a)).append(';');
		return sb.toString();
	}

	private static boolean isAnonymousSibling(String owner, String type) {
		int dollar = owner.indexOf('$');
		String top = dollar < 0 ? owner : owner.substring(0, dollar);
		return type.startsWith(top + "$") && type.substring(type.lastIndexOf('$') + 1).chars().allMatch(Character::isDigit);
	}

	private static boolean declaredInside(Annotated o, LocalVariableNode lv, Run run) {
		int s = o.at(lv.start);
		// The table starts a variable after its first store; a store at the run's last instruction still counts.
		return s > run.start() && s <= run.end() + 1;
	}

	private static LocalVariableNode liveByName(MethodNode m, String name, String desc, AbstractInsnNode at) {
		int p = m.instructions.indexOf(at);
		LocalVariableNode best = null;
		for (LocalVariableNode lv : m.localVariables) {
			if (!lv.name.equals(name) || !lv.desc.equals(desc)) continue;
			int s = m.instructions.indexOf(lv.start), e = m.instructions.indexOf(lv.end);
			if (p >= s && p <= e) {
				if (best != null && best.index != lv.index) return null; // ambiguous
				best = lv;
			}
		}
		return best;
	}

	private static int paramSlots(MethodNode m) {
		int n = (m.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		for (Type t : Type.getArgumentTypes(m.desc)) n += t.getSize();
		return n;
	}

	private static int maxSlot(MethodNode m) {
		int max = paramSlots(m) - 1;
		for (AbstractInsnNode n : m.instructions) {
			if (n instanceof VarInsnNode v) max = Math.max(max, v.var + (v.getOpcode() == Opcodes.LLOAD || v.getOpcode() == Opcodes.DLOAD
					|| v.getOpcode() == Opcodes.LSTORE || v.getOpcode() == Opcodes.DSTORE ? 1 : 0));
			if (n instanceof IincInsnNode i) max = Math.max(max, i.var);
		}
		if (m.localVariables != null) for (LocalVariableNode lv : m.localVariables) max = Math.max(max, lv.index + Type.getType(lv.desc).getSize() - 1);
		return max;
	}

	/**
	 * base vanilla line minus other vanilla line, read off the aligned instructions next to the run. Both neighbours
	 * must agree when both exist; a run with neither is refused.
	 */
	private static int lineShift(Annotated o, Annotated b, Map<Integer, Integer> match, int prevM, int nextM) {
		Integer before = prevM >= 0 ? shiftAt(o, b, prevM, match.get(prevM)) : null;
		Integer after = nextM < o.code.size() && match.containsKey(nextM) ? shiftAt(o, b, nextM, match.get(nextM)) : null;
		if (before != null && after != null && !before.equals(after)) throw new Refusal("the two decompiles number this stretch differently");
		if (before == null && after == null) throw new Refusal("no aligned vanilla line next to the run");
		return before != null ? before : after;
	}

	private static Integer shiftAt(Annotated o, Annotated b, int oi, int bi) {
		PatchLineProvenance.Origin x = o.origin.get(oi), y = b.origin.get(bi);
		if (x == null || y == null || x.added() || y.added()) return null;
		return y.vanilla() - x.vanilla();
	}

	/** The calls an edit makes, for the report: what a conflict lost and what it kept. */
	private static String calls(Annotated a, int from, int to) {
		List<Integer> range = new ArrayList<>();
		for (int i = from; i <= to; i++) range.add(i);
		return calls(a, range);
	}

	private static String calls(Annotated a, List<Integer> indices) {
		Set<String> out = new LinkedHashSet<>();
		for (int i : indices) {
			if (a.code.get(i) instanceof MethodInsnNode c) out.add(c.owner.substring(c.owner.lastIndexOf('/') + 1) + "." + c.name);
		}
		return out.isEmpty() ? "[no calls]" : out.toString();
	}

	/**
	 * Where, from list position {@code from} on, the base's inserted instructions {@code baseAdded} hold a copy of
	 * the other body's instructions {@code oStart..oEnd}: the same instructions, and every lambda of the class they
	 * capture with the same body on both sides (the alignment key leaves lambda bodies out, and two sides that both
	 * capture {@code lambda$m$0} may each have put their own code in it). -1 when there is none.
	 */
	private static int indexOfCopy(Planning st, List<Integer> baseAdded, int oStart, int oEnd, int from) {
		List<String> baseKeys = baseAdded.stream().map(st.b.key::get).toList();
		List<String> keys = st.o.key.subList(oStart, oEnd + 1);
		for (int at = indexOfSequence(baseKeys, keys, from); at >= 0; at = indexOfSequence(baseKeys, keys, at + 1))
			if (sameLambdas(st, oStart, oEnd, baseAdded, at)) return at;
		return -1;
	}

	/** Whether the lambdas instructions {@code oStart..oEnd} capture have the bodies of the base's, from {@code at} on. */
	private static boolean sameLambdas(Planning st, int oStart, int oEnd, List<Integer> base, int at) {
		for (int i = oStart; i <= oEnd; i++) {
			if (st.o.code.get(i) instanceof InvokeDynamicInsnNode od && st.b.code.get(base.get(at + i - oStart)) instanceof InvokeDynamicInsnNode bd
					&& !sameLambdas(st.in, od, bd, 0)) return false;
		}
		return true;
	}

	private static boolean sameLambdas(Input in, InvokeDynamicInsnNode od, InvokeDynamicInsnNode bd, int depth) {
		return sameCaptures(in.owner().name, in.otherMethods(), od, in.baseMethods(), bd, depth);
	}

	private static int indexOfSequence(List<String> haystack, List<String> needle, int from) {
		outer:
		for (int i = from; i + needle.size() <= haystack.size(); i++) {
			for (int j = 0; j < needle.size(); j++) if (!haystack.get(i + j).equals(needle.get(j))) continue outer;
			return i;
		}
		return -1;
	}

	/** The first pseudo-node of the label/line/frame block immediately before a real instruction. */
	private static AbstractInsnNode labelBlockStart(AbstractInsnNode real) {
		AbstractInsnNode n = real;
		while (n.getPrevious() != null && n.getPrevious().getOpcode() < 0) n = n.getPrevious();
		return n;
	}

	/**
	 * Where an insertion before the anchor goes: the first node, from the start of the label block in front of the
	 * anchor, before which the exception coverage is {@code want}. It never moves past a label a jump, switch or
	 * handler lands on (that would put the run on those paths), nor past one an inserted run jumps to; null when no
	 * such node has the coverage.
	 */
	private static AbstractInsnNode insertionPoint(Planning st, AbstractInsnNode anchorReal, Map<String, Integer> want) {
		for (AbstractInsnNode x = labelBlockStart(anchorReal); ; x = x.getNext()) {
			if (coverageBefore(st.b.method, x).equals(want)) return x;
			if (x == anchorReal) return null;
			if (x instanceof LabelNode l && (st.pinned.contains(l) || st.runTargets.contains(l))) return null;
		}
	}

	/** A label that points at this real instruction (an existing one when there is one). */
	private static LabelNode labelBefore(Annotated b, AbstractInsnNode real) {
		for (AbstractInsnNode n = real.getPrevious(); n != null && n.getOpcode() < 0; n = n.getPrevious())
			if (n instanceof LabelNode l) return l;
		LabelNode fresh = new LabelNode();
		b.method.instructions.insertBefore(real, fresh);
		return fresh;
	}

	private static List<LabelNode> targets(AbstractInsnNode n) {
		if (n instanceof JumpInsnNode j) return List.of(j.label);
		if (n instanceof TableSwitchInsnNode t) { List<LabelNode> l = new ArrayList<>(t.labels); l.add(t.dflt); return l; }
		if (n instanceof LookupSwitchInsnNode t) { List<LabelNode> l = new ArrayList<>(t.labels); l.add(t.dflt); return l; }
		return List.of();
	}

	/** The exception regions, by caught type, that cover a real instruction. */
	private static Map<String, Integer> coverage(MethodNode m, AbstractInsnNode at) {
		int p = m.instructions.indexOf(at);
		Map<String, Integer> types = new TreeMap<>();
		for (TryCatchBlockNode t : m.tryCatchBlocks) {
			if (m.instructions.indexOf(t.start) <= p && p < m.instructions.indexOf(t.end))
				types.merge(String.valueOf(t.type), 1, Integer::sum);
		}
		return types;
	}

	/** The exception regions, by caught type, that cover code inserted immediately before {@code x}. */
	private static Map<String, Integer> coverageBefore(MethodNode m, AbstractInsnNode x) {
		int p = m.instructions.indexOf(x);
		Map<String, Integer> types = new TreeMap<>();
		for (TryCatchBlockNode t : m.tryCatchBlocks) {
			if (m.instructions.indexOf(t.start) < p && p <= m.instructions.indexOf(t.end))
				types.merge(String.valueOf(t.type), 1, Integer::sum);
		}
		return types;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Application

	private static void apply(MethodNode base, MethodNode other, Plan p) {
		Map<LabelNode, LabelNode> labels = new HashMap<>(p.labels());
		AbstractInsnNode first = realAt(other, p.run().start()), last = realAt(other, p.run().end());
		List<AbstractInsnNode> source = new ArrayList<>();
		for (AbstractInsnNode n = pseudoBlockStart(first); n != null; n = n.getNext()) {
			source.add(n);
			if (n == last) break;
		}
		Set<LabelNode> own = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		for (AbstractInsnNode n : source) if (n instanceof LabelNode l) { own.add(l); labels.put(l, new LabelNode()); }
		LabelNode runStart = p.runStart();
		InsnList copy = new InsnList();
		copy.add(runStart);
		for (AbstractInsnNode n : source) {
			if (n instanceof FrameNode || n instanceof LineNumberNode) continue;
			AbstractInsnNode c = n.clone(labels);
			Integer slot = p.slotFor().get(n);
			if (slot != null && c instanceof VarInsnNode v) v.var = slot;
			if (slot != null && c instanceof IincInsnNode i) i.var = slot;
			if (c instanceof InvokeDynamicInsnNode d) {
				for (int k = 0; k < d.bsmArgs.length; k++) if (d.bsmArgs[k] instanceof Handle h) {
					String to = p.lambdaRenames().get(h.getName() + h.getDesc());
					if (to != null && !to.equals(h.getName())) d.bsmArgs[k] = new Handle(h.getTag(), h.getOwner(), to, h.getDesc(), h.isInterface());
				}
			}
			copy.add(c);
		}
		// Exception regions wholly inside the run come along, innermost first.
		List<TryCatchBlockNode> inner = new ArrayList<>();
		for (TryCatchBlockNode t : other.tryCatchBlocks) {
			if (!own.contains(t.start) || !own.contains(t.handler)) continue;
			LabelNode end = own.contains(t.end) ? labels.get(t.end) : p.anchorLabel();
			inner.add(new TryCatchBlockNode(labels.get(t.start), end, labels.get(t.handler), t.type));
		}
		base.instructions.insertBefore(p.insertBefore(), copy);
		base.instructions.insertBefore(p.insertBefore(), p.anchorLabel());
		for (AbstractInsnNode n : p.removeFromBase()) base.instructions.remove(n);
		base.tryCatchBlocks.addAll(0, inner);
		for (JumpRetarget r : p.entryRetargets()) retarget(r.jump(), r.from(), runStart);
	}

	private static AbstractInsnNode realAt(MethodNode m, int index) {
		int i = 0;
		for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
			if (n.getOpcode() < 0) continue;
			if (i++ == index) return n;
		}
		throw new IllegalStateException("no instruction " + index);
	}

	/** Every pseudo-node (labels, lines, frames) directly before the run's first instruction belongs to the run. */
	private static AbstractInsnNode pseudoBlockStart(AbstractInsnNode first) {
		AbstractInsnNode n = first;
		while (n.getPrevious() != null && n.getPrevious().getOpcode() < 0) n = n.getPrevious();
		return n;
	}

	private static void retarget(AbstractInsnNode jump, LabelNode from, LabelNode to) {
		if (jump instanceof JumpInsnNode j && j.label == from) j.label = to;
		if (jump instanceof TableSwitchInsnNode t) { if (t.dflt == from) t.dflt = to; t.labels.replaceAll(l -> l == from ? to : l); }
		if (jump instanceof LookupSwitchInsnNode t) { if (t.dflt == from) t.dflt = to; t.labels.replaceAll(l -> l == from ? to : l); }
	}

	private static LabelNode firstLabel(MethodNode m) {
		if (m.instructions.getFirst() instanceof LabelNode l) return l;
		LabelNode l = new LabelNode();
		m.instructions.insert(l);
		return l;
	}

	private static LabelNode lastLabel(MethodNode m) {
		if (m.instructions.getLast() instanceof LabelNode l) return l;
		LabelNode l = new LabelNode();
		m.instructions.add(l);
		return l;
	}

	private static boolean hasLines(MethodNode m) {
		for (AbstractInsnNode n : m.instructions) if (n instanceof LineNumberNode) return true;
		return false;
	}

	static MethodNode copy(MethodNode m) {
		MethodNode c = new MethodNode(Opcodes.ASM9, m.access, m.name, m.desc, m.signature,
				m.exceptions == null ? null : m.exceptions.toArray(String[]::new));
		m.accept(c);
		return c;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Frames and verification against the merge's own declarations

	private MethodNode recomputeFrames(ClassNode owner, MethodNode method) {
		for (AbstractInsnNode n : method.instructions.toArray()) if (n instanceof FrameNode) method.instructions.remove(n);
		ClassNode shell = new ClassNode();
		shell.version = owner.version;
		shell.access = owner.access;
		shell.name = owner.name;
		shell.superName = owner.superName;
		shell.interfaces = owner.interfaces;
		shell.methods.add(method);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
			@Override
			protected String getCommonSuperClass(String a, String b) {
				return commonSuperClass(a, b);
			}

			@Override
			protected ClassLoader getClassLoader() {
				throw new IllegalStateException("the merge resolves types from its declaration graph");
			}
		};
		try {
			shell.accept(writer);
		} catch (RuntimeException e) {
			return null;
		}
		ClassNode read = new ClassNode();
		new ClassReader(writer.toByteArray()).accept(read, 0);
		return read.methods.get(0);
	}

	String commonSuperClass(String a, String b) {
		if (a.equals(b)) return a;
		if (graph.assignable("L" + b + ";", "L" + a + ";")) return a;
		if (graph.assignable("L" + a + ";", "L" + b + ";")) return b;
		ClassNode na = graph.node(a), nb = graph.node(b);
		if (na == null || nb == null || (na.access & Opcodes.ACC_INTERFACE) != 0 || (nb.access & Opcodes.ACC_INTERFACE) != 0)
			return "java/lang/Object";
		String cursor = na.superName;
		while (cursor != null) {
			if (graph.assignable("L" + b + ";", "L" + cursor + ";")) return cursor;
			ClassNode c = graph.node(cursor);
			cursor = c == null ? null : c.superName;
		}
		return "java/lang/Object";
	}

	private String verify(ClassNode owner, MethodNode method) {
		Type self = Type.getObjectType(owner.name);
		Type parent = owner.superName == null ? null : Type.getObjectType(owner.superName);
		List<Type> interfaces = owner.interfaces.stream().map(Type::getObjectType).toList();
		SimpleVerifier verifier = new SimpleVerifier(Opcodes.ASM9, self, parent, interfaces, (owner.access & Opcodes.ACC_INTERFACE) != 0) {
			@Override
			protected boolean isInterface(Type type) {
				ClassNode n = graph.node(type.getInternalName());
				return n != null && (n.access & Opcodes.ACC_INTERFACE) != 0;
			}

			@Override
			protected Type getSuperClass(Type type) {
				ClassNode n = graph.node(type.getInternalName());
				return n == null || n.superName == null ? null : Type.getObjectType(n.superName);
			}

			@Override
			protected boolean isAssignableFrom(Type type, Type other) {
				if (type.equals(other)) return true;
				if (type.getSort() == Type.OBJECT && graph.node(type.getInternalName()) == null) return true;
				if (other.getSort() == Type.OBJECT && graph.node(other.getInternalName()) == null) return true;
				if (type.getSort() == Type.OBJECT && isInterface(type)) return true; // the JVM checks interfaces at use
				return graph.assignable(other, type);
			}

			@Override
			protected Class<?> getClass(Type type) {
				throw new IllegalStateException("the merge resolves types from its declaration graph");
			}
		};
		try {
			new Analyzer<>(verifier).analyze(owner.name, method);
			return null;
		} catch (AnalyzerException | RuntimeException e) {
			return e.getMessage();
		}
	}
}
