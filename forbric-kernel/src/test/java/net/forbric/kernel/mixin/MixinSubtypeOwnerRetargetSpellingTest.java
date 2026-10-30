/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Both moves read a point's owner, name and descriptor as Mixin resolves the target — an owner prefix, a dotted owner,
 * whitespace, or no descriptor where the class the mod was compiled against decides it — and the host as Mixin binds the
 * selector. lithostitched's real predicate injector respelled, and an invented archer goal whose field the merge widened.
 */
@ResourceLock("system-properties")
class MixinSubtypeOwnerRetargetSpellingTest {
	private static final Path LITHO = Path.of("run/client-merged-pack/mods/lithostitched-1.7.13-fabric-26.2.jar");
	private static final String MIXIN = "dev/worldgen/lithostitched/mixin/common/predicate/RegistryLoadTaskMixin";
	private static final String TARGET = "net/minecraft/resources/RegistryLoadTask$PendingRegistration";
	private static final String PARSE = "(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;";
	private static final String CODEC_PARSE = "Lcom/mojang/serialization/Codec;parse" + PARSE;

	@Test
	void thePredicatePointWrittenAnotherWayStillFollowsTheCallThroughCodec() throws Exception {
		ClassNode merged = game(false), vanilla = game(true);
		Map<String, List<String>> spellings = Map.of(
				"com.mojang.serialization.Decoder.parse" + PARSE, List.of("net.minecraft.resources.RegistryLoadTask$PendingRegistration.loadFromResource"),
				" Lcom/mojang/serialization/Decoder; parse " + PARSE, List.of("L" + TARGET + ";loadFromResource"),
				"Lcom/mojang/serialization/Decoder;parse", List.of(" loadFromResource "));
		for (var spelling : spellings.entrySet()) {
			ClassNode mixin = respelled(spelling.getValue(), spelling.getKey());
			assertEquals(1, MixinSubtypeOwnerRetarget.adapt(mixin, n -> merged, n -> n.equals(TARGET) ? vanilla : null), spelling.toString());
			assertEquals(CODEC_PARSE, MixinFit.value(at(mixin), "target"), "the same call through Codec, spelled out");
		}
	}

	@Test
	void aPointOrHostTheMoveCannotTellAsTheSameCallStaysAsWritten() throws Exception {
		ClassNode merged = game(false), vanilla = game(true);
		Function<String, ClassNode> natives = n -> n.equals(TARGET) ? vanilla : null;
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(respelled(List.of("loadFromResource"), "parse" + PARSE), n -> merged, natives),
				"no owner: Mixin already matches Codec.parse");
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(respelled(List.of("loadFromResource"), "Lcom/mojang/serialization/Decoder;parse"), n -> merged, n -> null),
				"no descriptor and no class the mod was compiled against");
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(respelled(List.of("loadFromResource"), "com.mojang.serialization.Encoder.parse" + PARSE), n -> merged, natives),
				"an owner the merge does not call through a subtype");
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(respelled(List.of("Lnet/minecraft/resources/Elsewhere;loadFromResource"), "Lcom/mojang/serialization/Decoder;parse" + PARSE),
				n -> merged, natives), "a selector naming another owner binds nothing");
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(respelled(List.of("/.*/"), "Lcom/mojang/serialization/Decoder;parse" + PARSE), n -> merged, natives),
				"a pattern binding no one host");
	}

	// --- through a field the merge widened ---

	private static final String GOAL = "net/minecraft/world/entity/ai/goal/RangedBowAttackGoal";
	private static final String MOB = "net/minecraft/world/entity/Mob", MONSTER = "net/minecraft/world/entity/monster/Monster";
	private static final String LOOK = "(Lnet/minecraft/world/entity/Entity;FF)V";

	@Test
	void theArchersLookWrittenAnotherWayFollowsTheWidenedField() {
		for (String point : List.of("net.minecraft.world.entity.monster.Monster.lookAt" + LOOK, " L" + MONSTER + "; lookAt " + LOOK, "L" + MONSTER + ";lookAt")) {
			for (List<String> selectors : List.of(List.of("L" + GOAL + ";aim"), List.of("aim", "net.minecraft.world.entity.ai.goal.RangedBowAttackGoal.aim()V"))) {
				ClassNode mixin = archer(selectors, point);
				assertEquals(1, MixinSubtypeOwnerRetarget.adapt(mixin, world()::get, n -> n.equals(GOAL) ? vanillaGoal() : null), point + " in " + selectors);
				AnnotationNode at = at(mixin);
				assertEquals("L" + MOB + ";lookAt" + LOOK, MixinFit.value(at, "target"));
				assertEquals(0, MixinFit.value(at, "ordinal"));
			}
		}
	}

	@Test
	void anArcherPointWithoutItsOwnerOrDescriptorProofStaysAsWritten() {
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(archer(List.of("aim"), "lookAt" + LOOK), world()::get, n -> vanillaGoal()), "no owner");
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(archer(List.of("aim"), "L" + MONSTER + ";lookAt"), world()::get, n -> null), "no descriptor, nothing to read it in");
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(archer(List.of("Lnet/minecraft/world/entity/ai/goal/Goal;aim"), "L" + MONSTER + ";lookAt" + LOOK), world()::get,
				n -> vanillaGoal()), "another owner's method");
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(archer(List.of("aim"), "L" + MONSTER + ";lookAt(Lnet/minecraft/world/entity/Entity;)V"), world()::get,
				n -> vanillaGoal()), "another overload of lookAt");
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static ClassNode respelled(List<String> selectors, String point) throws Exception {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(LITHO), "lithostitched's Fabric jar required");
		ClassNode mixin;
		try (ZipFile zip = new ZipFile(LITHO.toFile())) {
			mixin = MixinFit.parse(zip.getInputStream(zip.getEntry(MIXIN + ".class")).readAllBytes());
		}
		MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("loadFromResource")).findFirst().orElseThrow();
		AnnotationNode injector = MixinFit.injectorOf(handler);
		for (int i = 0; i + 1 < injector.values.size(); i += 2)
			if ("method".equals(injector.values.get(i))) injector.values.set(i + 1, new ArrayList<>(selectors));
		AnnotationNode at = MixinFit.atNodes(injector).getFirst();
		for (int i = 0; i + 1 < at.values.size(); i += 2) if ("target".equals(at.values.get(i))) at.values.set(i + 1, point);
		return mixin;
	}

	private static AnnotationNode at(ClassNode mixin) {
		for (MethodNode m : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(m);
			if (injector != null) return MixinFit.atNodes(injector).getFirst();
		}
		throw new AssertionError("no injector");
	}

	/** The pending registration as the kernel reads it, local variable table included (the handler names its local). */
	private static ClassNode game(boolean vanilla) throws Exception {
		Path jar = vanilla ? TestFixtures.vanillaJar() : TestFixtures.stagedBase();
		TestFixtures.require(vanilla ? Fixture.MC_LIBRARIES : Fixture.STAGED, Files.isRegularFile(jar), "actual game required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry(TARGET + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}

	/** AFTER the archer's own look in {@code aim}, a plain instance handler. */
	private static ClassNode archer(List<String> selectors, String point) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
		mixin.name = "spelled/archery/AimFix";
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(GOAL)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", point, "shift",
				new String[] {"Lorg/spongepowered/asm/mixin/injection/At$Shift;", "AFTER"}));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(selectors), "at", new ArrayList<>(List.of(at))));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "steady", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		handler.maxLocals = 2;
		mixin.methods = new ArrayList<>(List.of(handler));
		return mixin;
	}

	/** The merged goal: {@code aim} looks through the widened {@code mob} field, kept in a local first; and the type chain. */
	private static Map<String, ClassNode> world() {
		ClassNode goal = node(GOAL, "java/lang/Object");
		goal.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mob", "L" + MOB + ";", null, null));
		goal.methods.add(aim(MOB));
		Map<String, ClassNode> world = new HashMap<>();
		world.put(GOAL, goal);
		ClassNode mob = node(MOB, "java/lang/Object");
		mob.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "lookAt", LOOK, null, null));
		world.put(MOB, mob);
		world.put(MONSTER, node(MONSTER, MOB));
		return world;
	}

	/** The goal the mod was compiled against: the look is {@code Monster.lookAt}. */
	private static ClassNode vanillaGoal() {
		ClassNode goal = node(GOAL, "java/lang/Object");
		goal.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mob", "L" + MONSTER + ";", null, null));
		goal.methods.add(aim(MONSTER));
		return goal;
	}

	private static MethodNode aim(String owner) {
		MethodNode aim = new MethodNode(Opcodes.ACC_PUBLIC, "aim", "()V", null, null);
		aim.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		aim.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, GOAL, "mob", "L" + owner + ";"));
		aim.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
		aim.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		aim.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		aim.instructions.add(new InsnNode(Opcodes.FCONST_0));
		aim.instructions.add(new InsnNode(Opcodes.FCONST_0));
		aim.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "lookAt", LOOK, false));
		aim.instructions.add(new InsnNode(Opcodes.RETURN));
		aim.maxStack = 4;
		aim.maxLocals = 2;
		return aim;
	}

	private static ClassNode node(String name, String superName) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = name;
		node.superName = superName;
		return node;
	}
}
