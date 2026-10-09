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
import net.forbric.kernel.mixin.MixinCarrierCallbackAdapters;

/**
 * {@code MixinPlacementTransactionAdapter} (one rule of {@code MixinCarrierCallbackAdapters}) through the real weave, on a
 * mod that is not Create and is not written as Create's pair: an {@code @Inject} just before vanilla's {@code Item.useOn}
 * call in {@code ItemStack.useOn} that captures the player (not the item) and shares the clicked position, and one at the
 * item-interaction check after the call that reads the share and captures nothing. The merged {@code useOn} hands the
 * whole placement to MinecraftForge's {@code ForgeHooks.onPlaceItemIntoWorld}, so neither point is in it.
 *
 * <p>Three uses: a builder, an adventure-mode player whose placement vanilla refuses before the call, and no player
 * (vanilla reaches the call but not the check after it). Adapted, each handler runs exactly where vanilla would have run
 * it — before and after the builder's use, never for the refused one, only before for the playerless one — because each
 * runs inside a replay of vanilla's own instructions. With {@code -Dforbric.carrierCallbackAdapters=off} neither binds.
 */
class PlacementTransactionWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/placementsingle");
	private static final String CONFIG = "placementsingle.mixins.json";
	private static final String MOD = "builder";

	private static final String ADAPTED = WeaveHarnessMain.DONE + " alice: before alice, used, after 1,2,3 | bob: - | nobody: before nobody, used";
	private static final String UNADAPTED = WeaveHarnessMain.DONE + " alice: used | bob: - | nobody: used";

	@TempDir static Path work;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

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
				SOURCES.resolve("net/minecraftforge/common/ForgeHooks.java"),
				SOURCES.resolve("fixture/placementsingle/Log.java"),
				SOURCES.resolve("fixture/placementsingle/Probe.java"));
		List<Path> sources = new ArrayList<>(shared);
		sources.add(SOURCES.resolve("net/minecraft/world/item/ItemStack.java"));
		sources.add(SOURCES.resolve("org/example/builder/mixin/BuilderMixin.java"));
		// Compiled with the LocalVariableTable the game's classes carry, which is where a @Local's slot is read.
		Path fixture = WeaveHarness.fixture(work, "placementsingle", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		// The class the mod was compiled against: vanilla's ItemStack.useOn, which makes the Item.useOn call itself.
		List<Path> originals = new ArrayList<>(shared);
		originals.add(SOURCES.resolve("native/ItemStack.java"));
		Path original = WeaveHarness.fixture(work, "original", originals, Map.of(), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, fixture, NativeWeaveReferences.classes(original));
		adapted = WeaveHarness.run(work, "adapted", fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.placementsingle.Probe", "probe", Map.of());
		off = WeaveHarness.run(work, "adapter-off", fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.placementsingle.Probe", "probe",
				Map.of(MixinCarrierCallbackAdapters.PROPERTY, "off"));
	}

	@Test void eachHandlerRunsWhereVanillaWouldHaveRunIt() {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings " + adapted.findings());
	}

	@Test void switchedOffNeitherHandlerBinds() {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
	}

	@Test void theControlFlipsEveryAdapterAssertion() {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapter predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	/** Both handlers attached, and the mixin's own row is resolved once they are seen in the defined class. */
	private static boolean adaptedHolds(WeaveHarness.Result run) {
		return run.output().lines().anyMatch(ADAPTED::equals) && losses(run).isEmpty() && run.findings().stream()
				.filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin:")).allMatch(f -> f.confidence().equals("RESOLVED"));
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		List<WeaveHarness.Finding> losses = losses(run);
		return run.output().lines().anyMatch(UNADAPTED::equals) && losses.size() == 2
				&& losses.stream().anyMatch(f -> f.id().contains("#beforeUse(")) && losses.stream().anyMatch(f -> f.id().contains("#afterUse("));
	}

	/** The final audit's rows for the mod's injectors that did not attach. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& !f.confidence().equals("RESOLVED")).toList();
	}
}
