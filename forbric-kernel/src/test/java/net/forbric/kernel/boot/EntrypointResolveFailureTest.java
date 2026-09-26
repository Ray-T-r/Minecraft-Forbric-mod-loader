/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.fabric.KernelFabricLoader;
import net.forbric.kernel.fabric.KernelModContainer;
import net.forbric.kernel.ui.CompatibilityDecision;

/**
 * An entrypoint class that cannot be loaded or linked is a mod that did not start, not an entrypoint of another type.
 *
 * <p>fabric-networking's {@code CommonPacketsImpl::init} names {@code ServerConfigurationPacketListenerImpl} in a
 * lambda's signature; when that class failed its weave, reflecting on the entrypoint class threw, the loader logged a
 * WARN and dropped the entrypoint, and Fabric's common packet handshake never registered with no finding anywhere.
 */
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
@ResourceLock("KernelFabricEcosystem")
class EntrypointResolveFailureTest {
	@TempDir Path directory;
	private static int healthyRuns;

	@BeforeEach @AfterEach void reset() {
		ModCatalog.publish(List.of()); CompatibilityFindings.reset(); CompatibilityDecision.reset(); KernelLoadReport.reset();
		System.clearProperty(KernelFabricLoader.RESOLVE_FAILURE_PROPERTY);
		System.clearProperty(KernelFabricEcosystem.PRELAUNCH_FAILURE_PROPERTY);
		healthyRuns = 0;
	}

	@Test void aLifecycleEntrypointThatCannotLoadFailsItsModThroughTheDriver() throws Exception {
		KernelFabricLoader loader = loader();
		register(loader, "brokennet", "main", "probe.CommonPacketsImpl::init");
		register(loader, "healthy", "main", Healthy.class.getName());
		loader.freeze();
		ModCatalog.publish(List.of(entry("brokennet"), entry("healthy")));

		assertEquals(1, invokeMain(loader), "the healthy mod still runs");
		assertEquals(1, healthyRuns);
		assertEquals(List.of("brokennet"), ModCatalog.failures().stream().map(ModCatalog.Entry::modId).toList());
		assertEquals("its main entrypoint threw", ModCatalog.failures().getFirst().statusDetail());
		KernelLoadReport.writeTo(directory.resolve("load-report.txt"));
		CompatibilityFinding finding = CompatibilityFindings.confirmedRequired().getFirst();
		assertEquals("brokennet", finding.modId());
		assertEquals("initialization:entrypoint:main", finding.id());
	}

	/**
	 * preLaunch is one of the keys the loader hands to its driver, so its driver must fail the mod too. It only logged
	 * an ERROR: Core Lib's preLaunch died that way on every sweep and the Mods screen still called Core Lib loaded.
	 */
	@Test void aPreLaunchEntrypointThatCannotLoadFailsItsModThroughTheDriver() throws Exception {
		KernelFabricLoader loader = loader();
		register(loader, "brokenpre", "preLaunch", "probe.PreLaunch");
		loader.freeze();
		ModCatalog.publish(List.of(entry("brokenpre")));

		runPreLaunch(loader);

		assertEquals(List.of("brokenpre"), ModCatalog.failures().stream().map(ModCatalog.Entry::modId).toList());
		assertEquals("its preLaunch entrypoint threw", ModCatalog.failures().getFirst().statusDetail());
		KernelLoadReport.writeTo(directory.resolve("load-report.txt"));
		CompatibilityFinding finding = CompatibilityFindings.confirmedRequired().getFirst();
		assertEquals("brokenpre", finding.modId());
		assertEquals("initialization:entrypoint:preLaunch", finding.id());
	}

	@Test void aPreLaunchFailureIsOnlyLoggedWithItsSwitch() throws Exception {
		System.setProperty(KernelFabricEcosystem.PRELAUNCH_FAILURE_PROPERTY, "warn");
		KernelFabricLoader loader = loader();
		register(loader, "brokenpre", "preLaunch", "probe.PreLaunch");
		loader.freeze();
		ModCatalog.publish(List.of(entry("brokenpre")));

		runPreLaunch(loader);

		assertTrue(ModCatalog.failures().isEmpty(), "an ERROR line and nothing else, as it used to be");
	}

	@Test void theSwitchSkipsItAsBefore() throws Exception {
		System.setProperty(KernelFabricLoader.RESOLVE_FAILURE_PROPERTY, "warn");
		KernelFabricLoader loader = loader();
		register(loader, "brokennet", "main", "probe.CommonPacketsImpl::init");
		register(loader, "healthy", "main", Healthy.class.getName());
		loader.freeze();
		ModCatalog.publish(List.of(entry("brokennet"), entry("healthy")));

		assertEquals(1, invokeMain(loader));
		assertTrue(ModCatalog.failures().isEmpty(), "dropped with a WARN, as it used to be");
	}

	/** Another key is the reading mod's business: skipped, and a SUSPECTED finding names the key, once. */
	@Test void aPluginEntrypointThatCannotLoadIsSkippedAndSuspected() throws Exception {
		KernelFabricLoader loader = loader();
		register(loader, "withplugin", "modmenu", "probe.ModMenuIntegration");
		loader.freeze();
		ModCatalog.publish(List.of(entry("withplugin")));

		assertTrue(loader.getEntrypointContainers("modmenu", Object.class).isEmpty());
		assertTrue(loader.getEntrypointContainers("modmenu", Object.class).isEmpty(), "a second query");
		List<CompatibilityFinding> found = CompatibilityFindings.all();
		assertEquals(1, found.size(), found.toString());
		assertEquals("entrypoint:modmenu:probe.ModMenuIntegration", found.getFirst().id());
		assertEquals(CompatibilityFinding.Confidence.SUSPECTED, found.getFirst().confidence());
		assertFalse(found.getFirst().required());
		assertTrue(ModCatalog.failures().isEmpty(), "the mod itself started");
	}

	/** A class that loads and is simply another type is not a failure: it stays a silent skip. */
	@Test void anEntrypointOfAnotherTypeIsStillASilentSkip() throws Exception {
		KernelFabricLoader loader = loader();
		register(loader, "othertype", "main", NotAnInitializer.class.getName());
		loader.freeze();
		ModCatalog.publish(List.of(entry("othertype")));

		assertEquals(0, invokeMain(loader));
		assertTrue(ModCatalog.failures().isEmpty());
		assertTrue(CompatibilityFindings.all().isEmpty());
	}

	/** A loader whose classes named probe.* fail to define, as a class whose mixin weave threw does. */
	private KernelFabricLoader loader() throws Exception {
		var constructor = KernelFabricLoader.class.getDeclaredConstructor(EnvType.class, Path.class, Path.class, String[].class, String.class);
		constructor.setAccessible(true);
		KernelFabricLoader loader = constructor.newInstance(EnvType.SERVER, directory, directory.resolve("config"), new String[0], "26.2");
		ClassLoader broken = new ClassLoader(getClass().getClassLoader()) {
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (name.startsWith("probe.")) throw new LinkageError("could not define " + name + ": its mixin weave failed");
				return super.loadClass(name, resolve);
			}
		};
		Field gameLoader = KernelFabricLoader.class.getDeclaredField("gameLoader");
		gameLoader.setAccessible(true);
		gameLoader.set(loader, broken);
		return loader;
	}

	private static int invokeMain(KernelFabricLoader loader) throws Exception {
		Field active = KernelFabricEcosystem.class.getDeclaredField("loader");
		active.setAccessible(true);
		Object previous = active.get(null);
		String shim = System.getProperty(KernelForeignShimContext.SWITCH);
		try {
			active.set(null, loader);
			System.setProperty(KernelForeignShimContext.SWITCH, "off");
			var invoke = KernelFabricEcosystem.class.getDeclaredMethod("invoke", String.class, Class.class, Consumer.class);
			invoke.setAccessible(true);
			return (int) invoke.invoke(null, "main", ModInitializer.class, (Consumer<ModInitializer>) ModInitializer::onInitialize);
		} finally {
			active.set(null, previous);
			if (shim == null) System.clearProperty(KernelForeignShimContext.SWITCH); else System.setProperty(KernelForeignShimContext.SWITCH, shim);
		}
	}

	private static void runPreLaunch(KernelFabricLoader loader) throws Exception {
		Field active = KernelFabricEcosystem.class.getDeclaredField("loader");
		active.setAccessible(true);
		Object previous = active.get(null);
		try {
			active.set(null, loader);
			KernelFabricEcosystem.runPreLaunch();
		} finally {
			active.set(null, previous);
			KernelFabricEcosystem.resetPhasesForTests();
		}
	}

	private static void register(KernelFabricLoader loader, String id, String key, String value) {
		String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\",\"entrypoints\":{\"" + key + "\":[\"" + value + "\"]}}";
		loader.register(new KernelModContainer(FabricModMetadataParser.read(new StringReader(json)), null, null));
	}

	private static ModCatalog.Entry entry(String id) {
		return new ModCatalog.Entry(Ecosystem.FABRIC, id, id, "1", "", List.of(), id + ".jar", "", "");
	}

	public static final class Healthy implements ModInitializer {
		@Override public void onInitialize() { healthyRuns++; }
	}

	public static final class NotAnInitializer implements Runnable {
		@Override public void run() { fail("never constructed"); }
	}
}
