/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import net.forbric.api.NativeEventDelivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Counts deliveries: after the injector, a MinecraftForge event the caller delivers natively reaches its subscribers
 * exactly once per occurrence, whether the native call comes before the NeoForge one or after it, and a NeoForge event
 * with no native counterpart on its path is still forwarded.
 *
 * <p>The families are invented ({@code late/forge}, {@code late/neo}): the injector keys on namespaces, bus types and
 * control flow, never on who calls whom. The bridge here is the guard {@code GameEventMultiplexer} wraps every bridge
 * listener in — skip what {@link NativeEventDelivery#covered} says was delivered natively, forward the rest and carry a
 * cancellation back.
 */
@ExecutesInjector(NativeDualEventInjector.class)
class NativeDualEventInjectorExecutionTest {
	static final NativeDualEventInjector.Families FAMILIES =
			new NativeDualEventInjector.Families("late/forge/", "late/forge/bus/", "late/neo/", "late/neo/bus/Bus");
	static final String FORGE_EVENT = "late/forge/ForgeTouch";
	static final Set<String> BRIDGED = Set.of(FORGE_EVENT);
	private static final List<String> TRANSFORMED = List.of("late/game/Callers", "late/neo/Hooks", "late/forge/Factory");

	static Map<String, byte[]> classes(Path work) throws Exception {
		return classes(work, Map.of());
	}

	/** The fixture's classes, compiled together with {@code more}. */
	static Map<String, byte[]> classes(Path work, Map<String, String> more) throws Exception {
		Map<String, String> sources = new HashMap<>(sources());
		sources.putAll(more);
		return new HashMap<>(InjectorExecution.compile(work, sources,
				List.of(Path.of(NativeEventDelivery.class.getProtectionDomain().getCodeSource().getLocation().toURI()))));
	}

	private static Map<String, String> pairs(String... sourcesByName) {
		Map<String, String> out = new LinkedHashMap<>();
		for (int i = 0; i < sourcesByName.length; i += 2) out.put(sourcesByName[i], sourcesByName[i + 1]);
		return out;
	}

	private static Map<String, String> sources() {
		return pairs(
				"late.neo.bus.Bus", "package late.neo.bus; public interface Bus { Object post(Object event); }",
				"late.neo.NeoTouch", "package late.neo; public class NeoTouch { public final Object who; public boolean canceled; public NeoTouch(Object who){this.who=who;} }",
				"late.neo.Hooks", """
						package late.neo;
						import java.util.*;
						import java.util.function.Consumer;
						public final class Hooks {
						    public static final List<Consumer<Object>> LISTENERS = new ArrayList<>();
						    public static final Map<Object,Integer> POSTED = new IdentityHashMap<>();
						    public static final late.neo.bus.Bus BUS = event -> {
						        POSTED.merge(((NeoTouch) event).who, 1, Integer::sum);
						        for (Consumer<Object> listener : List.copyOf(LISTENERS)) listener.accept(event);
						        return event;
						    };
						    public static boolean touch(Object who) { return ((NeoTouch) BUS.post(new NeoTouch(who))).canceled; }
						}
						""",
				"late.forge.bus.ForgeBus", "package late.forge.bus; public interface ForgeBus { boolean post(Object event); }",
				"late.forge.ForgeTouch", "package late.forge; public class ForgeTouch { public static final ForgeTouch SHARED = new ForgeTouch(new Object()); public final Object who; public boolean canceled; public ForgeTouch(Object who){this.who=who;} }",
				"late.forge.Factory", """
						package late.forge;
						import java.util.*;
						import java.util.function.Consumer;
						public final class Factory {
						    public static final List<Consumer<Object>> LISTENERS = new ArrayList<>();
						    public static final Map<Object,Integer> POSTED = new IdentityHashMap<>();
						    public static final late.forge.bus.ForgeBus BUS = event -> {
						        POSTED.merge(((ForgeTouch) event).who, 1, Integer::sum);
						        for (Consumer<Object> listener : List.copyOf(LISTENERS)) listener.accept(event);
						        return ((ForgeTouch) event).canceled;
						    };

						    public static boolean touch(Object who) { return BUS.post(new ForgeTouch(who)); }
						    public static boolean touchIf(Object who, boolean when) { if (!when) return false; return BUS.post(new ForgeTouch(who)); }
						    public static boolean touchShared() { return BUS.post(ForgeTouch.SHARED); }
						    public static boolean touchSharedIf(boolean when) { if (!when) return false; return BUS.post(ForgeTouch.SHARED); }
						}
						""",
				"late.bridge.Bridge", """
						package late.bridge;
						import net.forbric.api.NativeEventDelivery;
						public final class Bridge {
						    public static int excused;
						    /** The guard GameEventMultiplexer wraps every bridge in, for one bridge's MinecraftForge event. */
						    static boolean covered(Object event, String forge, Runnable forward) {
						        return NativeEventDelivery.covered(event, java.util.Set.of(forge), new NativeEventDelivery.Deferred() {
						            @Override public void deliver() { forward.run(); }
						            @Override public void excused() { excused++; }
						        });
						    }
						    public static void install() {
						        late.neo.Hooks.LISTENERS.add(event -> {
						            if (event.getClass() != late.neo.NeoTouch.class) return;
						            late.neo.NeoTouch neo = (late.neo.NeoTouch) event;
						            // As NeoForge's bus does for the kernel's LOWEST listeners: a cancelled event is not forwarded.
						            if (neo.canceled) return;
						            if (NativeEventDelivery.covered(event, java.util.Set.of("late/forge/ForgeTouch"), new NativeEventDelivery.Deferred() {
						                @Override public void deliver() { late.forge.Factory.touch(neo.who); }
						                @Override public void excused() { excused++; }
						            })) return;
						            if (late.forge.Factory.touch(neo.who)) neo.canceled = true;
						        });
						    }
						}
						""",
				"late.game.Callers", """
						package late.game;
						import late.forge.Factory;
						import late.neo.Hooks;
						public final class Callers {
						    public static void forgeFirst(Object who) { Factory.touch(who); Hooks.touch(who); }
						    public static void neoFirst(Object who) { Hooks.touch(who); Factory.touch(who); }
						    public static void branches(Object who, boolean forge) { if (forge) Factory.touch(who); else Hooks.touch(who); }
						    public static void conditional(Object who, boolean when) { Factory.touchIf(who, when); Hooks.touch(who); }
						    public static void guarded(Object who, Object gate) { if (gate != null) Factory.touch(who); if (gate != null) Hooks.touch(who); }
						    public static void skippableAfter(Object who, boolean when) { Hooks.touch(who); if (when) Factory.touch(who); }
						    public static void loop(Object who) { Factory.touch(who); for (int i = 0; i < 2; i++) Hooks.touch(who); }
						    public static void inline(Object who) { Factory.touch(who); Hooks.BUS.post(new late.neo.NeoTouch(who)); }
						    public static void forgeInline(Object who) { Factory.BUS.post(new late.forge.ForgeTouch(who)); Hooks.touch(who); }
						    public static boolean neoOnly(Object who) { return Hooks.touch(who); }
						    public static boolean cancelledFirst(Object who) { if (Factory.touch(who)) return true; return Hooks.touch(who); }
						    public static void sharedFirst(Object who) { Factory.touchShared(); Hooks.touch(who); }
						    public static void sharedAfter(Object who) { Hooks.touch(who); Factory.touchShared(); }
						    public static void sharedIf(Object who, boolean when) { Factory.touchSharedIf(when); Hooks.touch(who); }
						    public static void guardedAfter(Object who, Object gate, boolean neoExit) { Hooks.touch(who); if (neoExit) return; if (gate == null) return; Factory.touch(who); }
						    public static void vanillaTurnedExit(Object who, boolean flag) { Hooks.touch(who); if (flag) { System.identityHashCode(who); return; } Factory.touch(who); }
						    public static void cancelledExit(Object who, Object gate) { if (Hooks.touch(who)) return; if (gate == null) return; Factory.touch(who); }
						    public static void unjudged(Object who, Object gate) { Hooks.touch(who); if (gate == null) return; Factory.touch(who); }
						}
						""");
	}

	/**
	 * MinecraftForge's own body of the callers that have one: the same checks without NeoForge's call or its exits, and
	 * a vanilla branch that MinecraftForge's body continues through to its call. {@code unjudged} has none.
	 */
	static java.util.function.Function<String, ClassNode> forgeBodies(Path work) throws Exception {
		Map<String, String> sources = new HashMap<>(sources());
		sources.put("late.game.Callers", """
				package late.game;
				import late.forge.Factory;
				public final class Callers {
				    public static void guardedAfter(Object who, Object gate, boolean neoExit) { if (gate == null) return; Factory.touch(who); }
				    public static void vanillaTurnedExit(Object who, boolean flag) { if (flag) { System.identityHashCode(who); } Factory.touch(who); }
				    public static void cancelledExit(Object who, Object gate) { if (gate == null) return; Factory.touch(who); }
				}
				""");
		byte[] callers = InjectorExecution.compile(work.resolve("forge-body"), sources,
				List.of(Path.of(NativeEventDelivery.class.getProtectionDomain().getCodeSource().getLocation().toURI()))).get("late/game/Callers");
		return owner -> {
			if (!owner.equals("late/game/Callers")) return null;
			ClassNode node = new ClassNode();
			new ClassReader(callers).accept(node, ClassReader.SKIP_FRAMES);
			return node;
		};
	}

	static NativeDualEventInjector injector(Map<String, byte[]> original) {
		return injector(original, owner -> null);
	}

	static NativeDualEventInjector injector(Map<String, byte[]> original, java.util.function.Function<String, ClassNode> forge) {
		return new NativeDualEventInjector(path -> original.get(path.substring(0, path.length() - ".class".length())),
				BRIDGED, FAMILIES, forge);
	}

	/** A defined world: the transformed (or original) classes, the bridge installed. */
	record World(ClassLoader loader) {
		Class<?> type(String name) throws Exception {
			return loader.loadClass(name.replace('/', '.'));
		}

		/** Runs {@code Callers.method} for a fresh subject; MinecraftForge's and NeoForge's deliveries for it. */
		int[] run(String method, Object... rest) throws Exception {
			return runFor(new Object(), method, rest);
		}

		int[] runFor(Object who, String method, Object... rest) throws Exception {
			Object[] args = new Object[rest.length + 1];
			args[0] = who;
			System.arraycopy(rest, 0, args, 1, rest.length);
			Class<?>[] types = new Class<?>[args.length];
			types[0] = Object.class;
			for (int i = 1; i < args.length; i++) types[i] = args[i] instanceof Boolean ? boolean.class : Object.class;
			type("late/game/Callers").getMethod(method, types).invoke(null, args);
			return new int[] {count("late/forge/Factory", who), count("late/neo/Hooks", who)};
		}

		Object call(String method, Object who) throws Exception {
			return type("late/game/Callers").getMethod(method, Object.class).invoke(null, who);
		}

		int count(String owner, Object who) throws Exception {
			@SuppressWarnings("unchecked")
			Map<Object, Integer> posted = (Map<Object, Integer>) type(owner).getField("POSTED").get(null);
			return posted.getOrDefault(who, 0);
		}

		@SuppressWarnings("unchecked")
		List<Consumer<Object>> listeners(String owner) throws Exception {
			return (List<Consumer<Object>>) type(owner).getField("LISTENERS").get(null);
		}
	}

	static World world(Map<String, byte[]> original, boolean repair) throws Exception {
		return world(original, repair, owner -> null);
	}

	static World world(Map<String, byte[]> original, boolean repair, java.util.function.Function<String, ClassNode> forge) throws Exception {
		Map<String, byte[]> definitions = new HashMap<>(original);
		if (repair) {
			NativeDualEventInjector transformer = injector(original, forge);
			for (String owner : new TreeSet<>(original.keySet())) {
				byte[] before = definitions.get(owner), after = transformer.transform(owner.replace('/', '.'), before, null);
				if (TRANSFORMED.contains(owner)) assertNotSame(before, after, owner + " is a site the injector must rewrite");
				assertSame(after, transformer.transform(owner.replace('/', '.'), after, null), "a second pass over " + owner + " changes nothing");
				definitions.put(owner, after);
			}
		}
		ClassLoader loader = new ClassLoader(NativeEventDelivery.class.getClassLoader()) {
			@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
				byte[] bytes = definitions.get(name.replace('.', '/'));
				if (bytes == null) throw new ClassNotFoundException(name);
				return defineClass(name, bytes, 0, bytes.length);
			}
		};
		for (var entry : definitions.entrySet()) assertEquals("", InjectorExecution.verify(entry.getValue(), loader), entry.getKey());
		World world = new World(loader);
		world.type("late/bridge/Bridge").getMethod("install").invoke(null);
		return world;
	}

	@Test
	void eachMinecraftForgeEventIsDeliveredOncePerOccurrenceWhicheverFamilyCallsFirst(@TempDir Path work) throws Exception {
		Map<String, byte[]> classes = classes(work);
		World before = world(classes, false);
		assertArrayEquals(new int[] {2, 1}, before.run("forgeFirst"), "the forward doubles the restored native call");
		assertArrayEquals(new int[] {2, 1}, before.run("neoFirst"));

		World after = world(classes, true);
		assertArrayEquals(new int[] {1, 1}, after.run("forgeFirst"), "native first, dominating: the bridge stands down");
		assertArrayEquals(new int[] {1, 1}, after.run("neoFirst"), "native after, post-dominating: no order restriction");
		assertArrayEquals(new int[] {1, 1}, after.run("inline"), "an inline NeoForge post is captured by the wrapper itself");
		assertArrayEquals(new int[] {1, 1}, after.run("forgeInline"), "an inline MinecraftForge post is a native delivery too");
		assertArrayEquals(new int[] {1, 1}, after.run("neoOnly"), "a NeoForge delivery with no native counterpart still forwards");
	}

	@Test
	void onlyADeliveryThatActuallyHappenedCovers(@TempDir Path work) throws Exception {
		World after = world(classes(work), true);
		assertArrayEquals(new int[] {1, 1}, after.run("conditional", true), "the conditional hook posted: covered");
		assertArrayEquals(new int[] {1, 1}, after.run("conditional", false), "it did not: the forward is the only delivery");
		assertArrayEquals(new int[] {1, 1}, after.run("guarded", new Object()), "both calls behind the same test");
		assertArrayEquals(new int[] {0, 0}, after.run("guarded", (Object) null));
		assertArrayEquals(new int[] {1, 0}, after.run("branches", true), "different branches never meet");
		assertArrayEquals(new int[] {1, 1}, after.run("branches", false));
		assertArrayEquals(new int[] {2, 2}, after.run("loop"), "one native delivery covers one NeoForge post, not every one after it");
		assertArrayEquals(new int[] {1, 1}, after.run("skippableAfter", false), "a native call that may not follow proves nothing");
		// The documented limit: a native call that follows only sometimes cannot be known when the bridge decides, so
		// the forward is kept and that path still delivers twice. Losing the event on the other path would be worse.
		assertArrayEquals(new int[] {2, 1}, after.run("skippableAfter", true));
	}

	/**
	 * A MinecraftForge hook that posts its event class's one instance instead of constructing one — MinecraftForge's
	 * client tick posts {@code TickEvent.ClientTickEvent.Pre.INSTANCE} — delivers that event natively all the same. The
	 * forward for the NeoForge event beside it is the second delivery the stand-down exists to skip, proved from either
	 * order and recorded when the hook posts only sometimes.
	 */
	@Test
	void aHookThatPostsItsEventsOneInstanceIsANativeDeliveryToo(@TempDir Path work) throws Exception {
		Map<String, byte[]> classes = classes(work);
		World before = world(classes, false);
		assertArrayEquals(new int[] {1, 1}, before.run("sharedFirst"), "premise: the bridge forwards beside the native post");
		World after = world(classes, true);
		Object shared = after.type("late/forge/ForgeTouch").getField("SHARED").get(null);
		Object sharedWho = shared.getClass().getField("who").get(shared);
		for (String caller : List.of("sharedFirst", "sharedAfter")) {
			int nativeBefore = after.count("late/forge/Factory", sharedWho);
			assertArrayEquals(new int[] {0, 1}, after.run(caller), caller + ": the native post was the delivery, so the bridge stood down");
			assertEquals(nativeBefore + 1, after.count("late/forge/Factory", sharedWho), caller + ": and the native post happened");
		}
		assertArrayEquals(new int[] {0, 1}, after.run("sharedIf", true), "the conditional hook posted its instance: recorded, covered");
		assertArrayEquals(new int[] {1, 1}, after.run("sharedIf", false), "it did not: the forward is the only delivery");
	}

	/**
	 * The merged system-chat listener's shape: NeoForge's hook first, then checks, then MinecraftForge's hook. Where every
	 * check that can skip MinecraftForge's call is one its own body has too, the native call is the delivery MinecraftForge
	 * itself makes: once where the checks pass, none where they fail. Where the caller leaves on a way only it has — an
	 * exit NeoForge added, or a vanilla branch MinecraftForge's body goes on from to its call — the bridge delivers there,
	 * once. A cancelled NeoForge event is not forwarded on any way out, and a caller with no MinecraftForge body to read
	 * keeps its forward.
	 */
	@Test
	void aNativeCallTheCallerMayBypassIsTheDeliveryWhereMinecraftForgesOwnBodyBypassesItToo(@TempDir Path work) throws Exception {
		Map<String, byte[]> classes = classes(work);
		var forge = forgeBodies(work);
		World before = world(classes, false);
		assertArrayEquals(new int[] {2, 1}, before.run("guardedAfter", new Object(), false), "premise: the forward beside the native call");

		World after = world(classes, true, forge);
		Object first = new Object();
		assertArrayEquals(new int[] {1, 1}, after.runFor(first, "guardedAfter", new Object(), false),
				"the check both bodies have passes: the native call is the one delivery");
		int excused = excused(after);
		Object guarded = new Object();
		assertArrayEquals(new int[] {0, 1}, after.runFor(guarded, "guardedAfter", null, false),
				"the check fails: MinecraftForge's own body would not deliver, and the bridge does not either");
		assertEquals(excused + 1, excused(after), "the stand-down was settled as excused there");
		Object exit = new Object();
		assertArrayEquals(new int[] {1, 1}, after.runFor(exit, "guardedAfter", new Object(), true),
				"NeoForge's own exit: the bridge delivers on that way out, once");
		assertEquals(1, after.count("late/forge/Factory", first), "what an earlier invocation stood down for is never asked for again");
		assertEquals(0, after.count("late/forge/Factory", guarded));
		// The documented limit of a way out only the merged game has: checks behind it are not run there, so the event
		// is delivered on it even when a check MinecraftForge's body would have made after it would have failed.
		assertArrayEquals(new int[] {1, 1}, after.run("guardedAfter", (Object) null, true));

		assertArrayEquals(new int[] {1, 1}, after.run("vanillaTurnedExit", true),
				"a vanilla branch MinecraftForge's body continues through to its call: delivered on the merged game's exit");
		assertArrayEquals(new int[] {1, 1}, after.run("vanillaTurnedExit", false), "and natively otherwise");

		assertArrayEquals(new int[] {1, 1}, after.run("cancelledExit", new Object()));
		assertArrayEquals(new int[] {0, 1}, after.run("cancelledExit", (Object) null));
		after.listeners("late/neo/Hooks").add(0, event -> {
			try {
				event.getClass().getField("canceled").setBoolean(event, true);
			} catch (ReflectiveOperationException failure) {
				throw new RuntimeException(failure);
			}
		});
		assertArrayEquals(new int[] {0, 1}, after.run("cancelledExit", new Object()),
				"NeoForge's exit on its own cancel: a cancelled event is forwarded on no way out");
		after.listeners("late/neo/Hooks").remove(0);

		assertArrayEquals(new int[] {2, 1}, after.run("unjudged", new Object()),
				"the control: no MinecraftForge body to judge against, so the forward is kept beside the native call");
		assertArrayEquals(new int[] {1, 1}, after.run("unjudged", (Object) null));
	}

	private static int excused(World world) throws Exception {
		return world.type("late/bridge/Bridge").getField("excused").getInt(null);
	}

	@Test
	void theDifferentBranchCallerIsNotRewrittenAtAll(@TempDir Path work) throws Exception {
		Map<String, byte[]> classes = classes(work);
		byte[] after = injector(classes).transform("late.game.Callers", classes.get("late/game/Callers"), null);
		ClassNode node = new ClassNode();
		new ClassReader(after).accept(node, 0);
		for (MethodNode method : node.methods) {
			if (!method.name.equals("branches") && !method.name.equals("neoOnly")) continue;
			for (AbstractInsnNode instruction : method.instructions) {
				assertFalse(instruction instanceof MethodInsnNode call && call.name.startsWith("forbric$native"),
						method.name + " has no MinecraftForge delivery on the NeoForge one's path, so nothing is wrapped");
			}
		}
	}

	@Test
	void aNestedEventOfTheSameClassKeepsItsOwnForward(@TempDir Path work) throws Exception {
		World after = world(classes(work), true);
		Object nested = new Object();
		boolean[] once = {false};
		after.listeners("late/neo/Hooks").add(0, event -> {
			if (once[0]) return;
			once[0] = true;
			try {
				after.call("neoOnly", nested);
			} catch (Exception failure) {
				throw new RuntimeException(failure);
			}
		});
		assertArrayEquals(new int[] {1, 1}, after.run("forgeFirst"), "the outer event had its native delivery");
		assertEquals(1, after.count("late/forge/Factory", nested), "the nested one, constructed by a listener, did not");
		after.listeners("late/neo/Hooks").remove(0);
		assertArrayEquals(new int[] {1, 1}, after.run("neoOnly"), "nothing leaks into the next independent event");
	}

	@Test
	void aCancellationIsStillCarriedAndANativeOneStillStops(@TempDir Path work) throws Exception {
		World after = world(classes(work), true);
		after.listeners("late/forge/Factory").add(event -> {
			try {
				event.getClass().getField("canceled").setBoolean(event, true);
			} catch (ReflectiveOperationException failure) {
				throw new RuntimeException(failure);
			}
		});
		Object who = new Object();
		assertEquals(Boolean.TRUE, after.call("neoOnly", who), "the forward still carries a MinecraftForge veto back");
		Object first = new Object();
		assertEquals(Boolean.TRUE, after.call("cancelledFirst", first));
		assertEquals(1, after.count("late/forge/Factory", first));
		assertEquals(0, after.count("late/neo/Hooks", first), "the native veto returns before NeoForge is asked");
	}

	@Test
	void aThrowingListenerLeavesNoScopeOrRecordBehind(@TempDir Path work) throws Exception {
		World after = world(classes(work), true);
		IllegalStateException failure = new IllegalStateException("listener failure");
		after.listeners("late/neo/Hooks").add(0, event -> { throw failure; });
		InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> after.run("conditional", true));
		assertSame(failure, thrown.getCause());
		after.listeners("late/neo/Hooks").remove(0);
		assertArrayEquals(new int[] {1, 1}, after.run("neoOnly"), "the next independent event forwards");
		assertArrayEquals(new int[] {1, 1}, after.run("conditional", false), "and nothing recorded before the throw covers it");
	}
}
