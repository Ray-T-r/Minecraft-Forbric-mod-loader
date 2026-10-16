package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
import net.forbric.kernel.mixin.NativeEntryRelay;

/**
 * {@code NativeEntryRelay} through the real weave with TWO guests on one decision method, and the same guests on the
 * liquid block their own loader has, as the oracle.
 *
 * <p>fabric-block-api's flow veto and Create Fly's fluid reaction both enter vanilla's {@code shouldSpreadLiquid} with a
 * cancellable {@code HEAD} {@code @Inject} that answers false. On the merged liquid block nothing calls that method —
 * placing asks MinecraftForge's interaction registry, a neighbour update NeoForge's — so both hooks bind and never run.
 * Relayed, placing and neighbour updates ask {@code shouldSpreadLiquid} again, first, and each carrier registry only where
 * it lets the decision through.
 *
 * <p>The probe registers one flow listener that denies "denied", then gives "lava" (where Create's registry reacts),
 * "water" and "denied" each a placement, a neighbour update and a shape update. In both config orders the trail of each
 * update is the trail the same guests leave on the native liquid block, with the carrier registries' own question added
 * exactly where the native method answered true — Mixin decides the order of the two hooks, as natively, and a shape
 * update (which vanilla never asks the method about) is not vetoed, as natively. Switched off, Create's hook stays in the
 * method nothing calls: lava flows.
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
	private static final String RELAYED = "[Forbric/Mixin] %s.shouldSpreadLiquid: net.minecraft.world.level.block.LiquidBlock.shouldSpreadLiquid";
	private static final String CONFLICT = "Method overwrite conflict";

	@TempDir static Path work;
	private static Path merged, natives;
	private static WeaveHarness.Result createFirst, fabricFirst, nativeCreateFirst, nativeFabricFirst, off;

	@BeforeAll static void weaveAll() throws Exception {
		List<Path> sources = sources(false);
		Map<String, Path> configs = Map.of(CREATE_CONFIG, SOURCES.resolve(CREATE_CONFIG), FABRIC_CONFIG, SOURCES.resolve(FABRIC_CONFIG));
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("net/minecraft/world/level/block/LiquidBlock.java"));
		nativeSources.add(SOURCES.resolve("native/net/minecraft/world/level/block/LiquidBlock.java"));
		natives = WeaveHarness.fixture(work, "fluidpair-native", nativeSources, configs, List.of("-g"));
		merged = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "fluidpair", sources, configs, List.of("-g")),
				NativeWeaveReferences.classes(natives));
		var create = new WeaveHarness.Config(CREATE_CONFIG, CREATE_MOD, Ecosystem.FABRIC);
		var fabric = new WeaveHarness.Config(FABRIC_CONFIG, FABRIC_MOD, Ecosystem.FABRIC);
		createFirst = run(merged, "create-first", List.of(create, fabric), Map.of());
		fabricFirst = run(merged, "fabric-first", List.of(fabric, create), Map.of());
		nativeCreateFirst = run(natives, "native-create-first", List.of(create, fabric), Map.of());
		nativeFabricFirst = run(natives, "native-fabric-first", List.of(fabric, create), Map.of());
		off = run(merged, "relay-off", List.of(create, fabric), Map.of(NativeEntryRelay.PROPERTY, "off"));
	}

	@Test void createFirstEachUpdateIsTheNativeOneWithTheCarriersQuestionAdded() throws Exception {
		assertTrue(relayed(createFirst, nativeCreateFirst), createFirst.describe() + "\nnative " + trail(nativeCreateFirst));
		WeaveHarness.assertWovenAndVerified(createFirst, TARGET, merged);
	}

	@Test void fabricFirstEachUpdateIsTheNativeOneWithTheCarriersQuestionAdded() throws Exception {
		assertTrue(relayed(fabricFirst, nativeFabricFirst), fabricFirst.describe() + "\nnative " + trail(nativeFabricFirst));
		WeaveHarness.assertWovenAndVerified(fabricFirst, TARGET, merged);
	}

	@Test void theNativeOracleReactsAndVetoes() {
		// The oracle itself: Create reacts at lava, the listener vetoes "denied", a shape update is not asked.
		String trail = trail(nativeCreateFirst);
		assertTrue(trail.contains("create reacted lava") && trail.contains("deny denied") && trail.contains("shape denied: flow denied"), trail);
	}

	@Test void switchedOffCreatesHookStaysInTheMethodNothingCallsAndLavaFlows() throws Exception {
		assertTrue(offHolds(off), off.describe());
		WeaveHarness.assertWovenAndVerified(off, TARGET, merged);
	}

	@Test void theControlFlipsEveryAssertion() throws Exception {
		assertTrue(relayed(createFirst, nativeCreateFirst) && !relayed(off, nativeCreateFirst), "relay predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(createFirst) && !offHolds(fabricFirst), "control predicate does not separate the runs");
	}

	/**
	 * The merged run's trail, with the carrier registries' own questions taken out, is the native run's; each carrier
	 * question stands exactly where the native method let the decision through (the update scheduled a tick natively);
	 * both hooks are entered from the relayed method, which placing and neighbour updates call; nothing was skipped or
	 * reported.
	 */
	private static boolean relayed(WeaveHarness.Result run, WeaveHarness.Result oracle) throws Exception {
		String[] mergedUpdates = trail(run).split(" \\| "), nativeUpdates = trail(oracle).split(" \\| ");
		if (mergedUpdates.length != nativeUpdates.length || mergedUpdates.length != 9) return false;
		for (int i = 0; i < mergedUpdates.length; i++) {
			if (!withoutCarrier(mergedUpdates[i]).equals(nativeUpdates[i])) return false;
			boolean asked = mergedUpdates[i].contains(" none "), ticked = nativeUpdates[i].contains("flow ");
			if (!mergedUpdates[i].startsWith("shape") && asked != ticked) return false;
		}
		ClassNode node = woven(run);
		return run.printed(String.format(RELAYED, CREATE_MIXIN)) || run.printed(String.format(RELAYED, FABRIC_MIXIN))
				? calls(node, "onPlace", "shouldSpreadLiquid") && calls(node, "neighborChanged", "shouldSpreadLiquid")
						&& entersFrom(node, "shouldSpreadLiquid", CREATE_MIXIN) && entersFrom(node, "shouldSpreadLiquid", FABRIC_MIXIN)
						&& !run.printed(CONFLICT) && node.methods.stream().noneMatch(m -> m.name.contains("$forbricOriginal")) && findings(run).isEmpty()
				: false;
	}

	/** One update's trail without the carrier registries' own questions ("forge none x", "neoforge none x"). */
	private static String withoutCarrier(String update) {
		int colon = update.indexOf(": ");
		String steps = Arrays.stream(update.substring(colon + 2).split(", ")).filter(step -> !step.matches("(forge|neoforge) none .*"))
				.collect(Collectors.joining(", "));
		return update.substring(0, colon + 2) + (steps.isEmpty() ? "-" : steps);
	}

	/** Create never reacts: its hook sits in the method nothing calls, so the merged block asks only the carriers. */
	private static boolean offHolds(WeaveHarness.Result run) throws Exception {
		String trail = trail(run);
		boolean lavaFlowsOnPlacement = Arrays.stream(trail.split(" \\| ")).anyMatch(update -> update.startsWith("place lava:") && update.contains("flow lava"));
		return !trail.contains("create reacted") && lavaFlowsOnPlacement && !calls(woven(run), "onPlace", "shouldSpreadLiquid")
				&& entersFrom(woven(run), "shouldSpreadLiquid", CREATE_MIXIN);
	}

	private static String trail(WeaveHarness.Result run) {
		return run.output().lines().filter(line -> line.startsWith(WeaveHarnessMain.DONE + " ")).map(line -> line.substring(WeaveHarnessMain.DONE.length() + 1))
				.findFirst().orElse("");
	}

	private static boolean calls(ClassNode node, String method, String callee) {
		MethodNode body = node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
		for (var insn : body.instructions) if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET) && call.name.equals(callee)) return true;
		return false;
	}

	/** Whether {@code method} of the woven class calls a handler merged from {@code mixin}. */
	private static boolean entersFrom(ClassNode node, String method, String mixin) {
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
			for (int i = 0; i + 1 < annotation.values.size(); i += 2) if ("mixin".equals(annotation.values.get(i))) return (String) annotation.values.get(i + 1);
		}
		return null;
	}

	private static List<WeaveHarness.Finding> findings(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> (f.modId().equals(CREATE_MOD) || f.modId().equals(FABRIC_MOD)) && !f.confidence().equals("RESOLVED")).toList();
	}

	private static WeaveHarness.Result run(Path fixture, String label, List<WeaveHarness.Config> configs, Map<String, String> properties)
			throws Exception {
		return WeaveHarness.run(work, label, fixture, configs, List.of(), EnvType.SERVER, "fixture.fluidpair.Probe", "probe", properties);
	}

	private static List<Path> sources(boolean includeNative) throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			return walk.filter(p -> p.toString().endsWith(".java") && (includeNative || !SOURCES.relativize(p).startsWith("native"))).sorted().toList();
		}
	}
}
