/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A NeoForge setup phase in which a mod throws stops the boot, as native NeoForge 26.2.0.93 stops it (D82 "stop like
 * native", extended to every phase by D87): {@code ModLoader.dispatchParallelEvent} hands the event to every mod through
 * {@code ModContainer.acceptEvent}, waits for all of them, and a phase in which one threw ends in
 * {@code ModLoader.waitForFuture}'s {@code ModLoadingException} — the mod's own issue — before the phase's deferred work runs;
 * deferred work that throws ends the same way under "&lt;phase&gt;: Deferred Queue" (javap of ModLoader, ModContainer,
 * DeferredWorkQueue). Runs the compiled {@code KernelNeoSetup.firePhase} with the kernel's boot classes over NeoForge's real
 * carrier and patched game: real mod buses, real containers, real events and a real {@code DeferredWorkQueue}.
 *
 * <p>Until D87 the phase contained both: the mod was marked DEGRADED, the phase returned, and the boot carried on.
 */
class NeoSetupPhaseStopStagedTest {
	private URLClassLoader cl;
	private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

	@BeforeEach
	void load() throws Exception {
		URL main = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main").toUri().toURL();
		URL resources = Path.of(System.getProperty("user.dir"), "build", "resources", "main").toUri().toURL();
		cl = StagedGameClassLoader.create(List.of(main, resources));
		Class<?> modList = Class.forName("net.neoforged.fml.ModList", true, cl);
		Object list = modList.getMethod("of", List.class, List.class).invoke(null, List.of(), List.of());
		Method setLoadedMods = modList.getDeclaredMethod("setLoadedMods", List.class);
		setLoadedMods.setAccessible(true);
		setLoadedMods.invoke(list, List.of());
		Class.forName("net.neoforged.fml.ModLoader", true, cl).getMethod("clearLoadingIssues").invoke(null);
	}

	@AfterEach
	void close() throws Exception {
		if (cl != null) cl.close();
	}

	@Test
	void aSetupListenerThatThrowsStopsTheBootOnceEveryModHadThePhase() throws Exception {
		Map<String, Object> mods = new LinkedHashMap<>();
		mods.put("thrower", mod("thrower", "net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent", event -> {
			calls.add("thrower");
			throw new IllegalStateException("thrower's common setup fails on purpose");
		}));
		mods.put("healthy", mod("healthy", "net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent", event -> {
			calls.add("healthy");
			enqueue(event, () -> calls.add("healthy:deferred"));
		}));

		Throwable stop = firePhaseThrows(mods, "FML_COMMON_SETUP_EVENT", "common setup");

		assertSame(modLoadingException(), stop.getClass(), "NeoForge's own ModLoadingException: " + stop);
		List<?> issues = issues(stop);
		assertEquals(1, issues.size(), issues.toString());
		assertEquals("fml.modloadingissue.errorduringevent", key(issues.get(0)), "the mod's own issue, from acceptEvent");
		assertEquals("thrower", affectedMod(issues.get(0)), "NeoForge's issue names the mod that threw");
		assertEquals(List.of("thrower", "healthy"), calls, "every mod had the phase, and its deferred work never ran");
		assertTrue(stopped(), "the boot stops on it");
	}

	@Test
	void aDeferredTaskThatThrowsStopsTheBootUnderThePhasesDeferredQueue() throws Exception {
		Map<String, Object> mods = new LinkedHashMap<>();
		mods.put("thrower", mod("thrower", "net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent",
				event -> enqueue(event, () -> {
					calls.add("thrower:deferred");
					throw new IllegalStateException("thrower's deferred task fails on purpose");
				})));
		mods.put("healthy", mod("healthy", "net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent",
				event -> enqueue(event, () -> calls.add("healthy:deferred"))));

		Throwable stop = firePhaseThrows(mods, "FML_LOAD_COMPLETE_EVENT", "load complete");

		assertSame(modLoadingException(), stop.getClass(), "NeoForge's own ModLoadingException: " + stop);
		List<?> issues = issues(stop);
		assertEquals(1, issues.size(), issues.toString());
		assertEquals("fml.modloadingissue.uncaughterror", key(issues.get(0)), issues.toString());
		assertEquals(List.of("Complete loading of 0 mods: Deferred Queue"), args(issues.get(0)),
				"the task name native NeoForge's dispatchParallelEvent gives the deferred queue");
		assertTrue(calls.containsAll(List.of("thrower:deferred", "healthy:deferred")), "every deferred task ran: " + calls);
		assertTrue(stopped(), "the boot stops on it");
	}

	@Test
	void aPhaseNobodyFailsReturnsAndRunsItsDeferredWork() throws Exception {
		Map<String, Object> mods = new LinkedHashMap<>();
		mods.put("one", mod("one", "net.neoforged.fml.event.lifecycle.InterModEnqueueEvent", event -> {
			calls.add("one");
			enqueue(event, () -> calls.add("one:deferred"));
		}));
		mods.put("two", mod("two", "net.neoforged.fml.event.lifecycle.InterModEnqueueEvent", event -> calls.add("two")));

		assertEquals(2, firePhase(mods, "INTER_MOD_ENQUEUE_EVENT", "IMC enqueue"));
		assertEquals(List.of("one", "two", "one:deferred"), calls);
		assertTrue(!stopped(), "nothing stopped");
	}

	/**
	 * The registry phase's first event: natively {@code RegistryManager.postNewRegistryEvent} hands it to every container
	 * ({@code ModLoader.postEventWrapContainerInModOrder}), and the first listener that throws ends the "Registry
	 * initialization" task with {@code acceptEvent}'s {@code ModLoadingException} naming the mod. Until D87 the kernel skipped
	 * that mod's custom registries and went on.
	 */
	@Test
	void aNewRegistryEventListenerThatThrowsEndsTheRegistryPhase() throws Exception {
		Map<String, Object> mods = new LinkedHashMap<>();
		mods.put("thrower", mod("thrower", "net.neoforged.neoforge.registries.NewRegistryEvent", event -> {
			calls.add("thrower");
			throw new IllegalStateException("thrower's NewRegistryEvent listener fails on purpose");
		}));
		mods.put("later", mod("later", "net.neoforged.neoforge.registries.NewRegistryEvent", event -> calls.add("later")));
		Class<?> loader = Class.forName("net.forbric.kernel.boot.KernelModLoader", true, cl);
		java.lang.reflect.Field published = loader.getDeclaredField("publishedNeo");
		published.setAccessible(true);
		published.set(null, mods);
		List<Object> buses = new ArrayList<>();
		for (Object identity : mods.values()) buses.add(identity.getClass().getMethod("bus").invoke(identity));

		Method post = Class.forName("net.forbric.kernel.runtime.KernelNeoRegistries", true, cl).getMethod("postNewRegistryEvent", List.class);
		InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> post.invoke(null, buses),
				"a failing NewRegistryEvent listener must end the registry phase, not cost its mod only its registries");
		Throwable failure = thrown.getCause();

		assertSame(modLoadingException(), failure.getClass(), "acceptEvent's ModLoadingException: " + failure);
		List<?> issues = issues(failure);
		assertEquals("fml.modloadingissue.errorduringevent", key(issues.get(0)));
		assertEquals("thrower", affectedMod(issues.get(0)), "it names the mod");
		assertEquals(List.of("thrower"), calls, "the first throw ends the delivery, as NeoForge's fan-out does");
	}

	/** A NeoForge mod: a mod bus with one listener for {@code eventClass}, and the kernel's container on that bus. */
	private Object mod(String id, String eventClass, Consumer<Object> listener) throws Exception {
		Class<?> busBuilder = Class.forName("net.neoforged.bus.api.BusBuilder", true, cl);
		Object builder = busBuilder.getMethod("builder").invoke(null);
		builder = busBuilder.getMethod("markerType", Class.class)
				.invoke(builder, Class.forName("net.neoforged.fml.event.IModBusEvent", true, cl));
		builder = busBuilder.getMethod("allowPerPhasePost").invoke(builder);
		Object bus = builder.getClass().getMethod("build").invoke(builder);
		Class<?> iEventBus = Class.forName("net.neoforged.bus.api.IEventBus", true, cl);
		iEventBus.getMethod("addListener", Class.class, Consumer.class).invoke(bus, Class.forName(eventClass, true, cl), listener);
		Object container = Class.forName("net.forbric.kernel.runtime.KernelContainers", true, cl)
				.getMethod("container", String.class, Object.class, Path.class).invoke(null, id, bus, null);
		return Class.forName("net.forbric.kernel.boot.KernelModLoader$NeoIdentity", true, cl)
				.getConstructor(Object.class, Object.class).newInstance(bus, container);
	}

	private static void enqueue(Object event, Runnable work) {
		try {
			event.getClass().getMethod("enqueueWork", Runnable.class).invoke(event, work);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private int firePhase(Map<String, Object> mods, String phase, String label) throws Exception {
		return (int) firePhaseMethod().invoke(null, mods, foreignType(phase), label);
	}

	private Throwable firePhaseThrows(Map<String, Object> mods, String phase, String label) throws Exception {
		Method fire = firePhaseMethod();
		Object type = foreignType(phase);
		InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> fire.invoke(null, mods, type, label),
				"a phase a mod failed must not return as if it had passed");
		return thrown.getCause();
	}

	private Method firePhaseMethod() throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelNeoSetup", true, cl)
				.getMethod("firePhase", Map.class, Class.forName("net.forbric.api.ForeignType", true, cl), String.class);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private Object foreignType(String name) throws Exception {
		return Enum.valueOf((Class) Class.forName("net.forbric.api.ForeignType", true, cl), name);
	}

	private Class<?> modLoadingException() throws Exception {
		return Class.forName("net.neoforged.fml.ModLoadingException", false, cl);
	}

	private boolean stopped() throws Exception {
		Method stopped = Class.forName("net.forbric.kernel.boot.RegistrationStop", true, cl).getDeclaredMethod("stopped");
		stopped.setAccessible(true);
		return (boolean) stopped.invoke(null);
	}

	private static List<?> issues(Throwable stop) throws Exception {
		return (List<?>) stop.getClass().getMethod("getIssues").invoke(stop);
	}

	private static String key(Object issue) throws Exception {
		return (String) issue.getClass().getMethod("translationKey").invoke(issue);
	}

	private static List<?> args(Object issue) throws Exception {
		return (List<?>) issue.getClass().getMethod("translationArgs").invoke(issue);
	}

	private static String affectedMod(Object issue) throws Exception {
		Object info = issue.getClass().getMethod("affectedMod").invoke(issue);
		return info == null ? null : (String) info.getClass().getMethod("getModId").invoke(info);
	}
}
