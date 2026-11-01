package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * fabric-screen-api's wrap of the screen draw and spruceui's NeoForge wrap of {@code ClientHooks.extractScreen}, the real
 * mixins, on NeoForge's own {@code Gui}: the inline that would put the screen draw back into {@code extractRenderState}
 * removes spruceui's call, so it is declined while it is planned, with spruceui's config not read yet (a boot reads the
 * Fabric configs first), and the screen draw is carried by the operation seam, which leaves NeoForge's call in place.
 * JourneyMap's NeoForge build then adds a HEAD callback to {@code ClientHooks.extractScreen}, the gateway the seam
 * instruments: an addition the seam's witness accepts, where a change to what Forbric instrumented is still refused.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class NativeCallInliningRegisteredMixinsStagedTest {
	private static final String GUI = "net/minecraft/client/gui/Gui";
	private static final String GATEWAY = "Lnet/neoforged/neoforge/client/ClientHooks;extractScreen(Lnet/minecraft/client/gui/screens/Screen;"
			+ "Ljava/util/Stack;Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";
	private static final String LDL = "lambdynamiclights-4.12.2+26.2.jar";
	private static final String SPRUCE = "META-INF/jars/spruceui-11.0.0+26.2.jar";
	private static final String SPRUCE_GUI = "dev/lambdaurora/spruceui/mixin/neoforge/GuiMixin";

	/** Serves the staged base and the NeoForge carrier, where the inline reads the bodies it may put back. */
	private final net.forbric.kernel.classloading.ForbricClassLoader loader = stagedLoader();

	private net.forbric.kernel.classloading.ForbricClassLoader stagedLoader() {
		TestFixtures.requireFiles(Fixture.STAGED, "the staged base and NeoForge carrier", TestFixtures.stagedBase(), TestFixtures.neoCarrier());
		try {
			return new net.forbric.kernel.classloading.ForbricClassLoader(new java.net.URL[] {TestFixtures.stagedBase().toUri().toURL(),
					TestFixtures.neoCarrier().toUri().toURL()}, getClass().getClassLoader());
		} catch (java.net.MalformedURLException impossible) {
			throw new AssertionError(impossible);
		}
	}

	@AfterEach void clear() throws Exception {
		NativeCallInlining.release(loader);
		MixinOperationSeamTransport.release(loader);
		loader.close();
		System.clearProperty(NativeCallInlining.REGISTERED_PROPERTY);
	}

	@Test void spruceuisAnchorDeclinesTheInlineBeforeItsConfigIsReadAndTheSeamCarriesTheScreenDraw() throws Exception {
		ClassNode fabric = fabricScreen(), spruce = spruceGui(), gui = StagedFabricMixinFixture.game(GUI, false);
		assertEquals(1, gatewayCalls(spruce, gui), "NeoForge's Gui makes the call spruceui wraps");
		assertEquals(0, NativeCallInlining.adapt(fabric, code(gui), NativeCallTestEvidence.staged(), loader, () -> List.of(fabric, spruce)),
				"spruceui anchors on ClientHooks.extractScreen, the call the inline would remove");
		assertNotNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(fabric, "onExtractGui")), "the wrap is untouched by the inline pass");
		assertEquals(1, MixinOperationSeamTransport.adapt(fabric, code(gui), NativeCallTestEvidence.staged(), loader),
				"the operation seam runs fabric-screen-api's handler around the draw inside ClientHooks");
		MethodNode scope = fabric.methods.stream().filter(m -> m.name.endsWith("$invoke$scope") && MixinFit.injectorOf(m) != null).findFirst()
				.orElseThrow();
		assertEquals(GATEWAY, MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(scope)).getFirst(), "target"),
				"its scope is NeoForge's call, which stays for spruceui too");
		assertEquals(1, gatewayCalls(spruce, gui), "and NeoForge's call is still there");
	}

	/**
	 * The seam instruments NeoForge's real {@code ClientHooks.extractScreen}; JourneyMap's HEAD callback (as Mixin
	 * merges an {@code @Inject} at HEAD of a static method: a new CallbackInfo in a new local, the arguments, the merged
	 * handler) only adds code around it, so the witness takes the final body. Removing the instrumented call, writing a
	 * parameter, an unattributed change, or the switch off are refused.
	 */
	@Test void journeyMapsHeadCallbackIntoTheInstrumentedGatewayIsAnAddition() throws Exception {
		ClassNode fabric = fabricScreen(), spruce = spruceGui(), gui = StagedFabricMixinFixture.game(GUI, false);
		NativeCallInlining.adapt(fabric, code(gui), NativeCallTestEvidence.staged(), loader, () -> List.of(fabric, spruce));
		assertEquals(1, MixinOperationSeamTransport.adapt(fabric, code(gui), NativeCallTestEvidence.staged(), loader));
		ClassNode hooks = MixinFit.parse(MixinOperationSeamTransport.transform(loader, HOOKS.replace('/', '.'),
				StagedFabricMixinFixture.bytes(CarpetMixinAdapterTest.target(HOOKS))));
		MethodNode instrumented = copy(gateway(hooks));
		assertEquals(1, java.util.Arrays.stream(instrumented.instructions.toArray()).filter(i -> i instanceof org.objectweb.asm.tree.MethodInsnNode call
				&& call.name.startsWith("forbric$selected$")).count(), "the gateway forwards to the screen draw through the seam");

		MethodNode handler = journeyMapHandler(hooks);
		MethodNode added = gateway(hooks);
		added.instructions.insert(headCallback(added, handler));
		java.util.Set<String> source = java.util.Set.of(fabric.name.replace('/', '.'));
		MixinOperationSeamTransport.Additions accepted = MixinOperationSeamTransport.additions(instrumented, added, hooks, source);
		assertNull(accepted.refused(), "JourneyMap only added code around the instrumented call");
		assertEquals(List.of(JOURNEYMAP), accepted.mixins(), "and the added code is JourneyMap's");
		assertTrue(String.valueOf(MixinOperationSeamTransport.additions(instrumented, added, hooks, java.util.Set.of(JOURNEYMAP)).refused())
				.contains("which no other mixin merged"), "added code calling the source mixin's own handler is not another mixin's");

		MethodNode unmarked = copy(instrumented);
		MethodNode plain = journeyMapHandler(hooks);
		plain.name = "forbric$test$unmarkedHandler";
		plain.visibleAnnotations = null;
		unmarked.instructions.insert(headCallback(unmarked, plain));
		assertTrue(String.valueOf(MixinOperationSeamTransport.additions(instrumented, unmarked, hooks, source).refused())
				.contains("which no other mixin merged"), "an addition calling a method no mixin merged is refused");
		MethodNode dropped = copy(added);
		org.objectweb.asm.tree.AbstractInsnNode selected = java.util.Arrays.stream(dropped.instructions.toArray())
				.filter(i -> i instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.startsWith("forbric$selected$")).findFirst().orElseThrow();
		dropped.instructions.set(selected, new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, HOOKS, "extractScreenInternal",
				((org.objectweb.asm.tree.MethodInsnNode) selected).desc, false));
		assertTrue(String.valueOf(MixinOperationSeamTransport.additions(instrumented, dropped, hooks, source).refused()).contains("is gone"),
				"the instrumented call replaced by another is refused");
		MethodNode reassigned = copy(added);
		org.objectweb.asm.tree.InsnList write = new org.objectweb.asm.tree.InsnList();
		write.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ACONST_NULL));
		write.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ASTORE, 0));
		reassigned.instructions.insert(write);
		assertTrue(String.valueOf(MixinOperationSeamTransport.additions(instrumented, reassigned, hooks, source).refused()).contains("writes local 0"),
				"an addition that reassigns the screen is refused");
		System.setProperty(MixinOperationSeamTransport.ADDITIONS_PROPERTY, "off");
		try {
			assertEquals("", MixinOperationSeamTransport.additions(instrumented, added, hooks, source).refused(), "the switch off refuses every change");
		} finally {
			System.clearProperty(MixinOperationSeamTransport.ADDITIONS_PROPERTY);
		}

		// A change no mixin owns that only adds code, in a body that already calls a method another mixin merged (here
		// extractScreenInternal as another mod's @Overwrite would leave it): the instrumented code's own calls do not make
		// the addition anyone's.
		MethodNode internal = hooks.methods.stream().filter(m -> m.name.equals("extractScreenInternal")).findFirst().orElseThrow();
		AnnotationNode overwrite = new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
		overwrite.values = new java.util.ArrayList<>(List.of("mixin", "org.example.OverwriteMixin", "priority", 1000, "sessionId", "test"));
		internal.visibleAnnotations = new java.util.ArrayList<>(List.of(overwrite));
		MethodNode unattributed = copy(instrumented);
		org.objectweb.asm.tree.InsnList observe = new org.objectweb.asm.tree.InsnList();
		observe.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
		observe.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.POP));
		unattributed.instructions.insert(observe);
		assertEquals(List.of("org.example.OverwriteMixin"), SeamDeclines.contributors(hooks, unattributed, source),
				"the whole body names the overwrite's mixin");
		assertEquals("no other mixin's handler is in what was added",
				MixinOperationSeamTransport.additions(instrumented, unattributed, hooks, source).refused(),
				"but the added code calls no other mixin's handler, so it is refused");
	}

	/** The defect: deciding on the mixins planned so far, the inline is planned and the seam is not tried. */
	@Test void decidedOnTheMixinsPlannedSoFarTheInlineIsPlanned() throws Exception {
		ClassNode spruce = spruceGui(), gui = StagedFabricMixinFixture.game(GUI, false);
		assertEquals(1, NativeCallInlining.adapt(fabricScreen(), code(gui), NativeCallTestEvidence.staged(), loader, null),
				"no list of registered mixins: spruceui's anchor is not known yet");
		NativeCallInlining.release(loader);
		System.setProperty(NativeCallInlining.REGISTERED_PROPERTY, "off");
		assertEquals(1, NativeCallInlining.adapt(fabricScreen(), code(gui), NativeCallTestEvidence.staged(), loader, () -> List.of(spruce)),
				"the switch off: the list is not read");
	}

	/** A registered mixin that anchors nothing the inline removes does not stop it (spruceui's common GuiMixin, on setScreen). */
	@Test void aRegisteredMixinAnchoringElsewhereLeavesTheInlinePlanned() throws Exception {
		ClassNode common = spruceClass("dev/lambdaurora/spruceui/mixin/GuiMixin"), gui = StagedFabricMixinFixture.game(GUI, false);
		assertEquals(1, NativeCallInlining.adapt(fabricScreen(), code(gui), NativeCallTestEvidence.staged(), loader, () -> List.of(common)));
	}

	private static final String HOOKS = "net/neoforged/neoforge/client/ClientHooks";
	private static final String JOURNEYMAP = "journeymap.common.mixin.client.ClientHooksMixin";

	private static MethodNode copy(MethodNode method) {
		MethodNode result = new MethodNode(method.access, method.name, method.desc, method.signature, method.exceptions.toArray(String[]::new));
		method.accept(result);
		return result;
	}

	private static MethodNode gateway(ClassNode hooks) {
		return hooks.methods.stream().filter(m -> (m.name + m.desc).equals(GATEWAY.substring(GATEWAY.indexOf(';') + 1))).findFirst().orElseThrow();
	}

	/** JourneyMap's preLayerScreenEvent as Mixin merges it into ClientHooks: private static, marked with its mixin. */
	private static MethodNode journeyMapHandler(ClassNode hooks) {
		MethodNode handler = new MethodNode(org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_STATIC,
				"handler$zzz000$journeymap$preLayerScreenEvent", "(Lnet/minecraft/client/gui/screens/Screen;Ljava/util/Stack;"
				+ "Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIFLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		handler.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
		AnnotationNode merged = new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
		merged.values = new java.util.ArrayList<>(List.of("mixin", JOURNEYMAP, "priority", 1000, "sessionId", "test"));
		handler.visibleAnnotations = new java.util.ArrayList<>(List.of(merged));
		hooks.methods.add(handler);
		return handler;
	}

	/** What Mixin puts at HEAD of a static method for a non-cancellable {@code @Inject}. */
	private static org.objectweb.asm.tree.InsnList headCallback(MethodNode target, MethodNode handler) {
		org.objectweb.asm.tree.InsnList code = new org.objectweb.asm.tree.InsnList();
		String info = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
		int local = target.maxLocals;
		code.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.NEW, info));
		code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.DUP));
		code.add(new org.objectweb.asm.tree.LdcInsnNode("extractScreen"));
		code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_0));
		code.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESPECIAL, info, "<init>", "(Ljava/lang/String;Z)V", false));
		code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ASTORE, local));
		int slot = 0;
		for (org.objectweb.asm.Type argument : org.objectweb.asm.Type.getArgumentTypes(target.desc)) {
			code.add(new org.objectweb.asm.tree.VarInsnNode(argument.getOpcode(org.objectweb.asm.Opcodes.ILOAD), slot));
			slot += argument.getSize();
		}
		code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, local));
		code.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, HOOKS, handler.name, handler.desc, false));
		target.maxLocals = local + 1;
		return code;
	}

	private static Function<String, ClassNode> code(ClassNode gui) {
		return name -> name.equals(gui.name) ? gui : CarpetMixinAdapterTest.target(name);
	}

	private static int gatewayCalls(ClassNode spruce, ClassNode gui) {
		MethodNode handler = spruce.methods.stream().filter(m -> MixinFit.injectorOf(m) != null).findFirst().orElseThrow();
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst();
		assertEquals(GATEWAY, MixinFit.value(at, "target"));
		return MixinCallbackShape.calls(at, MixinTargetSelectors.one(handler, gui)).size();
	}

	private static ClassNode fabricScreen() throws Exception {
		ClassNode source = StagedFabricMixinFixture.mixin("fabric-screen-api-v1", "net/fabricmc/fabric/mixin/screen/GuiMixin");
		MixinStubRebind.noteEcosystem(source.name, Ecosystem.FABRIC);
		return source;
	}

	private static ClassNode spruceGui() throws Exception {
		return spruceClass(SPRUCE_GUI);
	}

	/** A class of the spruceui jar LambDynamicLights nests, from the client pack the gates run. */
	private static ClassNode spruceClass(String name) throws Exception {
		Path jar = Path.of("run/client-merged-pack/mods", LDL);
		TestFixtures.requireFile(Fixture.THIRD_PARTY, jar, "LambDynamicLights (nests spruceui) from the client pack");
		try (ZipFile outer = new ZipFile(jar.toFile())) {
			ZipEntry nested = outer.getEntry(SPRUCE);
			assertNotNull(nested, SPRUCE + " in " + jar);
			try (ZipInputStream inner = new ZipInputStream(outer.getInputStream(nested))) {
				for (ZipEntry entry; (entry = inner.getNextEntry()) != null; ) {
					if (entry.getName().equals(name + ".class")) {
						ClassNode node = MixinFit.parse(inner.readAllBytes());
						MixinStubRebind.noteEcosystem(node.name, Ecosystem.NEOFORGE);
						return node;
					}
				}
			}
		}
		throw new AssertionError(name + " not in " + SPRUCE);
	}
}
