/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import net.forbric.kernel.TestFixtures;

/**
 * MinecraftForge's breathe event, posted from NeoForge's (KernelGameWorldEvents.installBreathe), driven for real.
 *
 * <p>The merged game decides a body's air in NeoForge's hook alone, so before the bridge a MinecraftForge mod's
 * {@code LivingBreatheEvent} listener never ran (0 events on Forbric, 40 a body on MinecraftForge, measured by the air
 * probe). The bridge says NeoForge's answer in MinecraftForge's terms, posts it, and writes back what MinecraftForge's
 * listeners changed. The scenarios are the probe's canaries, with the values native MinecraftForge 26.2 handed them;
 * the control is that a listener that changes nothing writes nothing, whatever NeoForge's listeners left.
 *
 * <p>The translation and the write-back have no game types in their signatures, so they are called directly, over the
 * compiled game side and the staged game.
 */
class KernelBreatheBridgeTest {
	private URLClassLoader loader;
	private Class<?> breath, neoBreath;

	@BeforeEach void load() throws Exception {
		TestFixtures.requireForgeCarrier("MinecraftForge's LivingBreatheEvent, which the bridge hands NeoForge's answer to");
		loader = StagedGameClassLoader.create(List.of());
		breath = Class.forName("net.forbric.kernel.runtime.KernelGameWorldEvents$ForgeBreath", true, loader);
		neoBreath = Class.forName("net.forbric.kernel.runtime.KernelGameWorldEvents$NeoBreath", true, loader);
	}

	@AfterEach void close() throws Exception {
		if (loader != null) loader.close();
	}

	/** NeoForge's event as it stands, recording every write. */
	private static final class Neo {
		boolean canBreathe;
		int consume, refill;
		final List<String> writes = new ArrayList<>();

		Neo(boolean canBreathe, int consume, int refill) {
			this.canBreathe = canBreathe;
			this.consume = consume;
			this.refill = refill;
		}
	}

	private Object view(Neo neo) {
		return Proxy.newProxyInstance(loader, new Class<?>[] {neoBreath}, (proxy, method, args) -> switch (method.getName()) {
			case "canBreathe" -> neo.canBreathe;
			case "consume" -> neo.consume;
			case "refill" -> neo.refill;
			case "setCanBreathe" -> { neo.canBreathe = (Boolean) args[0]; neo.writes.add("canBreathe=" + args[0]); yield null; }
			case "setConsume" -> { neo.consume = (Integer) args[0]; neo.writes.add("consume=" + args[0]); yield null; }
			case "setRefill" -> { neo.refill = (Integer) args[0]; neo.writes.add("refill=" + args[0]); yield null; }
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	/** What MinecraftForge's event is handed for NeoForge's answer. */
	private Object handed(Neo neo, BooleanSupplier refillsByRule, IntSupplier vanillaRefill) throws Exception {
		Method of = breath.getDeclaredMethod("of", boolean.class, int.class, int.class, BooleanSupplier.class, IntSupplier.class);
		of.setAccessible(true);
		return of.invoke(null, neo.canBreathe, neo.consume, neo.refill, refillsByRule, vanillaRefill);
	}

	private Object forge(boolean canBreathe, int consume, int refill, boolean canRefill) throws Exception {
		Constructor<?> make = breath.getDeclaredConstructor(boolean.class, int.class, int.class, boolean.class);
		make.setAccessible(true);
		return make.newInstance(canBreathe, consume, refill, canRefill);
	}

	private Object get(Object forge, String component) throws Exception {
		Method read = breath.getDeclaredMethod(component);
		read.setAccessible(true);
		return read.invoke(forge);
	}

	private List<String> carry(Neo neo, Object handed, Object after) throws Exception {
		Method carry = Class.forName("net.forbric.kernel.runtime.KernelGameWorldEvents", false, loader)
				.getDeclaredMethod("carryBack", neoBreath, breath, breath);
		carry.setAccessible(true);
		try {
			carry.invoke(null, view(neo), handed, after);
		} catch (InvocationTargetException thrown) {
			throw (Exception) thrown.getCause();
		}
		return neo.writes;
	}

	private static BooleanSupplier never() {
		return () -> { throw new AssertionError("MinecraftForge's refill rule was asked although NeoForge's answer refills"); };
	}

	private static IntSupplier noAmount() {
		return () -> { throw new AssertionError("vanilla's refill was asked although NeoForge's answer refills"); };
	}

	/** Negative control: a MinecraftForge listener that changes nothing writes nothing, whatever NeoForge left. */
	@Test void aListenerThatChangesNothingWritesNothing() throws Exception {
		for (boolean canBreathe : new boolean[] {true, false})
			for (int consume : new int[] {1, 0, -2})
				for (int refill : new int[] {4, 0, -1})
					for (boolean rule : new boolean[] {true, false})
						for (int vanilla : new int[] {4, 0}) {
							Neo neo = new Neo(canBreathe, consume, refill);
							Object handed = handed(neo, () -> rule, () -> vanilla);
							assertEquals(List.of(), carry(neo, handed, handed),
									"NeoForge " + canBreathe + "/" + consume + "/" + refill + ", rule " + rule + ", vanilla " + vanilla);
						}
	}

	/** NeoForge's answer refills: MinecraftForge may refill that much, and neither of its own questions is asked (every body, every tick). */
	@Test void aRefillingAnswerIsHandedAsItIs() throws Exception {
		Object handed = handed(new Neo(true, 1, 4), never(), noAmount());
		assertEquals(true, get(handed, "canRefill"));
		assertEquals(4, get(handed, "refill"));
	}

	/**
	 * NeoForge holds a breathing body's air: under MinecraftForge's rule (a water-breathing body) it may refill, of
	 * nothing; under nothing but Breath of the Nautilus it may not, of what vanilla would give — as natively.
	 */
	@Test void aHeldBodyIsHandedMinecraftForgesOwnRule() throws Exception {
		Object mayRefill = handed(new Neo(true, 1, 0), () -> true, noAmount());
		assertEquals(true, get(mayRefill, "canRefill"));
		assertEquals(0, get(mayRefill, "refill"));
		Object nautilus = handed(new Neo(true, 1, 0), () -> false, () -> 4);
		assertEquals(false, get(nautilus, "canRefill"));
		assertEquals(4, get(nautilus, "refill"));
		Neo neo = new Neo(true, 1, 0);
		assertEquals(List.of("refill=4"), carry(neo, nautilus, forge(true, 1, 4, true)),
				"a MinecraftForge mod that lets the body refill gives it vanilla's refill");
	}

	/** canary-refill2 / canary-refill0: in water with water breathing, a mod sets the refill (natively 202, 204… and 200, 200…). */
	@Test void aChangedRefillIsCarriedBack() throws Exception {
		Neo two = new Neo(true, 1, 4);
		Object handed = handed(two, never(), noAmount());
		assertEquals(List.of("refill=2"), carry(two, handed, forge(true, 1, 2, true)));
		Neo zero = new Neo(true, 1, 4);
		assertEquals(List.of("refill=0"), carry(zero, handed(zero, never(), noAmount()), forge(true, 1, 0, true)));
		Neo held = new Neo(true, 1, 4);
		assertEquals(List.of("refill=0"), carry(held, handed(held, never(), noAmount()), forge(true, 1, 4, false)),
				"a body MinecraftForge may not refill regains nothing");
	}

	/** canary-cant: a mod says a water-breathing body cannot breathe and loses 3 a tick (natively 197, 194…). */
	@Test void aBodyMadeToDrownLosesWhatTheModSays() throws Exception {
		Neo neo = new Neo(true, 1, 4);
		assertEquals(List.of("canBreathe=false", "consume=3"), carry(neo, handed(neo, never(), noAmount()), forge(false, 3, 4, true)));
	}

	/** canary-can: a mod lets a drowning body breathe and refill 3 a tick (natively 203, 206…). */
	@Test void aDrowningBodyMadeToBreatheRegainsWhatTheModSays() throws Exception {
		Neo neo = new Neo(false, 1, 4);
		assertEquals(List.of("canBreathe=true", "refill=3"), carry(neo, handed(neo, never(), noAmount()), forge(true, 1, 3, true)));
	}

	// ---- the bridge, on real buses ----

	private static final String NEO_EVENT = "net.neoforged.neoforge.event.entity.living.LivingBreatheEvent";
	private static final String FORGE_EVENT = "net.minecraftforge.event.entity.living.LivingBreatheEvent";

	/**
	 * Every NeoForge listener above LOWEST answers before MinecraftForge's, also one added after the bridge, as a mod's
	 * setup event adds one (the bridge is installed before any mod's setup): MinecraftForge's listener is handed what a
	 * NeoForge listener at LOW and one at NORMAL, both added after the bridge, set. A bridge at any priority above
	 * LOWEST would be asked before one of them.
	 */
	@Test void neoForgeListenersAboveLowestAddedAfterTheBridgeAnswerFirst() throws Exception {
		try (URLClassLoader cl = StagedGameClassLoader.create(List.of(Path.of("build/classes/java/main").toAbsolutePath().toUri().toURL()))) {
			Object builder = cl.loadClass("net.neoforged.bus.api.BusBuilder").getMethod("builder").invoke(null);
			Object bus = builder.getClass().getMethod("build").invoke(builder);
			cl.loadClass("net.forbric.kernel.runtime.KernelGameWorldEvents").getMethod("installBreathe", Object.class).invoke(null, bus);
			Class<?> neo = cl.loadClass(NEO_EVENT), priority = cl.loadClass("net.neoforged.bus.api.EventPriority");
			Method add = cl.loadClass("net.neoforged.bus.api.IEventBus").getMethod("addListener", priority, boolean.class, Class.class, Consumer.class);
			Consumer<Object> normal = event -> {
				try {
					neo.getMethod("setConsumeAirAmount", int.class).invoke(event, 3);
				} catch (ReflectiveOperationException failure) {
					throw new AssertionError(failure);
				}
			};
			Consumer<Object> low = event -> {
				try {
					neo.getMethod("setRefillAirAmount", int.class).invoke(event, 2);
				} catch (ReflectiveOperationException failure) {
					throw new AssertionError(failure);
				}
			};
			add.invoke(bus, priority.getField("LOW").get(null), false, neo, low);
			add.invoke(bus, priority.getField("NORMAL").get(null), false, neo, normal);
			Class<?> forge = cl.loadClass(FORGE_EVENT);
			List<String> seen = new ArrayList<>();
			Consumer<Object> listener = event -> {
				try {
					seen.add(forge.getMethod("canBreathe").invoke(event) + "/" + forge.getMethod("getConsumeAirAmount").invoke(event) + "/"
							+ forge.getMethod("getRefillAirAmount").invoke(event) + "/" + forge.getMethod("canRefillAir").invoke(event));
				} catch (ReflectiveOperationException failure) {
					throw new AssertionError(failure);
				}
			};
			cl.loadClass("net.minecraftforge.eventbus.api.bus.EventBus").getMethod("addListener", Consumer.class).invoke(forge.getField("BUS").get(null), listener);
			Object event = neo.getConstructor(cl.loadClass("net.minecraft.world.entity.LivingEntity"), boolean.class, int.class, int.class)
					.newInstance(null, true, 1, 4);
			cl.loadClass("net.neoforged.bus.api.IEventBus").getMethod("post", cl.loadClass("net.neoforged.bus.api.Event")).invoke(bus, event);
			assertEquals(List.of("true/3/2/true"), seen);
		}
	}
}
