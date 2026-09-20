/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * The MinecraftForge side of the block-break bridge, posted for real on MinecraftForge's own bus.
 *
 * <p>The part worth testing is not the forward, it is how a refusal is READ. MinecraftForge's own
 * {@code onBlockBreakEvent} reads {@code getResult().isDenied()} and never looks at the cancel flag, while the
 * event is also {@code Cancellable} and {@code post} returns whether a listener cancelled it. A bridge that reads
 * one of the two honours half the mods that say no — and which half depends only on which idiom each mod's author
 * happened to reach for, so the failure is invisible until someone's claim is griefed.
 *
 * <p>So this drives a real {@code BlockEvent.BreakEvent} on a real {@code CancellableEventBus}, with a listener
 * refusing it each way in turn. The event's fields are null: nothing on this path reads them, and a real
 * {@code Level} cannot be built outside a running game.
 */
class KernelGameBlockEventsTest {
	@Test
	void aModThatDeniesTheResultIsHonoured() throws Exception {
		assertTrue(vetoWith(event -> setResult(event, "DENY")),
				"MinecraftForge's own hook refuses a break by writing DENY, so a mod written against it does too");
	}

	/**
	 * The OTHER idiom, and the one MinecraftForge's own hook never reads.
	 *
	 * <p>On this eventbus a cancelling listener is a {@code Predicate} that returns true — there is no
	 * {@code setCanceled} on the event at all — and the refusal reaches the caller only as {@code post}'s return
	 * value. A bridge that read {@code getResult()} alone would take every such mod's "no" for a "yes".
	 */
	@Test
	void aModThatCancelsTheEventIsHonoured() throws Exception {
		assertTrue(vetoByCancelling(),
				"a MinecraftForge mod refusing a break through a cancelling listener must not be read as consent");
	}

	@Test
	void aModThatOnlyWatchesDoesNotStopTheBreak() throws Exception {
		assertFalse(vetoWith(event -> { }),
				"a listener that merely observes must leave the break alone — otherwise every logging mod becomes "
						+ "a protection mod");
	}

	@Test
	void aBreakTheGameHasAlreadyRefusedArrivesDenied() throws Exception {
		try (URLClassLoader cl = gameSideLoader()) {
			Method seed = bridge(cl).getDeclaredMethod("seed", boolean.class);
			seed.setAccessible(true);
			assertEquals("DENY", ((Enum<?>) seed.invoke(null, true)).name(),
					"an arriving cancelled NeoForge event means the game decided against the break; a mod reading "
							+ "getResult() must see that rather than a break that looks permitted");
			assertEquals("DEFAULT", ((Enum<?>) seed.invoke(null, false)).name());
		}
	}

	// ---- clicking a block: the tri-state translation, which is where a forward stops being a forward ----

	@Test
	void aNeoForgeDecisionIsHandedToMinecraftForgeUnderItsOwnName() throws Exception {
		try (URLClassLoader cl = gameSideLoader()) {
			assertEquals("DENY", name(seed(cl, "FALSE")),
					"a MinecraftForge mod must read the decision already made, not a blank one");
			assertEquals("ALLOW", name(seed(cl, "TRUE")));
			assertEquals("DEFAULT", name(seed(cl, "DEFAULT")));
		}
	}

	@Test
	void aMinecraftForgeDecisionCrossesWhenNobodyHasDecidedYet() throws Exception {
		try (URLClassLoader cl = gameSideLoader()) {
			assertEquals("FALSE", carried(cl, "DEFAULT", "DENY"),
					"refusing the interaction is the whole reason a protection mod listens");
			assertEquals("TRUE", carried(cl, "DEFAULT", "ALLOW"),
					"forcing it is the other half, and dropping it would be the same silent ignore the other way");
		}
	}

	@Test
	void aMinecraftForgeDecisionNeverOverrulesANeoForgeOne() throws Exception {
		try (URLClassLoader cl = gameSideLoader()) {
			assertEquals(null, carried(cl, "FALSE", "ALLOW"),
					"a MinecraftForge mod must not silently undo a NeoForge mod that ran first — the same "
							+ "asymmetry cancellation has, for the same reason");
			assertEquals(null, carried(cl, "TRUE", "DENY"));
		}
	}

	@Test
	void aMinecraftForgeModThatDecidesNothingWritesNothing() throws Exception {
		try (URLClassLoader cl = gameSideLoader()) {
			assertEquals(null, carried(cl, "DEFAULT", "DEFAULT"),
					"an untouched decision must stay untouched — writing DEFAULT over DEFAULT is how a later "
							+ "listener's value would be clobbered");
		}
	}

	/**
	 * The two left-click action enums, matched by NAME.
	 *
	 * <p>They carry the same four constants today and an ordinal match would keep compiling and start meaning
	 * something else the day either family inserts one.
	 */
	@Test
	void everyLeftClickActionHasTheSameNameOnBothSides() throws Exception {
		try (URLClassLoader cl = gameSideLoader()) {
			Class<?> neo = Class.forName(
					"net.neoforged.neoforge.event.entity.player.PlayerInteractEvent$LeftClickBlock$Action", true, cl);
			Method action = bridge(cl).getDeclaredMethod("action", neo);
			action.setAccessible(true);
			List<String> unmapped = new ArrayList<>();
			for (Object constant : neo.getEnumConstants()) {
				Object mapped = action.invoke(null, constant);
				if (mapped == null) unmapped.add(((Enum<?>) constant).name());
				else assertEquals(((Enum<?>) constant).name(), ((Enum<?>) mapped).name());
			}
			assertTrue(unmapped.isEmpty(), "a left-click action MinecraftForge cannot spell is skipped rather than "
					+ "guessed, so these would silently never reach a MinecraftForge mod: " + unmapped);
			assertEquals(null, action.invoke(null, new Object[] {null}), "a missing action is not invented");
		}
	}

	private static Object seed(ClassLoader cl, String triState) throws Exception {
		Class<?> tri = Class.forName("net.minecraft.util.TriState", true, cl);
		Method seed = bridge(cl).getDeclaredMethod("seed", tri);
		seed.setAccessible(true);
		return seed.invoke(null, Enum.valueOf(tri.asSubclass(Enum.class), triState));
	}

	/** Runs the write-back rule once and returns the TriState name it wrote, or null when it wrote nothing. */
	private static String carried(ClassLoader cl, String neoValue, String forgeValue) throws Exception {
		Class<?> tri = Class.forName("net.minecraft.util.TriState", true, cl);
		Class<?> result = Class.forName("net.minecraftforge.common.util.Result", true, cl);
		Method carry = bridge(cl).getDeclaredMethod("carryDecision", tri, result, Consumer.class);
		carry.setAccessible(true);
		List<Object> written = new ArrayList<>();
		carry.invoke(null, Enum.valueOf(tri.asSubclass(Enum.class), neoValue),
				Enum.valueOf(result.asSubclass(Enum.class), forgeValue), (Consumer<Object>) written::add);
		assertTrue(written.size() <= 1, "the rule writes once or not at all");
		return written.isEmpty() ? null : name(written.get(0));
	}

	private static String name(Object enumConstant) {
		return ((Enum<?>) enumConstant).name();
	}

	/** Runs {@code vetoed} once with an observing {@code listener} subscribed, and returns its verdict. */
	private static boolean vetoWith(Consumer<Object> listener) throws Exception {
		return run(bus -> addListener(bus, Consumer.class, listener));
	}

	/** Runs {@code vetoed} once with a CANCELLING listener subscribed — a Predicate that returns true. */
	private static boolean vetoByCancelling() throws Exception {
		java.util.function.Predicate<Object> always = event -> true;
		return run(bus -> addListener(bus, java.util.function.Predicate.class, always));
	}

	@FunctionalInterface
	private interface Subscribe {
		Object on(Object bus) throws Exception;
	}

	private static boolean run(Subscribe subscribe) throws Exception {
		try (URLClassLoader cl = gameSideLoader()) {
			Class<?> breakEvent = Class.forName("net.minecraftforge.event.level.BlockEvent$BreakEvent", true, cl);
			Class<?> result = Class.forName("net.minecraftforge.common.util.Result", true, cl);
			Object bus = breakEvent.getField("BUS").get(null);
			Object registration = subscribe.on(bus);
			try {
				Object event = breakEvent.getConstructors()[0].newInstance(null, null, null, null,
						Enum.valueOf(result.asSubclass(Enum.class), "DEFAULT"));
				Method vetoed = bridge(cl).getDeclaredMethod("vetoed", breakEvent);
				vetoed.setAccessible(true);
				return (boolean) vetoed.invoke(null, event);
			} finally {
				removeListener(bus, registration);
			}
		}
	}

	private static Object addListener(Object bus, Class<?> shape, Object listener) throws Exception {
		for (Method m : bus.getClass().getMethods()) {
			if (m.getName().equals("addListener") && m.getParameterCount() == 1
					&& m.getParameterTypes()[0] == shape) {
				m.setAccessible(true);
				return m.invoke(bus, listener);
			}
		}
		throw new AssertionError("MinecraftForge's CancellableEventBus no longer takes a " + shape.getSimpleName()
				+ " listener — the bridge posts onto this bus, so this is a real change, not a test detail");
	}

	private static void removeListener(Object bus, Object registration) throws Exception {
		for (Method m : bus.getClass().getMethods()) {
			if (m.getName().equals("removeListener") && m.getParameterCount() == 1) {
				m.setAccessible(true);
				m.invoke(bus, registration);
				return;
			}
		}
	}

	private static void setResult(Object event, String value) {
		try {
			Class<?> result = Class.forName("net.minecraftforge.common.util.Result", false,
					event.getClass().getClassLoader());
			event.getClass().getMethod("setResult", result)
					.invoke(event, Enum.valueOf(result.asSubclass(Enum.class), value));
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private static Class<?> bridge(ClassLoader cl) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameBlockEvents", true, cl);
	}

	/** The runtime output plus the staged carriers and the merged base, which is what this class links against. */
	private static URLClassLoader gameSideLoader() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").normalize();
		Path run = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run").normalize();
		Path forgeRt = run.resolve("forge-runtime/forge-runtime.jar");
		Path neoRt = run.resolve("neoforge-runtime/neoforge-runtime.jar");
		Path merged = run.resolve("merged-base/patched-mc-merged-26.2.jar");
		assumeTrue(Files.isDirectory(compiled) && Files.isRegularFile(forgeRt) && Files.isRegularFile(neoRt)
				&& Files.isRegularFile(merged), "the game-side set is not compiled, or the staged bases are absent");
		List<URL> urls = new ArrayList<>(List.of(compiled.toUri().toURL(), merged.toUri().toURL(),
				forgeRt.toUri().toURL(), neoRt.toUri().toURL()));
		// The game's own libraries too: TriState holds a Codec, so translating one decision links DataFixerUpper.
		Path libraries = Path.of(System.getProperty("user.home"), "Library", "Application Support", "minecraft",
				"libraries");
		if (Files.isDirectory(libraries)) {
			try (java.util.stream.Stream<Path> jars = Files.walk(libraries)) {
				for (Path jar : jars.filter(f -> f.toString().endsWith(".jar")).toList()) {
					urls.add(jar.toUri().toURL());
				}
			}
		}
		return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
	}
}
