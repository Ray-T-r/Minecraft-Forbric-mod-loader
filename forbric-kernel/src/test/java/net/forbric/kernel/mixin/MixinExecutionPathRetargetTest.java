package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import net.forbric.api.Ecosystem;

/** Proof guards use unrelated symbols; actual unmodified Fabric API contracts exercise the same planner. */
@ResourceLock("ModCatalog") @ResourceLock("system-properties")
class MixinExecutionPathRetargetTest {
	private static final String OWNER = "example/Dispatch", MEMBER = "Lexample/Action;consume(I)V";
	private static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
	private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";

	@BeforeEach @AfterEach void reset() {
		System.clearProperty(MixinExecutionPathRetarget.PROPERTY);
		MixinRetarget.reset();
		MixinStubRebind.forget();
		MergedBaseUncalledMethods.forgetGuests();
	}

	@Test void anExplicitUniqueHelperEdgeMovesAnOperationWithoutChangingTheHandler() {
		ClassNode target = delegated(false);
		ClassNode mixin = mixin("(I" + OPERATION + ")V", WRAP);
		MethodNode handler = mixin.methods.getFirst();
		AbstractInsnNode originalBody = handler.instructions.getFirst();
		MixinRetarget.Plan plan = plan(mixin, target);
		assertEquals(1, plan.rewrites().size(), plan.describe());
		assertEquals("piece(IZ)V", plan.rewrites().getFirst().to());
		assertEquals(1, MixinRetarget.apply(mixin, plan));
		assertSame(originalBody, handler.instructions.getFirst(), "the mod's Operation implementation remains the same body");
		assertEquals("(I" + OPERATION + ")V", handler.desc);
	}

	@Test void aSecondCallOfTheHelperMakesTheEdgeAmbiguous() {
		ClassNode target = delegated(false);
		MethodNode other = new MethodNode(Opcodes.ACC_PUBLIC, "elsewhere", "(I)V", null, null);
		other.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); other.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
		other.instructions.add(new InsnNode(Opcodes.ICONST_0));
		other.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "piece", "(IZ)V", false));
		other.instructions.add(new InsnNode(Opcodes.RETURN)); target.methods.add(other);
		assertTrue(plan(mixin("(I" + OPERATION + ")V", WRAP), parsed(target)).isEmpty());
	}

	@Test void aMissingOriginalReferenceCannotAuthorizeAHelperMigration() {
		ClassNode target = delegated(false), mixin = mixin("(I" + OPERATION + ")V", WRAP);
		assertTrue(MixinRetarget.plan(mixin, path -> path.equals(target.name + ".class") ? StagedFabricMixinFixture.bytes(target) : null,
				(ecosystem, owner) -> null).isEmpty());
	}

	@Test void anAnchorAbsentFromTheModsOwnPlatformCannotAuthorizeTheMove() {
		ClassNode target = delegated(false), mixin = mixin("(I" + OPERATION + ")V", WRAP), reference = reference(target);
		reference.methods.getFirst().instructions.clear(); reference.methods.getFirst().instructions.add(new InsnNode(Opcodes.RETURN));
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.NEOFORGE);
		assertTrue(plan(mixin, target, parsed(reference)).isEmpty(), "a NeoForge-native missing anchor must remain a miss");
	}

	@Test void aVerifiedOriginalAnchorCanAuthorizeTheSameRuleForAnyDeclaredFamily() {
		ClassNode target = delegated(false), mixin = mixin("(I" + OPERATION + ")V", WRAP);
		for (Ecosystem family : List.of(Ecosystem.FABRIC, Ecosystem.FORGE, Ecosystem.NEOFORGE)) {
			MixinStubRebind.noteEcosystem(mixin.name, family);
			assertEquals("piece(IZ)V", plan(mixin, target).rewrites().getFirst().to(), family.toString());
		}
	}

	@Test void anObsoleteCapturedLocalIsNotInventedInTheHelper() {
		ClassNode mixin = mixin("(I" + OPERATION + "Ljava/lang/String;)V", WRAP);
		local(mixin.methods.getFirst(), 2, false);
		assertTrue(plan(mixin, delegated(false)).isEmpty(), "the helper has no original String capture");
	}

	@Test void aSameValuedParameterLocalCanFollowTheActualForwardingSlots() {
		ClassNode mixin = mixin("(I" + OPERATION + "I)V", WRAP);
		local(mixin.methods.getFirst(), 2, true);
		assertEquals("piece(IZ)V", plan(mixin, delegated(false)).rewrites().getFirst().to());
	}

	@Test void aParameterReassignedBeforeDelegationIsNotTheOriginalCapture() {
		ClassNode mixin = mixin("(I" + OPERATION + "I)V", WRAP);
		local(mixin.methods.getFirst(), 2, true);
		assertTrue(plan(mixin, delegated(true)).isEmpty());
	}

	@Test void anInjectAtTheHelpersFirstInvocationStaysOnTheCallerIncludingItsCancellation() {
		ClassNode mixin = mixin("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", MixinRetarget.INJECT);
		MethodNode handler = mixin.methods.getFirst();
		MixinFit.injectorOf(handler).values.addAll(List.of("cancellable", true));
		MixinRetarget.Plan plan = plan(mixin, delegated(false));
		assertEquals(1, plan.rewrites().size(), plan.describe());
		assertEquals(MixinRetarget.Element.AT_TARGET, plan.rewrites().getFirst().element());
		assertEquals("Lexample/Dispatch;piece(IZ)V", plan.rewrites().getFirst().to());
		MixinRetarget.apply(mixin, plan);
		assertEquals(List.of("dispatch"), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method")));
		assertEquals(Boolean.TRUE, MixinFit.value(MixinFit.injectorOf(handler), "cancellable"));
	}

	@Test void anInjectCannotJumpAheadOfASideEffectInsideTheHelper() {
		ClassNode target = delegated(false);
		MethodNode helper = target.methods.stream().filter(m -> m.name.equals("piece")).findFirst().orElseThrow();
		helper.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "example/Action", "effect", "()V", false));
		assertTrue(plan(mixin("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", MixinRetarget.INJECT), parsed(target)).isEmpty());
	}

	@Test void aClosedBranchingFragmentIsProvedWithoutDependingOnTheNames() {
		ClassNode target = fragmentTarget();
		assertTrue(MixinExecutionPathRetarget.fragment(target, target.methods.get(0), target.methods.get(1)));
	}

	@Test void aChangedGuardIsNotTheSameFragmentEvenThoughItsCallMatches() {
		ClassNode target = fragmentTarget();
		for (AbstractInsnNode instruction : target.methods.get(1).instructions) if (instruction instanceof JumpInsnNode jump) jump.setOpcode(Opcodes.IFNE);
		assertFalse(MixinExecutionPathRetarget.fragment(target, target.methods.get(0), target.methods.get(1)));
	}

	@Test void anOutsideBranchEnteringTheMiddleOfTheFragmentPreventsExtraction() {
		ClassNode target = fragmentTarget();
		MethodNode selected = target.methods.get(0);
		LabelNode middle = new LabelNode();
		for (AbstractInsnNode instruction : selected.instructions) if (instruction instanceof VarInsnNode load && load.var == 1) {
			selected.instructions.insertBefore(instruction, middle); break;
		}
		InsnList entry = new InsnList(); entry.add(new InsnNode(Opcodes.ICONST_0)); entry.add(new JumpInsnNode(Opcodes.IFEQ, middle));
		selected.instructions.insert(entry);
		target = parsed(target);
		assertFalse(MixinExecutionPathRetarget.fragment(target, target.methods.get(0), target.methods.get(1)),
				"extracting the fragment would remove a path which bypassed its guard");
	}

	@Test void aCapturedLocalDefinedOutsideTheFragmentPreventsTheMove() {
		ClassNode target = fragmentTarget();
		MethodNode helper = target.methods.get(1);
		helper.instructions.insert(new InsnNode(Opcodes.POP));
		helper.instructions.insert(new VarInsnNode(Opcodes.ALOAD, 3));
		assertFalse(MixinExecutionPathRetarget.fragment(target, target.methods.get(0), helper));
	}

	@Test void actualModelGenerationAndFluidTravelFollowTheirExplicitHelperEdges() throws Exception {
		assertActual("fabric-data-generation-api-v1", "net/fabricmc/fabric/mixin/datagen/client/ModelProviderMixin",
				"net/minecraft/client/data/models/ModelProvider", Map.of("registerBlockStateModels", "registerModels",
						"registerItemModels", "registerModels", "setFabricPackOutput", "registerModels"));
		assertActual("fabric-renderer-api-v1", "net/fabricmc/fabric/mixin/client/renderer/block/model/SimpleModelWrapperMixin",
				"net/minecraft/client/resources/model/SimpleModelWrapper", Map.of("storeModelBakery", "bake"));
		assertActual("fabric-content-registries-v0", "net/fabricmc/fabric/mixin/content/registry/fluid/LivingEntityMixin",
				"net/minecraft/world/entity/LivingEntity", Map.of("travelInCustomFluid", "travelInFluid"));
	}

	@Test void actualHudFragmentsMoveIntoReachedPrivateHelpersAndRetainTheOriginalOperationBodies() throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-rendering-v1", "net/fabricmc/fabric/mixin/client/rendering/HudMixin");
		ClassNode target = StagedFabricMixinFixture.game("net/minecraft/client/gui/Hud", false);
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		MixinRetarget.Plan plan = plan(mixin, target);
		for (String handler : List.of("wrapHotbar", "wrapMountHealth", "wrapExtractInfoBar", "wrapExperienceLevel")) {
			assertTrue(plan.rewrites().stream().anyMatch(r -> r.handler().startsWith(handler + "(") && r.element() == MixinRetarget.Element.SELECTOR
					&& r.why().contains("exact closed CFG fragment")), handler + "\n" + plan.describe());
		}
		// The selected-item invocation is widened, and spectator action has a differently factored guard. This proof
		// refuses those shapes instead of relocating a handler on its anchor alone.
		assertFalse(plan.rewrites().stream().anyMatch(r -> r.handler().startsWith("wrapExtractSpectatorGui(")), plan.describe());
		System.setProperty(MixinExecutionPathRetarget.PROPERTY, "off");
		assertFalse(plan(mixin, target).rewrites().stream().anyMatch(r -> r.why().contains("exact closed CFG fragment")),
				"the generic path rule's control must leave the original dead bindings intact");
	}

	@Test void fuelCapturesThatExistOnlyOnTheForwarderAreNotDiscarded() throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-content-registries-v0", "net/fabricmc/fabric/mixin/content/registry/FuelValuesMixin");
		ClassNode target = StagedFabricMixinFixture.game("net/minecraft/world/level/block/entity/FuelValues", false);
		assertFalse(plan(mixin, target).rewrites().stream().anyMatch(r -> r.handler().startsWith("build(")),
				"Provider and FeatureFlagSet cannot be fabricated from a Builder parameter");
	}

	private static void assertActual(String module, String name, String targetName, Map<String, String> handlers) throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin(module, name), target = gameWithDebug(targetName, false);
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		MixinRetarget.Plan plan = plan(mixin, target, gameWithDebug(targetName, true));
		for (var expected : handlers.entrySet()) assertTrue(plan.rewrites().stream().anyMatch(r -> r.handler().startsWith(expected.getKey() + "(")
				&& r.to().contains(expected.getValue() + "(")), expected + "\n" + plan.describe());
	}

	private static MixinRetarget.Plan plan(ClassNode mixin, ClassNode target) {
		return plan(mixin, target, target.name.equals(OWNER) ? reference(target) : null);
	}
	private static MixinRetarget.Plan plan(ClassNode mixin, ClassNode target, ClassNode reference) {
		return MixinRetarget.plan(mixin, path -> path.equals(target.name + ".class") ? StagedFabricMixinFixture.bytes(target) : null,
				(ecosystem, owner) -> reference != null && reference.name.equals(owner) ? reference : null);
	}
	private static ClassNode reference(ClassNode target) {
		ClassNode reference = parsed(target);
		MethodNode method = reference.methods.stream().filter(m -> m.name.equals("dispatch")).findFirst().orElseThrow();
		method.instructions.clear(); method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "example/Action", "consume", "(I)V", false));
		method.instructions.add(new InsnNode(Opcodes.RETURN)); return parsed(reference);
	}
	private static ClassNode gameWithDebug(String owner, boolean vanilla) throws Exception {
		java.nio.file.Path jar = vanilla ? net.forbric.kernel.TestFixtures.vanillaJar()
				: net.forbric.kernel.TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		net.forbric.kernel.TestFixtures.require(vanilla ? net.forbric.kernel.TestFixtures.Fixture.MC_LIBRARIES
				: net.forbric.kernel.TestFixtures.Fixture.STAGED, java.nio.file.Files.isRegularFile(jar), "actual game with LVT required");
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode(); new ClassReader(zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes())
					.accept(node, ClassReader.SKIP_FRAMES); return node;
		}
	}

	private static ClassNode mixin(String descriptor, String annotation) {
		ClassNode mixin = new ClassNode(); mixin.name = "example/UnrelatedMixin";
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		mixin.visibleAnnotations = List.of(ann("Lorg/spongepowered/asm/mixin/Mixin;", "targets", List.of(OWNER)));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "observe", descriptor, null, null);
		AnnotationNode at = ann("Lorg/spongepowered/asm/mixin/injection/At;", "value", "INVOKE", "target", MEMBER);
		handler.visibleAnnotations = new ArrayList<>(List.of(ann(annotation, "method", new ArrayList<>(List.of("dispatch")), "at", new ArrayList<>(List.of(at)))));
		handler.instructions.add(new InsnNode(Opcodes.RETURN)); mixin.methods.add(handler); return mixin;
	}

	@SuppressWarnings("unchecked")
	private static void local(MethodNode handler, int index, boolean argsOnly) {
		handler.invisibleParameterAnnotations = new List[Type.getArgumentTypes(handler.desc).length];
		handler.invisibleParameterAnnotations[index] = List.of(ann(MixinRetarget.LOCAL_SUGAR, "argsOnly", argsOnly));
	}

	private static ClassNode delegated(boolean reassign) {
		ClassNode target = base();
		MethodNode caller = new MethodNode(Opcodes.ACC_PUBLIC, "dispatch", "(I)V", null, null);
		if (reassign) { caller.instructions.add(new InsnNode(Opcodes.ICONST_4)); caller.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1)); }
		caller.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); caller.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
		caller.instructions.add(new InsnNode(Opcodes.ICONST_1)); caller.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "piece", "(IZ)V", false));
		caller.instructions.add(new InsnNode(Opcodes.RETURN)); target.methods.add(caller);
		MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE, "piece", "(IZ)V", null, null);
		helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1)); helper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "example/Action", "consume", "(I)V", false));
		helper.instructions.add(new InsnNode(Opcodes.RETURN)); target.methods.add(helper); return parsed(target);
	}

	private static ClassNode fragmentTarget() {
		ClassNode target = base();
		MethodNode retained = new MethodNode(Opcodes.ACC_PUBLIC, "retained", "(IZ)V", null, null);
		retained.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "example/Action", "before", "()V", false));
		piece(retained); retained.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "example/Action", "after", "()V", false));
		retained.instructions.add(new InsnNode(Opcodes.RETURN)); target.methods.add(retained);
		MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE, "unrelatedName", "(IZ)V", null, null);
		piece(helper); helper.instructions.add(new InsnNode(Opcodes.RETURN)); target.methods.add(helper); return parsed(target);
	}
	private static void piece(MethodNode method) {
		LabelNode exit = new LabelNode(); method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2)); method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, exit));
		method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1)); method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "example/Action", "consume", "(I)V", false));
		method.instructions.add(exit);
	}
	private static ClassNode base() { ClassNode node = new ClassNode(); node.name = OWNER; node.version = Opcodes.V21; node.access = Opcodes.ACC_PUBLIC; node.superName = "java/lang/Object"; return node; }
	private static ClassNode parsed(ClassNode node) { ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return MixinFit.parse(writer.toByteArray()); }
	private static AnnotationNode ann(String descriptor, Object... values) { AnnotationNode annotation = new AnnotationNode(descriptor); annotation.values = new ArrayList<>(Arrays.asList(values)); return annotation; }
}
