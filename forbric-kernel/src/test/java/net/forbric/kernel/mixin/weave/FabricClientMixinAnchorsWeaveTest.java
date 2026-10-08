package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.FabricClientMixinAnchors;
import net.forbric.kernel.mixin.MixinOperationSeamTransport;
import org.objectweb.asm.*;

/** Source screen events execute at the actual top draw after layers; a no-op renderer redirect retains its contract. */
class FabricClientMixinAnchorsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/fabricclientanchors");
	private static final String SCREEN_CONFIG = "fabricclientanchors-screen.mixins.json";
	private static final String RENDER_CONFIG = "fabricclientanchors-render.mixins.json";
	private static final String SCREEN_MOD = "fabricscreen";
	private static final String RENDER_MOD = "fabricrender";
	private static final String GUI = "net/minecraft/client/gui/Gui";
	private static final String RENDERER = "net/minecraft/client/renderer/LevelRenderer";
	private static final String ANCHORED = "frame=[layer:toast-layer, before:inventory@3,4,0.5, screen:inventory, after:inventory] destroy=[]";
	private static final String UNANCHORED = "frame=[layer:toast-layer, screen:inventory] destroy=[vanilla-part]";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weaveBoth() throws Exception {
		fixture = WeaveHarness.fixture(work, "fabricclientanchors", sources(),
				Map.of(SCREEN_CONFIG, SOURCES.resolve(SCREEN_CONFIG), RENDER_CONFIG, SOURCES.resolve(RENDER_CONFIG)));
		fixture = NativeWeaveReferences.with(work,fixture,Map.of(GUI,originalGui()));
		adapted = run("adapted", "on");
		off = run("off", "off");
	}

	@Test void theSourceScreenEventsBracketOnlyTheTopDrawAndTheRedirectBinds() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings: " + adapted.findings());
		for (String target : List.of(GUI, RENDERER)) {
			assertTrue(WeaveHarness.hasMergedMethod(adapted.defined(target)), target + " — " + adapted.describe());
			WeaveHarness.assertWovenAndVerified(adapted, target, fixture);
		}
	}

	@Test void withTheSwitchOffNeitherHookRunsAndBothAreReported() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings: " + off.findings());
		for (String target : List.of(GUI, RENDERER)) WeaveHarness.assertWovenAndVerified(off, target, fixture);
	}

	/** The run and its control must be told apart by the very predicates the test uses on them. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapted predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + ANCHORED) && losses(run, SCREEN_MOD).isEmpty()
				&& losses(run, RENDER_MOD).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		List<WeaveHarness.Finding> screen = losses(run, SCREEN_MOD), render = losses(run, RENDER_MOD);
		return run.printed(WeaveHarnessMain.DONE + " " + UNANCHORED)
				&& screen.size() == 1 && screen.get(0).id().contains("#onExtractGui")
				&& render.size() == 1 && render.get(0).id().contains("#cancelCollectParts");
	}

	/** Every required injector of the mod the runtime audit reports, at any confidence. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run, String mod) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.modId().equals(mod)
				&& f.required()).toList();
	}

	private static WeaveHarness.Result run(String label, String adapter) throws Exception {
		return WeaveHarness.run(work, label, fixture, List.of(
				new WeaveHarness.Config(SCREEN_CONFIG, SCREEN_MOD, Ecosystem.FABRIC),
				new WeaveHarness.Config(RENDER_CONFIG, RENDER_MOD, Ecosystem.FABRIC)),
				List.of(), EnvType.CLIENT, "fixture.fabricclientanchors.Probe", "run",
				Map.of(FabricClientMixinAnchors.PROPERTY, adapter,MixinOperationSeamTransport.PROPERTY,adapter));
	}
    private static byte[] originalGui(){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,GUI,null,"java/lang/Object",null);writer.visitField(Opcodes.ACC_PUBLIC,"screen","Lnet/minecraft/client/gui/screens/Screen;",null,null).visitEnd();MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC,"extractRenderState","(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",null,null);method.visitCode();method.visitTypeInsn(Opcodes.NEW,"net/minecraft/client/gui/GuiGraphicsExtractor");method.visitInsn(Opcodes.DUP);method.visitMethodInsn(Opcodes.INVOKESPECIAL,"net/minecraft/client/gui/GuiGraphicsExtractor","<init>","()V",false);method.visitVarInsn(Opcodes.ASTORE,1);method.visitVarInsn(Opcodes.ALOAD,0);method.visitFieldInsn(Opcodes.GETFIELD,GUI,"screen","Lnet/minecraft/client/gui/screens/Screen;");method.visitVarInsn(Opcodes.ALOAD,1);method.visitVarInsn(Opcodes.ILOAD,2);method.visitVarInsn(Opcodes.ILOAD,3);method.visitVarInsn(Opcodes.FLOAD,4);method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"net/minecraft/client/gui/screens/Screen","extractRenderStateWithTooltipAndSubtitles","(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",false);method.visitInsn(Opcodes.RETURN);method.visitMaxs(0,0);method.visitEnd();writer.visitEnd();return writer.toByteArray();}

	private static List<Path> sources() throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}
}
