/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/**
 * MinecraftForge's drown event, posted from NeoForge's (KernelGameWorldEvents.installDrown), driven for real.
 *
 * <p>The merged game posts only NeoForge's drown event (CommonHooks.onLivingBreathe); MinecraftForge's hook, which
 * constructs its own inline, has no caller, so before the bridge a MinecraftForge mod's {@code LivingDrownEvent}
 * listener never ran (the air probe: 0 events on Forbric, one per drowning on MinecraftForge 26.2). Native
 * MinecraftForge posts it once the air is down to -20, always as {@code (entity, true, 2.0f, 8)}, with
 * {@code BUS.post} so a cancel is the post's result; a cancel or a body no longer drowning skips the reset and the
 * damage, and the damage is whatever the listeners leave; the bubble count is never read (ForgeHooks.onLivingBreathe bc
 * 198-282), so a changed one is not carried back — NeoForge's hook draws the body's random numbers per bubble, and
 * writing it would change that body's random sequence where natively the change does nothing. The scenarios are the
 * probe's canaries with the values native MinecraftForge 26.2 handed them; the controls are that a listener that changes
 * nothing writes nothing, that a changed bubble count writes nothing, and that a body NeoForge does not drown is not
 * handed to MinecraftForge at all.
 *
 * <p>The carry-back has no game types in its signature and is called directly; the bridge itself is driven on a real
 * NeoForge bus and MinecraftForge's real event bus, over the compiled game side and the staged game.
 */
class KernelDrownBridgeTest {
	private static final String NEO = "net.neoforged.neoforge.event.entity.living.LivingDrownEvent";
	private static final String FORGE = "net.minecraftforge.event.entity.living.LivingDrownEvent";

	// ---- the carry-back, called directly ----

	/** NeoForge's drown event as it stands, recording every write. */
	private static final class Neo {
		final List<String> writes = new ArrayList<>();
	}

	private static Object view(ClassLoader loader, Neo neo) throws Exception {
		Class<?> type = Class.forName("net.forbric.kernel.runtime.KernelGameWorldEvents$NeoDrown", true, loader);
		return Proxy.newProxyInstance(loader, new Class<?>[] {type}, (proxy, method, args) -> {
			switch (method.getName()) {
				case "setDrowning", "setDamage" -> neo.writes.add(method.getName().substring(3).toLowerCase() + "=" + args[0]);
				default -> throw new UnsupportedOperationException(method.getName());
			}
			return null;
		});
	}

	private static Class<?> drown(ClassLoader loader) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameWorldEvents$ForgeDrown", true, loader);
	}

	private static Object handed(ClassLoader loader, float damage, int bubbles) throws Exception {
		Method of = drown(loader).getDeclaredMethod("of", float.class, int.class);
		of.setAccessible(true);
		return of.invoke(null, damage, bubbles);
	}

	private static Object forge(ClassLoader loader, boolean drowning, float damage, int bubbles) throws Exception {
		var make = drown(loader).getDeclaredConstructor(boolean.class, float.class, int.class);
		make.setAccessible(true);
		return make.newInstance(drowning, damage, bubbles);
	}

	private static Object get(Object drown, String component) throws Exception {
		Method read = drown.getClass().getDeclaredMethod(component);
		read.setAccessible(true);
		return read.invoke(drown);
	}

	/** Runs the carry-back; returns whether it vetoed, and fills {@code neo.writes}. */
	private static boolean carry(ClassLoader loader, Neo neo, Object handed, Object after, boolean cancelled) throws Exception {
		Method carry = Class.forName("net.forbric.kernel.runtime.KernelGameWorldEvents", false, loader)
				.getDeclaredMethod("carryBack", Class.forName("net.forbric.kernel.runtime.KernelGameWorldEvents$NeoDrown", false, loader),
						drown(loader), drown(loader), boolean.class);
		carry.setAccessible(true);
		try {
			return (Boolean) carry.invoke(null, view(loader, neo), handed, after, cancelled);
		} catch (InvocationTargetException thrown) {
			throw (Exception) thrown.getCause();
		}
	}

	/** Negative control: a MinecraftForge listener that changes nothing writes nothing and vetoes nothing, whatever NeoForge handed it. */
	@Test void aListenerThatChangesNothingWritesNothing() throws Exception {
		try (URLClassLoader loader = StagedGameClassLoader.create(List.of())) {
			for (float damage : new float[] {2f, 0f, 5.5f, -1f, 0.5f})
				for (int bubbles : new int[] {8, 0, 3, -2}) {
					Neo neo = new Neo();
					Object handed = handed(loader, damage, bubbles);
					assertFalse(carry(loader, neo, handed, handed, false), "vetoed " + damage + "/" + bubbles);
					assertEquals(List.of(), neo.writes, "NeoForge " + damage + "/" + bubbles);
				}
		}
	}

	/** What MinecraftForge's listeners are handed: always a drowning body, as natively, with NeoForge's damage and bubbles. */
	@Test void aDrowningBodyIsHandedAsMinecraftForgeHandsIt() throws Exception {
		try (URLClassLoader loader = StagedGameClassLoader.create(List.of())) {
			Object vanilla = handed(loader, 2f, 8);
			assertEquals(true, get(vanilla, "drowning"));
			assertEquals(2f, get(vanilla, "damage"));
			assertEquals(8, get(vanilla, "bubbles"));
			Object changedByNeo = handed(loader, 4f, 2);
			assertEquals(true, get(changedByNeo, "drowning"));
			assertEquals(4f, get(changedByNeo, "damage"));
			assertEquals(2, get(changedByNeo, "bubbles"));
		}
	}

	/** drown-cancel: a cancelled answer is the veto alone, whatever else the listener set (natively 197→…→-45, no damage). */
	@Test void aCancelIsTheVetoAndNothingElse() throws Exception {
		try (URLClassLoader loader = StagedGameClassLoader.create(List.of())) {
			Neo neo = new Neo();
			assertTrue(carry(loader, neo, handed(loader, 2f, 8), forge(loader, false, 7f, 1), true));
			assertEquals(List.of(), neo.writes);
		}
	}

	/** drown-notdrowning / drown-damage3 / drown-damage0: each change MinecraftForge's hook reads is carried back on its own. */
	@Test void eachChangedValueIsCarriedBack() throws Exception {
		try (URLClassLoader loader = StagedGameClassLoader.create(List.of())) {
			Neo notDrowning = new Neo();
			assertFalse(carry(loader, notDrowning, handed(loader, 2f, 8), forge(loader, false, 2f, 8), false));
			assertEquals(List.of("drowning=false"), notDrowning.writes);
			Neo three = new Neo();
			assertFalse(carry(loader, three, handed(loader, 2f, 8), forge(loader, true, 3f, 8), false));
			assertEquals(List.of("damage=3.0"), three.writes);
			Neo zero = new Neo();
			assertFalse(carry(loader, zero, handed(loader, 2f, 8), forge(loader, true, 0f, 8), false));
			assertEquals(List.of("damage=0.0"), zero.writes);
			Neo all = new Neo();
			assertFalse(carry(loader, all, handed(loader, 4f, 2), forge(loader, false, 1f, 9), false));
			assertEquals(List.of("drowning=false", "damage=1.0"), all.writes);
		}
	}

	/**
	 * drown-bubbles0 / drown-bubbles50: a changed bubble count is not carried back, alone or beside a change that is. Native
	 * MinecraftForge never reads it, so nothing a MinecraftForge listener does to it may reach NeoForge's hook, which would
	 * draw six of the body's random numbers per bubble for it.
	 */
	@Test void aChangedBubbleCountIsNotCarriedBack() throws Exception {
		try (URLClassLoader loader = StagedGameClassLoader.create(List.of())) {
			for (int handedBubbles : new int[] {8, 2, 0})
				for (int after : new int[] {0, 1, 50, 1_000_000, -3}) {
					if (after == handedBubbles) continue;
					Neo neo = new Neo();
					assertFalse(carry(loader, neo, handed(loader, 2f, handedBubbles), forge(loader, true, 2f, after), false));
					assertEquals(List.of(), neo.writes, handedBubbles + " -> " + after);
				}
			Neo beside = new Neo();
			assertFalse(carry(loader, beside, handed(loader, 2f, 8), forge(loader, true, 3f, 0), false));
			assertEquals(List.of("damage=3.0"), beside.writes);
		}
	}

	// ---- the bridge, on real buses ----

	private static URLClassLoader game() throws Exception {
		return StagedGameClassLoader.create(List.of(Path.of("build/classes/java/main").toAbsolutePath().toUri().toURL()));
	}

	private static Object neoBus(ClassLoader cl) throws Exception {
		Object builder = cl.loadClass("net.neoforged.bus.api.BusBuilder").getMethod("builder").invoke(null);
		Object bus = builder.getClass().getMethod("build").invoke(builder);
		cl.loadClass("net.forbric.kernel.runtime.KernelGameWorldEvents").getMethod("installDrown", Object.class).invoke(null, bus);
		return bus;
	}

	private static Object neoEvent(ClassLoader cl, boolean drowning, float damage, int bubbles) throws Exception {
		return cl.loadClass(NEO).getConstructor(cl.loadClass("net.minecraft.world.entity.LivingEntity"), boolean.class, float.class, int.class)
				.newInstance(null, drowning, damage, bubbles);
	}

	private static void post(ClassLoader cl, Object bus, Object event) throws Exception {
		cl.loadClass("net.neoforged.bus.api.IEventBus").getMethod("post", cl.loadClass("net.neoforged.bus.api.Event")).invoke(bus, event);
	}

	/** NeoForge's event after the post: drowning, damage, bubbles, cancelled. */
	private static String state(ClassLoader cl, Object event) throws Exception {
		Class<?> type = cl.loadClass(NEO);
		return type.getMethod("isDrowning").invoke(event) + "/" + type.getMethod("getDamageAmount").invoke(event) + "/"
				+ type.getMethod("getBubbleCount").invoke(event) + "/"
				+ cl.loadClass("net.neoforged.bus.api.ICancellableEvent").getMethod("isCanceled").invoke(event);
	}

	/** A MinecraftForge listener on its real bus: records what it is handed, applies {@code change}, cancels when it returns true. */
	private static List<String> listen(ClassLoader cl, Predicate<Object> change) throws Exception {
		Class<?> forge = cl.loadClass(FORGE);
		List<String> seen = new ArrayList<>();
		Predicate<Object> listener = event -> {
			try {
				seen.add(forge.getMethod("isDrowning").invoke(event) + "/" + forge.getMethod("getDamageAmount").invoke(event) + "/"
						+ forge.getMethod("getBubbleCount").invoke(event));
			} catch (ReflectiveOperationException failure) {
				throw new AssertionError(failure);
			}
			return change.test(event);
		};
		cl.loadClass("net.minecraftforge.eventbus.api.bus.CancellableEventBus").getMethod("addListener", Predicate.class)
				.invoke(forge.getField("BUS").get(null), listener);
		return seen;
	}

	private static Predicate<Object> set(ClassLoader cl, String setter, Class<?> type, Object value) {
		return event -> {
			try {
				cl.loadClass(FORGE).getMethod(setter, type).invoke(event, value);
			} catch (ReflectiveOperationException failure) {
				throw new AssertionError(failure);
			}
			return false;
		};
	}

	/** drown-read: one MinecraftForge event per drowning, handed (true, 2.0, 8); a listener that only reads leaves NeoForge's event as it was. */
	@Test void aDrowningBodyReachesMinecraftForgeOnceAndAReadingListenerChangesNothing() throws Exception {
		try (URLClassLoader cl = game()) {
			Object bus = neoBus(cl);
			List<String> seen = listen(cl, event -> false);
			Object event = neoEvent(cl, true, 2f, 8);
			post(cl, bus, event);
			assertEquals(List.of("true/2.0/8"), seen);
			assertEquals("true/2.0/8/false", state(cl, event));
		}
	}

	/** Natively MinecraftForge asks only about a drowning body; NeoForge also posts from air 0, not yet drowning, and that is not handed on. */
	@Test void aBodyNeoForgeDoesNotDrownIsNotHandedToMinecraftForge() throws Exception {
		try (URLClassLoader cl = game()) {
			Object bus = neoBus(cl);
			List<String> seen = listen(cl, event -> true);
			Object event = neoEvent(cl, false, 2f, 8);
			post(cl, bus, event);
			assertEquals(List.of(), seen);
			assertEquals("false/2.0/8/false", state(cl, event));
		}
	}

	/** drown-cancel, drown-notdrowning, drown-damage3, drown-damage0, drown-bubbles0, drown-bubbles50, end to end. */
	@Test void theCanariesReachNeoForgesEventAsNativeMinecraftForgeAppliesThem() throws Exception {
		record Canary(String name, java.util.function.Function<ClassLoader, Predicate<Object>> change, String after) {
		}
		for (Canary canary : List.of(
				new Canary("cancel", cl -> event -> true, "true/2.0/8/true"),
				new Canary("notdrowning", cl -> set(cl, "setDrowning", boolean.class, false), "false/2.0/8/false"),
				new Canary("damage3", cl -> set(cl, "setDamageAmount", float.class, 3f), "true/3.0/8/false"),
				new Canary("damage0", cl -> set(cl, "setDamageAmount", float.class, 0f), "true/0.0/8/false"),
				// Natively a changed bubble count changes nothing; NeoForge's event keeps the count it had.
				new Canary("bubbles0", cl -> set(cl, "setBubbleCount", int.class, 0), "true/2.0/8/false"),
				new Canary("bubbles50", cl -> set(cl, "setBubbleCount", int.class, 50), "true/2.0/8/false"))) {
			try (URLClassLoader cl = game()) {
				Object bus = neoBus(cl);
				List<String> seen = listen(cl, canary.change().apply(cl));
				Object event = neoEvent(cl, true, 2f, 8);
				post(cl, bus, event);
				assertEquals(List.of("true/2.0/8"), seen, canary.name());
				assertEquals(canary.after(), state(cl, event), canary.name());
			}
		}
	}

	/**
	 * NeoForge's listeners run first: MinecraftForge is handed NeoForge's damage and bubbles, and a MinecraftForge listener
	 * that leaves them keeps them; one NeoForge listener's cancel keeps MinecraftForge from being asked, as every
	 * cancellable bridge here does.
	 */
	@Test void neoForgesAnswerIsWhatMinecraftForgeIsHanded() throws Exception {
		try (URLClassLoader cl = game()) {
			Object bus = neoBus(cl);
			Class<?> neo = cl.loadClass(NEO);
			Consumer<Object> neoListener = event -> {
				try {
					neo.getMethod("setDamageAmount", float.class).invoke(event, 4f);
					neo.getMethod("setBubbleCount", int.class).invoke(event, 2);
				} catch (ReflectiveOperationException failure) {
					throw new AssertionError(failure);
				}
			};
			cl.loadClass("net.neoforged.bus.api.IEventBus").getMethod("addListener", Class.class, Consumer.class).invoke(bus, neo, neoListener);
			List<String> seen = listen(cl, event -> false);
			Object event = neoEvent(cl, true, 2f, 8);
			post(cl, bus, event);
			assertEquals(List.of("true/4.0/2"), seen);
			assertEquals("true/4.0/2/false", state(cl, event));
		}
		try (URLClassLoader cl = game()) {
			Object bus = neoBus(cl);
			Class<?> neo = cl.loadClass(NEO);
			Consumer<Object> veto = event -> {
				try {
					cl.loadClass("net.neoforged.bus.api.ICancellableEvent").getMethod("setCanceled", boolean.class).invoke(event, true);
				} catch (ReflectiveOperationException failure) {
					throw new AssertionError(failure);
				}
			};
			cl.loadClass("net.neoforged.bus.api.IEventBus").getMethod("addListener", Class.class, Consumer.class).invoke(bus, neo, veto);
			List<String> seen = listen(cl, event -> false);
			Object event = neoEvent(cl, true, 2f, 8);
			post(cl, bus, event);
			assertEquals(List.of(), seen);
			assertEquals("true/2.0/8/true", state(cl, event));
		}
	}
}
