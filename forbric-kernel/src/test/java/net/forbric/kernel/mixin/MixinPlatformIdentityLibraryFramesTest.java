/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.spongepowered.asm.service.IMixinService;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.classloading.LoaderProbePolicy;
import net.forbric.kernel.transform.InjectorExecution;

/**
 * A library a mod bundles without a loader manifest of its own has no platform, and the game loader attributes it to no
 * mod — so when its code reads {@link ForbricMixinService#getName} for the mod that drives it, the answer is the
 * platform of the nearest calling code that has one, as it is natively, where the library is loaded into the one
 * platform there is.
 *
 * <p>The library ({@code fixture.commonsplat}) asks in three shapes: a plugin base class reading the name in its
 * constructor, a two-class chain handed a bound {@code Supplier}, and a static initialiser that whoever first touches
 * the class sets off. A mod jar ({@code fixture.gadgetmod}) drives all three; a second mod jar
 * ({@code fixture.relaymod}) of another ecosystem calls the first mod, then uses the library itself.
 *
 * <p>Nothing else changes: with no mod among the callers — the library called by the kernel, by Mixin, by a jar no
 * single ecosystem owns — or with a frame of the loader's own machinery between the library and the mod, the answer
 * stays the kernel's own name.
 */
@ResourceLock("system-properties")
class MixinPlatformIdentityLibraryFramesTest {
	private static final String GADGET = "fixture.gadgetmod.GadgetMod";
	private static final String RELAY = "fixture.relaymod.RelayMod";
	private static final String LIBRARY_PACKAGE = "fixture/commonsplat/";

	private static final Map<String, String> SOURCES = Map.of(
			"fixture.commonsplat.ServiceAwarePlugin", """
					package fixture.commonsplat;

					/** The base a library gives the config plugins of the mods that bundle it. */
					public abstract class ServiceAwarePlugin {
						private final String loaderName;

						protected ServiceAwarePlugin(org.spongepowered.asm.service.IMixinService service) {
							this.loaderName = service.getName();
						}

						public final String loaderName() {
							return loaderName;
						}
					}
					""",
			"fixture.commonsplat.BundledPlugin", """
					package fixture.commonsplat;

					/** The library's own concrete plugin, for a config that names it directly. */
					public final class BundledPlugin extends ServiceAwarePlugin {
						public BundledPlugin(org.spongepowered.asm.service.IMixinService service) {
							super(service);
						}

						public static String nameOf(org.spongepowered.asm.service.IMixinService service) {
							return new BundledPlugin(service).loaderName();
						}
					}
					""",
			"fixture.commonsplat.NameMemo", """
					package fixture.commonsplat;

					/** Two library frames deep: the name comes from a supplier it was handed. */
					public final class NameMemo {
						public static String of(java.util.function.Supplier<String> source) {
							String held = Fetch.once(source);
							return held;
						}
					}
					""",
			"fixture.commonsplat.Fetch", """
					package fixture.commonsplat;

					final class Fetch {
						static String once(java.util.function.Supplier<String> source) {
							return source.get();
						}
					}
					""",
			"fixture.commonsplat.ServiceHandoff", """
					package fixture.commonsplat;

					/** Where a mod leaves the service for the library's static initialiser. */
					public final class ServiceHandoff {
						public static org.spongepowered.asm.service.IMixinService service;
					}
					""",
			"fixture.commonsplat.LoaderName", """
					package fixture.commonsplat;

					/** The name, read once when the class initialises; whoever first touches VALUE triggers it. */
					public final class LoaderName {
						public static final String VALUE;

						static {
							VALUE = ServiceHandoff.service.getName();
						}
					}
					""",
			GADGET, """
					package fixture.gadgetmod;

					import fixture.commonsplat.BundledPlugin;
					import fixture.commonsplat.LoaderName;
					import fixture.commonsplat.NameMemo;
					import fixture.commonsplat.ServiceHandoff;
					import org.spongepowered.asm.service.IMixinService;

					public final class GadgetMod {
						public static String ask(IMixinService service) {
							ServiceHandoff.service = service;
							return "base=" + new GadgetPlugin(service).loaderName()
									+ " chained=" + NameMemo.of(service::getName)
									+ " static=" + LoaderName.VALUE;
						}

						/** Hands the library's method to the loader's machinery, which calls it. */
						public static String throughMachinery(IMixinService service,
								java.util.function.BiFunction<java.util.function.Function<IMixinService, String>, IMixinService, String> machinery) {
							return machinery.apply(BundledPlugin::nameOf, service);
						}

						/** Hands the service's own method to the loader's machinery: the machinery is what asks. */
						public static String machineryAsks(IMixinService service,
								java.util.function.BiFunction<java.util.function.Function<IMixinService, String>, IMixinService, String> machinery) {
							return machinery.apply(IMixinService::getName, service);
						}
					}
					""",
			"fixture.gadgetmod.GadgetPlugin", """
					package fixture.gadgetmod;

					final class GadgetPlugin extends fixture.commonsplat.ServiceAwarePlugin {
						GadgetPlugin(org.spongepowered.asm.service.IMixinService service) {
							super(service);
						}
					}
					""",
			RELAY, """
					package fixture.relaymod;

					public final class RelayMod {
						public static String ask(org.spongepowered.asm.service.IMixinService service) {
							String gadget = fixture.gadgetmod.GadgetMod.ask(service);
							return "gadget[" + gadget + "] relay=" + new fixture.commonsplat.BundledPlugin(service).loaderName();
						}
					}
					""");

	@TempDir static Path work;
	private static Path library;
	private static Path gadget;
	private static Path relay;

	@BeforeAll static void compile() throws Exception {
		Map<String, byte[]> classes = InjectorExecution.compile(work, SOURCES);
		library = write(work.resolve("platform-commons.jar"), classes, LIBRARY_PACKAGE);
		gadget = write(work.resolve("gadget.jar"), classes, "fixture/gadgetmod/");
		relay = write(work.resolve("relay.jar"), classes, "fixture/relaymod/");
	}

	@AfterEach void reset() {
		System.clearProperty(MixinPlatformIdentity.PROPERTY);
	}

	private static Path write(Path target, Map<String, byte[]> classes, String prefix) throws Exception {
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
			for (var entry : classes.entrySet()) {
				if (!entry.getKey().startsWith(prefix)) continue;
				out.putNextEntry(new JarEntry(entry.getKey() + ".class"));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
		return target;
	}

	private static DiscoveredMod mod(Path jar, String id, Ecosystem ecosystem) {
		return new DiscoveredMod(ecosystem, id, "1", id, List.of(), List.of(), null, jar.toString());
	}

	/** A game loader over the mod jars and the library; the boot attributed the mods as {@code mods}, the library not at all. */
	private static ForbricClassLoader game(List<Path> jars, List<DiscoveredMod> mods) throws Exception {
		List<URL> urls = new ArrayList<>();
		for (Path jar : jars) urls.add(jar.toUri().toURL());
		urls.add(library.toUri().toURL());
		ForbricClassLoader loader = new ForbricClassLoader(urls.toArray(URL[]::new),
				MixinPlatformIdentityLibraryFramesTest.class.getClassLoader());
		loader.setModOrigins(mods);
		return loader;
	}

	private static ForbricClassLoader gadgetAs(Ecosystem... owners) throws Exception {
		List<DiscoveredMod> mods = new ArrayList<>();
		for (int i = 0; i < owners.length; i++) mods.add(mod(gadget, "gadget" + i, owners[i]));
		return game(List.of(gadget), mods);
	}

	private static Object call(ForbricClassLoader loader, String type, String method, Object... args) throws Throwable {
		try (loader) {
			return InjectorExecution.invokeStatic(loader.loadClass(type), method, args);
		}
	}

	private static String every(String name) {
		return "base=" + name + " chained=" + name + " static=" + name;
	}

	/** The loader's own machinery calling what it was handed: a frame of the kernel's side, as Mixin's or the kernel's is. */
	static String machinery(Function<IMixinService, String> handed, IMixinService service) {
		return handed.apply(service);
	}

	@Test void aBundledLibraryAsksForTheModThatDrivesIt() throws Throwable {
		assertEquals(every("Knot/Fabric"), call(gadgetAs(Ecosystem.FABRIC), GADGET, "ask", new ForbricMixinService()));
		assertEquals(every("ModLauncher"), call(gadgetAs(Ecosystem.FORGE), GADGET, "ask", new ForbricMixinService()));
		assertEquals(every("FML"), call(gadgetAs(Ecosystem.NEOFORGE), GADGET, "ask", new ForbricMixinService()));
	}

	/** The arbitrated family of the mod's jar, the boot's other ownership record, is read through the library too. */
	@Test void theDrivingModsArbitratedFamilyAnswersToo() throws Throwable {
		ForbricClassLoader loader = game(List.of(gadget), List.of());
		loader.setJarFamilies(Map.of(gadget, LoaderProbePolicy.Family.NEOFORGE));
		assertEquals(every("FML"), call(loader, GADGET, "ask", new ForbricMixinService()));
	}

	/**
	 * The nearest mod answers: a Fabric mod's use of the library, reached from a NeoForge mod, is told Fabric's name, and
	 * the NeoForge mod's own use of the same library class right after is told NeoForge's.
	 */
	@Test void theNearestModAmongTheCallersAnswers() throws Throwable {
		ForbricClassLoader loader = game(List.of(gadget, relay),
				List.of(mod(gadget, "gadget", Ecosystem.FABRIC), mod(relay, "relay", Ecosystem.NEOFORGE)));
		assertEquals("gadget[" + every("Knot/Fabric") + "] relay=FML", call(loader, RELAY, "ask", new ForbricMixinService()));
	}

	@Test void withNoModAmongTheCallersTheLibraryIsToldTheKernelsName() throws Throwable {
		assertEquals("Forbric", call(gadgetAs(Ecosystem.FABRIC), "fixture.commonsplat.BundledPlugin", "nameOf", new ForbricMixinService()),
				"the library's plugin built from the kernel's side, as Mixin builds a config's plugin, with a mod loaded beside it");
		assertEquals(every("Forbric"), call(gadgetAs(), GADGET, "ask", new ForbricMixinService()),
				"the mod jar attributed to nothing either");
		assertEquals(every("Forbric"), call(gadgetAs(Ecosystem.FABRIC, Ecosystem.NEOFORGE), GADGET, "ask", new ForbricMixinService()),
				"the mod jar two ecosystems claim");
	}

	/**
	 * A frame of the loader's own machinery between the library and the mod ends the walk: the mod further out is not
	 * asked. Nor is it when the machinery itself asks while a mod's call is under way — Mixin is never told a platform.
	 */
	@Test void theLoadersOwnMachineryBetweenTheAskerAndTheModEndsTheWalk() throws Throwable {
		BiFunction<Function<IMixinService, String>, IMixinService, String> machinery = MixinPlatformIdentityLibraryFramesTest::machinery;
		assertEquals("Forbric", call(gadgetAs(Ecosystem.FABRIC), GADGET, "throughMachinery", new ForbricMixinService(), machinery),
				"the library called by the machinery");
		assertEquals("Forbric", call(gadgetAs(Ecosystem.FABRIC), GADGET, "machineryAsks", new ForbricMixinService(), machinery),
				"the machinery asking");
	}

	@Test void switchedOffTheLibraryIsToldTheKernelsName() throws Throwable {
		System.setProperty(MixinPlatformIdentity.PROPERTY, "off");
		assertEquals(every("Forbric"), call(gadgetAs(Ecosystem.FABRIC), GADGET, "ask", new ForbricMixinService()));
	}
}
