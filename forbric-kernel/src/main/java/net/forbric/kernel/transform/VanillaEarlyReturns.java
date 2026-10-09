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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableAnnotationNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Gives a merged method back the early {@code return}s vanilla compiled, so that a mixin's {@code @At("TAIL")} runs on
 * the paths it runs on in vanilla, and on no others.
 *
 * <p>Mixin's TAIL is a method's LAST return instruction. Both carriers' bases come out of a decompile-recompile
 * pipeline, and the decompiler turns a guard clause inside out: vanilla's
 * {@code if (player == null || this.level == null) return; ...body...} comes back as
 * {@code if (player != null && this.level != null) { ...body... }}. The early return is gone, and the paths that took it
 * now jump to the one return at the end — the one TAIL names. On vanilla, and so on Fabric, a TAIL handler runs only
 * after the body ran; on the merged game it also runs when the guard bailed out. Issue #31: TaCZ's
 * {@code Camera.update} TAIL handler asks {@code getCameraEntityPartialTicks}, which reads {@code this.level}, and
 * every frame of the title screen died on it — the body it was written to follow had returned early because there is
 * no level.
 *
 * <p>Measured against 26.2: 3633 methods whose last return is reached by more control-flow edges in the merged base
 * than in vanilla — 1319 of them {@code void}. A sweep of 1100 local mod jars found 70 Fabric TAIL injectors on them.
 * No ecosystem names any of these methods differently, so nothing in {@code merge-conflicts.txt} ever showed them.
 *
 * <p><b>How the paths are told apart.</b> An edge into a return is keyed by how it got there: the condition under which
 * it is taken (a conditional jump's opcode, the inverse of the one it falls past, or "unconditional") and the
 * normalised instructions of the basic block it leaves (operands by name, constants by value, local slots past the
 * parameters erased — the recompiler renumbers those). The decompiler inverts conditions and swaps fall-through for
 * jumps, but it does not change what a block computes, so the same key finds the same path in both bodies. The census
 * ({@code VanillaEarlyReturnsCensusTest}) keys vanilla's edges and writes, per affected method, the vanilla return
 * each key went to; {@code vanilla-early-returns.txt} ships that. At run time this class keys the merged body the same
 * way and gives an edge its own return only when its key occurs as many times in both bodies and vanilla sent that
 * occurrence to an early return. Several occurrences of one key that vanilla sent to different returns — a block that
 * is only {@code iconst_0} — are told apart by what leads into them, not by the order the recompiler happened to emit
 * them in. Anything it cannot pair keeps going to the tail: a missed split leaves today's behaviour, a wrong one would
 * hide a path from a handler, so ambiguity always loses.
 *
 * <p><b>What changes.</b> Never what the method computes: a retargeted edge reaches a return of the same opcode with
 * the same stack, one block earlier. Each vanilla early return that was folded becomes one block
 * ({@code label; full frame; return}) placed immediately before the tail, in vanilla's return order, so the tail
 * stays last — TAIL — and, where every early return was folded, {@code @At("RETURN", ordinal = n)} counts the way
 * vanilla's does. The blocks carry a full copy of the tail's frame (every edge into them was an edge into the tail
 * before), the tail's own frame is rewritten as full so the compressed frame after it still decodes, and try-catch
 * and local-variable ranges that ended at the tail are cut to end before the blocks, so the new code is covered by
 * exactly the ranges the tail is.
 *
 * <p><b>Who sees it.</b> TAIL now means what vanilla means, which is what a Fabric mod was compiled against. A
 * NeoForge or MinecraftForge mod was compiled against the folded body, where TAIL ran on every path;
 * {@code MixinNativeTail} rewrites those mods' TAIL to the returns that were the tail before, from
 * {@link #splitOf}, so their handlers keep running exactly where they always ran.
 *
 * <p>{@code -Dforbric.vanillaEarlyReturns=off} leaves every body as merged (and the TAIL rewrite stands down with it).
 */
public final class VanillaEarlyReturns implements ClassTransformer {
	public static final String PROPERTY = "forbric.vanillaEarlyReturns";
	static final String TABLE = "/net/forbric/kernel/transform/vanilla-early-returns.txt";
	/** How many normalised instructions of the leaving block go into a key: 8 moves at least one edge in 3245 of 3633. */
	static final int CONTEXT = 8;
	/**
	 * The fewest normalised instructions of each block leading INTO an edge's block that tell two occurrences of one
	 * key apart; the census takes more, up to {@link #CONTEXT}, only where vanilla needs more to tell its own apart.
	 * Short on purpose: the recompiler moves block boundaries (a pattern binding gains a {@code goto}), and the last few
	 * instructions before a branch are what survives that.
	 */
	static final int LEAD = 3;
	/** The label of an edge vanilla sends to its last return. */
	static final String TAIL = "T";
	/** Joins a label to its occurrence's lead in the table: {@code 1@3:0a1b2c3d}, the lead's length, then its hash. */
	static final char LEAD_MARK = '@';

	private static final String CAMERA = "net/minecraft/client/Camera";

	/** What a split left behind, for {@code MixinNativeTail}: returns before the new blocks, and how many blocks. */
	public record Split(int inlineReturns, int blocks) {
	}

	private static final Map<String, Split> SPLITS = new ConcurrentHashMap<>();
	private static volatile Map<String, Map<String, Map<String, List<String>>>> table;

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** The split this boot made in {@code owner.name+desc} (internal owner), or null when it made none. */
	public static Split splitOf(String owner, String name, String desc) {
		return SPLITS.get(owner + "#" + name + desc);
	}

	/** Every method this boot split, by owner, as {@code name+desc}. */
	public static Set<String> splitMethods(String owner) {
		Set<String> out = new HashSet<>();
		String prefix = owner + "#";
		for (String key : SPLITS.keySet()) if (key.startsWith(prefix)) out.add(key.substring(prefix.length()));
		return out;
	}

	@Override
	public String name() {
		return "forbric:vanilla-early-returns";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		return AnchorSet.of(new AnchorSet.Anchor(CAMERA.replace('/', '.'), AnchorSet.Severity.REQUIRED,
				"Camera.update's guard is folded into its tail — a Fabric TAIL handler there runs before any level "
						+ "exists and the title screen crashes (issue #31); and every other method in the shipped table "
						+ "keeps the same fold"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || classBytes == null || classBytes.length == 0) return classBytes;
		String internal = className.replace('.', '/');
		Map<String, Map<String, List<String>>> rows = table().get(internal);
		if (rows == null) return classBytes;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, 0);
			int methods = restore(node, rows);
			if (methods == 0) return classBytes;
			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			ForbricLog.debug("[Forbric/EarlyReturns] %s: %d method(s) return early again where vanilla does", className, methods);
			return writer.toByteArray();
		} catch (RuntimeException e) {
			ForbricLog.warn("[Forbric/EarlyReturns] could not restore " + className + "'s early returns; left as merged", e);
			return classBytes;
		}
	}

	/** Splits every method of {@code node} that {@code rows} names; returns how many it changed. */
	static int restore(ClassNode node, Map<String, Map<String, List<String>>> rows) {
		int changed = 0;
		for (MethodNode method : node.methods) {
			Map<String, List<String>> vanilla = rows.get(method.name + method.desc);
			if (vanilla == null || method.instructions.size() == 0) continue;
			AbstractInsnNode tail = lastReturn(method);
			if (tail == null) continue;
			List<Edge> edges = edges(method);
			Map<Edge, Integer> decisions = decide(method, edges, tail, vanilla);
			if (decisions.isEmpty()) continue;
			int inline = returns(method).size() - 1;
			int blocks = split(node.name, method, tail, edges, decisions);
			if (blocks == 0) continue;
			SPLITS.put(node.name + "#" + method.name + method.desc, new Split(inline, blocks));
			changed++;
		}
		return changed;
	}

	// --- the shipped table ---

	static Map<String, Map<String, Map<String, List<String>>>> table() {
		Map<String, Map<String, Map<String, List<String>>>> loaded = table;
		if (loaded != null) return loaded;
		synchronized (VanillaEarlyReturns.class) {
			if (table == null) table = read();
			return table;
		}
	}

	private static Map<String, Map<String, Map<String, List<String>>>> read() {
		Map<String, Map<String, Map<String, List<String>>>> out = new HashMap<>();
		try (InputStream in = VanillaEarlyReturns.class.getResourceAsStream(TABLE)) {
			if (in == null) {
				ForbricLog.warn("[Forbric/EarlyReturns] %s is missing — no merged method gets vanilla's early returns back", TABLE);
				return out;
			}
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				Row row = Row.parse(line);
				if (row != null) out.computeIfAbsent(row.owner(), o -> new HashMap<>()).put(row.method(), row.keys());
			}
		} catch (IOException unreadable) {
			ForbricLog.warn("[Forbric/EarlyReturns] could not read " + TABLE, unreadable);
		}
		int methods = out.values().stream().mapToInt(Map::size).sum();
		ForbricLog.info("[Forbric/EarlyReturns] %d merged methods in %d classes get vanilla's early returns back as they load, "
				+ "so a mixin's TAIL runs where it runs on vanilla (-D%s=off keeps the merged shape)", methods, out.size(), PROPERTY);
		return out;
	}

	/** One line: {@code owner#name+desc key=label,label ...}, labels being vanilla return indices or {@value #TAIL}. */
	record Row(String owner, String method, Map<String, List<String>> keys) {
		static Row parse(String line) {
			line = line.strip();
			if (line.isEmpty() || line.startsWith("#")) return null;
			String[] parts = line.split(" ");
			int hash = parts[0].indexOf('#');
			if (hash <= 0 || parts.length < 2) return null;
			Map<String, List<String>> keys = new LinkedHashMap<>();
			for (int i = 1; i < parts.length; i++) {
				int eq = parts[i].indexOf('=');
				if (eq <= 0) return null;
				keys.put(parts[i].substring(0, eq), List.of(parts[i].substring(eq + 1).split(",")));
			}
			return new Row(parts[0].substring(0, hash), parts[0].substring(hash + 1), keys);
		}

		String format() {
			StringJoiner line = new StringJoiner(" ");
			line.add(owner + "#" + method);
			keys.forEach((key, labels) -> line.add(key + "=" + String.join(",", labels)));
			return line.toString();
		}
	}

	// --- edges into returns, and their keys ---

	enum Kind { JUMP, GOTO, FALL, SWITCH }

	/**
	 * One way control reaches a return. {@code source} is the jump or switch that takes it, or the return itself for
	 * a fall-through; {@code entry} is the switch entry ({@code -1} for its default).
	 */
	record Edge(Kind kind, AbstractInsnNode source, int entry, String key, AbstractInsnNode target) {
		@Override
		public boolean equals(Object other) {
			return this == other;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(this);
		}
	}

	/** Every edge into every return of {@code method}, in instruction order. */
	static List<Edge> edges(MethodNode method) {
		Set<LabelNode> entries = entries(method);
		int fixed = fixedSlots(method);
		List<Edge> out = new ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof JumpInsnNode jump && jump.getOpcode() != Opcodes.JSR) {
				AbstractInsnNode target = resolve(jump.label);
				if (!isReturn(target)) continue;
				if (jump.getOpcode() != Opcodes.GOTO) {
					out.add(new Edge(Kind.JUMP, jump, 0, hash(jump.getOpcode() + "|" + context(jump, entries, fixed)), target));
				} else {
					// A GOTO only other jumps reach is a link in their chain; they are counted at their sources.
					AbstractInsnNode before = previousReal(jump);
					if (before != null && !endsFlow(before)) {
						out.add(new Edge(Kind.GOTO, jump, 0, hash("U|" + context(jump, entries, fixed)), target));
					}
				}
			} else if (insn instanceof TableSwitchInsnNode table) {
				for (int k = 0; k < table.labels.size(); k++) {
					switchEdge(out, table, k, "S" + (table.min + k), table.labels.get(k), entries, fixed);
				}
				switchEdge(out, table, -1, "SD", table.dflt, entries, fixed);
			} else if (insn instanceof LookupSwitchInsnNode lookup) {
				for (int k = 0; k < lookup.labels.size(); k++) {
					switchEdge(out, lookup, k, "S" + lookup.keys.get(k), lookup.labels.get(k), entries, fixed);
				}
				switchEdge(out, lookup, -1, "SD", lookup.dflt, entries, fixed);
			} else if (isReturn(insn)) {
				AbstractInsnNode before = previousReal(insn);
				if (before == null || endsFlow(before)) continue;
				String key = before instanceof JumpInsnNode passed
						? inverse(passed.getOpcode()) + "|" + context(passed, entries, fixed)
						: "U|" + context(before.getNext(), entries, fixed);
				out.add(new Edge(Kind.FALL, insn, 0, hash(key), insn));
			}
		}
		return out;
	}

	private static void switchEdge(List<Edge> out, AbstractInsnNode table, int entry, String sense, LabelNode label,
			Set<LabelNode> entries, int fixed) {
		AbstractInsnNode target = resolve(label);
		if (isReturn(target)) out.add(new Edge(Kind.SWITCH, table, entry, hash(sense + "|" + context(table, entries, fixed)), target));
	}

	/** The local slots of {@code this} and the parameters: the ones the recompiler cannot renumber. */
	private static int fixedSlots(MethodNode method) {
		int fixed = Type.getArgumentsAndReturnSizes(method.desc) >> 2;
		if ((method.access & Opcodes.ACC_STATIC) != 0) fixed--;
		return fixed;
	}

	/** Labels control can arrive at other than by falling through: jump and switch targets, handler starts. */
	private static Set<LabelNode> entries(MethodNode method) {
		Set<LabelNode> out = new HashSet<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof JumpInsnNode jump) out.add(jump.label);
			else if (insn instanceof TableSwitchInsnNode table) { out.addAll(table.labels); out.add(table.dflt); }
			else if (insn instanceof LookupSwitchInsnNode lookup) { out.addAll(lookup.labels); out.add(lookup.dflt); }
		}
		if (method.tryCatchBlocks != null) for (TryCatchBlockNode handler : method.tryCatchBlocks) out.add(handler.handler);
		return out;
	}

	/**
	 * The last {@value #CONTEXT} instructions of the basic block that ends just before {@code end}: walking back stops
	 * at anything control can enter by (a target label) or leave by (a jump, switch, return or throw).
	 */
	private static String context(AbstractInsnNode end, Set<LabelNode> entries, int fixed) {
		return context(end, entries, fixed, CONTEXT);
	}

	private static String context(AbstractInsnNode end, Set<LabelNode> entries, int fixed, int limit) {
		StringJoiner out = new StringJoiner("|");
		int taken = 0;
		for (AbstractInsnNode insn = end.getPrevious(); insn != null && taken < limit; insn = insn.getPrevious()) {
			if (insn instanceof LabelNode label) {
				if (entries.contains(label)) break;
				continue;
			}
			int opcode = insn.getOpcode();
			if (opcode < 0 || opcode == Opcodes.NOP) continue;
			if (endsFlow(insn) || insn instanceof JumpInsnNode) break;
			out.add(normalised(insn, fixed));
			taken++;
		}
		return out.toString();
	}

	/**
	 * One instruction as the recompiler cannot have changed it: operands by name, constants by value whatever opcode
	 * pushed them, and a local by its slot only while that slot is {@code this} or a parameter.
	 */
	private static String normalised(AbstractInsnNode insn, int fixed) {
		int opcode = insn.getOpcode();
		if (insn instanceof VarInsnNode var) return opcode + (var.var < fixed ? "@" + var.var : "@L");
		if (insn instanceof IincInsnNode inc) return "IINC" + (inc.var < fixed ? "@" + inc.var : "@L") + ":" + inc.incr;
		if (insn instanceof FieldInsnNode field) return opcode + " " + field.owner + "." + field.name + field.desc;
		if (insn instanceof MethodInsnNode call) return opcode + " " + call.owner + "." + call.name + call.desc;
		if (insn instanceof TypeInsnNode type) return opcode + " " + type.desc;
		if (insn instanceof InvokeDynamicInsnNode indy) return "INDY " + indy.name + indy.desc;
		if (insn instanceof MultiANewArrayInsnNode multi) return "MULTI " + multi.desc + multi.dims;
		if (insn instanceof IntInsnNode push && opcode != Opcodes.NEWARRAY) return "C" + push.operand;
		if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return "C" + (opcode - Opcodes.ICONST_0);
		if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1) return "C" + (opcode - Opcodes.LCONST_0) + "L";
		if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) return "C" + (float) (opcode - Opcodes.FCONST_0) + "F";
		if (opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1) return "C" + (double) (opcode - Opcodes.DCONST_0) + "D";
		if (insn instanceof LdcInsnNode ldc) {
			Object c = ldc.cst;
			if (c instanceof Integer) return "C" + c;
			if (c instanceof Long) return "C" + c + "L";
			if (c instanceof Float) return "C" + c + "F";
			if (c instanceof Double) return "C" + c + "D";
			return "LDC " + c;
		}
		return String.valueOf(opcode);
	}

	static String hash(String key) {
		return String.format("%08x", key.hashCode());
	}

	// --- pairing ---

	/**
	 * Which merged edges into {@code tail} vanilla sent to an early return, and to which one (its index among
	 * vanilla's returns). A key is paired only when it occurs as often here as in {@code vanilla}.
	 *
	 * <p>When one key's occurrences went to different returns in vanilla, order alone does not say which is which: the
	 * recompiler may emit the blocks the other way round ({@code Identifier.equals}: vanilla's "not an Identifier"
	 * {@code false} comes last, the merged one first, and pairing by order moved the path vanilla sends to the tail).
	 * Those occurrences carry a lead ({@link #lead}) in the table, and pair by it; occurrences a lead cannot tell apart
	 * stay on the tail. And a split that would leave the tail with no edge at all is not made: some edge vanilla sends
	 * to the tail was taken for an early one.
	 */
	static Map<Edge, Integer> decide(MethodNode method, List<Edge> merged, AbstractInsnNode tail, Map<String, List<String>> vanilla) {
		Map<String, List<Edge>> byKey = new LinkedHashMap<>();
		for (Edge edge : merged) byKey.computeIfAbsent(edge.key(), k -> new ArrayList<>()).add(edge);
		Map<Edge, Integer> out = new IdentityHashMap<>();
		Set<LabelNode> entries = entries(method);
		int fixed = fixedSlots(method);
		byKey.forEach((key, here) -> {
			List<String> there = vanilla.get(key);
			if (there == null) return;
			if (there.stream().noneMatch(label -> label.indexOf(LEAD_MARK) >= 0)) {
				// Every occurrence went to the same return: any pairing is the same pairing, once the counts agree.
				if (there.size() != here.size()) return;
				for (int k = 0; k < here.size(); k++) pair(out, here.get(k), there.get(k), tail);
				return;
			}
			Map<String, List<String>> thereByLead = new LinkedHashMap<>();
			int length = LEAD;
			for (String label : there) {
				int mark = label.indexOf(LEAD_MARK), colon = label.indexOf(':', mark + 1);
				if (mark < 0 || colon < 0) return;
				length = Integer.parseInt(label.substring(mark + 1, colon));
				thereByLead.computeIfAbsent(label.substring(colon + 1), l -> new ArrayList<>()).add(label.substring(0, mark));
			}
			Map<String, List<Edge>> hereByLead = new LinkedHashMap<>();
			for (Edge edge : here) hereByLead.computeIfAbsent(lead(method, edge, entries, fixed, length), l -> new ArrayList<>()).add(edge);
			// Lead by lead: an occurrence whose lead vanilla does not have — an early return a kernel hook put in front
			// (Player.hurtServer's attack seam) — is nobody's pair and stays where it is, and does not unpair the rest.
			thereByLead.forEach((lead, labels) -> {
				List<Edge> edges = hereByLead.get(lead);
				if (edges == null || edges.size() != labels.size()) return;
				// What the lead cannot tell apart pairs in order only while none of it is vanilla's tail path: the tail
				// stays right whichever way round, at worst two early returns swap ordinals.
				if (labels.stream().distinct().count() > 1 && labels.contains(TAIL)) return;
				for (int k = 0; k < edges.size(); k++) pair(out, edges.get(k), labels.get(k), tail);
			});
		});
		if (out.isEmpty()) return out;
		for (Edge edge : merged) {
			if (edge.target() == tail && !out.containsKey(edge)) return out;
		}
		return new IdentityHashMap<>();
	}

	private static void pair(Map<Edge, Integer> out, Edge edge, String label, AbstractInsnNode tail) {
		if (edge.target() == tail && !TAIL.equals(label)) out.put(edge, Integer.parseInt(label));
	}

	/**
	 * {@code key → label of each occurrence} for vanilla's {@code method}, the shape the table ships. Where one key's
	 * occurrences went to different returns, each label carries its occurrence's {@link #lead}.
	 */
	static Map<String, List<String>> labels(MethodNode method) {
		List<AbstractInsnNode> returns = returns(method);
		AbstractInsnNode tail = returns.isEmpty() ? null : returns.get(returns.size() - 1);
		Map<String, List<Edge>> byKey = new LinkedHashMap<>();
		for (Edge edge : edges(method)) byKey.computeIfAbsent(edge.key(), k -> new ArrayList<>()).add(edge);
		Set<LabelNode> entries = entries(method);
		int fixed = fixedSlots(method);
		Map<String, List<String>> out = new LinkedHashMap<>();
		byKey.forEach((key, edges) -> {
			List<String> labels = new ArrayList<>();
			for (Edge edge : edges) labels.add(edge.target() == tail ? TAIL : Integer.toString(returns.indexOf(edge.target())));
			if (labels.stream().distinct().count() > 1) {
				int length = LEAD;
				List<String> leads = leads(method, edges, entries, fixed, length);
				while (length < CONTEXT && !separates(leads, labels)) leads = leads(method, edges, entries, fixed, ++length);
				for (int k = 0; k < edges.size(); k++) labels.set(k, labels.get(k) + LEAD_MARK + length + ":" + leads.get(k));
			}
			out.put(key, labels);
		});
		return out;
	}

	/**
	 * What leads into the basic block {@code edge} leaves: for each way control enters it — a jump or switch case
	 * (followed through {@code goto}s), or falling in from the instruction before — the condition under which it enters
	 * (a jump's own opcode when the block is its target, the inverse when the block is what it falls past) and the last
	 * {@code length} normalised instructions before that branch. The recompiler inverts a condition only by swapping
	 * which block is the target, so the condition a block is entered under survives it; sorted, so the order it emits
	 * predecessors in does not count.
	 */
	static String lead(MethodNode method, Edge edge, Set<LabelNode> entries, int fixed, int length) {
		return hash(leadText(method, edge, entries, fixed, length));
	}

	private static List<String> leads(MethodNode method, List<Edge> edges, Set<LabelNode> entries, int fixed, int length) {
		List<String> out = new ArrayList<>();
		for (Edge edge : edges) out.add(lead(method, edge, entries, fixed, length));
		return out;
	}

	/** Whether no two occurrences that went to different returns share a lead. */
	private static boolean separates(List<String> leads, List<String> labels) {
		for (int i = 0; i < leads.size(); i++) {
			for (int j = i + 1; j < leads.size(); j++) {
				if (leads.get(i).equals(leads.get(j)) && !labels.get(i).equals(labels.get(j))) return false;
			}
		}
		return true;
	}

	static String leadText(MethodNode method, Edge edge, Set<LabelNode> entries, int fixed, int length) {
		AbstractInsnNode end = edge.source();
		if (edge.kind() == Kind.FALL) {
			// The block that falls in, not the return: a label other edges reach may sit between the two.
			AbstractInsnNode before = previousReal(edge.source());
			end = before instanceof JumpInsnNode ? before : before.getNext();
		}
		AbstractInsnNode boundary = end.getPrevious();
		while (boundary != null) {
			if (boundary instanceof LabelNode label && entries.contains(label)) break;
			if (boundary.getOpcode() >= 0 && (endsFlow(boundary) || boundary instanceof JumpInsnNode)) break;
			boundary = boundary.getPrevious();
		}
		List<String> leads = new ArrayList<>();
		if (boundary == null) {
			leads.add("ENTRY");
		} else if (boundary instanceof JumpInsnNode jump && jump.getOpcode() != Opcodes.GOTO && jump.getOpcode() != Opcodes.JSR) {
			leads.add(inverse(jump.getOpcode()) + "|" + context(jump, entries, fixed, length));
		} else if (boundary instanceof LabelNode) {
			AbstractInsnNode first = real(boundary);
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof JumpInsnNode jump && jump.getOpcode() != Opcodes.JSR) {
					if (jump.getOpcode() == Opcodes.GOTO) {
						// A goto only other branches reach is a link in their chain; they are counted at their sources.
						AbstractInsnNode before = previousReal(jump);
						if (before == null || endsFlow(before)) continue;
					}
					if (resolve(jump.label) == first) {
						leads.add((jump.getOpcode() == Opcodes.GOTO ? "U" : jump.getOpcode()) + "|" + context(jump, entries, fixed, length));
					}
				} else if (insn instanceof TableSwitchInsnNode table) {
					for (int k = 0; k < table.labels.size(); k++) {
						if (resolve(table.labels.get(k)) == first) leads.add("S" + (table.min + k) + "|" + context(table, entries, fixed, length));
					}
					if (resolve(table.dflt) == first) leads.add("SD|" + context(table, entries, fixed, length));
				} else if (insn instanceof LookupSwitchInsnNode lookup) {
					for (int k = 0; k < lookup.labels.size(); k++) {
						if (resolve(lookup.labels.get(k)) == first) leads.add("S" + lookup.keys.get(k) + "|" + context(lookup, entries, fixed, length));
					}
					if (resolve(lookup.dflt) == first) leads.add("SD|" + context(lookup, entries, fixed, length));
				}
			}
			AbstractInsnNode before = previousReal(boundary);
			if (before != null && !endsFlow(before)) {
				leads.add(before instanceof JumpInsnNode jump
						? inverse(jump.getOpcode()) + "|" + context(jump, entries, fixed, length)
						: "U|" + context(boundary, entries, fixed, length));
			}
			if (method.tryCatchBlocks != null) {
				for (TryCatchBlockNode handler : method.tryCatchBlocks) {
					if (real(handler.handler) == first) leads.add("H" + handler.type);
				}
			}
		}
		Collections.sort(leads);
		return String.join("/", leads);
	}

	// --- the split ---

	/**
	 * Gives each decided edge the block for its vanilla return; returns how many blocks it placed, or 0 when the
	 * tail is not a shape this can split without guessing (no frame there to copy). {@code edges} is the list the
	 * decisions were taken from: edges are compared by identity.
	 */
	static int split(String owner, MethodNode method, AbstractInsnNode tail, List<Edge> edges, Map<Edge, Integer> decisions) {
		AbstractInsnNode runStart = tail;
		FrameNode tailFrame = null;
		LabelNode tailLabel = null;
		Set<LabelNode> run = new HashSet<>();
		for (AbstractInsnNode insn = tail.getPrevious(); insn != null && insn.getOpcode() < 0; insn = insn.getPrevious()) {
			runStart = insn;
			if (insn instanceof FrameNode frame && tailFrame == null) tailFrame = frame;
			if (insn instanceof LabelNode label) {
				run.add(label);
				tailLabel = label;
			}
		}
		if (tailFrame == null || tailLabel == null) return 0;
		List<List<Object>> state = stateAt(owner, method, tailFrame);
		if (state == null) return 0;

		TreeMap<Integer, LabelNode> blocks = new TreeMap<>();
		Integer fallGroup = null;
		for (Map.Entry<Edge, Integer> decision : decisions.entrySet()) {
			blocks.computeIfAbsent(decision.getValue(), g -> new LabelNode());
			if (decision.getKey().kind() == Kind.FALL) fallGroup = decision.getValue();
		}

		// Chains through a GOTO this moves would follow it: point those edges at the tail directly first.
		Set<AbstractInsnNode> movedGotos = new HashSet<>();
		for (Edge edge : decisions.keySet()) if (edge.kind() == Kind.GOTO) movedGotos.add(edge.source());
		if (!movedGotos.isEmpty()) {
			for (Edge edge : edges) {
				if (decisions.containsKey(edge) || edge.target() != tail || edge.kind() == Kind.FALL) continue;
				if (passesThrough(labelOf(edge), movedGotos)) retarget(edge, tailLabel);
			}
		}
		for (Map.Entry<Edge, Integer> decision : decisions.entrySet()) {
			if (decision.getKey().kind() != Kind.FALL) retarget(decision.getKey(), blocks.get(decision.getValue()));
		}

		// The blocks go in vanilla's return order, so RETURN ordinals count as vanilla's do; what fell into the tail
		// falls into its own block when that block comes first, and jumps to it when it does not.
		LabelNode gate = new LabelNode();
		InsnList code = new InsnList();
		code.add(gate);
		AbstractInsnNode before = previousReal(runStart);
		if (fallGroup != null) {
			if (!fallGroup.equals(blocks.firstKey())) code.add(new JumpInsnNode(Opcodes.GOTO, blocks.get(fallGroup)));
		} else if (before != null && !endsFlow(before)) {
			code.add(new JumpInsnNode(Opcodes.GOTO, tailLabel));
		}
		for (int group : blocks.keySet()) {
			code.add(blocks.get(group));
			code.add(fullFrame(state));
			code.add(new InsnNode(tail.getOpcode()));
		}
		method.instructions.insertBefore(runStart, code);

		// The tail's compressed frame was relative to the frame before it, which is now a block's.
		tailFrame.type = Opcodes.F_FULL;
		tailFrame.local = new ArrayList<>(state.get(0));
		tailFrame.stack = new ArrayList<>(state.get(1));

		// Ranges that ended where the tail begins end where the blocks begin: the blocks are covered by exactly the
		// ranges the tail is.
		// (A range that also STARTS in the run covers only the tail and is left as it is.)
		if (method.tryCatchBlocks != null) {
			for (TryCatchBlockNode range : method.tryCatchBlocks) {
				if (run.contains(range.end) && !run.contains(range.start)) range.end = gate;
			}
		}
		if (method.localVariables != null) {
			for (LocalVariableNode local : method.localVariables) {
				if (run.contains(local.end) && !run.contains(local.start)) local.end = gate;
			}
		}
		cutAnnotations(method.visibleLocalVariableAnnotations, run, gate);
		cutAnnotations(method.invisibleLocalVariableAnnotations, run, gate);
		return blocks.size();
	}

	private static void cutAnnotations(List<LocalVariableAnnotationNode> annotations, Set<LabelNode> run, LabelNode gate) {
		if (annotations == null) return;
		for (LocalVariableAnnotationNode annotation : annotations) {
			for (int i = 0; i < annotation.end.size(); i++) {
				if (run.contains(annotation.end.get(i)) && !run.contains(annotation.start.get(i))) annotation.end.set(i, gate);
			}
		}
	}

	private static FrameNode fullFrame(List<List<Object>> state) {
		return new FrameNode(Opcodes.F_FULL, state.get(0).size(), state.get(0).toArray(), state.get(1).size(), state.get(1).toArray());
	}

	private static LabelNode labelOf(Edge edge) {
		if (edge.source() instanceof JumpInsnNode jump) return jump.label;
		if (edge.source() instanceof TableSwitchInsnNode table) return edge.entry() < 0 ? table.dflt : table.labels.get(edge.entry());
		if (edge.source() instanceof LookupSwitchInsnNode lookup) return edge.entry() < 0 ? lookup.dflt : lookup.labels.get(edge.entry());
		return null;
	}

	private static void retarget(Edge edge, LabelNode to) {
		if (edge.source() instanceof JumpInsnNode jump) jump.label = to;
		else if (edge.source() instanceof TableSwitchInsnNode table) {
			if (edge.entry() < 0) table.dflt = to; else table.labels.set(edge.entry(), to);
		} else if (edge.source() instanceof LookupSwitchInsnNode lookup) {
			if (edge.entry() < 0) lookup.dflt = to; else lookup.labels.set(edge.entry(), to);
		}
	}

	private static boolean passesThrough(LabelNode label, Set<AbstractInsnNode> gotos) {
		AbstractInsnNode insn = label;
		for (int guard = 0; guard < 64; guard++) {
			insn = real(insn);
			if (!(insn instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.GOTO) return false;
			if (gotos.contains(jump)) return true;
			insn = jump.label;
		}
		return false;
	}

	/**
	 * The full locals and stack {@code at} declares, decoded from the method's initial frame and every compressed
	 * frame before it. Null when the chain does not decode (a CHOP past the start), which no javac output has.
	 */
	public static List<List<Object>> stateAt(String owner, MethodNode method, FrameNode at) {
		List<Object> locals = initialLocals(owner, method);
		List<Object> stack = new ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof FrameNode frame)) continue;
			switch (frame.type) {
				case Opcodes.F_NEW, Opcodes.F_FULL -> {
					locals = new ArrayList<>(frame.local);
					stack = new ArrayList<>(frame.stack);
				}
				case Opcodes.F_SAME -> stack = new ArrayList<>();
				case Opcodes.F_SAME1 -> stack = new ArrayList<>(List.of(frame.stack.get(0)));
				case Opcodes.F_APPEND -> {
					locals.addAll(frame.local);
					stack = new ArrayList<>();
				}
				case Opcodes.F_CHOP -> {
					if (frame.local.size() > locals.size()) return null;
					for (int k = 0; k < frame.local.size(); k++) locals.remove(locals.size() - 1);
					stack = new ArrayList<>();
				}
				default -> {
					return null;
				}
			}
			if (frame == at) return List.of(locals, stack);
		}
		return null;
	}

	private static List<Object> initialLocals(String owner, MethodNode method) {
		List<Object> locals = new ArrayList<>();
		if ((method.access & Opcodes.ACC_STATIC) == 0) {
			locals.add("<init>".equals(method.name) && !"java/lang/Object".equals(owner) ? Opcodes.UNINITIALIZED_THIS : owner);
		}
		for (Type parameter : Type.getArgumentTypes(method.desc)) {
			switch (parameter.getSort()) {
				case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> locals.add(Opcodes.INTEGER);
				case Type.FLOAT -> locals.add(Opcodes.FLOAT);
				case Type.LONG -> locals.add(Opcodes.LONG);
				case Type.DOUBLE -> locals.add(Opcodes.DOUBLE);
				case Type.ARRAY -> locals.add(parameter.getDescriptor());
				default -> locals.add(parameter.getInternalName());
			}
		}
		return locals;
	}

	// --- small helpers ---

	public static AbstractInsnNode lastReturn(MethodNode method) {
		for (AbstractInsnNode insn = method.instructions.getLast(); insn != null; insn = insn.getPrevious()) {
			if (isReturn(insn)) return insn;
		}
		return null;
	}

	static List<AbstractInsnNode> returns(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (isReturn(insn)) out.add(insn);
		}
		return out;
	}

	static boolean isReturn(AbstractInsnNode insn) {
		return insn != null && insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN;
	}

	private static boolean endsFlow(AbstractInsnNode insn) {
		int opcode = insn.getOpcode();
		return isReturn(insn) || opcode == Opcodes.GOTO || opcode == Opcodes.ATHROW
				|| insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode;
	}

	private static AbstractInsnNode real(AbstractInsnNode insn) {
		while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		return insn;
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
		insn = insn.getPrevious();
		while (insn != null && insn.getOpcode() < 0) insn = insn.getPrevious();
		return insn;
	}

	/** The instruction control actually reaches from {@code label}, following GOTOs. */
	private static AbstractInsnNode resolve(LabelNode label) {
		AbstractInsnNode insn = label;
		for (int guard = 0; guard < 64; guard++) {
			insn = real(insn);
			if (insn instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO) insn = jump.label;
			else return insn;
		}
		return null;
	}

	private static int inverse(int opcode) {
		return switch (opcode) {
			case Opcodes.IFEQ -> Opcodes.IFNE;
			case Opcodes.IFNE -> Opcodes.IFEQ;
			case Opcodes.IFLT -> Opcodes.IFGE;
			case Opcodes.IFGE -> Opcodes.IFLT;
			case Opcodes.IFGT -> Opcodes.IFLE;
			case Opcodes.IFLE -> Opcodes.IFGT;
			case Opcodes.IF_ICMPEQ -> Opcodes.IF_ICMPNE;
			case Opcodes.IF_ICMPNE -> Opcodes.IF_ICMPEQ;
			case Opcodes.IF_ICMPLT -> Opcodes.IF_ICMPGE;
			case Opcodes.IF_ICMPGE -> Opcodes.IF_ICMPLT;
			case Opcodes.IF_ICMPGT -> Opcodes.IF_ICMPLE;
			case Opcodes.IF_ICMPLE -> Opcodes.IF_ICMPGT;
			case Opcodes.IF_ACMPEQ -> Opcodes.IF_ACMPNE;
			case Opcodes.IF_ACMPNE -> Opcodes.IF_ACMPEQ;
			case Opcodes.IFNULL -> Opcodes.IFNONNULL;
			case Opcodes.IFNONNULL -> Opcodes.IFNULL;
			default -> -1;
		};
	}
}
