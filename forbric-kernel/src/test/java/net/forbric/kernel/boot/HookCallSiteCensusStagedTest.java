package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.forbric.api.GameEventBridge;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.NativeDualEventInjector;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;

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
			// ForgeHooks posts the damage family (attack, hurt, damage) and more; left out, the census was blind to
			// every one of them and the dead table never grew rows for them.
			"net/minecraftforge/common/ForgeHooks", "forge-runtime/forge-runtime.jar",
			// And the rest of both families' hook classes: the client's (the F3 text, tooltips, sounds) and NeoForge's
			// own two, so that a dead event of either family has no hook class left to hide in.
			"net/minecraftforge/client/ForgeHooksClient", "forge-runtime/forge-runtime.jar",
			"net/neoforged/neoforge/event/EventHooks", "neoforge-runtime/neoforge-runtime.jar",
			"net/neoforged/neoforge/common/CommonHooks", "neoforge-runtime/neoforge-runtime.jar",
			"net/neoforged/neoforge/client/ClientHooks", "neoforge-runtime/neoforge-runtime.jar");

	private static Path root() {
		return TestFixtures.stagedRoot().normalize();
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
		return root().resolve(TestFixtures.BASE_JAR);
	}

	/** The kernel's own game-side classes, compiled beside these tests: the fourth place an event can come from. */
	private static Path kernelRuntime() {
		return Path.of(System.getProperty("forbric.test.runtimeClasses",
				Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").toString())).normalize();
	}

	/**
	 * The kernel's boot-side classes, compiled beside these tests: they cannot name a game type, so they call a family
	 * method by reflection ({@code PayloadInterop} gathers MinecraftForge's configuration tasks that way), which the census
	 * reads as a call.
	 */
	private static Path kernelMain() {
		return Path.of(System.getProperty("forbric.test.mainClasses",
				Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main").toString())).normalize();
	}

	/** Each family's own patched game, as the merge read it: what tells a game's entry from API a guest calls. */
	private static List<Path> natives() {
		List<Path> out = new ArrayList<>();
		for (Path game : List.of(forgePatchedGame(), neoForgePatchedGame())) if (Files.isRegularFile(game)) out.add(game);
		return out;
	}

	/** The censused hook classes whose carrier is staged, each with its carrier. */
	private static Map<String, Path> stagedHooks(java.util.function.Predicate<String> family) {
		Map<String, Path> hooks = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : new TreeMap<>(HOOK_CLASSES).entrySet()) {
			Path carrier = root().resolve(e.getValue());
			if (family.test(e.getKey()) && Files.isRegularFile(carrier)) hooks.put(e.getKey(), carrier);
		}
		return hooks;
	}

	/** One census of the staged base per run: replaying the merged-base repairs over every class is not free. */
	private static volatile Map<String, HookCallSiteCensus.Census> cachedCensus;

	private static Map<String, HookCallSiteCensus.Census> censusAll() throws Exception {
		Map<String, HookCallSiteCensus.Census> cached = cachedCensus;
		if (cached != null) return cached;
		Map<String, Path> hooks = stagedHooks(hook -> true);
		if (hooks.isEmpty() || !Files.isRegularFile(base())) return Map.of();
		// The carriers as carriers, not as game: a hook the game never calls may still be reached through
		// its own ecosystem's runtime, and calling that dead put a delivered event on the work list once. All hook
		// classes at once, because a carrier caller that is itself a hook counts only when something reaches it. And
		// the game as the kernel loads it: a merged-base repair that sends a hook call elsewhere takes that call away.
		try (var loadTime = new net.forbric.kernel.transform.MergedBaseLoadTimeView(served())) {
			cached = HookCallSiteCensus.all(hooks, List.of(base()), carriers(), List.of(kernelRuntime(), kernelMain()), loadTime,
					natives());
		}
		cachedCensus = cached;
		return cached;
	}

	/** The same census of the staged bytes, the merged-base repairs not replayed: what a repair moves is the difference. */
	private static volatile Map<String, HookCallSiteCensus.Census> cachedStaged;

	private static Map<String, HookCallSiteCensus.Census> censusStaged() throws Exception {
		Map<String, HookCallSiteCensus.Census> cached = cachedStaged;
		if (cached != null) return cached;
		cached = HookCallSiteCensus.all(stagedHooks(hook -> true), List.of(base()), carriers(), List.of(kernelRuntime(), kernelMain()));
		cachedStaged = cached;
		return cached;
	}

	/** One census, summed over every hook class: an event posted by several hook classes is live if any of them posts it. */
	private record Measured(Set<String> dead, Set<String> live, Set<String> kernelDelivered, Set<String> guestPosted,
			Set<String> kernelConstructs, Map<String, Set<String>> postsThrough) {
	}

	private static Measured measure(Map<String, HookCallSiteCensus.Census> all) {
		Set<String> dead = new TreeSet<>(), live = new TreeSet<>(), kernel = new TreeSet<>(), guest = new TreeSet<>(),
				constructs = new TreeSet<>();
		Map<String, Set<String>> postsThrough = new TreeMap<>();
		for (HookCallSiteCensus.Census c : all.values()) {
			dead.addAll(c.deadEvents());
			live.addAll(c.liveEvents());
			kernel.addAll(c.kernelDelivered());
			guest.addAll(c.guestPosted());
			constructs.addAll(c.kernelConstructs());
			c.postsThrough().forEach((hook, events) -> postsThrough.put(c.hookClass() + "." + hook, events));
		}
		dead.removeAll(live);
		kernel.retainAll(dead);
		guest.retainAll(dead);
		return new Measured(dead, live, kernel, guest, constructs, postsThrough);
	}

	/** What a launch serves game classes from, for the repairs that read another class: the base, then the carriers. */
	private static List<Path> served() {
		List<Path> out = new ArrayList<>(List.of(base()));
		out.addAll(carriers());
		return out;
	}

	/** NeoForge's own patched game, as it was before the merge. */
	private static Path neoForgePatchedGame() {
		return root().resolve(TestFixtures.GENUINE_BASE_JAR);
	}

	private static boolean forgeFamily(String internalName) {
		return internalName.startsWith("net/minecraftforge/");
	}

	/** Each family's hook classes against that family's own patched game: what its loader posts natively. */
	private static Map<String, Map<String, Path>> nativeSides() {
		Map<String, Map<String, Path>> out = new LinkedHashMap<>();
		out.put(forgePatchedGame().toString(), stagedHooks(HookCallSiteCensusStagedTest::forgeFamily));
		out.put(neoForgePatchedGame().toString(), stagedHooks(hook -> !forgeFamily(hook)));
		return out;
	}

	/**
	 * The events each family's own patched game never posts either: every hook constructing one is called by neither that
	 * game nor a reached method of that family's runtime. Read from the staged copies the merge itself read.
	 */
	private static volatile Set<String> cachedNativelyDead;

	private static Set<String> nativelyNeverPosted() throws Exception {
		Set<String> cached = cachedNativelyDead;
		if (cached != null) return cached;
		Set<String> dead = new TreeSet<>(), live = new TreeSet<>();
		for (Map.Entry<String, Map<String, Path>> side : nativeSides().entrySet()) {
			Path game = Path.of(side.getKey());
			TestFixtures.require(Fixture.STAGED, Files.isRegularFile(game), "a family's own patched game is absent: " + game);
			if (side.getValue().isEmpty()) continue;
			for (HookCallSiteCensus.Census c : HookCallSiteCensus.all(side.getValue(), List.of(game),
					List.copyOf(new java.util.LinkedHashSet<>(side.getValue().values())), List.of()).values()) {
				dead.addAll(c.deadEvents());
				live.addAll(c.liveEvents());
			}
		}
		dead.removeAll(live);
		cachedNativelyDead = Set.copyOf(dead);
		return dead;
	}

	/** Every event the census measures as never posted, over every hook class: dead in one and live in none. */
	private static Map<String, HookCallSiteCensus.Census> measuredDead(Map<String, HookCallSiteCensus.Census> all, Set<String> dead,
			Set<String> kernelDelivered) {
		Set<String> live = new TreeSet<>();
		for (HookCallSiteCensus.Census c : all.values()) {
			dead.addAll(c.deadEvents());
			live.addAll(c.liveEvents());
			kernelDelivered.addAll(c.kernelDelivered());
		}
		dead.removeAll(live);
		kernelDelivered.retainAll(dead);
		return all;
	}

	/**
	 * The other direction, which nothing checked until 2026-10-24: every event the census measures as never posted has a
	 * row — the audit names a mod waiting on it ({@link DeadEventAudit#DEAD}, or {@link DeadEventAudit#PARTIAL} when a
	 * guest or the kernel still posts it on some path), or a bridge or repair delivers it ({@link DeadEventAudit#BRIDGED},
	 * {@link DeadEventAudit#REPAIRED}, {@link DeadEventAudit#STAND_DOWN} — each of which must then prove it, see
	 * {@link #everyRowThatSilencesTheAuditProvesItsDelivery}). The table had only ever been checked for rows that lie, so it
	 * could leave out a measured-dead event: MinecraftForge's LivingDrownEvent was measured dead and named nowhere, and a
	 * MinecraftForge mod that stopped drowning did nothing with no line saying so.
	 */
	@Test
	void everyEventTheCensusMeasuresDeadHasARow() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(kernelRuntime()), "the kernel's game side is not compiled");
		Map<String, HookCallSiteCensus.Census> all = censusAll();
		TestFixtures.require(Fixture.STAGED, !all.isEmpty(), "staged carriers absent");
		Set<String> dead = new TreeSet<>(), kernel = new TreeSet<>();
		measuredDead(all, dead, kernel);
		// What the merge lost, not what was never there: an event its own family's patched game never posts either is
		// waited for in vain under that family's own loader too, and a row would accuse its listeners of a loss they have
		// natively (four MinecraftForge events were rows for one round for exactly that reason).
		Set<String> nativelyDead = nativelyNeverPosted();
		List<String> unnamed = new ArrayList<>();
		int native_ = 0;
		for (String event : dead) {
			if (nativelyDead.contains(event)) {
				native_++;
				continue;
			}
			if (DeadEventAudit.DEAD.containsKey(event) || DeadEventAudit.PARTIAL.containsKey(event) || DeadEventAudit.BRIDGED.containsKey(event)
					|| DeadEventAudit.REPAIRED.contains(event) || DeadEventAudit.STAND_DOWN.containsKey(event)) continue;
			unnamed.add(event + (kernel.contains(event) ? " (the kernel delivers it: a BRIDGED or REPAIRED row)" : ""));
		}
		System.out.println("[Forbric/Hooks] measured never posted: " + dead.size() + " event(s), " + kernel.size()
				+ " delivered by the kernel, " + native_ + " never posted by their own family either, " + unnamed.size()
				+ " named in no table");
		// The denominator: a census that measured nothing dead would pass this vacuously.
		assertTrue(dead.size() > 0, "not one event is measured dead — verify the scan before believing it");
		assertEquals(List.of(), unnamed, "events the merged game never posts that no table names: a mod waiting on one is "
				+ "told nothing, and nothing says whether a bridge carries it");
	}

	/**
	 * No table names an event its own family's patched game never posts. A DEAD or PARTIAL row would mark a mod DEGRADED
	 * for a loss its own loader has too ("which this merged game never posts" is then a false accusation); a bridge or a
	 * repair would post what the native loader never does. Measured on the family's own patched game with the same census
	 * as the merged base: MinecraftForge 26.2 never posts PotionBrewEvent$Pre, EntityTeleportEvent$ChorusFruit,
	 * ChunkDataEvent$Load or CustomizeGuiOverlayEvent$DebugText, and those four were DEAD rows for one round.
	 */
	@Test
	void noTableNamesAnEventItsOwnFamilyNeverPosts() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		Set<String> nativelyDead = nativelyNeverPosted();
		System.out.println("[Forbric/Hooks] never posted by their own family's patched game: " + nativelyDead);
		Map<String, String> named = new TreeMap<>();
		for (String event : nativelyDead) {
			if (DeadEventAudit.DEAD.containsKey(event)) named.put(event, "DEAD");
			if (DeadEventAudit.PARTIAL.containsKey(event)) named.put(event, "PARTIAL");
			if (DeadEventAudit.BRIDGED.containsKey(event)) named.put(event, "BRIDGED");
			if (DeadEventAudit.STAND_DOWN.containsKey(event)) named.put(event, "STAND_DOWN");
			if (DeadEventAudit.REPAIRED.contains(event)) named.put(event, "REPAIRED");
		}
		// The denominator, as measured on the staged MinecraftForge 26.2-65.0.1: a native census that found nothing would
		// make the assertion below vacuous.
		assertTrue(!nativelyDead.isEmpty(), "the native census found no event its own family never posts — verify the scan");
		assertEquals(Map.of(), named, "rows for events the native loader never posts either");
	}

	/**
	 * {@link DeadEventAudit#CLIENT_ONLY} is exactly the DEAD and PARTIAL rows MinecraftForge posts natively only on the
	 * client: every method of its patched game whose calls reach a hook constructing the event carries the
	 * {@code @OnlyIn(Dist.CLIENT)} its merge of the client and server jars puts on what only the client jar has (on the
	 * method or its class). The table replaced a package rule, which named a mod DEGRADED on every dedicated server for
	 * {@code PlayerInteractEvent$LeftClickEmpty} — a common-package event posted from {@code Minecraft} alone.
	 */
	@Test
	void theClientOnlyTableIsWhereTheNativeLoaderPostsOnlyOnTheClient() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		Map<String, Set<HookCallSiteCensus.PostingSite>> sites = new TreeMap<>();
		for (Map.Entry<String, Map<String, Path>> side : nativeSides().entrySet()) {
			Path game = Path.of(side.getKey());
			TestFixtures.require(Fixture.STAGED, Files.isRegularFile(game), "a family's own patched game is absent: " + game);
			if (side.getValue().isEmpty()) continue;
			sites.putAll(HookCallSiteCensus.postingSites(side.getValue(), List.of(game),
					List.copyOf(new java.util.LinkedHashSet<>(side.getValue().values()))));
		}
		Set<String> rows = new TreeSet<>(DeadEventAudit.DEAD.keySet());
		rows.addAll(DeadEventAudit.PARTIAL.keySet());
		Set<String> clientOnly = new TreeSet<>();
		List<String> unjudgeable = new ArrayList<>();
		int clientSites = 0;
		for (String event : rows) {
			Set<HookCallSiteCensus.PostingSite> posted = sites.getOrDefault(event, Set.of());
			if (posted.isEmpty()) continue;
			boolean common = false, carrier = false;
			for (HookCallSiteCensus.PostingSite site : posted) {
				if (site.carrier()) carrier = true;
				else if (site.clientOnly()) clientSites++;
				else common = true;
			}
			if (common) continue;
			// Posted from client-only game code and from a carrier method this cannot place: a person has to say which.
			if (carrier) unjudgeable.add(event + " " + posted);
			else clientOnly.add(event);
		}
		System.out.println("[Forbric/Hooks] rows posted natively only on the client: " + clientOnly.size() + " of " + rows.size());
		assertTrue(clientSites > 0, "no posting site is client-only — the @OnlyIn reading matched nothing; verify it");
		assertEquals(List.of(), unjudgeable, "rows whose native posting this census cannot place on a side");
		assertEquals(clientOnly, new TreeSet<>(DeadEventAudit.CLIENT_ONLY),
				"CLIENT_ONLY and where MinecraftForge posts these events disagree; the table is the one that is wrong");
	}

	/**
	 * The census reads the game as the kernel's merged-base repairs leave it. {@code routePlaceItemHookToNeoForge} sends
	 * {@code ItemStack.useOn}'s {@code ForgeHooks.onPlaceItemIntoWorld} to NeoForge's {@code CommonHooks}: read off the
	 * staged bytes MinecraftForge's multi-block place event is posted and NeoForge's is not, and at runtime it is the other
	 * way round — which is what the census, and the tables, must say.
	 */
	@Test
	void theCensusSeesTheCallsTheMergedBaseRepairsMove() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(kernelRuntime()), "the kernel's game side is not compiled");
		Map<String, HookCallSiteCensus.Census> loaded = censusAll();
		TestFixtures.require(Fixture.STAGED, !loaded.isEmpty(), "staged carriers absent");
		Map<String, HookCallSiteCensus.Census> staged = censusStaged();
		String forgeMulti = "net/minecraftforge/event/level/BlockEvent$EntityMultiPlaceEvent";
		String neoMulti = "net/neoforged/neoforge/event/level/BlockEvent$EntityMultiPlaceEvent";
		Set<String> deadLoaded = new TreeSet<>(), deadStaged = new TreeSet<>();
		measuredDead(loaded, deadLoaded, new TreeSet<>());
		measuredDead(staged, deadStaged, new TreeSet<>());
		assertFalse(deadStaged.contains(forgeMulti), "the staged ItemStack.useOn calls ForgeHooks.onPlaceItemIntoWorld");
		assertTrue(deadLoaded.contains(forgeMulti), "the repair takes that call away, so nothing posts it at runtime");
		assertTrue(deadStaged.contains(neoMulti), "the staged game never calls CommonHooks.onPlaceItemIntoWorld");
		assertFalse(deadLoaded.contains(neoMulti), "the repair sends useOn there, so NeoForge's is posted at runtime");
		assertTrue(DeadEventAudit.DEAD.containsKey(forgeMulti), "a mod waiting for MinecraftForge's multi-block place is named");
	}

	/** The census, the guests' poster scan and this test read the same hook classes. */
	@Test
	void theCensusedHookClassesAreBothFamiliesHookClasses() {
		assertEquals(HookCallSiteCensus.HOOK_CLASSES, HOOK_CLASSES.keySet());
	}

	/**
	 * No {@link DeadEventAudit#DEAD} row names an event something delivers. A row that says "never posted" about an event a
	 * bridge, a repair or the kernel posts names a mod for an event it receives — the precedence in
	 * {@link DeadEventAudit#audit} used to hide such a row (a bridge is consulted first), and the census could only catch
	 * one it judges: {@code PlayerInteractEvent$RightClickBlock} stayed a DEAD row behind its BRIDGED one for a round because
	 * no hook class constructs it. So the rows are checked against the other tables, and against everything the kernel's
	 * own code constructs, whether the census judges the event or not.
	 */
	@Test
	void noDeadRowNamesADeliveredEvent() throws Exception {
		Map<String, String> elsewhere = new TreeMap<>();
		for (String event : DeadEventAudit.DEAD.keySet()) {
			if (DeadEventAudit.BRIDGED.containsKey(event)) elsewhere.put(event, "BRIDGED");
			if (DeadEventAudit.STAND_DOWN.containsKey(event)) elsewhere.put(event, "STAND_DOWN");
			if (DeadEventAudit.REPAIRED.contains(event)) elsewhere.put(event, "REPAIRED");
			if (DeadEventAudit.PARTIAL.containsKey(event)) elsewhere.put(event, "PARTIAL");
		}
		assertEquals(Map.of(), elsewhere, "DEAD rows for events another table says are delivered");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(kernelRuntime()), "the kernel's game side is not compiled");
		Map<String, HookCallSiteCensus.Census> all = censusAll();
		TestFixtures.require(Fixture.STAGED, !all.isEmpty(), "staged carriers absent");
		Measured m = measure(all);
		assertTrue(m.kernelDelivered().size() > 0, "the kernel delivers none of the dead events — the fourth source read nothing");
		Set<String> delivered = new TreeSet<>(DeadEventAudit.DEAD.keySet());
		delivered.retainAll(m.kernelConstructs());
		Set<String> measured = new TreeSet<>(DeadEventAudit.DEAD.keySet());
		measured.retainAll(m.kernelDelivered());
		delivered.addAll(measured);
		assertEquals(Set.of(), delivered, "listed as never posted although the kernel posts them: move each to BRIDGED or REPAIRED");
	}

	/**
	 * Every row that silences the audit for its event proves the delivery it claims, read from the kernel's bytecode.
	 *
	 * <p>A {@link DeadEventAudit#BRIDGED} or {@link DeadEventAudit#STAND_DOWN} row means "this bridge posts it": the bridge is
	 * installed by {@link GameEventMultiplexer} (its install method exists on the runtime class the setup names, public and
	 * static, and is declared to {@link KernelRuntimeClasses}, the boot-time seam check) or, for a pass a transformer lands,
	 * recorded by the kernel class that lands it — and what that reaches, through the kernel and then the carriers,
	 * posts the event. A {@link DeadEventAudit#REPAIRED} row means "the kernel posts it": its own code posts it, or the
	 * merged-base repairs make the game post it (posted as the game is loaded, not as it is staged). Posting is read by
	 * {@link BridgeDeliveryProof}: a kernel construction that flows into a bus's {@code post} or {@code fire}, or a
	 * construction in a family method the kernel reaches, an event's own constructor and instance methods left unfollowed.
	 *
	 * <p>Until 2026-10-25 only the DEAD table was held to the bytecode. Moving {@code PlayerXpEvent$XpChange}, which nothing
	 * delivers, into REPAIRED, into STAND_DOWN under the level-save bridge, or into BRIDGED under the drown bridge left the
	 * suite green, and each would have silenced the audit for every mod waiting on it. For one more round a construction
	 * counted as a post (the drown bridge with its {@code post} taken out still proved its row), and so did what another
	 * event's constructor sets off: {@code PlayerEvent$HarvestCheck}, asked by MinecraftForge's {@code BreakEvent}
	 * constructor, proved a row under the break bridge or in REPAIRED, which would have silenced the audit for a check the
	 * game asks on paths no break takes.
	 */
	@Test
	void everyRowThatSilencesTheAuditProvesItsDelivery() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(kernelRuntime()) && Files.isDirectory(kernelMain()),
				"the kernel is not compiled");
		Map<String, HookCallSiteCensus.Census> all = censusAll();
		TestFixtures.require(Fixture.STAGED, !all.isEmpty(), "staged carriers absent");
		BridgeDeliveryProof proof = new BridgeDeliveryProof(List.of(kernelMain(), kernelRuntime()), carriers());
		Map<GameEventBridge, BridgeDeliveryProof.Entry> installs = proof.multiplexerInstalls();
		Map<GameEventBridge, Set<String>> recorders = proof.recorders();
		Map<GameEventBridge, Set<String>> posts = new HashMap<>();
		List<String> unproved = new ArrayList<>();
		int bridgeRows = 0;
		for (Map<String, GameEventBridge> table : List.of(DeadEventAudit.BRIDGED, DeadEventAudit.STAND_DOWN)) {
			for (Map.Entry<String, GameEventBridge> row : new TreeMap<>(table).entrySet()) {
				bridgeRows++;
				GameEventBridge bridge = row.getValue();
				BridgeDeliveryProof.Entry install = installs.get(bridge);
				boolean multiplexed = bridge.pass() == GameEventBridge.Pass.GAME_BUS || bridge.pass() == GameEventBridge.Pass.CLIENT_GAME_BUS
						|| bridge.pass() == GameEventBridge.Pass.CLIENT_MOD_BUS;
				String event = row.getKey().substring(row.getKey().lastIndexOf('/') + 1);
				if (multiplexed && install == null) {
					unproved.add(event + ": " + bridge + " is a " + bridge.pass() + " bridge GameEventMultiplexer never installs");
					continue;
				}
				if (install != null && !proof.exists(install)) {
					unproved.add(event + ": " + bridge + "'s setup names " + install + ", which is not a public static method");
					continue;
				}
				if (install != null && !declaredToTheSeamCheck(install)) {
					unproved.add(event + ": " + bridge + "'s install " + install + " is not declared to KernelRuntimeClasses");
				}
				if (!multiplexed && recorders.getOrDefault(bridge, Set.of()).isEmpty()) {
					unproved.add(event + ": " + bridge + " is a " + bridge.pass() + " bridge no kernel class records as installed");
					continue;
				}
				Set<String> delivered = posts.computeIfAbsent(bridge, b -> {
					Set<String> entries = new TreeSet<>();
					if (install != null) {
						entries.add(install.owner() + "." + install.method());
						entries.addAll(proof.routedInto(install.owner()));
					}
					for (String recorder : recorders.getOrDefault(b, Set.of())) entries.addAll(proof.entriesOf(recorder));
					return proof.posts(entries);
				});
				if (!delivered.contains(row.getKey())) unproved.add(event + ": " + bridge + " does not post it");
			}
		}
		Measured loaded = measure(all), staged = measure(censusStaged());
		Set<String> kernelPosts = proof.postedByTheKernel();
		for (String event : new TreeSet<>(DeadEventAudit.REPAIRED)) {
			boolean byKernel = kernelPosts.contains(event);
			boolean byRepair = loaded.live().contains(event) && !staged.live().contains(event);
			if (!byKernel && !byRepair) unproved.add(event + ": REPAIRED, but neither the kernel's code nor a merged-base repair posts it");
		}
		System.out.println("[Forbric/Hooks] delivery proved for " + bridgeRows + " bridge row(s) through " + posts.size()
				+ " bridge(s) (" + installs.size() + " installed by the multiplexer, " + recorders.size() + " recorded elsewhere) and "
				+ DeadEventAudit.REPAIRED.size() + " repaired row(s); unproved: " + unproved.size());
		// The denominators: a reading of the multiplexer that found nothing would leave every BRIDGED row "no install".
		assertTrue(installs.size() >= 90, "only " + installs.size() + " bridges read from GameEventMultiplexer — verify the reading");
		assertTrue(!recorders.isEmpty(), "no kernel class records a bridge — verify the reading");
		assertEquals(List.of(), unproved, "rows that silence the audit for an event their delivery does not post");
	}

	/**
	 * {@link DeadEventAudit#NO_NATIVE_PATH} is exactly the {@link DeadEventAudit#BRIDGED} and {@link DeadEventAudit#STAND_DOWN}
	 * events the merged game, as the kernel loads it, never constructs itself — in its own methods, or in a family method it or
	 * the family's runtime reaches. It decides what a mod is told when the event's bridge is missing: "never posts" for these,
	 * and for every other event the merged game's own paths. Until 2026-10-25 every missing bridge said "never posts", which
	 * named nearly every MinecraftForge mod for a tick the merged game still posts whenever the bridges were off.
	 */
	@Test
	void theNoNativePathTableIsWhereTheMergedGamePostsNothingItself() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		Map<String, HookCallSiteCensus.Census> all = censusAll();
		TestFixtures.require(Fixture.STAGED, !all.isEmpty(), "staged carriers absent");
		Set<String> gameConstructs = all.values().iterator().next().gameConstructs();
		Set<String> rows = new TreeSet<>(DeadEventAudit.BRIDGED.keySet());
		rows.addAll(DeadEventAudit.STAND_DOWN.keySet());
		Set<String> none = new TreeSet<>(), stand = new TreeSet<>(), bridged = new TreeSet<>();
		for (String event : rows) {
			if (gameConstructs.contains(event)) continue;
			none.add(event);
			(DeadEventAudit.STAND_DOWN.containsKey(event) ? stand : bridged).add(event);
		}
		System.out.println("[Forbric/Hooks] bridged events the merged game never posts itself: " + none.size() + " of " + rows.size()
				+ " (BRIDGED " + bridged.size() + " of " + DeadEventAudit.BRIDGED.size() + ", STAND_DOWN " + stand.size() + " of "
				+ DeadEventAudit.STAND_DOWN.size() + ")");
		for (String event : none) System.out.println("[Forbric/Hooks]   no native path: " + event);
		// The denominators: a reading of the game that constructed nothing would put every row here, one that read the kernel as
		// the game none.
		assertTrue(none.size() < rows.size(), "the merged game posts none of the bridged events itself — verify the reading");
		assertTrue(!none.isEmpty(), "the merged game posts every bridged event itself — verify the reading");
		assertEquals(none, new TreeSet<>(DeadEventAudit.NO_NATIVE_PATH),
				"NO_NATIVE_PATH and what the merged game posts itself disagree; the table is the one that is wrong");
	}

	/** Whether {@link KernelRuntimeClasses} declares {@code install}'s method, so the boot-time seam check covers it. */
	private static boolean declaredToTheSeamCheck(BridgeDeliveryProof.Entry install) {
		String name = install.method().substring(0, install.method().indexOf('('));
		for (KernelRuntimeClasses.Call call : KernelRuntimeClasses.callsOn(install.owner().replace('/', '.'))) {
			if (call.name().equals(name)) return true;
		}
		return false;
	}

	/**
	 * The two events the census used to miss, pinned on the real base. MinecraftForge's breathe event is posted only by
	 * {@code ForgeEventFactory.onLivingBreathe}, whose one caller is {@code ForgeHooks.onLivingBreathe}, which nothing
	 * calls: counting any carrier caller called it posted. Its drown event is constructed by that same dead hook and by a
	 * factory method nothing calls. Both must be measured dead, and both are delivered by the kernel's bridges.
	 */
	@Test
	void minecraftForgesBreatheAndDrownEventsAreMeasuredDeadAndBridged() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(kernelRuntime()), "the kernel's game side is not compiled");
		Map<String, HookCallSiteCensus.Census> all = censusAll();
		TestFixtures.require(Fixture.STAGED, !all.isEmpty(), "staged carriers absent");
		Set<String> dead = new TreeSet<>(), kernel = new TreeSet<>();
		measuredDead(all, dead, kernel);
		for (String event : List.of("net/minecraftforge/event/entity/living/LivingBreatheEvent",
				"net/minecraftforge/event/entity/living/LivingDrownEvent")) {
			assertTrue(dead.contains(event), event + " is not measured dead");
			assertTrue(kernel.contains(event), event + " is not delivered by the kernel");
			assertNotNull(DeadEventAudit.BRIDGED.get(event), event + " has no BRIDGED row");
		}
	}

	@Test
	void theCensusRunsAndTheSurfaceIsNotAllAlive() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		Map<String, HookCallSiteCensus.Census> all = censusAll();
		TestFixtures.require(Fixture.STAGED, !all.isEmpty(), "staged carriers absent");
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
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		Map<String, HookCallSiteCensus.Census> all = censusAll();
		TestFixtures.require(Fixture.STAGED, !all.isEmpty(), "staged carriers absent");

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
		return TestFixtures.forgeMergeInput();
	}

	/**
	 * {@link DeadEventAudit#PARTIAL} is exactly the events posted on some of their paths and not the others.
	 *
	 * <p>An event is partly posted when a hook that posts it — any hook class of either family, a hook counting for the
	 * events of every hook it calls and of every hook the constructor of an event it constructs calls — has fewer call sites in the merged game, read as its repairs leave it, than in its
	 * own family's patched game, and the event is still posted somewhere; or when the census measures it never posted by
	 * the game but the kernel or a guest's call into a family's API posts it, and no bridge or repair row claims the whole
	 * delivery. Until 2026-10-25 only the two event factories were measured, and only their partly kept hooks: five events
	 * that lost game paths were named in no table (container open, harvest check, crop growth before and after, fluid
	 * placement), and the counts left out the paths that reach a poster through another hook.
	 */
	@Test
	void thePartialTableIsExactlyWhatTheCallSitesSay() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(forgePatchedGame()) && Files.isRegularFile(neoForgePatchedGame()),
				"a family's own patched game is absent");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(kernelRuntime()), "the kernel's game side is not compiled");
		Map<String, HookCallSiteCensus.Census> all = censusAll();
		TestFixtures.require(Fixture.STAGED, !all.isEmpty(), "staged carriers absent");
		Measured m = measure(all);
		Set<String> nativelyDead = nativelyNeverPosted();
		// event -> {call sites the merged game kept, call sites its family's game has}, over every hook posting it.
		Map<String, int[]> paths = new TreeMap<>();
		Map<String, List<String>> lostBy = new TreeMap<>();
		int eroded = 0;
		Map<String, List<HookCallSiteCensus.Erosion>> erosion = erosion();
		for (Map.Entry<String, List<HookCallSiteCensus.Erosion>> hooks : erosion.entrySet()) {
			for (HookCallSiteCensus.Erosion e : hooks.getValue()) {
				Set<String> events = m.postsThrough().getOrDefault(hooks.getKey() + "." + e.hook(), Set.of());
				for (String event : events) {
					int[] kept = paths.computeIfAbsent(event, k -> new int[2]);
					kept[0] += e.after();
					kept[1] += e.before();
				}
				if (e.after() >= e.before()) continue;
				eroded++;
				for (String event : events) {
					lostBy.computeIfAbsent(event, k -> new ArrayList<>()).add(hooks.getKey().substring(hooks.getKey().lastIndexOf('/') + 1)
							+ "." + e.hook().substring(0, e.hook().indexOf('(')) + " " + e.after() + "/" + e.before());
				}
			}
		}
		Set<String> expected = new TreeSet<>();
		for (String event : lostBy.keySet()) if (!m.dead().contains(event)) expected.add(event);
		for (String event : m.dead()) {
			if (nativelyDead.contains(event)) continue;
			boolean fullDelivery = DeadEventAudit.BRIDGED.containsKey(event) || DeadEventAudit.REPAIRED.contains(event)
					|| DeadEventAudit.STAND_DOWN.containsKey(event);
			if (!fullDelivery && (m.kernelDelivered().contains(event) || m.guestPosted().contains(event))) expected.add(event);
		}
		System.out.println("[Forbric/Hooks] hooks with fewer game call sites than their family's game: " + eroded
				+ ", events posted on some of their paths: " + expected.size());
		for (String event : expected) {
			int[] kept = paths.getOrDefault(event, new int[2]);
			System.out.println("[Forbric/Hooks] partly posted: " + event + " on " + kept[0] + " of its " + kept[1] + " paths"
					+ (m.dead().contains(event) ? (m.kernelDelivered().contains(event) ? " (the kernel posts it)" : " (a guest's API call posts it)") : "")
					+ " " + lostBy.getOrDefault(event, List.of()));
		}
		// The denominator: a run that found no erosion would make the equality below vacuously true against an
		// empty table, which is how a generator stops generating without anyone noticing.
		assertTrue(eroded > 0, "no hook lost part of its call sites — verify the scan before believing it");
		assertEquals(expected, new TreeSet<>(DeadEventAudit.PARTIAL.keySet()),
				"the partially-posted table and the call-site counts disagree; the table is the one that is wrong");
		// And the numbers a row gives are the census's, not a count someone made by hand.
		int stated = 0;
		java.util.regex.Pattern ratio = java.util.regex.Pattern.compile("(\\d+) of its (\\d+) paths");
		for (Map.Entry<String, String> row : DeadEventAudit.PARTIAL.entrySet()) {
			java.util.regex.Matcher matcher = ratio.matcher(row.getValue());
			if (!matcher.find()) continue;
			stated++;
			int[] kept = paths.getOrDefault(row.getKey(), new int[2]);
			assertEquals(kept[0] + " of its " + kept[1] + " paths", matcher.group(0), row.getKey()
					+ ": the row's count is not the call sites' — regenerate it from the line this test prints");
		}
		assertTrue(stated > 0, "no partly-posted row states how many paths it has — the count check checked nothing");
	}

	/**
	 * Each {@link DeadEventAudit#PARTIAL} row a bridge or a repair also carries is silent while its bridge is installed
	 * ({@link DeadEventAudit#audit} consults the bridge first), on the claim that the bridge or repair delivers the paths the
	 * merge lost. This holds every one of them to that claim, game method by game method: at each method of the family's own
	 * patched game that posted the event from more places than the merged game (as loaded) still does, the merged method
	 * hands the bridge what it forwards — it calls a NeoForge hook posting a NeoForge event the bridge listens to, or
	 * constructs one inline — or calls into the kernel, whose delivery posts the row's event or one the bridge listens to.
	 * A lost path nothing covers would be a listener silently missing it with nothing said; until 2026-10-25 the claim was
	 * written in the introduction and checked nowhere.
	 */
	@Test
	void aBridgedPartialRowIsSilentOnlyWhereItsBridgeCoversEveryLostPath() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(forgePatchedGame()) && Files.isRegularFile(neoForgePatchedGame()),
				"a family's own patched game is absent");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(kernelRuntime()) && Files.isDirectory(kernelMain()), "the kernel is not compiled");
		Map<String, HookCallSiteCensus.Census> all = censusAll();
		TestFixtures.require(Fixture.STAGED, !all.isEmpty(), "staged carriers absent");
		Measured m = measure(all);
		BridgeDeliveryProof proof = new BridgeDeliveryProof(List.of(kernelMain(), kernelRuntime()), carriers());
		Map<GameEventBridge, BridgeDeliveryProof.Entry> installs = proof.multiplexerInstalls();
		Map<GameEventBridge, Set<String>> recorders = proof.recorders();
		Set<String> hooks = stagedHooks(hook -> true).keySet();
		// caller method -> hook -> call count, in each family's game and in the merged game as loaded
		Map<String, Map<String, Integer>> forge = callersOf(forgePatchedGame(), hooks, null), neo = callersOf(neoForgePatchedGame(), hooks, null);
		Map<String, Map<String, Integer>> merged;
		Map<String, Set<String>> mergedKernelCalls = new HashMap<>(), mergedNews = new HashMap<>();
		try (var loadTime = new net.forbric.kernel.transform.MergedBaseLoadTimeView(served())) {
			merged = callersOf(base(), hooks, loadTime);
			CarrierCallGraph.forEachClass(base(), loadTime, node -> {
				if (hooks.contains(node.name)) return;
				for (MethodNode method : node.methods) {
					if (method.instructions == null) continue;
					String caller = node.name + "#" + method.name + method.desc;
					for (AbstractInsnNode insn : method.instructions) {
						if (insn instanceof MethodInsnNode call && call.owner.startsWith("net/forbric/kernel/")) {
							mergedKernelCalls.computeIfAbsent(caller, k -> new TreeSet<>()).add(call.owner + "." + call.name + call.desc);
						}
						if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW) mergedNews.computeIfAbsent(caller, k -> new TreeSet<>()).add(t.desc);
					}
				}
			});
		}
		Set<String> rows = new TreeSet<>(DeadEventAudit.PARTIAL.keySet());
		rows.removeIf(e -> DeadEventAudit.bridgeOf(e) == null && !DeadEventAudit.REPAIRED.contains(e));
		Set<String> uncovered = new TreeSet<>();
		Map<String, Set<String>> kernelPosts = new HashMap<>();
		int lostSites = 0;
		for (String event : rows) {
			Map<String, Map<String, Integer>> family = event.startsWith("net/minecraftforge/") ? forge : neo;
			Set<String> posting = new TreeSet<>();
			m.postsThrough().forEach((hook, events) -> {
				if (events.contains(event) && hook.startsWith(event.startsWith("net/minecraftforge/") ? "net/minecraftforge/" : "net/neoforged/")) posting.add(hook);
			});
			GameEventBridge bridge = DeadEventAudit.bridgeOf(event);
			Set<String> entries = new TreeSet<>();
			if (bridge != null) {
				BridgeDeliveryProof.Entry install = installs.get(bridge);
				if (install != null) entries.add(install.owner() + "." + install.method());
				for (String recorder : recorders.getOrDefault(bridge, Set.of())) entries.addAll(proof.entriesOf(recorder));
			}
			Set<String> sources = proof.listensTo(entries);
			List<String> lines = new ArrayList<>();
			for (Map.Entry<String, Map<String, Integer>> caller : new TreeMap<>(family).entrySet()) {
				int before = 0, after = 0;
				for (String hook : posting) {
					before += caller.getValue().getOrDefault(hook, 0);
					after += merged.getOrDefault(caller.getKey(), Map.of()).getOrDefault(hook, 0);
				}
				if (before == 0 || after >= before) continue;
				lostSites++;
				String why = null;
				for (Map.Entry<String, Integer> call : merged.getOrDefault(caller.getKey(), Map.of()).entrySet()) {
					Set<String> posted = m.postsThrough().getOrDefault(call.getKey(), Set.of());
					for (String source : sources) if (posted.contains(source)) why = "posts " + simple(source) + " (" + simple(call.getKey()) + ")";
				}
				for (String made : mergedNews.getOrDefault(caller.getKey(), Set.of())) if (sources.contains(made)) why = "constructs " + simple(made);
				for (String kernelCall : mergedKernelCalls.getOrDefault(caller.getKey(), Set.of())) {
					Set<String> posts = kernelPosts.computeIfAbsent(kernelCall, k -> proof.posts(Set.of(k)));
					if (posts.contains(event) || sources.stream().anyMatch(posts::contains)) why = "the kernel posts it (" + simple(kernelCall) + ")";
				}
				lines.add(caller.getKey() + " " + after + "/" + before + " -> " + (why == null ? "NOT COVERED" : why));
				if (why == null) uncovered.add(event);
			}
			System.out.println("[Forbric/Hooks] lost paths of " + event + " (" + (bridge != null ? bridge : "REPAIRED") + ", listens to "
					+ sources.stream().map(HookCallSiteCensusStagedTest::simple).toList() + "):");
			for (String line : lines) System.out.println("[Forbric/Hooks]   " + line);
		}
		System.out.println("[Forbric/Hooks] bridged or repaired partial rows: " + rows.size() + ", lost paths: " + lostSites
				+ ", rows with a lost path nothing covers: " + uncovered);
		assertTrue(lostSites > 0, "no lost path found for a bridged partial row — verify the reading");
		assertEquals(Set.of(), uncovered, "bridged partial rows with a lost path their bridge or repair does not cover: the audit "
				+ "is silent about them while the bridge is in, so a listener misses that path unannounced — cover the path, or "
				+ "make the audit name the row while its bridge is installed");
	}

	/** A type's simple name, or a hook id's class and method name. */
	private static String simple(String name) {
		int paren = name.indexOf('(');
		String head = paren < 0 ? name : name.substring(0, paren);
		return head.substring(head.lastIndexOf('/') + 1);
	}

	/** Caller method ({@code owner#name+desc}) → hook id → how many times it calls the hook, in {@code jar}, hook classes left out. */
	private static Map<String, Map<String, Integer>> callersOf(Path jar, Set<String> hooks, java.util.function.BiFunction<String, byte[], byte[]> loadTime)
			throws Exception {
		Map<String, Map<String, Integer>> out = new HashMap<>();
		CarrierCallGraph.forEachClass(jar, loadTime, node -> {
			if (hooks.contains(node.name)) return;
			for (MethodNode method : node.methods) {
				if (method.instructions == null) continue;
				String caller = node.name + "#" + method.name + method.desc;
				for (AbstractInsnNode insn : method.instructions) {
					HookCallSiteCensus.callees(insn, callee -> {
						if (hooks.contains(callee.substring(0, callee.indexOf('.')))) {
							out.computeIfAbsent(caller, k -> new HashMap<>()).merge(callee, 1, Integer::sum);
						}
					});
				}
			}
		});
		return out;
	}

	/** Every hook class's erosion, each family against its own patched game, the merged game read as the kernel loads it. */
	private static volatile Map<String, List<HookCallSiteCensus.Erosion>> cachedErosion;

	private static Map<String, List<HookCallSiteCensus.Erosion>> erosion() throws Exception {
		Map<String, List<HookCallSiteCensus.Erosion>> cached = cachedErosion;
		if (cached != null) return cached;
		cached = new TreeMap<>();
		try (var loadTime = new net.forbric.kernel.transform.MergedBaseLoadTimeView(served())) {
			cached.putAll(HookCallSiteCensus.erosion(stagedHooks(HookCallSiteCensusStagedTest::forgeFamily).keySet(),
					forgePatchedGame(), base(), loadTime));
			cached.putAll(HookCallSiteCensus.erosion(stagedHooks(hook -> !forgeFamily(hook)).keySet(),
					neoForgePatchedGame(), base(), loadTime));
		}
		cachedErosion = cached;
		return cached;
	}

	/**
	 * The review tool LostSilent, kept as a test, with code of its own (no census, no erosion helper): every hook — of all
	 * seven hook classes — that the merged game, read as its repairs leave it, calls from fewer places than its family's
	 * own patched game, and every event it posts (constructing it, or through a hook it calls). Each such event is named:
	 * {@link DeadEventAudit#DEAD} or {@link DeadEventAudit#PARTIAL}, or, when every hook posting it lost every game call
	 * site, a bridge or repair row that took the whole delivery over. Five events once lost game paths with no row
	 * anywhere, because only the two event factories' hooks were counted.
	 */
	@Test
	void everyHookThatLostGameCallSitesHasItsEventsNamed() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(forgePatchedGame()) && Files.isRegularFile(neoForgePatchedGame()),
				"a family's own patched game is absent");
		Map<String, Path> hooks = stagedHooks(hook -> true);
		TestFixtures.require(Fixture.STAGED, hooks.size() == HOOK_CLASSES.size(), "staged carriers absent");
		// hook -> events it constructs (its family's types named ...Event), and hook -> the hooks it calls.
		Map<String, Set<String>> constructs = new HashMap<>(), calls = new HashMap<>();
		for (Map.Entry<String, Path> e : hooks.entrySet()) {
			String family = e.getKey().startsWith("net/minecraftforge/") ? "net/minecraftforge/" : "net/neoforged/";
			try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(e.getValue().toFile())) {
				ClassNode node = new ClassNode();
				new ClassReader(zip.getInputStream(zip.getEntry(e.getKey() + ".class")).readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
				for (MethodNode method : node.methods) {
					if ((method.access & Opcodes.ACC_STATIC) == 0 || (method.access & Opcodes.ACC_PUBLIC) == 0 || method.instructions == null) continue;
					String hook = e.getKey() + "." + method.name + method.desc;
					for (AbstractInsnNode insn : method.instructions) {
						if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.startsWith(family) && t.desc.contains("Event")) {
							constructs.computeIfAbsent(hook, k -> new TreeSet<>()).add(t.desc);
						}
						if (insn instanceof MethodInsnNode call && hooks.containsKey(call.owner) && !(call.owner + "." + call.name + call.desc).equals(hook)) {
							calls.computeIfAbsent(hook, k -> new HashSet<>()).add(call.owner + "." + call.name + call.desc);
						}
					}
				}
			}
		}
		// And the hooks an event's constructor calls, read from the carriers: constructing the event posts theirs too
		// (MinecraftForge's BreakEvent asks for a harvest check from its constructor).
		Map<String, Set<String>> constructorCalls = new HashMap<>();
		for (Path jar : new java.util.LinkedHashSet<>(hooks.values())) {
			try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
				Set<String> constructed = new TreeSet<>();
				constructs.values().forEach(constructed::addAll);
				for (String event : constructed) {
					for (String type = event; type != null; ) {
						var entry = zip.getEntry(type + ".class");
						if (entry == null) break;
						ClassNode node = new ClassNode();
						new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
						for (MethodNode method : node.methods) {
							if (!method.name.equals("<init>") || method.instructions == null) continue;
							for (AbstractInsnNode insn : method.instructions) {
								if (insn instanceof MethodInsnNode call && hooks.containsKey(call.owner)) {
									constructorCalls.computeIfAbsent(event, k -> new HashSet<>()).add(call.owner + "." + call.name + call.desc);
								}
							}
						}
						type = node.superName;
					}
				}
			}
		}
		Map<String, Integer> before = new HashMap<>(), after = new HashMap<>();
		lostSilentSites(forgePatchedGame(), hooks.keySet(), null, before, "net/minecraftforge/");
		lostSilentSites(neoForgePatchedGame(), hooks.keySet(), null, before, "net/neoforged/");
		try (var loadTime = new net.forbric.kernel.transform.MergedBaseLoadTimeView(served())) {
			lostSilentSites(base(), hooks.keySet(), loadTime, after, "");
		}
		// event -> the hooks posting it that lost call sites, and whether every hook posting it lost all of them.
		Map<String, List<String>> lost = new TreeMap<>();
		Map<String, Boolean> allGone = new HashMap<>();
		for (Map.Entry<String, Integer> site : before.entrySet()) {
			Set<String> events = new TreeSet<>(), seen = new HashSet<>();
			ArrayDeque<String> work = new ArrayDeque<>(List.of(site.getKey()));
			while (!work.isEmpty()) {
				String at = work.removeFirst();
				if (!seen.add(at)) continue;
				for (String event : constructs.getOrDefault(at, Set.of())) {
					events.add(event);
					work.addAll(constructorCalls.getOrDefault(event, Set.of()));
				}
				work.addAll(calls.getOrDefault(at, Set.of()));
			}
			int kept = after.getOrDefault(site.getKey(), 0);
			for (String event : events) {
				allGone.merge(event, kept == 0, Boolean::logicalAnd);
				if (kept < site.getValue()) lost.computeIfAbsent(event, k -> new ArrayList<>()).add(site.getKey() + " " + kept + "/" + site.getValue());
			}
		}
		List<String> unnamed = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : lost.entrySet()) {
			String event = e.getKey();
			String table = DeadEventAudit.DEAD.containsKey(event) ? "DEAD" : DeadEventAudit.PARTIAL.containsKey(event) ? "PARTIAL"
					: DeadEventAudit.BRIDGED.containsKey(event) ? "BRIDGED" : DeadEventAudit.REPAIRED.contains(event) ? "REPAIRED"
					: DeadEventAudit.STAND_DOWN.containsKey(event) ? "STAND_DOWN" : "-NONE-";
			System.out.println("[Forbric/Hooks] lost game paths: " + table + " " + event + " " + e.getValue());
			boolean named = table.equals("DEAD") || table.equals("PARTIAL") || (!table.equals("-NONE-") && allGone.get(event));
			if (!named) unnamed.add(event + " (" + table + ") " + e.getValue());
		}
		assertTrue(lost.size() > 0, "no hook lost a call site — verify the scan before believing it");
		assertEquals(List.of(), unnamed, "events that lost game paths, named neither DEAD nor PARTIAL, and not wholly taken over");
	}

	/** Direct call sites of the hooks of {@code family} (every family when empty) in {@code jar}, hook classes left out. */
	private static void lostSilentSites(Path jar, Set<String> hooks, java.util.function.BiFunction<String, byte[], byte[]> loadTime,
			Map<String, Integer> into, String family) throws Exception {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
			for (var entries = zip.entries(); entries.hasMoreElements(); ) {
				var entry = entries.nextElement();
				if (!entry.getName().endsWith(".class")) continue;
				byte[] bytes;
				try (var in = zip.getInputStream(entry)) {
					bytes = in.readAllBytes();
				}
				String name = entry.getName().substring(0, entry.getName().length() - ".class".length());
				if (hooks.contains(name)) continue;
				if (loadTime != null) {
					byte[] loaded = loadTime.apply(name, bytes);
					if (loaded != null) bytes = loaded;
				}
				ClassNode node = new ClassNode();
				new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
				for (MethodNode m : node.methods) {
					if (m.instructions == null) continue;
					for (AbstractInsnNode insn : m.instructions) {
						HookCallSiteCensus.callees(insn, callee -> {
							String owner = callee.substring(0, callee.indexOf('.'));
							if (hooks.contains(owner) && owner.startsWith(family)) into.merge(callee, 1, Integer::sum);
						});
					}
				}
			}
		}
	}

	/**
	 * The review tool OutrightWhy, kept as a test: the events the census once kept live only because some carrier method
	 * that is not a hook called a hook posting them, whether or not anything reached that method (computed here with code of
	 * its own, under that old rule). Each is now either measured never posted — and then named by a row, see
	 * {@link #everyEventTheCensusMeasuresDeadHasARow} — or reached for a reason the census can state: a chain of carrier
	 * methods, each named in the body of the next, ending at the game (which names the last one), a listener (which carries
	 * {@code @SubscribeEvent}), a class the runtime constructs (a family's {@code @Mod} class or a service provider), or a
	 * method code outside the scan may call.
	 */
	@Test
	void everyEventLiveOnlyThroughACarrierCallerIsReachedForAReason() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		Map<String, HookCallSiteCensus.Census> all = censusAll();
		TestFixtures.require(Fixture.STAGED, !all.isEmpty(), "staged carriers absent");
		Map<String, Path> hooks = stagedHooks(hook -> true);
		// The old rule, independently: gates and their posters, the game's calls, carrier edges between gates, and every
		// gate a non-gate carrier method calls counted reached outright.
		Set<String> gates = new HashSet<>();
		Map<String, Set<String>> posters = new TreeMap<>();
		Map<String, Set<String>> edges = new HashMap<>(), outright = new HashMap<>();
		Map<String, ClassNode> carrierNodes = new HashMap<>();
		Set<String> runtimeConstructed = new HashSet<>();
		for (Path jar : carriers()) {
			try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
				for (var entries = zip.entries(); entries.hasMoreElements(); ) {
					var entry = entries.nextElement();
					if (entry.getName().startsWith("META-INF/services/") && !entry.isDirectory()) {
						for (String line : new String(zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
							if (!line.isBlank() && !line.trim().startsWith("#")) runtimeConstructed.add(line.trim().replace('.', '/'));
						}
					}
					if (!entry.getName().endsWith(".class")) continue;
					ClassNode node = new ClassNode();
					new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
					carrierNodes.put(node.name, node);
				}
			}
		}
		for (ClassNode node : carrierNodes.values()) {
			if (annotated(node.visibleAnnotations, "/fml/common/Mod;")) runtimeConstructed.add(node.name);
			if (!hooks.containsKey(node.name)) continue;
			String family = node.name.startsWith("net/minecraftforge/") ? "net/minecraftforge/" : "net/neoforged/";
			for (MethodNode m : node.methods) {
				if (!HookCallSiteCensus.isHook(m)) continue;
				String gate = node.name + "." + m.name + m.desc;
				gates.add(gate);
				for (AbstractInsnNode insn : m.instructions) {
					if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.startsWith(family) && t.desc.contains("Event")) {
						posters.computeIfAbsent(t.desc, k -> new TreeSet<>()).add(gate);
					}
				}
			}
		}
		for (ClassNode node : carrierNodes.values()) {
			for (MethodNode m : node.methods) {
				if (m.instructions == null) continue;
				String caller = node.name + "." + m.name + m.desc;
				for (AbstractInsnNode insn : m.instructions) {
					HookCallSiteCensus.callees(insn, callee -> {
						if (callee.equals(caller) || !gates.contains(callee)) return;
						(gates.contains(caller) ? edges : outright).computeIfAbsent(gates.contains(caller) ? caller : callee,
								k -> new TreeSet<>()).add(gates.contains(caller) ? callee : caller);
					});
				}
			}
		}
		// What the game calls (owner.name+desc), the name+desc of each call, and every class its code names.
		Set<String> gameCalls = new HashSet<>(), gameNameDescs = new HashSet<>(), gameNames = new HashSet<>();
		try (var loadTime = new net.forbric.kernel.transform.MergedBaseLoadTimeView(served())) {
			CarrierCallGraph.forEachClass(base(), loadTime, node -> {
				if (hooks.containsKey(node.name)) return;
				for (MethodNode m : node.methods) {
					if (m.instructions == null) continue;
					for (AbstractInsnNode insn : m.instructions) {
						HookCallSiteCensus.callees(insn, callee -> {
							gameCalls.add(callee);
							gameNameDescs.add(callee.substring(callee.indexOf('.') + 1));
							gameNames.add(callee.substring(0, callee.indexOf('.')));
						});
						if (insn instanceof org.objectweb.asm.tree.FieldInsnNode f) gameNames.add(f.owner);
						if (insn instanceof TypeInsnNode t) gameNames.add(t.desc);
					}
				}
			});
		}
		Set<String> byGame = oldReach(gates, gameCalls, edges, Set.of()), withOutright = oldReach(gates, gameCalls, edges, outright.keySet());
		Set<String> outrightOnly = new TreeSet<>();
		for (Map.Entry<String, Set<String>> e : posters.entrySet()) {
			boolean game = e.getValue().stream().anyMatch(byGame::contains), carrier = e.getValue().stream().anyMatch(withOutright::contains);
			if (!game && carrier) outrightOnly.add(e.getKey());
		}
		Measured m = measure(all);
		Map<String, List<String>> witness = new HashMap<>();
		for (HookCallSiteCensus.Census c : all.values()) c.witness().forEach((hook, chain) -> witness.put(c.hookClass() + "." + hook, chain));
		List<String> unexplained = new ArrayList<>();
		for (String event : outrightOnly) {
			if (m.dead().contains(event)) {
				System.out.println("[Forbric/Hooks] once live only through a carrier caller, now measured never posted: " + event);
				continue;
			}
			String reason = null;
			for (String poster : posters.get(event)) {
				List<String> chain = witness.get(poster);
				if (chain == null || !m.live().contains(event)) continue;
				String why = validChain(chain, carrierNodes, runtimeConstructed, gameNames, gameNameDescs);
				if (why == null) {
					reason = String.join(" <- ", chain);
					break;
				}
				reason = "INVALID " + why + ": " + String.join(" <- ", chain);
			}
			System.out.println("[Forbric/Hooks] once live only through a carrier caller, reached: " + event + " — " + reason);
			if (reason == null || reason.startsWith("INVALID")) unexplained.add(event + " " + reason);
		}
		assertTrue(!outrightOnly.isEmpty(), "the old rule kept nothing live through an outright carrier caller — verify the reading");
		assertEquals(List.of(), unexplained, "events the census keeps live without a reason it can state");
	}

	/** The old reach: gates called by the game, or by a reached gate, or by any non-gate carrier caller when {@code outright}. */
	private static Set<String> oldReach(Set<String> gates, Set<String> roots, Map<String, Set<String>> edges, Set<String> outright) {
		Set<String> reached = new HashSet<>();
		ArrayDeque<String> work = new ArrayDeque<>();
		for (String gate : gates) if (roots.contains(gate) || outright.contains(gate)) work.add(gate);
		while (!work.isEmpty()) {
			String gate = work.removeFirst();
			if (!reached.add(gate)) continue;
			for (String next : edges.getOrDefault(gate, Set.of())) if (gates.contains(next)) work.add(next);
		}
		return reached;
	}

	/** Null when {@code chain} (reached method first, root last) holds up against the bytecode; else what does not. */
	private static String validChain(List<String> chain, Map<String, ClassNode> carriers, Set<String> runtimeConstructed,
			Set<String> gameNames, Set<String> gameNameDescs) {
		String root = chain.get(chain.size() - 1);
		for (int i = 0; i + 2 < chain.size(); i++) {
			String callee = chain.get(i), caller = chain.get(i + 1);
			MethodNode body = method(carriers, caller);
			String calleeOwner = callee.substring(0, callee.indexOf('.'));
			// A call names the callee's class, or (inherited, as a static call through a subclass does) its name and type.
			if (body == null || !names(body, calleeOwner, callee.substring(callee.indexOf('.') + 1))) {
				return caller + " names neither " + calleeOwner + " nor " + callee.substring(callee.indexOf('.') + 1);
			}
		}
		String first = chain.get(chain.size() - 2), owner = first.substring(0, first.indexOf('.'));
		MethodNode entry = method(carriers, first);
		return switch (root) {
			// The game calls it (by its name and type, on its class or one that inherits it), or — for a static initializer —
			// touches its class.
			case CarrierCallGraph.GAME -> first.endsWith(".<clinit>()V") ? (gameNames.contains(owner) ? null : "the game never touches " + owner)
					: gameNameDescs.contains(first.substring(first.indexOf('.') + 1)) ? null
					: "the game calls no " + first.substring(first.indexOf('.') + 1);
			case CarrierCallGraph.LISTENER -> entry != null && (annotated(entry.visibleAnnotations, "/SubscribeEvent;")
					|| annotated(entry.invisibleAnnotations, "/SubscribeEvent;")) ? null : first + " is no listener";
			case CarrierCallGraph.RUNTIME -> runtimeConstructed.contains(owner) ? null : owner + " is neither a mod class nor a service";
			case CarrierCallGraph.OUTSIDE -> null;
			default -> "the root " + root + " is not one the game or the runtime enters";
		};
	}

	private static MethodNode method(Map<String, ClassNode> carriers, String id) {
		ClassNode node = carriers.get(id.substring(0, id.indexOf('.')));
		if (node == null) return null;
		for (MethodNode m : node.methods) if ((m.name + m.desc).equals(id.substring(id.indexOf('.') + 1))) return m;
		return null;
	}

	/**
	 * Whether {@code body} names {@code type} in any operand (a call, a handle, a field, a construction), or calls a method
	 * of {@code nameDesc} on any class.
	 */
	private static boolean names(MethodNode body, String type, String nameDesc) {
		if (body.instructions == null) return false;
		for (AbstractInsnNode insn : body.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(type)) return true;
			if (insn instanceof org.objectweb.asm.tree.FieldInsnNode f && f.owner.equals(type)) return true;
			if (insn instanceof TypeInsnNode t && t.desc.equals(type)) return true;
			boolean[] found = {false};
			HookCallSiteCensus.callees(insn, callee -> found[0] |= callee.startsWith(type + ".") || callee.endsWith("." + nameDesc));
			if (found[0]) return true;
		}
		return false;
	}

	private static boolean annotated(List<org.objectweb.asm.tree.AnnotationNode> annotations, String suffix) {
		if (annotations == null) return false;
		for (org.objectweb.asm.tree.AnnotationNode a : annotations) if (a.desc.endsWith(suffix)) return true;
		return false;
	}

	@Test
	void anEventIsNotBothNeverPostedAndPartlyPosted() throws Exception {
		// The two tables answer different questions and a row in both would make the audit's precedence decide
		// which sentence a player sees, which is not a decision precedence should be making.
		Set<String> both = new TreeSet<>(DeadEventAudit.DEAD.keySet());
		both.retainAll(DeadEventAudit.PARTIAL.keySet());
		assertEquals(Set.of(), both, "listed as never posted AND as partly posted: " + both);
	}

	/**
	 * A method that posts through both families can hand one subscriber a bridged event twice: the native
	 * MinecraftForge call, and the bridge forwarding the NeoForge one. Every such pair that can run in one invocation
	 * must be stood down by the kernel, which this checks on what the kernel actually writes: the NeoForge call is
	 * wrapped in a scope that names the MinecraftForge event — proved from the caller's bytecode, or recorded from a
	 * MinecraftForge call the kernel also wrapped — and both families' hooks report the events they construct.
	 *
	 * <p>The pairs are found independently of the kernel's proof, and as widely as the kernel delivers: a MinecraftForge
	 * hook whose carrier body constructs a forwarded event, against either a static call into any NeoForge class whose
	 * carrier body constructs the event of the same name (EventHooks, CommonHooks, ClientHooks, or any other) or an
	 * inline post on NeoForge's bus whose argument is that event constructed in the method; and a path from one call to
	 * the other. Pairs on branches that never meet cannot deliver twice.
	 */
	@Test
	void noSinglePathPostsABridgedEventTwice() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		List<String> both = candidateMethods();
		System.out.println("[Forbric/Hooks] methods calling a MinecraftForge hook and delivering through NeoForge: " + both.size());
		Set<String> forwarded = DeadEventAudit.forwardedForgeEvents();
		NativeDualEventInjector injector = new NativeDualEventInjector(HookCallSiteCensusStagedTest::protocolBytes, forwarded);
		List<String> unproved = new ArrayList<>();
		Map<String, Integer> kinds = new TreeMap<>();
		int risks = 0;
		for (String method : both) {
			String owner = method.substring(0, method.indexOf('#'));
			byte[] bytes = classBytes(owner);
			if (method.equals(PORTAL_OWNER + "#" + PORTAL_METHOD)) {
				// The portal's bridge carries a result and is composed by PortalSpawnInjector, not by this scope.
				risks++;
				kinds.merge("portal", 1, Integer::sum);
				if (!portalStandsDown(bytes)) unproved.add(method);
				continue;
			}
			for (Risk risk : risks(method, bytes, forwarded)) {
				risks++;
				kinds.merge(risk.kind(), 1, Integer::sum);
				if (standsDown(injector, method, bytes, risk) == null) unproved.add(method + " " + risk);
			}
		}
		System.out.println("[Forbric/Hooks] single-path pairs of a forwarded event: " + risks + " " + kinds);
		// The denominator, as measured on the staged base this branch builds: a scan that stopped matching a kind of
		// NeoForge delivery would make the assertion below vacuous for it, and the census only ever saw hook classes once.
		assertTrue(risks >= MEASURED_PAIRS, "only " + risks + " pairs of a forwarded event, fewer than the " + MEASURED_PAIRS
				+ " this census measured — verify the scan: " + kinds);
		for (String kind : List.of("hook", "static", "inline")) assertTrue(kinds.getOrDefault(kind, 0) > 0, "no pair delivered by NeoForge as " + kind);
		assertEquals(List.of(), unproved,
				"a method that posts through both families can deliver a bridged event twice to one subscriber, unless "
						+ "the kernel scopes the NeoForge delivery with the MinecraftForge one: " + unproved);
	}

	/**
	 * Pairs the widened census finds on the staged base this branch builds: 24 through NeoForge's hook classes, 19
	 * through its other static hooks (CommonHooks, and the seven server-lifecycle calls initServer and runServer pair with
	 * MinecraftForge's ServerLifecycleHooks), 15 inline posts on its bus and the portal. Hook classes alone found 17 on an
	 * earlier base, and the 21 other ones were never checked; MinecraftForge's facade classes alone found 35, and the
	 * lifecycle pairs, which delivered every server event twice, were never checked either. The 17 above the 42 measured
	 * before are the events of bridges that had no row until DeadEventAudit.STAND_DOWN — the level's load, save and unload, the
	 * client's log-in and log-out, fog, overlays and screen events, all looked for nowhere. The ticks pair too but the
	 * census cannot see them: its pairing is by name, and MinecraftForge nests its ticks in TickEvent where NeoForge does
	 * not; gate-m41's audit counts those on the buses.
	 */
	private static final int MEASURED_PAIRS = 59;

	/**
	 * Methods of the merged base that call a MinecraftForge hook and deliver through NeoForge in any of the ways the
	 * kernel stands down for: a static call into NeoForge's namespace, or an inline post on its bus. A MinecraftForge hook
	 * is any static call into its namespace outside its bus package, as NativeDualEventInjector reads one: its facade
	 * classes alone left out ServerLifecycleHooks, whose pairs delivered every server lifecycle event twice.
	 */
	private static List<String> candidateMethods() throws Exception {
		List<String> out = new ArrayList<>();
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(base().toFile())) {
			for (var entries = zip.entries(); entries.hasMoreElements(); ) {
				var entry = entries.nextElement();
				if (!entry.getName().endsWith(".class")) continue;
				ClassNode node = new ClassNode();
				try (var input = zip.getInputStream(entry)) {
					new ClassReader(input.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
				}
				for (MethodNode m : node.methods) {
					if (m.instructions == null) continue;
					boolean forge = false, neo = false;
					for (AbstractInsnNode insn : m.instructions) {
						if (!(insn instanceof MethodInsnNode call)) continue;
						if (call.getOpcode() == Opcodes.INVOKESTATIC && forgeHook(call.owner)) forge = true;
						if (call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.startsWith("net/neoforged/") || inlineNeoPost(call)) neo = true;
					}
					if (forge && neo) out.add(node.name + "#" + m.name + m.desc);
				}
			}
		}
		return out;
	}

	/** A static call's owner MinecraftForge hooks live in: its namespace, outside the package of its buses. */
	private static boolean forgeHook(String owner) {
		return owner.startsWith("net/minecraftforge/") && !owner.startsWith("net/minecraftforge/eventbus/");
	}

	private static boolean inlineNeoPost(MethodInsnNode call) {
		return (call.getOpcode() == Opcodes.INVOKEINTERFACE || call.getOpcode() == Opcodes.INVOKEVIRTUAL)
				&& call.owner.equals("net/neoforged/bus/api/IEventBus") && call.name.equals("post");
	}

	@Test
	void aDualFamilyPortalCallerWithoutTheProvedShapeIsStillReported() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		ClassNode node = new ClassNode();
		new ClassReader(classBytes(PORTAL_OWNER)).accept(node, 0);
		MethodNode host = node.methods.stream().filter(m -> (m.name + m.desc).equals(PORTAL_METHOD)).findFirst().orElseThrow();
		// An unguarded MinecraftForge call at entry: both families are called, but not in the reviewed shape.
		InsnList unguarded = new InsnList();
		unguarded.add(new VarInsnNode(Opcodes.ALOAD, 2));
		unguarded.add(new VarInsnNode(Opcodes.ALOAD, 3));
		unguarded.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false));
		unguarded.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraftforge/event/ForgeEventFactory", "onTrySpawnPortal",
				"(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Ljava/util/Optional;)Ljava/util/Optional;", false));
		unguarded.add(new InsnNode(Opcodes.POP));
		host.instructions.insert(unguarded);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		try {
			assertFalse(portalStandsDown(writer.toByteArray()));
		} finally {
			net.forbric.api.CompatibilityFindings.reset();
		}
	}

	/**
	 * The negative controls for the general stand-down, one per way NeoForge delivers and one for a recorded (not
	 * proved) stand-down: a stood-down pair of that kind whose MinecraftForge call is taken out of the family (the same
	 * instruction, to an owner outside MinecraftForge's namespace) is no longer stood down, so the check above can fail
	 * for each of them.
	 */
	@Test
	void aPairWithoutItsNativeDeliveryIsNotStoodDown() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		Set<String> forwarded = DeadEventAudit.forwardedForgeEvents();
		NativeDualEventInjector injector = new NativeDualEventInjector(HookCallSiteCensusStagedTest::protocolBytes, forwarded);
		Map<String, String[]> found = new LinkedHashMap<>();
		Map<String, Risk> pairs = new HashMap<>();
		for (String method : candidateMethods()) {
			if (method.startsWith(PORTAL_OWNER + "#")) continue;
			byte[] bytes = classBytes(method.substring(0, method.indexOf('#')));
			for (Risk risk : risks(method, bytes, forwarded)) {
				String verdict = standsDown(injector, method, bytes, risk);
				if (verdict == null) continue;
				for (String kind : List.of(risk.kind(), verdict)) {
					if (found.containsKey(kind)) continue;
					found.put(kind, new String[] { method });
					pairs.put(kind, risk);
				}
			}
		}
		for (String kind : List.of("hook", "static", "inline", "recorded")) {
			assertTrue(found.containsKey(kind), "no stood-down pair of kind " + kind + " to break: " + found.keySet());
			String method = found.get(kind)[0];
			Risk pair = pairs.get(kind);
			String owner = method.substring(0, method.indexOf('#'));
			ClassNode node = new ClassNode();
			new ClassReader(classBytes(owner)).accept(node, 0);
			for (MethodNode m : node.methods) {
				if (!(m.name + m.desc).equals(method.substring(method.indexOf('#') + 1))) continue;
				((MethodInsnNode) m.instructions.get(pair.forge())).owner = "test/elsewhere/Factory";
			}
			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			assertNull(standsDown(new NativeDualEventInjector(HookCallSiteCensusStagedTest::protocolBytes, forwarded), method,
					writer.toByteArray(), pair), kind + ": " + method + " " + pair + " with its MinecraftForge call gone");
		}
	}

	private static final String PORTAL_OWNER = "net/minecraft/world/level/block/BaseFireBlock";
	private static final String PORTAL_METHOD = "onPlace(Lnet/minecraft/world/level/block/state/BlockState;"
			+ "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V";

	/**
	 * Whether the kernel proves the portal caller from its bytecode and confines the legacy forward to one family:
	 * PortalSpawnInjector accepts the reviewed composition and scopes the NeoForge call, and leaves anything else
	 * unchanged.
	 */
	private static boolean portalStandsDown(byte[] bytes) {
		byte[] adapted = new net.forbric.kernel.transform.PortalSpawnInjector()
				.transform(PORTAL_OWNER.replace('/', '.'), bytes, null);
		if (adapted == bytes) return false;
		ClassNode node = new ClassNode();
		new ClassReader(adapted).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (!(m.name + m.desc).equals(PORTAL_METHOD)) continue;
			for (AbstractInsnNode instruction : m.instructions) {
				if (instruction instanceof MethodInsnNode call && call.owner.equals("net/forbric/kernel/runtime/KernelPortalSpawn")
						&& call.name.equals("onTrySpawnPortalNeoOnly")) return true;
			}
		}
		return false;
	}

	/**
	 * One MinecraftForge call and one NeoForge delivery of a method, by instruction index, the forwarded event, and how
	 * NeoForge delivers it: through a hook class ("hook"), another static method of its namespace ("static"), or an
	 * inline post on its bus ("inline").
	 */
	private record Risk(int forge, int neo, String event, String kind) {
		@Override public String toString() {
			return event.substring(event.lastIndexOf('/') + 1) + " @" + forge + "/" + neo + " (" + kind + ")";
		}
	}

	/** The event's path below its family's event package, which names the two families' counterparts alike. */
	private static String namesake(String event) {
		for (String root : List.of("net/minecraftforge/event/", "net/minecraftforge/client/event/",
				"net/neoforged/neoforge/event/", "net/neoforged/neoforge/client/event/")) {
			if (event.startsWith(root)) return event.substring(root.length());
		}
		return event;
	}

	private static List<Risk> risks(String method, byte[] bytes, Set<String> forwarded) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		String selector = method.substring(method.indexOf('#') + 1);
		List<Risk> out = new ArrayList<>();
		for (MethodNode m : node.methods) {
			if (!(m.name + m.desc).equals(selector)) continue;
			List<Integer> forgeCalls = new ArrayList<>(), neoCalls = new ArrayList<>(), inlinePosts = new ArrayList<>();
			for (int i = 0; i < m.instructions.size(); i++) {
				if (!(m.instructions.get(i) instanceof MethodInsnNode call)) continue;
				if (inlineNeoPost(call)) inlinePosts.add(i);
				if (call.getOpcode() != Opcodes.INVOKESTATIC) continue;
				if (forgeHook(call.owner)) forgeCalls.add(i);
				if (call.owner.startsWith("net/neoforged/")) neoCalls.add(i);
			}
			Map<Integer, Set<Integer>> edges = edges(node.name, m);
			org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames =
					inlinePosts.isEmpty() ? null : new Analyzer<>(new org.objectweb.asm.tree.analysis.SourceInterpreter()).analyze(node.name, m);
			for (int f : forgeCalls) {
				for (String event : constructed((MethodInsnNode) m.instructions.get(f), "net/minecraftforge/")) {
					if (!forwarded.contains(event)) continue;
					for (int n : neoCalls) {
						MethodInsnNode neo = (MethodInsnNode) m.instructions.get(n);
						if (!constructed(neo, "net/neoforged/").stream().anyMatch(made -> namesake(made).equals(namesake(event)))) continue;
						String kind = HookCallSiteCensus.isHookClass(neo.owner, "net/neoforged/") ? "hook" : "static";
						if (reach(edges, f, n) || reach(edges, n, f)) out.add(new Risk(f, n, event, kind));
					}
					for (int n : inlinePosts) {
						String made = postedInline(m, frames, (MethodInsnNode) m.instructions.get(n));
						if (made == null || !made.startsWith("net/neoforged/") || !namesake(made).equals(namesake(event))) continue;
						if (reach(edges, f, n) || reach(edges, n, f)) out.add(new Risk(f, n, event, "inline"));
					}
				}
			}
		}
		return out;
	}

	/** The event class an inline post's argument was constructed as in the method, traced through locals; null when not one. */
	private static String postedInline(MethodNode m, org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames,
			MethodInsnNode post) {
		var frame = frames[m.instructions.indexOf(post)];
		return frame == null || frame.getStackSize() < 1 ? null : madeAs(m, frames, frame.getStack(frame.getStackSize() - 1), new HashSet<>());
	}

	private static String madeAs(MethodNode m, org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames,
			org.objectweb.asm.tree.analysis.SourceValue value, Set<AbstractInsnNode> seen) {
		if (value == null || value.insns.size() != 1) return null;
		AbstractInsnNode source = value.insns.iterator().next();
		if (!seen.add(source)) return null;
		if (source instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) return type.desc;
		var frame = frames[m.instructions.indexOf(source)];
		if (frame == null) return null;
		int opcode = source.getOpcode();
		if (opcode == Opcodes.DUP || opcode == Opcodes.ASTORE || opcode == Opcodes.CHECKCAST)
			return frame.getStackSize() < 1 ? null : madeAs(m, frames, frame.getStack(frame.getStackSize() - 1), seen);
		if (source instanceof VarInsnNode load && opcode == Opcodes.ALOAD) return madeAs(m, frames, frame.getLocal(load.var), seen);
		return null;
	}

	/** The family's types the hook's own body, in its carrier, constructs. */
	private static Set<String> constructed(MethodInsnNode call, String family) {
		byte[] bytes = protocolBytes(call.owner + ".class");
		Set<String> out = new TreeSet<>();
		if (bytes == null) return out;
		ClassNode hook = new ClassNode();
		new ClassReader(bytes).accept(hook, ClassReader.SKIP_FRAMES);
		for (MethodNode m : hook.methods) {
			if (!m.name.equals(call.name) || !m.desc.equals(call.desc)) continue;
			for (AbstractInsnNode i : m.instructions) {
				if (i instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.startsWith(family)) out.add(type.desc);
			}
		}
		return out;
	}

	private static Map<Integer, Set<Integer>> edges(String owner, MethodNode method) throws Exception {
		Map<Integer, Set<Integer>> edges = new HashMap<>();
		new Analyzer<BasicValue>(new BasicInterpreter()) {
			@Override protected void newControlFlowEdge(int from, int to) {
				edges.computeIfAbsent(from, k -> new HashSet<>()).add(to);
			}

			@Override protected boolean newControlFlowExceptionEdge(int from, int to) {
				newControlFlowEdge(from, to);
				return true;
			}
		}.analyze(owner, method);
		return edges;
	}

	private static boolean reach(Map<Integer, Set<Integer>> edges, int from, int to) {
		Set<Integer> seen = new HashSet<>();
		ArrayDeque<Integer> work = new ArrayDeque<>(edges.getOrDefault(from, Set.of()));
		while (!work.isEmpty()) {
			int at = work.removeFirst();
			if (at == to) return true;
			if (seen.add(at)) work.addAll(edges.getOrDefault(at, Set.of()));
		}
		return false;
	}

	/**
	 * How what the kernel writes stands the pair down: "proved" when the NeoForge delivery is bracketed by a scope naming
	 * the event, "recorded" when that scope covers what the MinecraftForge call the kernel bracketed for it records, and
	 * null when neither (or when a hook that must report what it constructs does not, without which a scope or a
	 * recording would capture nothing). The bracketed calls are the game's own instructions, found again by their turn
	 * among the method's calls of the same target.
	 */
	private static String standsDown(NativeDualEventInjector injector, String method, byte[] bytes, Risk risk) {
		String owner = method.substring(0, method.indexOf('#')), selector = method.substring(method.indexOf('#') + 1);
		byte[] adapted = injector.transform(owner.replace('/', '.'), bytes, null);
		if (adapted == bytes) return null;
		ClassNode before = new ClassNode(), node = new ClassNode();
		new ClassReader(bytes).accept(before, 0);
		new ClassReader(adapted).accept(node, 0);
		MethodNode original = null;
		for (MethodNode m : before.methods) if ((m.name + m.desc).equals(selector)) original = m;
		if (original == null) return null;
		MethodInsnNode neoCall = (MethodInsnNode) original.instructions.get(risk.neo());
		MethodInsnNode forgeCall = (MethodInsnNode) original.instructions.get(risk.forge());
		for (MethodNode host : node.methods) {
			if (!(host.name + host.desc).equals(selector)) continue;
			MethodInsnNode neoNow = sameTurn(original, neoCall, host), forgeNow = sameTurn(original, forgeCall, host);
			NativeDualEventInjector.Bracket scope = null, recording = null;
			for (NativeDualEventInjector.Bracket bracket : NativeDualEventInjector.brackets(host)) {
				if (bracket.scope() && bracket.call() == neoNow) scope = bracket;
				if (!bracket.scope() && bracket.call() == forgeNow) recording = bracket;
			}
			// A static NeoForge hook reports its constructions from its own class; an inline post's event is the caller's.
			if (scope == null || (!risk.kind().equals("inline") && !reports(injector, neoCall.owner, "constructed"))) return null;
			if (List.of(scope.natively().split(";")).contains(risk.event())) return "proved";
			if (!List.of(scope.candidates().split(";")).contains(risk.event()) || recording == null) return null;
			return List.of(recording.candidates().split(";")).contains(risk.event())
					&& reports(injector, forgeCall.owner, "nativeConstructed") ? "recorded" : null;
		}
		return null;
	}

	/** The call in {@code now} that is {@code call}'s turn among {@code was}'s calls of the same target. */
	private static MethodInsnNode sameTurn(MethodNode was, MethodInsnNode call, MethodNode now) {
		int turn = 0;
		for (AbstractInsnNode i : was.instructions) {
			if (i == call) break;
			if (i instanceof MethodInsnNode other && sameTarget(other, call)) turn++;
		}
		for (AbstractInsnNode i : now.instructions) {
			if (i instanceof MethodInsnNode other && sameTarget(other, call) && turn-- == 0) return other;
		}
		return null;
	}

	private static boolean sameTarget(MethodInsnNode a, MethodInsnNode b) {
		return a.owner.equals(b.owner) && a.name.equals(b.name) && a.desc.equals(b.desc);
	}

	/** Whether the injector, run over the hook class from its carrier, makes it report its constructions. */
	private static boolean reports(NativeDualEventInjector injector, String hookOwner, String report) {
		byte[] hook = protocolBytes(hookOwner + ".class");
		if (hook == null) return false;
		byte[] adapted = injector.transform(hookOwner.replace('/', '.'), hook, null);
		ClassNode node = new ClassNode();
		new ClassReader(adapted).accept(node, 0);
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode i : m.instructions) {
				if (i instanceof MethodInsnNode call && call.owner.equals("net/forbric/api/NativeEventDelivery") && call.name.equals(report)) return true;
			}
		}
		return false;
	}

	private static byte[] protocolBytes(String path) {
		for (Path jar : List.of(base(), root().resolve("forge-runtime/forge-runtime.jar"),
				root().resolve("merged-base/forge-runtime-interop.jar"), root().resolve("neoforge-runtime/neoforge-runtime.jar"))) {
			if (!Files.isRegularFile(jar)) continue;
			try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
				var entry = zip.getEntry(path);
				if (entry != null) try (var input = zip.getInputStream(entry)) {
					return input.readAllBytes();
				}
			} catch (Exception unavailable) {
				return null;
			}
		}
		return null;
	}

	private static byte[] classBytes(String owner) throws Exception {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(base().toFile())) {
			return zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes();
		}
	}
}
