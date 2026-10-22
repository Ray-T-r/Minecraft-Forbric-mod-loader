package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinStubRebind;

/**
 * MixinStubRebind's {@code argsOnly @ModifyVariable} at HEAD, through the real weave, against the same guest woven into
 * the class it was written for.
 *
 * <p>The guest (unrelated names) records x and y in a HEAD callback on {@code tooltip}, fires its own position event, and
 * hands the method the moved position through two argument modifiers at the same head, by ordinal — the shape of a
 * tooltip-position event. The merged fixture has the carrier-stubs.txt row's shape: vanilla's six-argument
 * {@code tooltip} only forwards to the seven-argument body, and a deferred path calls the body directly.
 * <ul>
 *   <li>native — the guest on vanilla's one method: both tooltips land where the event moved them;</li>
 *   <li>merged — all three handlers move to the body and run there in their native order: the same answer;</li>
 *   <li>merged with {@code -Dforbric.mixinStubRebind.modifyVariable=off} (the control) — the callback moves, the modifiers
 *       stay on the stub: through the old signature they run before the event (and hand it the stale position), and the
 *       deferred tooltip never moves.</li>
 * </ul>
 */
class MixinStubRebindHeadArgumentWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/headargs");
	private static final Path NATIVE = Path.of("src/test/resources/weave/headargs-native");
	private static final String CONFIG = "headargs.mixins.json";
	private static final String MOD = "quillnote";
	private static final String TARGET = "net/minecraft/client/gui/GuiGraphicsExtractor";
	private static final String MOVED = WeaveHarnessMain.DONE + " vanilla-call=160,80 deferred=180,100";
	private static final String STAYED = WeaveHarnessMain.DONE + " vanilla-call=0,0 deferred=30,40";
	private static final String X_MOVED = "NoteMixin: quill$x now targets " + TARGET.replace('/', '.') + ".tooltip(";

	@TempDir static Path work;
	private static Path merged, nativeFixture;
	private static WeaveHarness.Result nativeRun, adapted, off;

	@BeforeAll static void weave() throws Exception {
		List<Path> shared = List.of(
				SOURCES.resolve("net/minecraft/client/gui/Font.java"),
				SOURCES.resolve("net/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner.java"),
				SOURCES.resolve("net/minecraft/resources/Identifier.java"),
				SOURCES.resolve("net/minecraft/world/item/ItemStack.java"),
				SOURCES.resolve("fixture/quillnote/Nudge.java"),
				SOURCES.resolve("fixture/quillnote/Probe.java"),
				SOURCES.resolve("fixture/quillnote/mixin/NoteMixin.java"));
		merged = WeaveHarness.fixture(work, "headargs", concat(shared, SOURCES.resolve("net/minecraft/client/gui/GuiGraphicsExtractor.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		nativeFixture = WeaveHarness.fixture(work, "headargs-native", concat(shared, NATIVE.resolve("net/minecraft/client/gui/GuiGraphicsExtractor.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		nativeRun = run("native", nativeFixture, Map.of());
		adapted = run("adapted", merged, Map.of());
		off = run("modify-variable-off", merged, Map.of(MixinStubRebind.MODIFY_VARIABLE_PROPERTY, "off"));
	}

	private static List<Path> concat(List<Path> shared, Path extra) {
		java.util.ArrayList<Path> all = new java.util.ArrayList<>(shared);
		all.add(extra);
		return all;
	}

	@Test void theGuestOnItsOwnClassMovesBothTooltips() {
		assertTrue(nativeRun.printed(MOVED), nativeRun.describe());
	}

	@Test void onTheMergedBodyBothTooltipsMoveAsNatively() throws Exception {
		assertTrue(adapted.printed(MOVED), adapted.describe());
		assertTrue(adapted.printed(X_MOVED), adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted, TARGET, merged);
	}

	@Test void leftOnTheStubTheModifiersRunBeforeTheEventAndMissTheDeferredTooltip() throws Exception {
		assertTrue(off.printed(STAYED), off.describe());
		assertFalse(off.printed(X_MOVED), off.describe());
		WeaveHarness.assertWovenAndVerified(off, TARGET, merged);
	}

	/** The switch is the merged runs' only difference, so each run's answer must be the other's miss. */
	@Test void theControlFlipsTheAnswer() {
		assertTrue(adapted.printed(MOVED) && !off.printed(MOVED) && off.printed(STAYED) && !adapted.printed(STAYED));
	}

	private static WeaveHarness.Result run(String label, Path fixture, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.quillnote.Probe", "probe", properties);
	}
}
