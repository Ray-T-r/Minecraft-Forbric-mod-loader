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
import net.forbric.kernel.mixin.MixinSpriteLoaderCallbackAdapter;

/**
 * A locals capture on a carrier's delegating stub through the real weave, on another stub than the atlas listing: a
 * container whose vanilla setItem(int, ItemStack) forwards to the carrier's setItem(int, ItemStack, boolean), and another
 * mod's capture of the previous stack and the size limit before setChanged, by a bare-name selector, on a void target.
 * The fixture keeps its LocalVariableTable ({@code -g}), as a release build of the game does.
 *
 * <p>Moved, the capture runs on the body with the values the body computed. Switched off, it stays on the stub, which
 * makes no setChanged call: it never runs.
 */
class StubLocalsCaptureWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/stubcapture");
	private static final String CONFIG = "stubcapture.mixins.json";
	private static final String MOD = "slotlog";
	private static final String SEEN = WeaveHarnessMain.DONE + " seen=[1:airx0->applex64/64] trace=[changed]";
	private static final String UNSEEN = WeaveHarnessMain.DONE + " seen=[] trace=[changed]";

	@TempDir static Path work;
	private static WeaveHarness.Result moved, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(5, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		Path fixture = WeaveHarness.fixture(work, "stubcapture", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		moved = run(fixture, "moved", Map.of());
		off = run(fixture, "off", Map.of(MixinSpriteLoaderCallbackAdapter.PROPERTY, "off"));
	}

	@Test void theCaptureRunsOnTheBodyWithItsLocals() throws Exception {
		assertTrue(moved.printed(SEEN), moved.describe());
		assertEquals(List.of(), losses(moved), moved.describe());
	}

	@Test void switchedOffItStaysOnTheStubAndNeverRuns() throws Exception {
		assertTrue(off.printed(UNSEEN), off.describe());
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& f.required() && !"RESOLVED".equals(f.confidence())).toList();
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.stubcapture.Probe", "probe", properties);
	}
}
