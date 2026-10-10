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
import net.forbric.kernel.mixin.MixinBlockInteractionAdapters;

/**
 * {@code MixinBlockInteractionAdapters}' server break rule through the real weave, for a mod that is not the rule's
 * sample and captures another way: a mason mod's wrap of vanilla's {@code level.removeBlock} in destroyBlock that takes
 * the state playerWillDestroy returned as the one state live there, the position by an ordinal over destroyBlock's
 * parameters, and the block by its debug name. The merged destroyBlock calls its own {@code removeBlock(pos, state,
 * canHarvest)} instead, and keeps the state it read first live in a lower slot, so the state as written finds two there
 * and MixinExtras would refuse it: the moved wrap must be handed each capture as a {@code @Local(index)} of the slot
 * proved for it.
 *
 * <p>The proof is made twice — against the class the mod was compiled against (a native reference), and without it,
 * off the merged body alone — and both must break the granite wall into chiselled granite that stays, and the dirt
 * floor into air, exactly as the mod does on vanilla. A wrap handed NeoForge's earlier state would see plain granite and
 * let the wall go. Switched off, the wrap binds nothing on the merged class: both blocks are removed and the wrap is the
 * mod's required loss.
 */
class MasonBreakCapturesWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/masonbreak");
	private static final String CONFIG = "masonbreak.mixins.json";
	private static final String MOD = "mason";
	private static final String TARGET = "net/minecraft/server/level/ServerPlayerGameMode";

	private static final String KEPT = WeaveHarnessMain.DONE + " wall kept chiselled granite | floor broke air";
	private static final String REMOVED = WeaveHarnessMain.DONE + " wall broke air | floor broke air";

	@TempDir static Path work;
	private static Path merged, indexed;
	private static WeaveHarness.Result vanilla, unrecorded, proved, off;

	@BeforeAll static void weave() throws Exception {
		Map<String, Path> configs = Map.of(CONFIG, SOURCES.resolve("mods/" + CONFIG));
		List<Path> common = new ArrayList<>(sources(SOURCES.resolve("common")));
		common.addAll(sources(SOURCES.resolve("mods")));
		List<Path> vanillaSources = new ArrayList<>(common);
		vanillaSources.addAll(sources(SOURCES.resolve("vanilla")));
		Path vanillaFixture = WeaveHarness.fixture(work, "mason-vanilla", vanillaSources, configs, List.of("-g"));
		List<Path> mergedSources = new ArrayList<>(common);
		mergedSources.addAll(sources(SOURCES.resolve("merged")));
		// The moved wrap is handed a reordered Operation from the kernel's game-side KernelWrapOperations.
		mergedSources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java"));
		merged = WeaveHarness.fixture(work, "mason-merged", mergedSources, configs, List.of("-g"));
		indexed = NativeWeaveReferences.with(work, merged, NativeWeaveReferences.classes(vanillaFixture));
		vanilla = run(vanillaFixture, "vanilla", Map.of());
		unrecorded = run(merged, "merged-unrecorded", Map.of());
		proved = run(indexed, "merged-proved", Map.of());
		off = run(indexed, "adapter-off", Map.of(MixinBlockInteractionAdapters.PROPERTY, "off"));
	}

	@Test void onVanillaTheMasonKeepsTheChiselledWall() {
		assertTrue(returned(vanilla, KEPT), vanilla.describe());
		assertEquals(List.of(), losses(vanilla), vanilla.describe());
		assertEquals(List.of(), partial(vanilla), vanilla.describe());
	}

	@Test void offTheMergedBodyAloneEachCaptureReachesTheMovedWrap() throws Exception {
		assertTrue(adaptedHolds(unrecorded), unrecorded.describe() + "\nfindings " + unrecorded.findings());
		WeaveHarness.assertWovenAndVerified(unrecorded, TARGET, merged);
	}

	@Test void provedAgainstTheNativeClassEachCaptureReachesTheMovedWrap() throws Exception {
		assertTrue(adaptedHolds(proved), proved.describe() + "\nfindings " + proved.findings());
		WeaveHarness.assertWovenAndVerified(proved, TARGET, indexed);
	}

	@Test void switchedOffTheWrapIsUnboundAndBothBlocksGo() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
	}

	/** Each run's predicate must fail on the other, or the control proves nothing about the adapter. */
	@Test void theControlFlipsEveryAdapterAssertion() throws Exception {
		assertTrue(adaptedHolds(unrecorded) && adaptedHolds(proved) && !adaptedHolds(off), "adapter predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(unrecorded) && !offHolds(proved), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) throws Exception {
		return returned(run, KEPT) && losses(run).isEmpty() && partial(run).isEmpty() && callsHandler(run);
	}

	private static boolean offHolds(WeaveHarness.Result run) throws Exception {
		List<WeaveHarness.Finding> losses = losses(run);
		return returned(run, REMOVED) && losses.size() == 1 && losses.get(0).required() && !callsHandler(run);
	}

	private static boolean returned(WeaveHarness.Result run, String line) {
		return run.output().lines().anyMatch(line::equals);
	}

	/** The mod's injectors that did not attach. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& !f.confidence().equals("RESOLVED")).toList();
	}

	/** The mod's mixin rows that are not whole: left out, or applying only in part. */
	private static List<WeaveHarness.Finding> partial(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin:") && !f.confidence().equals("RESOLVED")).toList();
	}

	/** Whether the defined destroyBlock calls a handler the mason mod's mixin merged into ServerPlayerGameMode. */
	private static boolean callsHandler(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		MethodNode body = node.methods.stream().filter(m -> m.name.equals("destroyBlock")).findFirst().orElseThrow();
		for (var insn : body.instructions)
			if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET) && call.name.contains("$" + MOD + "$")) return true;
		return false;
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.masonbreak.Probe", "probe", properties);
	}

	private static List<Path> sources(Path root) throws Exception {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}
}
