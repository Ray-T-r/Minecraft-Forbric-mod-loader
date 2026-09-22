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

	private static Path base() {
		return root().resolve("merged-base/patched-mc-merged-26.2.jar");
	}

	private static Map<String, HookCallSiteCensus.Census> censusAll() throws Exception {
		Map<String, HookCallSiteCensus.Census> out = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : HOOK_CLASSES.entrySet()) {
			Path carrier = root().resolve(e.getValue());
			if (!Files.isRegularFile(carrier)) continue;
			out.put(e.getKey(), HookCallSiteCensus.of(carrier, e.getKey(), List.of(base())));
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
