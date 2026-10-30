package net.forbric.kernel.boot;

import net.forbric.kernel.TestFixtures;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import net.forbric.kernel.transform.EventChainAuditInjector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import net.forbric.kernel.ExpectCaught;

/**
 * The audit on the REAL buses: NeoForge's EventBus and MinecraftForge's bus records from the staged runtime jars,
 * rewritten by {@link EventChainAuditInjector}, with a NeoForge listener standing in for a kernel bridge.
 */
@ResourceLock("system-properties")
class EventChainAuditTest {
	private static final String NEO_PROBE = "probe.NeoProbe", FORGE_PROBE = "probe.ForgeProbe", MOD_CODE = "probe.ModCode",
			GAME_CODE = "probe.GameCode", NEO_CHILD = "probe.NeoChild", FORGE_CHILD = "probe.ForgeChild";
	private static final String FORGE_INTERNAL = FORGE_PROBE.replace('.', '/');
	private Object neoBus, forgeBus, forgeChildBus;
	private Class<?> neoProbe, forgeProbe, neoChild, forgeChild;
	private Method post, fire, setCanceled;

	@BeforeEach void buses() throws Exception {
		System.setProperty(EventChainAudit.PROPERTY, "unused-in-tests.json");
		EventChainAudit.reset();
		Path staged = Path.of(System.getProperty("forbric.stagedRoot"));
		TestFixtures.requireFiles(TestFixtures.Fixture.STAGED, "staged carriers", staged.resolve(TestFixtures.NEO_CARRIER_JAR),
				staged.resolve(TestFixtures.FORGE_CARRIER_JAR));
		String log4j = System.getProperty("forbric.log4jApiForTests", "");
		assertFalse(log4j.isBlank(), "the build passes the log4j-api jar the real buses need");
		var loader = new AuditedBuses(List.of(staged.resolve(TestFixtures.NEO_CARRIER_JAR), staged.resolve(TestFixtures.FORGE_CARRIER_JAR), Path.of(log4j)));
		neoProbe = loader.loadClass(NEO_PROBE); forgeProbe = loader.loadClass(FORGE_PROBE);
		neoChild = loader.loadClass(NEO_CHILD); forgeChild = loader.loadClass(FORGE_CHILD);
		neoBus = loader.loadClass("net.neoforged.bus.api.BusBuilder").getMethod("builder").invoke(null);
		neoBus = neoBus.getClass().getMethod("build").invoke(neoBus);
		Class<?> cancellableBus = loader.loadClass("net.minecraftforge.eventbus.api.bus.CancellableEventBus");
		forgeBus = cancellableBus.getMethod("create", Class.class).invoke(null, forgeProbe);
		forgeChildBus = cancellableBus.getMethod("create", Class.class).invoke(null, forgeChild);
		post = cancellableBus.getMethod("post", loader.loadClass("net.minecraftforge.eventbus.internal.Event"));
		fire = cancellableBus.getMethod("fire", loader.loadClass("net.minecraftforge.eventbus.internal.Event"));
		setCanceled = loader.loadClass("net.neoforged.bus.api.ICancellableEvent").getMethod("setCanceled", boolean.class);
	}

	@AfterEach void off() { System.clearProperty(EventChainAudit.PROPERTY); EventChainAudit.reset(); }

	@Test void aForwardedCancelThatReachesTheOuterEventIsCarried() throws Exception {
		forgeListener(true); bridge(1, true, false);
		postNeo();
		var pair = pair();
		assertEquals(1, pair.forwardedOnce.sum()); assertEquals(1, pair.innerCancelled.sum()); assertEquals(1, pair.cancelCarried.sum());
		assertTrue(EventChainAudit.violations().clean(), EventChainAudit.json());
	}

	@Test void aForwardedCancelTheBridgeDropsIsLost() throws Exception {
		forgeListener(true); bridge(1, false, false);
		postNeo();
		assertEquals(1, pair().cancelLost.sum());
		assertEquals(1, EventChainAudit.violations().cancelLost());
	}

	@Test void aBridgeInstalledTwiceIsDoubleDelivery() throws Exception {
		forgeListener(false); bridge(1, true, false); bridge(1, true, false);
		postNeo();
		assertEquals(1, pair().duplicated.sum());
		assertEquals(1, EventChainAudit.violations().duplicated());
	}

	@Test void aListenerThatAlwaysFansOutIsNotDoubleDelivery() throws Exception {
		forgeListener(false); bridge(5, true, true);
		postNeo(); postNeo(); postNeo();
		assertEquals(3, pair().multiInvocations.sum());
		assertTrue(EventChainAudit.violations().clean(), EventChainAudit.json());
	}

	@Test void aOneToOneForwardThatSometimesPostsTwiceDrifts() throws Exception {
		forgeListener(false);
		int[] forwards = {1};
		addNeo(event -> { for (int i = 0; i < forwards[0]; i++) invoke(post, forgeBus, forgeEvent()); });
		postNeo(); postNeo(); postNeo(); forwards[0] = 2; postNeo();
		assertEquals(1, EventChainAudit.violations().multiplicityDrift(), EventChainAudit.json());
		assertEquals(0, EventChainAudit.violations().duplicated());
	}

	@Test void aPostMadeByModCodeInsideTheDispatchIsIncidental() throws Exception {
		forgeListener(false);
		Method modPost = neoProbe.getClassLoader().loadClass(MOD_CODE).getMethod("post", Object.class, Object.class);
		addNeo(event -> invoke(modPost, null, forgeBus, forgeEvent()));
		postNeo();
		assertNull(EventChainAudit.pair("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE), EventChainAudit.json());
		assertEquals(1, EventChainAudit.incidental("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE));
	}

	@Test void aThrowingForwardIsAnInnerFailureAndStillPropagates() throws Exception {
		Predicate<Object> thrower = event -> { throw new IllegalStateException("listener failed"); };
		forgeBus.getClass().getMethod("addListener", Predicate.class).invoke(forgeBus, thrower);
		bridge(1, true, false);
		assertThrows(Exception.class, this::postNeo);
		assertEquals(1, pair().innerFailures.sum());
		assertEquals(0, EventChainAudit.violations().unbalanced(), "a throwing dispatch still leaves every frame it entered");
	}

	@Test void onceForwardedAnUnforwardedPostIsCounted() throws Exception {
		forgeListener(false);
		boolean[] skip = {false};
		Consumer<Object> bridge = event -> { if (!skip[0]) invoke(post, forgeBus, forgeEvent()); };
		addNeo(bridge);
		postNeo(); skip[0] = true; postNeo();
		var outer = EventChainAudit.outer("NEO:" + NEO_PROBE);
		assertEquals(2, outer.posts.sum()); assertEquals(1, outer.withoutForward.sum());
	}

	/**
	 * A bridge on the guarded bus that stands down because the merged game delivered MinecraftForge's event itself on
	 * the NeoForge event's path forwards nothing: no pair, and a native delivery credited to that NeoForge event
	 * instead. An event outside any such scope is forwarded as before.
	 */
	@Test void aBridgeThatStandsDownForANativeDeliveryIsCreditedWithIt() throws Exception {
		forgeListener(false);
		addNeo(new GameEventMultiplexer.StandDownListener(event -> invoke(post, forgeBus, forgeEvent()), Set.of(FORGE_INTERNAL)));
		Object covered = neoProbe.getConstructor().newInstance();
		try (net.forbric.api.NativeEventDelivery.Scope scope = net.forbric.api.NativeEventDelivery.begin(FORGE_INTERNAL, null, "")) {
			scope.capture(covered);
			postNeo(covered);
		}
		assertNull(EventChainAudit.pair("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE), EventChainAudit.json());
		var natively = EventChainAudit.nativePair("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE);
		assertNotNull(natively, EventChainAudit.json());
		assertEquals(1, natively.outerPosts.sum());
		assertEquals(0, natively.alsoForwarded.sum());
		assertEquals(0, natively.observed.sum(),
				"no MinecraftForge dispatch of the event happened beside it: the stand-down is the bridge's word only");
		assertTrue(EventChainAudit.violations().clean(), EventChainAudit.json());
		assertTrue(EventChainAudit.json().contains("\"natives\": [\n    {\"outer\": \"NEO:" + NEO_PROBE + "\", \"inner\": \"FORGE:"
				+ FORGE_PROBE + "\", \"outerPosts\": 1, \"observed\": 0, \"alsoForwarded\": 0, \"excused\": 0, \"deferred\": 0, "
				+ "\"deferredDispatched\": 0, \"deferredFailed\": 0, \"deferredCancelled\": 0}"), EventChainAudit.json());

		postNeo();
		assertEquals(1, pair().forwardedOnce.sum(), "outside the scope the same bridge forwards");
		assertEquals(1, natively.outerPosts.sum(), "and that post is no native delivery");
	}

	/**
	 * A bridge that forwards one NeoForge event as one of several MinecraftForge events (chat: player, system or other)
	 * stands down for the one the game delivers natively, and only that one is credited.
	 */
	@Test void aBridgeWithSeveralEventsStandsDownForTheOneDeliveredNatively() throws Exception {
		forgeListener(false);
		String other = "probe/ForgeOther";
		addNeo(new GameEventMultiplexer.StandDownListener(event -> invoke(post, forgeBus, forgeEvent()), Set.of(FORGE_INTERNAL, other)));
		game("neoThenForge", covered(), forgeEvent());
		var natively = EventChainAudit.nativePair("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE);
		assertEquals(1, natively.outerPosts.sum(), EventChainAudit.json());
		assertEquals(1, natively.observed.sum(), EventChainAudit.json());
		assertNull(EventChainAudit.nativePair("NEO:" + NEO_PROBE, "FORGE:probe.ForgeOther"),
				"the bridge's other event was not delivered natively, so nothing stood down for it: " + EventChainAudit.json());
		assertTrue(EventChainAudit.violations().clean(), EventChainAudit.json());
	}

	/** The negative control: one bridge stands down for the native delivery while another forwards it anyway. */
	@Test void aNativeDeliveryTheKernelForwardsAsWellIsDeliveredTwice() throws Exception {
		forgeListener(false);
		addNeo(new GameEventMultiplexer.StandDownListener(event -> invoke(post, forgeBus, forgeEvent()), Set.of(FORGE_INTERNAL)));
		bridge(1, true, false);
		Object covered = neoProbe.getConstructor().newInstance();
		try (net.forbric.api.NativeEventDelivery.Scope scope = net.forbric.api.NativeEventDelivery.begin(FORGE_INTERNAL, null, "")) {
			scope.capture(covered);
			postNeo(covered);
		}
		assertEquals(1, EventChainAudit.nativePair("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE).alsoForwarded.sum(), EventChainAudit.json());
		assertEquals(1, EventChainAudit.violations().nativeAndForwarded());
		assertFalse(EventChainAudit.violations().clean());
	}

	/**
	 * The game's own MinecraftForge dispatch beside a stand-down — posted by the same game method after the NeoForge
	 * one, the order a delivery proved from the caller's control flow has — is what makes the stand-down observed.
	 */
	@Test void aStandDownBesideTheGamesOwnDispatchIsObserved() throws Exception {
		forgeListener(false);
		addNeo(standDown());
		for (int turn = 0; turn < 3; turn++) game("neoThenForge", covered(), forgeEvent());
		var natively = EventChainAudit.nativePair("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE);
		assertEquals(3, natively.outerPosts.sum(), EventChainAudit.json());
		assertEquals(3, natively.observed.sum(), "each turn of the game method pairs its own two dispatches: " + EventChainAudit.json());
		assertNull(EventChainAudit.pair("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE));
		assertTrue(EventChainAudit.violations().clean(), EventChainAudit.json());
		assertTrue(EventChainAudit.json().contains("{\"event\": \"FORGE:" + FORGE_PROBE + "\", \"posts\": 3, \"asForward\": 0"),
				"the report carries the inner event's dispatches that were not kernel forwards: " + EventChainAudit.json());
	}

	/** The same where MinecraftForge's call comes first in the method — a delivery recorded as it happened. */
	@Test void aStandDownAfterTheGamesOwnDispatchIsObserved() throws Exception {
		forgeListener(false);
		addNeo(standDown());
		for (int turn = 0; turn < 3; turn++) game("forgeThenNeo", covered(), forgeEvent());
		var natively = EventChainAudit.nativePair("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE);
		assertEquals(3, natively.observed.sum(), "a Forge dispatch pairs with the NeoForge one after it, never the turn before's: "
				+ EventChainAudit.json());
		assertTrue(EventChainAudit.violations().clean(), EventChainAudit.json());
	}

	/**
	 * The defect a stand-down count cannot see: nothing recognised the game's MinecraftForge call, so no scope opened, the
	 * bridge forwarded, and the game posted its own as well. The pair looks like a clean forward; beside it is the native
	 * dispatch from the same method, so the forward was a second delivery.
	 */
	@Test void aForwardTheGameAlsoDeliveredItselfIsDeliveredTwice() throws Exception {
		forgeListener(false);
		bridge(1, true, false);
		game("neoThenForge", neoProbe.getConstructor().newInstance(), forgeEvent());
		assertEquals(1, pair().forwardedOnce.sum());
		assertEquals(1, pair().withNative.sum(), EventChainAudit.json());
		assertEquals(1, EventChainAudit.violations().nativeAndForwarded());
		assertFalse(EventChainAudit.violations().clean());

		EventChainAudit.reset();
		game("forgeThenNeo", neoProbe.getConstructor().newInstance(), forgeEvent());
		assertEquals(1, pair().withNative.sum(), "in either order: " + EventChainAudit.json());
	}

	/**
	 * Controls for the pairing: a MinecraftForge dispatch from another method, a NeoForge forward and a MinecraftForge
	 * dispatch the same method makes on different turns of a loop, and a forward with nothing beside it are not
	 * double delivery.
	 */
	@Test void dispatchesThatAreNotOneDeliveryAreNotPaired() throws Exception {
		forgeListener(false);
		bridge(1, true, false);
		game("neoOnly", neoProbe.getConstructor().newInstance(), null);
		game("forgeOnly", null, forgeEvent());
		game("neoOnly", neoProbe.getConstructor().newInstance(), null);
		assertEquals(2, pair().forwardedOnce.sum());
		assertEquals(0, pair().withNative.sum(), "a native dispatch from another method is another occurrence: " + EventChainAudit.json());
		assertTrue(EventChainAudit.violations().clean(), EventChainAudit.json());
	}

	/**
	 * One method, MinecraftForge's call first: a turn where it posted and the bridge stood down, a turn where it did not
	 * and the bridge forwarded, and another turn like the first. The third turn's MinecraftForge dispatch comes right
	 * after the second turn's forward, but from the earlier call site: it starts a new turn, not a second delivery of
	 * the forward's event.
	 */
	@Test void aLaterTurnsDispatchIsNotPairedWithAnEarlierTurnsForward() throws Exception {
		forgeListener(false);
		addNeo(standDown());
		game("forgeThenNeo", covered(), forgeEvent());
		game("forgeThenNeo", neoProbe.getConstructor().newInstance(), null);
		game("forgeThenNeo", covered(), forgeEvent());
		assertEquals(1, pair().forwardedOnce.sum(), EventChainAudit.json());
		assertEquals(0, pair().withNative.sum(), "the forward pairs with no later turn's dispatch: " + EventChainAudit.json());
		assertEquals(2, EventChainAudit.nativePair("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE).observed.sum(), EventChainAudit.json());
		assertTrue(EventChainAudit.violations().clean(), EventChainAudit.json());
	}

	/**
	 * A stand-down for a native call the game may still bypass owes its forward, and the game settles it where it leaves
	 * that call's path: excused where MinecraftForge's own body bypasses the call too, forwarded then where only the
	 * merged game does — and that late MinecraftForge dispatch is the kernel's forward, not one of the game's own.
	 */
	@Test void aStandDownTheGameBypassesIsExcusedOrForwardedLate() throws Exception {
		forgeListener(false);
		addNeo(standDown());
		Object shared = neoProbe.getConstructor().newInstance();
		var caller = net.forbric.api.NativeEventDelivery.enterNative("caller");
		try (net.forbric.api.NativeEventDelivery.Scope scope = net.forbric.api.NativeEventDelivery.begin("", caller, "", FORGE_INTERNAL)) {
			scope.capture(shared);
			postNeo(shared);
		}
		net.forbric.api.NativeEventDelivery.bypassed(caller, FORGE_INTERNAL, true);
		var natively = EventChainAudit.nativePair("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE);
		assertEquals(1, natively.excused.sum(), EventChainAudit.json());
		assertNull(EventChainAudit.outer("FORGE:" + FORGE_PROBE), "nothing was delivered on the shared way out");

		Object neoOnly = neoProbe.getConstructor().newInstance();
		caller = net.forbric.api.NativeEventDelivery.enterNative("caller");
		try (net.forbric.api.NativeEventDelivery.Scope scope = net.forbric.api.NativeEventDelivery.begin("", caller, "", FORGE_INTERNAL)) {
			scope.capture(neoOnly);
			postNeo(neoOnly);
		}
		net.forbric.api.NativeEventDelivery.bypassed(caller, FORGE_INTERNAL, false);
		net.forbric.api.NativeEventDelivery.bypassed(caller, FORGE_INTERNAL, false);
		assertEquals(1, natively.deferred.sum(), "forwarded once on the NeoForge-only way out: " + EventChainAudit.json());
		var forge = EventChainAudit.outer("FORGE:" + FORGE_PROBE);
		assertEquals(1, forge.posts.sum());
		assertEquals(1, forge.asForward.sum(), "the late dispatch is the kernel's forward: " + EventChainAudit.json());
		assertEquals(2, natively.outerPosts.sum());
		assertEquals(0, natively.observed.sum());
		assertEquals(1, natively.deferredDispatched.sum(), "the late forward's own dispatch is seen on the pair it was owed for");
		assertTrue(EventChainAudit.violations().clean(), EventChainAudit.json());
		assertTrue(EventChainAudit.json().contains("\"alsoForwarded\": 0, \"excused\": 1, \"deferred\": 1, \"deferredDispatched\": 1, "
				+ "\"deferredFailed\": 0, \"deferredCancelled\": 0}"), EventChainAudit.json());
	}

	/**
	 * A late forward is the kernel's word that it owed one; what it dispatched is what counts. One that dispatches
	 * nothing, one that dispatches twice, and one whose dispatch throws are each a violation, though each counts as
	 * settled by the kernel's own account.
	 */
	// The "throws" shape's forward is the test's own, not a bridge that reports its failure: NativeEventDelivery does.
	@ExpectCaught("listener failed")
	@Test void aLateForwardThatDispatchesOtherThanOnceOrThrowsIsAViolation() throws Exception {
		for (String shape : List.of("nothing", "twice", "throws")) {
			EventChainAudit.reset();
			Consumer<Object> forward = switch (shape) {
				case "nothing" -> event -> { };
				case "twice" -> event -> { invoke(post, forgeBus, forgeEvent()); invoke(post, forgeBus, forgeEvent()); };
				default -> event -> invoke(post, forgeBus, forgeEvent());
			};
			if (shape.equals("throws")) {
				Predicate<Object> thrower = event -> { throw new IllegalStateException("listener failed"); };
				forgeBus.getClass().getMethod("addListener", Predicate.class).invoke(forgeBus, thrower);
			}
			addNeo(new GameEventMultiplexer.StandDownListener(forward, Set.of(FORGE_INTERNAL)));
			Object event = neoProbe.getConstructor().newInstance();
			var caller = net.forbric.api.NativeEventDelivery.enterNative("caller");
			try (net.forbric.api.NativeEventDelivery.Scope scope = net.forbric.api.NativeEventDelivery.begin("", caller, "", FORGE_INTERNAL)) {
				scope.capture(event);
				postNeo(event);
			}
			net.forbric.api.NativeEventDelivery.bypassed(caller, FORGE_INTERNAL, false);
			var natively = EventChainAudit.nativePair("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE);
			assertEquals(1, natively.deferred.sum(), shape + ": the kernel's account says one was owed and forwarded: " + EventChainAudit.json());
			var v = EventChainAudit.violations();
			assertFalse(v.clean(), shape + ": " + EventChainAudit.json());
			if (shape.equals("throws")) {
				assertEquals(1, v.deferredFailures(), EventChainAudit.json());
				assertEquals(0, v.deferredUnmatched(), "it did dispatch, once: " + EventChainAudit.json());
			} else {
				assertEquals(1, v.deferredUnmatched(), shape + ": " + EventChainAudit.json());
				assertEquals(0, v.deferredFailures(), EventChainAudit.json());
			}
			buses();
		}
	}

	/**
	 * A mod-bus post dispatches one event to every mod's bus from one call site: one post, however many dispatches, and
	 * the bridge on one mod's bus that stands down for it pairs with the game's own MinecraftForge dispatch as one.
	 */
	@Test void aModBusFanOutIsOnePostAndPairsOnce() throws Exception {
		forgeListener(false);
		Object[] buses = {newNeoBus(), neoBus, newNeoBus()};
		addNeo(standDown());
		for (int turn = 0; turn < 2; turn++) {
			Method fan = neoProbe.getClassLoader().loadClass(GAME_CODE).getMethod("fanOutThenForge", Object[].class, Object.class, Object.class, Object.class);
			fan.invoke(null, buses, covered(), forgeBus, forgeEvent());
		}
		var outer = EventChainAudit.outer("NEO:" + NEO_PROBE);
		assertEquals(6, outer.posts.sum(), "three mods' dispatches per post");
		assertEquals(2, outer.distinct.sum(), "and two posts: " + EventChainAudit.json());
		var natively = EventChainAudit.nativePair("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE);
		assertEquals(2, natively.outerPosts.sum());
		assertEquals(2, natively.observed.sum(), "each post pairs with the game's own dispatch beside it: " + EventChainAudit.json());
		assertTrue(EventChainAudit.violations().clean(), EventChainAudit.json());
	}

	/**
	 * Each family's own listener posts a second event inside its first (a login registering client commands). With the
	 * game delivering MinecraftForge's first event natively, MinecraftForge's own listener has delivered the second inside
	 * it: forwarding NeoForge's second as well is a second delivery, standing down for it is observed.
	 */
	@Test void aCascadeUnderTheGamesOwnDispatchPairsWithWhatNeoForgesListenerPosted() throws Exception {
		forgeCascade();
		addNeo(standDown());
		addNeoChildCascade();
		bridgeChild();
		game("forgeThenNeo", covered(), forgeEvent());
		assertEquals(1, EventChainAudit.nativePair("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE).observed.sum(), EventChainAudit.json());
		var child = EventChainAudit.pair("NEO:" + NEO_CHILD, "FORGE:" + FORGE_CHILD);
		assertNotNull(child, EventChainAudit.json());
		assertEquals(1, child.withNative.sum(), "the cascade's forward was a second delivery: " + EventChainAudit.json());
		assertEquals(1, EventChainAudit.violations().nativeAndForwarded());
	}

	@Test void standingDownForACascadeTheGamesOwnDispatchDeliveredIsObserved() throws Exception {
		forgeCascade();
		addNeo(standDown());
		addNeoChildCascade();
		bridgeChildStandsDown();
		game("forgeThenNeo", covered(), forgeEvent());
		var stood = EventChainAudit.nativePair("NEO:" + NEO_CHILD, "FORGE:" + FORGE_CHILD);
		assertNotNull(stood, EventChainAudit.json());
		assertEquals(1, stood.observed.sum(), "standing down for it is observed beside MinecraftForge's own: " + EventChainAudit.json());
		assertTrue(EventChainAudit.violations().clean(), EventChainAudit.json());
	}

	/** The same under a forward: what MinecraftForge's own listener posts inside the forwarded event is native too. */
	@Test void aCascadeUnderAForwardPairsWithWhatNeoForgesListenerPosted() throws Exception {
		forgeCascade();
		addNeoChildCascade();
		bridgeChild();
		bridge(1, true, false);
		postNeo();
		var child = EventChainAudit.pair("NEO:" + NEO_CHILD, "FORGE:" + FORGE_CHILD);
		assertNotNull(child, EventChainAudit.json());
		assertEquals(1, child.withNative.sum(), EventChainAudit.json());
		assertEquals(1, EventChainAudit.violations().nativeAndForwarded());
	}

	/** MinecraftForge's own listener of the first event: posts the second one, as code that is not the kernel's. */
	private void forgeCascade() throws Exception {
		Method modPost = neoProbe.getClassLoader().loadClass(MOD_CODE).getMethod("post", Object.class, Object.class);
		Predicate<Object> listener = event -> { invoke(modPost, null, forgeChildBus, forgeChildEvent()); return false; };
		forgeBus.getClass().getMethod("addListener", Predicate.class).invoke(forgeBus, listener);
		Predicate<Object> childListener = event -> false;
		forgeChildBus.getClass().getMethod("addListener", Predicate.class).invoke(forgeChildBus, childListener);
	}

	/** NeoForge's own listener of the first event, ahead of the bridges: posts the second one. */
	private void addNeoChildCascade() throws Exception {
		Class<?> priority = neoBus.getClass().getClassLoader().loadClass("net.neoforged.bus.api.EventPriority");
		Consumer<Object> listener = event -> { try { postNeo(neoChild.getConstructor().newInstance()); } catch (Exception e) { throw new IllegalStateException(e); } };
		neoBus.getClass().getMethod("addListener", priority, boolean.class, Class.class, Consumer.class)
				.invoke(neoBus, priority.getField("HIGHEST").get(null), false, neoProbe, listener);
	}

	/** The second event's bridge: forwards it, or (standing down) is told it was delivered natively. */
	private void bridgeChild() throws Exception {
		addNeoChild(event -> invoke(post, forgeChildBus, forgeChildEvent()));
	}

	private void bridgeChildStandsDown() throws Exception {
		addNeoChild(event -> EventChainAudit.nativelyDelivered(event, Set.of(FORGE_CHILD.replace('.', '/'))));
	}

	private void addNeoChild(Consumer<Object> listener) throws Exception {
		Class<?> priority = neoBus.getClass().getClassLoader().loadClass("net.neoforged.bus.api.EventPriority");
		neoBus.getClass().getMethod("addListener", priority, boolean.class, Class.class, Consumer.class)
				.invoke(neoBus, priority.getField("LOWEST").get(null), false, neoChild, listener);
	}

	private Object forgeChildEvent() {
		try { return forgeChild.getConstructor().newInstance(); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
	}

	private Object newNeoBus() throws Exception {
		Object builder = neoBus.getClass().getClassLoader().loadClass("net.neoforged.bus.api.BusBuilder").getMethod("builder").invoke(null);
		return builder.getClass().getMethod("build").invoke(builder);
	}

	@Test void nothingCrossesWhenNoBridgeIsInstalled() throws Exception {
		forgeListener(false);
		postNeo(); invoke(post, forgeBus, forgeEvent());
		assertEquals(0, EventChainAudit.pairCount());
		assertTrue(EventChainAudit.violations().clean());
	}

	private Consumer<Object> standDown() {
		return new GameEventMultiplexer.StandDownListener(event -> invoke(post, forgeBus, forgeEvent()), Set.of(FORGE_INTERNAL));
	}

	/** A NeoForge event a scope captured as the one a native MinecraftForge delivery covers; the scope is left open. */
	private Object covered() throws Exception {
		Object event = neoProbe.getConstructor().newInstance();
		scopes.add(net.forbric.api.NativeEventDelivery.begin(FORGE_INTERNAL, null, ""));
		scopes.get(scopes.size() - 1).capture(event);
		return event;
	}

	private final List<net.forbric.api.NativeEventDelivery.Scope> scopes = new ArrayList<>();

	@AfterEach void closeScopes() { for (int i = scopes.size() - 1; i >= 0; i--) scopes.get(i).close(); scopes.clear(); }

	/** Calls one of the game method stand-ins: each posts from its own two call sites, in its own order. */
	private void game(String method, Object neoEvent, Object forgeEvent) throws Exception {
		Method m = neoProbe.getClassLoader().loadClass(GAME_CODE).getMethod(method, Object.class, Object.class, Object.class, Object.class);
		m.invoke(null, neoBus, neoEvent, forgeBus, forgeEvent);
	}

	private EventChainAudit.Pair pair() {
		var pair = EventChainAudit.pair("NEO:" + NEO_PROBE, "FORGE:" + FORGE_PROBE);
		assertNotNull(pair, EventChainAudit.json());
		return pair;
	}

	private void forgeListener(boolean cancel) throws Exception {
		Predicate<Object> listener = event -> cancel;
		forgeBus.getClass().getMethod("addListener", Predicate.class).invoke(forgeBus, listener);
	}

	/** A NeoForge listener shaped like the kernel's bridges: post MinecraftForge's event, carry its cancel back. */
	private void bridge(int forwards, boolean carry, boolean viaFire) throws Exception {
		addNeo(event -> {
			for (int i = 0; i < forwards; i++) {
				Object forge = forgeEvent();
				boolean cancelled = viaFire ? false : (boolean) invoke(post, forgeBus, forge);
				if (viaFire) invoke(fire, forgeBus, forge);
				if (cancelled && carry) invoke(setCanceled, event, true);
			}
		});
	}

	private void addNeo(Consumer<Object> listener) throws Exception {
		Class<?> priority = neoBus.getClass().getClassLoader().loadClass("net.neoforged.bus.api.EventPriority");
		Object lowest = priority.getField("LOWEST").get(null);
		neoBus.getClass().getMethod("addListener", priority, boolean.class, Class.class, Consumer.class).invoke(neoBus, lowest, false, neoProbe, listener);
	}

	private void postNeo() throws Exception {
		postNeo(neoProbe.getConstructor().newInstance());
	}

	private void postNeo(Object event) throws Exception {
		Method neoPost = neoBus.getClass().getMethod("post", neoBus.getClass().getClassLoader().loadClass("net.neoforged.bus.api.Event"));
		neoPost.invoke(neoBus, event);
	}

	private Object forgeEvent() {
		try { return forgeProbe.getConstructor().newInstance(); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
	}

	private static Object invoke(Method method, Object target, Object... args) {
		try { return method.invoke(target, args); }
		catch (java.lang.reflect.InvocationTargetException thrown) {
			if (thrown.getCause() instanceof RuntimeException runtime) throw runtime;
			throw new IllegalStateException(thrown.getCause());
		} catch (IllegalAccessException e) { throw new IllegalStateException(e); }
	}

	/** Child-first over the two runtime jars, with the three bus classes rewritten and the two probe events generated. */
	private static final class AuditedBuses extends ClassLoader {
		private final List<JarFile> jars = new ArrayList<>();
		private final Map<String, byte[]> generated = new HashMap<>();
		AuditedBuses(List<Path> jarPaths) throws IOException {
			super(EventChainAuditTest.class.getClassLoader());
			// Multi-release: log4j-api keeps its Java 9+ StackLocator under META-INF/versions.
			for (Path path : jarPaths) jars.add(new JarFile(path.toFile(), true, java.util.zip.ZipFile.OPEN_READ, Runtime.version()));
			generated.put(NEO_PROBE, event("probe/NeoProbe", "net/neoforged/bus/api/Event", "net/neoforged/bus/api/ICancellableEvent"));
			generated.put(FORGE_PROBE, event("probe/ForgeProbe", "net/minecraftforge/eventbus/api/event/MutableEvent",
					"net/minecraftforge/eventbus/api/event/characteristic/Cancellable"));
			generated.put(NEO_CHILD, event("probe/NeoChild", "net/neoforged/bus/api/Event", "net/neoforged/bus/api/ICancellableEvent"));
			generated.put(FORGE_CHILD, event("probe/ForgeChild", "net/minecraftforge/eventbus/api/event/MutableEvent",
					"net/minecraftforge/eventbus/api/event/characteristic/Cancellable"));
			generated.put(MOD_CODE, modCode());
			generated.put(GAME_CODE, gameCode());
		}
		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded != null) return loaded;
				byte[] bytes = generated.get(name);
				if (bytes == null) bytes = fromJars(name);
				if (bytes == null) return super.loadClass(name, resolve);
				if (name.equals("net.neoforged.bus.EventBus")) bytes = EventChainAuditInjector.rewrite(bytes, true, false);
				if (name.equals("net.minecraftforge.eventbus.internal.CancellableEventBusImpl")) bytes = EventChainAuditInjector.rewrite(bytes, false, true);
				if (name.equals("net.minecraftforge.eventbus.internal.EventBusImpl")) bytes = EventChainAuditInjector.rewrite(bytes, false, false);
				return defineClass(name, bytes, 0, bytes.length);
			}
		}
		private byte[] fromJars(String name) {
			String entry = name.replace('.', '/') + ".class";
			for (JarFile jar : jars) {
				var found = jar.getJarEntry(entry);
				if (found == null) continue;
				try (var in = jar.getInputStream(found)) { return in.readAllBytes(); } catch (IOException e) { throw new IllegalStateException(e); }
			}
			return null;
		}
		/** A mod's own class posting to MinecraftForge's bus: not kernel code. */
		private static byte[] modCode() {
			ClassWriter writer = new ClassWriter(0);
			writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "probe/ModCode", null, "java/lang/Object", null);
			MethodVisitor post = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "post", "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
			post.visitCode();
			post.visitVarInsn(Opcodes.ALOAD, 0); post.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraftforge/eventbus/api/bus/CancellableEventBus");
			post.visitVarInsn(Opcodes.ALOAD, 1); post.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraftforge/eventbus/internal/Event");
			post.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/minecraftforge/eventbus/api/bus/CancellableEventBus", "post", "(Lnet/minecraftforge/eventbus/internal/Event;)Z", true);
			post.visitInsn(Opcodes.POP); post.visitInsn(Opcodes.RETURN); post.visitMaxs(2, 2); post.visitEnd(); writer.visitEnd();
			return writer.toByteArray();
		}
		/**
		 * A game class: each method posts the NeoForge event, MinecraftForge's, or both in its own order, from its own
		 * call sites — the shape of a merged method that kept one or both families' hooks.
		 */
		private static byte[] gameCode() {
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "probe/GameCode", null, "java/lang/Object", null);
			String desc = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";
			// A mod-bus post: one NeoForge event to every mod's bus from one call site, then MinecraftForge's own dispatch.
			MethodVisitor fan = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fanOutThenForge",
					"([Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
			fan.visitCode();
			fan.visitInsn(Opcodes.ICONST_0); fan.visitVarInsn(Opcodes.ISTORE, 4);
			org.objectweb.asm.Label head = new org.objectweb.asm.Label(), end = new org.objectweb.asm.Label();
			fan.visitLabel(head);
			fan.visitFrame(Opcodes.F_APPEND, 1, new Object[] {Opcodes.INTEGER}, 0, null);
			fan.visitVarInsn(Opcodes.ILOAD, 4); fan.visitVarInsn(Opcodes.ALOAD, 0); fan.visitInsn(Opcodes.ARRAYLENGTH);
			fan.visitJumpInsn(Opcodes.IF_ICMPGE, end);
			fan.visitVarInsn(Opcodes.ALOAD, 0); fan.visitVarInsn(Opcodes.ILOAD, 4); fan.visitInsn(Opcodes.AALOAD);
			fan.visitTypeInsn(Opcodes.CHECKCAST, "net/neoforged/bus/api/IEventBus");
			fan.visitVarInsn(Opcodes.ALOAD, 1); fan.visitTypeInsn(Opcodes.CHECKCAST, "net/neoforged/bus/api/Event");
			fan.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/neoforged/bus/api/IEventBus", "post",
					"(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;", true);
			fan.visitInsn(Opcodes.POP); fan.visitIincInsn(4, 1); fan.visitJumpInsn(Opcodes.GOTO, head);
			fan.visitLabel(end);
			fan.visitFrame(Opcodes.F_CHOP, 1, null, 0, null);
			fan.visitVarInsn(Opcodes.ALOAD, 2); fan.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraftforge/eventbus/api/bus/CancellableEventBus");
			fan.visitVarInsn(Opcodes.ALOAD, 3); fan.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraftforge/eventbus/internal/Event");
			fan.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/minecraftforge/eventbus/api/bus/CancellableEventBus", "post",
					"(Lnet/minecraftforge/eventbus/internal/Event;)Z", true);
			fan.visitInsn(Opcodes.POP); fan.visitInsn(Opcodes.RETURN); fan.visitMaxs(0, 0); fan.visitEnd();
			for (String name : List.of("neoThenForge", "forgeThenNeo", "neoOnly", "forgeOnly")) {
				MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
				mv.visitCode();
				for (String step : switch (name) {
					case "neoThenForge" -> List.of("neo", "forge");
					case "forgeThenNeo" -> List.of("forge", "neo");
					case "neoOnly" -> List.of("neo");
					default -> List.of("forge");
				}) {
					// A null event skips that family's post, as a hook that posts only under a condition does.
					org.objectweb.asm.Label skip = new org.objectweb.asm.Label();
					mv.visitVarInsn(Opcodes.ALOAD, step.equals("neo") ? 1 : 3);
					mv.visitJumpInsn(Opcodes.IFNULL, skip);
					if (step.equals("neo")) {
						mv.visitVarInsn(Opcodes.ALOAD, 0); mv.visitTypeInsn(Opcodes.CHECKCAST, "net/neoforged/bus/api/IEventBus");
						mv.visitVarInsn(Opcodes.ALOAD, 1); mv.visitTypeInsn(Opcodes.CHECKCAST, "net/neoforged/bus/api/Event");
						mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/neoforged/bus/api/IEventBus", "post",
								"(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;", true);
					} else {
						mv.visitVarInsn(Opcodes.ALOAD, 2); mv.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraftforge/eventbus/api/bus/CancellableEventBus");
						mv.visitVarInsn(Opcodes.ALOAD, 3); mv.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraftforge/eventbus/internal/Event");
						mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/minecraftforge/eventbus/api/bus/CancellableEventBus", "post",
								"(Lnet/minecraftforge/eventbus/internal/Event;)Z", true);
					}
					mv.visitInsn(Opcodes.POP);
					mv.visitLabel(skip);
					mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
				}
				mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0, 0); mv.visitEnd();
			}
			writer.visitEnd();
			return writer.toByteArray();
		}
		private static byte[] event(String name, String superName, String marker) {
			ClassWriter writer = new ClassWriter(0);
			writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, superName, new String[] {marker});
			MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
			init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0); init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
			init.visitInsn(Opcodes.RETURN); init.visitMaxs(1, 1); init.visitEnd(); writer.visitEnd();
			return writer.toByteArray();
		}
	}
}
