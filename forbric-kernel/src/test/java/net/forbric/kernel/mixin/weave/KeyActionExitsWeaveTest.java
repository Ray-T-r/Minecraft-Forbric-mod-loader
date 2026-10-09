package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinKeyActionAdapter;

/**
 * {@code MixinKeyActionAdapter} through the real weave, on a CLIENT run, with an unrelated guest
 * ({@code com.example.keyecho}) hooking {@code keyPress} by vanilla's count: {@code RETURN} ordinal 4, ordinal 5 and
 * {@code TAIL}. The native body is a stand-in with vanilla 26.2's six returns in vanilla's order; the game body joins
 * the global key, the release and the press into one {@code ClientHooks.onKeyInput} call before its final return.
 *
 * <p>The probe presses, repeats and releases one key. The native run is the reference: ordinal 4 is the release, and
 * ordinal 5 and TAIL are the final return, which a release never reaches. Adapted, the game run hears exactly that —
 * with NeoForge's event in between. With {@code -Dforbric.keyActionCallbacks=off} both ordinal hooks find no return
 * of their number and are lost, and TAIL also hears the release.
 */
class KeyActionExitsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/keyexits");
	private static final String CONFIG = "keyexits.mixins.json";
	private static final String MOD = "keyecho";
	private static final String TARGET = "net/minecraft/client/KeyboardHandler";

	private static final String NATIVE = WeaveHarnessMain.DONE + " 1=[keyecho final G, keyecho tail] | 2=[keyecho final G,"
			+ " keyecho tail] | 0=[keyecho release G]";
	private static final String ADAPTED = WeaveHarnessMain.DONE + " 1=[neoforge G1, keyecho final G, keyecho tail]"
			+ " | 2=[neoforge G2, keyecho final G, keyecho tail] | 0=[keyecho release G, neoforge G0]";
	private static final String UNADAPTED = WeaveHarnessMain.DONE + " 1=[neoforge G1, keyecho tail]"
			+ " | 2=[neoforge G2, keyecho tail] | 0=[neoforge G0, keyecho tail]";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result nativeRun, adapted, off;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(7, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		Path nativeSource = SOURCES.resolve("native/" + TARGET + ".java"), gameSource = SOURCES.resolve(TARGET + ".java");
		List<Path> game = new ArrayList<>(sources), original = new ArrayList<>(sources);
		game.remove(nativeSource);
		original.remove(gameSource);
		Path nativeFixture = WeaveHarness.fixture(work, "native-keyexits", original, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		fixture = WeaveHarness.fixture(work, "keyexits", game, withNativeReference(work, nativeFixture, TARGET, CONFIG, SOURCES.resolve(CONFIG)));
		nativeRun = run("native", nativeFixture, Map.of());
		adapted = run("adapted", fixture, Map.of());
		off = run("adapter-off", fixture, Map.of(MixinKeyActionAdapter.PROPERTY, "off"));
	}

	@Test void theGameHearsEachActionWhereTheNativeBodyDoes() throws Exception {
		assertTrue(nativeRun.printedLine(NATIVE), nativeRun.describe());
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings " + adapted.findings());
		assertEquals(trail(NATIVE), trail(ADAPTED).replaceAll("neoforge G\\d(, )?", "").replace(", ]", "]"),
				"apart from NeoForge's event, the adapted trail is the native one");
		WeaveHarness.assertWovenAndVerified(adapted, TARGET, fixture);
	}

	@Test void switchedOffTheOrdinalHooksAreLostAndTailHearsTheRelease() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	/** Each run's predicate must fail on the other, or the control proves nothing about the adapter. */
	@Test void theControlFlipsEveryAdapterAssertion() {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapter predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	/**
	 * {@code resources} plus the native reference a guest's native ordinals are read against: the class compiled into
	 * {@code nativeFixture}, hash-pinned under the FABRIC prefix, exactly as the merged-base builder emits it.
	 */
	static Map<String, Path> withNativeReference(Path work, Path nativeFixture, String target, String config, Path configFile)
			throws Exception {
		byte[] original;
		try (var zip = new java.util.zip.ZipFile(nativeFixture.toFile())) {
			original = zip.getInputStream(zip.getEntry(target + ".class")).readAllBytes();
		}
		String label = nativeFixture.getFileName().toString().replace(".jar", "");
		Path binary = work.resolve(label + ".bin"), index = work.resolve(label + "-index.tsv");
		Files.write(binary, original);
		Files.writeString(index, "# forbric-native-reference-v1\n" + target + "\t"
				+ HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)) + "\n");
		String prefix = "META-INF/forbric/native-reference/FABRIC/";
		return Map.of(config, configFile, prefix + "index.tsv", index, prefix + target + ".class.bin", binary);
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) {
		return run.printedLine(ADAPTED) && losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		List<WeaveHarness.Finding> losses = losses(run);
		return run.printedLine(UNADAPTED) && losses.size() == 2 && losses.stream().allMatch(WeaveHarness.Finding::required)
				&& losses.stream().map(WeaveHarness.Finding::id).collect(Collectors.joining()).contains("#heardRelease(")
				&& losses.stream().map(WeaveHarness.Finding::id).collect(Collectors.joining()).contains("#heardFinal(");
	}

	private static String trail(String line) {
		return line.substring(WeaveHarnessMain.DONE.length() + 1);
	}

	/** The final audit's rows for the guest's injectors that did not attach. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& !f.confidence().equals("RESOLVED")).toList();
	}

	private static WeaveHarness.Result run(String label, Path jar, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, jar, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT, "fixture.keyexits.Probe",
				"probe", properties);
	}
}
