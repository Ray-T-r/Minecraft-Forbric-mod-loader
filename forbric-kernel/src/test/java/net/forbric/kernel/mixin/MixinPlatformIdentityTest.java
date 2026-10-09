/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * {@link ForbricMixinService#getName} tells a mod's code the name its own platform's Mixin service reports, however
 * that code asks, and tells everything else the kernel's own name.
 *
 * <p>The fixture is one jar of mod code asking in every shape a multi-loader mod uses, none of them Controlify's: a
 * three-platform switch, a one-sided {@code equals}, a helper in another class, a base class that reads the name in
 * its constructor, a value kept in a static initialiser and compared later, a method reference handed to
 * {@code Optional.map}, a bound {@code Supplier}, and reflection. The same jar is defined by a {@link ForbricClassLoader}
 * that attributes it to each ecosystem in turn, through either of the two ownership records a boot keeps.
 */
@ResourceLock("system-properties")
class MixinPlatformIdentityTest {
	private static final String ASKERS = "fixture.dualbuild.platform.Askers";

	private static final Map<String, String> SOURCES = Map.of(
			ASKERS, """
					package fixture.dualbuild.platform;

					import java.util.Optional;
					import java.util.function.Supplier;
					import org.spongepowered.asm.service.IMixinService;

					public final class Askers {
						static IMixinService service;

						/** Every shape, in one line: what each concluded. */
						public static String all(IMixinService given) throws ReflectiveOperationException {
							service = given;
							Supplier<String> bound = given::getName;
							return "switch=" + switched(given)
									+ " single=" + ("Knot/Fabric".equals(given.getName()) ? "fabric" : "other")
									+ " helper=" + (LoaderChecks.onNeoForge(given) ? "neoforge" : "other")
									+ " base=" + new ChosenPlugin(given).platform()
									+ " static=" + Remembered.SERVICE_NAME
									+ " ref=" + Optional.of(given).map(IMixinService::getName).orElseThrow()
									+ " supplier=" + bound.get()
									+ " reflect=" + IMixinService.class.getMethod("getName").invoke(given);
						}

						private static String switched(IMixinService given) {
							return switch (given.getName()) {
								case "Knot/Fabric" -> "fabric";
								case "ModLauncher" -> "forge";
								case "FML" -> "neoforge";
								default -> "unknown(" + given.getName() + ")";
							};
						}
					}
					""",
			"fixture.dualbuild.platform.LoaderChecks", """
					package fixture.dualbuild.platform;

					final class LoaderChecks {
						static boolean onNeoForge(org.spongepowered.asm.service.IMixinService service) {
							String name = service.getName();
							return name.equals("FML");
						}
					}
					""",
			"fixture.dualbuild.platform.PlatformAwarePlugin", """
					package fixture.dualbuild.platform;

					public abstract class PlatformAwarePlugin {
						private final String loader;

						protected PlatformAwarePlugin(org.spongepowered.asm.service.IMixinService service) {
							loader = service.getName();
						}

						public final String platform() {
							return loader;
						}
					}
					""",
			"fixture.dualbuild.platform.ChosenPlugin", """
					package fixture.dualbuild.platform;

					final class ChosenPlugin extends PlatformAwarePlugin {
						ChosenPlugin(org.spongepowered.asm.service.IMixinService service) {
							super(service);
						}
					}
					""",
			"fixture.dualbuild.platform.Remembered", """
					package fixture.dualbuild.platform;

					final class Remembered {
						static final String SERVICE_NAME = Askers.service.getName();
					}
					""");

	@TempDir static Path work;
	private static Path jar;
	private static Path library;

	@BeforeAll static void compile() throws Exception {
		Map<String, byte[]> classes = InjectorExecution.compile(work, SOURCES);
		jar = write(work.resolve("dualbuild.jar"), classes);
		Map<String, byte[]> helper = InjectorExecution.compile(work, Map.of("fixture.sharedlib.Platform", """
				package fixture.sharedlib;

				public final class Platform {
					public static String name(org.spongepowered.asm.service.IMixinService service) {
						return service.getName();
					}
				}
				""", "fixture.dualbuild.caller.UsesLibrary", """
				package fixture.dualbuild.caller;

				public final class UsesLibrary {
					public static String ask(org.spongepowered.asm.service.IMixinService service) {
						return service.getName() + "|" + fixture.sharedlib.Platform.name(service);
					}
				}
				"""));
		library = write(work.resolve("sharedlib.jar"), Map.of("fixture/sharedlib/Platform", helper.get("fixture/sharedlib/Platform")));
		write(work.resolve("caller.jar"), Map.of("fixture/dualbuild/caller/UsesLibrary", helper.get("fixture/dualbuild/caller/UsesLibrary")));
	}

	@AfterEach void reset() {
		System.clearProperty(MixinPlatformIdentity.PROPERTY);
		System.clearProperty("forbric.compatPluginPlatforms");
	}

	private static Path write(Path target, Map<String, byte[]> classes) throws Exception {
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
			for (var entry : classes.entrySet()) {
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

	/** A game loader over the fixture jar, which the boot attributed to {@code owners} (none: unattributed). */
	private static ForbricClassLoader game(Path jar, Ecosystem... owners) throws Exception {
		ForbricClassLoader loader = new ForbricClassLoader(new URL[] {jar.toUri().toURL()}, MixinPlatformIdentityTest.class.getClassLoader());
		List<DiscoveredMod> mods = new java.util.ArrayList<>();
		for (int i = 0; i < owners.length; i++) mods.add(mod(jar, "dualbuild" + i, owners[i]));
		loader.setModOrigins(mods);
		return loader;
	}

	/** What the fixture's code concluded, asking Forbric's real service from the classes {@code loader} defined. */
	private static String asked(ForbricClassLoader loader) throws Throwable {
		try (loader) {
			return (String) InjectorExecution.invokeStatic(loader.loadClass(ASKERS), "all", new ForbricMixinService());
		}
	}

	private static String every(String switched, String single, String helper, String name) {
		return "switch=" + switched + " single=" + single + " helper=" + helper + " base=" + name + " static=" + name
				+ " ref=" + name + " supplier=" + name + " reflect=" + name;
	}

	@Test void aModsCodeIsToldItsOwnPlatformsServiceNameHoweverItAsks() throws Throwable {
		assertEquals(every("fabric", "fabric", "other", "Knot/Fabric"), asked(game(jar, Ecosystem.FABRIC)));
		assertEquals(every("forge", "other", "other", "ModLauncher"), asked(game(jar, Ecosystem.FORGE)));
		assertEquals(every("neoforge", "other", "neoforge", "FML"), asked(game(jar, Ecosystem.NEOFORGE)));
	}

	/** The family a boot records per jar when it arbitrates a universal jar is the other record the answer is read from. */
	@Test void theArbitratedFamilyOfTheDefiningJarDecidesToo() throws Throwable {
		ForbricClassLoader loader = new ForbricClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader());
		loader.setJarFamilies(Map.of(jar, LoaderProbePolicy.Family.NEOFORGE));
		assertEquals(every("neoforge", "other", "neoforge", "FML"), asked(loader));
	}

	/** Each class answers for its own jar: a library a Fabric mod calls is told the library's platform, not the mod's. */
	@Test void theAskingCodesOwnJarDecidesNotItsCaller() throws Throwable {
		Path caller = work.resolve("caller.jar");
		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[] {caller.toUri().toURL(), library.toUri().toURL()},
				getClass().getClassLoader())) {
			loader.setModOrigins(List.of(mod(caller, "caller", Ecosystem.FABRIC), mod(library, "sharedlib", Ecosystem.NEOFORGE)));
			assertEquals("Knot/Fabric|FML", InjectorExecution.invokeStatic(loader.loadClass("fixture.dualbuild.caller.UsesLibrary"), "ask",
					new ForbricMixinService()));
		}
	}

	@Test void codeNoSingleModOwnsIsToldTheKernelsName() throws Throwable {
		String unknown = every("unknown(Forbric)", "other", "other", "Forbric");
		assertEquals(unknown, asked(game(jar)), "a jar no selected mod owns: the merged base, a carrier, a library");
		assertEquals(unknown, asked(game(jar, Ecosystem.FABRIC, Ecosystem.NEOFORGE)), "a jar two ecosystems claim");
		assertEquals("Forbric", new ForbricMixinService().getName(), "the kernel's own call, and Mixin's");
		assertEquals("Forbric", java.util.Optional.of(new ForbricMixinService()).map(IMixinService::getName).orElseThrow(),
				"a JDK frame in between is passed over to the code that set it up, here the kernel's test");
	}

	@Test void switchedOffEveryCallerIsToldTheKernelsName() throws Throwable {
		System.setProperty(MixinPlatformIdentity.PROPERTY, "off");
		assertEquals(every("unknown(Forbric)", "other", "other", "Forbric"), asked(game(jar, Ecosystem.FABRIC)));
		System.clearProperty(MixinPlatformIdentity.PROPERTY);
		System.setProperty("forbric.compatPluginPlatforms", "off");
		assertEquals(every("unknown(Forbric)", "other", "other", "Forbric"), asked(game(jar, Ecosystem.NEOFORGE)),
				"the released name of the switch still turns it off");
	}

	@Test void eachEcosystemHasItsPlatformsOwnServiceName() {
		assertEquals("Knot/Fabric", MixinPlatformIdentity.nativeServiceName(Ecosystem.FABRIC));
		assertEquals("ModLauncher", MixinPlatformIdentity.nativeServiceName(Ecosystem.FORGE));
		assertEquals("FML", MixinPlatformIdentity.nativeServiceName(Ecosystem.NEOFORGE));
		assertNull(MixinPlatformIdentity.nativeServiceName(null));
	}
}
