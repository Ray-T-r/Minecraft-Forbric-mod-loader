package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.tree.ClassNode;

import com.electronwill.nightconfig.core.UnmodifiableConfig;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * R3 over the corpus its audit read: every mixin a mod's config lists, in every jar of the local compatibility packs and
 * sweeps and every jar nested in them, planned against NeoForge's staged base as KernelGuestMixinAdapter plans it, each
 * with the ecosystem whose manifest declares its config. Pins exactly which injectors R3 moves and where, so a row of
 * {@code carrier-renames.txt} that the census would let in by mistake shows here as a move nobody reviewed, and the
 * audit's wrong targets are named: with {@code -Dforbric.mixinRetarget.renameCensus=off} (the bytes alone, as before
 * the census) every one of them moves again.
 *
 * <p>The jars are third-party and not in the repository: symlink {@code build/compat-inputs}, the {@code run/client-*}
 * packs, {@code build/sweep100-mac-network}, {@code build/purefabric-2026-10-03} and the root
 * {@code build/random100-20260929} from the main checkout. The
 * full list of moves is written to {@code build/reports/r3-renamed-body-corpus.txt}.
 */
@ResourceLock("system-properties")
@org.junit.jupiter.api.extension.ExtendWith(net.forbric.kernel.mixin.VanillaLambdaReference.class)
class MixinRetargetRenamedBodyCorpusTest {
	private static final Path MERGED = TestFixtures.stagedBase();
	private static final Path NEO_RUNTIME = TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);
	/** The audit's corpus — the packs, the sweeps and the random Modrinth sample — and the pure-Fabric A/B's mods, each walked whole. */
	private static final List<Path> CORPUS = List.of(Path.of("build/compat-inputs"), Path.of("run/client-merged-pack/mods"),
			Path.of("run/client-neo-pack/mods"), Path.of("run/client-popular/mods"), Path.of("build/sweep100-mac-network/mods"),
			Path.of("build/purefabric-2026-10-03/mods"), Path.of("../build/random100-20260929/downloads"));

	/** How a move into a renamed body nothing calls reads: it binds there and never runs. */
	private static final String NEVER_RUNS = " (never runs)";

	/**
	 * Every move, as {@code mixin#handler -> renamed method}: each read against vanilla 26.2, both carriers' patched jars
	 * and NeoForge's base. The entity hooks follow NeoForge's {@code addEntityWithoutEvent} (the whole body); the anvil
	 * hooks its {@code createResultInternal}, Fabric API's HUD wraps its four layers and davids' preview rotation its
	 * {@code renderEntityInInventoryFollowsAngle} (pieces, handlers that need only the call); the sleep hooks the lambda
	 * that holds vanilla's checks (a piece the method hands the position in place, and whose lefts it returns: apoli's
	 * avian veto and Fabric API's direction wrap cancel with one); polymer's id and apoli's power lines
	 * the advanced tooltip tail (a piece handed the tooltip's own arguments); and the known-packs filter
	 * {@code runConfiguration}, which {@code handlePong} runs. malilib's last tooltip hook and trinkets' attribute line go
	 * to NeoForge's renamed tooltip body, which nothing calls: there they bind and never run, and the move says so.
	 */
	private static final Set<String> EXPECTED = Set.of(
			"carpet/mixins/PersistentEntitySectionManager_scarpetMixin#handleAddedEntity -> addEntityWithoutEvent",
			"com/craftjakob/mixin/fabric/event/PersistentEntitySectionManagerMixin#onAddEntity -> addEntityWithoutEvent",
			"com/pufferler/translucentarmor/mixin/InventoryScreenMixin#davids$applyLivePreviewRotation -> renderEntityInInventoryFollowsAngle",
			"dev/architectury/mixin/fabric/MixinPersistentEntitySectionManager#addEntity -> addEntityWithoutEvent",
			"eu/pb4/polymer/core/mixin/client/item/ItemStackMixin#polymer$changeId -> addDetailsToTooltipTail",
			"eu/pb4/trinkets/mixin/fabric/ItemStackMixin#getTooltipVanilla -> addDetailsToTooltipComponents" + NEVER_RUNS,
			"fi/dy/masa/malilib/mixin/item/MixinItemStack#onGetTooltipComponentsLast -> addDetailsToTooltipComponents" + NEVER_RUNS,
			"io/github/apace100/apoli/mixin/ItemStackMixinClient#addEquipmentPowerTooltips -> addDetailsToTooltipTail",
			"io/github/apace100/apoli/mixin/ServerPlayerEntityMixin#preventAvianSleep -> lambda$startSleepInBed$0",
			"me/drex/essentials/mixin/style/AnvilMenuMixin#itemNameFormatting -> createResultInternal",
			"net/everlasting/mixin/AnvilMenuMixin#everlasting$blockFurtherEnchanting -> createResultInternal",
			"net/fabricmc/fabric/mixin/client/rendering/HudMixin#wrapAirBar -> extractAirLevel",
			"net/fabricmc/fabric/mixin/client/rendering/HudMixin#wrapArmorBar -> extractArmorLevel",
			"net/fabricmc/fabric/mixin/client/rendering/HudMixin#wrapFoodBar -> extractFoodLevel",
			"net/fabricmc/fabric/mixin/client/rendering/HudMixin#wrapHealthBar -> extractHealthLevel",
			"net/fabricmc/fabric/mixin/entity/event/ServerPlayerMixin#hasNoMonstersNearby -> lambda$startSleepInBed$0",
			"net/fabricmc/fabric/mixin/entity/event/ServerPlayerMixin#onSetSpawnPoint -> lambda$startSleepInBed$0",
			"net/fabricmc/fabric/mixin/entity/event/ServerPlayerMixin#redirectSleepDirection -> lambda$startSleepInBed$0",
			"net/fabricmc/fabric/mixin/resource/ServerConfigurationPacketListenerImplMixin#filterKnownPacks -> runConfiguration");
	/*
	 * Counted per jar, the moves are not pinned by number: the corpus lost jars since the count was taken (52 then, 44 at
	 * C5 on NeoForge's base, with the lost random100 copies), and a number cannot say which jar stopped moving. Instead each
	 * move is pinned to every jar that carries it: every unit listing the move's mixin with that handler as an injector
	 * moves it, and no unit moves a handler it does not carry.
	 */

	/**
	 * What R3 moved on the bytes alone and must not: the audit's wrong targets (methods of one shape, each vanilla's
	 * own), a NeoForge mod's genuine rename (it was compiled against it), the piece move whose handler means something
	 * else in a piece — an index it shares with the injectors left in the method — and Fabric API's tooltip injectors into
	 * the renamed body nothing calls, which share that index too (they could not bind there as in vanilla's body).
	 * The audit's fourth wrong target, goldenpotions' {@code CreativeModeTabs} hook into {@code lambda$bootstrap$24}, was a
	 * merged-base lambda numbering: NeoForge's {@code CreativeModeTabs} numbers its {@code bootstrap} lambdas as vanilla's
	 * (javap of both), so not even the bytes alone move it, and {@link #EXPECTED} pins that it does not move.
	 */
	private static final Set<String> REFUSED = Set.of(
			"-> withShadowColor", "-> releaseUsingItem", "-> keyPressed",
			"net/fabricmc/fabric/mixin/item/ItemStackMixin#preAppendComponentTooltip -> addDetailsToTooltipComponents",
			"taxfreelevels/", "net/fabricmc/fabric/mixin/item/ItemStackMixin#postTooltipsAdvanced -> addDetailsToTooltipTail");

	/** This suite pins the legacy rule independently; execution-path proofs have their own positive/negative tests. */
	@org.junit.jupiter.api.BeforeEach
	void legacyRuleScope() { System.setProperty(MixinExecutionPathRetarget.PROPERTY, "off"); }

	@AfterEach
	void reset() {
		System.clearProperty(MixinExecutionPathRetarget.PROPERTY);
		System.clearProperty(MixinRetarget.RENAME_CENSUS_PROPERTY);
		MixinStubRebind.forget();
		MixinRetarget.reset();
	}

	@Test
	void r3MovesExactlyThePinnedInjectorsOverTheAuditCorpus() throws Exception {
		for (Path p : List.of(MERGED, NEO_RUNTIME)) TestFixtures.requireFile(Fixture.STAGED, p, p + " required");
		for (Path p : CORPUS) TestFixtures.require(Fixture.THIRD_PARTY, Files.isDirectory(p), p + " required (symlink it from the main checkout)");
		Function<String, byte[]> resolver = resolver();

		List<Path> jars = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Path dir : CORPUS) {
			try (Stream<Path> walk = Files.walk(dir, java.nio.file.FileVisitOption.FOLLOW_LINKS)) {
				for (Path jar : walk.filter(p -> p.toString().endsWith(".jar") && Files.isRegularFile(p)).sorted().toList()) {
					if (seen.add(jar.getFileName() + "#" + Files.size(jar))) jars.add(jar);
				}
			}
		}
		Census census = new Census(resolver);
		for (Path jar : jars) census.unit(jar.getFileName().toString(), Files.readAllBytes(jar), seen);
		Set<String> moves = census.moves, inJars = census.inJars, bytesAlone = census.bytesAlone, carried = census.carried;
		int mixins = census.mixins;
		int units = census.units;
		System.clearProperty(MixinRetarget.RENAME_CENSUS_PROPERTY);

		StringBuilder report = new StringBuilder(String.format("R3 over %d jars (%d units), %d listed mixins: %d moves in jars, %d distinct;"
				+ " on the bytes alone %d distinct%n%n", jars.size(), units, mixins, inJars.size(), moves.size(), bytesAlone.size()));
		inJars.forEach(line -> report.append(line).append('\n'));
		report.append("\nbytes alone:\n");
		bytesAlone.forEach(line -> report.append(line).append('\n'));
		Files.createDirectories(Path.of("build/reports"));
		Files.writeString(Path.of("build/reports/r3-renamed-body-corpus.txt"), report.toString());
		System.out.println(report);

		assertEquals(EXPECTED, moves, report.toString());
		Set<String> movedInJars = new TreeSet<>();
		for (String line : inJars) movedInJars.add(line.substring(0, line.lastIndexOf("  [")));
		assertEquals(carried, movedInJars, "every jar that carries a pinned handler moves it, and only those\n" + report);
		System.out.printf("R3: %d per-jar moves, each in a jar that carries its handler%n", movedInJars.size());
		for (String refused : REFUSED) {
			assertFalse(moves.stream().anyMatch(move -> move.contains(refused)), refused);
			assertTrue(bytesAlone.stream().anyMatch(move -> move.contains(refused)), "control, on the bytes alone: " + refused);
		}
	}

	/** R3's moves in {@code mixin}'s plan, as {@code mixin#handler -> renamed method}. */
	private static List<String> renamedBodyMoves(ClassNode mixin, Function<String, byte[]> resolver) {
		List<String> out = new ArrayList<>();
		MixinRetarget.Plan plan;
		try {
			plan = MixinRetarget.plan(mixin, resolver);
		} catch (RuntimeException unplannable) {
			return out;
		}
		for (MixinRetarget.Rewrite rewrite : plan.rewrites()) {
			if (!rewrite.why().contains("renamed the vanilla body")) continue;
			out.add(mixin.name + "#" + rewrite.handler() + " -> " + rewrite.to().substring(0, rewrite.to().indexOf('('))
					+ (rewrite.why().contains("never runs") ? NEVER_RUNS : ""));
		}
		return out;
	}

	/** What R3 does to every listed mixin of each unit read: a jar, and every jar nested in it at any depth, once each. */
	private static final class Census {
		final Function<String, byte[]> resolver;
		final Set<String> moves = new TreeSet<>(), inJars = new TreeSet<>(), bytesAlone = new TreeSet<>(), carried = new TreeSet<>();
		int mixins, units;

		Census(Function<String, byte[]> resolver) {
			this.resolver = resolver;
		}

		/** {@code jar} and the jars nested in it (Fabric {@code META-INF/jars}, Forge {@code META-INF/jarjar}), one at a time. */
		void unit(String name, byte[] jar, Set<String> seen) throws IOException {
			Map<String, byte[]> content;
			try {
				content = MixinFitLivenessCensusStagedTest.read(jar, MixinRetargetRenamedBodyCorpusTest::wanted);
			} catch (IOException | RuntimeException notAJar) {
				return;
			}
			units++;
			judge(name, content);
			for (Map.Entry<String, byte[]> e : content.entrySet()) {
				String entry = e.getKey();
				if (!entry.endsWith(".jar") || !(entry.startsWith("META-INF/jars/") || entry.startsWith("META-INF/jarjar/"))) continue;
				String nested = entry.substring(entry.lastIndexOf('/') + 1);
				if (seen.add(nested + "#" + e.getValue().length)) unit(name + "!" + nested, e.getValue(), seen);
			}
		}

		/** Every mixin a config of the unit lists, owned by the ecosystem whose manifest declares the config. */
		private void judge(String unit, Map<String, byte[]> content) {
			for (Map.Entry<String, Ecosystem> config : MixinFitLivenessCensusStagedTest.configOwners(content).entrySet()) {
				UnmodifiableConfig parsed = MixinFitLivenessCensusStagedTest.parse(content.get(config.getKey()));
				if (parsed == null) continue;
				String pkg = String.valueOf(parsed.<Object>get(List.of("package")));
				for (String entry : MixinFitLivenessCensusStagedTest.entries(parsed)) {
					byte[] bytes = content.get(pkg.replace('.', '/') + "/" + entry.replace('.', '/') + ".class");
					if (bytes == null) continue;
					ClassNode mixin;
					try {
						mixin = MixinFit.parse(bytes);
					} catch (RuntimeException unreadable) {
						continue;
					}
					mixins++;
					// The pinned moves this unit carries: the move's mixin listed, declaring the handler as an injector.
					for (String expected : EXPECTED) {
						String handler = expected.substring(expected.indexOf('#') + 1, expected.indexOf(" -> "));
						if (expected.startsWith(mixin.name + "#") && mixin.methods.stream()
								.anyMatch(m -> m.name.equals(handler) && MixinFit.injectorOf(m) != null)) carried.add(unit + "  " + expected);
					}
					MixinStubRebind.noteEcosystem(mixin.name, config.getValue());
					System.clearProperty(MixinRetarget.RENAME_CENSUS_PROPERTY);
					for (String move : renamedBodyMoves(mixin, resolver)) {
						moves.add(move);
						inJars.add(unit + "  " + move + "  [" + config.getValue() + "]");
					}
					System.setProperty(MixinRetarget.RENAME_CENSUS_PROPERTY, "off");
					for (String move : renamedBodyMoves(mixin, resolver)) bytesAlone.add(move + "  [" + config.getValue() + "]");
					System.clearProperty(MixinRetarget.RENAME_CENSUS_PROPERTY);
				}
			}
		}
	}

	private static boolean wanted(String name) {
		return name.endsWith(".class") || name.endsWith(".json") || name.endsWith(".jar") || name.endsWith(".toml") || name.endsWith("MANIFEST.MF");
	}

	/** The staged game as the jars hold it. */
	private static Function<String, byte[]> resolver() throws IOException {
		Map<String, byte[]> game = new HashMap<>();
		for (Path jar : List.of(NEO_RUNTIME, MERGED)) {
			game.putAll(MixinFitLivenessCensusStagedTest.read(Files.readAllBytes(jar), n -> n.endsWith(".class")));
		}
		return game::get;
	}
}
