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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;

/**
 * PostMixinFixups' delegated-constructor replay through the real weave: Sodium's (Fabric) quad mixin initialises its own
 * field in a RETURN injection into the vanilla-shaped {@code BakedQuad} constructor, which NeoForge's class turned into a
 * delegation to its wider, canonical one.
 *
 * <p>The probe builds a quad through each constructor and asks the mixin's added method. With the fixups on, the
 * injection Mixin wove at the end of the delegating constructor moves into the one it delegates to: both quads have
 * their face, and the narrow path still runs the handler once. With {@code -Dforbric.postMixinFixups=off} the handler
 * runs only for callers of the vanilla-shaped constructor, and a quad built the canonical way has none: the null that
 * made Sodium's mesher throw. The same mixin declared by a NeoForge mod is left where it was written, as on NeoForge,
 * whose class it is.
 */
class PostMixinFixupsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/postmixinfixups");
	private static final String CONFIG = "postmixinfixups.mixins.json";
	private static final String MOD = "sodium";
	/** PostMixinFixups' switch; its constant is package-private, so the census checks the name instead. */
	private static final String SWITCH = "forbric.postMixinFixups";
	private static final String QUAD = "net/minecraft/client/renderer/block/model/BakedQuad";
	private static final String NARROW = "([IILjava/lang/String;)V";
	private static final String WIDE = "([IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";
	private static final String BOTH_FACED = WeaveHarnessMain.DONE + " narrow=face(north) wide=face(up)";
	private static final String WIDE_UNFACED = WeaveHarnessMain.DONE + " narrow=face(north) wide=null";
	private static final String REPLAYED = "[Forbric/Mixin] post-mixin repair: replayed net.minecraft.client.renderer.block.model"
			+ ".BakedQuad's constructor injection ";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result fixed, off, neoforge;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(4, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "postmixinfixups", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		fixed = run("fixed", Ecosystem.FABRIC, Map.of());
		off = run("fixups-off", Ecosystem.FABRIC, Map.of(SWITCH, "off"));
		neoforge = run("neoforge-mixin", Ecosystem.NEOFORGE, Map.of());
	}

	@Test void aQuadBuiltEitherWayHasItsFace() throws Exception {
		assertTrue(fixed.printed(BOTH_FACED), fixed.describe());
		assertTrue(fixed.printed(REPLAYED), fixed.describe());
		// Moved, not copied: the narrow path reaches the handler once, through the constructor it delegates to.
		assertEquals(Map.of(NARROW, 0L, WIDE, 1L), handlerCalls(fixed), fixed.describe());
		assertEquals(List.of(), unsettled(fixed), fixed.findings().toString());
		WeaveHarness.assertWovenAndVerified(fixed, QUAD, fixture);
	}

	@Test void switchedOffAQuadBuiltTheCanonicalWayHasNoFace() throws Exception {
		assertTrue(off.printed(WIDE_UNFACED), off.describe());
		assertFalse(off.printed("post-mixin repair"), off.describe());
		assertEquals(Map.of(NARROW, 1L, WIDE, 0L), handlerCalls(off), off.describe());
		// The injector attached where it was written; nothing reports the constructor its callers skip.
		assertEquals(List.of(), unsettled(off), off.findings().toString());
		WeaveHarness.assertWovenAndVerified(off, QUAD, fixture);
	}

	/** A NeoForge mixin's native class is the base's own, which delegates the same way: nothing moves. */
	@Test void aNeoForgeMixinStaysWhereItWasWritten() throws Exception {
		assertTrue(unfacedHolds(neoforge), neoforge.describe());
		assertFalse(neoforge.printed("post-mixin repair"), neoforge.describe());
		assertEquals(List.of(), unsettled(neoforge), neoforge.findings().toString());
		WeaveHarness.assertWovenAndVerified(neoforge, QUAD, fixture);
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryReplayAssertion() throws Exception {
		assertTrue(facedHolds(fixed) && !facedHolds(off), "the fixed predicate does not separate the runs");
		assertTrue(unfacedHolds(off) && !unfacedHolds(fixed), "the control predicate does not separate the runs");
	}

	private static boolean facedHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(BOTH_FACED) && run.printed(REPLAYED) && handlerCalls(run).equals(Map.of(NARROW, 0L, WIDE, 1L));
	}

	private static boolean unfacedHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(WIDE_UNFACED) && !run.printed(REPLAYED) && handlerCalls(run).equals(Map.of(NARROW, 1L, WIDE, 0L));
	}

	/** Each constructor of the defined quad, by descriptor, to its number of calls into the mod's merged handler. */
	private static Map<String, Long> handlerCalls(WeaveHarness.Result run) throws Exception {
		ClassNode quad = new ClassNode();
		new ClassReader(run.defined(QUAD)).accept(quad, 0);
		Map<String, Long> calls = new java.util.HashMap<>();
		for (MethodNode constructor : quad.methods) {
			if (!constructor.name.equals("<init>")) continue;
			calls.put(constructor.desc, Stream.of(constructor.instructions.toArray()).filter(i -> i instanceof MethodInsnNode c
					&& c.owner.equals(QUAD) && c.name.startsWith("handler$") && c.name.endsWith("computeNormalFace")).count());
		}
		return calls;
	}

	private static List<WeaveHarness.Finding> unsettled(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.required() && !"RESOLVED".equals(f.confidence())).toList();
	}

	private static WeaveHarness.Result run(String label, Ecosystem ecosystem, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, ecosystem, EnvType.CLIENT,
				"fixture.postmixinfixups.Probe", "probe", properties);
	}
}
