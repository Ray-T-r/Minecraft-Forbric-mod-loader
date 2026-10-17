package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;

/**
 * {@code MixinPlacementTransactionAdapter}'s handler for a point after vanilla's {@code Item.useOn} call, replayed at the
 * return of {@code ItemStack.useOn}, runs only when the call was actually made — not merely when vanilla's checks before it
 * would let it be made. The merged {@code useOn} posts the carrier's own use event at its head (a listener may answer for
 * the use: it answers carol's with a success, without the use) and makes the call through the stack's own relay inside
 * the carrier's transaction. The guest is not Create: it notes who places before the call and reads a share after it.
 *
 * <p>Witnessed, carol's use replays nothing after the call (it was never made); every other use is the native run's. With
 * {@code -Dforbric.placementCallWitness=off} the replay trusts the head alone and runs for carol after a call that never
 * happened.
 */
class PlacementCallWitnessWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/placementwitness");
	private static final String CONFIG = "placementwitness.mixins.json";
	private static final String MOD = "builder";
	private static final String WITNESSED = WeaveHarnessMain.DONE
			+ " alice: before alice, used, after 1,2,3 | bob: - | nobody: before nobody, used | carol: before carol, vetoed";
	private static final String UNWITNESSED = WeaveHarnessMain.DONE
			+ " alice: before alice, used, after 1,2,3 | bob: - | nobody: before nobody, used | carol: before carol, vetoed, after 1,2,3";

	@TempDir static Path work;
	private static WeaveHarness.Result witnessed, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> shared = List.of(
				SOURCES.resolve("net/minecraft/core/BlockPos.java"),
				SOURCES.resolve("net/minecraft/world/InteractionResult.java"),
				SOURCES.resolve("net/minecraft/world/level/LevelReader.java"),
				SOURCES.resolve("net/minecraft/world/level/Level.java"),
				SOURCES.resolve("net/minecraft/world/level/block/state/pattern/BlockInWorld.java"),
				SOURCES.resolve("net/minecraft/world/entity/player/Abilities.java"),
				SOURCES.resolve("net/minecraft/world/entity/player/Player.java"),
				SOURCES.resolve("net/minecraft/world/item/Item.java"),
				SOURCES.resolve("net/minecraft/world/item/context/UseOnContext.java"),
				SOURCES.resolve("org/example/builder/mixin/BuilderMixin.java"),
				SOURCES.resolve("fixture/placementwitness/Log.java"),
				SOURCES.resolve("fixture/placementwitness/Probe.java"));
		List<Path> sources = new ArrayList<>(shared);
		sources.add(SOURCES.resolve("net/minecraft/world/item/ItemStack.java"));
		sources.add(SOURCES.resolve("org/kiln/carrier/Kiln.java"));
		sources.add(SOURCES.resolve("org/kiln/carrier/Vetoes.java"));
		Path fixture = WeaveHarness.fixture(work, "placementwitness", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		List<Path> originals = new ArrayList<>(shared);
		originals.add(SOURCES.resolve("native/ItemStack.java"));
		Path original = WeaveHarness.fixture(work, "original", originals, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, fixture, NativeWeaveReferences.classes(original));
		witnessed = run(fixture, "witnessed", Map.of());
		off = run(fixture, "witness-off", Map.of("forbric.placementCallWitness", "off"));
		natively = run(original, "native", Map.of());
	}

	@Test void aHandlerAfterTheCallRunsOnlyWhenTheCallWasMade() {
		assertTrue(witnessed.printedLine(WITNESSED), witnessed.describe());
		assertTrue(witnessed.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:"))
				.allMatch(f -> f.confidence().equals("RESOLVED")), witnessed.describe() + "\n" + witnessed.findings());
	}

	/** Every use the native game has — all but carol's, which the carrier's own event refuses — is the native run's. */
	@Test void everyNativeUseIsTheNativeRunsOne() {
		String nativeLine = natively.output().lines().filter(l -> l.startsWith(WeaveHarnessMain.DONE)).findFirst().orElse("");
		assertTrue(nativeLine.startsWith(WeaveHarnessMain.DONE + " alice: before alice, used, after 1,2,3 | bob: - | nobody: before nobody, used | carol: "),
				natively.describe());
		assertEquals(nativeLine.substring(0, nativeLine.indexOf(" | carol:")), WITNESSED.substring(0, WITNESSED.indexOf(" | carol:")));
	}

	@Test void switchedOffTheReplayTrustsTheHeadAlone() {
		assertTrue(off.printedLine(UNWITNESSED), off.describe());
	}

	@Test void theControlFlipsTheAssertion() {
		assertTrue(witnessed.printedLine(WITNESSED) && !off.printedLine(WITNESSED));
		assertTrue(off.printedLine(UNWITNESSED) && !witnessed.printedLine(UNWITNESSED));
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.placementwitness.Probe", "probe", properties);
	}
}
