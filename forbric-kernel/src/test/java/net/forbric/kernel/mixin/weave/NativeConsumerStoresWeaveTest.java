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

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.NativeConsumerStores;

/**
 * {@code NativeConsumerStores} through the real weave, on classes that share no name with any game or mod.
 *
 * <p>Natively {@code org.tavern.hall.Bell.ring} stores its tone once — high for gold, low otherwise — and hands it straight to
 * the chime. As merged, one carrier's tone is stored first, the hall's own choice only when that carrier has none, then the
 * other carrier's custom tone when it has one. The guest, {@code com.minstrel.tune}, modifies the tone at its {@code STORE}.
 *
 * <p>Moved to the chime's read of the tone, the guest tunes once per ring, the tone the chime plays: for bronze and gold
 * every line is the native run's; for tin and iron (tones only the carriers have) it tunes the carriers' tone once. Switched
 * off, the guest tunes at every merged store a ring passes: three times for iron (the first carrier's missing tone, the hall's
 * choice, the other carrier's custom tone).
 */
class NativeConsumerStoresWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/consumerstores");
	private static final String CONFIG = "consumerstores.mixins.json";
	private static final String MOD = "tune";
	private static final String MOVED = WeaveHarnessMain.DONE
			+ " bronze: tune low, play low+ in hall | gold: tune high, play high in hall | tin: tune tinny, play tinny in hall | iron: tune clang, play clang in hall";

	@TempDir static Path work;
	private static WeaveHarness.Result moved, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList();
		}
		assertEquals(8, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("org/tavern/hall/Bell.java"));
		nativeSources.add(SOURCES.resolve("native/org/tavern/hall/Bell.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		Path fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "consumerstores", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)),
				List.of("-g")), NativeWeaveReferences.classes(nativeFixture));
		moved = run(fixture, "moved", Map.of());
		off = run(fixture, "off", Map.of(NativeConsumerStores.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void theGuestTunesOncePerRingTheToneTheChimePlays() {
		assertTrue(moved.printedLine(MOVED), moved.describe());
		assertTrue(moved.printed("[Forbric/Mixin] com.minstrel.tune.mixin.BellMixin.tune$tone: its local's one native store is stored again"), moved.describe());
	}

	/** Bronze and gold — the tones the hall has natively — ring exactly as on the native bell. */
	@Test void everyNativeRingIsTheNativeRunsOne() {
		String line = natively.output().lines().filter(l -> l.startsWith(WeaveHarnessMain.DONE)).findFirst().orElse("");
		assertTrue(line.startsWith(WeaveHarnessMain.DONE + " bronze: tune low, play low+ in hall | gold: tune high, play high in hall"), natively.describe());
		assertTrue(MOVED.startsWith(line.substring(0, line.indexOf(" | tin:"))));
	}

	@Test void switchedOffTheGuestTunesAtEveryMergedStore() {
		String line = off.output().lines().filter(l -> l.startsWith(WeaveHarnessMain.DONE)).findFirst().orElse("");
		assertTrue(line.contains("iron: tune null, tune low, tune clang, play clang in hall"), off.describe());
	}

	@Test void theControlFlipsTheAssertion() {
		assertTrue(moved.printedLine(MOVED) && !off.printedLine(MOVED));
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.consumerstores.Probe", "probe", properties);
	}
}
