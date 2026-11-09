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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinPrivateDefaultCollision;

/**
 * MixinPrivateDefaultCollision through the real weave: fabric-entity-events' {@code private LivingEntity self()}, merged by
 * Mixin into a {@code LivingEntity} that implements NeoForge's {@code ILivingEntityExtension}, which defaults
 * {@code LivingEntity self()}.
 *
 * <p>The probe ticks an axolotl (a class below {@code LivingEntity}; the guest's handler calls its own {@code self()}) and
 * asks it an interface default that reaches the entity through the interface's {@code self()}. Renamed, the guest's method
 * no longer hides the default: the handler sees the axolotl and so does the interface. With
 * {@code -Dforbric.mixinPrivateDefaults=off} the merged private method keeps the name, HotSpot selects no default for the
 * axolotl, and the interface call throws {@code AbstractMethodError}, as the integrated server did on its first axolotl
 * tick; the guest's own handler still runs.
 */
class MixinPrivateDefaultCollisionWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/privatedefault");
	private static final String CONFIG = "privatedefault.mixins.json";
	private static final String MOD = "fabric-entity-events-v1";
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String RENAMED = WeaveHarnessMain.DONE + " tick=fabric:axolotl neoforge=neoforge:axolotl";
	private static final String HIDDEN = WeaveHarnessMain.DONE + " tick=fabric:axolotl neoforge=AbstractMethodError";
	private static final String RENAME_LOG = "guest mixin net.fabricmc.fabric.mixin.entity.event.effect.LivingEntityMixin's private "
			+ "self()Lnet/minecraft/world/entity/LivingEntity; is now self$forbric$";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result renamed, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java")).sorted().toList());
		}
		assertEquals(6, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "privatedefault", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		renamed = run("renamed", Map.of());
		off = run("private-defaults-off", Map.of(MixinPrivateDefaultCollision.PROPERTY, "off"));
		for (WeaveHarness.Result run : List.of(renamed, off)) System.out.println(run.label() + ": " + run.output().lines()
				.filter(line -> line.startsWith(WeaveHarnessMain.DONE)).findFirst().orElse("(the probe printed no result)"));
	}

	@Test void theRenamedMethodNoLongerHidesNeoForgesDefault() throws Exception {
		assertTrue(renamed.printedLine(RENAMED), renamed.describe());
		assertTrue(renamed.printed(RENAME_LOG), renamed.describe());
		assertFalse(declaresSelf(renamed), renamed.describe());
		WeaveHarness.assertWovenAndVerified(renamed, LIVING, fixture);
	}

	@Test void switchedOffTheMergedPrivateMethodHidesTheDefault() throws Exception {
		assertTrue(off.printedLine(HIDDEN), off.describe());
		assertFalse(off.printed(RENAME_LOG), off.describe());
		assertTrue(declaresSelf(off), off.describe());
		WeaveHarness.assertWovenAndVerified(off, LIVING, fixture);
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryAssertion() throws Exception {
		assertTrue(renamedHolds(renamed) && !renamedHolds(off), "the rename predicate does not separate the runs");
		assertTrue(hiddenHolds(off) && !hiddenHolds(renamed), "the control predicate does not separate the runs");
	}

	private static boolean renamedHolds(WeaveHarness.Result run) throws Exception {
		return run.printedLine(RENAMED) && run.printed(RENAME_LOG) && !declaresSelf(run);
	}

	private static boolean hiddenHolds(WeaveHarness.Result run) throws Exception {
		return run.printedLine(HIDDEN) && !run.printed(RENAME_LOG) && declaresSelf(run);
	}

	/** Whether the woven LivingEntity declares a method named self with the interface's descriptor. */
	private static boolean declaresSelf(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(LIVING)).accept(node, 0);
		return node.methods.stream().anyMatch(m -> m.name.equals("self") && m.desc.equals("()L" + LIVING + ";"));
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.privatedefault.Probe", "probe", properties);
	}
}
