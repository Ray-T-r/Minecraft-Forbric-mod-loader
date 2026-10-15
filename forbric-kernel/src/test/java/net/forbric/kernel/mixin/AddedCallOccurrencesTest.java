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

	/**
	 * NeoForge's {@code handleCommands} assigns {@code commands} again, to the server commands merged with the client ones.
	 * Create's {@code @At(FIELD, opcode=PUTFIELD, shift=AFTER)} registered its commands at both assignments.
	 */
	@Test void createsCommandsHookCountsVanillasAssignmentOnly() throws Exception {
		String listener = "net/minecraft/client/multiplayer/ClientPacketListener";
		ClassNode merged = StagedFabricMixinFixture.game(listener, false), vanilla = StagedFabricMixinFixture.game(listener, true);
		ClassNode mixin = CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/ClientPacketListenerMixin");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertTrue(AddedCallOccurrences.adapt(mixin, name -> name.equals(listener) ? merged : null,
				(ecosystem, name) -> name.equals(listener) ? vanilla : null) >= 1);
		assertEquals(0, MixinFit.value(StagedFabricMixinFixture.at(mixin, "addCommand"), "ordinal"));
	}

	/**
	 * NeoForge's {@code Animal.spawnChildFromBreeding} writes {@code child = event.getChild()} back after its
	 * {@code BabyEntitySpawnEvent}; PuzzlesLib's by-type {@code @ModifyVariable(at = @At("STORE"))} on the child ran at both
	 * stores, so its breeding event fired twice, the second time on what NeoForge's event left.
	 */
	@Test void puzzleslibsBreedingHookCountsVanillasStoreOnly() throws Exception {
		String animal = "net/minecraft/world/entity/animal/Animal";
		ClassNode merged = full(net.forbric.kernel.TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar"), animal);
		ClassNode vanilla = full(net.forbric.kernel.TestFixtures.vanillaJar(), animal);
		java.nio.file.Path jar = java.nio.file.Path.of("build/compat-inputs/sweep90/mods/PuzzlesLib-v26.2.4-mc26.2.x-Fabric.jar");
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.THIRD_PARTY, java.nio.file.Files.isRegularFile(jar), "sweep PuzzlesLib fixture");
		ClassNode mixin;
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
			mixin = MixinFit.parse(zip.getInputStream(zip.getEntry("fuzs/puzzleslib/fabric/mixin/AnimalFabricMixin.class")).readAllBytes());
		}
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(1, AddedCallOccurrences.adapt(mixin, name -> name.equals(animal) ? merged : null,
				(ecosystem, name) -> name.equals(animal) ? vanilla : null));
		assertEquals(0, MixinFit.value(StagedFabricMixinFixture.at(mixin, "spawnChildFromBreeding"), "ordinal"));
	}

	/**
	 * Architectury's CLIENT_RIGHT_CLICK_AIR is an {@code @Inject} at vanilla's second {@code ItemStack.isEmpty} in
	 * {@code Minecraft.startUseItem} (ordinal 1, the check in front of {@code useItem}). MinecraftForge's
	 * {@code onRightClickEmpty} and NeoForge's {@code onEmptyClick} each add an {@code isEmpty} in front of it, so ordinal 1
	 * named MinecraftForge's check. It now names the merged body's fourth, the one in front of {@code useItem}.
	 */
	@Test void architecturysRightClickAirKeepsVanillasIsEmptyByNumber() throws Exception {
		String minecraft = "net/minecraft/client/Minecraft";
		ClassNode merged = full(net.forbric.kernel.TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar"), minecraft);
		ClassNode vanilla = full(net.forbric.kernel.TestFixtures.vanillaJar(), minecraft);
		java.nio.file.Path jar = java.nio.file.Path.of("run/client-popular/mods/architectury-fabric-21.1.10.jar");
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.THIRD_PARTY, java.nio.file.Files.isRegularFile(jar), "popular Architectury fixture");
		ClassNode mixin;
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
			mixin = MixinFit.parse(zip.getInputStream(zip.getEntry("dev/architectury/mixin/fabric/client/MixinMinecraft.class")).readAllBytes());
		}
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		MethodNode start = StagedFabricMixinFixture.method(merged, "startUseItem");
		List<MethodInsnNode> isEmpty = calls(start, "Lnet/minecraft/world/item/ItemStack;isEmpty()Z");
		assertEquals(4, isEmpty.size(), "two family checks were added");
		assertTrue(AddedCallOccurrences.adapt(mixin, name -> name.equals(minecraft) ? merged : null,
				(ecosystem, name) -> name.equals(minecraft) ? vanilla : null) >= 1);
		assertEquals(3, MixinFit.value(StagedFabricMixinFixture.at(mixin, "rightClickAir"), "ordinal"));
		MethodInsnNode chosen = isEmpty.get(3);
		AbstractInsnNode after = chosen;
		while (!(after instanceof MethodInsnNode call && !call.name.equals("isEmpty"))) after = after.getNext();
		assertEquals("useItem", ((MethodInsnNode) after).name, "the check in front of useItem");
	}

	/**
	 * PuzzlesLib's entity-size and bow events pick their local by ordinal ({@code @ModifyVariable(ordinal = 1,
	 * at = @At("STORE"))}). NeoForge writes the {@code EntityEvent.Size} result back into the new dimensions in
	 * {@code Entity.refreshDimensions}: the event ran twice. The store the ordinal picks natively is the one counted.
	 */
	@Test void anOrdinalPickedLocalCountsVanillasStoreOnly() throws Exception {
		String entity = "net/minecraft/world/entity/Entity";
		ClassNode merged = full(net.forbric.kernel.TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar"), entity);
		ClassNode vanilla = full(net.forbric.kernel.TestFixtures.vanillaJar(), entity);
		ClassNode mixin = puzzleslib("fuzs/puzzleslib/fabric/mixin/EntityFabricMixin");
		assertTrue(AddedCallOccurrences.adapt(mixin, name -> name.equals(entity) ? merged : null,
				(ecosystem, name) -> name.equals(entity) ? vanilla : null) >= 1);
		assertEquals(0, MixinFit.value(StagedFabricMixinFixture.at(mixin, "refreshDimensions"), "ordinal"));
	}

	private static ClassNode puzzleslib(String name) throws Exception {
		java.nio.file.Path jar = java.nio.file.Path.of("build/compat-inputs/sweep90/mods/PuzzlesLib-v26.2.4-mc26.2.x-Fabric.jar");
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.THIRD_PARTY, java.nio.file.Files.isRegularFile(jar), "sweep PuzzlesLib fixture");
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
			ClassNode mixin = MixinFit.parse(zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes());
			MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
			return mixin;
		}
	}

	private static ClassNode full(java.nio.file.Path jar, String name) throws Exception {
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED, java.nio.file.Files.isRegularFile(jar), "game jar");
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes()).accept(node, org.objectweb.asm.ClassReader.SKIP_FRAMES);
			return node;
		}
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
