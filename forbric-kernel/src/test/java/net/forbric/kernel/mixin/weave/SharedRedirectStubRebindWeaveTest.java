package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;

/**
 * A {@code @Redirect} that shares state with an {@code @Inject} beside it, both bound by Mixin to a carrier's delegating
 * stub, moves with its sibling onto the body through {@code MixinStubRebind} — through the real weave, on a CLIENT run.
 *
 * <p>The fixture's {@code SectionCompiler} keeps vanilla's four-argument {@code compile} as a stub forwarding to NeoForge's
 * five-argument one, which walks the blocks, tessellates them into the {@code startedLayers} map and adds the extra
 * geometry; the fixture also carries the class a Fabric mod was compiled against (vanilla's, where the four-argument
 * {@code compile} is the body) as its native reference. The guest pair shares a renderer and an emitter between a setup
 * {@code @Inject} that also takes {@code startedLayers} by name and a tessellation {@code @Redirect}. The live overload
 * tessellates each block once, as vanilla's body did, so the redirect replaces only the calls it replaced natively, and
 * the pair moves together: every block is emitted by Fabric's renderer and NeoForge's extra geometry is kept.
 *
 * <p>The same pair is woven twice: in the shape and names of fabric-renderer-api's own {@code SectionCompilerMixin}, and
 * under names that share nothing with it (package, class, handlers, share keys, selector spelling). Both behave alike,
 * which is what retiring the name-keyed adapter that used to move the first one only requires. With
 * {@code -Dforbric.mixinStubRebind.shared=off} the pair stays on the stub, both injectors are reported, and vanilla's
 * tessellation draws the chunk.
 */
class SharedRedirectStubRebindWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/fabricsectioncompiler");
	private static final String FABRIC_CONFIG = "fabricsectioncompiler.mixins.json", RENAMED_CONFIG = "qzxrender.mixins.json";
	private static final String MOD = "fabricsectioncompiler", RENAMED_MOD = "qzxrender";
	private static final String COMPILER = "net/minecraft/client/renderer/chunk/SectionCompiler";
	private static final String FABRIC_QUADS = "quads=[fabric:stone, fabric:ctm_glass, neoforge:fluid]";
	private static final String VANILLA_QUADS = "quads=[vanilla:stone, vanilla:ctm_glass, neoforge:fluid]";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result fabricNamed;
	private static WeaveHarness.Result renamed;
	private static WeaveHarness.Result off;

	@BeforeAll static void weaveAll() throws Exception {
		List<Path> common = sources(p -> !p.startsWith(SOURCES.resolve("native")));
		List<Path> platform = common.stream().filter(p -> !p.startsWith(SOURCES.resolve("fixture"))
				&& !p.startsWith(SOURCES.resolve("net/minecraft/client/renderer/chunk/SectionCompiler.java"))
				&& !p.toString().contains("/mixin/")).toList();
		List<Path> nativeSources = new ArrayList<>(platform);
		nativeSources.add(SOURCES.resolve("native/net/minecraft/client/renderer/chunk/SectionCompiler.java"));
		Path nativeJar = WeaveHarness.fixture(work, "native", nativeSources, Map.of(), List.of("-g"));
		byte[] nativeBytes;
		try (var jar = new java.util.zip.ZipFile(nativeJar.toFile())) {
			nativeBytes = jar.getInputStream(jar.getEntry(COMPILER + ".class")).readAllBytes();
		}
		Path bin = work.resolve("SectionCompiler.class.bin");
		Files.write(bin, nativeBytes);
		Path index = work.resolve("index.tsv");
		Files.writeString(index, "# forbric-native-reference-v1\n" + COMPILER + "\t"
				+ HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(nativeBytes)) + "\n");
		fixture = WeaveHarness.fixture(work, MOD, common, Map.of(FABRIC_CONFIG, SOURCES.resolve(FABRIC_CONFIG),
				RENAMED_CONFIG, SOURCES.resolve(RENAMED_CONFIG),
				"META-INF/forbric/native-reference/FABRIC/index.tsv", index,
				"META-INF/forbric/native-reference/FABRIC/" + COMPILER + ".class.bin", bin), List.of("-g"));
		fabricNamed = run("fabric-named", FABRIC_CONFIG, MOD, Map.of());
		renamed = run("renamed", RENAMED_CONFIG, RENAMED_MOD, Map.of());
		off = run("renamed-off", RENAMED_CONFIG, RENAMED_MOD, Map.of("forbric.mixinStubRebind.shared", "off"));
	}

	@Test void fabricsRendererEmitsEveryBlockOfTheLiveCompileUnderFabricApisNames() throws Exception {
		assertTrue(movedHolds(fabricNamed, MOD), fabricNamed.describe() + "\nfindings: " + fabricNamed.findings());
		assertTrue(WeaveHarness.hasMergedMethod(fabricNamed.defined(COMPILER)), fabricNamed.describe());
		WeaveHarness.assertWovenAndVerified(fabricNamed, COMPILER, fixture);
	}

	@Test void andUnderNamesThatShareNothingWithThem() throws Exception {
		assertTrue(movedHolds(renamed, RENAMED_MOD), renamed.describe() + "\nfindings: " + renamed.findings());
		assertTrue(WeaveHarness.hasMergedMethod(renamed.defined(COMPILER)), renamed.describe());
		WeaveHarness.assertWovenAndVerified(renamed, COMPILER, fixture);
	}

	@Test void withSharedMovesOffThePairStaysOnTheStubAndBothInjectorsAreReported() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings: " + off.findings());
		WeaveHarness.assertWovenAndVerified(off, COMPILER, fixture);
	}

	/** The runs and their control must be told apart by the very predicates the test uses on them. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(movedHolds(renamed, RENAMED_MOD) && !movedHolds(off, RENAMED_MOD), "moved predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(renamed), "control predicate does not separate the runs");
	}

	private static boolean movedHolds(WeaveHarness.Result run, String mod) {
		return run.printed(WeaveHarnessMain.DONE + " " + FABRIC_QUADS) && losses(run, mod).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		List<WeaveHarness.Finding> losses = losses(run, RENAMED_MOD);
		return run.printed(WeaveHarnessMain.DONE + " " + VANILLA_QUADS) && losses.size() == 2
				&& losses.stream().anyMatch(f -> f.id().contains("#qzxPrepare"))
				&& losses.stream().anyMatch(f -> f.id().contains("#qzxPaint"));
	}

	/** Every required injector of the mod the runtime audit reports, at any confidence. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run, String mod) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.modId().equals(mod)
				&& f.required()).toList();
	}

	private static WeaveHarness.Result run(String label, String config, String mod, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, config, mod, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.fabricsectioncompiler.Probe", "run", properties);
	}

	private static List<Path> sources(java.util.function.Predicate<Path> keep) throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			return walk.filter(p -> p.toString().endsWith(".java")).filter(keep).sorted().toList();
		}
	}
}
