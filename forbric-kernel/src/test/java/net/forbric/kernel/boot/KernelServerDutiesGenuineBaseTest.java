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

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import net.forbric.api.EventBridges;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.runtime.StagedGameClassLoader;

/**
 * The duties lane 2.3 moved out of MinecraftForge-gated code, run on the GENUINE NeoForge base: the NFRT-patched game jar
 * and the NeoForge carrier, with no MinecraftForge class anywhere on the loader.
 *
 * <p>Measured before the move: {@code GameEventMultiplexer.install} probed {@code ForgeEventFactory}, its
 * {@code ClassNotFoundException} went to {@code Caught.expected}, and Fabric's game instance, the server-started work (load
 * report, dedicated-server compatibility decision) and the {@code tryDropExperience} relay went with it, silently;
 * {@code KernelContainers} handed 74 NeoForge mods its own subclass for want of MinecraftForge's {@code UnsafeHacks}. Here each
 * of those installs or works with the carrier alone, with {@code -Dforbric.unifiedEvents=off} too, and on the merged base the
 * order the move had to keep is checked against the real carriers.
 *
 * <p>The install and order checks run the production pass itself ({@code KernelLifecycle.startGameBuses}, which reads the
 * switch, runs the bridges and then the duties) and read the result off NeoForge's own listener lists, so they fail if the
 * pass stops installing a duty under the switch, installs the duties ahead of the bridges, or puts the experience relay back
 * behind the break bridge's stand-down view; each was broken on purpose and went red. The halves that need MinecraftForge's
 * carrier say so, and skip under {@code -Pforbric.hideForgeCarrier}.
 */
class KernelServerDutiesGenuineBaseTest {
	private static final Pattern ARTIFACT_PATH = Pattern.compile("\"artifact\"\\s*:\\s*\\{[^}]*?\"path\"\\s*:\\s*\"([^\"]+)\"");
	private static final Path RUNTIME_CLASSES = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").normalize();
	private static final Path MAIN_CLASSES = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main").normalize();

	/** The compiled kernel over the genuine NeoForge game jar, the NeoForge carrier and Minecraft's libraries: no MinecraftForge. */
	private static URLClassLoader genuine() throws Exception {
		Path game = TestFixtures.genuineBase(), neo = TestFixtures.neoCarrier();
		TestFixtures.requireFiles(Fixture.STAGED, "the genuine NeoForge base or its carrier is absent", game, neo);
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(RUNTIME_CLASSES), "the compiled game side is absent");
		List<URL> urls = new ArrayList<>(List.of(RUNTIME_CLASSES.toUri().toURL(), MAIN_CLASSES.toUri().toURL(), game.toUri().toURL(),
				neo.toUri().toURL(), mixin()));
		Path libraries = TestFixtures.minecraftDir().resolve("libraries");
		Path version = libraries.resolveSibling("versions/26.2/26.2.json");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(version), "no local Minecraft 26.2 version JSON");
		Matcher artifact = ARTIFACT_PATH.matcher(Files.readString(version));
		while (artifact.find()) {
			Path jar = libraries.resolve(artifact.group(1));
			if (Files.isRegularFile(jar)) urls.add(jar.toUri().toURL());
		}
		return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
	}

	/** The compiled kernel over today's merged base and both carriers. */
	private static URLClassLoader merged() throws Exception {
		return StagedGameClassLoader.create(List.of(MAIN_CLASSES.toUri().toURL(), mixin()));
	}

	/**
	 * The Mixin the kernel bundles, as the game has it: FancyModLoader's {@code FMLModContainer} names Mixin's exceptions,
	 * so initialising it off-game needs Mixin where the game would have it.
	 */
	private static URL mixin() throws Exception {
		return org.spongepowered.asm.mixin.Mixins.class.getProtectionDomain().getCodeSource().getLocation();
	}

	private static Object neoBus(ClassLoader cl) throws Exception {
		return Class.forName("net.neoforged.neoforge.common.NeoForge", true, cl).getField("EVENT_BUS").get(null);
	}

	private static void startAndPostStarted(ClassLoader cl, Object bus) throws Exception {
		Class<?> busType = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
		busType.getMethod("start").invoke(bus);
		Class<?> server = Class.forName("net.minecraft.server.MinecraftServer", false, cl);
		Object started = Class.forName("net.neoforged.neoforge.event.server.ServerStartedEvent", true, cl).getConstructor(server)
				.newInstance(new Object[] {null});
		busType.getMethod("post", Class.forName("net.neoforged.bus.api.Event", false, cl)).invoke(bus, started);
	}

	/** {@code owner}'s package-private {@code installStartedDuties(Object, Consumer)}, the seam that hands the work in. */
	private static void installStartedDuties(ClassLoader cl, String owner, Object bus, Consumer<Object> duties) throws Exception {
		Method install = Class.forName(owner, true, cl).getDeclaredMethod("installStartedDuties", Object.class, Consumer.class);
		install.setAccessible(true);
		install.invoke(null, bus, duties);
	}

	private static boolean names(Path compiled, String what) throws Exception {
		return new String(Files.readAllBytes(compiled), StandardCharsets.ISO_8859_1).contains(what);
	}

	private static final String STARTED = "net.neoforged.neoforge.event.server.ServerStartedEvent";
	private static final String ABOUT_TO_START = "net.neoforged.neoforge.event.server.ServerAboutToStartEvent";
	private static final String DROPS = "net.neoforged.neoforge.event.level.BlockDropsEvent";
	private static final String DATA_MAPS = "net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent";
	private static final String DUTIES = "net.forbric.kernel.runtime.KernelServerDuties";
	private static final String LIFECYCLE = "net.forbric.kernel.runtime.KernelGameServerLifecycle";
	private static final String DATA_MAP_WATCH = "net.forbric.kernel.runtime.KernelNeoDataMapWatch";
	/** How {@link #listeners} labels a listener added through a bridge's stand-down view. */
	private static final String STOOD_DOWN = "stand-down view of ";

	/** Runs {@code body} with {@code -Dforbric.unifiedEvents} set to {@code value} (cleared for null), and puts it back. */
	private static void withUnifiedEvents(String value, Body body) throws Exception {
		String before = System.getProperty(EventBridges.SWITCH_NAME);
		if (value == null) System.clearProperty(EventBridges.SWITCH_NAME);
		else System.setProperty(EventBridges.SWITCH_NAME, value);
		try {
			body.run();
		} finally {
			if (before == null) System.clearProperty(EventBridges.SWITCH_NAME);
			else System.setProperty(EventBridges.SWITCH_NAME, before);
		}
	}

	@FunctionalInterface
	private interface Body {
		void run() throws Exception;
	}

	/**
	 * Runs the production pass on {@code cl} as a dedicated server's boot does ({@code KernelLifecycle.startGameBuses}: the
	 * bridges, then the kernel's duties, then the buses start) and answers NeoForge's game bus.
	 */
	private static Object startGameBuses(ClassLoader cl) throws Exception {
		Method pass = KernelLifecycle.class.getDeclaredMethod("startGameBuses", ClassLoader.class, net.forbric.api.Side.class);
		pass.setAccessible(true);
		pass.invoke(null, cl, net.forbric.api.Side.DEDICATED_SERVER);
		return neoBus(cl);
	}

	/** Each duty, as the class whose listener it is on an event it listens to. */
	private static void assertEveryDutyListens(ClassLoader cl, Object bus, String base) throws Exception {
		assertTrue(listeners(cl, bus, ABOUT_TO_START).contains(DUTIES), base + ": the game-instance fallback");
		assertTrue(listeners(cl, bus, STARTED).contains(DUTIES), base + ": the load report and compatibility decision");
		assertTrue(listeners(cl, bus, DROPS).contains(DUTIES), base + ": the tryDropExperience relay");
		assertTrue(listeners(cl, bus, DATA_MAPS).contains(DATA_MAP_WATCH), base + ": the data-map watch");
	}

	/**
	 * Who listens to {@code event} on NeoForge's bus, in the order it dispatches them: for each listener the class that
	 * wrote it (a lambda's declaring class), prefixed with {@link #STOOD_DOWN} when it was added through a bridge's
	 * stand-down view. Read from NeoForge's own listener list, the array {@code post} walks.
	 */
	private static List<String> listeners(ClassLoader cl, Object bus, String event) throws Exception {
		Method listFor = Class.forName("net.neoforged.bus.EventBus", false, cl).getDeclaredMethod("getListenerList", Class.class);
		listFor.setAccessible(true);
		Object list = listFor.invoke(bus, Class.forName(event, false, cl));
		Object[] all = (Object[]) list.getClass().getMethod("getListeners").invoke(list);
		List<String> owners = new ArrayList<>();
		for (Object listener : all) {
			Object consumer = field(listener, "consumer");
			if (consumer != null) owners.add(owner(consumer));
		}
		return owners;
	}

	private static String owner(Object consumer) throws Exception {
		if (consumer instanceof GameEventMultiplexer.StandDownListener) return STOOD_DOWN + owner(field(consumer, "forward"));
		String name = consumer.getClass().getName();
		int lambda = name.indexOf("$$Lambda");
		return lambda < 0 ? name : name.substring(0, lambda);
	}

	/** {@code target}'s field {@code name}, declared on its class or a superclass; null when none declares it. */
	private static Object field(Object target, String name) throws Exception {
		for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (!f.getName().equals(name)) continue;
				f.setAccessible(true);
				return f.get(target);
			}
		}
		return null;
	}

	@Test
	void theGenuineBaseHasNoMinecraftForgeAtAll() throws Exception {
		try (URLClassLoader cl = genuine()) {
			for (String forge : List.of("net.minecraftforge.event.ForgeEventFactory", "net.minecraftforge.unsafe.UnsafeHacks",
					"net.minecraftforge.server.ServerLifecycleHooks")) {
				assertThrows(ClassNotFoundException.class, () -> Class.forName(forge, false, cl), "premise: " + forge + " is absent");
			}
		}
	}

	@Test
	void everyKernelServerDutyInstallsWithoutMinecraftForge() throws Exception {
		try (URLClassLoader cl = genuine()) {
			assertEquals(KernelServerDutiesInstaller.DUTIES, KernelServerDutiesInstaller.install(cl),
					"the game instance, the server-started work, the experience relay and the data-map watch, each on NeoForge's bus");
		}
	}

	/**
	 * The production pass itself, {@code KernelLifecycle.startGameBuses}, with {@code -Dforbric.unifiedEvents=off}: the bridges
	 * stand down at their first line, and every duty is still on NeoForge's bus afterwards, on both bases. Before the move
	 * these went on inside that pass, after its switch check, and the switch took them with the bridges. With the switch off
	 * MinecraftForge's own started duties are not there at all (they are part of the pass), so the kernel's is the only one.
	 */
	@Test
	void withTheBridgesSwitchedOffTheProductionPassStillInstallsEveryDuty() throws Exception {
		withUnifiedEvents("off", () -> {
			assertFalse(EventBridges.enabled(), "premise: -Dforbric.unifiedEvents=off");
			for (boolean genuineBase : List.of(true, false)) {
				String base = genuineBase ? "genuine" : "merged";
				try (URLClassLoader cl = genuineBase ? genuine() : merged()) {
					Object bus = startGameBuses(cl);
					assertEveryDutyListens(cl, bus, base);
					assertFalse(listeners(cl, bus, STARTED).contains(LIFECYCLE),
							base + ": MinecraftForge's started duties are part of the switched-off pass");
				}
			}
		});
	}

	/** And the same pass with the bridges on, on the genuine base: its probe for MinecraftForge no longer takes them either. */
	@Test
	void onTheGenuineBaseTheProductionPassInstallsEveryDuty() throws Exception {
		withUnifiedEvents(null, () -> {
			assertTrue(EventBridges.enabled(), "premise: the bridges are on");
			try (URLClassLoader cl = genuine()) {
				assertEveryDutyListens(cl, startGameBuses(cl), "genuine");
			}
		});
	}

	/**
	 * Today's base, the real carriers, the production pass: MinecraftForge's started duties (the login gate, the dead-event
	 * judgement) go on inside the bridge pass and the kernel's after it, on the same event and priority, so the kernel's is
	 * the last listener {@code ServerStartedEvent} has and runs after the judgement, and the load report it writes carries
	 * what the judgement found — the order the one listener had before the split. Read off NeoForge's own listener list,
	 * which is the order it dispatches in (each priority in turn, each in the order its listeners were added).
	 */
	@Test
	void onTheMergedBaseTheProductionPassPutsTheKernelsWorkAfterMinecraftForgesDuties() throws Exception {
		TestFixtures.requireForgeCarrier("MinecraftForge's started duties, which name ServerLifecycleHooks");
		withUnifiedEvents(null, () -> {
			try (URLClassLoader cl = merged()) {
				List<String> started = listeners(cl, startGameBuses(cl), STARTED);
				assertTrue(started.contains(LIFECYCLE), "premise: MinecraftForge's started duties are on the bus: " + started);
				assertEquals(DUTIES, started.get(started.size() - 1), "the kernel's work runs last: " + started);
				assertTrue(started.lastIndexOf(LIFECYCLE) < started.lastIndexOf(DUTIES), started.toString());
			}
		});
	}

	/**
	 * The experience relay is on NeoForge's bus itself. It used to be added by the MinecraftForge break bridge through the
	 * stand-down view that bridge is handed ({@code BlockEvent$BreakEvent} is a BRIDGED {@code BLOCK_BREAK} row), which wraps
	 * every typed listener it adds; now no view of that bridge's can stand it down. Checked in the same run as the bridge's
	 * own listener on the same event, which does go through that view, so the wrapping is live here and the relay is
	 * outside it.
	 */
	@Test
	void theExperienceRelayIsNotStoodDownWithTheBreakBridge() throws Exception {
		TestFixtures.requireForgeCarrier("the MinecraftForge break bridge the relay used to ride");
		assertFalse(DeadEventAudit.forgeEventsOf(net.forbric.api.GameEventBridge.BLOCK_BREAK).isEmpty(),
				"premise: BLOCK_BREAK has a row, so its bridge is handed a stand-down view");
		withUnifiedEvents(null, () -> {
			try (URLClassLoader cl = merged()) {
				List<String> drops = listeners(cl, startGameBuses(cl), DROPS);
				assertTrue(drops.contains(STOOD_DOWN + "net.forbric.kernel.runtime.KernelGameBlockEvents"),
						"premise: the break bridge's experience decision went on through the stand-down view: " + drops);
				assertTrue(drops.contains(DUTIES), "the relay is on the bus itself: " + drops);
				assertFalse(drops.contains(STOOD_DOWN + DUTIES), "and not behind the stand-down view: " + drops);
			}
		});
	}

	@Test
	void theServerStartedWorkRunsOnceWhenNeoForgeAnnouncesTheStart() throws Exception {
		try (URLClassLoader cl = genuine()) {
			Object bus = neoBus(cl);
			List<Object> ran = new CopyOnWriteArrayList<>();
			installStartedDuties(cl, "net.forbric.kernel.runtime.KernelServerDuties", bus, ran::add);
			startAndPostStarted(cl, bus);
			assertEquals(1, ran.size(), "once per start, with nothing of MinecraftForge on the loader");
		}
	}

	/** A NeoForge mod's container is NeoForge's own FMLModContainer on both bases, filled the same way. */
	@Test
	void aNeoForgeModGetsTheGenuineFmlContainerWithoutMinecraftForge() throws Exception {
		for (boolean genuineBase : List.of(true, false)) {
			try (URLClassLoader cl = genuineBase ? genuine() : merged()) {
				Object bus = neoBus(cl);
				Object container = Class.forName("net.forbric.kernel.runtime.KernelContainers", true, cl)
						.getMethod("container", String.class, Object.class, Path.class).invoke(null, "probe", bus, null);
				Class<?> fml = Class.forName("net.neoforged.fml.javafmlmod.FMLModContainer", false, cl);
				String base = genuineBase ? "genuine" : "merged";
				assertTrue(fml.isInstance(container), base + ": instanceof FMLModContainer, as Bookshelf and every library asks: "
						+ container.getClass());
				assertSame(bus, fml.getMethod("getEventBus").invoke(container), base + ": its bus");
				assertEquals("probe", fml.getMethod("getModId").invoke(container), base + ": its id");
				assertEquals("probe", fml.getMethod("getNamespace").invoke(container), base + ": its namespace");
			}
		}
		assertFalse(names(RUNTIME_CLASSES.resolve("net/forbric/kernel/runtime/KernelContainers.class"), "net/minecraftforge/"),
				"KernelContainers names nothing of MinecraftForge");
	}

	/** The NeoForge-then-Fabric loot chain links on the genuine base; MinecraftForge's link is its own class. */
	@Test
	void theLootChainNeedsNothingOfMinecraftForge() throws Exception {
		Path runtime = RUNTIME_CLASSES.resolve("net/forbric/kernel/runtime");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(runtime), "the compiled game side is absent");
		assertFalse(names(runtime.resolve("KernelLootBridge.class"), "net/minecraftforge/"), "the chain names nothing of MinecraftForge");
		assertTrue(names(runtime.resolve("KernelForgeLootLink.class"), "net/minecraftforge/event/ForgeEventFactory"),
				"MinecraftForge's link does, and only it");
		try (URLClassLoader cl = genuine()) {
			Class<?> chain = Class.forName("net.forbric.kernel.runtime.KernelLootBridge", true, cl);
			assertEquals(2, java.util.Arrays.stream(chain.getDeclaredMethods())
					.filter(m -> m.getName().equals("loadLootTable") || m.getName().equals("loadTagsForRegistry")).count(),
					"both routed calls resolve on the genuine base");
		}
	}

	/** PassiveSeeder's constructor-free allocation no longer goes through MinecraftForge's UnsafeHacks. */
	@Test
	void theSeedersAllocationNeedsNoMinecraftForge() throws Exception {
		assertFalse(names(MAIN_CLASSES.resolve("net/forbric/kernel/boot/PassiveSeeder.class"), "net.minecraftforge.unsafe.UnsafeHacks"),
				"PassiveSeeder.allocate asks the JDK, not MinecraftForge");
	}
}
