/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * {@link NeoFatalErrorWindow} against stand-ins of the FML classes it drives, built under their real names in a child
 * loader: FML's window holder, the provider SPI, {@code ProgramArgs}, {@code FMLConfig}, and a GLFW whose
 * {@code glfwInit} answers what the test says (no real window is ever opened). Each stand-in provider records what it was
 * given and whether FML's config data was set aside while it initialised.
 */
@ResourceLock("system-properties")
class NeoFatalErrorWindowTest {
	@TempDir
	Path dir;

	@AfterEach
	void clear() {
		System.clearProperty(NeoFatalErrorWindow.PROPERTY);
		NeoFatalErrorWindow.noteGameArguments(new String[0]);
	}

	private static final Map<String, String> FML = Map.of(
			"net/neoforged/fml/loading/ImmediateWindowHandler.java", """
					package net.neoforged.fml.loading;
					public class ImmediateWindowHandler {
						static net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider provider;
					}""",
			"net/neoforged/neoforgespi/earlywindow/ImmediateWindowProvider.java", """
					package net.neoforged.neoforgespi.earlywindow;
					public interface ImmediateWindowProvider {
						String name();
						void initialize(net.neoforged.fml.loading.ProgramArgs args);
					}""",
			"net/neoforged/fml/loading/ProgramArgs.java", """
					package net.neoforged.fml.loading;
					public final class ProgramArgs {
						public final String[] args;
						private ProgramArgs(String[] args) { this.args = args; }
						public static ProgramArgs from(String... args) { return new ProgramArgs(args); }
					}""",
			"net/neoforged/fml/loading/FMLConfig.java", """
					package net.neoforged.fml.loading;
					public final class FMLConfig {
						public enum ConfigValue { EARLY_WINDOW_CONTROL, EARLY_WINDOW_PROVIDER }
						static final FMLConfig INSTANCE = new FMLConfig();
						Object configData = "kernel-in-memory";
						public static String provider = "fmlearlywindow";
						public static String getConfigValue(ConfigValue v) { return provider; }
						public static Object data() { return INSTANCE.configData; }
					}""",
			"org/lwjgl/glfw/GLFW.java", """
					package org.lwjgl.glfw;
					public final class GLFW {
						public static boolean starts = true;
						public static boolean glfwInit() { return starts; }
					}""",
			"test/Recorded.java", """
					package test;
					public final class Recorded {
						public static String name; public static String[] args; public static Object configDuringInit = "unset";
					}""",
			"test/EarlyWindow.java", """
					package test;
					public class EarlyWindow implements net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider {
						public String name() { return "fmlearlywindow"; }
						public void initialize(net.neoforged.fml.loading.ProgramArgs a) {
							Recorded.name = name(); Recorded.args = a.args;
							Recorded.configDuringInit = net.neoforged.fml.loading.FMLConfig.data();
						}
					}""",
			"test/OtherWindow.java", """
					package test;
					public class OtherWindow implements net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider {
						public String name() { return "other"; }
						public void initialize(net.neoforged.fml.loading.ProgramArgs a) { Recorded.name = name(); }
					}""");

	private ClassLoader fml() throws IOException {
		Path src = dir.resolve("src");
		Path out = dir.resolve("classes");
		List<String> files = new ArrayList<>(List.of("-d", out.toString()));
		for (Map.Entry<String, String> e : FML.entrySet()) {
			Path file = src.resolve(e.getKey());
			Files.createDirectories(file.getParent());
			Files.writeString(file, e.getValue());
			files.add(file.toString());
		}
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		assertEquals(0, javac.run(null, null, null, files.toArray(new String[0])), "stand-ins compile");
		Path services = out.resolve("META-INF/services/net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider");
		Files.createDirectories(services.getParent());
		Files.writeString(services, "test.OtherWindow\ntest.EarlyWindow\n");
		return new ChildFirst(out.toUri().toURL(), getClass().getClassLoader());
	}

	private static Object field(ClassLoader loader, String owner, String name) throws Exception {
		Field f = Class.forName(owner, true, loader).getDeclaredField(name);
		f.setAccessible(true);
		return f.get(null);
	}

	/** The stand-ins win over anything of the same name the test class path carries (the real carrier may be on it). */
	private static final class ChildFirst extends URLClassLoader {
		ChildFirst(URL classes, ClassLoader parent) {
			super(new URL[] {classes}, parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!name.startsWith("net.neoforged.") && !name.startsWith("org.lwjgl.") && !name.startsWith("test.")) {
				return super.loadClass(name, resolve);
			}
			synchronized (getClassLoadingLock(name)) {
				Class<?> c = findLoadedClass(name);
				if (c == null) c = findClass(name);
				if (resolve) resolveClass(c);
				return c;
			}
		}

		@Override
		public java.util.Enumeration<URL> getResources(String name) throws IOException {
			return findResources(name);
		}
	}

	private static Object provider(ClassLoader loader) throws Exception {
		return field(loader, "net.neoforged.fml.loading.ImmediateWindowHandler", "provider");
	}

	private static Object recorded(ClassLoader loader, String name) throws Exception {
		return field(loader, "test.Recorded", name);
	}

	private static Object configData(ClassLoader loader) throws Exception {
		Class<?> config = Class.forName("net.neoforged.fml.loading.FMLConfig", true, loader);
		Field instance = config.getDeclaredField("INSTANCE");
		instance.setAccessible(true);
		Field data = config.getDeclaredField("configData");
		data.setAccessible(true);
		return data.get(instance.get(null));
	}

	@Test
	void withNoProviderTheOneFmlNamesIsOpenedWithTheGameArgumentsAndInstalled() throws Exception {
		ClassLoader loader = fml();
		NeoFatalErrorWindow.noteGameArguments(new String[] {"--assetsDir", "/a", "--assetIndex", "32"});
		assertEquals(NeoFatalErrorWindow.Outcome.OPENED, NeoFatalErrorWindow.open(loader));
		Object installed = provider(loader);
		assertNotNull(installed, "the opened provider is NeoForge's provider from now on");
		assertEquals("test.EarlyWindow", installed.getClass().getName(), "chosen by FML's configured name");
		assertEquals("fmlearlywindow", recorded(loader, "name"));
		assertEquals(List.of("--assetsDir", "/a", "--assetIndex", "32"), List.of((String[]) recorded(loader, "args")));
		assertNull(recorded(loader, "configDuringInit"), "the kernel's config is set aside while the window sets up, so "
				+ "FML's updateConfig cannot save it to config/fml.toml");
		assertEquals("kernel-in-memory", configData(loader), "and it is put back afterwards");
	}

	@Test
	void aProviderAlreadyThereIsLeftAlone() throws Exception {
		ClassLoader loader = fml();
		Object running = Class.forName("test.OtherWindow", true, loader).getConstructor().newInstance();
		Field f = Class.forName("net.neoforged.fml.loading.ImmediateWindowHandler", true, loader).getDeclaredField("provider");
		f.setAccessible(true);
		f.set(null, running);
		assertEquals(NeoFatalErrorWindow.Outcome.PRESENT, NeoFatalErrorWindow.open(loader));
		assertSame(running, provider(loader));
		assertNull(recorded(loader, "name"), "nothing was initialised");
	}

	@Test
	void withoutADisplayNeoForgesFallbackStays() throws Exception {
		ClassLoader loader = fml();
		Class.forName("org.lwjgl.glfw.GLFW", true, loader).getField("starts").set(null, false);
		assertEquals(NeoFatalErrorWindow.Outcome.FALLBACK, NeoFatalErrorWindow.open(loader));
		assertNull(provider(loader));
		assertNull(recorded(loader, "name"));
	}

	@Test
	void anUnknownProviderNameLeavesNeoForgesFallback() throws Exception {
		ClassLoader loader = fml();
		Class.forName("net.neoforged.fml.loading.FMLConfig", true, loader).getField("provider").set(null, "nosuchwindow");
		assertEquals(NeoFatalErrorWindow.Outcome.FALLBACK, NeoFatalErrorWindow.open(loader));
		assertNull(provider(loader));
	}

	@Test
	void theSwitchLeavesNeoForgesFallback() throws Exception {
		ClassLoader loader = fml();
		System.setProperty(NeoFatalErrorWindow.PROPERTY, "off");
		assertEquals(NeoFatalErrorWindow.Outcome.FALLBACK, NeoFatalErrorWindow.open(loader));
		assertNull(provider(loader));
	}

	@Test
	void withoutTheCarrierThereIsNothingToOpen() {
		assertEquals(NeoFatalErrorWindow.Outcome.FALLBACK,
				NeoFatalErrorWindow.open(new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())));
	}
}
