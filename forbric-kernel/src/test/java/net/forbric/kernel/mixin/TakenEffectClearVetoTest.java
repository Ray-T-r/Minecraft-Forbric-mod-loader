package net.forbric.kernel.mixin;

import static net.forbric.kernel.mixin.PerEffectClearVetoTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * A clear-all effect veto that decides from an effect other than the one being decided is not a per-effect veto: replayed
 * on a one-effect map, "the first effect" is every effect, "the effect after this one" never exists, and a loop left early
 * — or one that keeps a count or the previous effect in an array — decides later effects from earlier ones. Each RED
 * fixture is refused for that reason (the verdict says so, and its bytecode verifies, so it is not refused for being
 * malformed) and left as written. Each kind is paired with a control that makes the same comparison, removal, loop or
 * array per effect, and is still re-hosted.
 *
 * <p>None of the fixtures is a mod's code: the first effect is taken through {@code keySet()}, {@code entrySet()} with
 * {@code Objects.equals}, a stream's {@code findFirst()} and Kotlin's {@code first()}; the per-effect controls use a map
 * copy with lookups, an iterator removal on the live map, a loop tested at its bottom and an array made each round.
 */
@ResourceLock("system-properties")
class TakenEffectClearVetoTest {
	private static final String WARDS = "org/example/wards/Wards";
	private static final String PINNED = "L" + HOLDER + ";";
	private static final String SET = "java/util/Set", COLLECTION = "java/util/Collection";

	@AfterEach void reset() {
		System.clearProperty(FabricEntityMixinAnchors.CLEAR_VETO_PROPERTY);
	}

	// ---- RED: an effect taken once per clear decides

	/** {@code Holder first = effects.keySet().iterator().next(); if (first == Wards.PINNED) return; original.call(effects);} */
	@Test void theFirstEffectDecidingTheWholeClearIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/PinFirstMixin");
		MethodNode m = handler("pinFirst", 0);
		LabelNode skip = new LabelNode();
		add(m, new VarInsnNode(Opcodes.ALOAD, 1), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "keySet", "()Ljava/util/Set;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, SET, "iterator", "()Ljava/util/Iterator;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true), new TypeInsnNode(Opcodes.CHECKCAST, HOLDER),
				new VarInsnNode(Opcodes.ASTORE, 3), new VarInsnNode(Opcodes.ALOAD, 3), new FieldInsnNode(Opcodes.GETSTATIC, WARDS, "PINNED", PINNED),
				new JumpInsnNode(Opcodes.IF_ACMPEQ, skip));
		callOriginal(m.instructions);
		add(m, skip, new InsnNode(Opcodes.RETURN));
		m.maxLocals = 4;
		mixin.methods.add(m);
		assertRefused(mixin, m, "compares an effect taken once per clear");
	}

	/** Control: the same identity test made for each effect — {@code for (k : copy.keySet()) if (k == Wards.PINNED) effects.put(k, copy.get(k));} */
	@Test void theSameTestMadeForEachEffectIsReplayed() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/PinEachMixin");
		MethodNode m = handler("pinEach", 0);
		LabelNode head = new LabelNode(), end = new LabelNode();
		add(m, new VarInsnNode(Opcodes.ALOAD, 1),
				new MethodInsnNode(Opcodes.INVOKESTATIC, MAP, "copyOf", "(Ljava/util/Map;)Ljava/util/Map;", true), new VarInsnNode(Opcodes.ASTORE, 3));
		callOriginal(m.instructions);
		add(m, new VarInsnNode(Opcodes.ALOAD, 3), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "keySet", "()Ljava/util/Set;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, SET, "iterator", "()Ljava/util/Iterator;", true), new VarInsnNode(Opcodes.ASTORE, 4),
				head, new VarInsnNode(Opcodes.ALOAD, 4), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z", true),
				new JumpInsnNode(Opcodes.IFEQ, end), new VarInsnNode(Opcodes.ALOAD, 4),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true), new TypeInsnNode(Opcodes.CHECKCAST, HOLDER),
				new VarInsnNode(Opcodes.ASTORE, 5), new VarInsnNode(Opcodes.ALOAD, 5), new FieldInsnNode(Opcodes.GETSTATIC, WARDS, "PINNED", PINNED),
				new JumpInsnNode(Opcodes.IF_ACMPNE, head), new VarInsnNode(Opcodes.ALOAD, 1), new VarInsnNode(Opcodes.ALOAD, 5),
				new VarInsnNode(Opcodes.ALOAD, 3), new VarInsnNode(Opcodes.ALOAD, 5),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true),
				new InsnNode(Opcodes.POP), new JumpInsnNode(Opcodes.GOTO, head), end, new InsnNode(Opcodes.RETURN));
		m.maxLocals = 6;
		mixin.methods.add(m);
		verify(mixin, m);
		assertTrue(ClearVetoProof.prove(mixin, m).proved(), ClearVetoProof.prove(mixin, m).why());
		assertReplayed(mixin, "pinEach");
	}

	/** {@code if (Objects.equals(effects.entrySet().iterator().next().getKey(), Wards.PINNED)) return; original.call(effects);} */
	@Test void theFirstEffectComparedWithObjectsEqualsIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/FirstEntryMixin");
		MethodNode m = handler("firstEntry", 0);
		LabelNode skip = new LabelNode();
		add(m, new VarInsnNode(Opcodes.ALOAD, 1), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "entrySet", "()Ljava/util/Set;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, SET, "iterator", "()Ljava/util/Iterator;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true), new TypeInsnNode(Opcodes.CHECKCAST, ENTRY),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getKey", "()Ljava/lang/Object;", true),
				new FieldInsnNode(Opcodes.GETSTATIC, WARDS, "PINNED", PINNED),
				new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false),
				new JumpInsnNode(Opcodes.IFNE, skip));
		callOriginal(m.instructions);
		add(m, skip, new InsnNode(Opcodes.RETURN));
		mixin.methods.add(m);
		assertRefused(mixin, m, "decides on an effect taken once per clear");
	}

	/** {@code if (effects.values().stream().findFirst().orElse(null).getEffect() == Wards.PINNED) return; original.call(effects);} */
	@Test void theFirstEffectFromAStreamsFindFirstIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/StreamFirstMixin");
		MethodNode m = handler("streamFirst", 0);
		LabelNode skip = new LabelNode();
		add(m, new VarInsnNode(Opcodes.ALOAD, 1), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "values", "()Ljava/util/Collection;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, COLLECTION, "stream", "()Ljava/util/stream/Stream;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream", "findFirst", "()Ljava/util/Optional;", true),
				new InsnNode(Opcodes.ACONST_NULL),
				new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;", false),
				new TypeInsnNode(Opcodes.CHECKCAST, EFFECT), new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EFFECT, "getEffect", "()L" + HOLDER + ";", false),
				new FieldInsnNode(Opcodes.GETSTATIC, WARDS, "PINNED", PINNED), new JumpInsnNode(Opcodes.IF_ACMPEQ, skip));
		callOriginal(m.instructions);
		add(m, skip, new InsnNode(Opcodes.RETURN));
		mixin.methods.add(m);
		assertRefused(mixin, m, null);
	}

	/** kotlinc's {@code if (effects.keys.first() == Wards.PINNED) return; original.call(effects)}, with its parameter checks. */
	@Test void kotlinsFirstIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/KotlinFirstMixin");
		MethodNode m = handler("kotlinFirst", 0);
		LabelNode skip = new LabelNode();
		add(m, new VarInsnNode(Opcodes.ALOAD, 1), new LdcInsnNode("effects"),
				new MethodInsnNode(Opcodes.INVOKESTATIC, "kotlin/jvm/internal/Intrinsics", "checkNotNullParameter", "(Ljava/lang/Object;Ljava/lang/String;)V", false),
				new VarInsnNode(Opcodes.ALOAD, 1), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "keySet", "()Ljava/util/Set;", true),
				new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Iterable"),
				new MethodInsnNode(Opcodes.INVOKESTATIC, "kotlin/collections/CollectionsKt", "first", "(Ljava/lang/Iterable;)Ljava/lang/Object;", false),
				new FieldInsnNode(Opcodes.GETSTATIC, WARDS, "PINNED", PINNED), new JumpInsnNode(Opcodes.IF_ACMPEQ, skip));
		callOriginal(m.instructions);
		add(m, skip, new InsnNode(Opcodes.RETURN));
		mixin.methods.add(m);
		assertRefused(mixin, m, null);
	}

	/** A second effect taken in the same round: {@code a = it.next(); b = it.next(); if (Guards.keep(self, a.getKey(), b.getValue())) ...} */
	@Test void twoEffectsTakenInOneRoundAreLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/PairedMixin");
		MethodNode m = handler("paired", 0);
		LabelNode head = new LabelNode(), end = new LabelNode();
		add(m, new VarInsnNode(Opcodes.ALOAD, 1), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "entrySet", "()Ljava/util/Set;", true),
				new MethodInsnNode(Opcodes.INVOKESTATIC, SET, "copyOf", "(Ljava/util/Collection;)Ljava/util/Set;", true), new VarInsnNode(Opcodes.ASTORE, 3));
		callOriginal(m.instructions);
		add(m, new VarInsnNode(Opcodes.ALOAD, 3), new MethodInsnNode(Opcodes.INVOKEINTERFACE, SET, "iterator", "()Ljava/util/Iterator;", true),
				new VarInsnNode(Opcodes.ASTORE, 4), head, new VarInsnNode(Opcodes.ALOAD, 4),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z", true), new JumpInsnNode(Opcodes.IFEQ, end),
				new VarInsnNode(Opcodes.ALOAD, 4), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true),
				new TypeInsnNode(Opcodes.CHECKCAST, ENTRY), new VarInsnNode(Opcodes.ASTORE, 5),
				new VarInsnNode(Opcodes.ALOAD, 4), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true),
				new TypeInsnNode(Opcodes.CHECKCAST, ENTRY), new VarInsnNode(Opcodes.ASTORE, 6),
				new VarInsnNode(Opcodes.ALOAD, 0), new TypeInsnNode(Opcodes.CHECKCAST, LIVING),
				new VarInsnNode(Opcodes.ALOAD, 5), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getKey", "()Ljava/lang/Object;", true),
				new TypeInsnNode(Opcodes.CHECKCAST, HOLDER),
				new VarInsnNode(Opcodes.ALOAD, 6), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getValue", "()Ljava/lang/Object;", true),
				new TypeInsnNode(Opcodes.CHECKCAST, EFFECT), new MethodInsnNode(Opcodes.INVOKESTATIC, GUARDS, "keep", KEEP, false),
				new JumpInsnNode(Opcodes.IFEQ, head), new VarInsnNode(Opcodes.ALOAD, 1),
				new VarInsnNode(Opcodes.ALOAD, 5), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getKey", "()Ljava/lang/Object;", true),
				new VarInsnNode(Opcodes.ALOAD, 5), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getValue", "()Ljava/lang/Object;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true),
				new InsnNode(Opcodes.POP), new JumpInsnNode(Opcodes.GOTO, head), end, new InsnNode(Opcodes.RETURN));
		m.maxLocals = 7;
		mixin.methods.add(m);
		assertRefused(mixin, m, "hands an effect taken once per clear to " + GUARDS.replace('/', '.') + ".keep");
	}

	/** {@code Iterator<Holder> keys = effects.keySet().iterator(); effects.values().removeIf(v -> v.getEffect() == keys.next());} */
	@Test void anIteratorAdvancedInsideTheQuestionIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/LockstepMixin");
		MethodNode m = handler("lockstep", 0);
		Handle lambda = new Handle(Opcodes.H_INVOKESTATIC, mixin.name, "lambda$lockstep$0", "(Ljava/util/Iterator;L" + EFFECT + ";)Z", false);
		add(m, new VarInsnNode(Opcodes.ALOAD, 1), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "keySet", "()Ljava/util/Set;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, SET, "iterator", "()Ljava/util/Iterator;", true), new VarInsnNode(Opcodes.ASTORE, 3),
				new VarInsnNode(Opcodes.ALOAD, 1), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "values", "()Ljava/util/Collection;", true),
				new VarInsnNode(Opcodes.ALOAD, 3),
				indy("test", "(Ljava/util/Iterator;)Ljava/util/function/Predicate;", "(Ljava/lang/Object;)Z", lambda, "(L" + EFFECT + ";)Z"),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, COLLECTION, "removeIf", "(Ljava/util/function/Predicate;)Z", true),
				new InsnNode(Opcodes.POP), new InsnNode(Opcodes.RETURN));
		m.maxLocals = 4;
		mixin.methods.add(m);
		MethodNode body = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, lambda.getName(), lambda.getDesc(), null, null);
		LabelNode differs = new LabelNode();
		add(body, new VarInsnNode(Opcodes.ALOAD, 1), new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EFFECT, "getEffect", "()L" + HOLDER + ";", false),
				new VarInsnNode(Opcodes.ALOAD, 0), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true),
				new JumpInsnNode(Opcodes.IF_ACMPNE, differs), new InsnNode(Opcodes.ICONST_1), new InsnNode(Opcodes.IRETURN),
				differs, new InsnNode(Opcodes.ICONST_0), new InsnNode(Opcodes.IRETURN));
		body.maxStack = 2;
		body.maxLocals = 2;
		mixin.methods.add(body);
		verify(mixin, body);
		assertRefused(mixin, m, "compares an effect taken once per clear");
	}

	/** {@code Iterator it = effects.values().iterator(); it.next(); it.remove();} — the first effect goes, whichever it is. */
	@Test void removingTheFirstEffectThroughItsIteratorIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/DropFirstMixin");
		MethodNode m = handler("dropFirst", 0);
		add(m, new VarInsnNode(Opcodes.ALOAD, 1), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "values", "()Ljava/util/Collection;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, COLLECTION, "iterator", "()Ljava/util/Iterator;", true), new VarInsnNode(Opcodes.ASTORE, 3),
				new VarInsnNode(Opcodes.ALOAD, 3), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true),
				new InsnNode(Opcodes.POP), new VarInsnNode(Opcodes.ALOAD, 3), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "remove", "()V", true),
				new InsnNode(Opcodes.RETURN));
		m.maxLocals = 4;
		mixin.methods.add(m);
		assertRefused(mixin, m, "removes through an iterator an effect that is not the round's");
	}

	/** Control: the removal made for each effect on the live map, with no call of the original clear. */
	@Test void removingEachLetGoEffectThroughItsIteratorIsReplayed() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/DropEachMixin");
		MethodNode m = handler("dropEach", 0);
		LabelNode head = new LabelNode(), end = new LabelNode();
		add(m, new VarInsnNode(Opcodes.ALOAD, 1), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "entrySet", "()Ljava/util/Set;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, SET, "iterator", "()Ljava/util/Iterator;", true), new VarInsnNode(Opcodes.ASTORE, 3),
				head, new VarInsnNode(Opcodes.ALOAD, 3), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z", true),
				new JumpInsnNode(Opcodes.IFEQ, end), new VarInsnNode(Opcodes.ALOAD, 3),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true), new TypeInsnNode(Opcodes.CHECKCAST, ENTRY),
				new VarInsnNode(Opcodes.ASTORE, 4));
		ask(m, 4);
		add(m, new JumpInsnNode(Opcodes.IFNE, head), new VarInsnNode(Opcodes.ALOAD, 3),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "remove", "()V", true), new JumpInsnNode(Opcodes.GOTO, head),
				end, new InsnNode(Opcodes.RETURN));
		m.maxLocals = 5;
		mixin.methods.add(m);
		verify(mixin, m);
		assertTrue(ClearVetoProof.prove(mixin, m).proved(), ClearVetoProof.prove(mixin, m).why());
		assertReplayed(mixin, "dropEach");
	}

	// ---- RED: a loop over effects left early, or told whether more follow

	/** {@code for (e : before) { if (!Guards.keep(...)) break; effects.put(...); }} — later effects depend on earlier ones. */
	@Test void aBreakOutOfTheLoopIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/UntilFirstMixin");
		MethodNode m = loop("untilFirst", Exit.BREAK);
		mixin.methods.add(m);
		assertRefused(mixin, m, "leaves a loop over effects before they run out");
	}

	/** {@code for (e : before) { if (!Guards.keep(...)) return; effects.put(...); }} */
	@Test void aReturnOutOfTheLoopIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/UntilFirstMixin");
		MethodNode m = loop("untilFirst", Exit.RETURN);
		mixin.methods.add(m);
		assertRefused(mixin, m, "leaves a loop over effects before they run out");
	}

	/** {@code for (e : before) { if (!it.hasNext()) continue; ... }} — the last effect is decided apart from the others. */
	@Test void theLastEffectTreatedApartIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/AllButLastMixin");
		MethodNode m = loop("allButLast", Exit.LAST_APART);
		mixin.methods.add(m);
		assertRefused(mixin, m, "decides on whether more effects follow other than to end their loop");
	}

	/** Control: the same loop going on to the next effect ({@code continue}) is per effect. */
	@Test void theSameLoopGoingOnIsReplayed() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/UntilFirstMixin");
		MethodNode m = loop("untilFirst", Exit.CONTINUE);
		mixin.methods.add(m);
		verify(mixin, m);
		assertTrue(ClearVetoProof.prove(mixin, m).proved(), ClearVetoProof.prove(mixin, m).why());
		assertReplayed(mixin, "untilFirst");
	}

	/** Control: a loop tested at its bottom (as ecj writes {@code while}) ends only when its effects run out, too. */
	@Test void aLoopTestedAtItsBottomIsReplayed() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/BottomTestedMixin");
		MethodNode m = handler("bottomTested", 0);
		LabelNode body = new LabelNode(), test = new LabelNode();
		add(m, new VarInsnNode(Opcodes.ALOAD, 1),
				new MethodInsnNode(Opcodes.INVOKESTATIC, MAP, "copyOf", "(Ljava/util/Map;)Ljava/util/Map;", true), new VarInsnNode(Opcodes.ASTORE, 3));
		callOriginal(m.instructions);
		add(m, new VarInsnNode(Opcodes.ALOAD, 3), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "entrySet", "()Ljava/util/Set;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, SET, "iterator", "()Ljava/util/Iterator;", true), new VarInsnNode(Opcodes.ASTORE, 4),
				new JumpInsnNode(Opcodes.GOTO, test), body, new VarInsnNode(Opcodes.ALOAD, 4),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true), new TypeInsnNode(Opcodes.CHECKCAST, ENTRY),
				new VarInsnNode(Opcodes.ASTORE, 5));
		ask(m, 5);
		add(m, new JumpInsnNode(Opcodes.IFEQ, test));
		putBack(m, 5);
		add(m, test, new VarInsnNode(Opcodes.ALOAD, 4), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z", true),
				new JumpInsnNode(Opcodes.IFNE, body), new InsnNode(Opcodes.RETURN));
		m.maxLocals = 6;
		mixin.methods.add(m);
		verify(mixin, m);
		assertTrue(ClearVetoProof.prove(mixin, m).proved(), ClearVetoProof.prove(mixin, m).why());
		assertReplayed(mixin, "bottomTested");
	}

	// ---- RED: a value carried between effects through an array

	/** {@code int[] kept = new int[1]; for (e : before) { if (kept[0] >= 2) continue; if (keep) { kept[0]++; put; } }} — at most two kept. */
	@Test void aCountKeptInAnArrayAcrossEffectsIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/AtMostTwoMixin");
		MethodNode m = handler("atMostTwo", 0);
		LabelNode head = new LabelNode(), end = new LabelNode();
		snapshotThenIterate(m);
		add(m, new InsnNode(Opcodes.ICONST_1), new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT), new VarInsnNode(Opcodes.ASTORE, 6));
		roundHead(m, head, end);
		add(m, new VarInsnNode(Opcodes.ALOAD, 6), new InsnNode(Opcodes.ICONST_0), new InsnNode(Opcodes.IALOAD), new InsnNode(Opcodes.ICONST_2),
				new JumpInsnNode(Opcodes.IF_ICMPGE, head));
		ask(m, 5);
		add(m, new JumpInsnNode(Opcodes.IFEQ, head), new VarInsnNode(Opcodes.ALOAD, 6), new InsnNode(Opcodes.ICONST_0), new VarInsnNode(Opcodes.ALOAD, 6),
				new InsnNode(Opcodes.ICONST_0), new InsnNode(Opcodes.IALOAD), new InsnNode(Opcodes.ICONST_1), new InsnNode(Opcodes.IADD),
				new InsnNode(Opcodes.IASTORE));
		putBack(m, 5);
		add(m, new JumpInsnNode(Opcodes.GOTO, head), end, new InsnNode(Opcodes.RETURN));
		m.maxLocals = 7;
		mixin.methods.add(m);
		assertRefused(mixin, m, "carries a value from one round to the next through an array");
	}

	/** {@code Object[] previous = new Object[1]; for (e : before) { if (previous[0] != null && keep) put; previous[0] = e; }} — the first effect always goes. */
	@Test void thePreviousEffectKeptInAnArrayIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/AfterFirstMixin");
		MethodNode m = handler("afterFirst", 0);
		LabelNode head = new LabelNode(), end = new LabelNode(), next = new LabelNode();
		snapshotThenIterate(m);
		add(m, new InsnNode(Opcodes.ICONST_1), new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"), new VarInsnNode(Opcodes.ASTORE, 6));
		roundHead(m, head, end);
		add(m, new VarInsnNode(Opcodes.ALOAD, 6), new InsnNode(Opcodes.ICONST_0), new InsnNode(Opcodes.AALOAD), new JumpInsnNode(Opcodes.IFNULL, next));
		ask(m, 5);
		add(m, new JumpInsnNode(Opcodes.IFEQ, next));
		putBack(m, 5);
		add(m, next, new VarInsnNode(Opcodes.ALOAD, 6), new InsnNode(Opcodes.ICONST_0), new VarInsnNode(Opcodes.ALOAD, 5), new InsnNode(Opcodes.AASTORE),
				new JumpInsnNode(Opcodes.GOTO, head), end, new InsnNode(Opcodes.RETURN));
		m.maxLocals = 7;
		mixin.methods.add(m);
		assertRefused(mixin, m, "carries a value from one round to the next through an array");
	}

	/** {@code int[] seen = new int[2]; seen[Objects.hashCode(firstKey) & 1] = 1; if (seen[0] != 0) return; original.call(effects);} */
	@Test void theFirstEffectAsAnArrayIndexIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/HashedFirstMixin");
		MethodNode m = handler("hashedFirst", 0);
		LabelNode skip = new LabelNode();
		add(m, new InsnNode(Opcodes.ICONST_2), new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT), new VarInsnNode(Opcodes.ASTORE, 3),
				new VarInsnNode(Opcodes.ALOAD, 3), new VarInsnNode(Opcodes.ALOAD, 1),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "keySet", "()Ljava/util/Set;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, SET, "iterator", "()Ljava/util/Iterator;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true),
				new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "hashCode", "(Ljava/lang/Object;)I", false),
				new InsnNode(Opcodes.ICONST_1), new InsnNode(Opcodes.IAND), new InsnNode(Opcodes.ICONST_1), new InsnNode(Opcodes.IASTORE),
				new VarInsnNode(Opcodes.ALOAD, 3), new InsnNode(Opcodes.ICONST_0), new InsnNode(Opcodes.IALOAD), new JumpInsnNode(Opcodes.IFNE, skip));
		callOriginal(m.instructions);
		add(m, skip, new InsnNode(Opcodes.RETURN));
		m.maxLocals = 4;
		mixin.methods.add(m);
		assertRefused(mixin, m, "indexes an array by an aggregate of the effects");
	}

	/** Control: an array made afresh in each round, handed to the question with that round's key and effect. */
	@Test void anArrayMadeEachRoundIsReplayed() throws Exception {
		ClassNode mixin = mixin("org/example/wards/mixin/ArgsEachMixin");
		MethodNode m = handler("argsEach", 0);
		LabelNode head = new LabelNode(), end = new LabelNode();
		snapshotThenIterate(m);
		roundHead(m, head, end);
		add(m, new InsnNode(Opcodes.ICONST_2), new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"), new VarInsnNode(Opcodes.ASTORE, 6),
				new VarInsnNode(Opcodes.ALOAD, 6), new InsnNode(Opcodes.ICONST_0), new VarInsnNode(Opcodes.ALOAD, 5),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getKey", "()Ljava/lang/Object;", true), new InsnNode(Opcodes.AASTORE),
				new VarInsnNode(Opcodes.ALOAD, 6), new InsnNode(Opcodes.ICONST_1), new VarInsnNode(Opcodes.ALOAD, 5),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getValue", "()Ljava/lang/Object;", true), new InsnNode(Opcodes.AASTORE),
				new VarInsnNode(Opcodes.ALOAD, 0), new TypeInsnNode(Opcodes.CHECKCAST, LIVING), new VarInsnNode(Opcodes.ALOAD, 6),
				new MethodInsnNode(Opcodes.INVOKESTATIC, GUARDS, "keepAll", "(L" + LIVING + ";[Ljava/lang/Object;)Z", false),
				new JumpInsnNode(Opcodes.IFEQ, head));
		putBack(m, 5);
		add(m, new JumpInsnNode(Opcodes.GOTO, head), end, new InsnNode(Opcodes.RETURN));
		m.maxLocals = 7;
		mixin.methods.add(m);
		verify(mixin, m);
		assertTrue(ClearVetoProof.prove(mixin, m).proved(), ClearVetoProof.prove(mixin, m).why());
		assertReplayed(mixin, "argsEach");
	}

	// ---- shapes

	enum Exit { CONTINUE, BREAK, RETURN, LAST_APART }

	/** {@code before = Set.copyOf(effects.entrySet()); original.call(effects); it = before.iterator();} (slots 3 and 4). */
	private static void snapshotThenIterate(MethodNode m) {
		add(m, new VarInsnNode(Opcodes.ALOAD, 1), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "entrySet", "()Ljava/util/Set;", true),
				new MethodInsnNode(Opcodes.INVOKESTATIC, SET, "copyOf", "(Ljava/util/Collection;)Ljava/util/Set;", true), new VarInsnNode(Opcodes.ASTORE, 3));
		callOriginal(m.instructions);
		add(m, new VarInsnNode(Opcodes.ALOAD, 3), new MethodInsnNode(Opcodes.INVOKEINTERFACE, SET, "iterator", "()Ljava/util/Iterator;", true),
				new VarInsnNode(Opcodes.ASTORE, 4));
	}

	/** {@code head: if (!it.hasNext()) goto end; e = (Entry) it.next();} (entry in slot 5). */
	private static void roundHead(MethodNode m, LabelNode head, LabelNode end) {
		add(m, head, new VarInsnNode(Opcodes.ALOAD, 4), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z", true),
				new JumpInsnNode(Opcodes.IFEQ, end), new VarInsnNode(Opcodes.ALOAD, 4),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true), new TypeInsnNode(Opcodes.CHECKCAST, ENTRY),
				new VarInsnNode(Opcodes.ASTORE, 5));
	}

	/**
	 * {@code before = Set.copyOf(effects.entrySet()); original.call(effects); it = before.iterator(); while (it.hasNext())
	 * { e = it.next(); [LAST_APART: if (!it.hasNext()) continue;] if (!Guards.keep(self, k, v)) continue|break|return;
	 * effects.put(k, v); }}
	 */
	private static MethodNode loop(String name, Exit exit) {
		MethodNode m = handler(name, 0);
		LabelNode head = new LabelNode(), end = new LabelNode(), kept = new LabelNode();
		add(m, new VarInsnNode(Opcodes.ALOAD, 1), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "entrySet", "()Ljava/util/Set;", true),
				new MethodInsnNode(Opcodes.INVOKESTATIC, SET, "copyOf", "(Ljava/util/Collection;)Ljava/util/Set;", true), new VarInsnNode(Opcodes.ASTORE, 3));
		callOriginal(m.instructions);
		add(m, new VarInsnNode(Opcodes.ALOAD, 3), new MethodInsnNode(Opcodes.INVOKEINTERFACE, SET, "iterator", "()Ljava/util/Iterator;", true),
				new VarInsnNode(Opcodes.ASTORE, 4), head, new VarInsnNode(Opcodes.ALOAD, 4),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z", true), new JumpInsnNode(Opcodes.IFEQ, end),
				new VarInsnNode(Opcodes.ALOAD, 4), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true),
				new TypeInsnNode(Opcodes.CHECKCAST, ENTRY), new VarInsnNode(Opcodes.ASTORE, 5));
		if (exit == Exit.LAST_APART) add(m, new VarInsnNode(Opcodes.ALOAD, 4),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z", true), new JumpInsnNode(Opcodes.IFEQ, head));
		ask(m, 5);
		switch (exit) {
			case CONTINUE, LAST_APART -> add(m, new JumpInsnNode(Opcodes.IFEQ, head));
			case BREAK -> add(m, new JumpInsnNode(Opcodes.IFEQ, end));
			case RETURN -> add(m, new JumpInsnNode(Opcodes.IFNE, kept), new InsnNode(Opcodes.RETURN), kept);
		}
		putBack(m, 5);
		add(m, new JumpInsnNode(Opcodes.GOTO, head), end, new InsnNode(Opcodes.RETURN));
		m.maxLocals = 6;
		return m;
	}

	/** {@code Guards.keep((LivingEntity) this, (Holder) e.getKey(), (MobEffectInstance) e.getValue())} on the entry in {@code slot}. */
	private static void ask(MethodNode m, int slot) {
		add(m, new VarInsnNode(Opcodes.ALOAD, 0), new TypeInsnNode(Opcodes.CHECKCAST, LIVING),
				new VarInsnNode(Opcodes.ALOAD, slot), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getKey", "()Ljava/lang/Object;", true),
				new TypeInsnNode(Opcodes.CHECKCAST, HOLDER),
				new VarInsnNode(Opcodes.ALOAD, slot), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getValue", "()Ljava/lang/Object;", true),
				new TypeInsnNode(Opcodes.CHECKCAST, EFFECT), new MethodInsnNode(Opcodes.INVOKESTATIC, GUARDS, "keep", KEEP, false));
	}

	/** {@code effects.put(e.getKey(), e.getValue())} for the entry in {@code slot}. */
	private static void putBack(MethodNode m, int slot) {
		add(m, new VarInsnNode(Opcodes.ALOAD, 1),
				new VarInsnNode(Opcodes.ALOAD, slot), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getKey", "()Ljava/lang/Object;", true),
				new VarInsnNode(Opcodes.ALOAD, slot), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getValue", "()Ljava/lang/Object;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true),
				new InsnNode(Opcodes.POP));
	}

	// ---- assertions

	/**
	 * Refused, for {@code reason} when given (else for any reason but unreadable bytecode), and left as written. The
	 * fixture verifies, so it is refused for what it does, not for how it was assembled.
	 */
	private static void assertRefused(ClassNode mixin, MethodNode handler, String reason) throws Exception {
		verify(mixin, handler);
		ClearVetoProof.Verdict verdict = ClearVetoProof.prove(mixin, handler);
		assertFalse(verdict.proved(), handler.name + " was proved a per-effect veto");
		assertFalse(verdict.why().startsWith("unreadable"), verdict.why());
		if (reason != null) assertTrue(verdict.why().startsWith(reason), "refused for: " + verdict.why());
		assertLeftAlone(mixin, handler.name);
	}

	private static void verify(ClassNode mixin, MethodNode m) throws Exception {
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, m);
	}
}
