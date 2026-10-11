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

package net.forbric.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Which hook each dropped conflict actually cost, and whether anything is waiting for it.
 *
 * <h2>Why the report alone cannot decide anything</h2>
 *
 * <p>{@code merge-conflicts.txt} says a method lost its Forge hook. It does not say WHICH hook, so the thousand
 * lines are a thousand unknowns, and the two obvious responses — flip the arbitration, or repair them by hand in
 * the order they appear — both act without knowing what any single line costs.
 *
 * <p>This names it: read the method's body in MinecraftForge's own patched game and in the merged base, and the
 * hook calls present in the first and absent from the second are what the merge took. Then the same for the
 * NeoForge side, because forcing a method to Forge's body would take those instead — a whitelist entry is a
 * TRADE, not a repair, and it is only worth making when the hook gained has someone waiting and the hook given
 * up does not.
 *
 * <p>Potential consumers are identified by an event class in a mod's constant pool. This is not proof of a
 * registered listener, and absence is not proof that reflection or an unmodelled helper never uses it.
 * Raw losses are reported separately from runtime restoration. Restoration is assessed only when the kernel's
 * defined-class evidence is supplied, and then structurally ({@link EffectiveHookEvidence}), for the conflict rows
 * and for every raw loss in the census alike.
 * The appended platform census covers every direct platform method invocation in both patched JARs,
 * including non-conflict callers and symbols outside the event-facade model. It does not equate those
 * platform symbols with event hooks.
 *
 * <p>Usage: {@code LostHookAttribution <forge-patched.jar> <neo-patched.jar> <merged.jar> <forge-runtime.jar>
 * <neoforge-runtime.jar> <merge-conflicts.txt> <mods-dir> [defined-class-evidence-dir]}
 */
public final class LostHookAttribution {

	private static final String[] HOOK_CLASSES = {
			"net/minecraftforge/event/ForgeEventFactory", "net/minecraftforge/client/event/ForgeEventFactoryClient",
			"net/minecraftforge/common/ForgeHooks", "net/minecraftforge/client/ForgeHooksClient",
			"net/neoforged/neoforge/event/EventHooks", "net/neoforged/neoforge/client/ClientHooks",
	};

	private LostHookAttribution() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 7 || args.length > 8) {
			System.err.println("usage: LostHookAttribution <forge-patched.jar> <neo-patched.jar> <merged.jar> "
					+ "<forge-runtime.jar> <neoforge-runtime.jar> <merge-conflicts.txt> <mods-dir> [defined-class-evidence-dir]");
			System.exit(2);
		}
		Map<String, ClassNode> forge = load(args[0], "forge-patched");
		Map<String, ClassNode> neo = load(args[1], "neo-patched");
		Map<String, ClassNode> merged = load(args[2], "merged");
		EffectiveHookEvidence effective = args.length == 8 ? new EffectiveHookEvidence(Path.of(args[7])) : null;
		Map<EffectiveHookEvidence.State, Integer> effectiveCounts = new java.util.EnumMap<>(EffectiveHookEvidence.State.class);

		// hook owner#name+desc -> the events it constructs, from both carriers.
		Map<String, Set<String>> eventsOfHook = new HashMap<>();
		for (String carrier : new String[] { args[3], args[4] }) {
			for (String hookClass : HOOK_CLASSES) collectEvents(carrier, hookClass, eventsOfHook);
		}
		Set<String> wanted = eventsNamedByMods(Path.of(args[6]));
		System.out.println("[attribution] mods name " + wanted.size()
				+ " ecosystem class references (candidate filter; not event/subscriber count)");

		int judged = 0, noHookFound = 0, unobserved = 0;
		List<String> candidates = new ArrayList<>(), trades = new ArrayList<>();
		Report report = report(Path.of(args[5]));
		List<Conflict> conflicts = report.conflicts();
		Attribution attribution = new Attribution(eventsOfHook, wanted, effective, effectiveCounts, candidates, trades);
		for (Conflict c : conflicts) {
			MethodNode f = method(forge.get(c.owner()), c.method());
			MethodNode n = method(neo.get(c.owner()), c.method());
			MethodNode m = method(merged.get(c.owner()), c.method());
			if (f == null || n == null || m == null) { unobserved++; continue; }
			judged++;
			if (!attribution.attribute(c.owner() + "#" + c.method(), c.lostFamily(),
					c.fieldInitKept() ? " kind=FIELD_INIT_KEPT" : c.displacedLambda() ? " kind=DISPLACED_LAMBDA" : "",
					c.lostFamily().equals("forge") ? f : n, c.lostFamily().equals("forge") ? n : f, m)) noHookFound++;
		}
		System.out.println("[attribution] conflicts=" + conflicts.size() + " judged=" + judged
				+ " no-modelled-direct-hook=" + noHookFound + " unobserved=" + unobserved
				+ " field-init-kept=" + conflicts.stream().filter(Conflict::fieldInitKept).count()
				+ " displaced-lambda=" + conflicts.stream().filter(Conflict::displacedLambda).count());
		// A method the edit splice took is no hook-lost row, yet a run of the other side it reports as competing or
		// refused is a hook that body still lacks. The report names which runs; the bodies say which family's hook.
		// The base family's own calls are all kept (the splice only adds), so judging both families is exact.
		int residualJudged = 0, residualNoHook = 0, residualUnobserved = 0;
		List<Splice> residuals = report.splices().stream().filter(Splice::residual).toList();
		for (Splice s : residuals) {
			MethodNode f = method(forge.get(s.owner()), s.method());
			MethodNode n = method(neo.get(s.owner()), s.method());
			MethodNode m = method(merged.get(s.owner()), s.method());
			if (f == null || n == null || m == null) { residualUnobserved++; continue; }
			residualJudged++;
			boolean named = attribution.attribute(s.owner() + "#" + s.method(), "forge", " kind=SPLICE_RESIDUAL", f, n, m);
			named |= attribution.attribute(s.owner() + "#" + s.method(), "neo", " kind=SPLICE_RESIDUAL", n, f, m);
			if (!named) residualNoHook++;
		}
		System.out.println("[attribution] spliced-residual=" + residuals.size() + " judged=" + residualJudged
				+ " no-modelled-direct-hook=" + residualNoHook + " unobserved=" + residualUnobserved
				+ " (methods the edit splice took with a run it reports as competing or refused)");
		System.out.println("[attribution] scope: " + HOOK_CLASSES.length + " hook facades; direct calls and event"
				+ " construction only; event type references are potential consumers, not proof of subscription");
		if (effective == null) System.out.println("[attribution] runtime restoration=NOT_ASSESSED; supply actual defined-class evidence"
				+ " before treating RAW-LOST as a remaining defect");
		else {
			for (var state : EffectiveHookEvidence.State.values())
				System.out.println("[effective] " + state + "=" + effectiveCounts.getOrDefault(state, 0));
			System.out.println("[effective] direct/helper restoration is structural evidence only, not proof of execution,"
					+ " cancellation or return-value fidelity. VIA_KERNEL_BRIDGE means a defined kernel class invokes the"
					+ " exact hook (an event-bus forward); its route from this caller is not proven. OBSERVED_WITHOUT_HOOK"
					+ " is residual direct-call loss; reflection and other unmodelled routes remain unassessed."
					+ " UNOBSERVED is not a pass.");
		}
		System.out.println("[attribution] CANDIDATES (lost event referenced, no retained event reference observed): " + candidates.size());
		for (String row : candidates) System.out.println("    + " + row);
		System.out.println("[attribution] TRADES (both event types referenced): " + trades.size());
		for (String row : trades) System.out.println("    ~ " + row);
		printPlatformCensus(forge, neo, merged, conflicts, report.splices(), effective);
	}

	/** One method's modelled hooks: which the losing family's body calls and the merged body does not. */
	private record Attribution(Map<String, Set<String>> eventsOfHook, Set<String> wanted, EffectiveHookEvidence effective,
			Map<EffectiveHookEvidence.State, Integer> effectiveCounts, List<String> candidates, List<String> trades) {
		/** Prints the row and returns true when the losing body has a modelled hook the merged body lacks. */
		boolean attribute(String caller, String lostFamily, String kind, MethodNode losing, MethodNode retained, MethodNode merged) {
			Set<String> lost = new TreeSet<>(hookCalls(losing));
			lost.removeAll(hookCalls(merged));
			Set<String> kept = new TreeSet<>(hookCalls(retained));
			kept.retainAll(hookCalls(merged));
			if (lost.isEmpty()) return false;
			boolean gainWanted = anyWanted(lost, eventsOfHook, wanted);
			boolean giveUpWanted = anyWanted(kept, eventsOfHook, wanted);
			String row = caller + " lost-family=" + lostFamily + kind + " RAW-LOST " + lost + " RETAINED " + kept;
			System.out.println("[attribution] " + row);
			if (effective != null) for (String hook : lost) {
				var state = effective.state(caller, hook);
				effectiveCounts.merge(state, 1, Integer::sum);
				System.out.println("[effective] " + caller + " hook=" + hook + " state=" + state);
			}
			if (gainWanted && !giveUpWanted) candidates.add(row);
			else if (gainWanted) trades.add(row);
			return true;
		}
	}

	/** Raw, same-caller bytecode coverage; none of these states asserts runtime event behavior. */
	enum RawCallState { RAW_RETAINED, RAW_PARTIAL_LOSS, RAW_LOST, RAW_INVOCATION_CHANGED, MERGED_CALLER_MISSING }

	/**
	 * {@code spliceResidual}: not a hook-lost row, but a method the edit splice took while reporting one of the
	 * other side's runs as competing or refused -- the report lists that loss, in its edit-splice section.
	 */
	record PlatformCall(String caller, String symbol, boolean modelledFacade, boolean listedConflict, boolean spliceResidual,
			RawCallState state, int originalOccurrences, int mergedOccurrences,
			Map<String, Integer> originalForms, Map<String, Integer> mergedForms) {
		int retainedOccurrences() {
			return state == RawCallState.MERGED_CALLER_MISSING ? 0 : invocationOverlap(originalForms, mergedForms);
		}

		int lostOccurrences() {
			return state == RawCallState.MERGED_CALLER_MISSING ? 0 : originalOccurrences - retainedOccurrences();
		}

		/** How the report lists this caller: a hook-lost row, a spliced method with a lost run, or not at all. */
		String membership() {
			return listedConflict ? "LISTED" : spliceResidual ? "SPLICE_RESIDUAL" : "UNLISTED";
		}
	}

	record PlatformCensus(int classes, int methods, int callers, int listedConflictCallers, int spliceResidualCallers,
			int missingMergedMethods, int missingMergedCallers, List<PlatformCall> calls) { }

	/** The census over the hook-lost rows alone, for a report without an edit-splice section. */
	static PlatformCensus platformCensus(Map<String, ClassNode> source, Map<String, ClassNode> merged,
			List<Conflict> conflicts) {
		return platformCensus(source, merged, conflicts, List.of());
	}

	/**
	 * Full denominator for direct invocation instructions in the supplied patched JAR, including callers
	 * absent from the conflict report. Symbols are owner + name + descriptor, not names or event guesses.
	 * Repeated calls and opcode/itf are counted: overlap is a multiset comparison inside an exact matching caller,
	 * not a claim that the same control-flow site survived. Missing callers are unobserved, not raw losses.
	 * Membership only labels a caller; every caller is scanned the same way whatever the report says about it.
	 *
	 * <p>A caller's calls count as kept in the merged class's method of its name and in the copy the edit splice
	 * carried under a fresh name ({@code <name>$forbricforge} or {@code <name>$forbricneo}, a lambda whose name the
	 * base side gives a different body): moving a lambda's calls to its own name is not losing them, and keeping a
	 * lambda under a name another body owns is not keeping them, so the count rewards neither.
	 */
	static PlatformCensus platformCensus(Map<String, ClassNode> source, Map<String, ClassNode> merged,
			List<Conflict> conflicts, List<Splice> splices) {
		Set<String> listed = new TreeSet<>(), residual = new TreeSet<>();
		for (Conflict conflict : conflicts) listed.add(conflict.owner() + "#" + conflict.method());
		for (Splice splice : splices) if (splice.residual()) residual.add(splice.owner() + "#" + splice.method());
		int methods = 0, callers = 0, listedCallers = 0, residualCallers = 0, missingMethods = 0, missingCallers = 0;
		List<PlatformCall> rows = new ArrayList<>();
		for (ClassNode cn : new TreeMap<>(source).values()) {
			for (MethodNode original : cn.methods.stream().sorted(Comparator.comparing(m -> m.name + m.desc)).toList()) {
				methods++;
				String caller = cn.name + "#" + original.name + original.desc;
				MethodNode counterpart = method(merged.get(cn.name), original.name + original.desc);
				if (counterpart == null) missingMethods++;
				Map<String, Map<String, Integer>> originalCalls = platformCallForms(original);
				if (originalCalls.isEmpty()) continue;
				callers++;
				boolean listedConflict = listed.contains(caller);
				boolean spliceResidual = !listedConflict && residual.contains(caller);
				if (listedConflict) listedCallers++;
				if (spliceResidual) residualCallers++;
				if (counterpart == null) missingCallers++;
				Map<String, Map<String, Integer>> mergedCalls = counterpart == null ? Map.of() : platformCallForms(counterpart);
				if (counterpart != null) mergedCalls = withCarriedCopies(mergedCalls, merged.get(cn.name), original);
				for (var call : originalCalls.entrySet()) {
					Map<String, Integer> originalForms = call.getValue(), mergedForms = mergedCalls.getOrDefault(call.getKey(), Map.of());
					int originalCount = occurrences(originalForms), mergedCount = occurrences(mergedForms);
					int retainedCount = invocationOverlap(originalForms, mergedForms);
					RawCallState state = counterpart == null ? RawCallState.MERGED_CALLER_MISSING
							: mergedCount == 0 ? RawCallState.RAW_LOST
							: retainedCount == 0 ? RawCallState.RAW_INVOCATION_CHANGED
							: retainedCount < originalCount ? RawCallState.RAW_PARTIAL_LOSS : RawCallState.RAW_RETAINED;
					String owner = call.getKey().substring(0, call.getKey().indexOf('#'));
					rows.add(new PlatformCall(caller, call.getKey(), isModelledFacade(owner), listedConflict, spliceResidual,
							state, originalCount, counterpart == null ? -1 : mergedCount,
							Collections.unmodifiableMap(new TreeMap<>(originalForms)),
							Collections.unmodifiableMap(new TreeMap<>(mergedForms))));
				}
			}
		}
		return new PlatformCensus(source.size(), methods, callers, listedCallers, residualCallers, missingMethods, missingCallers,
				List.copyOf(rows));
	}

	/** The merged calls of a caller, its carried copies' added ({@link #platformCensus}). */
	private static Map<String, Map<String, Integer>> withCarriedCopies(Map<String, Map<String, Integer>> calls, ClassNode merged,
			MethodNode original) {
		Map<String, Map<String, Integer>> out = new TreeMap<>();
		calls.forEach((symbol, forms) -> out.put(symbol, new TreeMap<>(forms)));
		for (String family : List.of("forge", "neo")) {
			MethodNode copy = method(merged, original.name + "$forbric" + family + original.desc);
			if (copy == null) continue;
			platformCallForms(copy).forEach((symbol, forms) -> forms.forEach((form, n) ->
					out.computeIfAbsent(symbol, k -> new TreeMap<>()).merge(form, n, Integer::sum)));
		}
		return out;
	}

	/** Every direct invocation of either platform namespace, including non-event APIs and constructors. */
	static Map<String, Integer> platformCalls(MethodNode method) {
		Map<String, Integer> calls = new TreeMap<>();
		platformCallForms(method).forEach((symbol, forms) -> calls.put(symbol, occurrences(forms)));
		return calls;
	}

	static int occurrences(Map<String, Integer> forms) {
		return forms.values().stream().mapToInt(Integer::intValue).sum();
	}

	static int invocationOverlap(Map<String, Integer> original, Map<String, Integer> merged) {
		int overlap = 0;
		for (var form : original.entrySet()) overlap += Math.min(form.getValue(), merged.getOrDefault(form.getKey(), 0));
		return overlap;
	}

	static Map<String, Map<String, Integer>> platformCallForms(MethodNode method) {
		Map<String, Map<String, Integer>> calls = new TreeMap<>();
		if (method.instructions == null) return calls;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && (call.owner.startsWith("net/minecraftforge/")
					|| call.owner.startsWith("net/neoforged/"))) {
				String opcode = switch (call.getOpcode()) {
					case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
					case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
					case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
					case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
					default -> "opcode-" + call.getOpcode();
				};
				calls.computeIfAbsent(call.owner + "#" + call.name + call.desc, ignored -> new TreeMap<>())
						.merge(opcode + "/itf=" + call.itf, 1, Integer::sum);
			}
		}
		return calls;
	}

	private static boolean isModelledFacade(String owner) {
		for (String facade : HOOK_CLASSES) if (facade.equals(owner)) return true;
		return false;
	}

	/** Raw losses a census row can have; retained rows and callers the merge does not have are not losses. */
	private static boolean rawLoss(RawCallState state) {
		return state == RawCallState.RAW_LOST || state == RawCallState.RAW_PARTIAL_LOSS
				|| state == RawCallState.RAW_INVOCATION_CHANGED;
	}

	/**
	 * Every raw loss in the census joined to the final definitions -- listed conflict or not, modelled facade or
	 * not. The conflict rows alone got this before, so a loss in an unlisted caller (the facade calls in lambdas,
	 * anything outside the six-owner model) had a raw state and never a repaired, residual or unobserved one.
	 */
	static Map<PlatformCall, EffectiveHookEvidence.State> effectiveStates(PlatformCensus census,
			EffectiveHookEvidence effective) {
		Map<PlatformCall, EffectiveHookEvidence.State> out = new LinkedHashMap<>();
		for (PlatformCall call : census.calls()) {
			if (rawLoss(call.state())) out.put(call, effective.state(call.caller(), call.symbol(), call.originalForms()));
		}
		return out;
	}

	private static void printPlatformCensus(Map<String, ClassNode> forge, Map<String, ClassNode> neo,
			Map<String, ClassNode> merged, List<Conflict> conflicts, List<Splice> splices, EffectiveHookEvidence effective) {
		System.out.println("[platform-census] scope: all loaded classes and declared methods in each patched JAR;"
				+ " every direct MethodInsnNode targeting net/minecraftforge/ or net/neoforged/; both namespaces"
				+ " scanned on both sides; caller and symbol identities are owner#name+descriptor");
		System.out.println("[platform-census] conflict-membership: LISTED is every '... hook lost)' row of the report, plain,"
				+ " field-init-kept and displaced-lambda (an unrecognised form stops the run); SPLICE_RESIDUAL is a method the edit splice took while"
				+ " reporting a run of the other side as competing or refused (an unrecognised edit-splice row stops the run);"
				+ " UNLISTED callers are scanned equally; MODELLED_FACADE is the existing six-owner model;"
				+ " OUTSIDE_EVENT_MODEL symbols are not classified as event hooks");
		System.out.println("[platform-census] comparison: raw symbol+opcode+itf occurrence-count overlap in the same caller;"
				+ " not call-site/control-flow equivalence; MERGED_CALLER_MISSING is unobserved, excluded from raw"
				+ " retained/lost counts; RAW_INVOCATION_CHANGED means the symbol remains only with different invocation forms."
				+ " Invokedynamic/handle targets, fields and reflection are NOT_ASSESSED; runtime rewriting, kernel"
				+ " helpers and kernel event-bus forwards are classified per raw loss (effective=) only from supplied"
				+ " defined-class evidence; raw absence is not proof of a behavior defect");
		printPlatformCensus("forge", platformCensus(forge, merged, conflicts, splices), effective);
		printPlatformCensus("neo", platformCensus(neo, merged, conflicts, splices), effective);
	}

	private static void printPlatformCensus(String side, PlatformCensus census, EffectiveHookEvidence effective) {
		Map<PlatformCall, EffectiveHookEvidence.State> states = effective == null ? Map.of() : effectiveStates(census, effective);
		Set<String> symbols = new TreeSet<>(), modelled = new TreeSet<>();
		Map<String, long[]> groups = new TreeMap<>();
		long occurrences = 0, retained = 0;
		for (PlatformCall call : census.calls()) {
			symbols.add(call.symbol());
			if (call.modelledFacade()) modelled.add(call.symbol());
			String scope = call.modelledFacade() ? "MODELLED_FACADE" : "OUTSIDE_EVENT_MODEL";
			String membership = call.membership();
			long[] counts = groups.computeIfAbsent(scope + " conflict=" + membership + " state=" + call.state(),
					ignored -> new long[2]);
			counts[0]++;
			counts[1] += call.originalOccurrences();
			occurrences += call.originalOccurrences();
			retained += call.retainedOccurrences();
			System.out.println("[platform-census] call side=" + side + " caller=" + call.caller() + " symbol=" + call.symbol()
					+ " scope=" + scope + " conflict=" + membership + " state=" + call.state()
					+ " original-occurrences=" + call.originalOccurrences() + " merged-occurrences="
					+ (call.mergedOccurrences() < 0 ? "UNOBSERVED" : call.mergedOccurrences())
					+ " original-forms=" + call.originalForms() + " merged-forms="
					+ (call.mergedOccurrences() < 0 ? "UNOBSERVED" : call.mergedForms())
					+ " retained-overlap=" + call.retainedOccurrences() + " raw-lost-occurrences=" + call.lostOccurrences()
					+ (rawLoss(call.state()) ? " effective=" + (effective == null ? "NOT_ASSESSED" : states.get(call)) : ""));
		}
		System.out.println("[platform-census] denominator side=" + side + " classes=" + census.classes()
				+ " methods=" + census.methods() + " methods-without-platform-calls=" + (census.methods() - census.callers())
				+ " platform-callers=" + census.callers() + " listed-conflict-callers=" + census.listedConflictCallers()
				+ " splice-residual-callers=" + census.spliceResidualCallers()
				+ " unlisted-callers=" + (census.callers() - census.listedConflictCallers() - census.spliceResidualCallers())
				+ " merged-methods-missing=" + census.missingMergedMethods()
				+ " merged-platform-callers-missing=" + census.missingMergedCallers());
		System.out.println("[platform-census] totals side=" + side + " symbols=" + symbols.size()
				+ " modelled-facade-symbols=" + modelled.size() + " outside-event-model-symbols=" + (symbols.size() - modelled.size())
				+ " caller-symbol-pairs=" + census.calls().size() + " original-occurrences=" + occurrences
				+ " retained-overlap=" + retained + " raw-lost-occurrences=" + rawLostOccurrences(census)
				+ " unobserved-occurrences=" + unobservedOccurrences(census));
		for (var group : groups.entrySet()) System.out.println("[platform-census] coverage side=" + side + " scope="
				+ group.getKey() + " caller-symbol-pairs=" + group.getValue()[0] + " original-occurrences=" + group.getValue()[1]);
		long rawLossPairs = census.calls().stream().filter(call -> rawLoss(call.state())).count();
		if (effective == null) {
			System.out.println("[platform-census] effective side=" + side + " raw-loss-pairs=" + rawLossPairs
					+ " NOT_ASSESSED (no defined-class evidence supplied)");
			return;
		}
		StringBuilder totals = new StringBuilder("[platform-census] effective side=" + side + " raw-loss-pairs=" + rawLossPairs);
		for (var state : EffectiveHookEvidence.State.values())
			totals.append(' ').append(state).append('=').append(states.values().stream().filter(state::equals).count());
		System.out.println(totals);
	}

	/**
	 * Every occurrence of a platform call the patched body makes that the merged body of the same caller does not:
	 * the number this census prints as raw-lost-occurrences, and the one a ratchet on the merge bounds.
	 */
	static long rawLostOccurrences(PlatformCensus census) {
		return census.calls().stream().mapToLong(PlatformCall::lostOccurrences).sum();
	}

	/**
	 * Occurrences in callers the merged jar does not have. They are not counted as lost, so a bound on losses alone
	 * would let a merge that drops a whole method move its calls out of the count; a ratchet bounds both.
	 */
	static long unobservedOccurrences(PlatformCensus census) {
		return census.calls().stream().filter(call -> call.state() == RawCallState.MERGED_CALLER_MISSING)
				.mapToLong(PlatformCall::originalOccurrences).sum();
	}

	private static boolean anyWanted(Set<String> hooks, Map<String, Set<String>> eventsOfHook, Set<String> wanted) {
		for (String hook : hooks) {
			for (String event : eventsOfHook.getOrDefault(hook, Set.of())) {
				if (wanted.contains(event)) return true;
			}
		}
		return false;
	}

	/** {@code owner#name+desc} of every modelled hook call in this body. */
	static Set<String> hookCalls(MethodNode m) {
		Set<String> out = new LinkedHashSet<>();
		if (m.instructions == null) return out;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof MethodInsnNode mi)) continue;
			for (String hookClass : HOOK_CLASSES) {
				if (mi.owner.equals(hookClass)) out.add(mi.owner + "#" + mi.name + mi.desc);
			}
		}
		return out;
	}

	/** hook {@code owner#name+desc} -> the ecosystem classes its body constructs. */
	private static void collectEvents(String carrier, String hookClass, Map<String, Set<String>> into)
			throws IOException {
		try (ZipFile zf = new ZipFile(carrier)) {
			ZipEntry e = zf.getEntry(hookClass + ".class");
			if (e == null) return;
			ClassNode cn = new ClassNode();
			try (InputStream in = zf.getInputStream(e)) {
				new ClassReader(in.readAllBytes()).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
			}
			for (MethodNode m : cn.methods) {
				if (m.instructions == null) continue;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode t
							&& (t.desc.startsWith("net/minecraftforge/") || t.desc.startsWith("net/neoforged/"))) {
						into.computeIfAbsent(hookClass + "#" + m.name + m.desc, k -> new TreeSet<>()).add(t.desc);
					}
				}
			}
		}
	}

	/** Ecosystem event classes named in any mod jar's constant pool, nested jars included. */
	private static Set<String> eventsNamedByMods(Path modsDir) throws IOException {
		Set<String> named = new TreeSet<>();
		if (!Files.isDirectory(modsDir)) throw new IOException("mods directory not found: " + modsDir);
		try (var jars = Files.list(modsDir)) {
			for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
				try (ZipFile zf = new ZipFile(jar.toFile())) {
					namesIn(zf, named);
				} catch (IOException unreadable) {
					throw new IOException("cannot inventory mod jar: " + jar, unreadable);
				}
			}
		}
		return named;
	}

	private static void namesIn(ZipFile zf, Set<String> into) throws IOException {
		var entries = zf.entries();
		List<byte[]> nested = new ArrayList<>();
		while (entries.hasMoreElements()) {
			ZipEntry e = entries.nextElement();
			if (e.getName().endsWith(".class")) {
				try (InputStream in = zf.getInputStream(e)) {
					ClassReader r = new ClassReader(in.readAllBytes());
					char[] buf = new char[r.getMaxStringLength()];
					for (int i = 1; i < r.getItemCount(); i++) {
						int off = r.getItem(i);
						if (off == 0 || r.readByte(off - 1) != 7) continue;
						String name = r.readUTF8(off, buf);
						if (name == null) continue;
						if (name.startsWith("net/minecraftforge/") || name.startsWith("net/neoforged/")) into.add(name);
					}
				} catch (RuntimeException unparsable) {
					throw new IOException("cannot inventory mod class: " + e.getName(), unparsable);
				}
			} else if (e.getName().endsWith(".jar") && (e.getName().startsWith("META-INF/jars/") || e.getName().startsWith("META-INF/jarjar/"))) {
				try (InputStream in = zf.getInputStream(e)) {
					nested.add(in.readAllBytes());
				}
			}
		}
		for (byte[] bytes : nested) {
			Path tmp = Files.createTempFile("forbric-nested", ".jar");
			try {
				Files.write(tmp, bytes);
				try (ZipFile inner = new ZipFile(tmp.toFile())) {
					namesIn(inner, into);
				}
			} catch (IOException unreadable) {
				throw new IOException("cannot inventory nested mod jar", unreadable);
			} finally {
				Files.deleteIfExists(tmp);
			}
		}
	}

	private static MethodNode method(ClassNode cn, String key) {
		if (cn == null) return null;
		for (MethodNode m : cn.methods) {
			if ((m.name + m.desc).equals(key)) return m;
		}
		return null;
	}

	/**
	 * One method whose hook the merge gave up. {@code fieldInitKept} marks MergedBaseBuilder's second form: the
	 * side that hooked the method lost because only the other side's body initialises a field that side added.
	 * {@code displacedLambda} marks the third: a lambda body the capturer realignment displaced, which no arbitration
	 * or edit splice judged.
	 */
	record Conflict(String owner, String method, String lostFamily, boolean fieldInitKept, boolean displacedLambda) {
		Conflict(String owner, String method, String lostFamily) {
			this(owner, method, lostFamily, false, false);
		}

		Conflict(String owner, String method, String lostFamily, boolean fieldInitKept) {
			this(owner, method, lostFamily, fieldInitKept, false);
		}
	}

	/**
	 * The three row forms MergedBaseBuilder writes. The plain one ends {@code (forge hook lost)}; the field-init one ends
	 * {@code (kept neo body to preserve base-added field init; forge hook lost)}, and matching the plain suffix
	 * alone dropped all six of those without a word -- LivingEntity's constructor, and with it onLivingMakeBrain,
	 * never reached the attribution while the census labelled it UNLISTED although the report lists it. The displaced
	 * one ends {@code (lambda body displaced by its capturer's side; forge hook lost)}.
	 */
	private static final Pattern CONFLICT_ROW = Pattern.compile(
			"^([^\\s#]+)#(\\S+) \\((?:kept (forge|neo) body to preserve base-added field init; |(lambda body displaced by its"
					+ " capturer's side; ))?(forge|neo) hook lost\\)$");

	/** The hook-lost rows of the report; the edit-splice section is read, validated, and never counted as a row. */
	static List<Conflict> conflicts(Path report) throws IOException {
		return report(report).conflicts();
	}

	/** The heading MergedBaseBuilder writes above the per-method edit-splice decisions. */
	static final String SPLICE_SECTION = "=== edit splice:";

	/**
	 * One run of the other side, as the edit-splice section reports it: {@code +} carried into the kept body,
	 * {@code =} already there ({@code same-edit}) or competing with the base's own edit of the same vanilla lines,
	 * {@code x} refused with a reason.
	 */
	record SpliceRun(char mark, String kind, int firstLine, int lastLine, String detail) {
		/** A run the kept body does not have: the base's competing edit stood, or the run could not be placed. */
		boolean lost() {
			return mark == 'x' || (mark == '=' && !kind.equals("same-edit"));
		}
	}

	/** One method's edit-splice entry: whether the splice took it, why not, and what became of each run. */
	record Splice(String owner, String method, boolean spliced, String reason, List<SpliceRun> runs) {
		/**
		 * Taken, yet a run of the other side is reported lost. Such a method is no hook-lost row -- the merge kept
		 * both sides' edits -- but it still lacks the part of the other side's edit that run was.
		 */
		boolean residual() {
			return spliced && runs.stream().anyMatch(SpliceRun::lost);
		}
	}

	record Report(List<Conflict> conflicts, List<Splice> splices) { }

	private static final Pattern SPLICE_HEADER = Pattern.compile("^([^\\s#]+)#(\\S+) (SPLICED|NOT SPLICED \\((.*)\\))$");
	private static final Pattern SPLICE_RUN = Pattern.compile("^    ([+=x]) (\\S+) L(-?\\d+)-(-?\\d+)(?: (.*))?$");

	/**
	 * Both parts of the report a census reads: every hook-lost row, and the edit-splice section. Inside that section
	 * every line is a method entry or one of its runs; a line of neither form stops the run, the same way an
	 * unrecognised hook-lost row does, because a decision the parser cannot read is one the census would silently
	 * misfile. Nothing in the section is ever taken for a hook-lost row, whatever its text ends with.
	 */
	static Report report(Path report) throws IOException {
		List<Conflict> conflicts = new ArrayList<>();
		List<Splice> splices = new ArrayList<>();
		SpliceEntry entry = null;
		boolean inSplice = false;
		for (String line : Files.readAllLines(report, StandardCharsets.UTF_8)) {
			if (line.startsWith("===")) {
				if (entry != null) splices.add(entry.done());
				entry = null;
				inSplice = line.startsWith(SPLICE_SECTION);
				continue;
			}
			if (inSplice) {
				if (line.isBlank()) continue;
				Matcher header = SPLICE_HEADER.matcher(line), run = SPLICE_RUN.matcher(line);
				if (header.matches()) {
					if (entry != null) splices.add(entry.done());
					entry = new SpliceEntry(header.group(1), header.group(2), header.group(3).equals("SPLICED"), header.group(4));
				} else if (run.matches() && entry != null) {
					entry.runs.add(new SpliceRun(run.group(1).charAt(0), run.group(2), Integer.parseInt(run.group(3)),
							Integer.parseInt(run.group(4)), run.group(5) == null ? "" : run.group(5)));
				} else {
					throw new IOException("unrecognised edit-splice row in " + report + ": " + line);
				}
				continue;
			}
			if (!line.trim().endsWith("hook lost)")) continue;
			// A row the parser does not understand is a row the denominator silently loses, so it stops the run:
			// the next form MergedBaseBuilder learns to write has to be taught here, not skipped here.
			Matcher row = CONFLICT_ROW.matcher(line.trim());
			if (!row.matches()) throw new IOException("unrecognised hook-lost row in " + report + ": " + line);
			String kept = row.group(3), lost = row.group(5);
			if (kept != null && kept.equals(lost)) throw new IOException("row keeps and loses the same side: " + line);
			conflicts.add(new Conflict(row.group(1), row.group(2), lost, kept != null, row.group(4) != null));
		}
		if (entry != null) splices.add(entry.done());
		return new Report(List.copyOf(conflicts), List.copyOf(splices));
	}

	/** An edit-splice entry while its run lines are still being read. */
	private record SpliceEntry(String owner, String method, boolean spliced, String reason, List<SpliceRun> runs) {
		SpliceEntry(String owner, String method, boolean spliced, String reason) {
			this(owner, method, spliced, reason, new ArrayList<>());
		}

		Splice done() {
			return new Splice(owner, method, spliced, reason, List.copyOf(runs));
		}
	}

	static Map<String, ClassNode> load(String jar, String label) throws IOException {
		Map<String, ClassNode> out = new LinkedHashMap<>();
		try (ZipFile zf = new ZipFile(jar)) {
			var entries = zf.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				if (!e.getName().endsWith(".class")) continue;
				ClassNode cn = new ClassNode();
				try (InputStream in = zf.getInputStream(e)) {
					new ClassReader(in.readAllBytes()).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
				}
				if (out.putIfAbsent(cn.name, cn) != null) {
					throw new IOException("ambiguous duplicate class identity in " + jar + ": " + cn.name);
				}
			}
		}
		System.out.println("[attribution] " + label + " : " + out.size() + " classes");
		return out;
	}

	static {
		Map<String, Integer> unused = new TreeMap<>();
		assert unused.isEmpty();
	}
}
