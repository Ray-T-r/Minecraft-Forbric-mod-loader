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
import net.forbric.kernel.mixin.MixinFluidReactionAdapter;

/**
 * {@code MixinFluidReactionAdapter}'s HEAD wrap through the real weave with TWO guests on one decision method, and the
 * same guests on the liquid block their own loader has, as the oracle.
 *
 * <p>fabric-block-api's flow veto and Create Fly's fluid reaction both enter vanilla's {@code shouldSpreadLiquid} with a
 * cancellable {@code HEAD} {@code @Inject} that answers false. On NeoForge's liquid block placing still asks that method,
 * so both hooks run there as compiled, but a neighbour update asks NeoForge's interaction registry instead, so natively
 * neither hook would run on it. Wrapped, each hook runs ahead of the registry question on a neighbour update, and the
 * registry only where the hooks let the decision through.
 *
 * <p>The probe registers one flow listener that denies "denied", then gives "lava" (where Create's registry reacts),
 * "water" and "denied" each a placement, a neighbour update and a shape update. In both config orders the trail of each
 * update is the trail the same guests leave on the native liquid block, with NeoForge's registry question added on a
 * neighbour update exactly where the native method answered true — a shape update (which vanilla never asks the method
 * about) is not vetoed, as natively — except that on a neighbour update the two hooks run in the reverse of their native
 * order (pinned below). With {@code -Dforbric.fluidReactionNeighbourChange.head=off} the hooks run on
 * placement alone: on a neighbour update Create never reacts, the veto is not asked, and lava flows.
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
	private static final String WRAPPED = "[Forbric/Mixin] %s: shouldSpreadLiquid now also runs on a neighbour change, at FluidInteractionRegistry's canInteract in neighborChanged";
	private static final String CONFLICT = "Method overwrite conflict";

	@TempDir static Path work;
	private static Path genuine, natives;
	private static WeaveHarness.Result createFirst, fabricFirst, nativeCreateFirst, nativeFabricFirst, off;

	@BeforeAll static void weaveAll() throws Exception {
		List<Path> sources = sources(false);
		Map<String, Path> configs = Map.of(CREATE_CONFIG, SOURCES.resolve(CREATE_CONFIG), FABRIC_CONFIG, SOURCES.resolve(FABRIC_CONFIG));
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("net/minecraft/world/level/block/LiquidBlock.java"));
		nativeSources.add(SOURCES.resolve("native/net/minecraft/world/level/block/LiquidBlock.java"));
		natives = WeaveHarness.fixture(work, "fluidpair-native", nativeSources, configs, List.of("-g"));
		genuine = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "fluidpair", sources, configs, List.of("-g")),
				NativeWeaveReferences.classes(natives));
		var create = new WeaveHarness.Config(CREATE_CONFIG, CREATE_MOD, Ecosystem.FABRIC);
		var fabric = new WeaveHarness.Config(FABRIC_CONFIG, FABRIC_MOD, Ecosystem.FABRIC);
		createFirst = run(genuine, "create-first", List.of(create, fabric), Map.of());
		fabricFirst = run(genuine, "fabric-first", List.of(fabric, create), Map.of());
		nativeCreateFirst = run(natives, "native-create-first", List.of(create, fabric), Map.of());
		nativeFabricFirst = run(natives, "native-fabric-first", List.of(fabric, create), Map.of());
		off = run(genuine, "head-off", List.of(create, fabric), Map.of(MixinFluidReactionAdapter.HEAD_PROPERTY, "off"));
	}

	@Test void createFirstEachUpdateIsTheNativeOneWithTheCarriersQuestionAdded() throws Exception {
		assertTrue(relayed(createFirst, nativeCreateFirst, nativeFabricFirst), createFirst.describe() + "\nnative " + trail(nativeCreateFirst)
				+ "\nnative, reversed " + trail(nativeFabricFirst));
		WeaveHarness.assertWovenAndVerified(createFirst, TARGET, genuine);
	}

	@Test void fabricFirstEachUpdateIsTheNativeOneWithTheCarriersQuestionAdded() throws Exception {
		assertTrue(relayed(fabricFirst, nativeFabricFirst, nativeCreateFirst), fabricFirst.describe() + "\nnative " + trail(nativeFabricFirst)
				+ "\nnative, reversed " + trail(nativeCreateFirst));
		WeaveHarness.assertWovenAndVerified(fabricFirst, TARGET, genuine);
	}

	/**
	 * The one difference from native that the wraps keep, pinned so it cannot change unseen: two mixins' wraps of one
	 * call chain with the later-applied one outside, so on a neighbour update the two hooks run in the reverse of their
	 * native (HEAD) order. Where only one of them acts the outcome is the same; where the first native hook cancels, the
	 * other one is asked too (here: Create reacts at "lava" either way, and the flow listener is asked there as well).
	 */
	@Test void onANeighbourUpdateTheTwoHooksRunInTheReverseOfTheirNativeOrder() {
		assertNotEquals(update(nativeCreateFirst, "neighbor lava"), update(nativeFabricFirst, "neighbor lava"), "the oracle does not tell the orders apart");
		assertEquals(update(nativeFabricFirst, "neighbor lava"), withoutCarrier(update(createFirst, "neighbor lava")));
		assertEquals(update(nativeCreateFirst, "neighbor lava"), withoutCarrier(update(fabricFirst, "neighbor lava")));
	}

	@Test void theNativeOracleReactsAndVetoes() {
		// The oracle itself: Create reacts at lava, the listener vetoes "denied", a shape update is not asked.
		String trail = trail(nativeCreateFirst);
		assertTrue(trail.contains("create reacted lava") && trail.contains("deny denied") && trail.contains("shape denied: flow denied"), trail);
	}

	@Test void switchedOffTheHooksRunOnPlacementAloneAndLavaFlowsOnANeighbourUpdate() throws Exception {
		assertTrue(offHolds(off), off.describe());
		WeaveHarness.assertWovenAndVerified(off, TARGET, genuine);
	}

	@Test void theControlFlipsEveryAssertion() throws Exception {
		assertTrue(relayed(createFirst, nativeCreateFirst, nativeFabricFirst) && !relayed(off, nativeCreateFirst, nativeFabricFirst),
				"wrap predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(createFirst) && !offHolds(fabricFirst), "control predicate does not separate the runs");
	}

	/**
	 * The run's trail, with NeoForge's registry question taken out, is the native run's — on a neighbour update the native
	 * run's of the reverse config order ({@code reversed}; see the test above); that question stands, on a neighbour
	 * update, exactly where the native method let the decision through (the update scheduled a tick natively), and never on
	 * a placement, which asks {@code shouldSpreadLiquid} as natively; both hooks are entered from that method and from
	 * {@code neighborChanged} (their wraps), both wraps were announced, and nothing was skipped or reported.
	 */
	private static boolean relayed(WeaveHarness.Result run, WeaveHarness.Result oracle, WeaveHarness.Result reversed) throws Exception {
		String[] updates = trail(run).split(" \\| "), nativeUpdates = trail(oracle).split(" \\| "), reversedUpdates = trail(reversed).split(" \\| ");
		if (updates.length != nativeUpdates.length || updates.length != 9 || reversedUpdates.length != 9) return false;
		for (int i = 0; i < updates.length; i++) {
			if (!withoutCarrier(updates[i]).equals((updates[i].startsWith("neighbor") ? reversedUpdates : nativeUpdates)[i])) return false;
			boolean asked = updates[i].contains(" none "), ticked = nativeUpdates[i].contains("flow ");
			if (updates[i].startsWith("neighbor") ? asked != ticked : asked) return false;
		}
		ClassNode node = woven(run);
		return run.printed(String.format(WRAPPED, CREATE_MIXIN)) && run.printed(String.format(WRAPPED, FABRIC_MIXIN))
				&& calls(node, "onPlace", "shouldSpreadLiquid") && !calls(node, "neighborChanged", "shouldSpreadLiquid")
				&& entersFrom(node, "shouldSpreadLiquid", CREATE_MIXIN) && entersFrom(node, "shouldSpreadLiquid", FABRIC_MIXIN)
				&& reaches(node, "neighborChanged", CREATE_MIXIN) && reaches(node, "neighborChanged", FABRIC_MIXIN)
				&& !run.printed(CONFLICT) && findings(run).isEmpty();
	}

	/** The {@code name} update's trail ("neighbor lava: …") of {@code run}. */
	private static String update(WeaveHarness.Result run, String name) {
		return Arrays.stream(trail(run).split(" \\| ")).filter(update -> update.startsWith(name + ":")).findFirst().orElse("");
	}

	/** One update's trail without NeoForge's registry question ("neoforge none x"). */
	private static String withoutCarrier(String update) {
		int colon = update.indexOf(": ");
		String steps = Arrays.stream(update.substring(colon + 2).split(", ")).filter(step -> !step.matches("neoforge none .*"))
				.collect(Collectors.joining(", "));
		return update.substring(0, colon + 2) + (steps.isEmpty() ? "-" : steps);
	}

	/**
	 * The hooks run on placement alone: Create reacts on placing lava, but on a neighbour update it never reacts, the veto
	 * is not asked, lava and "denied" flow, and neither hook is entered from {@code neighborChanged}.
	 */
	private static boolean offHolds(WeaveHarness.Result run) throws Exception {
		String trail = trail(run);
		List<String> updates = Arrays.asList(trail.split(" \\| "));
		boolean placeReacts = updates.stream().anyMatch(update -> update.startsWith("place lava:") && update.contains("create reacted lava"));
		boolean neighbourFlows = updates.contains("neighbor lava: neoforge none lava, flow lava")
				&& updates.contains("neighbor denied: neoforge none denied, flow denied");
		ClassNode node = woven(run);
		return placeReacts && neighbourFlows && !run.printed(String.format(WRAPPED, CREATE_MIXIN)) && !run.printed(String.format(WRAPPED, FABRIC_MIXIN))
				&& !reaches(node, "neighborChanged", CREATE_MIXIN) && !reaches(node, "neighborChanged", FABRIC_MIXIN)
				&& entersFrom(node, "shouldSpreadLiquid", CREATE_MIXIN);
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

	/**
	 * Whether a handler merged from {@code mixin} is reached from {@code method} of the woven class through its own methods
	 * and the method handles it passes (an inner wrap runs from the lambda the outer one is handed as its operation).
	 */
	private static boolean reaches(ClassNode node, String method, String mixin) {
		java.util.Deque<MethodNode> todo = new java.util.ArrayDeque<>(node.methods.stream().filter(m -> m.name.equals(method)).toList());
		java.util.Set<MethodNode> seen = new java.util.HashSet<>(todo);
		while (!todo.isEmpty()) {
			for (var insn : todo.pop().instructions) {
				List<String[]> called = new ArrayList<>();
				if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET)) called.add(new String[] {call.name, call.desc});
				if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode dynamic) for (Object argument : dynamic.bsmArgs)
					if (argument instanceof org.objectweb.asm.Handle handle && handle.getOwner().equals(TARGET)) called.add(new String[] {handle.getName(), handle.getDesc()});
				for (String[] member : called) for (MethodNode m : node.methods) if (m.name.equals(member[0]) && m.desc.equals(member[1]) && seen.add(m)) {
					if (mixin.equals(mergedFrom(m))) return true;
					todo.add(m);
				}
			}
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
