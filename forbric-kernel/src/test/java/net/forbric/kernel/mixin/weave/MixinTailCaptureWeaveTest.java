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
import net.forbric.kernel.mixin.MixinGuiItemCaptureAdapter;

/**
 * The TAIL-capture repair through the real weave, on a mod and a method that share nothing with Item Glint Relight's
 * GUI item: a lectern renderer whose {@code submit} guard was folded, so the two locals its body declares (an
 * {@code int} and a {@code String}) end at the join the tail sits on; a mixin that names its target by string, spells
 * its selector owner-qualified and captures both with CAPTURE_FAILHARD. Beside it, a TAIL capture of a local the method
 * declares before its guard, which the tail holds on every path: that one must stay at TAIL.
 *
 * <p>Repaired, the submit capture runs once, after the body wrote its line, with the values the body computed, and not
 * for the empty lectern; the label capture runs at the tail for both calls. Switched off, the submit capture is woven at
 * the join and the renderer fails verification.
 */
class MixinTailCaptureWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/tailcapture");
	private static final String CONFIG = "tailcapture.mixins.json";
	private static final String MOD = "lecternlog";
	private static final String SEEN = WeaveHarnessMain.DONE
			+ " seen=[book:atlas#2, label tag:x, label tag:null] out=[book:atlas/2, tag:x]";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result repaired, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(4, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "tailcapture", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		repaired = run("repaired", Map.of());
		off = run("off", Map.of(MixinGuiItemCaptureAdapter.PROPERTY, "off"));
	}

	@Test void theBodysLocalsAreCapturedAfterTheBodyAndTheLabelStaysAtTheTail() throws Exception {
		assertTrue(repaired.printed(SEEN), repaired.describe());
		assertFalse(repaired.printed("VerifyError"), repaired.describe());
		assertEquals(List.of(), losses(repaired), repaired.findings() + "\n" + repaired.describe());
	}

	@Test void switchedOffTheFoldedTailCannotServeTheCapture() throws Exception {
		// Woven at the join: Mixin types the body's slots from their earlier stores and loads them on the path that never
		// wrote them, so the renderer fails verification on first use.
		assertTrue(off.printed(WeaveHarnessMain.THREW + "java.lang.VerifyError: Bad local variable type"), off.describe());
		assertFalse(off.printed(WeaveHarnessMain.DONE), off.describe());
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& f.required() && !"RESOLVED".equals(f.confidence())).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.tailcapture.Probe", "probe", properties);
	}
}
