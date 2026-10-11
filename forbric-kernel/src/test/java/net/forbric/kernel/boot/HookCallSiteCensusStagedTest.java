package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
			"net/neoforged/neoforge/event/EventHooks", "neoforge-runtime/neoforge-runtime.jar");

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

	@Test
	void thePartialTableIsExactlyWhatTheCallSitesSay() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		Path before = forgePatchedGame();
		// The merge's own input, staged beside it; no vanilla install has it.
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(before), "MinecraftForge's patched game absent");
		Path carrier = root().resolve("forge-runtime/forge-runtime.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(carrier), "staged Forge carrier absent");

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

	/**
	 * A method that posts through both families can hand one subscriber a bridged event twice: the native
	 * MinecraftForge call, and the bridge forwarding the NeoForge one. Every such pair that can run in one invocation
	 * must be stood down by the kernel, which this checks on what the kernel actually writes: the NeoForge call is
	 * wrapped in a scope that names the MinecraftForge event — proved from the caller's bytecode, or recorded from a
	 * MinecraftForge call the kernel also wrapped — and both families' hooks report the events they construct.
	 *
	 * <p>The pairs are found independently of the kernel's proof: a MinecraftForge hook whose carrier body constructs a
	 * forwarded event, a NeoForge hook whose carrier body constructs the event of the same name under NeoForge's event
	 * package, and a path from one call to the other. Pairs on branches that never meet cannot deliver twice.
	 */
	@Test
	void noSinglePathPostsABridgedEventTwice() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		List<String> both = HookCallSiteCensus.methodsCallingBothFamilies(List.of(base()));
		System.out.println("[Forbric/Hooks] methods calling both ecosystems' event hooks: " + both.size());
		Set<String> forwarded = DeadEventAudit.forwardedForgeEvents();
		NativeDualEventInjector injector = new NativeDualEventInjector(HookCallSiteCensusStagedTest::protocolBytes, forwarded);
		List<String> unproved = new ArrayList<>();
		int risks = 0;
		for (String method : both) {
			String owner = method.substring(0, method.indexOf('#'));
			byte[] bytes = classBytes(owner);
			if (method.equals(PORTAL_OWNER + "#" + PORTAL_METHOD)) {
				// The portal's bridge carries a result and is composed by PortalSpawnInjector, not by this scope.
				risks++;
				if (!portalStandsDown(bytes)) unproved.add(method);
				continue;
			}
			for (Risk risk : risks(method, bytes, forwarded)) {
				risks++;
				if (!standsDown(injector, method, bytes, risk)) unproved.add(method + " " + risk);
			}
		}
		System.out.println("[Forbric/Hooks] single-path pairs of a forwarded event: " + risks);
		// The denominator: a scan that matched nothing would make the assertion below vacuous.
		assertTrue(risks > 0, "no method delivers a forwarded event through both families — verify the scan");
		assertEquals(List.of(), unproved,
				"a method that posts through both families can deliver a bridged event twice to one subscriber, unless "
						+ "the kernel scopes the NeoForge delivery with the MinecraftForge one: " + unproved);
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
	 * The negative control for the general stand-down: a stood-down pair whose MinecraftForge call is taken out of the
	 * family (the same instruction, to an owner outside MinecraftForge's namespace) is no longer stood down, so the
	 * check above can fail.
	 */
	@Test
	void aPairWithoutItsNativeDeliveryIsNotStoodDown() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base()), "staged merged base absent");
		Set<String> forwarded = DeadEventAudit.forwardedForgeEvents();
		NativeDualEventInjector injector = new NativeDualEventInjector(HookCallSiteCensusStagedTest::protocolBytes, forwarded);
		String found = null;
		Risk pair = null;
		for (String method : HookCallSiteCensus.methodsCallingBothFamilies(List.of(base()))) {
			if (method.startsWith(PORTAL_OWNER + "#")) continue;
			byte[] bytes = classBytes(method.substring(0, method.indexOf('#')));
			for (Risk risk : risks(method, bytes, forwarded)) {
				if (found == null && standsDown(injector, method, bytes, risk)) {
					found = method;
					pair = risk;
				}
			}
		}
		assertNotNull(found, "no stood-down pair to break");
		String owner = found.substring(0, found.indexOf('#'));
		ClassNode node = new ClassNode();
		new ClassReader(classBytes(owner)).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (!(m.name + m.desc).equals(found.substring(found.indexOf('#') + 1))) continue;
			((MethodInsnNode) m.instructions.get(pair.forge())).owner = "test/elsewhere/Factory";
		}
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		assertFalse(standsDown(new NativeDualEventInjector(HookCallSiteCensusStagedTest::protocolBytes, forwarded), found,
				writer.toByteArray(), pair), found + " " + pair + " with its MinecraftForge call gone");
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

	/** One MinecraftForge call and one NeoForge call of a method, by instruction index, and the forwarded event. */
	private record Risk(int forge, int neo, String event) {
		@Override public String toString() {
			return event.substring(event.lastIndexOf('/') + 1) + " @" + forge + "/" + neo;
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
			List<Integer> forgeCalls = new ArrayList<>(), neoCalls = new ArrayList<>();
			for (int i = 0; i < m.instructions.size(); i++) {
				if (!(m.instructions.get(i) instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
				if (HookCallSiteCensus.isHookClass(call.owner, "net/minecraftforge/")) forgeCalls.add(i);
				if (HookCallSiteCensus.isHookClass(call.owner, "net/neoforged/")) neoCalls.add(i);
			}
			Map<Integer, Set<Integer>> edges = edges(node.name, m);
			for (int f : forgeCalls) {
				for (String event : constructed((MethodInsnNode) m.instructions.get(f), "net/minecraftforge/")) {
					if (!forwarded.contains(event)) continue;
					for (int n : neoCalls) {
						if (!constructed((MethodInsnNode) m.instructions.get(n), "net/neoforged/").stream()
								.anyMatch(neo -> namesake(neo).equals(namesake(event)))) continue;
						if (reach(edges, f, n) || reach(edges, n, f)) out.add(new Risk(f, n, event));
					}
				}
			}
		}
		return out;
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
	 * Whether what the kernel writes stands the pair down: the NeoForge call goes through a scope naming the event —
	 * proved, or recorded from a MinecraftForge call the kernel wrapped for that — and both families' hooks report the
	 * events they construct, without which a scope or a recording would capture nothing.
	 */
	private static boolean standsDown(NativeDualEventInjector injector, String method, byte[] bytes, Risk risk) {
		String owner = method.substring(0, method.indexOf('#')), selector = method.substring(method.indexOf('#') + 1);
		byte[] adapted = injector.transform(owner.replace('/', '.'), bytes, null);
		if (adapted == bytes) return false;
		ClassNode before = new ClassNode(), node = new ClassNode();
		new ClassReader(bytes).accept(before, 0);
		new ClassReader(adapted).accept(node, 0);
		MethodInsnNode neoCall = null, forgeCall = null;
		for (MethodNode m : before.methods) {
			if (!(m.name + m.desc).equals(selector)) continue;
			neoCall = (MethodInsnNode) m.instructions.get(risk.neo());
			forgeCall = (MethodInsnNode) m.instructions.get(risk.forge());
		}
		for (MethodNode host : node.methods) {
			if (!(host.name + host.desc).equals(selector)) continue;
			// The method's own entry record clear, when the kernel inserted one, is the only thing ahead of its code.
			int offset = host.instructions.size() > 1 && host.instructions.get(1) instanceof MethodInsnNode enter
					&& enter.name.equals("enterNative") ? 2 : 0;
			if (!(host.instructions.get(risk.neo() + offset) instanceof MethodInsnNode wrapped)
					|| !wrapped.name.startsWith(NativeDualEventInjector.WRAPPER_PREFIX)) return false;
			List<String> scope = strings(node, wrapped);
			if (scope.size() < 3 || !reports(injector, neoCall.owner, "constructed")) return false;
			if (List.of(scope.get(0).split(";")).contains(risk.event())) return true;
			if (!List.of(scope.get(2).split(";")).contains(risk.event())) return false;
			if (!(host.instructions.get(risk.forge() + offset) instanceof MethodInsnNode recorded)
					|| !recorded.name.startsWith(NativeDualEventInjector.SOURCE_PREFIX)) return false;
			List<String> recording = strings(node, recorded);
			return recording.size() >= 2 && List.of(recording.get(1).split(";")).contains(risk.event())
					&& reports(injector, forgeCall.owner, "nativeConstructed");
		}
		return false;
	}

	/** The string constants of the wrapper {@code call} names, in order. */
	private static List<String> strings(ClassNode node, MethodInsnNode call) {
		List<String> out = new ArrayList<>();
		for (MethodNode wrapper : node.methods) {
			if (!wrapper.name.equals(call.name) || !wrapper.desc.equals(call.desc)) continue;
			for (AbstractInsnNode i : wrapper.instructions) if (i instanceof LdcInsnNode ldc && ldc.cst instanceof String s) out.add(s);
		}
		return out;
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
