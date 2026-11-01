package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.TraceClassVisitor;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;

/**
 * MixinOverloadPin's diagnosis, made by the real pipeline and read where a player reads it: the mod's finding.
 *
 * <p>The fixture's {@code SkyPass} is shaped like a class a carrier patch reshaped — its {@code lambda$render$0} takes
 * other captures than vanilla's — and vanilla's {@code SkyPass}, indexed into the fixture as the FABRIC native reference a
 * launch reads from the vanilla-reference sidecar, has the vanilla shape. The guest mixin is written for that vanilla
 * shape. The carrier's lambda takes two Strings, so MixinHandlerShim alone declines to guess; with the handler rule off
 * ({@code -Dforbric.mixinFit.handlerFit=off}), as in the two diagnosis runs, nothing binds it and Mixin rejects the
 * descriptor. (Until Stage 4 this fixture was a merged class carrying both bodies, and the duplicate-lambda pruner's
 * record was the evidence; NeoForge's base carries one body.)
 *
 * <p>What the stage decides is only what the failure SAYS. With it on, the mod's finding carries why ("a lambda of
 * render whose captures a carrier patch changed from vanilla's"); with {@code -Dforbric.mixinOverloadPin=off} it is the bare
 * InvalidInjectionException. And it decides nothing else: the woven class and what it returns are identical in
 * both runs, because a diagnosis that moved the injection would put it somewhere the mod did not ask for.
 *
 * <p>Both of those runs keep the mixin in front of Mixin with {@code -Dforbric.mixinFit.handlerFit=off}. By default, with
 * vanilla's class in the reference, the pipeline does better than explain: MixinRetarget reads the handler's arguments in
 * vanilla's lambda and moves it onto the carrier's, and the hook runs
 * ({@link #byDefaultTheRetargetBindsTheHandlerFromTheReference}). (On the merged base the evidence was the pruner's
 * record, with no reference to map from, and the default verdict left the mixin out as UNFIT.) So MixinOverloadPin's
 * diagnosis is what a failure says only where Mixin still meets such a binding: with the handler rule off, as here.
 */
class MixinOverloadPinWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/overloadpin");
	private static final String CONFIG = "overloadpin.mixins.json";
	private static final String MOD = "skyfixture";
	private static final String TARGET = "fixture/overloadpin/SkyPass";
	private static final String MIXIN = "fixture.overloadpin.mixin.SkyPassMixin";
	private static final String VANILLA_SHAPE = "(Ljava/lang/String;Ljava/lang/StringBuilder;)V";
	private static final String REASON = "its beforeSky targets lambda$render$0, a lambda of render whose captures a "
			+ "carrier patch changed from vanilla's";
	private static final String BARE = "its mixin " + MIXIN + " failed to apply to fixture.overloadpin.SkyPass "
			+ "(InvalidInjectionException)";
	private static final String EXPLAINED_LOG = "SkyPassMixin.beforeSky cannot bind to " + TARGET + ".lambda$render$0";

	/** Vanilla's SkyPass: the one lambda, in the shape the guest's handler was written for. */
	private static final String VANILLA_SKY_PASS = """
			package fixture.overloadpin;
			public class SkyPass {
			 public String render() { StringBuilder out = new StringBuilder(); lambda$render$0("sky", out); return out.toString(); }
			 private void lambda$render$0(String sky, StringBuilder out) { out.append("vanilla drew ").append(sky); }
			}
			""";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result explained;
	private static WeaveHarness.Result off;
	private static WeaveHarness.Result bound;

	@BeforeAll static void weaveBothWays() throws Exception {
		Path current = WeaveHarness.fixture(work, "overloadpin", List.of(
				SOURCES.resolve("fixture/overloadpin/SkyPass.java"),
				SOURCES.resolve("fixture/overloadpin/SkyProbe.java"),
				SOURCES.resolve("fixture/overloadpin/mixin/SkyPassMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		Path vanilla = work.resolve("native-src/fixture/overloadpin/SkyPass.java");
		Files.createDirectories(vanilla.getParent());
		Files.writeString(vanilla, VANILLA_SKY_PASS);
		Path nativeFixture = WeaveHarness.fixture(work, "native-overloadpin", List.of(vanilla,
				SOURCES.resolve("fixture/overloadpin/SkyProbe.java"),
				SOURCES.resolve("fixture/overloadpin/mixin/SkyPassMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		fixture = NativeWeaveReferences.with(work, current, Map.of(TARGET, NativeWeaveReferences.classes(nativeFixture).get(TARGET)));
		explained = run("explained", Map.of("forbric.mixinOverloadPin", "on", "forbric.mixinFit.handlerFit", "off"));
		off = run("overload-pin-off", Map.of("forbric.mixinOverloadPin", "off", "forbric.mixinFit.handlerFit", "off"));
		bound = run("default", Map.of());
	}

	/**
	 * By default MixinRetarget reads the handler against vanilla's lambda in the reference and moves it onto the carrier's:
	 * vanilla's {@code String sky} must arrive as the carrier's {@code sky}, not its {@code moon} (the carrier's lambda
	 * takes two Strings, so the type alone does not decide), and the {@code StringBuilder} as the carrier's. The handler
	 * prints the String it got, so a mapping that handed over {@code moon} fails here. The two runs above, with the
	 * handler rule off, are the control: there Mixin rejects the descriptor and the hook never runs.
	 */
	@Test void byDefaultTheRetargetBindsTheHandlerFromTheReference() throws Exception {
		assertTrue(boundHolds(bound), bound.describe() + "\nfindings: " + bound.findings());
		assertTrue(callsHandler(bound.defined(TARGET)), bound.describe());
		WeaveHarness.assertWovenAndVerified(bound, TARGET, fixture);
		for (WeaveHarness.Result control : List.of(explained, off)) assertFalse(boundHolds(control), control.describe());
	}

	private static boolean boundHolds(WeaveHarness.Result run) {
		return run.printed("retargeted guest mixin " + MOD + " (" + CONFIG + "):SkyPassMixin")
				&& !run.printed("InvalidInjectionException") && run.printedLine(WeaveHarnessMain.DONE + " [sky hook ran sky] drew sky+moon")
				&& mixinLosses(run).isEmpty();
	}

	@Test void theFailureCarriesTheReshapeReasonOnlyWhileTheStageIsOn() throws Exception {
		for (WeaveHarness.Result run : List.of(explained, off)) {
			// The shape the handler was written for is in neither run's class: the control differs by the stage alone.
			assertFalse(declares(run.defined(TARGET), "lambda$render$0", VANILLA_SHAPE), run.describe());

			// What the woven code did: the injection landed nowhere, in particular not on the surviving lambda.
			assertTrue(run.printed(WeaveHarnessMain.DONE + " drew sky+moon"), run.describe());
			assertFalse(run.printed("[sky hook ran"), run.describe());
			// Mixin merges the handler before the descriptor check rejects it; what must not exist is a call to it.
			assertFalse(callsHandler(run.defined(TARGET)), "something calls beforeSky — " + run.describe());
			WeaveHarness.assertWovenAndVerified(run, TARGET, fixture);
			assertTrue(run.printed("InvalidInjectionException Invalid descriptor"), run.describe());

			List<WeaveHarness.Finding> losses = mixinLosses(run);
			assertEquals(1, losses.size(), run.label() + " findings: " + run.findings());
			assertTrue(losses.get(0).confirmedRequired(), losses.toString());
		}

		assertTrue(explainedHolds(explained), "with the stage on, the finding must say why: " + mixinLosses(explained)
				+ "\n" + explained.describe());
		assertEquals(BARE + " — " + REASON, mixinLosses(explained).get(0).detail());
		assertTrue(offHolds(off), "with the stage off, the finding must be Mixin's bare exception: " + mixinLosses(off)
				+ "\n" + off.describe());
	}

	/**
	 * The diagnosis rewrites nothing: the class the game defines is the same with the stage off. Compared as text
	 * because Mixin stamps a random session id on every merged member, and that id is the only byte allowed to differ.
	 */
	@Test void theDiagnosisChangesNoBytes() throws Exception {
		assertEquals(normalised(off.defined(TARGET)), normalised(explained.defined(TARGET)),
				"MixinOverloadPin changed what was woven into " + TARGET);
	}

	/** The run and its control must be told apart by the very predicates the test uses on them. */
	@Test void theOffControlFlipsEveryDiagnosisAssertion() {
		assertTrue(explainedHolds(explained) && !explainedHolds(off), "diagnosis predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(explained), "control predicate does not separate the runs");
	}

	private static boolean explainedHolds(WeaveHarness.Result run) {
		List<WeaveHarness.Finding> losses = mixinLosses(run);
		return run.printed(EXPLAINED_LOG) && losses.size() == 1 && losses.get(0).detail().endsWith(" — " + REASON);
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		List<WeaveHarness.Finding> losses = mixinLosses(run);
		return !run.printed(EXPLAINED_LOG) && losses.size() == 1 && losses.get(0).detail().equals(BARE);
	}

	/** KernelMixinErrorHandler's row for the mixin as a whole, the one MixinOverloadPin's reason is written into. */
	private static List<WeaveHarness.Finding> mixinLosses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().equals("mixin:" + CONFIG + ":" + MIXIN)
				&& f.modId().equals(MOD)).toList();
	}

	private static boolean callsHandler(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
				.anyMatch(insn -> insn instanceof MethodInsnNode call && call.name.endsWith("beforeSky"));
	}

	/** The class as text, with Mixin's per-session random id (stamped on every merged member) blanked out. */
	private static String normalised(byte[] bytes) {
		StringWriter text = new StringWriter();
		new ClassReader(bytes).accept(new TraceClassVisitor(new PrintWriter(text)), 0);
		return text.toString().replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<session>");
	}

	private static boolean declares(byte[] bytes, String name, String desc) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
		return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.overloadpin.SkyProbe", "render", properties);
	}
}
