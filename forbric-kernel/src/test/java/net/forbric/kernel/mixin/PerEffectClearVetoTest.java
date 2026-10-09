package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * Clear-all effect vetoes written in ways balm does not write them are proved by what they do and re-hosted on NeoForge's
 * per-effect question; look-alikes that are not per-effect vetoes are left as written. None of the fixtures shares a
 * name with balm, and none is balm's instruction sequence: a loop over a snapshot, a {@code removeIf} with no call of the
 * original clear, kotlinc's shape (null checks, a {@code LinkedHashMap} destination, the argument array kept in a local,
 * two captured host maps), and balm's stream with one more captured value.
 */
@ResourceLock("system-properties")
class PerEffectClearVetoTest {
	static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	static final String HOLDER = "net/minecraft/core/Holder";
	static final String EFFECT = "net/minecraft/world/effect/MobEffectInstance";
	static final String OP = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	static final String GUARDS = "org/example/effects/Guards";
	static final String KEEP = "(L" + LIVING + ";L" + HOLDER + ";L" + EFFECT + ";)Z";
	static final String MAP = "java/util/Map", ENTRY = "java/util/Map$Entry", ITERATOR = "java/util/Iterator";

	@AfterEach void reset() {
		System.clearProperty(FabricEntityMixinAnchors.CLEAR_VETO_PROPERTY);
	}

	@Test void aLoopOverASnapshotIsReplayedPerEffect() throws Exception {
		ClassNode mixin = mixin("org/example/effects/mixin/SnapshotVetoMixin");
		mixin.methods.add(snapshotLoop("keepGuarded", false, false));
		assertReplayed(mixin, "keepGuarded");
	}

	@Test void aRemoveIfThatNeverCallsTheOriginalIsReplayedPerEffect() throws Exception {
		ClassNode mixin = mixin("org/example/effects/mixin/PruneVetoMixin");
		addRemoveIf(mixin);
		assertReplayed(mixin, "pruneEffects");
	}

	@Test void kotlincsShapeWithTwoCapturedHostMapsIsReplayedPerEffect() throws Exception {
		ClassNode mixin = mixin("org/example/effects/mixin/KotlinVetoMixin");
		mixin.methods.add(kotlinShaped());
		MethodNode generated = assertReplayed(mixin, "vetoClear");
		long copies = Arrays.stream(generated.instructions.toArray()).filter(i -> i instanceof MethodInsnNode c && c.name.equals("<init>")
				&& c.desc.equals("(Ljava/util/Map;)V")).count();
		assertEquals(2, copies, "each captured host map gets its own copy of the one-effect map");
	}

	@Test void aStreamFilterCapturingMoreThanTheEntityIsReplayed() throws Exception {
		ClassNode mixin = mixin("org/example/effects/mixin/StrictVetoMixin");
		addStreamWithExtraCapture(mixin);
		MethodNode generated = assertReplayed(mixin, "strictClear");
		assertTrue(Arrays.stream(generated.instructions.toArray()).noneMatch(i -> i instanceof MethodInsnNode c && c.name.startsWith("lambda$")),
				"the predicate's extra capture is recomputed by running the handler, not guessed");
	}

	/** RED: one question for the whole clear (no effect in it) is not a per-effect veto. */
	@Test void aWholeClearVetoIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/effects/mixin/AllOrNothingMixin");
		MethodNode m = handler("allOrNothing", 0);
		LabelNode go = new LabelNode();
		add(m, new VarInsnNode(Opcodes.ALOAD, 0), new TypeInsnNode(Opcodes.CHECKCAST, LIVING),
				new MethodInsnNode(Opcodes.INVOKESTATIC, GUARDS, "allowClear", "(L" + LIVING + ";)Z", false),
				new JumpInsnNode(Opcodes.IFNE, go), new InsnNode(Opcodes.RETURN), go);
		callOriginal(m.instructions);
		add(m, new InsnNode(Opcodes.RETURN));
		mixin.methods.add(m);
		assertLeftAlone(mixin, "allOrNothing");
	}

	/** RED: a count carried from one effect to the next decides differently for each effect alone. */
	@Test void aCountAcrossEffectsIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/effects/mixin/SnapshotVetoMixin");
		mixin.methods.add(snapshotLoop("keepGuarded", true, false));
		assertLeftAlone(mixin, "keepGuarded");
	}

	/** RED: how many effects there are reaches a decision. */
	@Test void anAggregateDecisionIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/effects/mixin/AtMostMixin");
		MethodNode m = handler("atMost", 0);
		LabelNode go = new LabelNode();
		add(m, new VarInsnNode(Opcodes.ALOAD, 1), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "size", "()I", true),
				new InsnNode(Opcodes.ICONST_3), new JumpInsnNode(Opcodes.IF_ICMPLE, go), new InsnNode(Opcodes.RETURN), go);
		callOriginal(m.instructions);
		add(m, new InsnNode(Opcodes.RETURN));
		mixin.methods.add(m);
		assertLeftAlone(mixin, "atMost");
	}

	/** RED: what goes back into the effect map is not one of its own entries. */
	@Test void puttingBackSomethingElseIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/effects/mixin/SnapshotVetoMixin");
		mixin.methods.add(snapshotLoop("keepGuarded", false, true));
		assertLeftAlone(mixin, "keepGuarded");
	}

	/** RED: the whole map handed to an unknown call can be read whole. */
	@Test void theWholeMapHandedToAnApiIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/effects/mixin/DelegateMixin");
		MethodNode m = handler("delegate", 0);
		add(m, new VarInsnNode(Opcodes.ALOAD, 0), new TypeInsnNode(Opcodes.CHECKCAST, LIVING), new VarInsnNode(Opcodes.ALOAD, 1),
				new MethodInsnNode(Opcodes.INVOKESTATIC, GUARDS, "decide", "(L" + LIVING + ";Ljava/util/Map;)V", false));
		callOriginal(m.instructions);
		add(m, new InsnNode(Opcodes.RETURN));
		mixin.methods.add(m);
		assertLeftAlone(mixin, "delegate");
	}

	/** RED: an unknown call in the bookkeeping over the decided effects happens per kept effect, not per question. */
	@Test void anUnknownCallOverTheDecidedEffectsIsLeftAlone() throws Exception {
		ClassNode mixin = mixin("org/example/effects/mixin/KotlinVetoMixin");
		MethodNode m = kotlinShaped();
		// After putAll: for (e : destination.entrySet()) Log.kept();
		AbstractInsnNode ret = m.instructions.getLast();
		LabelNode head = new LabelNode(), end = new LabelNode();
		InsnList loop = new InsnList();
		for (AbstractInsnNode i : new AbstractInsnNode[] {new VarInsnNode(Opcodes.ALOAD, 5),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "entrySet", "()Ljava/util/Set;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;", true),
				new VarInsnNode(Opcodes.ASTORE, 11), head, new VarInsnNode(Opcodes.ALOAD, 11),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z", true), new JumpInsnNode(Opcodes.IFEQ, end),
				new VarInsnNode(Opcodes.ALOAD, 11), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true),
				new InsnNode(Opcodes.POP), new MethodInsnNode(Opcodes.INVOKESTATIC, "org/example/effects/Log", "kept", "()V", false),
				new JumpInsnNode(Opcodes.GOTO, head), end}) loop.add(i);
		m.instructions.insertBefore(ret, loop);
		m.maxLocals = 12;
		mixin.methods.add(m);
		assertLeftAlone(mixin, "vetoClear");
	}

	/** The switch leaves a proved veto as written. */
	@Test void theSwitchLeavesAProvedVetoAlone() throws Exception {
		System.setProperty(FabricEntityMixinAnchors.CLEAR_VETO_PROPERTY, "off");
		ClassNode mixin = mixin("org/example/effects/mixin/SnapshotVetoMixin");
		mixin.methods.add(snapshotLoop("keepGuarded", false, false));
		assertLeftAlone(mixin, "keepGuarded");
	}

	// ---- shapes

	/**
	 * {@code snapshot = Set.copyOf(map.entrySet()); original.call(map); for (e : snapshot) if (Guards.keep(...)) map.put(k, v);}
	 * — counting adds a round-to-round counter; foreign puts a null value back.
	 */
	static MethodNode snapshotLoop(String name, boolean counting, boolean foreign) {
		MethodNode m = handler(name, 0);
		LabelNode head = new LabelNode(), end = new LabelNode();
		add(m, new VarInsnNode(Opcodes.ALOAD, 1), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "entrySet", "()Ljava/util/Set;", true),
				new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Set", "copyOf", "(Ljava/util/Collection;)Ljava/util/Set;", true),
				new VarInsnNode(Opcodes.ASTORE, 3));
		callOriginal(m.instructions);
		if (counting) add(m, new InsnNode(Opcodes.ICONST_0), new VarInsnNode(Opcodes.ISTORE, 6));
		add(m, new VarInsnNode(Opcodes.ALOAD, 3), new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;", true),
				new VarInsnNode(Opcodes.ASTORE, 4), head, new VarInsnNode(Opcodes.ALOAD, 4),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z", true), new JumpInsnNode(Opcodes.IFEQ, end),
				new VarInsnNode(Opcodes.ALOAD, 4), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true),
				new TypeInsnNode(Opcodes.CHECKCAST, ENTRY), new VarInsnNode(Opcodes.ASTORE, 5));
		if (counting) add(m, new IincInsnNode(6, 1), new VarInsnNode(Opcodes.ILOAD, 6), new InsnNode(Opcodes.ICONST_2),
				new JumpInsnNode(Opcodes.IF_ICMPGT, head));
		add(m, new VarInsnNode(Opcodes.ALOAD, 0), new TypeInsnNode(Opcodes.CHECKCAST, LIVING), new VarInsnNode(Opcodes.ALOAD, 5),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getKey", "()Ljava/lang/Object;", true), new TypeInsnNode(Opcodes.CHECKCAST, HOLDER),
				new VarInsnNode(Opcodes.ALOAD, 5), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getValue", "()Ljava/lang/Object;", true),
				new TypeInsnNode(Opcodes.CHECKCAST, EFFECT), new MethodInsnNode(Opcodes.INVOKESTATIC, GUARDS, "keep", KEEP, false),
				new JumpInsnNode(Opcodes.IFEQ, head), new VarInsnNode(Opcodes.ALOAD, 1), new VarInsnNode(Opcodes.ALOAD, 5),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getKey", "()Ljava/lang/Object;", true));
		if (foreign) add(m, new InsnNode(Opcodes.ACONST_NULL));
		else add(m, new VarInsnNode(Opcodes.ALOAD, 5), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getValue", "()Ljava/lang/Object;", true));
		add(m, new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true),
				new InsnNode(Opcodes.POP), new JumpInsnNode(Opcodes.GOTO, head), end, new InsnNode(Opcodes.RETURN));
		m.maxLocals = 7;
		return m;
	}

	/** {@code entity = (LivingEntity) this; map.values().removeIf(e -> !Guards.keep(entity, e.getEffect(), e));} */
	static void addRemoveIf(ClassNode mixin) {
		MethodNode m = handler("pruneEffects", 0);
		Handle lambda = new Handle(Opcodes.H_INVOKESTATIC, mixin.name, "lambda$pruneEffects$0", "(L" + LIVING + ";L" + EFFECT + ";)Z", false);
		add(m, new VarInsnNode(Opcodes.ALOAD, 0), new TypeInsnNode(Opcodes.CHECKCAST, LIVING), new VarInsnNode(Opcodes.ASTORE, 3),
				new VarInsnNode(Opcodes.ALOAD, 1), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "values", "()Ljava/util/Collection;", true),
				new VarInsnNode(Opcodes.ALOAD, 3), indy("test", "(L" + LIVING + ";)Ljava/util/function/Predicate;", "(Ljava/lang/Object;)Z", lambda, "(L" + EFFECT + ";)Z"),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Collection", "removeIf", "(Ljava/util/function/Predicate;)Z", true),
				new InsnNode(Opcodes.POP), new InsnNode(Opcodes.RETURN));
		m.maxLocals = 4;
		mixin.methods.add(m);
		MethodNode body = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, lambda.getName(), lambda.getDesc(), null, null);
		LabelNode no = new LabelNode();
		add(body, new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ALOAD, 1),
				new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EFFECT, "getEffect", "()L" + HOLDER + ";", false), new VarInsnNode(Opcodes.ALOAD, 1),
				new MethodInsnNode(Opcodes.INVOKESTATIC, GUARDS, "keep", KEEP, false), new JumpInsnNode(Opcodes.IFNE, no),
				new InsnNode(Opcodes.ICONST_1), new InsnNode(Opcodes.IRETURN), no, new InsnNode(Opcodes.ICONST_0), new InsnNode(Opcodes.IRETURN));
		body.maxStack = 4;
		body.maxLocals = 2;
		mixin.methods.add(body);
	}

	/**
	 * kotlinc's {@code val kept = effects.filter { (k, v) -> Guards.keep(entity, k, v) }; original.call(effects);
	 * effects.putAll(kept); copy.keys.removeAll(kept.keys)} with two captured host maps.
	 */
	static MethodNode kotlinShaped() {
		MethodNode m = handler("vetoClear", 2);
		LabelNode head = new LabelNode(), end = new LabelNode();
		add(m, new VarInsnNode(Opcodes.ALOAD, 1), new LdcInsnNode("effects"),
				new MethodInsnNode(Opcodes.INVOKESTATIC, "kotlin/jvm/internal/Intrinsics", "checkNotNullParameter", "(Ljava/lang/Object;Ljava/lang/String;)V", false),
				new VarInsnNode(Opcodes.ALOAD, 2), new LdcInsnNode("original"),
				new MethodInsnNode(Opcodes.INVOKESTATIC, "kotlin/jvm/internal/Intrinsics", "checkNotNullParameter", "(Ljava/lang/Object;Ljava/lang/String;)V", false),
				new TypeInsnNode(Opcodes.NEW, "java/util/LinkedHashMap"), new InsnNode(Opcodes.DUP),
				new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/LinkedHashMap", "<init>", "()V", false), new TypeInsnNode(Opcodes.CHECKCAST, MAP),
				new VarInsnNode(Opcodes.ASTORE, 5), new VarInsnNode(Opcodes.ALOAD, 1),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "entrySet", "()Ljava/util/Set;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;", true), new VarInsnNode(Opcodes.ASTORE, 6),
				head, new VarInsnNode(Opcodes.ALOAD, 6), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z", true),
				new JumpInsnNode(Opcodes.IFEQ, end), new VarInsnNode(Opcodes.ALOAD, 6),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true), new TypeInsnNode(Opcodes.CHECKCAST, ENTRY),
				new VarInsnNode(Opcodes.ASTORE, 7), new VarInsnNode(Opcodes.ALOAD, 7),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getKey", "()Ljava/lang/Object;", true), new TypeInsnNode(Opcodes.CHECKCAST, HOLDER),
				new VarInsnNode(Opcodes.ASTORE, 8), new VarInsnNode(Opcodes.ALOAD, 7),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getValue", "()Ljava/lang/Object;", true), new TypeInsnNode(Opcodes.CHECKCAST, EFFECT),
				new VarInsnNode(Opcodes.ASTORE, 9), new VarInsnNode(Opcodes.ALOAD, 0), new TypeInsnNode(Opcodes.CHECKCAST, LIVING),
				new VarInsnNode(Opcodes.ALOAD, 8), new VarInsnNode(Opcodes.ALOAD, 9), new MethodInsnNode(Opcodes.INVOKESTATIC, GUARDS, "keep", KEEP, false),
				new JumpInsnNode(Opcodes.IFEQ, head), new VarInsnNode(Opcodes.ALOAD, 5), new VarInsnNode(Opcodes.ALOAD, 7),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getKey", "()Ljava/lang/Object;", true), new VarInsnNode(Opcodes.ALOAD, 7),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getValue", "()Ljava/lang/Object;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true),
				new InsnNode(Opcodes.POP), new JumpInsnNode(Opcodes.GOTO, head), end,
				// the argument array kept in a local
				new VarInsnNode(Opcodes.ALOAD, 2), new InsnNode(Opcodes.ICONST_1), new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"),
				new VarInsnNode(Opcodes.ASTORE, 10), new VarInsnNode(Opcodes.ALOAD, 10), new InsnNode(Opcodes.ICONST_0), new VarInsnNode(Opcodes.ALOAD, 1),
				new InsnNode(Opcodes.AASTORE), new VarInsnNode(Opcodes.ALOAD, 10),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, OP, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true), new InsnNode(Opcodes.POP),
				new VarInsnNode(Opcodes.ALOAD, 1), new VarInsnNode(Opcodes.ALOAD, 5), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "putAll", "(Ljava/util/Map;)V", true),
				new VarInsnNode(Opcodes.ALOAD, 3), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "keySet", "()Ljava/util/Set;", true),
				new VarInsnNode(Opcodes.ALOAD, 5), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "keySet", "()Ljava/util/Set;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "removeAll", "(Ljava/util/Collection;)Z", true), new InsnNode(Opcodes.POP),
				new InsnNode(Opcodes.RETURN));
		m.maxLocals = 11;
		return m;
	}

	/** balm's stream, its predicate capturing the entity and a flag read once: {@code strict = Guards.STRICT}. */
	static void addStreamWithExtraCapture(ClassNode mixin) {
		MethodNode m = handler("strictClear", 1);
		Handle lambda = new Handle(Opcodes.H_INVOKESTATIC, mixin.name, "lambda$strictClear$0", "(L" + LIVING + ";ZLjava/util/Map$Entry;)Z", false);
		Handle key = new Handle(Opcodes.H_INVOKEINTERFACE, ENTRY, "getKey", "()Ljava/lang/Object;", true);
		Handle value = new Handle(Opcodes.H_INVOKEINTERFACE, ENTRY, "getValue", "()Ljava/lang/Object;", true);
		add(m, new VarInsnNode(Opcodes.ALOAD, 0), new TypeInsnNode(Opcodes.CHECKCAST, LIVING), new VarInsnNode(Opcodes.ASTORE, 4),
				new FieldInsnNode(Opcodes.GETSTATIC, GUARDS, "STRICT", "Z"), new VarInsnNode(Opcodes.ISTORE, 5),
				new VarInsnNode(Opcodes.ALOAD, 1), new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "entrySet", "()Ljava/util/Set;", true),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "stream", "()Ljava/util/stream/Stream;", true),
				new VarInsnNode(Opcodes.ALOAD, 4), new VarInsnNode(Opcodes.ILOAD, 5),
				indy("test", "(L" + LIVING + ";Z)Ljava/util/function/Predicate;", "(Ljava/lang/Object;)Z", lambda, "(Ljava/util/Map$Entry;)Z"),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream", "filter", "(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;", true),
				indy("apply", "()Ljava/util/function/Function;", "(Ljava/lang/Object;)Ljava/lang/Object;", key, "(Ljava/util/Map$Entry;)Ljava/lang/Object;"),
				indy("apply", "()Ljava/util/function/Function;", "(Ljava/lang/Object;)Ljava/lang/Object;", value, "(Ljava/util/Map$Entry;)Ljava/lang/Object;"),
				new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/stream/Collectors", "toMap",
						"(Ljava/util/function/Function;Ljava/util/function/Function;)Ljava/util/stream/Collector;", false),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream", "collect", "(Ljava/util/stream/Collector;)Ljava/lang/Object;", true),
				new TypeInsnNode(Opcodes.CHECKCAST, MAP), new VarInsnNode(Opcodes.ASTORE, 6));
		callOriginal(m.instructions);
		add(m, new VarInsnNode(Opcodes.ALOAD, 1), new VarInsnNode(Opcodes.ALOAD, 6),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "putAll", "(Ljava/util/Map;)V", true), new InsnNode(Opcodes.RETURN));
		m.maxLocals = 7;
		mixin.methods.add(m);
		MethodNode body = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, lambda.getName(), lambda.getDesc(), null, null);
		add(body, new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ALOAD, 2),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getKey", "()Ljava/lang/Object;", true), new TypeInsnNode(Opcodes.CHECKCAST, HOLDER),
				new VarInsnNode(Opcodes.ALOAD, 2), new MethodInsnNode(Opcodes.INVOKEINTERFACE, ENTRY, "getValue", "()Ljava/lang/Object;", true),
				new TypeInsnNode(Opcodes.CHECKCAST, EFFECT), new MethodInsnNode(Opcodes.INVOKESTATIC, GUARDS, "keep", KEEP, false),
				new VarInsnNode(Opcodes.ILOAD, 1), new InsnNode(Opcodes.IOR), new InsnNode(Opcodes.IRETURN));
		body.maxStack = 4;
		body.maxLocals = 3;
		mixin.methods.add(body);
	}

	// ---- assertions and helpers

	static MethodNode assertReplayed(ClassNode mixin, String name) throws Exception {
		ClassNode living = StagedFabricMixinFixture.living(false);
		assertEquals(1, FabricEntityMixinAnchors.adapt(mixin, n -> living), name + " is a per-effect veto");
		MethodNode original = StagedFabricMixinFixture.method(mixin, name);
		assertNull(MixinFit.injectorOf(original), "the dead clear() wrap is gone");
		assertTrue(Arrays.stream(original.instructions.toArray()).noneMatch(i -> i instanceof MethodInsnNode c && c.owner.equals(OP)),
				"the original operation became a clear of the map the handler is handed");
		MethodNode generated = StagedFabricMixinFixture.method(mixin, "forbric$clearVeto$" + name);
		assertEquals("(L" + LIVING + ";L" + EFFECT + ";L" + OP + ";)Z", generated.desc);
		assertEquals("Lnet/neoforged/neoforge/event/EventHooks;onEffectRemoved(L" + LIVING + ";L" + EFFECT + ";)Z",
				MixinFit.value(StagedFabricMixinFixture.at(mixin, "forbric$clearVeto$" + name), "target"));
		assertTrue(Arrays.stream(generated.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.name.equals(name)),
				"the guest's own handler is run");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, generated);
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, original);
		assertEquals(0, FabricEntityMixinAnchors.adapt(mixin, n -> living), "adapted once");
		return generated;
	}

	static void assertLeftAlone(ClassNode mixin, String name) throws Exception {
		ClassNode living = StagedFabricMixinFixture.living(false);
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, FabricEntityMixinAnchors.adapt(mixin, n -> living));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
		assertNotNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin, name)));
	}

	static ClassNode mixin(String name) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
		mixin.name = name;
		mixin.superName = "java/lang/Object";
		AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		target.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(LIVING)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(target));
		return mixin;
	}

	/** A {@code void name(Map effects, Operation original, Map... captured)} @WrapOperation of activeEffects.clear(). */
	static MethodNode handler(String name, int captured) {
		StringBuilder desc = new StringBuilder("(Ljava/util/Map;L" + OP + ";");
		for (int i = 0; i < captured; i++) desc.append("Ljava/util/Map;");
		MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE, name, desc.append(")V").toString(), null, null);
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", "Ljava/util/Map;clear()V"));
		AnnotationNode wrap = new AnnotationNode("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
		wrap.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("removeAllEffects()Z")), "at", new ArrayList<>(List.of(at))));
		m.visibleAnnotations = new ArrayList<>(List.of(wrap));
		m.maxStack = 6;
		m.maxLocals = 3 + captured;
		return m;
	}

	static void callOriginal(InsnList code) {
		for (AbstractInsnNode i : new AbstractInsnNode[] {new VarInsnNode(Opcodes.ALOAD, 2), new InsnNode(Opcodes.ICONST_1),
				new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"), new InsnNode(Opcodes.DUP), new InsnNode(Opcodes.ICONST_0),
				new VarInsnNode(Opcodes.ALOAD, 1), new InsnNode(Opcodes.AASTORE),
				new MethodInsnNode(Opcodes.INVOKEINTERFACE, OP, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true), new InsnNode(Opcodes.POP)})
			code.add(i);
	}

	static InvokeDynamicInsnNode indy(String name, String desc, String erased, Handle impl, String instantiated) {
		return new InvokeDynamicInsnNode(name, desc, new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
						+ "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
				Type.getMethodType(erased), impl, Type.getMethodType(instantiated));
	}

	static void add(MethodNode m, AbstractInsnNode... insns) {
		for (AbstractInsnNode i : insns) m.instructions.add(i);
	}
}
