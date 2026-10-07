package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.FabricFluidFlowMixinAdapter;

/**
 * {@code FabricFluidFlowMixinAdapter} and {@code MixinFluidInteractionAdapter} through the real weave TOGETHER, on one
 * merged {@code LiquidBlock}: fabric-block-api's ALLOW veto and Create Fly's fluid reaction both hook vanilla's dead
 * {@code shouldSpreadLiquid} with a handler of the same name and descriptor, and both adapters keep that handler as
 * {@code shouldSpreadLiquid$forbricOriginal} for their wrappers to call.
 *
 * <p>Both renames used to be plain methods, so Mixin merged the first mod's and skipped the second ("Method overwrite
 * conflict ... Skipping method."): the second mod's wrappers called the first mod's body, and with Create Fly installed
 * {@code FluidFlowEvents.ALLOW} never fired. The fix marks each retained original {@code @Unique}, which makes Mixin
 * rename the later one and the calls to it.
 *
 * <p>The probe registers one ALLOW listener that records every call and denies flow at "denied", then gives "lava"
 * (where Create's registry reacts), "water" and "denied" each a placement, a neighbour update and a shape update. Runs:
 * <ul>
 *   <li>fixed, Create's config first — the order of the field report, so fabric-block-api's original is the one Mixin
 *       has to rename;</li>
 *   <li>fixed, fabric-block-api's config first — Create's is renamed instead, and the wrappers nest the other way;</li>
 *   <li>control, {@code -Dforbric.fabricFluidFlow=off} — the symptom the fix removes, reached by a switch: Fabric's
 *       veto binds to the dead method, so the listener is never asked and the denied position floods, silently.</li>
 * </ul>
 * The fix itself has no switch, so the control cannot be "the same run without {@code @Unique}". It is the observable
 * outcome of the defect instead, and the conflict's own mechanism is pinned in the fixed runs: no overwrite conflict is
 * printed, and each mod's wrappers call a distinct original merged from that mod's own mixin — which is exactly what
 * the conflict broke. {@code FabricFluidFlowMixinAdapterTest} checks that {@code retainOriginal} adds the annotation.
 */
class FluidPairMixinAdaptersWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/fluidpair");
	private static final String CREATE_CONFIG = "create.mixins.json";
	private static final String FABRIC_CONFIG = "fabric-block-api-v1.mixins.json";
	private static final String CREATE_MOD = "create";
	private static final String FABRIC_MOD = "fabric-block-api-v1";
	private static final String CREATE_MIXIN = "com.zurrtum.create.mixin.LiquidBlockMixin";
	private static final String FABRIC_MIXIN = "net.fabricmc.fabric.mixin.block.LiquidBlockMixin";
	private static final String TARGET = "net/minecraft/world/level/block/LiquidBlock";
	private static final String HANDLER = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/world/level/block/state/BlockState;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
	private static final String CREATE_LOG = "[Forbric/Mixin] original fluid interaction callback now guards both live carrier reaction sites";
	private static final String FABRIC_LOG = "[Forbric/FluidFlow] Fabric's original ALLOW callback now guards both carrier interactions";
	private static final String CONFLICT = "Method overwrite conflict";

	/** Fabric's wrapper is outermost: the listener is asked at "lava" before Create reacts. */
	private static final String CREATE_FIRST = "place lava: allow lava, create reacted lava"
			+ " | neighbor lava: allow lava, create reacted lava | shape lava: allow lava, flow lava"
			+ " | place water: allow water, forge none water, flow water"
			+ " | neighbor water: allow water, neoforge none water, flow water | shape water: allow water, flow water"
			+ " | place denied: deny denied | neighbor denied: deny denied | shape denied: deny denied";
	/** Create's wrapper is outermost: its reaction at "lava" settles the update before the listener is asked. */
	private static final String FABRIC_FIRST = "place lava: create reacted lava"
			+ " | neighbor lava: create reacted lava | shape lava: allow lava, flow lava"
			+ " | place water: allow water, forge none water, flow water"
			+ " | neighbor water: allow water, neoforge none water, flow water | shape water: allow water, flow water"
			+ " | place denied: deny denied | neighbor denied: deny denied | shape denied: deny denied";
	/** The listener is never asked, and "denied" flows on every update like "water". */
	private static final String SILENT_VETO = "place lava: create reacted lava"
			+ " | neighbor lava: create reacted lava | shape lava: flow lava"
			+ " | place water: forge none water, flow water | neighbor water: neoforge none water, flow water"
			+ " | shape water: flow water | place denied: forge none denied, flow denied"
			+ " | neighbor denied: neoforge none denied, flow denied | shape denied: flow denied";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result createFirst;
	private static WeaveHarness.Result fabricFirst;
	private static WeaveHarness.Result fabricOff;

	@BeforeAll static void weaveAll() throws Exception {
		fixture = WeaveHarness.fixture(work, "fluidpair", sources(),
				Map.of(CREATE_CONFIG, SOURCES.resolve(CREATE_CONFIG), FABRIC_CONFIG, SOURCES.resolve(FABRIC_CONFIG)));
		var create = new WeaveHarness.Config(CREATE_CONFIG, CREATE_MOD, Ecosystem.FABRIC);
		var fabric = new WeaveHarness.Config(FABRIC_CONFIG, FABRIC_MOD, Ecosystem.FABRIC);
		createFirst = run("create-first", List.of(create, fabric), Map.of());
		fabricFirst = run("fabric-first", List.of(fabric, create), Map.of());
		fabricOff = run("fabric-off", List.of(create, fabric), Map.of(FabricFluidFlowMixinAdapter.PROPERTY, "off"));
	}

	@Test void withCreateFirstBothOriginalsRunAndFabricsVetoStopsFlow() throws Exception {
		assertTrue(fixedHolds(createFirst, CREATE_FIRST), createFirst.describe() + "\nfindings " + createFirst.findings());
		assertTrue(createFirst.printed(WeaveHarnessMain.REGISTERED + CREATE_CONFIG + ", " + FABRIC_CONFIG), createFirst.describe());
		WeaveHarness.assertWovenAndVerified(createFirst, TARGET, fixture);
	}

	@Test void withFabricFirstBothOriginalsRunAndFabricsVetoStopsFlow() throws Exception {
		assertTrue(fixedHolds(fabricFirst, FABRIC_FIRST), fabricFirst.describe() + "\nfindings " + fabricFirst.findings());
		assertTrue(fabricFirst.printed(WeaveHarnessMain.REGISTERED + FABRIC_CONFIG + ", " + CREATE_CONFIG), fabricFirst.describe());
		WeaveHarness.assertWovenAndVerified(fabricFirst, TARGET, fixture);
	}

	@Test void withFabricsAdapterOffTheVetoBindsToTheDeadMethodAndTheDeniedFluidFloods() throws Exception {
		assertTrue(controlHolds(fabricOff), fabricOff.describe() + "\nfindings " + fabricOff.findings());
		WeaveHarness.assertWovenAndVerified(fabricOff, TARGET, fixture);
	}

	/** Each run must be told apart from the others by the very predicates the test uses on them. */
	@Test void theControlFlipsEveryAssertion() throws Exception {
		assertTrue(fixedHolds(createFirst, CREATE_FIRST) && !fixedHolds(fabricOff, CREATE_FIRST),
				"create-first predicate does not separate the runs");
		assertTrue(fixedHolds(fabricFirst, FABRIC_FIRST) && !fixedHolds(fabricOff, FABRIC_FIRST),
				"fabric-first predicate does not separate the runs");
		assertTrue(controlHolds(fabricOff) && !controlHolds(createFirst) && !controlHolds(fabricFirst),
				"control predicate does not separate the runs");
	}

	/**
	 * Both originals ran — Create reacted where its registry says so, Fabric's listener was asked and its veto held —
	 * both adapters reported, Mixin skipped nothing, each mod's wrappers call its own original, and neither mod has a
	 * finding.
	 */
	private static boolean fixedHolds(WeaveHarness.Result run, String trail) throws Exception {
		return returned(run, trail) && run.printed(CREATE_LOG) && run.printed(FABRIC_LOG) && !run.printed(CONFLICT)
				&& eachModCallsItsOwnOriginal(run) && !callsHandlerOf(run, "shouldSpreadLiquid", FABRIC_MIXIN)
				&& !callsHandlerOf(run, "shouldSpreadLiquid", CREATE_MIXIN) && findings(run).isEmpty();
	}

	/**
	 * Create adapted and reacting, Fabric's handler still injected into the dead {@code shouldSpreadLiquid}, the
	 * listener never asked — and nothing reported it.
	 */
	private static boolean controlHolds(WeaveHarness.Result run) throws Exception {
		return returned(run, SILENT_VETO) && run.printed(CREATE_LOG) && !run.printed("[Forbric/FluidFlow]")
				&& !run.printed(CONFLICT) && callsHandlerOf(run, "shouldSpreadLiquid", FABRIC_MIXIN)
				&& !eachModCallsItsOwnOriginal(run) && findings(run).isEmpty();
	}

	/** The probe's whole return line: a value that merely starts with the expected one is a different outcome. */
	private static boolean returned(WeaveHarness.Result run, String trail) {
		String line = WeaveHarnessMain.DONE + " " + trail;
		return run.output().lines().anyMatch(line::equals);
	}

	/**
	 * What the overwrite conflict broke: per mod, the wrappers merged from its mixin call exactly one handler-shaped
	 * LiquidBlock method, that method was merged from the same mixin, and the two mods' originals are different methods.
	 */
	private static boolean eachModCallsItsOwnOriginal(WeaveHarness.Result run) throws Exception {
		ClassNode node = woven(run);
		Set<String> originals = new HashSet<>();
		for (String mixin : List.of(CREATE_MIXIN, FABRIC_MIXIN)) {
			Set<String> called = new HashSet<>();
			for (MethodNode method : node.methods) {
				if (!mixin.equals(mergedFrom(method)) || !method.name.contains("forbric$fluidFlow$")) continue;
				for (var insn : method.instructions) {
					if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET) && call.desc.equals(HANDLER)) called.add(call.name);
				}
			}
			if (called.size() != 1) return false;
			String original = called.iterator().next();
			MethodNode body = node.methods.stream().filter(m -> m.name.equals(original) && m.desc.equals(HANDLER)).findFirst().orElse(null);
			if (body == null || !mixin.equals(mergedFrom(body))) return false;
			originals.add(original);
		}
		return originals.size() == 2;
	}

	/** Whether {@code method} of the woven LiquidBlock calls a method merged from {@code mixin}. */
	private static boolean callsHandlerOf(WeaveHarness.Result run, String method, String mixin) throws Exception {
		ClassNode node = woven(run);
		MethodNode body = node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
		for (var insn : body.instructions) {
			if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(TARGET)) continue;
			if (node.methods.stream().anyMatch(m -> m.name.equals(call.name) && m.desc.equals(call.desc) && mixin.equals(mergedFrom(m)))) return true;
		}
		return false;
	}

	private static ClassNode woven(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		return node;
	}

	/** The mixin a woven method came from, by Mixin's own {@code @MixinMerged} record; null for the target's own. */
	private static String mergedFrom(MethodNode method) {
		if (method.visibleAnnotations == null) return null;
		for (AnnotationNode annotation : method.visibleAnnotations) {
			if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;") || annotation.values == null) continue;
			for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
				if ("mixin".equals(annotation.values.get(i))) return (String) annotation.values.get(i + 1);
			}
		}
		return null;
	}

	private static List<WeaveHarness.Finding> findings(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(CREATE_MOD) || f.modId().equals(FABRIC_MOD)).toList();
	}

	private static WeaveHarness.Result run(String label, List<WeaveHarness.Config> configs, Map<String, String> properties)
			throws Exception {
		return WeaveHarness.run(work, label, fixture, configs, List.of(), EnvType.SERVER, "fixture.fluidpair.Probe", "probe",
				properties);
	}

	private static List<Path> sources() throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}
}
