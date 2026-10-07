package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Which stages of the guest-mixin pipeline the real-Mixin weave tests actually exercise, read from the bytecode.
 *
 * <p>Three lists, all computed rather than typed in, so a new stage cannot be added without this test noticing:
 * <ul>
 *   <li>the classes {@code ForbricMixinService.getResourceAsStream} asks, with a guest config's bytes, which of its
 *       entries to leave out before Mixin reads the config;</li>
 *   <li>the adapters {@code ForbricMixinService.getClassNode} runs over every guest mixin before Mixin parses it;</li>
 *   <li>the stages {@code KernelMixinBootstrap.init} runs over every class after Mixin wove it.</li>
 * </ul>
 * Every stage is either {@link #WOVEN} — a weave test runs it and its off switch turns the behaviour off — or in
 * {@link #NOT_WOVEN_YET} with the reason. A stage in both, or an allowlist row whose stage no longer exists, fails.
 * The allowlist only shrinks: adding a weave scenario means deleting its row here, and the diff shows it.
 */
class WeaveCoverageCensusTest {
	private static final String SERVICE = "net/forbric/kernel/mixin/ForbricMixinService";
	private static final String BOOTSTRAP = "net/forbric/kernel/mixin/KernelMixinBootstrap";

	/**
	 * The off switches a stage's weave tests flip: constant {@code properties} declared by {@code owner} — the stage's
	 * own class unless the stage stands down on another class's switch. One per behaviour of the stage a test covers;
	 * none is an audit with no switch.
	 */
	record Switch(List<String> properties, String owner) {
		static Switch own(String... properties) {
			return new Switch(List.of(properties), null);
		}
	}

	/** stage (simple class name) -> the switch its weave test flips. The comment names the test. */
	static final Map<String, Switch> WOVEN = Map.ofEntries(
			// KernelGuestMixinAdapterWeaveTest; NativeAbsentTargetsWeaveTest flips NativeAbsentTargets' switch, which the
			// adapter's verdict reads, and this one as its native control.
			Map.entry("KernelGuestMixinAdapter", Switch.own("forbric.guestMixinAdapter")),
			Map.entry("FinalMixinApplications", Switch.own()), // WeaveHarnessSelfTest, MixinOutcomeWeaveTest
			// MixinOutcomeWeaveTest; the redirect of a widened static call in MixinAtWidenedRedirectWeaveTest.
			Map.entry("MixinAtWidenedCall", Switch.own("forbric.mixinAtWiden", "forbric.mixinAtWidenRedirect")),
			Map.entry("MixinStubRebind", Switch.own("forbric.mixinStubRebind")),
			// The whole retarget, and R3's census of carrier renames: MixinRetargetWeaveTest; its left exit:
			// MixinRetargetSleepPieceWeaveTest; its move into a renamed body nothing calls: MixinRetargetUncalledBodyWeaveTest;
			// R7 (a vanilla method the carrier replaced): MixinRetargetReplacedCallWeaveTest.
			Map.entry("MixinRetarget", Switch.own("forbric.mixinRetarget", "forbric.mixinRetarget.renameCensus",
					"forbric.mixinRetarget.renameCensus.leftExit", "forbric.mixinRetarget.renameCensus.uncalled",
					"forbric.mixinRetarget.replacedCall")),
			// The subtype pair in MixinSubtypeOwnerRetargetWeaveTest, the widened field in MixinRetypedFieldOwnerWeaveTest.
			Map.entry("MixinSubtypeOwnerRetarget", Switch.own("forbric.mixinSubtypeOwner", "forbric.mixinSubtypeOwner.retypedField")),
			Map.entry("MixinWrapOperationShim", Switch.own("forbric.wrapOperationShim")),
			Map.entry("MixinCollectionSourceAdapter", Switch.own("forbric.mixinCollectionSources")), // MixinCollectionSourceWeaveTest
			Map.entry("MixinSharedResultTransport", Switch.own("forbric.mixinSharedResults")), // MixinSharedResultTransportWeaveTest
			Map.entry("MixinDefaultPredicateAdapter", Switch.own("forbric.mixinDefaultPredicates")), // MixinDefaultPredicateAdapterWeaveTest
			Map.entry("MixinDefaultPredicateTransport", Switch.own("forbric.mixinDefaultPredicates")), // MixinDefaultPredicateTransportWeaveTest
			Map.entry("MixinReturnDecorationAdapter", Switch.own("forbric.mixinReturnDecorations")), // MixinReturnDecorationWeaveTest
			Map.entry("MixinDefaultCallbackTransport", Switch.own("forbric.mixinDefaultCallbacks")), // MixinDefaultCallbackTransportWeaveTest
			Map.entry("MixinNativePredicateSeam", Switch.own("forbric.nativePredicateSeams")), // MixinNativePredicateSeamWeaveTest
			Map.entry("MixinSharedPredicateSeam", Switch.own("forbric.sharedPredicateSeams")), // MixinSharedPredicateSeamWeaveTest
			Map.entry("MixinCrossHostPredicateIsland", Switch.own("forbric.crossHostPredicateIslands")), // MixinCrossHostPredicateIslandWeaveTest
			Map.entry("MixinPredicateDelegateAdapter", Switch.own("forbric.mixinPredicateDelegates")), // MixinPredicateDelegateWeaveTest
			Map.entry("MixinResourceContinuationAdapter", Switch.own("forbric.mixinResourceContinuations")), // MixinResourceContinuationWeaveTest
			Map.entry("MixinNullableCompositeCallback", Switch.own("forbric.mixinNullableCompositeCallbacks")), // MixinNullableCompositeWeaveTest
			Map.entry("MixinDecodeScopeAdapter", Switch.own("forbric.decodeSourceScopes")), // MixinDecodeScopeWeaveTest
			Map.entry("MixinUnusedArgumentObserverAdapter", Switch.own("forbric.mixinUnusedArgumentObservers")), // MixinUnusedArgumentObserverWeaveTest
			Map.entry("MixinRelocatedCall", Switch.own("forbric.mixinRelocatedCall")),
			// soften() in MixinLocalsCaptureWeaveTest, softenRequirements() in MixinRequireFailSoftWeaveTest.
			Map.entry("MixinLocalsCapture", Switch.own("forbric.localsFailSoft", "forbric.requireFailSoft")),
			Map.entry("MixinAtShape", Switch.own("forbric.mixinAtShape")),
			Map.entry("MixinOverloadPin", Switch.own("forbric.mixinOverloadPin")),
			Map.entry("MixinMergedTwin", Switch.own("forbric.mixinMergedTwins")),
			// MixinNativeTail stands down with VanillaEarlyReturns, whose split its test puts on the run's pre-Mixin chain.
			Map.entry("MixinNativeTail", new Switch(List.of("forbric.vanillaEarlyReturns"), "net.forbric.kernel.transform.VanillaEarlyReturns")),
			Map.entry("MixinHandlerShim", Switch.own("forbric.mixinHandlerShim")),
			Map.entry("MixinAnonymousRetarget", Switch.own("forbric.mixinAnonymousDrift")),
			Map.entry("InterfaceDefaultConflictRepair", Switch.own("forbric.defaultConflictRepair")),
			Map.entry("BarrelRollCameraAdapter", Switch.own("forbric.barrelRollCamera")),
			Map.entry("ReplacedCallRedirects", Switch.own("forbric.replacedCallRedirects")), // ReplacedCallRedirectsWeaveTest
			Map.entry("MixinTwinRebind", Switch.own("forbric.mixinTwinRebind")), // MixinTwinRebindWeaveTest
			Map.entry("ThinnedCallOrdinals", Switch.own("forbric.thinnedCallOrdinals")), // ThinnedCallOrdinalsWeaveTest
			Map.entry("GuiItemCaptureMixinAdapter", Switch.own("forbric.guiItemCaptureAnchor")),
			Map.entry("KernelClientHookMixinAnchors", Switch.own("forbric.clientHookMixinAnchors")),
			Map.entry("MixinShearsRelay", Switch.own("forbric.shearsRelay")),
			Map.entry("InsertedLambdaArgumentShim", Switch.own("forbric.insertedLambdaArguments")),
			Map.entry("CarpetMixinAdapter", Switch.own("forbric.carpetMixins")),
			// CarpetFluidMixinAdapter stands down with CarpetMixinAdapter's switch, which both read through its enabled().
			Map.entry("CarpetFluidMixinAdapter", new Switch(List.of("forbric.carpetMixins"), "net.forbric.kernel.mixin.CarpetMixinAdapter")),
			Map.entry("NativeCoremodParity", Switch.own("forbric.flowerPotRepair")), // NativeCoremodParityWeaveTest
			Map.entry("PostMixinFixups", Switch.own("forbric.postMixinFixups")), // PostMixinFixupsWeaveTest
			Map.entry("PostMixinCodecReturnArbitration", Switch.own("forbric.postMixinCodecArbitration")), // PostMixinCodecReturnArbitrationWeaveTest
			Map.entry("PostMixinCallbackPriority", Switch.own()), // MixinNullableCompositeWeaveTest's absent installed-callback control
			// An audit with no switch: ForgeTransferShapeAuditWeaveTest's control is an unreviewed twin in the same run.
			Map.entry("ForgeTransferShapeAudit", Switch.own()),
			Map.entry("FabricRegistryInitializationMixinAdapter", Switch.own("forbric.fabricRegistryInitialization")),
			// FabricFreezeHookMixinAdapter stands down with FabricFreezePointInjector, whose hooks its test puts on the pre-Mixin chain.
			Map.entry("FabricFreezeHookMixinAdapter", new Switch(List.of("forbric.fabricFreezePoint"), "net.forbric.kernel.transform.FabricFreezePointInjector")),
			Map.entry("FabricCreativePagerMixinAdapter", Switch.own("forbric.fabricCreativeKeyboard")),
			Map.entry("FabricServerLanguageMixinAdapter", Switch.own("forbric.fabricServerLanguage")),
			Map.entry("FabricSoundMixinAdapter", new Switch(List.of("forbric.fabricSoundContracts"), "net.forbric.kernel.transform.FabricSoundContractTransformer")),
			Map.entry("FabricEnchantmentMixinAdapter", new Switch(List.of("forbric.fabricItemContracts"), "net.forbric.kernel.transform.FabricItemContractTransformer")),
			Map.entry("FabricRegistryLoaderMixinAdapter", Switch.own("forbric.fabricRegistryLoader")),
			Map.entry("FabricFluidFlowMixinAdapter", Switch.own("forbric.fabricFluidFlow")),
			Map.entry("FabricBlockStateCodecMixinAdapter", Switch.own("forbric.blockStateModelFormats")),
			Map.entry("FabricEntityMixinAnchors", Switch.own("forbric.fabricEntityAnchors")),
			Map.entry("FabricClientMixinAnchors", Switch.own("forbric.fabricClientAnchors")),
			Map.entry("FabricBlockBreakMixinAdapter", Switch.own("forbric.fabricBlockBreak")),
			Map.entry("FabricSectionCompilerMixinAdapter", Switch.own("forbric.fabricChunkRendering")),
			Map.entry("CreateStructureMixinAdapter", Switch.own("forbric.createStructureMixin")),
			Map.entry("CreateKeyboardMixinAdapter", Switch.own("forbric.createKeyboardMixin")),
			Map.entry("ContinuitySpriteMixinAdapter", Switch.own("forbric.continuitySpriteSources")),
			Map.entry("CreateFluidMixinAdapter", Switch.own("forbric.createFluidMixins")),
			Map.entry("CreateInjectionAdapters", Switch.own("forbric.createInjectionAdapters")),
			Map.entry("CreateInteractionMixinAdapters", Switch.own("forbric.createInteractionMixins")),
			Map.entry("CreateContextualBlockAdapters", Switch.own("forbric.createContextualBlocks")),
			Map.entry("CreateEntitySoundMixinAdapter", Switch.own("forbric.createEntitySounds")),
			Map.entry("CreateBreathingMixinAdapter", Switch.own("forbric.createBreathingMixin")),
			Map.entry("CreateHudMixinAdapter", Switch.own("forbric.createHudMixin")),
			// The injectors Mixin rejects outright, taken out at the end of getClassNode: MixinRefusedBindingWeaveTest.
			Map.entry("GuestInjectorPruner", Switch.own("forbric.guestInjectorPruner.refused")));

	private static final String NO_SCENARIO = "no weave scenario yet; ClassNode-level tests only";
	/** Only shrinks. Every row is a stage whose output no CI test has yet run through the real weave. */
	static final Map<String, String> NOT_WOVEN_YET = notWovenYet(
			"FabricMiningMixinAdapter");

	@Test void everyPipelineStageIsWovenOrListedWithAReason() throws Exception {
		Set<String> configTime = configTimeStages();
		Set<String> preMixin = preMixinAdapters();
		Set<String> postMixin = postMixinStages();
		assertEquals(Set.of("KernelGuestMixinAdapter"), configTime, "the config-time stages changed; place the new one in a list");
		assertTrue(preMixin.size() >= 40, "the census could not read getClassNode's adapters: " + preMixin);
		assertEquals(Set.of("NativeCoremodParity", "PostMixinFixups", "InterfaceDefaultConflictRepair",
				"ForgeTransferShapeAudit", "PostMixinCodecReturnArbitration", "PostMixinCallbackPriority"), postMixin, "the post-Mixin stages changed; place the new one in a list");

		Set<String> stages = new TreeSet<>(configTime);
		stages.addAll(preMixin);
		stages.addAll(postMixin);
		assertEquals(List.of(), problems(stages, WOVEN.keySet(), NOT_WOVEN_YET.keySet()));
		for (var woven : WOVEN.entrySet()) {
			for (String property : woven.getValue().properties()) assertSwitchBelongsTo(woven.getKey(), property, woven.getValue().owner());
		}

		long config = configTime.stream().filter(WOVEN::containsKey).count();
		long pre = preMixin.stream().filter(WOVEN::containsKey).count();
		long post = postMixin.stream().filter(WOVEN::containsKey).count();
		System.out.printf("weave coverage: config-time %d/%d, getClassNode %d/%d, post-Mixin %d/%d%n", config,
				configTime.size(), pre, preMixin.size(), post, postMixin.size());
	}

	/** The census must be able to see an undeclared stage, an overlap and a stale row, or it proves nothing. */
	@Test void theCensusCanFail() {
		Set<String> stages = new TreeSet<>(NOT_WOVEN_YET.keySet());
		stages.addAll(WOVEN.keySet());
		stages.add("BrandNewAdapter");
		assertEquals(List.of("undeclared: BrandNewAdapter"), problems(stages, WOVEN.keySet(), NOT_WOVEN_YET.keySet()));

		Set<String> both = new TreeSet<>(NOT_WOVEN_YET.keySet());
		both.add("MixinAtWidenedCall");
		stages.remove("BrandNewAdapter");
		assertEquals(List.of("both woven and allowlisted: MixinAtWidenedCall"), problems(stages, WOVEN.keySet(), both));

		Set<String> gone = new TreeSet<>(stages);
		gone.remove("MixinShearsRelay");
		assertEquals(List.of("woven but no longer a stage: MixinShearsRelay"), problems(gone, WOVEN.keySet(), NOT_WOVEN_YET.keySet()));

		// Whichever row is still allowlisted: the list only shrinks, so a row named here would leave it one day.
		String listed = NOT_WOVEN_YET.keySet().iterator().next();
		Set<String> unlisted = new TreeSet<>(stages);
		unlisted.remove(listed);
		assertEquals(List.of("allowlisted but no longer a stage: " + listed), problems(unlisted, WOVEN.keySet(), NOT_WOVEN_YET.keySet()));
	}

	static List<String> problems(Set<String> stages, Set<String> woven, Set<String> allowlisted) {
		List<String> problems = new java.util.ArrayList<>();
		for (String stage : stages) {
			if (woven.contains(stage) && allowlisted.contains(stage)) problems.add("both woven and allowlisted: " + stage);
			else if (!woven.contains(stage) && !allowlisted.contains(stage)) problems.add("undeclared: " + stage);
		}
		for (String row : allowlisted) if (!stages.contains(row)) problems.add("allowlisted but no longer a stage: " + row);
		for (String row : woven) if (!stages.contains(row)) problems.add("woven but no longer a stage: " + row);
		return problems;
	}

	/**
	 * A scenario cannot claim adapter X while flipping Y's switch: the switch must be one of X's own constants, or of
	 * the one class X is declared to stand down with.
	 */
	private static void assertSwitchBelongsTo(String stage, String property, String owner) throws Exception {
		Class<?> type = owner != null ? Class.forName(owner) : stageClass(stage);
		for (Field field : type.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
				field.setAccessible(true);
				if (property.equals(field.get(null))) return;
			}
		}
		fail(type.getSimpleName() + " declares no constant " + property + ", so the weave scenario for "
				+ stage + " does not switch it off");
	}

	private static Class<?> stageClass(String stage) throws ClassNotFoundException {
		try {
			return Class.forName("net.forbric.kernel.mixin." + stage);
		} catch (ClassNotFoundException notAnAdapter) {
			return Class.forName("net.forbric.kernel.transform." + stage); // a post-Mixin stage
		}
	}

	/**
	 * Every class getResourceAsStream hands a guest config's bytes to and takes back the entries to leave out — the
	 * rewrites the service makes itself (relaxation, the named suppression list) are not separate stages.
	 */
	static Set<String> configTimeStages() throws IOException {
		ClassNode service = read(SERVICE);
		MethodNode getResourceAsStream = service.methods.stream().filter(m -> m.name.equals("getResourceAsStream")
				&& m.desc.equals("(Ljava/lang/String;)Ljava/io/InputStream;")).findFirst().orElseThrow();
		Set<String> stages = new TreeSet<>();
		for (AbstractInsnNode insn : getResourceAsStream.instructions) {
			if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode call
					&& call.owner.startsWith("net/forbric/kernel/mixin/") && !call.owner.equals(SERVICE)
					&& call.desc.contains("[B") && call.desc.endsWith(")Ljava/util/List;")) {
				stages.add(call.owner.substring(call.owner.lastIndexOf('/') + 1));
			}
		}
		return stages;
	}

	/** Every class getClassNode hands a guest mixin's ClassNode to, the transform package's pruner included. */
	static Set<String> preMixinAdapters() throws IOException {
		ClassNode service = read(SERVICE);
		MethodNode getClassNode = service.methods.stream().filter(m -> m.name.equals("getClassNode")
				&& m.desc.equals("(Ljava/lang/String;ZI)Lorg/objectweb/asm/tree/ClassNode;")).findFirst().orElseThrow();
		Set<String> adapters = new TreeSet<>();
		for (AbstractInsnNode insn : getClassNode.instructions) {
			if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode call
					&& (call.owner.startsWith("net/forbric/kernel/mixin/") || call.owner.startsWith("net/forbric/kernel/transform/"))
					&& call.desc.contains("Lorg/objectweb/asm/tree/ClassNode;")) {
				adapters.add(call.owner.substring(call.owner.lastIndexOf('/') + 1));
			}
		}
		return adapters;
	}

	/** The stages of the (String, byte[]) -> byte[] lambda KernelMixinBootstrap.init installs after Mixin. */
	static Set<String> postMixinStages() throws IOException {
		ClassNode bootstrap = read(BOOTSTRAP);
		Set<String> stages = new TreeSet<>();
		for (MethodNode method : bootstrap.methods) {
			if (!method.name.startsWith("lambda$init$") || !method.desc.endsWith("Ljava/lang/String;[B)[B")) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && (call.owner.startsWith("net/forbric/kernel/transform/")
						|| call.owner.startsWith("net/forbric/kernel/mixin/")) && !call.owner.endsWith("/MixinWeaverSlot")) {
					stages.add(call.owner.substring(call.owner.lastIndexOf('/') + 1));
				}
			}
		}
		return stages;
	}

	private static Map<String, String> notWovenYet(String... stages) {
		Map<String, String> rows = new LinkedHashMap<>();
		for (String stage : stages) rows.put(stage, NO_SCENARIO);
		return rows;
	}

	private static ClassNode read(String internalName) throws IOException {
		try (InputStream in = WeaveCoverageCensusTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
			assertNotNull(in, internalName + " is not on the test classpath");
			ClassNode node = new ClassNode();
			new ClassReader(in).accept(node, 0);
			return node;
		}
	}
}
