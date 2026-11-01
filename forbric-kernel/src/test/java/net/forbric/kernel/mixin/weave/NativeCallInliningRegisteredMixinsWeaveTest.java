package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinOperationSeamTransport;
import net.forbric.kernel.mixin.NativeCallInlining;

/**
 * A Fabric wrap of the screen draw and a NeoForge mod's wrap of NeoForge's layered draw, on a Gui in NeoForge's shape, both
 * run under real Mixin: the NeoForge mod's events around the whole layered draw, the Fabric events around the top screen
 * only (the shape of fabric-screen-api with spruceui, NeoForge build, in one pack), the inline of
 * {@code ClientHooks.extractScreen} declined while it is planned and never withdrawn.
 *
 * <p>A third mod, in the shape of JourneyMap's NeoForge build, adds a HEAD callback to {@code ClientHooks.extractScreen}
 * itself, the gateway the operation seam instruments: the seam still holds, since that mod only added code around what
 * Forbric instrumented ({@code -Dforbric.operationSeams.additions=off} declines it there: the second control).
 *
 * <p>The Fabric config is read first, as a boot registers Fabric configs ahead of the NeoForge family's. With
 * {@code -Dforbric.nativeCallInlining.registeredMixins=off} the inline is planned for the Fabric wrap, then withdrawn when
 * {@code Gui} is defined because the NeoForge mod anchors on that call: the control. This harness reads every config
 * before Mixin prepares any mixin, so the Fabric mixin's second planning (when Mixin reads it) already knows the NeoForge
 * mod and the operation seam still carries the wrap there; in a boot, where a transformation prepares the configs read so
 * far before the NeoForge family's are read, it does not, and the wrap is lost (gate-m9's screen-draw check:
 * NativeCallInliningRegisteredMixinsStagedTest pins that order with the real mixins).
 */
class NativeCallInliningRegisteredMixinsWeaveTest {
	private static final Path SCREEN_SOURCES = Path.of("src/test/resources/weave/fabricclientanchors");
	private static final Path NEO_SOURCES = Path.of("src/test/resources/weave/neoscreengateway");
	private static final String SCREEN_CONFIG = "fabricclientanchors-screen.mixins.json";
	private static final String NEO_CONFIG = "neoscreengateway.mixins.json";
	private static final String SCREEN_MOD = "fabricscreen";
	private static final String NEO_MOD = "neoscreen";
	private static final String HOOKS_CONFIG = "neoscreenhooks.mixins.json";
	private static final String HOOKS_MOD = "neohooks";
	private static final String GUI = "net/minecraft/client/gui/Gui";
	private static final String BOTH = "frame=[neo-before:inventory, jm-head:inventory, layer:toast-layer, before:inventory@3,4,0.5, "
			+ "screen:inventory, after:inventory, neo-after:inventory]";
	/** The seam declined at the instrumented gateway: NeoForge's mods still draw around it, the Fabric events do not. */
	private static final String DECLINED = "frame=[neo-before:inventory, jm-head:inventory, layer:toast-layer, screen:inventory, "
			+ "neo-after:inventory]";
	private static final String ADDED = "only added code around them; the operation seam still holds";
	private static final String WITHDRAWN = "which the inline would remove";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;
	private static WeaveHarness.Result additionsOff;

	@BeforeAll static void weaveBoth() throws Exception {
		List<Path> sources = new ArrayList<>(sources(SCREEN_SOURCES));
		sources.addAll(sources(NEO_SOURCES));
		fixture = WeaveHarness.fixture(work, "registeredmixins", sources,
				Map.of(SCREEN_CONFIG, SCREEN_SOURCES.resolve(SCREEN_CONFIG), NEO_CONFIG, NEO_SOURCES.resolve(NEO_CONFIG),
						HOOKS_CONFIG, NEO_SOURCES.resolve(HOOKS_CONFIG)));
		fixture = NativeWeaveReferences.with(work, fixture, Map.of(GUI, vanillaGui()));
		adapted = run("adapted", Map.of(NativeCallInlining.REGISTERED_PROPERTY, "on"));
		off = run("off", Map.of(NativeCallInlining.REGISTERED_PROPERTY, "off"));
		additionsOff = run("additions-off", Map.of(MixinOperationSeamTransport.ADDITIONS_PROPERTY, "off"));
	}

	@Test void bothWrapsRunEachAroundTheDrawItsOwnLoaderWraps() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings: " + adapted.findings());
		assertTrue(adapted.printed("mixin org.example.neoscreen.mixin.GuiMixin anchors an injector on "
				+ "extractScreen(Lnet/minecraft/client/gui/screens/Screen;Ljava/util/Stack;Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V, "
				+ "which the inline removes"), "the inline is declined while it is planned — " + adapted.describe());
		assertFalse(adapted.printed(WITHDRAWN), "and never withdrawn later — " + adapted.describe());
		assertFalse(adapted.printed("is made in net.minecraft.client.gui.Gui.extractRenderState again"), adapted.describe());
		assertTrue(adapted.printed("the final body of net.neoforged.neoforge.client.ClientHooks.extractScreen(Lnet/minecraft/client/gui/screens/Screen;"
				+ "Ljava/util/Stack;Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V keeps every instruction Forbric instrumented for it, in order, "
				+ "and mixin org.example.neoscreen.hooks.ClientHooksMixin " + ADDED), "the HEAD callback is named as an addition — " + adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted, GUI, fixture);
	}

	/** The second control: with additions off, the HEAD callback into the gateway declines the seam, naming that mixin. */
	@Test void withAdditionsOffTheGatewaysHeadCallbackDeclinesTheSeam() throws Exception {
		assertTrue(additionsOffHolds(additionsOff), additionsOff.describe() + "\nfindings: " + additionsOff.findings());
		WeaveHarness.assertWovenAndVerified(additionsOff, GUI, fixture);
	}

	@Test void withTheSwitchOffTheInlineIsPlannedThenWithdrawn() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings: " + off.findings());
		WeaveHarness.assertWovenAndVerified(off, GUI, fixture);
	}

	/** The run and its control must be told apart by the very predicates the test uses on them. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapted predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
		assertTrue(additionsOffHolds(additionsOff) && !additionsOffHolds(adapted) && !adaptedHolds(additionsOff),
				"additions predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + BOTH) && losses(run, SCREEN_MOD).isEmpty() && losses(run, NEO_MOD).isEmpty()
				&& losses(run, HOOKS_MOD).isEmpty() && !run.printed(WITHDRAWN) && run.printed(ADDED) && !run.printed("source operation skipped at");
	}

	private static boolean additionsOffHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + DECLINED) && !run.printed(ADDED) && run.output().lines().filter(line ->
				line.contains("source operation skipped at") && line.contains("changed by mixin org.example.neoscreen.hooks.ClientHooksMixin")).count() == 1
				&& losses(run, NEO_MOD).isEmpty() && losses(run, HOOKS_MOD).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printed(WITHDRAWN) && run.printed("is made in net.minecraft.client.gui.Gui.extractRenderState again")
				&& losses(run, NEO_MOD).isEmpty();
	}

	/** Every required injector of the mod the runtime audit reports, at any confidence. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run, String mod) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.modId().equals(mod) && f.required()).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, List.of(
				new WeaveHarness.Config(SCREEN_CONFIG, SCREEN_MOD, Ecosystem.FABRIC),
				new WeaveHarness.Config(NEO_CONFIG, NEO_MOD, Ecosystem.NEOFORGE),
				new WeaveHarness.Config(HOOKS_CONFIG, HOOKS_MOD, Ecosystem.NEOFORGE)),
				List.of(), EnvType.CLIENT, "fixture.fabricclientanchors.Probe", "run", properties);
	}

	/** Vanilla's Gui, as the Fabric mod was compiled against: the screen drawn in extractRenderState itself. */
	private static byte[] vanillaGui() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, GUI, null, "java/lang/Object", null);
		writer.visitField(Opcodes.ACC_PUBLIC, "screen", "Lnet/minecraft/client/gui/screens/Screen;", null, null).visitEnd();
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "extractRenderState", "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
				null, null);
		method.visitCode();
		method.visitTypeInsn(Opcodes.NEW, "net/minecraft/client/gui/GuiGraphicsExtractor");
		method.visitInsn(Opcodes.DUP);
		method.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/client/gui/GuiGraphicsExtractor", "<init>", "()V", false);
		method.visitVarInsn(Opcodes.ASTORE, 1);
		method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitFieldInsn(Opcodes.GETFIELD, GUI, "screen", "Lnet/minecraft/client/gui/screens/Screen;");
		method.visitVarInsn(Opcodes.ALOAD, 1);
		method.visitVarInsn(Opcodes.ILOAD, 2);
		method.visitVarInsn(Opcodes.ILOAD, 3);
		method.visitVarInsn(Opcodes.FLOAD, 4);
		method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/gui/screens/Screen", "extractRenderStateWithTooltipAndSubtitles",
				"(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", false);
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(0, 0);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static List<Path> sources(Path root) throws Exception {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}
}
