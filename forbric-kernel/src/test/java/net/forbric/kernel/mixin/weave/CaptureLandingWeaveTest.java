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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinCaptureLanding;

/**
 * MixinCaptureLanding through the real weave: the fixture's anonymous renderer {@code Renderers$1} has a folded tail, and
 * the mod declares no Fabric Loader lower bound, so its TAIL locals capture weaves at 0.9.2, where Mixin's legacy local
 * walk needs the landing. The class must capture {@code state(a)} and {@code state(b)}, and nothing for the null items,
 * which submit nothing.
 *
 * <p>Beside it, another mod's injector two nodes past the same call ({@code Shift.BY}, {@code by = 2}). BY counts every node,
 * so a NOP there would move that point from after the join's label to before it; the method keeps its body instead, and
 * that injector runs on every path, as it does with the landing switched off.
 *
 * <p>Until Stage 4 the fixture also had the merged base's renamed twin {@code Renderers$1$forbricneo}, which the deleted
 * MixinMergedTwin added as a second target; that a landing follows a class the adapter never read is
 * MixinCaptureLandingTest's {@code anyClassTheMixinIsAppliedToGetsItsLanding}.
 */
class CaptureLandingWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/twincapture");
	private static final String CONFIG = "twincapture.mixins.json";
	private static final String SHIFTING = "twincapture-shifted.mixins.json";
	private static final String MOD = "twincapture";
	private static final String VANILLA = "fixture/twincapture/Renderers$1";
	private static final String SUBMITTED = " submitted=[state(a), state(b)]";
	private static final String BOTH = WeaveHarnessMain.DONE + " captured=[state(a), state(b)]" + SUBMITTED + " shifted=[]";
	/** The BY injector after the join's label: it runs for the null items too. */
	private static final String SHIFTED = WeaveHarnessMain.DONE + " captured=[]" + SUBMITTED + " shifted=[a, null, b, null]";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result legacy, modern, landingOff, shifted, shiftedLandingOff;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(7, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "twincapture", sources,
				Map.of(CONFIG, SOURCES.resolve(CONFIG), SHIFTING, SOURCES.resolve(SHIFTING)), List.of("-g"));
		legacy = run("9002", Map.of(WeaveHarnessMain.REQUIRES, ""));
		modern = run("10000", Map.of(WeaveHarnessMain.REQUIRES, "fabricloader=>=0.12"));
		landingOff = run("landing-off-9002", Map.of(WeaveHarnessMain.REQUIRES, "", MixinCaptureLanding.PROPERTY, "off"));
		shifted = runWithShift("shifted-9002", Map.of(WeaveHarnessMain.REQUIRES, ""));
		shiftedLandingOff = runWithShift("shifted-landing-off-9002",
				Map.of(WeaveHarnessMain.REQUIRES, "", MixinCaptureLanding.PROPERTY, "off"));
	}

	@Test void theFoldedTailCapturesAtZeroNineTwo() throws Exception {
		assertTrue(legacy.printed("level=configs): {9002=1}"), legacy.describe());
		assertTrue(legacy.printed(BOTH), legacy.describe());
		assertFalse(legacy.printed("has incompatible changes"), legacy.describe());
		assertEquals(List.of("NOP"), afterTheSubmission(legacy, VANILLA), legacy.describe());
	}

	/** From 0.10.0 on, Mixin's walk stops before the join's frame: both items capture, and the class gets no NOP. */
	@Test void fromZeroTenBothCaptureWithoutALanding() throws Exception {
		assertTrue(modern.printed("level=configs): {10000=1}"), modern.describe());
		assertTrue(modern.printed(BOTH), modern.describe());
		assertEquals(List.of(), afterTheSubmission(modern, VANILLA), modern.describe());
	}

	/** The control: without the landing, 0.9.2 skips the moved capture. */
	@Test void withoutTheLandingZeroNineTwoSkipsBoth() {
		assertTrue(landingOff.printed("has incompatible changes"), landingOff.describe());
		assertTrue(landingOff.printed(WeaveHarnessMain.DONE + " captured=[]" + SUBMITTED + " shifted=[]"), landingOff.describe());
	}

	/** Another mixin shifts BY in {@code render}: no NOP, so its point stays after the join's label, as without the landing. */
	@Test void aPointShiftedByInTheMethodKeepsTheBodyAndItsPlace() throws Exception {
		assertTrue(shifted.printed("shifts a point BY in that method"), shifted.describe());
		assertTrue(shifted.printed(SHIFTED), shifted.describe());
		assertTrue(shiftedLandingOff.printed(SHIFTED), shiftedLandingOff.describe());
		assertEquals(List.of(), afterTheSubmission(shifted, VANILLA), shifted.describe());
	}

	/** The NOPs between {@code owner.render}'s woven submit call and the next label. */
	private static List<String> afterTheSubmission(WeaveHarness.Result run, String owner) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(owner)).accept(node, 0);
		MethodNode render = node.methods.stream().filter(m -> m.name.equals("render")).findFirst().orElseThrow();
		List<String> between = new ArrayList<>();
		boolean after = false;
		for (AbstractInsnNode insn : render.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals("submit")) { after = true; continue; }
			if (!after) continue;
			if (insn instanceof org.objectweb.asm.tree.LabelNode) break;
			if (insn.getOpcode() == Opcodes.NOP) between.add("NOP");
		}
		return between;
	}

	private static WeaveHarness.Result runWithShift(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, List.of(new WeaveHarness.Config(CONFIG, MOD, Ecosystem.FABRIC),
				new WeaveHarness.Config(SHIFTING, "twinshift", Ecosystem.FABRIC)), List.of(), EnvType.SERVER,
				"fixture.twincapture.mod.Probe", "run", properties);
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.twincapture.mod.Probe", "run", properties);
	}
}
