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

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;

/**
 * {@code KernelGuestMixinAdapter.unfitMixins} through the real weave: a guest config whose one mixin fits the merged
 * class and whose other mixin's only anchor, {@code oldLabel()}, the merged class does not have.
 *
 * <p>The UNFIT mixin is taken out of the config while Mixin reads it ({@code ForbricMixinService.getResourceAsStream}),
 * so Mixin never prepares it: the defined Widget carries the fitting mixin's work and nothing of the unfit one, and the
 * mod gets one confirmed finding saying the mixin was left out. The control is the stage's own switch,
 * {@code -Dforbric.guestMixinAdapter=off}: the same mixin then reaches Mixin, which applies all of it that can apply —
 * Widget implements the mod's interface, so the mod's cast succeeds, while the hook behind it never fires.
 */
class KernelGuestMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/unfit");
	private static final String CONFIG = "unfit.mixins.json";
	private static final String MOD = "unfit";
	private static final String TARGET = "net/minecraft/fixture/unfit/Widget";
	private static final String STAMPED = "fixture/unfit/Stamped";
	private static final String AUTO_SUPPRESSED = "[Forbric/Mixin] auto-suppressing guest mixin unfit (unfit.mixins.json):"
			+ "StaleWidgetMixin — UNFIT on this base (no anchor resolves (@Inject target Widget.oldLabel))";
	private static final String REMOVED = "[Forbric/Mixin] suppressed mixin StaleWidgetMixin from unfit (unfit.mixins.json)";
	private static final String LEFT_OUT = "mixin:unfit.mixins.json:fixture.unfit.mixin.StaleWidgetMixin";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result suppressed;
	private static WeaveHarness.Result off;

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "unfit", List.of(
				SOURCES.resolve("net/minecraft/fixture/unfit/Widget.java"),
				SOURCES.resolve("fixture/unfit/Stamped.java"),
				SOURCES.resolve("fixture/unfit/Probe.java"),
				SOURCES.resolve("fixture/unfit/mixin/WidgetMixin.java"),
				SOURCES.resolve("fixture/unfit/mixin/StaleWidgetMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		suppressed = run("suppressed", Map.of());
		off = run("adapter-off", Map.of("forbric.guestMixinAdapter", "off"));
	}

	@Test void anUnfitMixinIsLeftOutOfItsConfigBeforeMixinReadsIt() throws Exception {
		assertTrue(suppressedHolds(suppressed), suppressed.describe() + "\nfindings " + suppressed.findings());
		// Taken out while the config was registered, not when Widget was woven.
		assertTrue(suppressed.output().indexOf(REMOVED) < suppressed.output().indexOf("Mixin up on the sovereign kernel"),
				suppressed.describe());

		List<WeaveHarness.Finding> ours = ours(suppressed);
		assertEquals(1, ours.size(), "findings: " + suppressed.findings());
		assertEquals(LEFT_OUT, ours.get(0).id());
		assertTrue(ours.get(0).confirmedRequired(), "the config says required, so the loss is a required one: " + ours.get(0));

		ClassNode woven = node(suppressed);
		assertTrue(WeaveHarness.hasMergedMethod(suppressed.defined(TARGET)), "the fitting mixin was not applied — " + suppressed.describe());
		assertEquals(List.of(), woven.interfaces, "the left-out mixin's interface reached Widget");
		assertTrue(woven.fields.stream().noneMatch(f -> f.name.contains("stamp")), "the left-out mixin's field reached Widget");
		WeaveHarness.assertWovenAndVerified(suppressed, TARGET, fixture);
	}

	@Test void switchedOffTheUnfitMixinAppliesAroundAHookThatNeverRuns() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
		assertEquals(List.of(STAMPED), node(off).interfaces, off.describe());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	/** Each run's predicate must fail on the other, or the control proves nothing about the stage. */
	@Test void theControlFlipsEverySuppressionAssertion() {
		assertTrue(suppressedHolds(suppressed) && !suppressedHolds(off), "the suppression predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(suppressed), "the control predicate does not separate the runs");
	}

	private static boolean suppressedHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " widget|mixin unstamped") && run.printed(AUTO_SUPPRESSED)
				&& run.printed(REMOVED) && ours(run).stream().anyMatch(f -> f.id().equals(LEFT_OUT))
				&& ours(run).stream().noneMatch(f -> f.id().startsWith("mixin-injector:"));
	}

	/** Natively the mixin applies: the cast works, the hook is the audit's confirmed, required loss, and nothing says why. */
	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " widget|mixin stamped never stamped") && !run.printed(AUTO_SUPPRESSED)
				&& !run.printed(REMOVED) && ours(run).stream().noneMatch(f -> f.id().equals(LEFT_OUT))
				&& ours(run).stream().anyMatch(f -> f.id().startsWith("mixin-injector:") && f.id().contains("#onOldLabel(")
						&& f.confirmedRequired());
	}

	private static List<WeaveHarness.Finding> ours(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD)).toList();
	}

	private static ClassNode node(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		return node;
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.unfit.Probe", "probe", properties);
	}
}
