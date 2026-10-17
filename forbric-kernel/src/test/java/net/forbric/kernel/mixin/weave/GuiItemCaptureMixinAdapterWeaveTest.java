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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinCaptureLanding;
import net.forbric.kernel.mixin.MixinGuiItemCaptureAdapter;

/**
 * MixinGuiItemCaptureAdapter through the real weave: Item Glint Relight's TAIL capture of the GUI item's render state,
 * on an {@code item} that builds and submits that state inside its non-empty branch.
 *
 * <p>The probe draws a diamond, an empty slot and a tooltip, and reports what the guest's handlers were handed. Adapted,
 * the capture runs right after the one {@code addItem} submission, where the render-state local is live: the diamond
 * is captured with the very state the GUI submitted, and the empty slot (which submits nothing) is not. With
 * {@code -Dforbric.guiItemCaptureAnchor=off} the capture is woven at the TAIL, on the join after the branch: Mixin
 * still reads the local as in scope there (its table entry ends on that join's label), loads a slot the empty-slot
 * path never wrote, and the first use of the GUI class throws {@code VerifyError}, which is how the mod crashed.
 *
 * <p>At every level Fabric Loader gives a mod, 0.9.2 included: there the call falls straight into the join, AFTER is
 * the join's label, and Mixin's 0.9.2 local walk steps from it onto the join's frame, which does not declare the local.
 * MixinCaptureLanding gives the call a NOP to land on; switched off, Mixin skips the moved capture at 0.9.2 alone.
 *
 * <p>The fixture compiles with {@code -g}: the local's scope in the LocalVariableTable is what Mixin reads to decide
 * what it can capture where.
 */
class GuiItemCaptureMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/guiitemcapture");
	private static final String CONFIG = "guiitemcapture.mixins.json";
	private static final String MOD = "itemglintrelight";
	private static final String GUI = "net/minecraft/client/gui/GuiGraphicsExtractor";
	private static final String HANDLER = "itemglintrelight$captureGuiItem";
	private static final String SUBMITTED = " tooltips=[5,6] submitted=[state(diamond#7)@1,2] tooltip=hint@5,6";
	private static final String CAPTURED = WeaveHarnessMain.DONE + " captured=[diamond->state(diamond#7)]" + SUBMITTED;
	private static final String UNVERIFIABLE = WeaveHarnessMain.THREW + "java.lang.VerifyError: Bad local variable type";
	private static final String AT_ITEM = "Location:\n    " + GUI + ".item(Lnet/minecraft/world/entity/LivingEntity;";

	/** What the fixture mod declares in each run, so each level is the one Fabric Loader gives: declaring none is 0.9.2. */
	private static final String REQUIRES = WeaveHarnessMain.REQUIRES;
	private static final Map<String, String> LEVELS = Map.of(
			"9002", "", "10000", "fabricloader=>=0.12", "17001", "fabricloader=>=0.19");

	@TempDir static Path work;
	private static Path fixture;
	private static final Map<String, WeaveHarness.Result> adapted = new java.util.TreeMap<>();
	private static WeaveHarness.Result off, offLegacy, landingOff, landingOffModern;

	@BeforeAll static void weaveEveryWay() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(11, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "guiitemcapture", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		for (var level : LEVELS.entrySet()) {
			adapted.put(level.getKey(), run("adapted-" + level.getKey(), Map.of(REQUIRES, level.getValue())));
		}
		off = run("adapter-off", Map.of(MixinGuiItemCaptureAdapter.PROPERTY, "off", REQUIRES, LEVELS.get("10000")));
		offLegacy = run("adapter-off-9002", Map.of(MixinGuiItemCaptureAdapter.PROPERTY, "off", REQUIRES, LEVELS.get("9002")));
		landingOff = run("landing-off-9002", Map.of(MixinCaptureLanding.PROPERTY, "off", REQUIRES, LEVELS.get("9002")));
		landingOffModern = run("landing-off-10000", Map.of(MixinCaptureLanding.PROPERTY, "off", REQUIRES, LEVELS.get("10000")));
	}

	@Test void eachRunIsAtTheLevelFabricLoaderGivesWhatTheFixtureDeclares() {
		for (var run : adapted.entrySet()) {
			assertTrue(run.getValue().printed("level=configs): {" + run.getKey() + "=1}"), run.getValue().describe());
		}
		assertTrue(landingOff.printed("level=configs): {9002=1}"), landingOff.describe());
		assertTrue(offLegacy.printed("level=configs): {9002=1}"), offLegacy.describe());
		assertTrue(off.printed("level=configs): {10000=1}"), off.describe());
	}

	@Test void theDiamondIsCapturedWithTheStateTheGuiSubmittedAtEveryLevel() throws Exception {
		for (WeaveHarness.Result run : adapted.values()) {
			assertTrue(run.printed(CAPTURED), run.describe());
			assertFalse(run.printed("has incompatible changes"), run.describe());
			assertEquals(List.of(), losses(run), run.findings() + "\n" + run.describe());
			assertFalse(run.printed("VerifyError"), run.describe());
			// Woven right after the submission, inside the branch the local lives in.
			assertEquals(List.of(true), insideTheBranch(run), run.describe());
			WeaveHarness.assertWovenAndVerified(run, GUI, fixture);
		}
	}

	/** Where the capture landed: one NOP right after the submission, which the run without the landing does not have. */
	@Test void theLandingIsOneNopAfterTheSubmission() throws Exception {
		for (WeaveHarness.Result run : adapted.values()) assertEquals(List.of("NOP"), afterTheSubmission(run), run.describe());
		assertEquals(List.of(), afterTheSubmission(landingOff), landingOff.describe());
	}

	/**
	 * From 0.10.0 on, Mixin's walk stops before the join's frame and captured there without the landing too: the NOP is
	 * then the woven method's one difference, and the capture is woven exactly where it was.
	 */
	@Test void fromZeroTenTheLandingChangesNothingButTheNop() throws Exception {
		assertTrue(landingOffModern.printed(CAPTURED), landingOffModern.describe());
		assertEquals(woven(landingOffModern, false), woven(adapted.get("10000"), true));
	}

	/**
	 * Why the landing exists. Without it, at 0.9.2 — what Fabric Loader gives the fixture mod, which declares no Fabric
	 * Loader lower bound — AFTER the submission is the join's label; Mixin's legacy walk steps from it onto the join's
	 * frame, refuses the moved capture ("LVT ... has incompatible changes") and skips it: the feature is gone without a
	 * crash.
	 */
	@Test void withoutTheLandingTheLegacyWalkSkipsTheMovedCapture() {
		assertTrue(landingOff.printed("has incompatible changes"), landingOff.describe());
		assertTrue(landingOff.printed(WeaveHarnessMain.DONE + " captured=[]" + SUBMITTED), landingOff.describe());
	}

	@Test void switchedOffTheTailCaptureLoadsADeadLocalAndTheGuiClassFailsVerification() throws Exception {
		assertTrue(off.printed(UNVERIFIABLE), off.describe());
		assertTrue(off.printed(AT_ITEM), off.describe());
		assertFalse(off.printed(WeaveHarnessMain.DONE), off.describe());
		// Woven once, on the join after the branch, where the empty-slot path arrives without the local.
		assertEquals(List.of(false), insideTheBranch(off), off.describe());
		assertFalse(verdict(off).isEmpty(), "ASM's verifier accepts the TAIL capture — " + off.describe());
	}

	/**
	 * At 0.9.2 the unmoved TAIL capture does not crash: the legacy walk reads the join's frame at the tail, finds no local
	 * to capture and Mixin skips the handler, which is how it loses the feature instead.
	 */
	@Test void switchedOffAtZeroNineTwoTheTailCaptureIsSkipped() throws Exception {
		assertTrue(offLegacy.printed("has incompatible changes"), offLegacy.describe());
		assertTrue(offLegacy.printed(WeaveHarnessMain.DONE + " captured=[]" + SUBMITTED), offLegacy.describe());
		assertEquals(List.of(), insideTheBranch(offLegacy), offLegacy.describe());
	}

	/** Each switch is its runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryCaptureAssertion() throws Exception {
		WeaveHarness.Result at10000 = adapted.get("10000"), at9002 = adapted.get("9002");
		assertTrue(capturedHolds(at10000) && !capturedHolds(off), "the adapted predicate does not separate the runs");
		assertTrue(crashedHolds(off) && !crashedHolds(at10000), "the control predicate does not separate the runs");
		assertTrue(capturedHolds(at9002) && !capturedHolds(landingOff), "the landing predicate does not separate the runs");
		assertTrue(capturedHolds(at9002) && !capturedHolds(offLegacy), "the 0.9.2 control does not separate the runs");
	}

	/**
	 * The woven {@code item}, one instruction per line, with Mixin's per-session names made equal: {@code withoutNops}
	 * drops the NOPs.
	 */
	private static String woven(WeaveHarness.Result run, boolean withoutNops) throws Exception {
		ClassNode gui = new ClassNode();
		new ClassReader(run.defined(GUI)).accept(gui, 0);
		MethodNode item = gui.methods.stream().filter(m -> m.name.equals("item")).findFirst().orElseThrow();
		org.objectweb.asm.util.Textifier text = new org.objectweb.asm.util.Textifier();
		org.objectweb.asm.util.TraceMethodVisitor trace = new org.objectweb.asm.util.TraceMethodVisitor(text);
		for (AbstractInsnNode insn : item.instructions) {
			if (withoutNops && insn.getOpcode() == org.objectweb.asm.Opcodes.NOP) continue;
			insn.accept(trace);
		}
		java.io.StringWriter out = new java.io.StringWriter();
		text.print(new java.io.PrintWriter(out));
		return out.toString().replaceAll("md[0-9a-f]{6}\\$", "md\\$").replaceAll("\\$[a-z]{3}\\d{3}\\$", "\\$uid\\$");
	}

	/** The NOPs between the woven submission call and the next label: the capture's call sits before the landing. */
	private static List<String> afterTheSubmission(WeaveHarness.Result run) throws Exception {
		ClassNode gui = new ClassNode();
		new ClassReader(run.defined(GUI)).accept(gui, 0);
		MethodNode item = gui.methods.stream().filter(m -> m.name.equals("item")).findFirst().orElseThrow();
		List<String> between = new java.util.ArrayList<>();
		boolean after = false;
		for (AbstractInsnNode insn : item.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals("addItem")) { after = true; continue; }
			if (!after) continue;
			if (insn instanceof org.objectweb.asm.tree.LabelNode) break;
			if (insn.getOpcode() == org.objectweb.asm.Opcodes.NOP) between.add("NOP");
		}
		return between;
	}

	private static boolean capturedHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(CAPTURED) && losses(run).isEmpty() && insideTheBranch(run).equals(List.of(true))
				&& verdict(run).isEmpty();
	}

	private static boolean crashedHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(UNVERIFIABLE) && !run.printed(WeaveHarnessMain.DONE) && insideTheBranch(run).equals(List.of(false))
				&& !verdict(run).isEmpty();
	}

	/**
	 * For each call of the merged capture handler in the woven {@code item}: whether it sits before the label the
	 * empty-stack test jumps to, i.e. inside the non-empty branch. Empty when Mixin wove none.
	 */
	private static List<Boolean> insideTheBranch(WeaveHarness.Result run) throws Exception {
		ClassNode gui = new ClassNode();
		new ClassReader(run.defined(GUI)).accept(gui, 0);
		MethodNode item = gui.methods.stream().filter(m -> m.name.equals("item")).findFirst().orElseThrow();
		List<JumpInsnNode> jumps = Stream.of(item.instructions.toArray()).filter(JumpInsnNode.class::isInstance)
				.map(JumpInsnNode.class::cast).toList();
		assertEquals(1, jumps.size(), "item has one branch, the empty-stack test: " + jumps);
		int join = item.instructions.indexOf(jumps.get(0).label);
		List<Boolean> inside = new java.util.ArrayList<>();
		for (AbstractInsnNode insn : item.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(GUI) && call.name.endsWith(HANDLER)) {
				inside.add(item.instructions.indexOf(insn) < join);
			}
		}
		return inside;
	}

	/** ASM's verifier over the woven GUI class: empty when it verifies. */
	private static String verdict(WeaveHarness.Result run) throws Exception {
		try (var jars = new java.net.URLClassLoader(new java.net.URL[] {fixture.toUri().toURL()},
				GuiItemCaptureMixinAdapterWeaveTest.class.getClassLoader())) {
			return run.verify(GUI, jars);
		}
	}

	/** The final audit's rows for the mod's required injectors that attached nowhere and are not settled either way. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& f.required() && !"RESOLVED".equals(f.confidence())).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.guiitemcapture.Probe", "probe", properties);
	}
}
