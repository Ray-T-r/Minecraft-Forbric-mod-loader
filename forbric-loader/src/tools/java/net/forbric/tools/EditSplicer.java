/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import java.util.ArrayList;
import java.util.HashMap;
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
 *       the exception regions covering it are the same, by type, in both bodies.</li>
 *   <li>Every local it reads that it did not declare itself is found in the base body by name and descriptor,
 *       live at the insertion point. Locals it declares get fresh slots.</li>
 *   <li>In a constructor it comes after the call that initializes {@code this}, on both sides.</li>
 * </ul>
 *
 * A run that replaced vanilla lines replaces the base's copy of those lines only when the base left them vanilla.
 * When the base changed the same lines too, the two edits are kept once when they are the same instructions, and
 * otherwise the run is reported as a conflict and the base's edit stands. A run inserted where the base inserted
 * its own run goes first when it is MinecraftForge's, so on every shared insertion point MinecraftForge's code runs
 * before NeoForge's. Nothing here keys on a class, method or mod name.
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

	/** Both sides of one method, with everything a splice needs to read. */
	record Input(ClassNode owner, MethodNode base, MethodNode other, String sourcePath,
			PatchLineProvenance baseProvenance, PatchLineProvenance otherProvenance, boolean otherIsForge,
			Map<String, MethodNode> ownerMethods, Map<String, MethodNode> otherMethods) { }

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

	private record Run(int start, int end, Set<PatchLineProvenance.Block> blocks) {
		boolean replacement() {
			return blocks.stream().anyMatch(b -> !b.insertionOnly());
		}
	}

	private record Plan(String kind, Run run, AbstractInsnNode insertBefore, List<AbstractInsnNode> removeFromBase,
			Map<LabelNode, LabelNode> labels, LabelNode anchorLabel, Map<AbstractInsnNode, Integer> slotFor,
			List<JumpRetarget> entryRetargets, Map<String, String> lambdaRenames) { }

	/** A base jump that must enter the inserted run rather than skip to the anchor. */
	private record JumpRetarget(AbstractInsnNode jump, LabelNode from) { }

	/** Never throws: a defect in the splice is the method's refusal, and the merge keeps its existing decision. */
	Result splice(Input in) {
		try {
			return spliceUnchecked(in);
		} catch (RuntimeException e) {
			return refuse("splicer error: " + e);
		}
	}

	private Result spliceUnchecked(Input in) {
		List<Outcome> spliced = new ArrayList<>(), conflicts = new ArrayList<>(), refused = new ArrayList<>();
		MethodNode base = copy(in.base());
		MethodNode other = in.other();
		if (!base.desc.equals(other.desc) || (base.access & Opcodes.ACC_STATIC) != (other.access & Opcodes.ACC_STATIC))
			return refuse("descriptor or static flag differs");
		if (in.sourcePath() == null) return refuse("class names no source file");
		if (base.instructions.size() == 0 || other.instructions.size() == 0) return refuse("no code");
		if (!hasLines(base) || !hasLines(other)) return refuse("no line numbers");
		// A missing local variable table only limits which runs can be carried (locals are matched by name).
		if (base.localVariables == null) base.localVariables = new ArrayList<>();
		Annotated o, b;
		try {
			o = annotate(in.owner().name, other, in.otherProvenance(), in.sourcePath());
			b = annotate(in.owner().name, base, in.baseProvenance(), in.sourcePath());
		} catch (AnalyzerException e) {
			return refuse("analysis failed: " + e.getMessage());
		}
		Map<Integer, Integer> match = align(o, b);
		if (match.isEmpty()) return refuse("no shared vanilla code");
		long vanillaO = o.code.size() - countAdded(o), vanillaB = b.code.size() - countAdded(b);
		if (match.size() < 0.85 * Math.min(vanillaO, vanillaB))
			return refuse("vanilla parts do not align (" + match.size() + "/" + Math.min(vanillaO, vanillaB) + ")");

		List<Run> runs = runs(o);
		if (runs.isEmpty()) return refuse("the other side added nothing here");
		int oInit = initCall(o), bInit = initCall(b);
		List<Plan> plans = new ArrayList<>();
		Map<LocalVariableNode, Integer> declared = new IdentityHashMap<>();
		int[] nextSlot = { Math.max(base.maxLocals, maxSlot(base) + 1) };
		Map<String, MethodNode> addedMethods = new LinkedHashMap<>();
		Set<AbstractInsnNode> claimed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

		for (Run run : runs) {
			int first = o.line.get(run.start()), last = o.line.get(run.end());
			String why = null;
			Plan plan = null;
			try {
				plan = plan(in, o, b, match, run, oInit, bInit, declared, nextSlot, addedMethods, claimed, conflicts, spliced);
			} catch (Refusal r) {
				why = r.getMessage();
			}
			if (plan != null) plans.add(plan);
			else if (why != null) refused.add(new Outcome(run.replacement() ? "replace" : "insert", first, last, why));
		}
		if (plans.isEmpty()) return new Result(null, List.of(), spliced, conflicts, refused,
				spliced.isEmpty() ? "no run could be carried over" : "every other-side run is already in the base");

		// Base code reachable before the splice must stay reachable: a carried run that ends in a return or throw
		// would otherwise silently cut off what the base does after it.
		Set<AbstractInsnNode> reachable = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		for (int i = 0; i < b.code.size(); i++) if (b.stack(i) >= 0) reachable.add(b.code.get(i));
		for (Plan p : plans) apply(base, other, p);
		base.maxLocals = Math.max(base.maxLocals, nextSlot[0]);
		base.maxStack = Math.max(base.maxStack, other.maxStack) + 4;
		try {
			Frame<BasicValue>[] after = new Analyzer<>(new BasicInterpreter()).analyze(in.owner().name, base);
			for (int i = 0; i < after.length; i++) {
				AbstractInsnNode n = base.instructions.get(i);
				if (after[i] == null && reachable.contains(n))
					return new Result(null, List.of(), List.of(), conflicts, refused, "splice would make base code unreachable");
			}
		} catch (AnalyzerException e) {
			return new Result(null, List.of(), List.of(), conflicts, refused, "spliced body does not analyze: " + e.getMessage());
		}
		for (Plan p : plans) spliced.add(new Outcome(p.kind(), o.line.get(p.run().start()), o.line.get(p.run().end()), "carried over"));
		for (var e : declared.entrySet()) {
			LocalVariableNode lv = e.getKey();
			// Declared locals keep their names for debuggers and @Local captures; their scope is the whole method,
			// which is wider than the source scope but never wrong for a slot nothing else uses.
			base.localVariables.add(new LocalVariableNode(lv.name, lv.desc, null, firstLabel(base), lastLabel(base), e.getValue()));
		}
		MethodNode finished = recomputeFrames(in.owner(), base);
		if (finished == null) return new Result(null, List.of(), List.of(), conflicts, refused, "frames could not be computed");
		String verify = verify(in.owner(), finished);
		if (verify != null) return new Result(null, List.of(), List.of(), conflicts, refused, "does not verify: " + verify);
		return new Result(finished, List.copyOf(addedMethods.values()), spliced, conflicts, refused, "spliced");
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

	private Plan plan(Input in, Annotated o, Annotated b, Map<Integer, Integer> match, Run run, int oInit, int bInit,
			Map<LocalVariableNode, Integer> declared, int[] nextSlot, Map<String, MethodNode> addedMethods,
			Set<AbstractInsnNode> claimed, List<Outcome> conflicts, List<Outcome> spliced) {
		// The run's neighbours in the other body that the alignment maps. Normally the instructions right next to it;
		// when the one after it has no counterpart (a recompile difference, or a vanilla line the base rewrote), an
		// insertion can still be placed right after the base's counterpart of the one before it.
		int nextV = run.end() + 1;
		int nextM = nextV;
		while (nextM < o.code.size() && !match.containsKey(nextM)) nextM++;
		int prevM = run.start() - 1;
		while (prevM >= 0 && !match.containsKey(prevM)) prevM--;
		for (int i = prevM + 1; i < run.start(); i++) if (o.added(i)) throw new Refusal("another run of the other side is adjacent and unplaced");
		boolean gapAfter = nextM != nextV, endsMethod = nextM >= o.code.size();
		int bPrev = prevM < 0 ? -1 : match.get(prevM);
		int bNext;
		if (endsMethod && run.replacement() && nextV >= o.code.size()) bNext = b.code.size();
		else if (!gapAfter && !endsMethod) bNext = match.get(nextM);
		else if (!run.replacement() && prevM >= 0 && prevM == run.start() - 1 && bPrev + 1 < b.code.size()) bNext = -1; // placed after bPrev below
		else throw new Refusal(endsMethod ? "run ends the method" : "the vanilla instruction after the run has no counterpart");
		if (bNext >= 0 && bPrev >= bNext) throw new Refusal("alignment out of order");
		if (oInit >= 0 && run.start() <= oInit) throw new Refusal("run precedes this() initialization");
		if (bInit >= 0 && (bNext >= 0 ? bNext : bPrev + 1) <= bInit) throw new Refusal("anchor precedes this() initialization in the base");

		List<Integer> baseAdded = new ArrayList<>(), baseVanilla = new ArrayList<>();
		if (bNext >= 0) for (int i = bPrev + 1; i < bNext; i++) (b.added(i) ? baseAdded : baseVanilla).add(i);
		List<String> runKeys = o.key.subList(run.start(), run.end() + 1);
		List<String> baseKeys = baseAdded.stream().map(b.key::get).toList();
		int first = o.line.get(run.start()), last = o.line.get(run.end());

		AbstractInsnNode insertBefore;
		int anchor;
		String kind = run.replacement() ? "replace" : "insert";
		List<AbstractInsnNode> remove = new ArrayList<>();
		if (!run.replacement()) {
			if (!baseAdded.isEmpty() && containsSequence(baseKeys, runKeys)) {
				conflicts.add(new Outcome("same-edit", first, last, "the base already has this inserted code"));
				return null;
			}
			if (bNext >= 0) {
				anchor = bNext;
				// MinecraftForge's code goes before NeoForge's on a shared insertion point.
				if (in.otherIsForge()) while (anchor - 1 > bPrev && b.added(anchor - 1)) anchor--;
			} else {
				anchor = bPrev + 1;
				if (!in.otherIsForge()) while (anchor < b.code.size() && b.added(anchor)) anchor++;
				if (anchor >= b.code.size()) throw new Refusal("nothing follows the insertion point in the base");
			}
			insertBefore = labelBlockStart(b.code.get(anchor));
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
				if (baseVanilla.isEmpty() && competing.stream().map(b.key::get).toList().equals(runKeys)) {
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
				if (t == nextV) { labels.put(target, anchorLabel); continue; }
				Integer bt = match.get(t);
				if (bt == null || o.added(t)) throw new Refusal("run jumps to code the base cannot locate");
				labels.put(target, labelBefore(b, b.code.get(bt)));
			}
		}
		for (int i = 0; i < o.code.size(); i++) {
			if (i >= run.start() && i <= run.end()) continue;
			for (LabelNode target : targets(o.code.get(i))) {
				int t = o.at(target);
				if (t > run.start() && t <= run.end()) throw new Refusal("other code jumps into the middle of the run");
				if (t == run.start()) {
					// An entry into the run: its base counterpart must enter the inserted copy too.
					Integer bj = match.get(i);
					if (bj == null) throw new Refusal("a jump into the run has no base counterpart");
					AbstractInsnNode bJump = b.code.get(bj);
					LabelNode from = null;
					for (LabelNode l : targets(bJump)) if ((bNext >= 0 && b.at(l) == bNext) || b.at(l) == anchor) from = l;
					if (from == null) throw new Refusal("a jump into the run maps to a base jump elsewhere");
					entries.add(new JumpRetarget(bJump, from));
				}
			}
		}
		for (TryCatchBlockNode tcb : o.method.tryCatchBlocks) {
			int s = o.at(tcb.start), e = o.at(tcb.end), h = o.at(tcb.handler);
			boolean sIn = s >= run.start() && s <= run.end(), hIn = h >= run.start() && h <= run.end();
			boolean eIn = e > run.start() && e <= run.end() + 1;
			if (sIn != hIn || (sIn && !eIn)) throw new Refusal("an exception region straddles the run");
		}
		if (!sameCoverage(o, o.code.get(run.start()), b, insertBefore))
			throw new Refusal("the run is covered by different exception regions in the two bodies");

		// Locals: one base slot per instruction. A local the run (or an earlier carried run) declared gets a fresh
		// slot; any other is found in the base by name and descriptor, live where the run goes.
		Map<AbstractInsnNode, Integer> slotFor = new IdentityHashMap<>();
		Map<LocalVariableNode, Integer> declaredHere = new IdentityHashMap<>();
		Map<Integer, Integer> temporaries = new HashMap<>();
		int params = paramSlots(o.method);
		int[] cursor = { nextSlot[0] };
		for (int i = run.start(); i <= run.end(); i++) {
			AbstractInsnNode n = o.code.get(i);
			int slot = n instanceof VarInsnNode v ? v.var : n instanceof IincInsnNode inc ? inc.var : -1;
			if (slot < 0) continue;
			boolean store = n.getOpcode() >= Opcodes.ISTORE && n.getOpcode() <= Opcodes.ASTORE;
			LocalVariableNode lv = local(o.method, slot, store ? next(n) : n);
			int mapped;
			if (lv != null && declared.containsKey(lv)) {
				mapped = declared.get(lv);
			} else if (lv != null && declaredInside(o, lv, run)) {
				mapped = declaredHere.computeIfAbsent(lv, x -> { int f = cursor[0]; cursor[0] += Type.getType(x.desc).getSize(); return f; });
			} else if (lv == null && slot < params) {
				mapped = slot;
			} else if (lv == null) {
				// A store with no table entry: a compiler temporary that lives only inside the run.
				if (!store && !temporaries.containsKey(slot)) throw new Refusal("run reads a local with no name");
				mapped = temporaries.computeIfAbsent(slot, x -> { int f = cursor[0]; cursor[0] += 2; return f; });
			} else {
				LocalVariableNode bl = liveByName(b.method, lv.name, lv.desc, insertBefore);
				if (bl == null && slot < params) mapped = slot;
				else if (bl == null) throw new Refusal("local '" + lv.name + "' is not live at the insertion point in the base");
				else mapped = bl.index;
			}
			slotFor.put(n, mapped);
		}

		// Methods and classes the run reaches.
		Map<String, String> lambdaRenames = new HashMap<>();
		for (int i = run.start(); i <= run.end(); i++) {
			AbstractInsnNode n = o.code.get(i);
			if (n instanceof InvokeDynamicInsnNode d) {
				for (Object arg : d.bsmArgs) {
					if (!(arg instanceof Handle h) || !h.getOwner().equals(in.owner().name)) continue;
					carryOwnMethod(in, h.getName(), h.getDesc(), lambdaRenames, addedMethods, 0);
				}
			}
			String touched = n instanceof TypeInsnNode t ? t.desc : n instanceof MethodInsnNode c ? c.owner : null;
			if (touched != null && isAnonymousSibling(in.owner().name, touched))
				throw new Refusal("run reaches an anonymous class whose numbering is pipeline-specific: " + touched);
			if (n instanceof MethodInsnNode c && c.owner.equals(in.owner().name) && c.name.startsWith("lambda$"))
				throw new Refusal("run calls a lambda body directly");
		}
		for (AbstractInsnNode n : remove) if (!claimed.add(n)) throw new Refusal("base lines already replaced by another run");
		nextSlot[0] = cursor[0];
		declared.putAll(declaredHere);
		return new Plan(kind, run, insertBefore, remove, labels, anchorLabel, slotFor, entries, lambdaRenames);
	}

	/**
	 * A private synthetic method of the owner the run names through a method handle: reuse the merged class's
	 * method of that name when it is the same body, otherwise carry the other side's body under a fresh name.
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
		MethodNode ours = in.ownerMethods().get(key);
		if (ours != null && bodyKey(ours).equals(bodyKey(theirs))) { renames.put(key, name); return; }
		if (!name.startsWith("lambda$") || (theirs.access & Opcodes.ACC_SYNTHETIC) == 0) {
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
				if (arg instanceof Handle h && h.getOwner().equals(in.owner().name)) carryOwnMethod(in, h.getName(), h.getDesc(), renames, addedMethods, depth + 1);
		}
		renameHandles(carried, in.owner().name, renames);
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

	private static boolean containsSequence(List<String> haystack, List<String> needle) {
		outer:
		for (int i = 0; i + needle.size() <= haystack.size(); i++) {
			for (int j = 0; j < needle.size(); j++) if (!haystack.get(i + j).equals(needle.get(j))) continue outer;
			return true;
		}
		return false;
	}

	/** The first pseudo-node of the label/line/frame block immediately before a real instruction. */
	private static AbstractInsnNode labelBlockStart(AbstractInsnNode real) {
		AbstractInsnNode n = real;
		while (n.getPrevious() != null && n.getPrevious().getOpcode() < 0) n = n.getPrevious();
		return n;
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

	/** Same exception regions, by caught type, cover the run in the other body and the insertion point in the base. */
	private static boolean sameCoverage(Annotated o, AbstractInsnNode oAt, Annotated b, AbstractInsnNode bAt) {
		return coverage(o.method, oAt).equals(coverage(b.method, bAt));
	}

	private static Map<String, Integer> coverage(MethodNode m, AbstractInsnNode at) {
		int p = m.instructions.indexOf(at);
		Map<String, Integer> types = new TreeMap<>();
		for (TryCatchBlockNode t : m.tryCatchBlocks) {
			if (m.instructions.indexOf(t.start) <= p && p < m.instructions.indexOf(t.end))
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
		LabelNode runStart = new LabelNode();
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
