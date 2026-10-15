/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * NeoForge's {@code ServerPlayerGameMode.useItemOn} asks {@code player.getMainHandItem().doesSneakBypassUse(...)} after
 * vanilla's {@code getMainHandItem().isEmpty()}. Create's cancellable {@code @Inject} at {@code getMainHandItem} has no
 * ordinal, so it ran at both: twice for a sneaking player holding an item (the lane's census: one call natively, two on
 * Forbric). The point now counts the native occurrence only.
 */
class AddedCallOccurrencesTest {
	private static final String GAME_MODE = "net/minecraft/server/level/ServerPlayerGameMode";
	private static final String MAIN_HAND = "Lnet/minecraft/server/level/ServerPlayer;getMainHandItem()Lnet/minecraft/world/item/ItemStack;";

	@Test void createsUseItemOnInjectorCountsVanillasMainHandCallOnly() throws Exception {
		ClassNode merged = StagedFabricMixinFixture.game(GAME_MODE, false), vanilla = StagedFabricMixinFixture.game(GAME_MODE, true);
		assertEquals(1, calls(StagedFabricMixinFixture.method(vanilla, "useItemOn"), MAIN_HAND).size());
		List<MethodInsnNode> mergedCalls = calls(StagedFabricMixinFixture.method(merged, "useItemOn"), MAIN_HAND);
		assertEquals(2, mergedCalls.size(), "NeoForge's doesSneakBypassUse asks again");
		assertEquals("isEmpty", ((MethodInsnNode) next(mergedCalls.get(0))).name, "the first is vanilla's");

		ClassNode mixin = create();
		assertEquals(1, AddedCallOccurrences.adapt(mixin, name -> name.equals(GAME_MODE) ? merged : null,
				(ecosystem, name) -> name.equals(GAME_MODE) ? vanilla : null));
		AnnotationNode at = StagedFabricMixinFixture.at(mixin, "interactBlock");
		assertEquals(0, MixinFit.value(at, "ordinal"));
		assertTrue(CurrentBodyOrdinals.counted(StagedFabricMixinFixture.method(mixin, "interactBlock")));
	}

	@Test void onVanillaOrSwitchedOffNothingIsNumbered() throws Exception {
		ClassNode merged = StagedFabricMixinFixture.game(GAME_MODE, false), vanilla = StagedFabricMixinFixture.game(GAME_MODE, true);
		assertEquals(0, AddedCallOccurrences.adapt(create(), name -> vanilla, (ecosystem, name) -> vanilla), "nothing was added");
		String old = System.setProperty(AddedCallOccurrences.PROPERTY, "off");
		try {
			assertEquals(0, AddedCallOccurrences.adapt(create(), name -> merged, (ecosystem, name) -> vanilla));
		} finally {
			if (old == null) System.clearProperty(AddedCallOccurrences.PROPERTY); else System.setProperty(AddedCallOccurrences.PROPERTY, old);
		}
	}

	/** Two native calls, one added between them, its value going elsewhere: an {@code @At} list gets one point for each native call. */
	@Test void severalNativeOccurrencesBecomeOnePointEach() {
		ClassNode original = owner(false), current = owner(true);
		ClassNode mixin = mixin("t/AddedMixin", false);
		assertEquals(1, AddedCallOccurrences.adapt(mixin, name -> current, (ecosystem, name) -> original));
		List<AnnotationNode> points = MixinFit.atNodes(MixinFit.injectorOf(mixin.methods.getFirst()));
		assertEquals(List.of(0, 2), points.stream().map(p -> MixinFit.value(p, "ordinal")).toList());
	}

	/** An added call that reads exactly like the native one cannot be told apart from it: the point stays as written. */
	@Test void anAddedCallLikeTheNativeOneIsNotToldApart() {
		ClassNode original = owner(false), current = owner(true);
		MethodNode m = current.methods.getFirst();
		// the added call's value goes where the first native one's goes
		for (AbstractInsnNode i : m.instructions) if (i instanceof MethodInsnNode call && call.name.equals("other")) call.name = "first";
		ClassNode mixin = mixin("t/AlikeMixin", false);
		assertEquals(0, AddedCallOccurrences.adapt(mixin, name -> current, (ecosystem, name) -> original));
		assertNull(MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(mixin.methods.getFirst())).getFirst(), "ordinal"));
	}

	private static ClassNode create() throws Exception {
		ClassNode mixin = CreateGuestMixinFixture.mixin("com/zurrtum/create/mixin/ServerPlayerGameModeMixin");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	private static List<MethodInsnNode> calls(MethodNode method, String member) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode i : method.instructions) if (i instanceof MethodInsnNode call && CallOccurrenceAlignment.member(call).equals(member)) out.add(call);
		return out;
	}

	private static AbstractInsnNode next(AbstractInsnNode from) {
		AbstractInsnNode i = from.getNext();
		while (i != null && i.getOpcode() < 0) i = i.getNext();
		return i;
	}

	/** {@code static void m(Object o) { T.first(T.get(o)); [T.other(T.get(o));] T.second(T.get(o)); }} */
	private static ClassNode owner(boolean added) {
		MethodNode m = new MethodNode(Opcodes.ACC_STATIC, "m", "(Ljava/lang/Object;)V", null, null);
		InsnList c = m.instructions;
		consume(c, "first");
		if (added) consume(c, "other");
		consume(c, "second");
		c.add(new InsnNode(Opcodes.RETURN));
		m.maxLocals = 1;
		m.maxStack = 1;
		ClassNode owner = new ClassNode();
		owner.name = "t/T";
		owner.superName = "java/lang/Object";
		owner.methods.add(m);
		return owner;
	}

	private static void consume(InsnList c, String consumer) {
		c.add(new VarInsnNode(Opcodes.ALOAD, 0));
		c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "t/T", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
		c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "t/T", consumer, "(Ljava/lang/Object;)V", false));
	}

	/** {@code @Mixin(T.class) class M { @Inject(method="m", at=@At(value="INVOKE", target="Lt/T;get(...)")) void h(CallbackInfo ci) {} }} */
	private static ClassNode mixin(String name, boolean unused) {
		ClassNode mixin = new ClassNode();
		mixin.name = name;
		mixin.superName = "java/lang/Object";
		AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		target.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(org.objectweb.asm.Type.getObjectType("t/T")))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(target));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "h", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", "Lt/T;get(Ljava/lang/Object;)Ljava/lang/Object;"));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("m")), "at", new ArrayList<>(List.of(at))));
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		mixin.methods.add(handler);
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}
}
