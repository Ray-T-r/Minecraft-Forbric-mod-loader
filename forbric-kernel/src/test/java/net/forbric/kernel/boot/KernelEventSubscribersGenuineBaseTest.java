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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.api.Side;
import net.forbric.kernel.GateLogContract;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.classloading.ForbricClassLoader;

/**
 * The summary line gate-m4-canary reads by its words, {@code [Forbric/EBS] registered N @EventBusSubscriber class(es) on the
 * game bus}, is reached without MinecraftForge: {@code KernelEventSubscribers.registerAll} driven on the GENUINE NeoForge base
 * (the NFRT-patched game jar and the NeoForge carrier, no MinecraftForge class anywhere on the loader) prints it for a
 * NeoForge mod's subscriber.
 *
 * <p>{@code registerAll} returns early only under a loader that is not the kernel's or when NeoForge's bus API does not
 * resolve; nothing in it is about MinecraftForge. Measured
 * the same way in Stage 2.0's throwaway boot of the genuine base (carriers {@code none, neoforge-runtime.jar}): {@code registered
 * 38 @EventBusSubscriber class(es) on the game bus} for the 77 server mods of client-merged-pack.
 */
class KernelEventSubscribersGenuineBaseTest {
	private static final Pattern ARTIFACT_PATH = Pattern.compile("\"artifact\"\\s*:\\s*\\{[^}]*?\"path\"\\s*:\\s*\"([^\"]+)\"");
	private static final String SUBSCRIBER = "probe/GenuineEvents";
	private static final String STARTED = "net/neoforged/neoforge/event/server/ServerStartedEvent";

	@TempDir
	Path temporary;

	/**
	 * The kernel's own loader over the genuine NeoForge game jar, the NeoForge carrier and {@code mods}, with Minecraft's
	 * libraries, Mixin and ASM on its parent, where its delegation policy sends them in a launch.
	 */
	private static ForbricClassLoader genuine(List<Path> mods) throws Exception {
		return genuine(mods, false);
	}

	/**
	 * {@link #genuine(List)}; with {@code gameSide}, also the kernel's compiled game side, owned as in a launch, with the
	 * kernel's boot classes it links to on the parent — this test's own, so it shares their state.
	 */
	private static ForbricClassLoader genuine(List<Path> mods, boolean gameSide) throws Exception {
		Path game = TestFixtures.stagedBase(), neo = TestFixtures.neoCarrier();
		TestFixtures.requireFiles(Fixture.STAGED, "the genuine NeoForge base or its carrier is absent", game, neo);
		List<URL> owned = new ArrayList<>();
		for (Path mod : mods) owned.add(mod.toUri().toURL());
		if (gameSide) {
			Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
			TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled), "the compiled game side is absent");
			owned.add(compiled.toUri().toURL());
		}
		owned.add(game.toUri().toURL());
		owned.add(neo.toUri().toURL());
		// Mixin and ASM come with the kernel in a launch (NeoForge's bus generates its listeners with ASM), and so does
		// night-config, which NeoForge's FMLConfig needs for ModWorkManager's modloading-worker pool the subscribers are
		// wired on.
		List<URL> libraries = new ArrayList<>(List.of(org.spongepowered.asm.mixin.Mixins.class.getProtectionDomain().getCodeSource().getLocation(),
				org.objectweb.asm.Type.class.getProtectionDomain().getCodeSource().getLocation(),
				com.electronwill.nightconfig.core.UnmodifiableConfig.class.getProtectionDomain().getCodeSource().getLocation(),
				com.electronwill.nightconfig.toml.TomlFormat.class.getProtectionDomain().getCodeSource().getLocation()));
		Path root = TestFixtures.minecraftDir().resolve("libraries");
		Path version = root.resolveSibling("versions/26.2/26.2.json");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(version), "no local Minecraft 26.2 version JSON");
		Matcher artifact = ARTIFACT_PATH.matcher(Files.readString(version));
		while (artifact.find()) {
			Path jar = root.resolve(artifact.group(1));
			if (Files.isRegularFile(jar)) libraries.add(jar.toUri().toURL());
		}
		return new ForbricClassLoader(owned.toArray(new URL[0]), new URLClassLoader(libraries.toArray(new URL[0]),
				gameSide ? KernelEventSubscribersGenuineBaseTest.class.getClassLoader() : ClassLoader.getPlatformClassLoader()));
	}

	/** A NeoForge mod's {@code @EventBusSubscriber} with one {@code @SubscribeEvent} on a game-bus event. */
	private Path neoForgeMod() throws Exception {
		return neoForgeMod(SUBSCRIBER, null);
	}

	/**
	 * {@link #neoForgeMod()} as class {@code subscriber}; with {@code threadKey}, its static initialiser writes the name
	 * of the thread it runs on into that system property.
	 */
	private Path neoForgeMod(String subscriber, String threadKey) throws Exception {
		return neoForgeMod(subscriber, threadKey, null);
	}

	/**
	 * {@link #neoForgeMod(String, String)}; with {@code activeKey}, its static initialiser also writes the namespace
	 * {@code ModLoadingContext.get().getActiveNamespace()} names there into that system property.
	 */
	private Path neoForgeMod(String subscriber, String threadKey, String activeKey) throws Exception {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, subscriber, null, "java/lang/Object", null);
		if (threadKey != null) {
			MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
			clinit.visitCode();
			clinit.visitLdcInsn(threadKey);
			clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
			clinit.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getName", "()Ljava/lang/String;", false);
			clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty",
					"(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
			clinit.visitInsn(Opcodes.POP);
			if (activeKey != null) {
				clinit.visitLdcInsn(activeKey);
				clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/neoforged/fml/ModLoadingContext", "get",
						"()Lnet/neoforged/fml/ModLoadingContext;", false);
				clinit.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/neoforged/fml/ModLoadingContext", "getActiveNamespace",
						"()Ljava/lang/String;", false);
				clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty",
						"(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
				clinit.visitInsn(Opcodes.POP);
			}
			clinit.visitInsn(Opcodes.RETURN);
			clinit.visitMaxs(2, 0);
			clinit.visitEnd();
		}
		cw.visitAnnotation("Lnet/neoforged/fml/common/EventBusSubscriber;", true).visit("modid", "genuineprobe");
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onStarted", "(L" + STARTED + ";)V", null, null);
		mv.visitAnnotation("Lnet/neoforged/bus/api/SubscribeEvent;", true).visitEnd();
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 1);
		mv.visitEnd();
		cw.visitEnd();
		Path jar = temporary.resolve(subscriber.replace('/', '_') + ".jar");
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry(subscriber + ".class"));
			zip.write(cw.toByteArray());
			zip.closeEntry();
		}
		return jar;
	}

	/**
	 * A mod's {@code @EventBusSubscriber} class is loaded and initialised on NeoForge's {@code modloading-worker} pool, where
	 * {@code FMLModContainer.constructMod} injects it natively (Stage 4 C5 lane THR); it was loaded on the caller's thread.
	 */
	@Test
	void theSubscriberClassIsInitialisedOnAModloadingWorker() throws Exception {
		String key = "forbric.ebstest.clinit.thread";
		System.clearProperty(key);
		Path mod = neoForgeMod("probe/WorkerEvents", key);
		try (ForbricClassLoader cl = genuine(List.of(mod))) {
			KernelEventSubscribers.registerAll(cl, List.of(mod), Side.DEDICATED_SERVER);
			String thread = System.getProperty(key);
			assertTrue(thread != null, "the subscriber class was initialised");
			assertTrue(!thread.equals(Thread.currentThread().getName()) && thread.startsWith("modloading-worker"),
					"on NeoForge's modloading-worker pool, as natively, not on the caller's thread: " + thread);
		} finally {
			System.clearProperty(key);
		}
	}

	/**
	 * The subscriber class is initialised with its own mod active in {@code ModLoadingContext}, as
	 * {@code FMLModContainer.constructMod} injects it natively with the container active (measured on the native .93
	 * server, Stage 4 C5 lane THR); it saw {@code "minecraft"}. The mod's container is NeoForge's own
	 * {@code FMLModContainer}, built by the kernel's game side and published as {@code constructMods} publishes it
	 * (review THR wave 2d, F4b).
	 */
	@Test
	void theSubscriberClassIsInitialisedWithItsOwnModActive() throws Exception {
		String threadKey = "forbric.ebstest.active.thread", activeKey = "forbric.ebstest.active.namespace";
		System.clearProperty(threadKey);
		System.clearProperty(activeKey);
		Path mod = neoForgeMod("probe/ActiveEvents", threadKey, activeKey);
		java.lang.reflect.Field published = KernelModLoader.class.getDeclaredField("publishedNeo");
		published.setAccessible(true);
		Object before = published.get(null);
		try (ForbricClassLoader cl = genuine(List.of(mod), true)) {
			Object bus = KernelBusSupport.makeModBus(cl);
			Object container = Class.forName("net.forbric.kernel.runtime.KernelContainers", true, cl)
					.getMethod("container", String.class, Object.class, Path.class).invoke(null, "genuineprobe", bus, null);
			published.set(null, java.util.Map.of("genuineprobe", new KernelModLoader.NeoIdentity(bus, container)));
			KernelEventSubscribers.registerAll(cl, List.of(mod), Side.DEDICATED_SERVER);
			assertTrue(String.valueOf(System.getProperty(threadKey)).startsWith("modloading-worker"),
					"premise: initialised on a modloading-worker: " + System.getProperty(threadKey));
			assertEquals("genuineprobe", System.getProperty(activeKey),
					"ModLoadingContext names the subscriber's own mod while its class initialises, as natively");
		} finally {
			published.set(null, before);
			System.clearProperty(threadKey);
			System.clearProperty(activeKey);
		}
	}

	/** gate-m4-canary's own pattern for the line, read out of the gate so the two cannot drift. */
	private static String canaryPattern() throws Exception {
		Matcher grep = Pattern.compile("EBS=\\$\\(grep -oE '([^']+)'").matcher(Files.readString(Path.of("run/gate-m4-canary.sh")));
		assertTrue(grep.find(), "gate-m4-canary.sh no longer reads the @EventBusSubscriber count the way this test expects");
		return grep.group(1);
	}

	@Test
	void theSummaryGateM4CanaryReadsIsPrintedWithoutMinecraftForge() throws Exception {
		Path mod = neoForgeMod();
		String[] out = new String[1];
		try (ForbricClassLoader cl = genuine(List.of(mod))) {
			for (String forge : List.of("net.minecraftforge.eventbus.api.bus.BusGroup", "net.minecraftforge.event.ForgeEventFactory")) {
				assertThrows(ClassNotFoundException.class, () -> Class.forName(forge, false, cl), "premise: " + forge + " is absent");
			}
			GateLogContract.capture(() -> {
				KernelEventSubscribers.registerAll(cl, List.of(mod), Side.DEDICATED_SERVER);
				return null;
			}, out);
		}
		assertTrue(out[0].contains("[Forbric/EBS] registered 1 @EventBusSubscriber class(es) on the game bus"),
				"the NeoForge subscriber's one listener, counted in the line's own words: " + out[0]);
		assertTrue(out[0].contains("[Forbric/EBS] 1 NeoForge listener method(s); "), out[0]);
		assertEquals(1, GateLogContract.count(temporary, canaryPattern(), out[0]), "gate-m4-canary's grep finds it: " + out[0]);
	}
}
