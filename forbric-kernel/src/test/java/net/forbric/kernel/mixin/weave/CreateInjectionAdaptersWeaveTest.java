package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinCarrierCallbackAdapters;

/**
 * {@code MixinCarrierCallbackAdapters} through the real weave, on its PersistentEntitySectionManager$Callback rule: Create
 * Fly's section hook captures the section the entity left with an implicit {@code @Local long}, written for vanilla's
 * onMove, where one long is live at updateStatus. On the merged onMove NeoForge keeps the old key in a second long for
 * its own event, so the implicit capture has two candidates.
 *
 * <p>The probe moves a carriage from section 11 to 12 and reports who heard it. Adapted, the capture is pinned to the
 * slot that holds the old key: the mod hears "11->12" just before the status update, beside NeoForge's own event.
 * With {@code -Dforbric.carrierCallbackAdapters=off} the hook cannot bind, the mod hears nothing, and the hook is its
 * required (SUSPECTED) loss. The adapter's other rules are not exercised here.
 */
class CreateInjectionAdaptersWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/createinjection");
	private static final String CONFIG = "createinjection.mixins.json";
	private static final String MOD = "create";
	private static final String TARGET = "net/minecraft/world/level/entity/PersistentEntitySectionManager$Callback";

	private static final String ADAPTED = WeaveHarnessMain.DONE + " create carriage 11->12, status TRACKED->TICKING, neoforge carriage 11->12";
	private static final String UNADAPTED = WeaveHarnessMain.DONE + " status TRACKED->TICKING, neoforge carriage 11->12";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "createinjection", List.of(
				SOURCES.resolve("fixture/createinjection/Trail.java"),
				SOURCES.resolve("net/minecraft/world/level/entity/EntityAccess.java"),
				SOURCES.resolve("net/minecraft/world/level/entity/Visibility.java"),
				SOURCES.resolve("net/neoforged/neoforge/common/CommonHooks.java"),
				SOURCES.resolve("net/minecraft/world/level/entity/PersistentEntitySectionManager.java"),
				SOURCES.resolve("fixture/createinjection/Probe.java"),
				SOURCES.resolve("com/zurrtum/create/mixin/PersistentEntitySectionManagerCallbackMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		adapted = run("adapted", Map.of());
		off = run("adapter-off", Map.of(MixinCarrierCallbackAdapters.PROPERTY, "off"));
	}

	@Test void theCaptureIsPinnedToTheOldSectionKey() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings " + adapted.findings());
		assertEquals(List.of(), adapted.findings().stream().filter(f -> f.modId().equals(MOD)).toList(), adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted, TARGET, fixture);
	}

	@Test void switchedOffTheAmbiguousCaptureCannotBindAndTheModHearsNothing() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	/** Each run's predicate must fail on the other, or the control proves nothing about the adapter. */
	@Test void theControlFlipsEveryAdapterAssertion() throws Exception {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapter predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) throws Exception {
		return returned(run, ADAPTED) && losses(run).isEmpty() && callsHandler(run, "onMove");
	}

	private static boolean offHolds(WeaveHarness.Result run) throws Exception {
		List<WeaveHarness.Finding> losses = losses(run);
		return returned(run, UNADAPTED) && losses.size() == 1 && losses.get(0).id().contains("#onEnteringSection(")
				&& losses.get(0).required() && !callsHandler(run, "onMove");
	}

	/** The probe's whole return line: a value that merely starts with the expected one is a different outcome. */
	private static boolean returned(WeaveHarness.Result run, String line) {
		return run.output().lines().anyMatch(line::equals);
	}

	/** The final audit's rows for the mod's injectors that did not attach. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& !f.confidence().equals("RESOLVED")).toList();
	}

	/** Whether {@code method} of the defined Callback calls a handler the mod's mixin merged into it. */
	private static boolean callsHandler(WeaveHarness.Result run, String method) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		MethodNode body = node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
		for (var insn : body.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET) && call.name.contains("$" + MOD + "$")) return true;
		}
		return false;
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.createinjection.Probe", "probe", properties);
	}
}
