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
import net.forbric.kernel.mixin.ThinnedCallOrdinals;

/**
 * ThinnedCallOrdinals through the real weave: a Fabric guest's {@code @Inject} before vanilla's third
 * {@code ItemStack.isEmpty()} in {@code MultiPlayerGameMode.performUseItemOn} (ViaFabricPlus' 1.12.2 placement hook, in
 * miniature), on the merged body that asks {@code doesSneakBypassUse} of both hand stacks and keeps that third
 * {@code isEmpty} as its only one.
 *
 * <p>The probe uses a stack of stone on a block. Re-counted, the hook runs once the block has had its turn and before
 * the stone is used. With {@code -Dforbric.thinnedCallOrdinals=off} ordinal 2 finds nothing, the hook never runs, and
 * the mod's required injector is reported lost.
 */
class ThinnedCallOrdinalsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/thinnedcall");
	private static final String CONFIG = "thinnedcall.mixins.json";
	private static final String MOD = "oldclient";
	private static final String TARGET = "net/minecraft/client/multiplayer/MultiPlayerGameMode";
	private static final String HOOKED = WeaveHarnessMain.DONE + " block,old-client:stone,use:stone";
	private static final String PLAIN = WeaveHarnessMain.DONE + " block,use:stone";
	private static final String MOVED_LOG = "[Forbric/Mixin] com/example/oldclient/mixin/PlaceMixin: place's ordinal 2 → 0 is proved";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, nativeRun;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(10, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		Path nativeSource = Files.createDirectories(work.resolve("native-source")).resolve("MultiPlayerGameMode.java");
		Files.writeString(nativeSource, Files.readString(SOURCES.resolve(TARGET + ".java")).replace("doesSneakBypassUse()", "isEmpty()"));
		List<Path> originalSources = new java.util.ArrayList<>(sources);
		originalSources.remove(SOURCES.resolve(TARGET + ".java")); originalSources.add(nativeSource);
		Path nativeFixture = WeaveHarness.fixture(work, "native-thinnedcall", originalSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		byte[] original;
		try (var zip = new java.util.zip.ZipFile(nativeFixture.toFile())) { original = zip.getInputStream(zip.getEntry(TARGET + ".class")).readAllBytes(); }
		Path binary = work.resolve("native.bin"), index = work.resolve("native-index.tsv"); Files.write(binary, original);
		Files.writeString(index, "# forbric-native-reference-v1\n" + TARGET + "\t" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original)) + "\n");
		String prefix = "META-INF/forbric/native-reference/FABRIC/";
		fixture = WeaveHarness.fixture(work, "thinnedcall", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG), prefix + "index.tsv", index, prefix + TARGET + ".class.bin", binary));
		nativeRun = WeaveHarness.run(work, "native", nativeFixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT, "fixture.thinnedcall.Probe", "probe", Map.of());
		moved = run("moved", Map.of());
		off = run("off", Map.of(ThinnedCallOrdinals.PROPERTY, "off"));
	}

	@Test void theHookRunsBeforeTheStackInTheHandIsUsed() throws Exception {
		assertTrue(nativeRun.printedLine(HOOKED), nativeRun.describe());
		assertTrue(movedHolds(moved), moved.describe() + "\nfindings: " + moved.findings());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	@Test void switchedOffTheHookNeverRunsAndIsReported() {
		assertTrue(offHolds(off), off.describe() + "\nfindings: " + off.findings());
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryMoveAssertion() {
		assertTrue(movedHolds(moved) && !movedHolds(off), "the move predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(moved), "the control predicate does not separate the runs");
	}

	private static boolean movedHolds(WeaveHarness.Result run) {
		return run.printedLine(HOOKED) && run.printed(MOVED_LOG) && losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printedLine(PLAIN) && !run.printed(MOVED_LOG) && !losses(run).isEmpty();
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.confirmedRequired()).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.thinnedcall.Probe", "probe", properties);
	}
}
