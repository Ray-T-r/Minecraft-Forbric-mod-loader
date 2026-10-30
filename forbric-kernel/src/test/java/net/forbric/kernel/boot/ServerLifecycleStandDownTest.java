/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

import net.forbric.api.GameEventBridge;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.runtime.StagedGameClassLoader;
import net.forbric.kernel.transform.NativeDualEventInjector;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * MinecraftForge's server lifecycle events, delivered once per server whichever family's hook the merged game calls.
 *
 * <p>A merged {@code initServer} and {@code runServer} that keep both families' edits call MinecraftForge's
 * {@code ServerLifecycleHooks} natively on the same path as NeoForge's, and the kernel forwarded each NeoForge event to
 * MinecraftForge's hook as well: every MinecraftForge mod's about-to-start, starting and started listeners ran twice, its
 * SERVER configs loaded twice with two file watchers each, PermissionAPI was initialised twice. The forwards are
 * {@code DeadEventAudit.BRIDGED} rows now, so {@code NativeDualEventInjector} scopes such a NeoForge call with the event
 * MinecraftForge delivered there and each forward, installed through {@code GameEventMultiplexer}'s guard, stands down
 * for it. The kernel's own work at server start is no forward and still runs once.
 *
 * <p>Two halves. On real buses, with the staged carriers' own hook methods lifted into classes of their own and callers
 * in both shapes a base can have: the forward and the native delivery are never both heard. And on the staged merged
 * base, the injector scopes each NeoForge lifecycle call the base pairs with MinecraftForge's, and no other.
 */
class ServerLifecycleStandDownTest {
	private static final String NEO_HOOKS = "net/neoforged/neoforge/server/ServerLifecycleHooks";
	private static final String FORGE_HOOKS = "net/minecraftforge/server/ServerLifecycleHooks";
	private static final String FORGE_EVENTS = "net/minecraftforge/event/server/";
	private static final String SERVER = "net/minecraft/server/MinecraftServer";
	private static final String CALLERS = "fixture/LifecycleCallers";
	private static final String HOOK = "(L" + SERVER + ";)V";

	// ---------------------------------------------------------------- the table the guard and the injector read

	@Test
	void everyServerLifecycleForwardIsABridgedRowOfItsOwnEvent() {
		Map<GameEventBridge, String> expected = Map.of(GameEventBridge.SERVER_ABOUT_TO_START, "ServerAboutToStartEvent",
				GameEventBridge.SERVER_STARTING, "ServerStartingEvent", GameEventBridge.SERVER_STARTED, "ServerStartedEvent",
				GameEventBridge.SERVER_STOPPING, "ServerStoppingEvent", GameEventBridge.SERVER_STOPPED, "ServerStoppedEvent");
		expected.forEach((bridge, event) -> assertEquals(java.util.Set.of(FORGE_EVENTS + event), DeadEventAudit.forgeEventsOf(bridge),
				bridge + " forwards exactly MinecraftForge's " + event + ", so it stands down for that event and no other"));
		for (String event : expected.values()) {
			assertTrue(DeadEventAudit.forwardedForgeEvents().contains(FORGE_EVENTS + event),
					event + " is what NativeDualEventInjector looks for on a path that also posts NeoForge's");
		}
	}

	// ---------------------------------------------------------------- on real buses

	@Test
	void aStartOnlyNeoForgeAnnouncesReachesMinecraftForgeOnceAndTheKernelsWorkRunsOnce() throws Exception {
		try (World world = World.of(false)) {
			AtomicInteger started = world.forgeCounter("ServerStartedEvent");
			world.run("neoStarted");
			assertEquals(1, started.get(), "the forward delivers MinecraftForge's started event");
			assertEquals(1, world.duties.get(), "the kernel's server-started work ran");
			assertTrue(world.loginsAllowed(), "and MinecraftForge's login gate is open");
		}
	}

	@Test
	void whereTheBaseStartsMinecraftForgeNativelyTheForwardStandsDownAndTheKernelsWorkStillRuns() throws Exception {
		try (World world = World.of(false)) {
			AtomicInteger started = world.forgeCounter("ServerStartedEvent");
			world.run("bothStarted");
			assertEquals(2, started.get(), "the shape of the defect: without the scope, native and forwarded");
		}
		try (World world = World.of(true)) {
			AtomicInteger started = world.forgeCounter("ServerStartedEvent");
			world.run("bothStarted");
			assertEquals(1, started.get(), "with it, once: natively");
			assertEquals(1, world.duties.get(), "the kernel's own work does not stand down with the forward");
			assertTrue(world.loginsAllowed());
		}
	}

	@Test
	void whereTheBaseStopsMinecraftForgeNativelyTheStoppingForwardStandsDown() throws Exception {
		for (boolean repaired : new boolean[] {false, true}) {
			try (World world = World.of(repaired)) {
				AtomicInteger stopping = world.forgeCounter("ServerStoppingEvent");
				world.run("bothStopping");
				assertEquals(repaired ? 1 : 2, stopping.get(), repaired ? "once, natively" : "twice without the scope");
			}
		}
	}

	/**
	 * Negative control: MinecraftForge's stopping hook natively on the path of NeoForge's STARTED hook. The scope names
	 * the stopping event, which is not what the started forward re-posts, so the started forward must still run.
	 */
	@Test
	void aNativeStoppingDoesNotStandDownTheStartedForward() throws Exception {
		try (World world = World.of(true)) {
			AtomicInteger started = world.forgeCounter("ServerStartedEvent"), stopping = world.forgeCounter("ServerStoppingEvent");
			world.run("forgeStoppingThenNeoStarted");
			assertEquals(1, stopping.get(), "the native stopping event");
			assertEquals(1, started.get(), "the started event, forwarded: it had no native delivery");
			assertEquals(1, world.duties.get());
		}
	}

	// ---------------------------------------------------------------- on the staged merged base

	/**
	 * The merged servers' own lifecycle calls, through the injector the kernel runs: each NeoForge hook the base calls on
	 * the same path as MinecraftForge's counterpart is scoped with that counterpart's event, proved from the bytecode, in
	 * either order (about-to-start and started follow MinecraftForge's call, starting precedes it); every other NeoForge
	 * lifecycle call's scope, where it has one, does not name its counterpart, so its forward still runs.
	 */
	@Test
	void theMergedServersPairedLifecycleCallsAreEachScopedWithTheirMinecraftForgeEvent() throws Exception {
		TestFixtures.requireForgeCarrier("the MinecraftForge lifecycle events each NeoForge call is scoped with");
		Path root = TestFixtures.stagedRoot();
		Path base = root.resolve(TestFixtures.BASE_JAR);
		TestFixtures.requireFile(Fixture.STAGED, base, "staged merged base absent");
		NativeDualEventInjector injector = new NativeDualEventInjector(path -> bytes(root, path), DeadEventAudit.forwardedForgeEvents());
		Map<String, java.util.Set<String>> proved = new TreeMap<>(), named = new TreeMap<>();
		for (String owner : List.of("net/minecraft/server/dedicated/DedicatedServer", "net/minecraft/client/server/IntegratedServer",
				"net/minecraft/gametest/framework/GameTestServer", SERVER)) {
			byte[] before = bytes(root, owner + ".class");
			assertNotNull(before, owner + " in the staged base");
			scopes(owner, injector.transform(owner.replace('/', '.'), before, null), proved, named);
		}
		System.out.println("[Forbric/Lifecycle] NeoForge lifecycle calls on the staged servers, with what their scopes prove: " + proved);
		Map<String, String> paired = new TreeMap<>();
		for (String owner : List.of("net/minecraft/server/dedicated/DedicatedServer", "net/minecraft/client/server/IntegratedServer")) {
			paired.put(owner + "#initServer()Z handleServerAboutToStart", "ServerAboutToStartEvent");
			paired.put(owner + "#initServer()Z handleServerStarting", "ServerStartingEvent");
		}
		paired.put("net/minecraft/gametest/framework/GameTestServer#initServer()Z handleServerAboutToStart", "ServerAboutToStartEvent");
		paired.put(SERVER + "#runServer()V handleServerStarted", "ServerStartedEvent");
		paired.put(SERVER + "#runServer()V handleServerStopping", "ServerStoppingEvent");
		paired.forEach((call, event) -> assertTrue(proved.getOrDefault(call, java.util.Set.of()).contains(FORGE_EVENTS + event),
				call + " is called on the same path as MinecraftForge's, so its scope proves " + event + ": " + proved.get(call)));
		// GameTestServer asks MinecraftForge about the start but not about starting, and runServer calls NeoForge's
		// handleServerStopped nine times and MinecraftForge's never: their forwards must stay.
		for (String call : List.of("net/minecraft/gametest/framework/GameTestServer#initServer()Z handleServerStarting",
				SERVER + "#runServer()V handleServerStopped")) {
			assertTrue(named.containsKey(call), call + " is a NeoForge lifecycle call of the staged base");
		}
		for (Map.Entry<String, java.util.Set<String>> call : named.entrySet()) {
			if (paired.containsKey(call.getKey())) continue;
			String hook = call.getKey().substring(call.getKey().lastIndexOf(' ') + 1);
			String counterpart = FORGE_EVENTS + "Server" + hook.substring("handleServer".length()) + "Event";
			assertFalse(call.getValue().contains(counterpart), call.getKey() + " has no native MinecraftForge counterpart on its"
					+ " path, yet its scope names " + counterpart);
		}
	}

	/**
	 * For each NeoForge lifecycle call of {@code owner} ("method hook"): the MinecraftForge events its scope proves, and
	 * every event its scope names at all (proved or recorded). An unscoped call names none.
	 */
	private static void scopes(String owner, byte[] adapted, Map<String, java.util.Set<String>> proved,
			Map<String, java.util.Set<String>> named) {
		ClassNode node = new ClassNode();
		new ClassReader(adapted).accept(node, 0);
		for (MethodNode m : node.methods) {
			Map<AbstractInsnNode, NativeDualEventInjector.Bracket> brackets = new HashMap<>();
			for (NativeDualEventInjector.Bracket bracket : NativeDualEventInjector.brackets(m)) if (bracket.scope()) brackets.put(bracket.call(), bracket);
			for (AbstractInsnNode insn : m.instructions) {
				if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(NEO_HOOKS) || !call.name.startsWith("handleServer")) continue;
				String key = owner + "#" + m.name + m.desc + " " + call.name;
				java.util.Set<String> p = proved.computeIfAbsent(key, k -> new java.util.TreeSet<>());
				java.util.Set<String> n = named.computeIfAbsent(key, k -> new java.util.TreeSet<>());
				// The scope bracketing the call opens with (proved, invocation, candidates, faithful), each a ';'-joined list.
				NativeDualEventInjector.Bracket scope = brackets.get(call);
				if (scope == null) continue;
				for (String e : scope.natively().split(";")) if (!e.isEmpty()) { p.add(e); n.add(e); }
				for (String e : scope.candidates().split(";")) if (!e.isEmpty()) n.add(e);
			}
		}
	}

	/** A class from the staged base or a carrier, as the kernel's injector reads hook bodies at boot. */
	private static byte[] bytes(Path root, String path) {
		for (Path jar : List.of(root.resolve(TestFixtures.BASE_JAR), root.resolve("forge-runtime/forge-runtime.jar"),
				root.resolve("merged-base/forge-runtime-interop.jar"), root.resolve("neoforge-runtime/neoforge-runtime.jar"))) {
			if (!Files.isRegularFile(jar)) continue;
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				var entry = zip.getEntry(path);
				if (entry != null) return zip.getInputStream(entry).readAllBytes();
			} catch (Exception unavailable) {
				return null;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- the world

	/** The staged game over its libraries, with the two lifted hook classes and the callers defined here. */
	private static final class World extends URLClassLoader {
		private final Map<String, byte[]> defined;
		final AtomicInteger duties = new AtomicInteger();

		private World(URL[] urls, Map<String, byte[]> defined) {
			super(urls, ClassLoader.getPlatformClassLoader());
			this.defined = defined;
		}

		static World of(boolean repaired) throws Exception {
			Map<String, byte[]> classes = classes();
			if (repaired) {
				NativeDualEventInjector injector = injector(classes);
				for (String owner : List.of(NEO_HOOKS, FORGE_HOOKS, CALLERS)) {
					byte[] before = classes.get(owner), after = injector.transform(owner.replace('/', '.'), before, null);
					assertNotSame(before, after, owner + " is a class the stand-down must rewrite");
					classes.put(owner, after);
				}
			}
			List<URL> urls = new ArrayList<>(StagedGameClassLoader.urls());
			urls.add(Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main").toUri().toURL());
			World world = new World(urls.toArray(new URL[0]), classes);
			Object bus = world.type("net/neoforged/neoforge/common/NeoForge").getField("EVENT_BUS").get(null);
			Class<?> lifecycle = world.type("net/forbric/kernel/runtime/KernelGameServerLifecycle");
			for (GameEventBridge bridge : List.of(GameEventBridge.SERVER_STARTED, GameEventBridge.SERVER_STOPPING)) {
				Object view = GameEventMultiplexer.guarded(world, bus, bridge);
				assertNotSame(bus, view, bridge + " is a BRIDGED row, so it installs on the guarded view");
				lifecycle.getMethod(bridge == GameEventBridge.SERVER_STARTED ? "installStarted" : "installStopping", Object.class)
						.invoke(null, view);
			}
			// The kernel's own work goes on the bus itself, after the forward, as GameEventMultiplexer installs it.
			Method duties = lifecycle.getDeclaredMethod("installStartedDuties", Object.class, Consumer.class);
			duties.setAccessible(true);
			duties.invoke(null, bus, (Consumer<Object>) server -> world.duties.incrementAndGet());
			world.type("net/neoforged/bus/api/IEventBus").getMethod("start").invoke(bus);
			return world;
		}

		static NativeDualEventInjector injector(Map<String, byte[]> classes) {
			Map<String, byte[]> hooks = Map.of(NEO_HOOKS, classes.get(NEO_HOOKS), FORGE_HOOKS, classes.get(FORGE_HOOKS));
			return new NativeDualEventInjector(path -> hooks.get(path.substring(0, path.length() - ".class".length())),
					DeadEventAudit.forwardedForgeEvents());
		}

		static Map<String, byte[]> classes() throws Exception {
			Path run = TestFixtures.stagedRoot();
			Path neo = run.resolve("neoforge-runtime/neoforge-runtime.jar");
			Path forge = run.resolve("merged-base/forge-runtime-interop.jar");
			if (!Files.isRegularFile(forge)) forge = run.resolve("forge-runtime/forge-runtime.jar");
			TestFixtures.requireFiles(Fixture.STAGED, "the staged carriers are absent", neo, forge);
			Map<String, byte[]> out = new HashMap<>();
			out.put(NEO_HOOKS, lifted(neo, NEO_HOOKS, List.of(), "handleServerStarted", "handleServerStopping"));
			// MinecraftForge's started hook opens its login gate, a field of its own class, as its last action.
			out.put(FORGE_HOOKS, lifted(forge, FORGE_HOOKS, List.of("allowLogins"), "handleServerStarted", "handleServerStopping"));
			out.put(CALLERS, callers());
			return out;
		}

		/**
		 * The carrier's {@code owner} with only the named static methods and fields, as they are in the carrier; each
		 * {@code AtomicBoolean} field starts false, as MinecraftForge's own class initialiser sets it.
		 */
		private static byte[] lifted(Path jar, String owner, List<String> fields, String... names) throws Exception {
			ClassNode original = new ClassNode();
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				var entry = zip.getEntry(owner + ".class");
				assertNotNull(entry, owner + " in " + jar);
				new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(original, 0);
			}
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			writer.visit(original.version, original.access, original.name, null, "java/lang/Object", null);
			for (FieldNode field : original.fields) {
				if (!fields.contains(field.name)) continue;
				assertEquals("Ljava/util/concurrent/atomic/AtomicBoolean;", field.desc, owner + "." + field.name);
				field.accept(writer);
			}
			if (!fields.isEmpty()) {
				MethodVisitor init = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
				init.visitCode();
				for (String field : fields) {
					init.visitTypeInsn(Opcodes.NEW, "java/util/concurrent/atomic/AtomicBoolean");
					init.visitInsn(Opcodes.DUP);
					init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/concurrent/atomic/AtomicBoolean", "<init>", "()V", false);
					init.visitFieldInsn(Opcodes.PUTSTATIC, owner, field, "Ljava/util/concurrent/atomic/AtomicBoolean;");
				}
				init.visitInsn(Opcodes.RETURN);
				init.visitMaxs(0, 0);
				init.visitEnd();
			}
			int found = 0;
			for (MethodNode method : original.methods) {
				if (!List.of(names).contains(method.name)) continue;
				assertTrue((method.access & Opcodes.ACC_STATIC) != 0, owner + "." + method.name + " is a static hook");
				method.accept(writer);
				found++;
			}
			assertEquals(names.length, found, owner + " declares each of " + List.of(names) + " once");
			writer.visitEnd();
			return writer.toByteArray();
		}

		/**
		 * The merged servers' lifecycle shapes: NeoForge asked alone; MinecraftForge asked natively first on the same path,
		 * as runServer asks both families' started and stopping hooks; and MinecraftForge's stopping hook on the path of
		 * NeoForge's started hook.
		 */
		private static byte[] callers() {
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, CALLERS, null, "java/lang/Object", null);
			caller(writer, "neoStarted", new String[] {NEO_HOOKS, "handleServerStarted"});
			caller(writer, "bothStarted", new String[] {FORGE_HOOKS, "handleServerStarted"}, new String[] {NEO_HOOKS, "handleServerStarted"});
			caller(writer, "bothStopping", new String[] {FORGE_HOOKS, "handleServerStopping"}, new String[] {NEO_HOOKS, "handleServerStopping"});
			caller(writer, "forgeStoppingThenNeoStarted", new String[] {FORGE_HOOKS, "handleServerStopping"},
					new String[] {NEO_HOOKS, "handleServerStarted"});
			writer.visitEnd();
			return writer.toByteArray();
		}

		private static void caller(ClassWriter writer, String name, String[]... hooks) {
			MethodVisitor m = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, HOOK, null, null);
			m.visitCode();
			for (String[] hook : hooks) {
				m.visitVarInsn(Opcodes.ALOAD, 0);
				m.visitMethodInsn(Opcodes.INVOKESTATIC, hook[0], hook[1], HOOK, false);
			}
			m.visitInsn(Opcodes.RETURN);
			m.visitMaxs(0, 0);
			m.visitEnd();
		}

		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			// One NativeEventDelivery for the injected hooks and the guard GameEventMultiplexer wraps the bridge in.
			if (name.startsWith("net.forbric.api.")) return ServerLifecycleStandDownTest.class.getClassLoader().loadClass(name);
			synchronized (getClassLoadingLock(name)) {
				Class<?> done = findLoadedClass(name);
				if (done != null) return done;
				byte[] bytes = defined.get(name.replace('.', '/'));
				if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
				return super.loadClass(name, resolve);
			}
		}

		Class<?> type(String internal) {
			try {
				return Class.forName(internal.replace('/', '.'), false, this);
			} catch (ClassNotFoundException absent) {
				throw new AssertionError(internal + " is not in the staged game", absent);
			}
		}

		/** Counts what a MinecraftForge mod's listener on one lifecycle event's own bus hears. */
		AtomicInteger forgeCounter(String event) throws Exception {
			AtomicInteger count = new AtomicInteger();
			Object bus = type(FORGE_EVENTS + event).getField("BUS").get(null);
			type("net/minecraftforge/eventbus/api/bus/EventBus").getMethod("addListener", Consumer.class)
					.invoke(bus, (Consumer<Object>) heard -> count.incrementAndGet());
			return count;
		}

		/** Runs one caller for a server the events carry; none of these hooks reads it. */
		void run(String caller) throws Exception {
			try {
				type(CALLERS).getMethod(caller, type(SERVER)).invoke(null, (Object) null);
			} catch (java.lang.reflect.InvocationTargetException thrown) {
				if (thrown.getCause() instanceof Exception failure) throw failure;
				throw thrown;
			}
		}

		boolean loginsAllowed() throws Exception {
			var field = type(FORGE_HOOKS).getDeclaredField("allowLogins");
			field.setAccessible(true);
			return ((AtomicBoolean) field.get(null)).get();
		}
	}
}
