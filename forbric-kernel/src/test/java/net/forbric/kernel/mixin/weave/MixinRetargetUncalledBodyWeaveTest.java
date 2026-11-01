package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;

/**
 * MixinRetarget's R3 through the real weave, into a renamed body nothing calls: malilib's last tooltip hook follows
 * vanilla's tooltip body into NeoForge's private addDetailsToTooltipComponents, binds there, never runs, and is reported
 * as an injector that never runs — which marks the mod's row and stops nothing — instead of a required injector that
 * bound nowhere, which stops a strict launch.
 *
 * <p>The fixture's {@code ItemStack} has the shape of the carrier-renames.txt pair
 * {@code addDetailsToTooltip -> addDetailsToTooltipComponents | PIECE | FABRIC | UNCALLED}: the dispatcher makes none
 * of vanilla's component calls, the private renamed body makes one and nothing calls it. The mixin is malilib's, cut
 * down: a HEAD hook that binds in the dispatcher, so the mixin reads PARTIAL, and a hook after the component call. The
 * probe builds a tooltip through the dispatcher.
 * <ul>
 *   <li>fabric — the hook moves into the renamed body and is attached there; it never runs; the final-class check
 *       reports it as never running, confirmed and not required;</li>
 *   <li>uncalled-off — {@code -Dforbric.mixinRetarget.renameCensus.uncalled=off}: it stays, binds nowhere, and is a
 *       confirmed required loss, which is what stopped every strict client with malilib;</li>
 *   <li>liveness-off — {@code -Dforbric.mixinFit.liveness=off}: it moves and reads as attached, with nothing reported,
 *       which is how the move read before the census.</li>
 * </ul>
 */
class MixinRetargetUncalledBodyWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/uncalledbody");
	private static final String CONFIG = "uncalledbody.mixins.json";
	private static final String MOD = "uncalledbody";
	private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
	private static final String RENAMED = "addDetailsToTooltipComponents";

	/** The HEAD hook in the dispatcher; the last hook nowhere that runs, wherever it is bound. */
	private static final String TOOLTIP = "stack[first;dispatch;]";
	private static final String MOVED = "ItemStackMixin — addDetailsToTooltip → " + RENAMED;

	@TempDir static Path work;
	private static Path fixture;
	private static final Map<String, WeaveHarness.Result> RUNS = new LinkedHashMap<>();

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "uncalledbody", List.of(
				SOURCES.resolve("net/minecraft/core/component/DataComponentType.java"),
				SOURCES.resolve("net/minecraft/world/entity/player/Player.java"),
				SOURCES.resolve("net/minecraft/world/item/component/TooltipDisplay.java"),
				SOURCES.resolve("net/minecraft/world/item/TooltipFlag.java"),
				SOURCES.resolve("net/minecraft/world/item/Item.java"),
				SOURCES.resolve("net/minecraft/world/item/ItemStack.java"),
				SOURCES.resolve("fixture/uncalledbody/Probe.java"),
				SOURCES.resolve("fixture/uncalledbody/mixin/ItemStackMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		RUNS.put("fabric", run("fabric", Map.of()));
		RUNS.put("uncalled-off", run("uncalled-off", Map.of("forbric.mixinRetarget.renameCensus.uncalled", "off")));
		RUNS.put("liveness-off", run("liveness-off", Map.of("forbric.mixinFit.liveness", "off")));
	}

	@Test void theHookBindsInTheRenamedBodyAndIsReportedAsNeverRunning() throws Exception {
		WeaveHarness.Result fabric = RUNS.get("fabric");
		assertTrue(neverRuns(fabric), fabric.describe() + "\nfindings: " + fabric.findings());
		WeaveHarness.assertWovenAndVerified(fabric, ITEM_STACK, fixture);
	}

	/** Kept out of the renamed body, the required hook binds nowhere: a confirmed required loss. */
	@Test void keptOutOfTheBodyItIsARequiredLoss() throws Exception {
		WeaveHarness.Result off = RUNS.get("uncalled-off");
		assertTrue(missing(off), off.describe() + "\nfindings: " + off.findings());
		WeaveHarness.assertWovenAndVerified(off, ITEM_STACK, fixture);
	}

	/** Unasked whether it runs, the bound hook reads as attached, and nothing is reported: the move as it read before. */
	@Test void unaskedWhetherItRunsTheMoveReadsAsAttached() {
		WeaveHarness.Result off = RUNS.get("liveness-off");
		assertTrue(silent(off), off.describe() + "\nfindings: " + off.findings());
	}

	/** The runs and their controls must be told apart by the very predicates the tests use on them. */
	@Test void theControlsFlipEveryAssertion() {
		WeaveHarness.Result fabric = RUNS.get("fabric"), uncalled = RUNS.get("uncalled-off"), liveness = RUNS.get("liveness-off");
		assertTrue(neverRuns(fabric) && !neverRuns(uncalled) && !neverRuns(liveness), "never-runs predicate does not separate the runs");
		assertTrue(missing(uncalled) && !missing(fabric) && !missing(liveness), "missing predicate does not separate the runs");
		assertTrue(silent(liveness) && !silent(fabric) && !silent(uncalled), "silent predicate does not separate the runs");
	}

	private static boolean neverRuns(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + TOOLTIP) && run.printed(MOVED) && handlerCalls(run, RENAMED) == 1
				&& findings(run, false).size() == 1 && findings(run, true).isEmpty()
				&& findings(run, false).get(0).detail().contains("never runs");
	}

	private static boolean missing(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + TOOLTIP) && !run.printed(MOVED) && handlerCalls(run, RENAMED) == 0
				&& findings(run, true).size() == 1;
	}

	private static boolean silent(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + TOOLTIP) && run.printed(MOVED) && handlerCalls(run, RENAMED) == 1
				&& findings(run, false).isEmpty() && findings(run, true).isEmpty();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.uncalledbody.Probe", "run",
				properties);
	}

	/**
	 * The confirmed findings the final-class check recorded for the last hook: required ones (the author's requirement
	 * unmet: it bound nowhere) when {@code required}, else the ones that stop nothing (it never runs).
	 */
	private static List<WeaveHarness.Finding> findings(WeaveHarness.Result run, boolean required) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.id().contains("#uncalledbody$last")
				&& f.modId().equals(MOD) && "CONFIRMED".equals(f.confidence()) && f.required() == required).toList();
	}

	/** Calls from the woven {@code method} of ItemStack to a handler Mixin merged in. */
	private static int handlerCalls(WeaveHarness.Result run, String method) {
		ClassNode woven = new ClassNode();
		try {
			new ClassReader(run.defined(ITEM_STACK)).accept(woven, 0);
		} catch (java.io.IOException unreadable) {
			throw new java.io.UncheckedIOException(unreadable);
		}
		List<String> merged = woven.methods.stream().filter(m -> m.visibleAnnotations != null && m.visibleAnnotations.stream()
				.anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")))
				.map(m -> m.name + m.desc).toList();
		int calls = 0;
		for (MethodNode m : woven.methods) {
			if (!m.name.equals(method)) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(ITEM_STACK) && merged.contains(call.name + call.desc)) calls++;
			}
		}
		return calls;
	}
}
