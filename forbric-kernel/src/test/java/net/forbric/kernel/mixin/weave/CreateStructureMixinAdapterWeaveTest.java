package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinStructurePlacementAdapter;

/**
 * {@code MixinStructurePlacementAdapter} through the real weave: Create Fly's three StructureTemplate hooks, written for
 * vanilla's {@code placeEntities}, on a merged StructureTemplate whose placeInWorld hands its entities to NeoForge's
 * {@code addEntitiesToWorld} instead.
 *
 * <p>The probe places one template twice, first with the mod's control processor and then with none, so the woven
 * code says whether all three hooks moved together: the processor is picked up before the entities are added
 * ({@code setProcessors}), applied to them while they are iterated ({@code getIterator}), and dropped afterwards
 * ({@code clearProcessors}) — a second placement that still sees it would mean the cleanup stayed behind. With
 * {@code -Dforbric.structurePlacementCallbacks=off} none of the three binds: both placements add plain entities and each
 * hook is the mod's required loss — with MixinRetarget's R7 off too, since R7 now moves the two {@code @Inject}s (the
 * pickup's point, the cleanup's selector) along MergedBaseCalleeSwaps' REPLACED row for any Fabric mod. With only the
 * adapter off, R7 binds those two and the iterator wrap inside the method, this adapter's own move, is the one loss.
 */
class CreateStructureMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/createstructure");
	private static final String CONFIG = "createstructure.mixins.json";
	private static final String MOD = "create";
	private static final String TARGET = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate";
	private static final String MIXIN = "com.zurrtum.create.mixin.StructureTemplateMixin";
	private static final List<String> HOOKS = List.of("setProcessors", "getIterator", "clearProcessors");

	private static final String ADAPTED = WeaveHarnessMain.DONE + " tamed pig, tamed cow | pig, cow";
	private static final String UNADAPTED = WeaveHarnessMain.DONE + " pig, cow | pig, cow";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;
	private static WeaveHarness.Result r7Only;

	@BeforeAll static void weave() throws Exception {
		String structure = "net/minecraft/world/level/levelgen/structure/templatesystem/";
		fixture = WeaveHarness.fixture(work, "createstructure", List.of(
				SOURCES.resolve("net/minecraft/world/level/ServerLevelAccessor.java"),
				SOURCES.resolve("net/minecraft/world/level/Level.java"),
				SOURCES.resolve("net/minecraft/core/BlockPos.java"),
				SOURCES.resolve("net/minecraft/util/RandomSource.java"),
				SOURCES.resolve("net/minecraft/util/ProblemReporter.java"),
				SOURCES.resolve(structure + "StructureProcessor.java"),
				SOURCES.resolve(structure + "StructurePlaceSettings.java"),
				SOURCES.resolve(structure + "StructureTemplate.java"),
				SOURCES.resolve("fixture/createstructure/ControlProcessor.java"),
				SOURCES.resolve("fixture/createstructure/Probe.java"),
				SOURCES.resolve("com/zurrtum/create/mixin/StructureTemplateMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		adapted = run("adapted", Map.of());
		off = run("adapter-off", Map.of(MixinStructurePlacementAdapter.PROPERTY, "off", "forbric.mixinRetarget.replacedCall", "off"));
		r7Only = run("r7-only", Map.of(MixinStructurePlacementAdapter.PROPERTY, "off"));
	}

	/** R7 alone: the pickup and the cleanup follow the replaced call, the iterator wrap is the adapter's and is lost. */
	@Test void withOnlyTheGeneralRuleTheWrapIsTheOneLoss() throws Exception {
		assertTrue(returned(r7Only, UNADAPTED), r7Only.describe());
		assertEquals(List.of("getIterator"), injectorLosses(r7Only).stream().map(f -> hookOf(f.id())).toList(), r7Only.findings().toString());
		assertEquals(List.of("setProcessors"), hooksCalledFrom(r7Only, "placeInWorld"), r7Only.describe());
		assertEquals(List.of("clearProcessors"), hooksCalledFrom(r7Only, "addEntitiesToWorld"), r7Only.describe());
		WeaveHarness.assertWovenAndVerified(r7Only, TARGET, fixture);
	}

	@Test void setupIterationAndCleanupRunTogetherOnTheLivePlacementCall() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings " + adapted.findings() + "\n" + calls(adapted));
		assertEquals(List.of(), injectorLosses(adapted), adapted.describe());
		assertTrue(mixinRow(adapted).stream().allMatch(f -> f.confidence().equals("RESOLVED")), adapted.findings().toString());
		// Where each hook landed in the defined class: the pickup in placeInWorld, the other two in NeoForge's method.
		assertEquals(List.of("setProcessors"), hooksCalledFrom(adapted, "placeInWorld"), adapted.describe());
		assertEquals(List.of("getIterator", "clearProcessors"), hooksCalledFrom(adapted, "addEntitiesToWorld"), adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted, TARGET, fixture);
	}

	@Test void switchedOffNoHookBindsAndEachIsTheModsLoss() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
		assertEquals(HOOKS.stream().sorted().toList(), injectorLosses(off).stream().map(f -> hookOf(f.id())).sorted().toList(),
				off.findings().toString());
		assertEquals(List.of(), hooksCalledFrom(off, "placeInWorld"), off.describe());
		assertEquals(List.of(), hooksCalledFrom(off, "addEntitiesToWorld"), off.describe());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	/** Each run's predicate must fail on the other, or the control proves nothing about the adapter. */
	@Test void theControlFlipsEveryAdapterAssertion() throws Exception {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapter predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) throws Exception {
		return returned(run, ADAPTED) && injectorLosses(run).isEmpty()
				&& hooksCalledFrom(run, "addEntitiesToWorld").equals(List.of("getIterator", "clearProcessors"));
	}

	private static boolean offHolds(WeaveHarness.Result run) throws Exception {
		return returned(run, UNADAPTED) && injectorLosses(run).size() == HOOKS.size()
				&& injectorLosses(run).stream().allMatch(WeaveHarness.Finding::required)
				&& hooksCalledFrom(run, "addEntitiesToWorld").isEmpty();
	}

	/** The probe's whole return line: a value that merely starts with the expected one is a different outcome. */
	private static boolean returned(WeaveHarness.Result run, String line) {
		return run.output().lines().anyMatch(line::equals);
	}

	/** The final audit's rows for the mod's injectors that did not attach. */
	private static List<WeaveHarness.Finding> injectorLosses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& !f.confidence().equals("RESOLVED")).toList();
	}

	private static List<WeaveHarness.Finding> mixinRow(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().equals("mixin:" + CONFIG + ":" + MIXIN)).toList();
	}

	private static String hookOf(String findingId) {
		String handler = findingId.substring(findingId.indexOf('#') + 1);
		return handler.substring(0, handler.indexOf('('));
	}

	/** The mod's hooks, in call order, that {@code method} of the defined StructureTemplate calls. */
	private static List<String> hooksCalledFrom(WeaveHarness.Result run, String method) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		MethodNode body = node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
		List<String> hooks = new java.util.ArrayList<>();
		for (var insn : body.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET)) {
				// A merged handler is named handler$…$create$<hook>; MixinExtras calls a wrap through its bridge, …$<hook>$mixinextras$bridge$n.
				List<String> parts = List.of(call.name.split("\\$"));
				HOOKS.stream().filter(parts::contains).forEach(hooks::add);
			}
		}
		return hooks;
	}

	/** Every call the defined StructureTemplate makes to itself, for failure messages. */
	private static String calls(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		StringBuilder out = new StringBuilder("defined " + TARGET + ":\n");
		for (MethodNode method : node.methods) {
			out.append("  ").append(method.name).append(method.desc).append('\n');
			for (var insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET)) out.append("    -> ").append(call.name).append('\n');
			}
		}
		return out.toString();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.createstructure.Probe", "probe", properties);
	}
}
