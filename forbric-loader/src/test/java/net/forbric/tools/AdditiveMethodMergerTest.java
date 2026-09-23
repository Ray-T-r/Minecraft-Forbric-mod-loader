/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public class AdditiveMethodMergerTest {

	static final String NEO = "net/neoforged/Probe";
	static final String FORGE = "net/minecraftforge/Probe";
	private static final List<Integer> OBSERVED = new ArrayList<>();
	private static boolean failHook;

	public static void record(int family, int value) {
		OBSERVED.add(family * 100 + value);
		if (failHook) throw new ArithmeticException("from hook");
	}

	/** Families whose result hook refuses (answers empty); every call is still recorded by family number. */
	private static final List<Integer> REFUSING = new ArrayList<>();

	public static Optional<?> filter(int family, Optional<?> value) {
		OBSERVED.add(family);
		return REFUSING.contains(family) ? Optional.empty() : value;
	}

	@Test
	void composesBothEntryHooksWithoutMutatingInputsAndExecutesTheOriginalBody() throws Exception {
		MethodNode vanilla = arithmetic();
		MethodNode base = prefix(vanilla, NEO), other = prefix(vanilla, FORGE);
		byte[] beforeBase = classBytes("Example", base), beforeOther = classBytes("Example", other);
		AdditiveMethodMerger.Result result = merge(vanilla, base, other);
		assertTrue(result.accepted(), result.reason());
		assertEquals(1, result.baseCalls());
		assertEquals(1, result.otherCalls());
		assertArrayEquals(beforeBase, classBytes("Example", base));
		assertArrayEquals(beforeOther, classBytes("Example", other));
		OBSERVED.clear();
		assertEquals(10, invoke(result.method(), 3));
		assertEquals(List.of(103, 203), OBSERVED);
	}

	@Test
	void originalControlFlowAndExceptionRegionArePreservedAndDoNotCatchNewHooks() throws Exception {
		MethodNode vanilla = divisionWithHandler();
		AdditiveMethodMerger.Result result = merge(vanilla, prefix(vanilla, NEO), prefix(vanilla, FORGE));
		assertTrue(result.accepted(), result.reason());
		assertEquals(5, invoke(result.method(), 2));
		assertEquals(-1, invoke(result.method(), 0));
		try {
			failHook = true;
			InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
					() -> invoke(result.method(), 0));
			assertInstanceOf(ArithmeticException.class, thrown.getCause());
		} finally {
			failHook = false;
		}
	}

	@Test
	void changedImmediateAndLocalSlotCannotHideBehindEqualOpcodes() {
		refusesChangedBody(arithmetic(), m -> ((IntInsnNode) executable(m).get(3)).operand++);
		MethodNode twoArguments = arithmetic();
		twoArguments.desc = "(II)I";
		refusesChangedBody(twoArguments, m -> ((VarInsnNode) executable(m).get(2)).var = 1);
	}

	@Test
	void incrementOperandAndSwitchKeysAndDestinationsAreCompared() {
		MethodNode increment = method("(I)I");
		increment.instructions.add(new IincInsnNode(0, 1));
		increment.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		increment.instructions.add(new InsnNode(Opcodes.IRETURN));
		refusesChangedBody(increment, m -> ((IincInsnNode) executable(m).get(2)).incr = 2);

		MethodNode switching = method("(I)I");
		LabelNode hit = new LabelNode(), miss = new LabelNode();
		switching.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		switching.instructions.add(new LookupSwitchInsnNode(miss, new int[] { 1 }, new LabelNode[] { hit }));
		switching.instructions.add(hit);
		switching.instructions.add(new InsnNode(Opcodes.ICONST_1));
		switching.instructions.add(new InsnNode(Opcodes.IRETURN));
		switching.instructions.add(miss);
		switching.instructions.add(new InsnNode(Opcodes.ICONST_0));
		switching.instructions.add(new InsnNode(Opcodes.IRETURN));
		assertTrue(merge(switching, prefix(switching, NEO), prefix(switching, FORGE)).accepted());
		refusesChangedBody(switching, m -> ((LookupSwitchInsnNode) executable(m).get(3)).keys.set(0, 2));
		refusesChangedBody(switching, m -> {
			LookupSwitchInsnNode s = (LookupSwitchInsnNode) executable(m).get(3);
			s.labels.set(0, s.dflt);
		});
	}

	@Test
	void changedJumpDestinationAndExpandedHandlerAreRejected() {
		MethodNode vanilla = divisionWithHandler();
		MethodNode other = prefix(vanilla, FORGE);
		LabelNode start = new LabelNode();
		other.instructions.insert(start);
		other.tryCatchBlocks.get(0).start = start;
		assertFalse(merge(vanilla, prefix(vanilla, NEO), other).accepted());

		MethodNode branch = method("(I)I");
		LabelNode first = new LabelNode(), second = new LabelNode();
		branch.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		branch.instructions.add(new JumpInsnNode(Opcodes.IFEQ, first));
		branch.instructions.add(second);
		branch.instructions.add(new InsnNode(Opcodes.ICONST_1));
		branch.instructions.add(new InsnNode(Opcodes.IRETURN));
		branch.instructions.add(first);
		branch.instructions.add(new InsnNode(Opcodes.ICONST_0));
		branch.instructions.add(new InsnNode(Opcodes.IRETURN));
		refusesChangedBody(branch, m -> {
			JumpInsnNode jump = (JumpInsnNode) executable(m).get(3);
			jump.label = (LabelNode) jump.getNext();
		});
	}

	@Test
	void bootstrapOwnerArgumentsAndDynamicConstantsAreNotDiscarded() {
		Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, "Bootstrap", "make", "()V", false);
		MethodNode dynamic = arithmetic();
		dynamic.instructions.insert(new InvokeDynamicInsnNode("call", "()V", bootstrap, 1));
		refusesChangedBody(dynamic, m -> ((InvokeDynamicInsnNode) executable(m).get(2)).bsmArgs = new Object[] { 2 });
		refusesChangedBody(dynamic, m -> ((InvokeDynamicInsnNode) executable(m).get(2)).bsm =
				new Handle(Opcodes.H_INVOKESTATIC, "DifferentBootstrap", "make", "()V", false));
		MethodNode constant = method("(I)I");
		constant.instructions.add(new LdcInsnNode(new ConstantDynamic("value", "I", bootstrap, 1)));
		constant.instructions.add(new InsnNode(Opcodes.IRETURN));
		refusesChangedBody(constant, m -> ((LdcInsnNode) executable(m).get(2)).cst =
				new ConstantDynamic("value", "I", bootstrap, 2));
	}

	@Test
	void wideParameterSlotsAndLiteralArgumentTypesAreChecked() {
		MethodNode vanilla = method("(JDLjava/lang/String;)I");
		vanilla.instructions.add(new InsnNode(Opcodes.ICONST_1));
		vanilla.instructions.add(new InsnNode(Opcodes.IRETURN));
		List<MethodNode> sides = new ArrayList<>();
		for (String family : List.of(NEO, FORGE)) {
			MethodNode side = new MethodNode(Opcodes.ASM9, vanilla.access, vanilla.name, vanilla.desc, null, null);
			vanilla.accept(side);
			InsnList prefix = new InsnList();
			prefix.add(new VarInsnNode(Opcodes.LLOAD, 0));
			prefix.add(new VarInsnNode(Opcodes.DLOAD, 2));
			prefix.add(new VarInsnNode(Opcodes.ALOAD, 4));
			prefix.add(new InsnNode(Opcodes.ACONST_NULL));
			prefix.add(new IntInsnNode(Opcodes.SIPUSH, 1200));
			prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, family, "observe",
					"(JDLjava/lang/String;Ljava/lang/Object;I)V", false));
			side.instructions.insert(prefix);
			sides.add(side);
		}
		assertTrue(merge(vanilla, sides.get(0), sides.get(1)).accepted());
		((VarInsnNode) executable(sides.get(1)).get(0)).var = 1; // second half of the long is not a parameter
		assertFalse(merge(vanilla, sides.get(0), sides.get(1)).accepted());
	}

	@Test
	void nonVoidHooksLocalWritesAndUnknownHelpersAreRefused() {
		MethodNode vanilla = arithmetic();
		for (Consumer<MethodNode> mutation : List.<Consumer<MethodNode>>of(
				m -> ((MethodInsnNode) executable(m).get(1)).desc = "(I)I",
				m -> m.instructions.insert(new IincInsnNode(0, 1)),
				m -> ((MethodInsnNode) executable(m).get(1)).owner = "Example$1",
				m -> ((VarInsnNode) executable(m).get(0)).var = 3,
				m -> ((VarInsnNode) executable(m).get(0)).setOpcode(Opcodes.ALOAD))) {
			MethodNode other = prefix(vanilla, FORGE);
			mutation.accept(other);
			AdditiveMethodMerger.Result result = merge(vanilla, prefix(vanilla, NEO), other);
			assertFalse(result.accepted(), result.reason());
			assertTrue(result.reason().contains("prefix"), result.reason());
		}
	}

	@Test
	void constructorsMissingVanillaAndChangedExecutionFlagsKeepExistingArbitration() {
		MethodNode vanilla = arithmetic();
		assertFalse(merge(null, prefix(vanilla, NEO), prefix(vanilla, FORGE)).accepted());
		MethodNode other = prefix(vanilla, FORGE);
		other.access &= ~Opcodes.ACC_STATIC;
		assertFalse(merge(vanilla, prefix(vanilla, NEO), other).accepted());
		vanilla.name = "<clinit>";
		assertFalse(merge(vanilla, prefix(vanilla, NEO), prefix(vanilla, FORGE)).accepted());
	}

	@Test
	void entryHooksThatDoNotLinkOrWhoseRuntimeCompensationDoesNotStandDownAreNotRestored() {
		MethodNode vanilla = arithmetic();
		AdditiveMethodMerger.Result unlinked = merge(vanilla, prefix(vanilla, NEO), prefix(vanilla, FORGE), context(false, true));
		assertFalse(unlinked.accepted());
		assertTrue(unlinked.reason().startsWith("entry hook does not resolve"), unlinked.reason());
		AdditiveMethodMerger.Result unreviewed = merge(vanilla, prefix(vanilla, NEO), prefix(vanilla, FORGE), context(true, false));
		assertFalse(unreviewed.accepted());
		assertTrue(unreviewed.reason().contains("no runtime stand-down is reviewed: restoring " + FORGE + ".observe(I)V"),
				unreviewed.reason());
	}

	@Test
	void pairedObserverHooksInsideAChangedBodyComposeBaseThenOther() throws Exception {
		// Both pipelines made the SAME change to vanilla (the leading no-op pair); only the hook owner differs.
		// The site sits with two values already on the stack, which the self-contained call must not disturb.
		MethodNode vanilla = arithmetic();
		MethodNode base = paired(vanilla, NEO), other = paired(vanilla, FORGE);
		assertFalse(merge(vanilla, prefix(vanilla, NEO), other).accepted(), "an entry hook is not at the same stage");
		byte[] beforeBase = classBytes("Example", base), beforeOther = classBytes("Example", other);
		AdditiveMethodMerger.Result result = merge(vanilla, base, other);
		assertTrue(result.accepted(), result.reason());
		assertTrue(result.reason().contains("1 observer(s), 0 guarded result(s)"), result.reason());
		assertArrayEquals(beforeBase, classBytes("Example", base));
		assertArrayEquals(beforeOther, classBytes("Example", other));
		OBSERVED.clear();
		assertEquals(10, invoke(result.method(), 3));
		assertEquals(List.of(103, 203), OBSERVED);
	}

	@Test
	void pairedResultHooksRepeatTheBaseRefusalBeforeTheOtherFamilyIsAsked() throws Exception {
		MethodNode vanilla = choosing();
		AdditiveMethodMerger.Result result = merge(vanilla, filtered(vanilla, NEO), filtered(vanilla, FORGE));
		assertTrue(result.accepted(), result.reason());
		assertTrue(result.reason().contains("0 observer(s), 1 guarded result(s)"), result.reason());
		byte[] bytes = classBytes("Example", result.method());
		for (List<Integer> refusing : List.of(List.<Integer>of(), List.of(1), List.of(2))) {
			try {
				REFUSING.clear(); REFUSING.addAll(refusing); OBSERVED.clear();
				assertEquals(refusing.isEmpty() ? 1 : 0, choose(bytes, Optional.of("portal")));
				assertEquals(refusing.equals(List.of(1)) ? List.of(1) : List.of(1, 2), OBSERVED,
						"a value the first family refused never reaches the second");
			} finally {
				REFUSING.clear();
			}
		}
		OBSERVED.clear();
		assertEquals(0, choose(bytes, Optional.empty()));
		assertEquals(List.of(1), OBSERVED, "the base family still sees what vanilla found, as it does natively");
	}

	@Test
	void pairedHookSitesThatCannotHoldBothCallsAreRefusedWithTheirReason() {
		MethodNode integer = method("(I)I");
		integer.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		integer.instructions.add(new InsnNode(Opcodes.IRETURN));
		record Case(String reason, java.util.function.BiConsumer<MethodNode, String> insert) { }
		for (Case c : List.of(
				new Case("result is branched on", (m, family) -> {
					LabelNode refuse = new LabelNode();
					InsnList site = new InsnList();
					site.add(new VarInsnNode(Opcodes.ILOAD, 0));
					site.add(new MethodInsnNode(Opcodes.INVOKESTATIC, family, "allow", "(I)Z", false));
					site.add(new JumpInsnNode(Opcodes.IFEQ, refuse));
					m.instructions.insert(site);
					m.instructions.add(refuse);
					m.instructions.add(new InsnNode(Opcodes.ICONST_0));
					m.instructions.add(new InsnNode(Opcodes.IRETURN));
				}),
				new Case("stores a result it does not read", (m, family) -> m.instructions.insert(site(family, "value",
						"(I)I", new VarInsnNode(Opcodes.ILOAD, 0), new VarInsnNode(Opcodes.ISTORE, 1)))),
				new Case("without the Optional refusal guard", (m, family) -> m.instructions.insert(site(family, "adjust",
						"(I)I", new VarInsnNode(Opcodes.ILOAD, 0), new VarInsnNode(Opcodes.ISTORE, 0)))),
				new Case("are not plain loads or constants", (m, family) -> {
					InsnList site = new InsnList();
					site.add(new VarInsnNode(Opcodes.ILOAD, 0));
					site.add(new InsnNode(Opcodes.ICONST_1));
					site.add(new InsnNode(Opcodes.IADD));
					site.add(new MethodInsnNode(Opcodes.INVOKESTATIC, family, "observe", "(I)V", false));
					m.instructions.insert(site);
				}),
				new Case("a branch or handler enters the site", (m, family) -> {
					LabelNode inside = new LabelNode();
					InsnList site = new InsnList();
					site.add(new VarInsnNode(Opcodes.ILOAD, 0));
					site.add(new JumpInsnNode(Opcodes.IFNE, inside));
					site.add(new InsnNode(Opcodes.ICONST_0));
					site.add(new InsnNode(Opcodes.ICONST_0));
					site.add(inside);
					site.add(new MethodInsnNode(Opcodes.INVOKESTATIC, family, "pair", "(II)V", false));
					m.instructions.insert(site);
				}),
				new Case("a handler range boundary falls inside the site", (m, family) -> {
					LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
					InsnList site = new InsnList();
					site.add(new VarInsnNode(Opcodes.ILOAD, 0));
					site.add(start);
					site.add(new MethodInsnNode(Opcodes.INVOKESTATIC, family, "observe", "(I)V", false));
					site.add(end);
					m.instructions.insert(site);
					m.instructions.add(handler);
					m.instructions.add(new InsnNode(Opcodes.ATHROW));
					m.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
				}))) {
			MethodNode base = copy(integer), other = copy(integer);
			c.insert().accept(base, NEO);
			c.insert().accept(other, FORGE);
			AdditiveMethodMerger.Result result = merge(integer, base, other);
			assertFalse(result.accepted(), result.reason());
			assertTrue(result.reason().startsWith("paired hook site not composable") && result.reason().contains(c.reason()),
					result.reason());
		}

		MethodNode vanilla = choosing();
		AdditiveMethodMerger.Result unlinked = merge(vanilla, filtered(vanilla, NEO), filtered(vanilla, FORGE), context(false, true));
		assertTrue(unlinked.reason().startsWith("paired hook does not resolve"), unlinked.reason());
		AdditiveMethodMerger.Result held = merge(vanilla, filtered(vanilla, NEO), filtered(vanilla, FORGE), context(true, false));
		assertFalse(held.accepted());
		assertTrue(held.reason().startsWith("paired hooks align, but no runtime stand-down is reviewed: restoring " + FORGE),
				held.reason());
		MethodNode renamed = filtered(vanilla, FORGE);
		for (AbstractInsnNode instruction : renamed.instructions) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(FORGE)) call.name = "differentFilter";
		}
		AdditiveMethodMerger.Result unpaired = merge(vanilla, filtered(vanilla, NEO), renamed);
		assertFalse(unpaired.accepted());
		assertFalse(unpaired.reason().startsWith("paired"), "differently named hooks are not a pair: " + unpaired.reason());
	}

	@Test
	void hooksPostedAtDifferentVanillaStagesAreRefusedWhateverTheGrammar() {
		// The fall-damage shape in miniature: vanilla clamps its argument into a local, then uses it. One family
		// fires at entry with the raw argument, the other after the clamp with the local.
		MethodNode vanilla = method("(D)D");
		vanilla.instructions.add(new VarInsnNode(Opcodes.DLOAD, 0));
		vanilla.instructions.add(new InsnNode(Opcodes.DCONST_1));
		vanilla.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false));
		vanilla.instructions.add(new VarInsnNode(Opcodes.DSTORE, 2));
		vanilla.instructions.add(new VarInsnNode(Opcodes.DLOAD, 2));
		vanilla.instructions.add(new InsnNode(Opcodes.DRETURN));
		MethodNode entry = copy(vanilla), clamped = copy(vanilla);
		entry.instructions.insert(site(FORGE, "onFall", "(D)V", new VarInsnNode(Opcodes.DLOAD, 0)));
		clamped.instructions.insert(executable(clamped).get(3), site(NEO, "onFall", "(D)V", new VarInsnNode(Opcodes.DLOAD, 2)));
		for (boolean neoBase : new boolean[] { true, false }) {
			AdditiveMethodMerger.Result result = neoBase ? merge(vanilla, clamped, entry)
					: AdditiveMethodMerger.merge(vanilla, entry, clamped, "net/minecraftforge/", "net/neoforged/", PROBES);
			assertFalse(result.accepted(), result.reason());
			assertTrue(result.reason().startsWith("hook stages differ"), result.reason());
			assertTrue(result.reason().contains("Probe.onFall at vanilla #0") && result.reason().contains("Probe.onFall at vanilla #4"),
					result.reason());
		}
		// Control: the same hooks at the same stage are not refused for their stage.
		MethodNode sameStage = copy(vanilla);
		sameStage.instructions.insert(site(NEO, "onFall", "(D)V", new VarInsnNode(Opcodes.DLOAD, 0)));
		assertTrue(merge(vanilla, sameStage, entry).accepted(), merge(vanilla, sameStage, entry).reason());
	}

	@Test
	void aVanillaCallOneSideReplacedAndTheOtherKeptIsRefused() {
		// NeoForge's spawner hook finalizes the mob itself; MinecraftForge keeps vanilla's finalizeSpawn after its own.
		MethodNode vanilla = method("(I)V");
		vanilla.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		vanilla.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "game/Mob", "finish", "(I)V", false));
		vanilla.instructions.add(new InsnNode(Opcodes.RETURN));
		MethodNode replaced = copy(vanilla), kept = copy(vanilla);
		((MethodInsnNode) executable(replaced).get(1)).owner = NEO;
		kept.instructions.insert(site(FORGE, "observe", "(I)V", new VarInsnNode(Opcodes.ILOAD, 0)));
		AdditiveMethodMerger.Result result = merge(vanilla, replaced, kept);
		assertFalse(result.accepted(), result.reason());
		assertEquals("one side replaces a vanilla call: base drops game/Mob.finish(I)V (vanilla #1) that other keeps",
				result.reason());
	}

	private static void refusesChangedBody(MethodNode vanilla, Consumer<MethodNode> mutate) {
		MethodNode other = prefix(vanilla, FORGE);
		mutate.accept(other);
		AdditiveMethodMerger.Result result = merge(vanilla, prefix(vanilla, NEO), other);
		assertFalse(result.accepted(), result.reason());
		assertTrue(result.reason().contains("operands, control flow or handlers"), result.reason());
	}

	static AdditiveMethodMerger.Result merge(MethodNode vanilla, MethodNode base, MethodNode other) {
		return merge(vanilla, base, other, PROBES);
	}

	static AdditiveMethodMerger.Result merge(MethodNode vanilla, MethodNode base, MethodNode other,
			AdditiveMethodMerger.Context context) {
		return AdditiveMethodMerger.merge(vanilla, base, other, "net/neoforged/", "net/minecraftforge/", context);
	}

	/** Both probe families link and restoring either is treated as reviewed, so a test sees the grammar alone. */
	static final AdditiveMethodMerger.Context PROBES = context(true, true);

	static AdditiveMethodMerger.Context context(boolean resolves, boolean reviewed) {
		return new AdditiveMethodMerger.Context() {
			@Override public boolean resolvesStatic(String owner, String name, String descriptor) {
				return resolves && (owner.equals(NEO) || owner.equals(FORGE));
			}

			@Override public boolean reviewed(MethodInsnNode alongside, MethodInsnNode restored) { return reviewed; }
		};
	}

	static MethodNode method(String descriptor) {
		return new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "compute", descriptor, null, null);
	}

	static MethodNode arithmetic() {
		MethodNode method = method("(I)I");
		method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		method.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 7));
		method.instructions.add(new InsnNode(Opcodes.IADD));
		method.instructions.add(new InsnNode(Opcodes.IRETURN));
		return method;
	}

	static MethodNode divisionWithHandler() {
		MethodNode method = method("(I)I");
		LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
		method.instructions.add(start);
		method.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 10));
		method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		method.instructions.add(new InsnNode(Opcodes.IDIV));
		method.instructions.add(end);
		method.instructions.add(new InsnNode(Opcodes.IRETURN));
		method.instructions.add(handler);
		method.instructions.add(new InsnNode(Opcodes.POP));
		method.instructions.add(new InsnNode(Opcodes.ICONST_M1));
		method.instructions.add(new InsnNode(Opcodes.IRETURN));
		method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/ArithmeticException"));
		return method;
	}

	static MethodNode copy(MethodNode source) {
		MethodNode result = new MethodNode(Opcodes.ASM9, source.access, source.name, source.desc, source.signature,
				source.exceptions.toArray(String[]::new));
		source.accept(result);
		return result;
	}

	/** Plain argument loads, one static call into {@code family}, then whatever consumes its result. */
	static InsnList site(String family, String name, String descriptor, AbstractInsnNode... around) {
		InsnList site = new InsnList();
		int loads = org.objectweb.asm.Type.getArgumentTypes(descriptor).length;
		for (int i = 0; i < around.length; i++) {
			if (i == loads) site.add(new MethodInsnNode(Opcodes.INVOKESTATIC, family, name, descriptor, false));
			site.add(around[i]);
		}
		if (around.length == loads) site.add(new MethodInsnNode(Opcodes.INVOKESTATIC, family, name, descriptor, false));
		return site;
	}

	/** arithmetic(), changed identically by both pipelines, with the family's observer after the literal. */
	static MethodNode paired(MethodNode vanilla, String family) {
		MethodNode result = copy(vanilla);
		result.instructions.insert(executable(result).get(1), site(family, "observe", "(I)V", new VarInsnNode(Opcodes.ILOAD, 0)));
		InsnList shared = new InsnList();
		shared.add(new InsnNode(Opcodes.ICONST_0));
		shared.add(new InsnNode(Opcodes.POP));
		result.instructions.insert(shared);
		return result;
	}

	/** {@code int choose(Optional found)}: the shape of BaseFireBlock's portal consumer. */
	static MethodNode choosing() {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "choose", "(Ljava/util/Optional;)I", null, null);
		LabelNode none = new LabelNode();
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false));
		method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, none));
		method.instructions.add(new InsnNode(Opcodes.ICONST_1));
		method.instructions.add(new InsnNode(Opcodes.IRETURN));
		method.instructions.add(none);
		method.instructions.add(new InsnNode(Opcodes.ICONST_0));
		method.instructions.add(new InsnNode(Opcodes.IRETURN));
		return method;
	}

	/** choosing() with the family's result hook threaded through the local, as both portal patches do. */
	static MethodNode filtered(MethodNode vanilla, String family) {
		MethodNode result = copy(vanilla);
		result.instructions.insert(executable(result).get(1), site(family, "filter",
				"(Ljava/util/Optional;)Ljava/util/Optional;", new VarInsnNode(Opcodes.ALOAD, 1), new VarInsnNode(Opcodes.ASTORE, 1)));
		return result;
	}

	static MethodNode prefix(MethodNode source, String owner) {
		MethodNode result = new MethodNode(Opcodes.ASM9, source.access, source.name, source.desc, source.signature,
				source.exceptions.toArray(String[]::new));
		source.accept(result);
		InsnList prefix = new InsnList();
		prefix.add(new VarInsnNode(Opcodes.ILOAD, 0));
		prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "observe", "(I)V", false));
		result.instructions.insert(prefix);
		return result;
	}

	static List<AbstractInsnNode> executable(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) out.add(instruction);
		return out;
	}

	static byte[] classBytes(String name, MethodNode method) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		method.accept(writer);
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static Object invoke(MethodNode method, int value) throws Exception {
		return invokeClass("Example", classBytes("Example", method), value);
	}

	static Object invokeClass(String owner, byte[] bytes, int value) throws Exception {
		return load(owner, bytes).getMethod("compute", int.class).invoke(null, value);
	}

	static int choose(byte[] bytes, Optional<?> found) throws Exception {
		return chooseClass("Example", bytes, found);
	}

	static int chooseClass(String owner, byte[] bytes, Optional<?> found) throws Exception {
		return (int) load(owner, bytes).getMethod("choose", Optional.class).invoke(null, found);
	}

	private static Class<?> load(String owner, byte[] bytes) {
		class Loader extends ClassLoader {
			Class<?> define(String name, byte[] bytes) { return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); }
		}
		Loader loader = new Loader();
		for (String family : List.of(NEO, FORGE)) loader.define(family, hookClass(family));
		return loader.define(owner, bytes);
	}

	/** A probe family's hook owner: observe(I)V records, filter(Optional)Optional records and may refuse. */
	static byte[] hookClass(String family) {
		int number = family.equals(NEO) ? Opcodes.ICONST_1 : Opcodes.ICONST_2;
		MethodNode observe = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "observe", "(I)V", null, null);
		observe.instructions.add(new InsnNode(number));
		observe.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
		observe.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TypeNames.RECORDER, "record", "(II)V", false));
		observe.instructions.add(new InsnNode(Opcodes.RETURN));
		MethodNode filter = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "filter",
				"(Ljava/util/Optional;)Ljava/util/Optional;", null, null);
		filter.instructions.add(new InsnNode(number));
		filter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		filter.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TypeNames.RECORDER, "filter",
				"(ILjava/util/Optional;)Ljava/util/Optional;", false));
		filter.instructions.add(new InsnNode(Opcodes.ARETURN));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, family, null, "java/lang/Object", null);
		observe.accept(writer);
		filter.accept(writer);
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static final class TypeNames {
		static final String RECORDER = AdditiveMethodMergerTest.class.getName().replace('.', '/');
	}
}
