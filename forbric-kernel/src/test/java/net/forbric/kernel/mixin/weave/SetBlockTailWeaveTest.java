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
import net.forbric.kernel.mixin.MixinPlayerWorldCallbackAdapter;

/**
 * setBlock's notification tail through the real weave, hooked by a mod that is neither Carpet nor C2ME and writes it
 * another way: one mixin conditions the neighbour update with a @WrapWithCondition (no @ModifyConstant beside it) and
 * modifies the chunk-status check's result with a @ModifyExpressionValue, both by bare-name selectors, on a level whose
 * setBlock hands both operations to markAndNotifyBlock.
 *
 * <p>Followed into the helper, the block set in a chunk that ticks no blocks still sends its update (the mod's status
 * hook) and the quiet one makes no neighbour update (its condition). Switched off, both hooks name setBlock, which makes
 * neither call: the quiet block updates its neighbours and the unticked one sends nothing.
 */
class SetBlockTailWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/settail");
	private static final String CONFIG = "settail.mixins.json";
	private static final String MOD = "quietfill";
	private static final String FOLLOWED = WeaveHarnessMain.DONE + " [updated@1,0,0, neighbours@1,0,0, updated@5,0,0]";
	private static final String NATIVE = WeaveHarnessMain.DONE + " [updated@1,0,0, neighbours@1,0,0, neighbours@5,0,0]";
	private static final String RESTORED = "[Forbric/Mixin] restored 2 callback(s) in org/example/quietfill/mixin/QuietPlacementMixin";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result followed, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(9, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "settail", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		followed = run("followed", Map.of());
		off = run("off", Map.of(MixinPlayerWorldCallbackAdapter.PROPERTY, "off"));
	}

	@Test void bothHooksActInTheHelperThatNowMakesTheirCalls() throws Exception {
		assertTrue(followed.printed(FOLLOWED), followed.describe());
		assertTrue(followed.printed(RESTORED), followed.describe());
		assertEquals(List.of(), unsettled(followed), followed.findings() + "\n" + followed.describe());
	}

	@Test void switchedOffBothHooksStayOnSetBlockAndActNowhere() throws Exception {
		assertTrue(off.printed(NATIVE), off.describe());
		assertFalse(off.printed(RESTORED), off.describe());
	}

	private static List<WeaveHarness.Finding> unsettled(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.required() && !"RESOLVED".equals(f.confidence())).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.settail.Probe", "probe", properties);
	}
}
