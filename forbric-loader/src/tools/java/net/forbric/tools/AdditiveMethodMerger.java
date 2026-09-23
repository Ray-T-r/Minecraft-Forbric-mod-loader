/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * A deliberately small three-input merge. Two shapes are composed, and nothing else:
 * <ul>
 *   <li><b>entry prefixes</b>: both sides prepend stack-neutral, static void hook calls to an otherwise EXACT
 *       vanilla body. The accepted prefixes load only unchanged parameters and literals, never write locals or
 *       keep stack values across calls, and run outside the original handlers, base first, other second.</li>
 *   <li><b>paired hooks</b>: the two sides are the same code except that each calls its own family's hook, with the
 *       same name and descriptor, at the same places. Everything else agrees, so locals, stack, branch targets
 *       and handler ranges agree by construction. A site holds both calls when the hook's arguments are plain
 *       loads (including {@code this}) and constants, and its result is either nothing or threaded back through
 *       the local it read, behind an Optional refusal guard that is repeated between the two calls.</li>
 * </ul>
 * The original body may contain branches, switches, lambdas and handlers: their operands, control-flow targets
 * and exception regions must all agree, including local-variable slots. No local renumbering, arbitrary
 * instruction splicing, constructor merging or predicate composition is attempted. A refusal leaves the existing
 * merge decision intact and names why.
 *
 * <p>Before either grammar is tried, each side is aligned with VANILLA. A vanilla call one side removed and the
 * other kept is refused (one side replaced vanilla behaviour its hook now performs), and so are hooks the two
 * families post at different points of the vanilla computation (fall damage is the reference counterexample).
 *
 * <p>Composition also needs two answers from outside the method: the restored call must link in the merged
 * output, and the runtime's compensation for the loss it repairs must stand down on structural proof, or the
 * restored hook and that compensation post the same event twice. See {@link Context}.
 *
 * <p>This establishes bytecode composition invariants, NOT commutativity of mod side effects: hooks can still
 * throw or mutate their arguments. Functional tests remain necessary.
 */
final class AdditiveMethodMerger {

	private AdditiveMethodMerger() { }

	record Result(MethodNode method, String reason, int baseCalls, int otherCalls) {
		boolean accepted() { return method != null; }
	}

	/** What a composition needs to know about the jar it is written into; both answers are required. */
	interface Context {
		/** A static method with exactly this owner, name and descriptor exists in the merged output or its runtimes. */
		boolean resolvesStatic(String owner, String name, String descriptor);

		/**
		 * The runtime's compensation for {@code restored} stands down on structural proof once the base carries it,
		 * composed after {@code alongside}: the base's own paired call, or null for an entry prefix.
		 */
		boolean reviewed(MethodInsnNode alongside, MethodInsnNode restored);
	}

	private static final String PORTAL_HOOK = "onTrySpawnPortal(Lnet/minecraft/world/level/LevelAccessor;"
			+ "Lnet/minecraft/core/BlockPos;Ljava/util/Optional;)Ljava/util/Optional;";

	/**
	 * Restorations whose runtime compensation stands down on structural proof, keyed as {@link #restorationKey}.
	 * The kernel's PortalSpawnInjector fingerprints exactly the guarded composition this merger emits for this pair
	 * (NeoForge's call, its nonempty guard repeated, then MinecraftForge's) and then limits the legacy forward to
	 * that NeoForge dispatch. No other bridge or repair has such a stand-down yet, so every other composable pair
	 * is held back with a reason rather than restored into a double delivery.
	 */
	static final Set<String> REVIEWED_RESTORATIONS = Set.of(
			"net/neoforged/neoforge/event/EventHooks." + PORTAL_HOOK
					+ " -> net/minecraftforge/event/ForgeEventFactory." + PORTAL_HOOK);

	static String restorationKey(MethodInsnNode alongside, MethodInsnNode restored) {
		return (alongside == null ? "<entry>" : alongside.owner + "." + alongside.name + alongside.desc)
				+ " -> " + restored.owner + "." + restored.name + restored.desc;
	}

	/** Resolves hook owners, on demand, from the first of {@code classSets} that holds them. */
	static Context context(List<Map<String, byte[]>> classSets, Set<String> reviewed) {
		Map<String, Set<String>> statics = new HashMap<>();
		return new Context() {
			@Override public boolean resolvesStatic(String owner, String name, String descriptor) {
				return statics.computeIfAbsent(owner, o -> {
					for (Map<String, byte[]> classes : classSets) {
						byte[] bytes = classes.get(o);
						if (bytes == null) continue;
						ClassNode node = new ClassNode();
						new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
						Set<String> found = new HashSet<>();
						for (MethodNode m : node.methods) if ((m.access & Opcodes.ACC_STATIC) != 0) found.add(m.name + m.desc);
						return found;
					}
					return Set.of();
				}).contains(name + descriptor);
			}

			@Override public boolean reviewed(MethodInsnNode alongside, MethodInsnNode restored) {
				return reviewed.contains(restorationKey(alongside, restored));
			}
		};
	}

	static Result merge(MethodNode vanilla, MethodNode base, MethodNode other,
			String basePackage, String otherPackage, Context context) {
		if (vanilla == null) return refused("no vanilla method");
		if (vanilla.name.startsWith("<")) return refused("constructor or class initializer");
		if (!sameHeader(vanilla, base) || !sameHeader(vanilla, other)) {
			return refused("method descriptor or execution flags differ");
		}
		List<AbstractInsnNode> original = code(vanilla);
		List<AbstractInsnNode> baseCode = code(base);
		List<AbstractInsnNode> otherCode = code(other);
		if (original.isEmpty()) return refused("no vanilla instructions");

		// Where each side left vanilla, before asking whether either grammar fits: these two refusals hold for
		// every grammar, present or future, so they are not left to the grammar being too narrow to accept.
		String replaced = replacedByOneSide(original, baseCode, otherCode);
		if (replaced != null) return refused(replaced);
		Alignment baseAligned = Alignment.of(original, baseCode), otherAligned = Alignment.of(original, otherCode);
		if (baseAligned == null || otherAligned == null) return refused("too large to align against vanilla");
		String stages = stageMismatch(baseCode, baseAligned, basePackage, otherCode, otherAligned, otherPackage);
		if (stages != null) return refused(stages);

		// The entry grammar first: when both prefixes fit it, its answer (including a refusal for linkage or
		// review) is final. Otherwise the paired grammar, and only when neither shape applies the entry refusal.
		String notEntry = entryShape(vanilla, base, other, basePackage, otherPackage);
		if (notEntry == null) return entryPrefixes(original, base, other, basePackage, otherPackage, context);
		Result paired = pairedHooks(base, other, basePackage, otherPackage, context);
		return paired != null ? paired : refused(notEntry);
	}

	/** Why the two bodies are not both vanilla behind a void-hook prefix, or null when they are. */
	private static String entryShape(MethodNode vanilla, MethodNode base, MethodNode other,
			String basePackage, String otherPackage) {
		List<AbstractInsnNode> original = code(vanilla), baseCode = code(base), otherCode = code(other);
		int baseAdded = baseCode.size() - original.size();
		int otherAdded = otherCode.size() - original.size();
		if (baseAdded <= 0 || otherAdded <= 0) return "both sides must add an entry prefix";
		Object expected = signature(vanilla, 0);
		if (!expected.equals(signature(base, baseAdded))) return "base changes vanilla operands, control flow or handlers";
		if (!expected.equals(signature(other, otherAdded))) return "other changes vanilla operands, control flow or handlers";
		if (prefixCalls(base, baseCode.subList(0, baseAdded), basePackage) == 0) {
			return "base prefix is not parameter/literal-only static void hooks";
		}
		if (prefixCalls(other, otherCode.subList(0, otherAdded), otherPackage) == 0) {
			return "other prefix is not parameter/literal-only static void hooks";
		}
		return null;
	}

	private static Result entryPrefixes(List<AbstractInsnNode> original, MethodNode base, MethodNode other,
			String basePackage, String otherPackage, Context context) {
		List<AbstractInsnNode> baseCode = code(base), otherCode = code(other);
		int baseAdded = baseCode.size() - original.size();
		int otherAdded = otherCode.size() - original.size();
		int baseCalls = prefixCalls(base, baseCode.subList(0, baseAdded), basePackage);
		int otherCalls = prefixCalls(other, otherCode.subList(0, otherAdded), otherPackage);
		for (AbstractInsnNode instruction : baseCode.subList(0, baseAdded)) {
			if (instruction instanceof MethodInsnNode call && !context.resolvesStatic(call.owner, call.name, call.desc)) {
				return refused("entry hook does not resolve: " + call.owner + "." + call.name + call.desc);
			}
		}
		for (AbstractInsnNode instruction : otherCode.subList(0, otherAdded)) {
			if (!(instruction instanceof MethodInsnNode call)) continue;
			if (!context.resolvesStatic(call.owner, call.name, call.desc)) {
				return refused("entry hook does not resolve: " + call.owner + "." + call.name + call.desc);
			}
			if (!context.reviewed(null, call)) {
				return refused("entry hooks compose, but no runtime stand-down is reviewed: restoring "
						+ call.owner + "." + call.name + call.desc);
			}
		}

		MethodNode merged = new MethodNode(Opcodes.ASM9, base.access, base.name, base.desc,
				base.signature, base.exceptions.toArray(String[]::new));
		base.accept(merged);
		// Insert immediately after the base prefix, BEFORE the labels opening vanilla's original regions.
		// Inserting at the first original opcode instead would accidentally move a throwing hook into a try.
		AbstractInsnNode afterPrefix = code(merged).get(baseAdded - 1);
		InsnList additions = new InsnList();
		for (AbstractInsnNode instruction : otherCode.subList(0, otherAdded)) {
			additions.add(instruction.clone(new HashMap<>()));
		}
		merged.instructions.insert(afterPrefix, additions);
		merged.maxStack = Math.max(base.maxStack, other.maxStack);
		return new Result(merged, "exact vanilla body; entry hooks ordered base then other", baseCalls, otherCalls);
	}

	private static Result refused(String reason) { return new Result(null, reason, 0, 0); }

	private static boolean sameHeader(MethodNode vanilla, MethodNode side) {
		int execution = Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED;
		return side != null && vanilla.name.equals(side.name) && vanilla.desc.equals(side.desc)
				&& (vanilla.access & execution) == (side.access & execution)
				&& Objects.equals(vanilla.exceptions, side.exceptions);
	}

	private static List<AbstractInsnNode> code(MethodNode method) {
		List<AbstractInsnNode> result = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction.getOpcode() >= 0) result.add(instruction);
		}
		return result;
	}

	/** Complete executable operands and label destinations; debug info and frames remain those of the base. */
	private static Object signature(MethodNode method, int skipped) {
		Map<LabelNode, Integer> positions = new IdentityHashMap<>();
		int position = -skipped;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof LabelNode label) positions.put(label, position);
			else if (instruction.getOpcode() >= 0) position++;
		}
		List<Object> instructions = new ArrayList<>();
		List<AbstractInsnNode> all = code(method);
		for (AbstractInsnNode instruction : all.subList(skipped, all.size())) {
			int op = instruction.getOpcode();
			Object operands;
			if (instruction instanceof InsnNode) operands = List.of();
			else if (instruction instanceof IntInsnNode n) operands = List.of(n.operand);
			else if (instruction instanceof VarInsnNode n) operands = List.of(n.var);
			else if (instruction instanceof TypeInsnNode n) operands = List.of(n.desc);
			else if (instruction instanceof FieldInsnNode n) operands = List.of(n.owner, n.name, n.desc);
			else if (instruction instanceof MethodInsnNode n) operands = List.of(n.owner, n.name, n.desc, n.itf);
			else if (instruction instanceof InvokeDynamicInsnNode n) {
				List<Object> args = new ArrayList<>();
				for (Object arg : n.bsmArgs) args.add(constant(arg));
				operands = List.of(n.name, n.desc, n.bsm, args);
			} else if (instruction instanceof JumpInsnNode n) operands = List.of(target(positions, n.label));
			else if (instruction instanceof LdcInsnNode n) operands = List.of(constant(n.cst));
			else if (instruction instanceof IincInsnNode n) operands = List.of(n.var, n.incr);
			else if (instruction instanceof TableSwitchInsnNode n) {
				operands = List.of(n.min, n.max, target(positions, n.dflt), targets(positions, n.labels));
			} else if (instruction instanceof LookupSwitchInsnNode n) {
				operands = List.of(n.keys, target(positions, n.dflt), targets(positions, n.labels));
			} else if (instruction instanceof MultiANewArrayInsnNode n) operands = List.of(n.desc, n.dims);
			else throw new IllegalArgumentException("unknown instruction kind " + instruction.getClass().getName());
			instructions.add(List.of(op, operands));
		}
		List<Object> handlers = new ArrayList<>();
		for (TryCatchBlockNode handler : method.tryCatchBlocks) {
			handlers.add(List.of(target(positions, handler.start), target(positions, handler.end),
					target(positions, handler.handler), handler.type == null ? "<any>" : handler.type));
		}
		return List.of(instructions, handlers);
	}

	private static Object constant(Object value) {
		// Float.equals/Double.equals canonicalize NaN; retain the actual payload too.
		if (value instanceof Float f) return List.of("float", Float.floatToRawIntBits(f));
		if (value instanceof Double d) return List.of("double", Double.doubleToRawLongBits(d));
		if (value instanceof org.objectweb.asm.ConstantDynamic dynamic) {
			List<Object> args = new ArrayList<>();
			for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
				args.add(constant(dynamic.getBootstrapMethodArgument(i)));
			}
			return List.of("dynamic", dynamic.getName(), dynamic.getDescriptor(), dynamic.getBootstrapMethod(), args);
		}
		return value;
	}

	private static int target(Map<LabelNode, Integer> positions, LabelNode label) {
		return positions.getOrDefault(label, Integer.MIN_VALUE);
	}

	private static List<Integer> targets(Map<LabelNode, Integer> positions, List<LabelNode> labels) {
		List<Integer> result = new ArrayList<>();
		for (LabelNode label : labels) result.add(target(positions, label));
		return result;
	}

	/** Past this many alignment cells (after trimming the common head and tail) a method is not aligned at all. */
	private static final long MAX_ALIGNMENT_CELLS = 4_000_000L;

	/**
	 * A longest common subsequence of one side's instructions with vanilla's. Shapes ignore local slots and branch
	 * targets, because each pipeline renumbers both; they keep every other operand. Deterministic, so two sides that
	 * agree outside their hook owners align identically. Only hook POSITIONS are read from it, and only to refuse:
	 * near a hook, a recompiled side can align one instruction either way, which changes a reported vanilla index
	 * but can never make either grammar accept a body that is not what that grammar describes.
	 */
	private record Alignment(int[] toVanilla) {
		static Alignment of(List<AbstractInsnNode> vanilla, List<AbstractInsnNode> side) {
			int n = vanilla.size(), m = side.size();
			Object[] v = new Object[n], s = new Object[m];
			for (int i = 0; i < n; i++) v[i] = shape(vanilla.get(i));
			for (int j = 0; j < m; j++) s[j] = shape(side.get(j));
			int[] toVanilla = new int[m];
			Arrays.fill(toVanilla, -1);
			int head = 0, tail = 0;
			while (head < n && head < m && v[head].equals(s[head])) {
				toVanilla[head] = head;
				head++;
			}
			while (tail < n - head && tail < m - head && v[n - 1 - tail].equals(s[m - 1 - tail])) {
				toVanilla[m - 1 - tail] = n - 1 - tail;
				tail++;
			}
			int rows = n - head - tail, columns = m - head - tail;
			if ((long) (rows + 1) * (columns + 1) > MAX_ALIGNMENT_CELLS) return null;
			int[][] lcs = new int[rows + 1][columns + 1];
			for (int i = rows - 1; i >= 0; i--) {
				for (int j = columns - 1; j >= 0; j--) {
					lcs[i][j] = v[head + i].equals(s[head + j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
				}
			}
			for (int i = 0, j = 0; i < rows && j < columns; ) {
				if (v[head + i].equals(s[head + j]) && lcs[i][j] == lcs[i + 1][j + 1] + 1) {
					toVanilla[head + j] = head + i;
					i++;
					j++;
				} else if (lcs[i + 1][j] >= lcs[i][j + 1]) i++;
				else j++;
			}
			return new Alignment(toVanilla);
		}

		/** The vanilla instruction a side's instruction comes before: one past the last vanilla match preceding it. */
		int anchor(int sideIndex) {
			for (int i = sideIndex - 1; i >= 0; i--) if (toVanilla[i] >= 0) return toVanilla[i] + 1;
			return 0;
		}
	}

	private static Object shape(AbstractInsnNode instruction) {
		int op = instruction.getOpcode();
		if (instruction instanceof MethodInsnNode n) return List.of(op, n.owner, n.name, n.desc);
		if (instruction instanceof FieldInsnNode n) return List.of(op, n.owner, n.name, n.desc);
		if (instruction instanceof TypeInsnNode n) return List.of(op, n.desc);
		if (instruction instanceof LdcInsnNode n) return List.of(op, constant(n.cst));
		if (instruction instanceof IntInsnNode n) return List.of(op, n.operand);
		if (instruction instanceof InvokeDynamicInsnNode n) return List.of(op, n.name, n.desc, n.bsm);
		if (instruction instanceof MultiANewArrayInsnNode n) return List.of(op, n.desc, n.dims);
		return op;
	}

	/**
	 * A vanilla call one side removed and the other kept. That side REPLACED vanilla behaviour, typically with a
	 * hook that now performs it: NeoForge's spawner hook finalizes the mob itself, while MinecraftForge's leaves
	 * {@code Mob.finalizeSpawn} in place. Keeping both sides' code drops the call one side relies on or repeats
	 * it through the other. Occurrences are counted, not aligned, so a call a pipeline merely moved is not
	 * mistaken for one it removed; a call both sides removed alike is a shared replacement and says nothing.
	 */
	private static String replacedByOneSide(List<AbstractInsnNode> vanilla, List<AbstractInsnNode> base,
			List<AbstractInsnNode> other) {
		Map<Object, Integer> inVanilla = calls(vanilla), inBase = calls(base), inOther = calls(other);
		for (int i = 0; i < vanilla.size(); i++) {
			if (!(vanilla.get(i) instanceof MethodInsnNode call)) continue;
			Object key = shape(call);
			int original = inVanilla.get(key), kept = inBase.getOrDefault(key, 0), keptByOther = inOther.getOrDefault(key, 0);
			if (kept == keptByOther || (kept >= original && keptByOther >= original)) continue;
			String remover = kept < keptByOther ? "base" : "other", keeper = kept < keptByOther ? "other" : "base";
			return "one side replaces a vanilla call: " + remover + " drops " + call.owner + "." + call.name + call.desc
					+ " (vanilla #" + i + ") that " + keeper + " keeps";
		}
		return null;
	}

	private static Map<Object, Integer> calls(List<AbstractInsnNode> code) {
		Map<Object, Integer> out = new HashMap<>();
		for (AbstractInsnNode instruction : code) if (instruction instanceof MethodInsnNode) out.merge(shape(instruction), 1, Integer::sum);
		return out;
	}

	/**
	 * Both families post hooks, but at different points of the vanilla computation. Fall damage is the reference
	 * counterexample: MinecraftForge posts LivingFallEvent at entry with the raw distance, NeoForge after vanilla has
	 * clamped that distance to the current impulse. No order of the two is either family's contract, so no
	 * composition of them is one, however well the bytecode would line up.
	 */
	private static String stageMismatch(List<AbstractInsnNode> baseCode, Alignment base, String basePackage,
			List<AbstractInsnNode> otherCode, Alignment other, String otherPackage) {
		Map<Integer, List<String>> baseStages = stages(baseCode, base, basePackage);
		Map<Integer, List<String>> otherStages = stages(otherCode, other, otherPackage);
		if (baseStages.isEmpty() || otherStages.isEmpty() || baseStages.keySet().equals(otherStages.keySet())) return null;
		return "hook stages differ: base " + describe(baseStages) + "; other " + describe(otherStages);
	}

	private static Map<Integer, List<String>> stages(List<AbstractInsnNode> code, Alignment aligned, String hookPackage) {
		Map<Integer, List<String>> out = new TreeMap<>();
		for (int i = 0; i < code.size(); i++) {
			if (isHook(code.get(i), hookPackage)) {
				MethodInsnNode call = (MethodInsnNode) code.get(i);
				out.computeIfAbsent(aligned.anchor(siteStart(code, i)), k -> new ArrayList<>())
						.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
			}
		}
		return out;
	}

	private static boolean isHook(AbstractInsnNode instruction, String hookPackage) {
		return instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
				&& call.owner.startsWith(hookPackage);
	}

	/** The call itself, or the first of the plain loads that push exactly its arguments. */
	private static int siteStart(List<AbstractInsnNode> code, int call) {
		int start = call - Type.getArgumentTypes(((MethodInsnNode) code.get(call)).desc).length;
		if (start < 0) return call;
		for (int i = start; i < call; i++) if (!plainPush(code.get(i))) return call;
		return start;
	}

	private static String describe(Map<Integer, List<String>> stages) {
		List<String> out = new ArrayList<>();
		stages.forEach((anchor, hooks) -> out.add(String.join(" and ", hooks) + " at vanilla #" + anchor));
		return String.join(", ", out);
	}

	/** One paired call site: the base's argument loads {@code start..call-1}, the call, its store, and a guard. */
	private record Site(int start, int call, int end, int guard) { }

	/**
	 * The paired-hook grammar, or null when the two bodies are not the same code apart from paired hook owners.
	 * Every other refusal from here on names the site that could not hold both calls.
	 */
	private static Result pairedHooks(MethodNode base, MethodNode other, String basePackage, String otherPackage,
			Context context) {
		List<AbstractInsnNode> baseCode = code(base), otherCode = code(other);
		if (baseCode.size() != otherCode.size()) return null;
		List<?> baseSignature = (List<?>) signature(base, 0), otherSignature = (List<?>) signature(other, 0);
		if (!baseSignature.get(1).equals(otherSignature.get(1))) return null;
		List<?> baseInstructions = (List<?>) baseSignature.get(0), otherInstructions = (List<?>) otherSignature.get(0);
		List<Integer> pairs = new ArrayList<>();
		for (int i = 0; i < baseCode.size(); i++) {
			if (baseInstructions.get(i).equals(otherInstructions.get(i))) continue;
			if (!(baseCode.get(i) instanceof MethodInsnNode b) || !(otherCode.get(i) instanceof MethodInsnNode o)
					|| b.getOpcode() != Opcodes.INVOKESTATIC || o.getOpcode() != Opcodes.INVOKESTATIC
					|| !b.owner.startsWith(basePackage) || !o.owner.startsWith(otherPackage)
					|| !b.name.equals(o.name) || !b.desc.equals(o.desc) || b.itf != o.itf) return null;
			pairs.add(i);
		}
		if (pairs.isEmpty()) return null;

		Map<LabelNode, Integer> positions = positions(base);
		List<Integer> targets = targets(base, positions);
		List<List<Integer>> coverage = coverage(base, positions, baseCode.size());
		List<Site> sites = new ArrayList<>();
		for (int index : pairs) {
			MethodInsnNode call = (MethodInsnNode) baseCode.get(index);
			String hook = call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name;
			Type[] arguments = Type.getArgumentTypes(call.desc);
			int start = index - arguments.length;
			if (start < 0) return refused("paired hook site not composable: " + hook + " does not load its own arguments");
			for (int i = start; i < index; i++) {
				if (!plainPush(baseCode.get(i))) {
					return refused("paired hook site not composable: arguments of " + hook + " are not plain loads or constants");
				}
			}
			Type result = Type.getReturnType(call.desc);
			int end = index, guard = 0;
			if (result.getSort() != Type.VOID) {
				AbstractInsnNode next = index + 1 < baseCode.size() ? baseCode.get(index + 1) : null;
				if (!(next instanceof VarInsnNode store) || store.getOpcode() != result.getOpcode(Opcodes.ISTORE)) {
					return refused("paired hook site not composable: " + hook + " result is " + consumption(next)
							+ ", not threaded back through a local it reads");
				}
				boolean threads = false;
				for (int i = start; i < index; i++) {
					threads |= baseCode.get(i) instanceof VarInsnNode load && load.var == store.var
							&& load.getOpcode() == result.getOpcode(Opcodes.ILOAD) && arguments[i - start].equals(result);
				}
				if (!threads) {
					return refused("paired hook site not composable: " + hook + " stores a result it does not read, so "
							+ "the second call would discard the first family's answer");
				}
				if (!optionalGuard(baseCode, index + 2, store.var) || !result.getDescriptor().equals("Ljava/util/Optional;")) {
					return refused("paired hook site not composable: " + hook + " threads a value without the Optional "
							+ "refusal guard this grammar repeats between the two calls");
				}
				end = index + 1;
				guard = 3;
			}
			for (int target : targets) {
				if ((target > start && target <= end) || (target > end + 1 && target <= end + guard)) {
					return refused("paired hook site not composable: a branch or handler enters the site of " + hook);
				}
			}
			for (int i = start; i <= end + guard; i++) {
				if (!coverage.get(i).equals(coverage.get(end))) {
					return refused("paired hook site not composable: a handler range boundary falls inside the site of " + hook);
				}
			}
			sites.add(new Site(start, index, end, guard));
		}
		for (int index : pairs) {
			for (MethodInsnNode call : List.of((MethodInsnNode) baseCode.get(index), (MethodInsnNode) otherCode.get(index))) {
				if (!context.resolvesStatic(call.owner, call.name, call.desc)) {
					return refused("paired hook does not resolve: " + call.owner + "." + call.name + call.desc);
				}
			}
		}
		for (int index : pairs) {
			MethodInsnNode restored = (MethodInsnNode) otherCode.get(index);
			if (!context.reviewed((MethodInsnNode) baseCode.get(index), restored)) {
				return refused("paired hooks align, but no runtime stand-down is reviewed: restoring "
						+ restored.owner + "." + restored.name + restored.desc);
			}
		}

		MethodNode merged = new MethodNode(Opcodes.ASM9, base.access, base.name, base.desc,
				base.signature, base.exceptions.toArray(String[]::new));
		base.accept(merged);
		List<AbstractInsnNode> mergedCode = code(merged);
		Map<LabelNode, LabelNode> sameLabels = new IdentityHashMap<>();
		for (AbstractInsnNode instruction : merged.instructions) {
			if (instruction instanceof LabelNode label) sameLabels.put(label, label);
		}
		int observers = 0, guarded = 0;
		for (Site site : sites) {
			// Right after the base's own site and BEFORE any label that follows it, as the entry grammar does: a
			// branch to that label skipped the site on both sides and now skips both calls. The base's refusal
			// guard is repeated first, so a value the first family refused never reaches the second.
			InsnList additions = new InsnList();
			for (int i = site.end() + 1; i <= site.end() + site.guard(); i++) additions.add(mergedCode.get(i).clone(sameLabels));
			for (int i = site.start(); i <= site.end(); i++) additions.add(otherCode.get(i).clone(new HashMap<>()));
			merged.instructions.insert(mergedCode.get(site.end()), additions);
			if (site.guard() > 0) guarded++;
			else observers++;
		}
		merged.maxStack = Math.max(base.maxStack, other.maxStack);
		return new Result(merged, "paired hooks at identical vanilla points, base then other: " + observers
				+ " observer(s), " + guarded + " guarded result(s)", pairs.size(), pairs.size());
	}

	/** One stack value from nothing, with no side effect and no bootstrap to run. */
	private static boolean plainPush(AbstractInsnNode instruction) {
		int op = instruction.getOpcode();
		if (instruction instanceof VarInsnNode) return op >= Opcodes.ILOAD && op <= Opcodes.ALOAD;
		if (instruction instanceof IntInsnNode) return op == Opcodes.BIPUSH || op == Opcodes.SIPUSH;
		if (instruction instanceof LdcInsnNode literal) return !(literal.cst instanceof org.objectweb.asm.ConstantDynamic);
		return instruction instanceof InsnNode && op >= Opcodes.ACONST_NULL && op <= Opcodes.DCONST_1;
	}

	private static boolean optionalGuard(List<AbstractInsnNode> code, int at, int slot) {
		return at + 2 < code.size() && code.get(at) instanceof VarInsnNode load
				&& load.getOpcode() == Opcodes.ALOAD && load.var == slot
				&& code.get(at + 1) instanceof MethodInsnNode present && present.getOpcode() == Opcodes.INVOKEVIRTUAL
				&& !present.itf && present.owner.equals("java/util/Optional") && present.name.equals("isPresent")
				&& present.desc.equals("()Z")
				&& code.get(at + 2) instanceof JumpInsnNode branch && branch.getOpcode() == Opcodes.IFEQ;
	}

	private static String consumption(AbstractInsnNode next) {
		if (next == null) return "never consumed";
		int op = next.getOpcode();
		if (next instanceof JumpInsnNode) return "branched on";
		if (op >= Opcodes.IRETURN && op <= Opcodes.ARETURN) return "returned";
		if (op == Opcodes.POP || op == Opcodes.POP2) return "discarded";
		if (next instanceof VarInsnNode) return "stored into a local of another type";
		return "passed on";
	}

	private static Map<LabelNode, Integer> positions(MethodNode method) {
		Map<LabelNode, Integer> positions = new IdentityHashMap<>();
		int position = 0;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof LabelNode label) positions.put(label, position);
			else if (instruction.getOpcode() >= 0) position++;
		}
		return positions;
	}

	/** Every executable position control can arrive at other than by falling through. */
	private static List<Integer> targets(MethodNode method, Map<LabelNode, Integer> positions) {
		List<Integer> out = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof JumpInsnNode jump) out.add(target(positions, jump.label));
			else if (instruction instanceof TableSwitchInsnNode table) {
				out.add(target(positions, table.dflt));
				out.addAll(targets(positions, table.labels));
			} else if (instruction instanceof LookupSwitchInsnNode lookup) {
				out.add(target(positions, lookup.dflt));
				out.addAll(targets(positions, lookup.labels));
			}
		}
		for (TryCatchBlockNode handler : method.tryCatchBlocks) out.add(target(positions, handler.handler));
		return out;
	}

	/** For each executable position, the handlers whose range covers it. */
	private static List<List<Integer>> coverage(MethodNode method, Map<LabelNode, Integer> positions, int size) {
		List<List<Integer>> out = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			List<Integer> covering = new ArrayList<>();
			for (int h = 0; h < method.tryCatchBlocks.size(); h++) {
				TryCatchBlockNode handler = method.tryCatchBlocks.get(h);
				if (target(positions, handler.start) <= i && i < target(positions, handler.end)) covering.add(h);
			}
			out.add(covering);
		}
		return out;
	}

	/** A tiny verifier for the accepted prefix grammar; no approximation of arbitrary JVM instructions. */
	private static int prefixCalls(MethodNode method, List<AbstractInsnNode> prefix, String hookPackage) {
		Map<Integer, Type> parameters = new HashMap<>();
		int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		// 'this' has no owner in MethodNode and is deliberately unsupported until owner/type validation exists.
		for (Type parameter : Type.getArgumentTypes(method.desc)) {
			parameters.put(slot, parameter);
			slot += parameter.getSize();
		}
		List<Type> stack = new ArrayList<>();
		int calls = 0;
		for (AbstractInsnNode instruction : prefix) {
			int op = instruction.getOpcode();
			if (instruction instanceof VarInsnNode variable) {
				Type parameter = parameters.get(variable.var);
				if (parameter == null || op != parameter.getOpcode(Opcodes.ILOAD)) return 0;
				stack.add(parameter);
			} else if (instruction instanceof LdcInsnNode literal) {
				if (literal.cst instanceof Integer) stack.add(Type.INT_TYPE);
				else if (literal.cst instanceof Long) stack.add(Type.LONG_TYPE);
				else if (literal.cst instanceof Float) stack.add(Type.FLOAT_TYPE);
				else if (literal.cst instanceof Double) stack.add(Type.DOUBLE_TYPE);
				else if (literal.cst instanceof String) stack.add(Type.getType(String.class));
				else return 0;
			} else if (instruction instanceof IntInsnNode && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) {
				stack.add(Type.INT_TYPE);
			} else if (instruction instanceof InsnNode && op == Opcodes.ACONST_NULL) stack.add(null);
			else if (instruction instanceof InsnNode && op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) stack.add(Type.INT_TYPE);
			else if (instruction instanceof InsnNode && op >= Opcodes.LCONST_0 && op <= Opcodes.LCONST_1) stack.add(Type.LONG_TYPE);
			else if (instruction instanceof InsnNode && op >= Opcodes.FCONST_0 && op <= Opcodes.FCONST_2) stack.add(Type.FLOAT_TYPE);
			else if (instruction instanceof InsnNode && op >= Opcodes.DCONST_0 && op <= Opcodes.DCONST_1) stack.add(Type.DOUBLE_TYPE);
			else if (instruction instanceof MethodInsnNode call && op == Opcodes.INVOKESTATIC
					&& call.owner.startsWith(hookPackage) && Type.getReturnType(call.desc).equals(Type.VOID_TYPE)) {
				Type[] arguments = Type.getArgumentTypes(call.desc);
				if (arguments.length != stack.size()) return 0;
				for (int i = 0; i < arguments.length; i++) {
					Type actual = stack.get(i), expected = arguments[i];
					boolean reference = expected.getSort() == Type.OBJECT || expected.getSort() == Type.ARRAY;
					boolean intLike = expected.getSort() >= Type.BOOLEAN && expected.getSort() <= Type.INT;
					if (!(actual == null ? reference : actual.equals(expected) || (intLike && actual.equals(Type.INT_TYPE)))) return 0;
				}
				stack.clear();
				calls++;
			} else return 0;
		}
		return stack.isEmpty() ? calls : 0;
	}
}
