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
import net.forbric.kernel.mixin.MixinTwinRebind;

/**
 * MixinTwinRebind through the real weave: a Fabric guest's {@code @ModifyReturnValue} on
 * {@code ModelBlockRenderer.shouldRenderFace}, selected by name and taking vanilla's four arguments after the result
 * (LiquidBounce's X-Ray face test, in miniature), on the merged shape — NeoForge's overload with the block's own
 * position declared first and called, vanilla's kept after it and called by nothing.
 *
 * <p>The probe draws an ore with stone above it and air below. Moved, the handler runs on NeoForge's face test for
 * both faces, handed the state, direction and neighbour vanilla would hand it, and shows the face the stone hides. With
 * {@code -Dforbric.mixinTwinRebind=off} Mixin binds the name to NeoForge's overload, rejects the handler and fails the
 * whole mixin: the stone hides the face, and the mod is reported.
 */
class MixinTwinRebindWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/twinrebind");
	private static final String CONFIG = "twinrebind.mixins.json";
	private static final String MOD = "xray";
	private static final String TARGET = "net/minecraft/client/renderer/block/ModelBlockRenderer";
	private static final String SHOWN = WeaveHarnessMain.DONE + " neoforge:ore>ore-up,xray:ore/up/ore-up,face:up,"
			+ "neoforge:ore>ore-down,xray:ore/down/ore-down,face:down";
	private static final String HIDDEN = WeaveHarnessMain.DONE + " neoforge:ore>ore-up,neoforge:ore>ore-down,face:down";
	private static final String MOVED_LOG = "[Forbric/Mixin] com.example.xray.mixin.RendererMixin: xray now targets "
			+ "net.minecraft.client.renderer.block.ModelBlockRenderer.shouldRenderFace(Lnet/minecraft/client/renderer/block/"
			+ "BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
			+ "Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;)Z — the merged game calls it where vanilla called "
			+ "shouldRenderFace";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(8, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "twinrebind", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		moved = run("moved", Map.of());
		off = run("off", Map.of(MixinTwinRebind.PROPERTY, "off"));
	}

	@Test void theFaceTestRunsOnTheOverloadWithVanillasArguments() throws Exception {
		assertTrue(movedHolds(moved), moved.describe() + "\nfindings: " + moved.findings());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	@Test void switchedOffTheMixinFailsWholeAndIsReported() {
		assertTrue(offHolds(off), off.describe() + "\nfindings: " + off.findings());
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryMoveAssertion() {
		assertTrue(movedHolds(moved) && !movedHolds(off), "the move predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(moved), "the control predicate does not separate the runs");
	}

	private static boolean movedHolds(WeaveHarness.Result run) {
		return run.printedLine(SHOWN) && run.printed(MOVED_LOG) && losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printedLine(HIDDEN) && !run.printed(MOVED_LOG) && !losses(run).isEmpty();
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.confirmedRequired()).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.twinrebind.Probe", "probe", properties);
	}
}
