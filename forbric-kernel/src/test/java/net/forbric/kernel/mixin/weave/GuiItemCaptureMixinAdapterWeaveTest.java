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

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(11, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "guiitemcapture", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		adapted = run("adapted", Map.of());
		off = run("adapter-off", Map.of(MixinGuiItemCaptureAdapter.PROPERTY, "off"));
	}

	@Test void theDiamondIsCapturedWithTheStateTheGuiSubmitted() throws Exception {
		assertTrue(adapted.printed(CAPTURED), adapted.describe());
		assertEquals(List.of(), losses(adapted), adapted.findings() + "\n" + adapted.describe());
		assertFalse(adapted.printed("VerifyError"), adapted.describe());
		// Woven right after the submission, inside the branch the local lives in.
		assertEquals(List.of(true), insideTheBranch(adapted), adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted, GUI, fixture);
	}

	@Test void switchedOffTheTailCaptureLoadsADeadLocalAndTheGuiClassFailsVerification() throws Exception {
		assertTrue(off.printed(UNVERIFIABLE), off.describe());
		assertTrue(off.printed(AT_ITEM), off.describe());
		assertFalse(off.printed(WeaveHarnessMain.DONE), off.describe());
		// Woven once, on the join after the branch, where the empty-slot path arrives without the local.
		assertEquals(List.of(false), insideTheBranch(off), off.describe());
		assertFalse(verdict(off).isEmpty(), "ASM's verifier accepts the TAIL capture — " + off.describe());
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryCaptureAssertion() throws Exception {
		assertTrue(capturedHolds(adapted) && !capturedHolds(off), "the adapted predicate does not separate the runs");
		assertTrue(crashedHolds(off) && !crashedHolds(adapted), "the control predicate does not separate the runs");
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
