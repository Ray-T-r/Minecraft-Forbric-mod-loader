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
 */
class KernelServerDutiesGenuineBaseTest {
	private static final Pattern ARTIFACT_PATH = Pattern.compile("\"artifact\"\\s*:\\s*\\{[^}]*?\"path\"\\s*:\\s*\"([^\"]+)\"");
	private static final Path RUNTIME_CLASSES = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").normalize();
	private static final Path MAIN_CLASSES = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main").normalize();

	/** The compiled kernel over the genuine NeoForge game jar, the NeoForge carrier and Minecraft's libraries: no MinecraftForge. */
	private static URLClassLoader genuine() throws Exception {
		Path run = TestFixtures.stagedRoot();
		Path game = run.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar"), neo = run.resolve("neoforge-runtime/neoforge-runtime.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(game) && Files.isRegularFile(neo),
				"the genuine NeoForge base or its carrier is absent: " + game);
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

	@Test
	void andWithTheBridgesSwitchedOff() throws Exception {
		String before = System.getProperty(EventBridges.SWITCH_NAME);
		System.setProperty(EventBridges.SWITCH_NAME, "off");
		try (URLClassLoader cl = genuine()) {
			assertFalse(EventBridges.enabled(), "premise: -Dforbric.unifiedEvents=off");
			assertEquals(KernelServerDutiesInstaller.DUTIES, KernelServerDutiesInstaller.install(cl),
					"the switch turns the bridges off, and none of these is a bridge");
		} finally {
			if (before == null) System.clearProperty(EventBridges.SWITCH_NAME);
			else System.setProperty(EventBridges.SWITCH_NAME, before);
		}
		try (URLClassLoader cl = merged()) {
			System.setProperty(EventBridges.SWITCH_NAME, "off");
			assertEquals(KernelServerDutiesInstaller.DUTIES, KernelServerDutiesInstaller.install(cl), "nor on today's base");
		} finally {
			if (before == null) System.clearProperty(EventBridges.SWITCH_NAME);
			else System.setProperty(EventBridges.SWITCH_NAME, before);
		}
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

	/**
	 * Today's base, the real carriers: MinecraftForge's started duties (the login gate, the dead-event judgement) are added
	 * by the bridge pass and the kernel's after it, on the same event and priority, and the kernel's still runs second, so
	 * the load report it writes carries what the judgement found — the order the one listener had before the split.
	 */
	@Test
	void onTheMergedBaseTheKernelsWorkStillRunsAfterMinecraftForgesDuties() throws Exception {
		try (URLClassLoader cl = merged()) {
			Object bus = neoBus(cl);
			List<String> order = new CopyOnWriteArrayList<>();
			installStartedDuties(cl, "net.forbric.kernel.runtime.KernelGameServerLifecycle", bus, server -> order.add("minecraftforge"));
			installStartedDuties(cl, "net.forbric.kernel.runtime.KernelServerDuties", bus, server -> order.add("kernel"));
			startAndPostStarted(cl, bus);
			assertEquals(List.of("minecraftforge", "kernel"), order);
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
