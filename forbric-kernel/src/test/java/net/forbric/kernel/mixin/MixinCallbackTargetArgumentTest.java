/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import static net.forbric.kernel.mixin.CallbackSourceFixture.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Callback adapters serving a handler that takes its target's arguments the way Mixin appends them — unannotated, after
 * the injector's own operands — exactly as they serve the {@code @Local(argsOnly = true)} it stands for; and refusing
 * one that only looks like it. Written by mods that are not the adapters' samples: a catacomb mod on
 * {@code StructureTemplate.placeEntities}, a sundial mod on the HUD's contextual bar.
 */
class MixinCallbackTargetArgumentTest {
	private static final String ST = MixinStructurePlacementAdapter.TARGET, OLD = MixinStructurePlacementAdapter.OLD, LIVE = MixinStructurePlacementAdapter.LIVE;
	private static final String ITERATOR = "Ljava/util/List;iterator()Ljava/util/Iterator;", LEVEL = "Lnet/minecraft/world/level/ServerLevelAccessor;";
	private static final String MIRROR = "Lnet/minecraft/world/level/block/Mirror;", POS = "Lnet/minecraft/core/BlockPos;";
	private static final BiFunction<Ecosystem, String, ClassNode> VANILLA = (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name);
	private static final BiFunction<Ecosystem, String, ClassNode> NONE = (family, name) -> null;

	// ---- placeEntities --------------------------------------------------------------------------------------------

	private static int structure(ClassNode mixin, BiFunction<Ecosystem, String, ClassNode> references) {
		return MixinStructurePlacementAdapter.adapt(mixin, CarpetMixinAdapterTest::target, references);
	}

	/** {@code return (Iterator) original.call(list);} */
	private static final Consumer<MethodVisitor> PASS_ITERATOR = code -> {
		code.visitVarInsn(Opcodes.ALOAD, 2);
		array(code, 1);
		code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true);
		code.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Iterator");
		code.visitInsn(Opcodes.ARETURN);
	};

	/** A catacomb mod's wrap of the entity list's iterator, taking {@code appended} after the Operation, unannotated. */
	private static ClassNode crypt(String selector, String appended, String target) {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/catacomb/mixin/CryptMixin", ST);
		source.handler("hauntLevel", "(Ljava/util/List;" + OPERATION + appended + ")Ljava/util/Iterator;", WRAP, "method", List.of(selector),
				"at", List.of(at("INVOKE", target))).code(PASS_ITERATOR);
		return source.build();
	}

	/** The slot the handler's parameter {@code parameter} reads in addEntitiesToWorld at the iterator call, as MixinExtras reads its @Local. */
	private static int readsIn(ClassNode mixin, String handler, int parameter) {
		MethodNode method = MixinPlayerWorldCallbackAdapter.named(mixin, handler);
		AnnotationNode local = MixinStubRebind.sugar(method, parameter, MixinRetarget.LOCAL_SUGAR);
		assertNotNull(local, "the appended value was given the @Local it stood for");
		MethodNode live = MixinPlayerWorldCallbackAdapter.selector(CarpetMixinAdapterTest.target(ST), LIVE);
		List<AbstractInsnNode> point = MixinCallbackProofs.points(live, MixinFit.atNodes(MixinFit.injectorOf(method)).getFirst());
		assertEquals(1, point.size());
		return MixinLocalOriginProof.slot(local, Type.getArgumentTypes(method.desc)[parameter], live, live.instructions.indexOf(point.getFirst()));
	}

	@Test void aWrapAppendingThePlacementLevelMovesReadingTheLevelAsItsArgsOnlyLocalDoes() throws Exception {
		for (var references : List.of(VANILLA, NONE)) {
			// Without the class the mod was compiled against only a described selector names placeEntities.
			String selector = references == VANILLA ? "placeEntities" : OLD;
			ClassNode appended = crypt(selector, LEVEL, ITERATOR);
			assertEquals(1, structure(appended, references), selector);
			assertEquals(List.of(LIVE), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(MixinPlayerWorldCallbackAdapter.named(appended, "hauntLevel")), "method")));
			CarpetMixinAdapterTest.verify(appended);
			assertEquals(1, readsIn(appended, "hauntLevel", 2), "addEntitiesToWorld's own level, not whatever it appends first");
			assertEquals(0, structure(appended, references), "idempotence");

			CallbackSourceFixture annotated = new CallbackSourceFixture("org/example/catacomb/mixin/CryptMixin", ST);
			annotated.handler("hauntLevel", "(Ljava/util/List;" + OPERATION + LEVEL + ")Ljava/util/Iterator;", WRAP, "method", List.of(selector),
					"at", List.of(at("INVOKE", ITERATOR))).local(2, "argsOnly", true).code(PASS_ITERATOR);
			ClassNode local = annotated.build();
			assertEquals(1, structure(local, references));
			assertEquals(readsIn(local, "hauntLevel", 2), readsIn(appended, "hauntLevel", 2), "the same value as the @Local(argsOnly = true) form");
		}
	}

	@Test void aRedirectAppendingTheLevelAfterItsReceiverMovesWithTheLevelPinned() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/catacomb/mixin/OssuaryMixin", ST);
		// The receiver is the redirected call's own operand; the level after it is placeEntities' first argument.
		source.handler("countBones", "(Ljava/util/List;" + LEVEL + ")Ljava/util/Iterator;", "Lorg/spongepowered/asm/mixin/injection/Redirect;",
				"method", List.of("placeEntities"), "at", List.of(at("INVOKE", ITERATOR))).code(code -> {
			code.visitVarInsn(Opcodes.ALOAD, 1);
			code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
			code.visitInsn(Opcodes.ARETURN);
		});
		ClassNode mixin = source.build();
		assertEquals(1, structure(mixin, VANILLA));
		CarpetMixinAdapterTest.verify(mixin);
		assertEquals(1, readsIn(mixin, "countBones", 1));
	}

	@Test void anAppendedValueTheMergedMethodCannotHandOverOrThatIsNoArgumentStays() throws Exception {
		for (var references : List.of(VANILLA, NONE)) {
			String selector = references == VANILLA ? "placeEntities" : OLD;
			// placeEntities' third argument is its Mirror; addEntitiesToWorld takes settings there, and no Mirror at all.
			assertUntouched(crypt(selector, LEVEL + POS + MIRROR, ITERATOR), references);
			// placeEntities' first argument is the level, not a position: Mixin refuses this handler.
			assertUntouched(crypt(selector, POS, ITERATOR), references);
		}
		// Widened by @Coerce: no typed @Local reads it.
		ClassNode coerced = crypt("placeEntities", "Ljava/lang/Object;", ITERATOR);
		MethodNode handler = MixinPlayerWorldCallbackAdapter.named(coerced, "hauntLevel");
		@SuppressWarnings("unchecked") List<AnnotationNode>[] table = new List[3];
		table[2] = new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Coerce;")));
		handler.invisibleParameterAnnotations = table;
		assertUntouched(coerced, VANILLA);
	}

	// ---- one point, however it is written (alone) -----------------------------------------------------------------

	@Test void twoWrapsAtOneCallAreOnePointHoweverEachIsWritten() throws Exception {
		CallbackSourceFixture spelled = new CallbackSourceFixture("org/example/catacomb/mixin/TwoSpellingsMixin", ST);
		spelled.handler("first", "(Ljava/util/List;" + OPERATION + ")Ljava/util/Iterator;", WRAP, "method", List.of("placeEntities"),
				"at", List.of(at("INVOKE", ITERATOR))).code(PASS_ITERATOR);
		// The descriptor where the other wrote a bare name, a dotted owner with whitespace, and the one call's ordinal 0.
		spelled.handler("second", "(Ljava/util/List;" + OPERATION + ")Ljava/util/Iterator;", WRAP, "method", List.of("L" + ST + ";" + OLD),
				"at", List.of(at("INVOKE", "java.util.List.iterator ()Ljava/util/Iterator;", "ordinal", 0))).code(PASS_ITERATOR);
		assertUntouched(spelled.build(), VANILLA);
	}

	@Test void twoWrapsAtTwoCallsEachMove() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/catacomb/mixin/TwoCallsMixin", ST);
		source.handler("first", "(Ljava/util/List;" + OPERATION + ")Ljava/util/Iterator;", WRAP, "method", List.of("placeEntities"),
				"at", List.of(at("INVOKE", ITERATOR))).code(PASS_ITERATOR);
		source.handler("second", "(Lnet/minecraft/nbt/CompoundTag;" + OPERATION + ")Lnet/minecraft/nbt/CompoundTag;", WRAP, "method", List.of("placeEntities"),
				"at", List.of(at("INVOKE", "Lnet/minecraft/nbt/CompoundTag;copy()Lnet/minecraft/nbt/CompoundTag;"))).code(code -> {
			code.visitVarInsn(Opcodes.ALOAD, 1);
			code.visitInsn(Opcodes.ARETURN);
		});
		ClassNode mixin = source.build();
		assertEquals(2, structure(mixin, VANILLA));
		CarpetMixinAdapterTest.verify(mixin);
	}

	private static void assertUntouched(ClassNode mixin, BiFunction<Ecosystem, String, ClassNode> references) {
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, structure(mixin, references));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}

	// ---- around Item.useOn: the point at the call, however it is written ---------------------------------------------

	private static final String STACK = "net/minecraft/world/item/ItemStack", CONTEXT = "Lnet/minecraft/world/item/context/UseOnContext;";
	private static final String USE_ON = "useOn(" + CONTEXT + ")Lnet/minecraft/world/InteractionResult;", CALL = "Lnet/minecraft/world/item/Item;" + USE_ON;

	private static int placement(ClassNode mixin) {
		return MixinPlacementTransactionAdapter.adapt(mixin, CarpetMixinAdapterTest::target, VANILLA);
	}

	@Test void aBuilderCallbackAtTheCallIsReadAsMixinReadsItsTarget() throws Exception {
		for (String target : List.of(" net.minecraft.world.item.Item . " + USE_ON, USE_ON, "Lnet/minecraft/world/item/Item;useOn")) {
			CallbackSourceFixture source = new CallbackSourceFixture("org/example/mason/mixin/TrowelMixin", STACK);
			source.handler("beforeLaying", "(" + CONTEXT + CIR + ")V", INJECT, "method", List.of("useOn"), "at", List.of(at("INVOKE", target)))
					.code(code -> code.visitInsn(Opcodes.RETURN));
			ClassNode mixin = source.build();
			assertEquals(1, placement(mixin), target + ": vanilla's useOn makes that one call");
			CarpetMixinAdapterTest.verify(mixin);
		}
	}

	@Test void twoBuilderCallbacksAtTheCallAreOnePointHoweverEachIsWritten() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/mason/mixin/TwoTrowelsMixin", STACK);
		source.handler("first", "(" + CONTEXT + CIR + ")V", INJECT, "method", List.of("useOn"), "at", List.of(at("INVOKE", CALL)))
				.code(code -> code.visitInsn(Opcodes.RETURN));
		source.handler("second", "(" + CONTEXT + CIR + ")V", INJECT, "method", List.of("L" + STACK + ";" + USE_ON),
				"at", List.of(at("INVOKE", "net.minecraft.world.item.Item.useOn (" + CONTEXT + ")Lnet/minecraft/world/InteractionResult;", "ordinal", 0)))
				.code(code -> code.visitInsn(Opcodes.RETURN));
		ClassNode mixin = source.build();
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, placement(mixin));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}

	// ---- the HUD's contextual bar ----------------------------------------------------------------------------------

	private static final String HUD = "net/minecraft/client/gui/Hud", GRAPHICS = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;", DELTA = "Lnet/minecraft/client/DeltaTracker;";
	private static final String INFO = "L" + HUD + "$ContextualInfo;", NEXT = "L" + HUD + ";nextContextualInfoState()" + INFO;

	/** A sundial mod's wrap of the contextual-bar choice: {@code return (ContextualInfo) original.call(hud);}. */
	private static Handler sundial(CallbackSourceFixture source, String extras, String selector, String target) {
		return source.handler("shadow", "(L" + HUD + ";" + OPERATION + extras + ")" + INFO, WRAP, "method", List.of(selector), "at", List.of(at("INVOKE", target)))
				.code(code -> {
					code.visitVarInsn(Opcodes.ALOAD, 2);
					array(code, 1);
					code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true);
					code.visitTypeInsn(Opcodes.CHECKCAST, HUD + "$ContextualInfo");
					code.visitInsn(Opcodes.ARETURN);
				});
	}

	private static int hud(ClassNode mixin, BiFunction<Ecosystem, String, ClassNode> references) {
		return MixinHudContextAdapter.adapt(mixin, MixinCallbackSelectorSpellingTest::target, references);
	}

	@Test void aHudWrapTakingWhicheverOfTheFramesValuesItWantsIsServed() throws Exception {
		CallbackSourceFixture graphicsAppended = new CallbackSourceFixture("org/example/sundial/mixin/SundialMixin", HUD);
		sundial(graphicsAppended, GRAPHICS, "extractHotbarAndDecorations", NEXT);
		CallbackSourceFixture deltaOnly = new CallbackSourceFixture("org/example/sundial/mixin/SundialMixin", HUD);
		sundial(deltaOnly, DELTA, "extractHotbarAndDecorations", NEXT).local(2);
		CallbackSourceFixture nothing = new CallbackSourceFixture("org/example/sundial/mixin/SundialMixin", HUD);
		sundial(nothing, "", "extractHotbarAndDecorations", NEXT);
		for (CallbackSourceFixture source : List.of(graphicsAppended, deltaOnly, nothing)) {
			ClassNode mixin = source.build();
			assertEquals(1, hud(mixin, NONE));
			CarpetMixinAdapterTest.verify(mixin);
			MethodNode wrapper = MixinPlayerWorldCallbackAdapter.named(mixin, "shadow");
			assertEquals("(L" + HUD + ";" + OPERATION + GRAPHICS + DELTA + ")V", wrapper.desc);
			for (int parameter : new int[]{2, 3}) assertNotNull(MixinStubRebind.sugar(wrapper, parameter, MixinRetarget.LOCAL_SUGAR),
					"the wrapper reads the new host's graphics and delta tracker as @Locals");
			MethodNode callback = mixin.methods.stream().filter(m -> m.name.equals("forbric$hudContextCallback")).findFirst().orElseThrow();
			MethodNode original = mixin.methods.stream().filter(m -> m.name.equals("shadow$forbricOriginal")).findFirst().orElseThrow();
			MethodInsnNode call = null;
			for (AbstractInsnNode insn : callback.instructions) if (insn instanceof MethodInsnNode m && m.name.equals(original.name)) call = m;
			assertNotNull(call);
			// The handler is handed exactly the values it declared, in its own order: slot 1 graphics, slot 2 delta tracker.
			List<Integer> handed = new ArrayList<>();
			for (AbstractInsnNode insn = call.getPrevious(); insn instanceof VarInsnNode load && load.var > 0 && load.var < 3; insn = insn.getPrevious()) handed.addFirst(load.var);
			Type[] declared = Type.getArgumentTypes(original.desc);
			assertEquals(declared.length - 2, handed.size());
			for (int i = 0; i < handed.size(); i++) assertEquals(handed.get(i) == 1 ? Type.getType(GRAPHICS) : Type.getType(DELTA), declared[2 + i]);
			assertEquals(0, hud(mixin, NONE), "idempotence");
		}
	}

	@Test void aHudValueThatIsNotTheTargetsArgumentAtItsPositionIsRefused() throws Exception {
		// The delta tracker is extractHotbarAndDecorations' second argument: appended first, Mixin refuses it.
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/sundial/mixin/SundialMixin", HUD);
		sundial(source, DELTA, "extractHotbarAndDecorations", NEXT);
		ClassNode mixin = source.build();
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, hud(mixin, VANILLA));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}

	@Test void theHudPointIsReadAsMixinReadsItsTarget() throws Exception {
		// Whitespace and a dotted owner: the same member, no body needed to say so.
		CallbackSourceFixture dotted = new CallbackSourceFixture("org/example/sundial/mixin/SundialMixin", HUD);
		sundial(dotted, "", "extractHotbarAndDecorations", "net.minecraft.client.gui.Hud.nextContextualInfoState ()" + INFO);
		assertEquals(1, hud(dotted.build(), NONE));
		// No owner: it names Hud's call only where vanilla's method makes no other call of that name and descriptor.
		CallbackSourceFixture ownerless = new CallbackSourceFixture("org/example/sundial/mixin/SundialMixin", HUD);
		sundial(ownerless, "", "extractHotbarAndDecorations", "nextContextualInfoState()" + INFO);
		ClassNode withNative = ownerless.build(), without = ownerless.build();
		assertEquals(1, hud(withNative, VANILLA), "read in vanilla's extractHotbarAndDecorations");
		CarpetMixinAdapterTest.verify(withNative);
		byte[] before = CarpetMixinAdapterTest.bytes(without);
		assertEquals(0, hud(without, NONE), "without vanilla's body nothing says which owner's call it names");
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(without));
		// A look-alike: the same name on another owner is another call.
		CallbackSourceFixture elsewhere = new CallbackSourceFixture("org/example/sundial/mixin/SundialMixin", HUD);
		sundial(elsewhere, "", "extractHotbarAndDecorations", "Lnet/minecraft/client/gui/Gui;nextContextualInfoState()" + INFO);
		ClassNode other = elsewhere.build();
		before = CarpetMixinAdapterTest.bytes(other);
		assertEquals(0, hud(other, VANILLA));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(other));
	}
}
