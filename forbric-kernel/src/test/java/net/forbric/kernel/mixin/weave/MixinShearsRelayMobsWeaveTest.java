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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinShearsRelay;

/**
 * MixinShearsRelay's mob rows through the real weave: BCLib's tag-based shears wraps of vanilla's {@code stack.is(Items.SHEARS)}
 * in a sheep's and a snow golem's {@code mobInteract}, on NeoForge's mobs, whose {@code mobInteract} reads the held stack and
 * asks nothing about it: NeoForge shears both through {@code ShearsItem.interactLivingEntity}, which only a {@code ShearsItem}
 * reaches.
 *
 * <p>The probe uses vanilla shears, a modded shears item that only carries the common shears tag, and a stick on each mob,
 * in {@code Player.interactOn}'s order (the mob's {@code mobInteract}, then the item's own entity entry), and names who
 * sheared. Relayed, each wrap moves behind a HEAD {@code @Inject} of {@code mobInteract} that asks it vanilla's question
 * about the held stack ({@code KernelMobShears.vanillaIs}) and runs NeoForge's shearing entry for a yes that is not a
 * {@code ShearsItem} ({@code KernelMobShears.shearAsShears}, the production source compiled in): the tagged shears shear
 * both mobs in {@code mobInteract}, vanilla shears are still left to their own entry, the stick shears nothing. With
 * {@code -Dforbric.shearsRelayMobs=off} both wraps stay as compiled, bound nowhere: the tagged shears shear neither mob, and
 * the final audit names both unbound handlers.
 */
class MixinShearsRelayMobsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/shearsrelaymobs");
	private static final String CONFIG = "shearsrelaymobs.mixins.json";
	private static final String MOD = "bclib";
	private static final String SHEEP = "net/minecraft/world/entity/animal/sheep/Sheep";
	private static final String GOLEM = "net/minecraft/world/entity/animal/golem/SnowGolem";
	private static final String RUNTIME = "net/forbric/kernel/runtime/KernelMobShears";
	private static final String RELAYED = WeaveHarnessMain.DONE + " sheep: shears=item tagged=mob stick=pass golem: shears=item tagged=mob stick=pass";
	private static final String UNRELAYED = WeaveHarnessMain.DONE + " sheep: shears=item tagged=pass stick=pass golem: shears=item tagged=pass stick=pass";
	private static final List<String> RELAY_LOGS = List.of(
			"org.betterx.bclib.mixin.common.shears.SheepMixin: bclib_isShears now also answers Sheep.mobInteract, whose shears check NeoForge moved",
			"org.betterx.bclib.mixin.common.shears.SnowGolemMixin: bclib_isShears now also answers SnowGolem.mobInteract, whose shears check NeoForge moved");

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result relayed, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java")).sorted().toList());
		}
		assertEquals(17, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		// The moved handler calls the kernel's game-side KernelMobShears, which ForbricClassLoader must define from a jar it
		// owns; a fresh clone has no compiled runtime source set, so the production source is compiled in here.
		sources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelMobShears.java"));
		fixture = WeaveHarness.fixture(work, "shearsrelaymobs", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		relayed = run("relayed", Map.of());
		off = run("mobs-off", Map.of(MixinShearsRelay.MOB_PROPERTY, "off"));
		for (WeaveHarness.Result run : List.of(relayed, off)) System.out.println(run.label() + ": " + run.output().lines()
				.filter(line -> line.startsWith(WeaveHarnessMain.DONE)).findFirst().orElse("(the probe printed no result)"));
	}

	@Test void aTagOnlyShearsItemShearsBothMobsThroughNeoForgesEntry() throws Exception {
		assertTrue(relayed.printedLine(RELAYED), relayed.describe());
		for (String log : RELAY_LOGS) assertTrue(relayed.printed(log), log + "\n" + relayed.describe());
		assertEquals(List.of(), unbound(relayed), relayed.findings() + "\n" + relayed.describe());
		for (String mob : List.of(SHEEP, GOLEM)) {
			assertTrue(calls(relayed, mob, RUNTIME, "shearAsShears"), mob + "\n" + relayed.describe());
			assertTrue(calls(relayed, mob, RUNTIME, "vanillaIs"), mob + "\n" + relayed.describe());
			WeaveHarness.assertWovenAndVerified(relayed, mob, fixture);
		}
	}

	@Test void switchedOffBothWrapsAreUnboundAndOnlyShearsItemsShear() throws Exception {
		assertTrue(off.printedLine(UNRELAYED), off.describe());
		for (String log : RELAY_LOGS) assertFalse(off.printed(log), off.describe());
		assertEquals(2, unbound(off).size(), off.findings() + "\n" + off.describe());
		for (String mob : List.of(SHEEP, GOLEM)) {
			assertFalse(calls(off, mob, RUNTIME, "shearAsShears"), mob + "\n" + off.describe());
			WeaveHarness.assertWovenAndVerified(off, mob, fixture);
		}
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryRelayAssertion() throws Exception {
		assertTrue(relayedHolds(relayed) && !relayedHolds(off), "the relay predicate does not separate the runs");
		assertTrue(unrelayedHolds(off) && !unrelayedHolds(relayed), "the control predicate does not separate the runs");
	}

	private static boolean relayedHolds(WeaveHarness.Result run) throws Exception {
		return run.printedLine(RELAYED) && RELAY_LOGS.stream().allMatch(run::printed) && unbound(run).isEmpty()
				&& calls(run, SHEEP, RUNTIME, "shearAsShears") && calls(run, GOLEM, RUNTIME, "shearAsShears");
	}

	private static boolean unrelayedHolds(WeaveHarness.Result run) throws Exception {
		return run.printedLine(UNRELAYED) && RELAY_LOGS.stream().noneMatch(run::printed) && unbound(run).size() == 2
				&& !calls(run, SHEEP, RUNTIME, "shearAsShears") && !calls(run, GOLEM, RUNTIME, "shearAsShears");
	}

	/** Whether any method of the woven {@code mob} calls {@code owner.name}: the moved handler is merged into the mob. */
	private static boolean calls(WeaveHarness.Result run, String mob, String owner, String name) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(mob)).accept(node, 0);
		for (MethodNode method : node.methods) {
			for (var insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
			}
		}
		return false;
	}

	/** The final audit's rows for the mod's required injectors that attached nowhere and are not settled either way. */
	private static List<WeaveHarness.Finding> unbound(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& f.id().contains("#bclib_isShears(") && f.required() && !"RESOLVED".equals(f.confidence())).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.shearsrelaymobs.Probe", "probe", properties);
	}
}
