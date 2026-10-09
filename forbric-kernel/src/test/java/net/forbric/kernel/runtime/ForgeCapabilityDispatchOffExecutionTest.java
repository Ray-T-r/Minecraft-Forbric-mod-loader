/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.transform.ForgeCapabilityCompositionTransformer;

/**
 * {@code -Dforbric.forgeCapabilities=off}, run: the staged base's required roots define through the real loader, and
 * the provider their accessor builds never reaches the attach bus and answers every ask empty.
 *
 * <p>Off used to skip the composition outright. Once the merged base listed Entity/BlockEntity/Level in
 * {@code required-ancestor-compositions.tsv}, the loader refused to define them without the composition's proof, and
 * the server died at its first Entity with "Unresolved stateful ancestor composition".
 */
class ForgeCapabilityDispatchOffExecutionTest {
	private static final List<String> ROOTS = List.of("net.minecraft.world.entity.Entity",
			"net.minecraft.world.level.block.entity.BlockEntity", "net.minecraft.world.level.Level");
	private static final String PROVIDER_IMPL = "net.minecraftforge.common.capabilities.ICapabilityProviderImpl";

	@AfterEach
	void clearSwitch() {
		System.clearProperty(ForgeCapabilityCompositionTransformer.PROPERTY);
	}

	@Test
	void withDispatchOffTheRequiredRootsDefineThroughTheRealLoader() throws Exception {
		List<URL> urls = StagedGameClassLoader.urls();
		System.setProperty(ForgeCapabilityCompositionTransformer.PROPERTY, "off");
		try (var parent = new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
				var bare = new ForbricClassLoader(urls.toArray(URL[]::new), parent)) {
			TestFixtures.require(Fixture.STAGED, resources(bare).apply("META-INF/forbric/required-ancestor-compositions.tsv") != null,
					"the staged merged base predates required-ancestor-compositions.tsv");
			LinkageError refused = assertThrows(LinkageError.class, () -> bare.loadClass(ROOTS.getFirst()),
					"premise: the staged base requires the composition, and nothing here proves it");
			assertTrue(refused.getMessage().contains("Unresolved stateful ancestor composition"), refused.getMessage());
		}
		try (var parent = new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
				var loader = new ForbricClassLoader(urls.toArray(URL[]::new), parent)) {
			// Exactly the kernel's wiring: the two-argument constructor, with the transfer bridge on.
			var composition = new ForgeCapabilityCompositionTransformer(resources(loader), true);
			assertFalse(composition.dispatches());
			assertFalse(composition.transferFallback(), "the kernel registers the transfer fallback only when this says so");
			loader.registerAncestorComposition(composition);
			loader.setTransformer((name, bytes) -> composition.transform(name, bytes, null));
			for (String root : ROOTS) {
				Class<?> defined = loader.loadClass(root);
				assertSame(loader, defined.getClassLoader(), root);
				assertTrue(Arrays.stream(defined.getInterfaces()).anyMatch(i -> i.getName().equals(PROVIDER_IMPL)),
						root + " carries the composed provider interface");
			}
		}
	}

	@Test
	void theInertProviderNeverReachesTheAttachBusAndAnswersEveryAskEmpty() throws Exception {
		try (URLClassLoader game = StagedGameClassLoader.create(List.of())) {
			Class<?> capabilities = game.loadClass("net.forbric.kernel.runtime.KernelForgeCapabilities");
			Class<?> asField = game.loadClass("net.minecraftforge.common.capabilities.CapabilityProvider$AsField");
			Class<?> impl = game.loadClass(PROVIDER_IMPL);
			Object owner = Proxy.newProxyInstance(game, new Class<?>[] { impl }, (proxy, method, args) -> switch (method.getName()) {
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				case "toString" -> "owner";
				default -> null;
			});

			// A listener on the very bus the dispatching entity provider asks.
			Object bus = game.loadClass("net.minecraftforge.event.AttachCapabilitiesEvent$Entities").getField("BUS").get(null);
			AtomicInteger heard = new AtomicInteger();
			Consumer<Object> listener = event -> heard.incrementAndGet();
			game.loadClass("net.minecraftforge.eventbus.api.bus.EventBus").getMethod("addListener", Consumer.class).invoke(bus, listener);
			Constructor<?> dispatching = game.loadClass(capabilities.getName() + "$Entities").getDeclaredConstructor(Object.class);
			dispatching.setAccessible(true);
			assertTrue(shouldFire(dispatching.newInstance(owner)), "premise: with a listener, the dispatching provider would gather");

			Method inert = capabilities.getMethod("inert", asField, Object.class);
			Object provider = inert.invoke(null, null, owner);
			assertEquals(capabilities.getName() + "$Inert", provider.getClass().getName());
			assertFalse(shouldFire(provider));
			assertEquals(0, heard.get(), "creating and initialising the inert provider fires nothing");

			Method ask = asField.getMethod("getCapability", game.loadClass("net.minecraftforge.common.capabilities.Capability"),
					game.loadClass("net.minecraft.core.Direction"));
			Object answer = ask.invoke(provider, null, null);
			assertEquals(Boolean.FALSE, answer.getClass().getMethod("isPresent").invoke(answer), "every ask answers empty");
			assertNull(capabilities.getMethod("dispatcher", asField).invoke(null, provider), "there is no dispatcher");
			assertNull(asField.getMethod("serializeInternal", game.loadClass("net.minecraft.core.HolderLookup$Provider")).invoke(provider,
					(Object) null), "nothing is written under ForgeCaps");
			assertSame(provider, inert.invoke(null, provider, owner), "created once, then reused");
			assertEquals(0, heard.get());
		}
	}

	private static boolean shouldFire(Object provider) throws Exception {
		for (Class<?> type = provider.getClass(); type != null; type = type.getSuperclass()) {
			try {
				Method method = type.getDeclaredMethod("shouldFireAttachCapabilitiesEvent");
				method.setAccessible(true);
				return (Boolean) method.invoke(provider);
			} catch (NoSuchMethodException next) {
				// inherited
			}
		}
		throw new AssertionError("no shouldFireAttachCapabilitiesEvent on " + provider.getClass());
	}

	private static Function<String, byte[]> resources(ForbricClassLoader loader) {
		return path -> {
			try (var input = loader.getGameResourceAsStream(path)) {
				return input == null ? null : input.readAllBytes();
			} catch (java.io.IOException unavailable) {
				return null;
			}
		};
	}
}
