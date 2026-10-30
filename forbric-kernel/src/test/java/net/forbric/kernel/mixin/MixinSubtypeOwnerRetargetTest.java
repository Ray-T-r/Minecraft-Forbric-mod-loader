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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import net.forbric.kernel.transform.WidenedFieldTwinInjector;

/**
 * lithostitched's Fabric predicate injector, on the real merged loadFromResource; and debugify's MC-121706 fix, on the real
 * merged RangedBowAttackGoal, whose field both carriers widened from Monster to Mob.
 */
@ResourceLock("system-properties")
class MixinSubtypeOwnerRetargetTest {
	private static final Path LITHO = Path.of("run/client-merged-pack/mods/lithostitched-1.7.13-fabric-26.2.jar");
	private static final String MIXIN = "dev/worldgen/lithostitched/mixin/common/predicate/RegistryLoadTaskMixin";
	private static final String TARGET = "net/minecraft/resources/RegistryLoadTask$PendingRegistration";

	@AfterEach void reset() {
		System.clearProperty(MixinSubtypeOwnerRetarget.PROPERTY);
		System.clearProperty(MixinSubtypeOwnerRetarget.RETYPED_FIELD_PROPERTY);
	}

	private static ClassNode litho() throws Exception {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(LITHO), "lithostitched's Fabric jar required");
		try (ZipFile zip = new ZipFile(LITHO.toFile())) {
			return MixinFit.parse(zip.getInputStream(zip.getEntry(MIXIN + ".class")).readAllBytes());
		}
	}

	/** The target as ForbricMixinService reads it: with its local variable table (the fixture skips debug info). */
	private static ClassNode withLocals(boolean vanilla) throws Exception {
		Path jar = vanilla ? TestFixtures.vanillaJar() : TestFixtures.stagedBase();
		TestFixtures.require(vanilla ? Fixture.MC_LIBRARIES : Fixture.STAGED, Files.isRegularFile(jar), "actual game required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry(TARGET + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}

	@Test void thePredicateCheckMovesToTheSameCallThroughCodec() throws Exception {
		ClassNode mixin = litho(), target = withLocals(false);
		assertEquals(1, MixinSubtypeOwnerRetarget.adapt(mixin, n -> target));
		MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("loadFromResource")).findFirst().orElseThrow();
		assertEquals("Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;",
				MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst(), "target"));
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(mixin, n -> target), "a second pass changes nothing");
	}

	@Test void vanillasOwnCallTheSwitchAndAMissingLocalAreLeftAlone() throws Exception {
		ClassNode vanilla = withLocals(true);
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(litho(), n -> vanilla), "vanilla still calls Decoder.parse");
		ClassNode merged = withLocals(false);
		System.setProperty(MixinSubtypeOwnerRetarget.PROPERTY, "off");
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(litho(), n -> merged));
		System.clearProperty(MixinSubtypeOwnerRetarget.PROPERTY);
		ClassNode renamed = withLocals(false);
		for (MethodNode m : renamed.methods) if (m.localVariables != null) for (LocalVariableNode l : m.localVariables) if (l.name.equals("json")) l.name = "element";
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(litho(), n -> renamed), "the handler's @Local(name=\"json\") must exist at the call");
	}

	@Test void codecDoesNotRedeclareParse() throws Exception {
		Path dfu = TestFixtures.minecraftDir().resolve("libraries/com/mojang/datafixerupper");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isDirectory(dfu), "Minecraft's DataFixerUpper required");
		Path jar;
		try (var files = Files.walk(dfu)) { jar = files.filter(p -> p.toString().endsWith(".jar")).sorted().reduce((a, b) -> b).orElseThrow(); }
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode codec = MixinFit.parse(zip.getInputStream(zip.getEntry("com/mojang/serialization/Codec.class")).readAllBytes());
			assertTrue(codec.interfaces.contains("com/mojang/serialization/Decoder"));
			assertEquals(List.of(), codec.methods.stream().filter(m -> m.name.equals("parse")).map(m -> m.name + m.desc).toList(),
					"Codec.parse is Decoder.parse: the retarget names the same method");
		}
	}

	// --- through a field the merge widened ---

	private static final String GOAL = "net/minecraft/world/entity/ai/goal/RangedBowAttackGoal";
	private static final String MONSTER_LOOK = "Lnet/minecraft/world/entity/monster/Monster;lookAt(Lnet/minecraft/world/entity/Entity;FF)V";
	private static final String MOB_LOOK = "Lnet/minecraft/world/entity/Mob;lookAt(Lnet/minecraft/world/entity/Entity;FF)V";

	/** One game's classes as the kernel serves them: the merged goal after the twin injector, which debugify's @Shadow needs. */
	private static Function<String, byte[]> game(boolean vanilla) throws Exception {
		Path jar = vanilla ? TestFixtures.vanillaJar() : TestFixtures.stagedBase();
		TestFixtures.require(vanilla ? Fixture.MC_LIBRARIES : Fixture.STAGED, Files.isRegularFile(jar), "actual game required");
		Map<String, byte[]> classes = new HashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (String name : List.of(GOAL, "net/minecraft/world/entity/monster/Monster", "net/minecraft/world/entity/PathfinderMob",
					"net/minecraft/world/entity/Mob")) {
				byte[] bytes = zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes();
				if (!vanilla && name.equals(GOAL)) bytes = new WidenedFieldTwinInjector().transform(name.replace('/', '.'), bytes, null);
				classes.put(name + ".class", bytes);
			}
		}
		return classes::get;
	}

	private static Function<String, ClassNode> nodes(Function<String, byte[]> game) {
		return name -> {
			byte[] bytes = game.apply(name + ".class");
			if (bytes == null) return null;
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			return node;
		};
	}

	/** debugify's RangedBowAttackGoalMixin, as compiled: AFTER Monster.lookAt in tick, a @Shadow of vanilla's mob. */
	private static ClassNode debugifyShaped(Integer ordinal) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
		mixin.name = "dev/example/RangedBowAttackGoalMixin";
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(GOAL)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		FieldNode shadow = new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mob", "Lnet/minecraft/world/entity/monster/Monster;", null, null);
		shadow.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Shadow;")));
		mixin.fields = new ArrayList<>(List.of(shadow));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", MONSTER_LOOK, "shift",
				new String[] {"Lorg/spongepowered/asm/mixin/injection/At$Shift;", "AFTER"}));
		if (ordinal != null) at.values.addAll(List.of("ordinal", ordinal));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("tick")), "at", new ArrayList<>(List.of(at))));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "lookAtTarget", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		handler.maxLocals = 2;
		mixin.methods = new ArrayList<>(List.of(handler));
		return mixin;
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * Both looks in the merged tick are Mob.lookAt; the archer's own is the second (the vehicle's is first, through Mob in
	 * vanilla too). The point moves there, the handler goes behind a guard on the field holding a Monster, and the verdict
	 * agrees — before the move it read the miss as PARTIAL, and Mixin's require=1 then stopped a strict launch.
	 */
	@Test void debugifysFixFollowsTheArchersOwnLookThroughTheWidenedField() throws Exception {
		Function<String, byte[]> merged = game(false);
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(bytes(debugifyShaped(null)), merged).verdict());
		ClassNode mixin = debugifyShaped(null);
		assertEquals(1, MixinSubtypeOwnerRetarget.adapt(mixin, nodes(merged)));
		MethodNode guard = StagedFabricMixinFixture.method(mixin, "lookAtTarget");
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(guard)).getFirst();
		assertEquals(MOB_LOOK, MixinFit.value(at, "target"));
		assertEquals(1, MixinFit.value(at, "ordinal"));
		List<String> code = new ArrayList<>();
		for (AbstractInsnNode insn : guard.instructions) {
			if (insn instanceof FieldInsnNode f) code.add("getfield " + f.owner + "." + f.name + ":" + f.desc);
			if (insn instanceof TypeInsnNode t) code.add("instanceof " + t.desc);
			if (insn instanceof MethodInsnNode m) code.add("call " + m.name);
		}
		assertEquals(List.of("getfield dev/example/RangedBowAttackGoalMixin.mob:Lnet/minecraft/world/entity/Mob;",
				"instanceof net/minecraft/world/entity/monster/Monster", "call " + MixinHandlerShim.asideName(mixin.name, "lookAtTarget",
						MixinSubtypeOwnerRetarget.NARROW_SUFFIX)), code);
		assertNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin, MixinHandlerShim.asideName(mixin.name, "lookAtTarget",
				MixinSubtypeOwnerRetarget.NARROW_SUFFIX))));

		// The mod's own ordinal 0 is vanilla's first Monster.lookAt: still the archer's, the second Mob.lookAt.
		ClassNode first = debugifyShaped(0);
		assertEquals(1, MixinSubtypeOwnerRetarget.adapt(first, nodes(merged)));
		assertEquals(1, MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(StagedFabricMixinFixture.method(first, "lookAtTarget"))).getFirst(), "ordinal"));
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(debugifyShaped(1), nodes(merged)), "vanilla had one Monster.lookAt: ordinal 1 missed natively");
	}

	/** RED control: with the rule off the point stays as compiled, and the verdict names the miss Mixin will meet. */
	@Test void withTheRuleOffTheArchersLookIsTheMissItWas() throws Exception {
		System.setProperty(MixinSubtypeOwnerRetarget.RETYPED_FIELD_PROPERTY, "off");
		Function<String, byte[]> merged = game(false);
		ClassNode mixin = debugifyShaped(null);
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(mixin, nodes(merged)));
		MixinFit.Result fit = MixinFit.evaluate(bytes(debugifyShaped(null)), merged);
		assertEquals(MixinFit.Verdict.PARTIAL, fit.verdict());
		assertEquals(List.of("@At(INVOKE) net.minecraft.world.entity.monster.Monster.lookAt in RangedBowAttackGoal.tick"), fit.unresolved());
	}

	@Test void vanillasGoalStillMakesTheCallItself() throws Exception {
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(debugifyShaped(null), nodes(game(true))));
	}

	/** The same name through another owner is the same call only when nothing between the two redeclares it. */
	@Test void aMethodTheNarrowTypeRedeclaresIsAnotherCall() {
		Map<String, ClassNode> world = new HashMap<>();
		world.put("a/Wide", type("a/Wide", "java/lang/Object", true));
		world.put("a/Middle", type("a/Middle", "a/Wide", false));
		world.put("a/Narrow", type("a/Narrow", "a/Middle", false));
		assertTrue(MixinSubtypeOwnerRetarget.sameMethod("a/Narrow", "a/Wide", "look", "()V", world::get));
		world.put("a/Middle", type("a/Middle", "a/Wide", true));
		assertFalse(MixinSubtypeOwnerRetarget.sameMethod("a/Narrow", "a/Wide", "look", "()V", world::get), "Middle overrides it");
		assertFalse(MixinSubtypeOwnerRetarget.sameMethod("a/Wide", "a/Narrow", "look", "()V", world::get), "not a supertype");
	}

	/**
	 * A receiver that is the field on one path and something else on another: which call vanilla named is unknowable, so
	 * nothing moves (a guess here would run the handler after a look it was never written for).
	 */
	@Test void aReceiverThatMayBeEitherDeclines() {
		ClassNode goal = new ClassNode();
		goal.version = Opcodes.V21;
		goal.access = Opcodes.ACC_PUBLIC;
		goal.name = GOAL;
		goal.superName = "java/lang/Object";
		goal.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mob", "Lnet/minecraft/world/entity/Mob;", null, null));
		goal.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "other", "Lnet/minecraft/world/entity/Mob;", null, null));
		MethodNode tick = new MethodNode(Opcodes.ACC_PUBLIC, "tick", "(Z)V", null, null);
		LabelNode join = new LabelNode(), otherwise = new LabelNode();
		tick.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
		tick.instructions.add(new JumpInsnNode(Opcodes.IFEQ, otherwise));
		tick.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		tick.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, GOAL, "mob", "Lnet/minecraft/world/entity/Mob;"));
		tick.instructions.add(new JumpInsnNode(Opcodes.GOTO, join));
		tick.instructions.add(otherwise);
		tick.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		tick.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, GOAL, "other", "Lnet/minecraft/world/entity/Mob;"));
		tick.instructions.add(join);
		tick.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
		tick.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		tick.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		tick.instructions.add(new InsnNode(Opcodes.FCONST_0));
		tick.instructions.add(new InsnNode(Opcodes.FCONST_0));
		tick.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/entity/Mob", "lookAt", "(Lnet/minecraft/world/entity/Entity;FF)V", false));
		tick.instructions.add(new InsnNode(Opcodes.RETURN));
		tick.maxStack = 4;
		tick.maxLocals = 3;
		goal.methods.add(tick);
		Map<String, ClassNode> world = new HashMap<>();
		world.put(GOAL, goal);
		world.put("net/minecraft/world/entity/Mob", type("net/minecraft/world/entity/Mob", "java/lang/Object", false));
		world.get("net/minecraft/world/entity/Mob").methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "lookAt", "(Lnet/minecraft/world/entity/Entity;FF)V", null, null));
		world.put("net/minecraft/world/entity/monster/Monster", type("net/minecraft/world/entity/monster/Monster", "net/minecraft/world/entity/Mob", false));
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(debugifyShaped(null), world::get));
		// …and the same body with the field on both paths moves.
		((FieldInsnNode) tick.instructions.get(7)).name = "mob";
		assertEquals(1, MixinSubtypeOwnerRetarget.adapt(debugifyShaped(null), world::get));
	}

	private static ClassNode type(String name, String superName, boolean declaresLook) {
		ClassNode node = new ClassNode();
		node.name = name;
		node.superName = superName;
		if (declaresLook) node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "look", "()V", null, null));
		return node;
	}
}
