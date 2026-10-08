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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.FabricCreativePagerMixinAdapter;

/**
 * {@code FabricCreativePagerMixinAdapter} through the real weave, on a CLIENT run: fabric-creative-tab-api's
 * PageUp/PageDown handler turns the page the screen draws.
 *
 * <p>The fixture's screen is the merged one after the pager bridge: NeoForge's page state, with Fabric's two pager
 * calls answered from it. The guest mixin brings a pager of its own. With the adapter only its key handler is kept,
 * so PageDown and PageUp move the drawn page. With {@code -Dforbric.fabricCreativeKeyboard=off} the whole mixin
 * applies, its pager overwrites the bridge's calls and turns a static page nothing draws: both keys report "handled"
 * and the drawn page never moves. Neither run reports a loss.
 */
class FabricCreativePagerMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/fabriccreativepager");
	private static final String CONFIG = "fabriccreativepager.mixins.json";
	private static final String MOD = "fabriccreativepager";
	private static final String SCREEN = "net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen";
	private static final String TURNS_DRAWN = "down=true drawn=1 up=true drawn=0";
	private static final String TURNS_HIDDEN = "down=true drawn=0 up=true drawn=0";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weaveBoth() throws Exception {
		fixture = WeaveHarness.fixture(work, MOD, sources(), Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		adapted = run("adapted", "on");
		off = run("off", "off");
	}

	@Test void theKeysTurnThePageTheScreenDraws() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings: " + adapted.findings());
		assertTrue(adapted.printed("[Forbric/CreativePager] retained Fabric's PageUp/PageDown callback"), adapted.describe());
		assertTrue(WeaveHarness.hasMergedMethod(adapted.defined(SCREEN)), adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted, SCREEN, fixture);
	}

	@Test void withTheSwitchOffTheKeysTurnASecondPagerNothingDraws() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings: " + off.findings());
		assertFalse(off.printed("[Forbric/CreativePager]"), off.describe());
		WeaveHarness.assertWovenAndVerified(off, SCREEN, fixture);
	}

	/** The run and its control must be told apart by the very predicates the test uses on them. */
	@Test void theControlFlipsEveryAssertion() throws Exception {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapted predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(WeaveHarnessMain.DONE + " " + TURNS_DRAWN) && losses(run).isEmpty() && secondPagerFields(run) == 0;
	}

	/** Fabric's own page is merged in as a static field: the second pager state the adapter leaves out. */
	private static boolean offHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(WeaveHarnessMain.DONE + " " + TURNS_HIDDEN) && losses(run).isEmpty() && secondPagerFields(run) == 1;
	}

	private static long secondPagerFields(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(SCREEN)).accept(node, ClassReader.SKIP_CODE);
		return node.fields.stream().filter(f -> f.desc.equals("I") && (f.access & Opcodes.ACC_STATIC) != 0
				&& (f.access & Opcodes.ACC_FINAL) == 0).count();
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.confirmedRequired()).toList();
	}

	private static WeaveHarness.Result run(String label, String adapter) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.fabriccreativepager.Probe", "run", Map.of(FabricCreativePagerMixinAdapter.PROPERTY,adapter,"forbric.mergedBaseCompat","off"));
	}

	private static List<Path> sources() throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}
}
