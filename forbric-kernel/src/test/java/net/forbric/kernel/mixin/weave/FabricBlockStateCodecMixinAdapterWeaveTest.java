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
import net.forbric.kernel.mixin.FabricBlockStateCodecMixinAdapter;

/**
 * {@code FabricBlockStateCodecMixinAdapter} through the real weave, on a CLIENT run: with fabric-model-loading's codec
 * redirects applied, a NeoForge custom block-state model still reads as itself (issue #16).
 *
 * <p>The fixture's {@code BlockStateModel.Unbaked} builds its two codec fields with {@code flatComapMap} on codecs
 * NeoForge's hooks supply; the guest interface mixin redirects both calls to Fabric's codecs. Codec, DataResult and
 * the rest are minimal hand-written stand-ins, and the kernel's real {@code KernelBlockStateModelFormats} is compiled
 * into the fixture. With the adapter each redirect keeps the codec it replaced: a variant naming {@code "type"} and a
 * plain variant are read by NeoForge's codec, one naming {@code "fabric:type"} by Fabric's. With
 * {@code -Dforbric.blockStateModelFormats=off} Fabric's codecs read everything, and the NeoForge model is an empty
 * plain variant in both fields. Neither run reports a loss.
 */
class FabricBlockStateCodecMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/fabricblockstatecodec");
	private static final String CONFIG = "fabricblockstatecodec.mixins.json";
	private static final String MOD = "fabricblockstatecodec";
	private static final String UNBAKED = "net/minecraft/client/renderer/block/dispatch/BlockStateModel$Unbaked";
	private static final String BOTH_DIALECTS = "neoforge:custom sophisticatedbackpacks:backpack | fabric:custom continuity:ctm"
			+ " | neoforge:plain minecraft:block/stone | neoforge-weighted:custom sophisticatedbackpacks:backpack";
	private static final String FABRIC_ONLY = "fabric:plain (empty) | fabric:custom continuity:ctm"
			+ " | fabric:plain minecraft:block/stone | fabric-weighted:plain (empty)";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;
	private static WeaveHarness.Result renamed;

	@BeforeAll static void weaveBoth() throws Exception {
		List<Path> sources = new ArrayList<>(sources());
		// The redirects now call the kernel's game-side KernelBlockStateModelFormats, which ForbricClassLoader must define.
		// It logs through and reads the switch of boot-side kernel classes: compiled against their sources, not packed.
		sources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelBlockStateModelFormats.java"));
		fixture = WeaveHarness.fixture(work, MOD, sources, Map.of(CONFIG, SOURCES.resolve(CONFIG), "qzxmodels.mixins.json",
				SOURCES.resolve("qzxmodels.mixins.json")), List.of("-sourcepath", "src/main/java", "-implicit:none"));
		adapted = run("adapted", "on");
		off = run("off", "off");
		renamed = WeaveHarness.run(work, "renamed", fixture, "qzxmodels.mixins.json", "qzxmodels", Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.fabricblockstatecodec.Probe", "run", Map.of());
	}

	@Test void eachDialectIsReadByItsOwnCodec() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings: " + adapted.findings());
		assertTrue(adapted.printed("block-state codec redirect(s) keep NeoForge's codec"), adapted.describe());
		assertTrue(WeaveHarness.hasMergedMethod(adapted.defined(UNBAKED)), adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted, UNBAKED, fixture);
	}

	/** The same redirects under names that share nothing with fabric-model-loading's keep NeoForge's codec alike. */
	@Test void theSameRedirectsUnderUnrelatedNamesKeepBothDialects() throws Exception {
		assertTrue(renamed.printed(WeaveHarnessMain.DONE + " " + BOTH_DIALECTS)
				&& renamed.findings().stream().noneMatch(f -> f.modId().equals("qzxmodels") && f.required()), renamed.describe());
		WeaveHarness.assertWovenAndVerified(renamed, UNBAKED, fixture);
	}

	@Test void withTheSwitchOffNeoForgesModelIsAnEmptyPlainVariant() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings: " + off.findings());
		assertFalse(off.printed("[Forbric/ModelFormats]"), off.describe());
		WeaveHarness.assertWovenAndVerified(off, UNBAKED, fixture);
	}

	/** The kernel classes in the fixture are the game-side one it needs, never a second copy of a boot-side class. */
	@Test void theFixtureCarriesOnlyTheGameSideKernelClass() throws Exception {
		try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(fixture.toFile())) {
			List<String> kernel = jar.stream().map(java.util.zip.ZipEntry::getName).filter(n -> n.startsWith("net/forbric/")).toList();
			assertFalse(kernel.isEmpty(), "KernelBlockStateModelFormats was not packed");
			assertTrue(kernel.stream().allMatch(n -> n.startsWith("net/forbric/kernel/runtime/KernelBlockStateModelFormats")), kernel.toString());
		}
	}

	/** The run and its control must be told apart by the very predicates the test uses on them. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapted predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + BOTH_DIALECTS) && losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + FABRIC_ONLY) && losses(run).isEmpty();
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.required()).toList();
	}

	private static WeaveHarness.Result run(String label, String adapter) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.fabricblockstatecodec.Probe", "run", Map.of(FabricBlockStateCodecMixinAdapter.PROPERTY, adapter));
	}

	private static List<Path> sources() throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}
}
