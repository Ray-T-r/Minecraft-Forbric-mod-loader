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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

/**
 * The main registration window takes registrations through a MinecraftForge wrapper that something locked before it
 * opened, and leaves it locked again when it closes.
 *
 * <p>A merged {@code Bootstrap.bootStrap()} that keeps both ecosystems' edits to vanilla's last call runs
 * MinecraftForge's {@code GameData.vanillaSnapshot()} beside NeoForge's, and its {@code LOCK_VANILLA} sets
 * {@code locked} on every vanilla wrapper while leaving the {@code ForgeRegistry} behind it unfrozen. The window used
 * to open only the vanilla {@code frozen} flag, so NeoForge's {@code RegisterEvent.register} — a {@code Registry.register}
 * through that wrapper — threw "Can not register to a locked registry" for every entry: NeoForge's own attributes
 * never registered and the dedicated server died in {@code DefaultAttributes.<clinit>} on "Trying to access unbound
 * value: neoforge:swim_speed" (gate-m47, every dedicated-server gate).
 *
 * <p>The stand-ins have the carrier's shapes and nothing else: a package-private {@code NamespacedWrapper} with the
 * {@code locked} field its {@code register} checks, a {@code ForgeRegistry} whose {@code freeze}/{@code unfreeze} write
 * that field as MinecraftForge's do, {@code RegistryManager.ACTIVE}, a MinecraftForge {@code GameData} whose
 * {@code vanillaSnapshot} applies {@code LOCK_VANILLA} and whose {@code freezeData} freezes every active registry, and
 * a {@code BuiltInRegistries.REGISTRY} holding the wrapper. The kernel's own window methods run against them.
 */
@ResourceLock("system-properties")
class RegistrationWindowForgeLockTest {
	private static final String LOCKED = "Can not register to a locked registry";

	/** What the merged Bootstrap leaves: the wrapper locked, the ForgeRegistry not frozen. */
	@Test
	void aWrapperLockedBeforeTheWindowTakesRegistrationsInsideItAndIsLockedAgainAfter(@TempDir Path dir)
			throws Exception {
		try (URLClassLoader cl = carriers(dir)) {
			Object attribute = attributeRegistry(cl);
			forgeGameData(cl, "vanillaSnapshot");
			assertEquals(LOCKED, registerFailure(cl, attribute, "minecraft:armor"),
					"the stand-in must reproduce the failure: after MinecraftForge's bootstrap snapshot a "
							+ "Registry.register through the wrapper is refused");

			KernelLifecycle.ReopenedRegistries opened = KernelLifecycle.openRegistrationWindow(cl);
			register(cl, attribute, "neoforge:swim_speed");
			assertTrue(entries(attribute).contains("neoforge:swim_speed"),
					"inside the window NeoForge's RegisterEvent registers through the wrapper — this is the "
							+ "registration whose absence left neoforge:swim_speed unbound");
			assertEquals(1, opened.lockedWrappers().size(), "the one locked wrapper is what the window cleared");
			assertEquals(0, opened.frozenForgeRegistries().size(),
					"LOCK_VANILLA leaves the ForgeRegistry unfrozen, so there is no ForgeRegistry to unfreeze");

			KernelLifecycle.closeRegistrationWindow(cl, opened);
			assertEquals(LOCKED, registerFailure(cl, attribute, "late:entry"),
					"closed, the wrapper is locked again, as MinecraftForge leaves it after its own registration");
		}
	}

	/**
	 * A base whose Bootstrap runs only NeoForge's snapshot leaves nothing locked, and the window then clears and puts
	 * back nothing: the opening is a no-op where it used to be enough.
	 */
	@Test
	void aWindowThatFindsNothingClosedClearsNothing(@TempDir Path dir) throws Exception {
		try (URLClassLoader cl = carriers(dir)) {
			Object attribute = attributeRegistry(cl);

			KernelLifecycle.ReopenedRegistries opened = KernelLifecycle.openRegistrationWindow(cl);
			assertEquals(0, opened.count(), "nothing was closed, so nothing is cleared or owed back");
			register(cl, attribute, "neoforge:swim_speed");
			assertTrue(entries(attribute).contains("neoforge:swim_speed"));

			KernelLifecycle.closeRegistrationWindow(cl, opened);
			assertEquals(LOCKED, registerFailure(cl, attribute, "late:entry"),
					"MinecraftForge's own freezeData freezes the ForgeRegistry, and its freeze locks the wrapper");
		}
	}

	/** The stand-ins are named by {@link ForeignType}, so a carrier rename cannot leave this test passing on air. */
	@Test
	void theStandInsAreTheTypesTheKernelNames() {
		assertEquals("net.minecraftforge.registries.GameData", ForeignType.GAME_DATA.binary(Ecosystem.FORGE));
		assertEquals("net.minecraftforge.registries.RegistryManager",
				ForeignType.REGISTRY_MANAGER.binary(Ecosystem.FORGE));
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static Object attributeRegistry(ClassLoader cl) throws Exception {
		return Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, cl).getField("ATTRIBUTE").get(null);
	}

	private static void forgeGameData(ClassLoader cl, String hook) throws Exception {
		Class.forName(ForeignType.GAME_DATA.binary(Ecosystem.FORGE), true, cl).getMethod(hook).invoke(null);
	}

	/** {@code Registry.register(registry, value)} — the call NeoForge's {@code RegisterEvent.register} makes. */
	private static void register(ClassLoader cl, Object registry, String value) throws Exception {
		Class<?> writable = Class.forName("net.minecraft.core.WritableRegistry", true, cl);
		Method register = Class.forName("net.minecraft.core.Registry", true, cl)
				.getMethod("register", writable, Object.class);
		try {
			register.invoke(null, registry, value);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof Exception cause) throw cause;
			throw e;
		}
	}

	private static String registerFailure(ClassLoader cl, Object registry, String value) {
		IllegalStateException refused = assertThrows(IllegalStateException.class, () -> register(cl, registry, value));
		String message = refused.getMessage();
		assertNotNull(message);
		return message.startsWith(LOCKED) ? LOCKED : message;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> entries(Object wrapper) throws Exception {
		var field = wrapper.getClass().getDeclaredField("entries");
		field.setAccessible(true);
		return (List<Object>) field.get(wrapper);
	}

	private static URLClassLoader carriers(Path dir) throws Exception {
		Path src = dir.resolve("src");
		Path out = Files.createDirectories(dir.resolve("classes"));
		List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "21"));
		for (Map.Entry<String, String> source : SOURCES.entrySet()) {
			Path file = src.resolve(source.getKey().replace('.', '/') + ".java");
			Files.createDirectories(file.getParent());
			Files.writeString(file, source.getValue());
			args.add(file.toString());
		}
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		assertNotNull(javac, "no system Java compiler — this test needs a JDK, not a JRE");
		assertEquals(0, javac.run(null, null, null, args.toArray(String[]::new)), "the stand-in carriers did not compile");
		return new URLClassLoader(new URL[] {out.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
	}

	private static final Map<String, String> SOURCES = Map.of(
			"net.minecraft.core.WritableRegistry", """
					package net.minecraft.core;
					public interface WritableRegistry {
						void register(Object value);
					}
					""",
			"net.minecraft.core.Registry", """
					package net.minecraft.core;
					public final class Registry {
						public static void register(WritableRegistry registry, Object value) {
							registry.register(value);
						}
					}
					""",
			"net.minecraftforge.registries.ILockableRegistry", """
					package net.minecraftforge.registries;
					public interface ILockableRegistry {
						void lock();
					}
					""",
			// Package-private, as the carrier ships it.
			"net.minecraftforge.registries.NamespacedWrapper", """
					package net.minecraftforge.registries;
					class NamespacedWrapper implements net.minecraft.core.WritableRegistry, ILockableRegistry {
						boolean locked;
						final java.util.List<Object> entries = new java.util.ArrayList<>();
						@Override public void register(Object value) {
							if (locked) throw new IllegalStateException(
									"Can not register to a locked registry. Modder should use Forge Register methods.");
							entries.add(value);
						}
						@Override public void lock() { locked = true; }
					}
					""",
			"net.minecraftforge.registries.ForgeRegistry", """
					package net.minecraftforge.registries;
					public class ForgeRegistry {
						private boolean isFrozen;
						private final NamespacedWrapper wrapper;
						ForgeRegistry(NamespacedWrapper wrapper) { this.wrapper = wrapper; }
						NamespacedWrapper getWrapper() { return wrapper; }
						public void freeze() { isFrozen = true; if (wrapper != null) wrapper.locked = true; }
						public void unfreeze() { isFrozen = false; if (wrapper != null) wrapper.locked = false; }
					}
					""",
			"net.minecraftforge.registries.RegistryManager", """
					package net.minecraftforge.registries;
					public class RegistryManager {
						public static final RegistryManager ACTIVE = new RegistryManager();
						final java.util.Map<String, ForgeRegistry> registries = new java.util.LinkedHashMap<>();
						public static Object wrapped(String name) {
							NamespacedWrapper wrapper = new NamespacedWrapper();
							ACTIVE.registries.put(name, new ForgeRegistry(wrapper));
							return wrapper;
						}
					}
					""",
			// LOCK_VANILLA over ACTIVE (locks the wrapper, leaves the ForgeRegistry unfrozen); unfreezeData touches only
			// vanilla's MappedRegistry flag, which these stand-ins do not have; freezeData freezes every active registry.
			"net.minecraftforge.registries.GameData", """
					package net.minecraftforge.registries;
					public class GameData {
						public static void vanillaSnapshot() {
							for (ForgeRegistry registry : RegistryManager.ACTIVE.registries.values()) {
								registry.getWrapper().lock();
							}
						}
						public static void unfreezeData() {}
						public static void freezeData() {
							for (ForgeRegistry registry : RegistryManager.ACTIVE.registries.values()) registry.freeze();
						}
					}
					""",
			"net.minecraft.core.registries.BuiltInRegistries", """
					package net.minecraft.core.registries;
					public final class BuiltInRegistries {
						public static final Object ATTRIBUTE = net.minecraftforge.registries.RegistryManager.wrapped("attribute");
						public static final java.util.List<Object> REGISTRY = java.util.List.of(ATTRIBUTE);
					}
					""");
}
