package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.ReplacedCallRedirects;

/**
 * ReplacedCallRedirects through the real weave: a Fabric guest's {@code @Redirect} of vanilla's
 * {@code ItemStack.isSameItem(in hand, in use)} in {@code LivingEntity.updatingUsingItem}, on the merged body where
 * NeoForge's {@code CommonHooks.canContinueUsing(in use, in hand)} replaced it (ViaFabricPlus' 1.14.3 item-use rule, in
 * miniature). The handler only puts a condition around the call: on an old server, the stack in hand must be the very
 * stack in use; otherwise it forwards vanilla's test.
 *
 * <p>The probe updates one entity using a bow while it holds another stack of bows, and reports what ran:
 * <ul>
 *   <li>moved, the mod letting the call through — NeoForge is asked, in its own order, and the item stays in use;</li>
 *   <li>moved, on an old server — the handler sees the two stacks where vanilla put them, and the item is no longer
 *       in use;</li>
 *   <li>with {@code -Dforbric.replacedCallRedirects=off}, on an old server — the redirect binds nothing, NeoForge's
 *       answer stands, and the mod's required injector is reported lost.</li>
 * </ul>
 */
class ReplacedCallRedirectsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/replacedcallredirect");
	private static final String CONFIG = "replacedcallredirect.mixins.json";
	private static final String MOD = "oldserver";
	private static final String TARGET = "net/minecraft/world/entity/LivingEntity";
	private static final String OLD_SERVER = "fixture.replacedcallredirect.oldServer";
	private static final String FORWARDED = WeaveHarnessMain.DONE + " neoforge:used->held,update";
	private static final String OLD = WeaveHarnessMain.DONE + " old:held/used,stop";
	private static final String MOVED_LOG = "[Forbric/Mixin] com.example.oldserver.mixin.ItemUseMixin: sameStack redirects "
			+ "canContinueUsing in net.minecraft.world.entity.LivingEntity.updatingUsingItem where the merged body calls it "
			+ "instead of isSameItem";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, movedOld, offOld;

	@BeforeAll static void weaveThreeWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(7, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "replacedcallredirect", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		moved = run("moved", Map.of());
		movedOld = run("moved-old", Map.of(OLD_SERVER, "true"));
		offOld = run("off-old", Map.of(OLD_SERVER, "true", ReplacedCallRedirects.PROPERTY, "off"));
	}

	@Test void letThroughNeoForgeIsAskedInItsOwnOrder() throws Exception {
		assertTrue(moved.printedLine(FORWARDED), moved.describe());
		assertTrue(moved.printed(MOVED_LOG), moved.describe());
		assertEquals(List.of(), losses(moved), moved.describe());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	@Test void onAnOldServerTheHandlerSeesVanillasOrderAndStopsTheUse() throws Exception {
		assertTrue(movedOldHolds(movedOld), movedOld.describe() + "\nfindings: " + movedOld.findings());
		WeaveHarness.assertWovenAndVerified(movedOld, TARGET, fixture);
	}

	@Test void switchedOffTheRedirectBindsNothingAndIsReported() throws Exception {
		assertTrue(offOldHolds(offOld), offOld.describe() + "\nfindings: " + offOld.findings());
	}

	/** The switch is the old-server runs' only difference, so each one's predicate must reject the other. */
	@Test void theControlFlipsEveryMoveAssertion() {
		assertTrue(movedOldHolds(movedOld) && !movedOldHolds(offOld), "the move predicate does not separate the runs");
		assertTrue(offOldHolds(offOld) && !offOldHolds(movedOld), "the control predicate does not separate the runs");
	}

	private static boolean movedOldHolds(WeaveHarness.Result run) {
		return run.printedLine(OLD) && run.printed(MOVED_LOG) && losses(run).isEmpty();
	}

	private static boolean offOldHolds(WeaveHarness.Result run) {
		return run.printedLine(FORWARDED) && !run.printed(MOVED_LOG) && !losses(run).isEmpty();
	}

	/** The final audit's rows for the mod's required injectors that attached nowhere and are not settled either way. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& f.required() && !"RESOLVED".equals(f.confidence())).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.replacedcallredirect.Probe", "probe", properties);
	}
}
