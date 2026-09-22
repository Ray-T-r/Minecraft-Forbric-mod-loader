package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Re-derives, from the staged bytecode, the hook census this project had only ever done by hand.
 *
 * <p>The numbers in {@code KernelGameScreenMouseEvents}' javadoc — 46 hooks declared, eight with a call site —
 * came from reading {@code javap -p -c} output and writing the answer down. So did the rows of
 * {@link DeadEventAudit#DEAD}. Both are claims about bytecode that is rebuilt whenever a carrier moves, and
 * neither had anything that would notice them going stale; the DEAD table had drifted to zero overlap with the
 * events measured dead in practice.
 *
 * <p>The synthetic {@code HookCallSiteCensusTest} pins what the census MEANS. This pins what it FINDS, and only
 * in the direction that cannot churn on an upstream bump: no exact counts, because those move every time the
 * merge does, but three relationships that must hold whatever the numbers are.
 */
class HookCallSiteCensusStagedTest {

	/** Hook classes and the carrier that declares each. */
	private static final Map<String, String> HOOK_CLASSES = Map.of(
			"net/minecraftforge/event/ForgeEventFactory", "forge-runtime/forge-runtime.jar",
			"net/minecraftforge/client/event/ForgeEventFactoryClient", "forge-runtime/forge-runtime.jar",
			"net/neoforged/neoforge/event/EventHooks", "neoforge-runtime/neoforge-runtime.jar");

	private static Path root() {
		String override = System.getenv("FORBRIC_OLD");
		if (override != null && !override.isBlank()) return Path.of(override, "run").normalize();
		return Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
	}

	/** The two ecosystem runtimes, for the third state. */
	private static List<Path> carriers() {
		List<Path> out = new ArrayList<>();
		for (String rel : List.of("forge-runtime/forge-runtime.jar", "neoforge-runtime/neoforge-runtime.jar")) {
			Path jar = root().resolve(rel);
			if (Files.isRegularFile(jar)) out.add(jar);
		}
		return out;
	}

	private static Path base() {
		return root().resolve("merged-base/patched-mc-merged-26.2.jar");
	}

	private static Map<String, HookCallSiteCensus.Census> censusAll() throws Exception {
		Map<String, HookCallSiteCensus.Census> out = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : HOOK_CLASSES.entrySet()) {
			Path carrier = root().resolve(e.getValue());
			if (!Files.isRegularFile(carrier)) continue;
			// The carriers as carriers, not as game: a hook the game never calls may still be reached through
			// its own ecosystem's runtime, and calling that dead put a delivered event on the work list once.
			out.put(e.getKey(), HookCallSiteCensus.of(carrier, e.getKey(), List.of(base()), carriers()));
		}
		return out;
	}

	@Test
	void theCensusRunsAndTheSurfaceIsNotAllAlive() throws Exception {
		assumeTrue(Files.isRegularFile(base()), "staged merged base absent");
		Map<String, HookCallSiteCensus.Census> all = censusAll();
		assumeTrue(!all.isEmpty(), "staged carriers absent");
		int declared = 0;
		int dead = 0;
		for (HookCallSiteCensus.Census c : all.values()) {
			System.out.println(c.summary());
			declared += c.declared().size();
			dead += c.dead().size();
		}
		// The denominator first: a census that scanned nothing must not read like one that found nothing.
		assertTrue(declared > 0, "no hooks declared — the census read the wrong jar or the wrong class name");
		// And the phenomenon the whole kernel exists for must still be measurable. If this ever goes to zero it
		// is either wonderful news or, far more likely, the scan silently stopped matching.
		assertTrue(dead > 0, "not one hook is dead across " + declared
				+ " declared — verify the scan before believing it");
	}

	@Test
	void everyRowOfTheDeadTableIsStillDeadInTheBytecode() throws Exception {
		assumeTrue(Files.isRegularFile(base()), "staged merged base absent");
		Map<String, HookCallSiteCensus.Census> all = censusAll();
		assumeTrue(!all.isEmpty(), "staged carriers absent");

		Map<String, Set<String>> postersOf = new TreeMap<>();
		Set<String> deadEvents = new TreeSet<>();
		Set<String> liveEvents = new TreeSet<>();
		for (HookCallSiteCensus.Census c : all.values()) {
			c.postersOf().forEach((event, posters) ->
					postersOf.computeIfAbsent(event, k -> new TreeSet<>()).addAll(posters));
			deadEvents.addAll(c.deadEvents());
			liveEvents.addAll(c.liveEvents());
		}
		// An event posted from more than one hook class is live if ANY of them is live.
		deadEvents.removeAll(liveEvents);

		List<String> lying = new ArrayList<>();
		int unjudged = 0;
		for (String event : DeadEventAudit.DEAD.keySet()) {
			if (!postersOf.containsKey(event)) {
				// No censused hook constructs it — this census has no opinion, by design. Registration events
				// posted from classes outside HOOK_CLASSES land here.
				unjudged++;
				continue;
			}
			if (liveEvents.contains(event)) lying.add(event);
		}
		System.out.println("[Forbric/Hooks] DEAD table: " + DeadEventAudit.DEAD.size() + " rows, "
				+ unjudged + " not judged by this census, " + lying.size() + " contradicted");
		assertTrue(lying.isEmpty(), "DeadEventAudit says these are never posted, but the merged base still calls "
				+ "a hook that constructs them — every mod waiting on one is being told it is degraded when it is "
				+ "not: " + lying);
		// If EVERY row were unjudged the assertion above would be vacuous, and that is exactly how a census
		// pointed at the wrong classes reads green.
		assertTrue(unjudged < DeadEventAudit.DEAD.size(),
				"not one DEAD row was judged — HOOK_CLASSES does not cover the classes that post them");
	}

	/** MinecraftForge's own patched game, as it was before the merge took some of its call sites. */
	private static Path forgePatchedGame() {
		return Path.of(System.getProperty("user.home"), "Library", "Application Support", "minecraft",
				"libraries", "net", "forbric", "patched-mc-forge", "26.2-65.0.1",
				"patched-mc-forge-26.2-65.0.1.jar");
	}

	@Test
	void thePartialTableIsExactlyWhatTheCallSitesSay() throws Exception {
		assumeTrue(Files.isRegularFile(base()), "staged merged base absent");
		Path before = forgePatchedGame();
		assumeTrue(Files.isRegularFile(before), "MinecraftForge's patched game absent");
		Path carrier = root().resolve("forge-runtime/forge-runtime.jar");
		assumeTrue(Files.isRegularFile(carrier), "staged Forge carrier absent");

		// hook -> the events it posts, so an eroded hook can be named by what a mod actually subscribes to.
		Map<String, Set<String>> eventsOfHook = new TreeMap<>();
		Set<String> measured = new TreeSet<>();
		int eroded = 0;
		for (String hookClass : List.of("net/minecraftforge/event/ForgeEventFactory",
				"net/minecraftforge/client/event/ForgeEventFactoryClient")) {
			HookCallSiteCensus.Census census = HookCallSiteCensus.of(carrier, hookClass, List.of(base()));
			census.postersOf().forEach((event, posters) -> posters.forEach(
					poster -> eventsOfHook.computeIfAbsent(poster, k -> new TreeSet<>()).add(event)));
			for (HookCallSiteCensus.Erosion e : HookCallSiteCensus.erosion(hookClass, before, base())) {
				if (!e.partial()) continue;
				eroded++;
				measured.addAll(eventsOfHook.getOrDefault(e.hook(), Set.of()));
			}
		}
		System.out.println("[Forbric/Hooks] partially kept hooks: " + eroded + ", events they post: " + measured.size());
		// The denominator: a run that found no erosion would make the equality below vacuously true against an
		// empty table, which is how a generator stops generating without anyone noticing.
		assertTrue(eroded > 0, "no hook lost part of its call sites — verify the scan before believing it");
		assertEquals(measured, new TreeSet<>(DeadEventAudit.PARTIAL.keySet()),
				"the partially-posted table and the call-site counts disagree; the table is the one that is wrong");
	}

	@Test
	void anEventIsNotBothNeverPostedAndPartlyPosted() throws Exception {
		// The two tables answer different questions and a row in both would make the audit's precedence decide
		// which sentence a player sees, which is not a decision precedence should be making.
		Set<String> both = new TreeSet<>(DeadEventAudit.DEAD.keySet());
		both.retainAll(DeadEventAudit.PARTIAL.keySet());
		assertEquals(Set.of(), both, "listed as never posted AND as partly posted: " + both);
	}

	@Test
	void noSinglePathPostsABridgedEventTwice() throws Exception {
		assumeTrue(Files.isRegularFile(base()), "staged merged base absent");
		List<String> both = HookCallSiteCensus.methodsCallingBothFamilies(List.of(base()));
		System.out.println("[Forbric/Hooks] methods calling both ecosystems' event hooks: " + both.size());
		assertEquals(List.of(), both,
				"a method that posts through both families can deliver a bridged event twice to one subscriber: "
						+ both);
	}
}
