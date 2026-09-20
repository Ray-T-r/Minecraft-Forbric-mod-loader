/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executes the compiled runtime funnel against recording hook boundaries, without bootstrapping Minecraft. */
class KernelForgeCreativeTabsTest {
	private static final String BRIDGE = "net.forbric.kernel.runtime.KernelForgeCreativeTabs";
	private static final String TAB = "net.minecraft.world.item.CreativeModeTab";
	@TempDir Path temporary;

	@BeforeEach @AfterEach void reset() { System.clearProperty("forbric.forgeCreativeTabs"); }

	@Test void neoRunsInsideForgeAndTheGeneratorRunsOnlyOnce() throws Exception {
		try (Fixture fixture = fixture(false)) {
			fixture.run(null);
			assertEquals(List.of("forge", "neo", "generator", "neo-event", "forge-event"), fixture.calls());
			assertEquals(List.of("forge(neo(vanilla))", "forge(neo-event)", "forge-event"), fixture.output);
			assertEquals(List.of("PARENT_TAB_ONLY", "PARENT_AND_SEARCH_TABS", "PARENT_AND_SEARCH_TABS"), fixture.visibility);
			for (Object tab : fixture.recorded("tabs")) assertSame(fixture.tab, tab);
			for (Object parameters : fixture.recorded("parameters")) assertSame(fixture.parameters, parameters);
		}
	}

	@Test void disabledFunnelPreservesTheNeoPathWithoutResolvingForge() throws Exception {
		System.setProperty("forbric.forgeCreativeTabs", "off");
		try (Fixture fixture = fixture(true)) {
			fixture.run(null);
			assertEquals(List.of("neo", "generator", "neo-event"), fixture.calls());
			assertEquals(List.of("neo(vanilla)", "neo-event"), fixture.output);
		}
	}

	@Test void aGeneratorFailureIsNotSwallowedOrRetried() throws Exception {
		try (Fixture fixture = fixture(false)) {
			IllegalStateException failure = new IllegalStateException("broken generator");
			InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> fixture.run(failure));
			assertSame(failure, thrown.getCause());
			assertEquals(List.of("forge", "neo", "generator"), fixture.calls());
			assertTrue(fixture.output.isEmpty());
		}
	}

	private Fixture fixture(boolean hideForge) throws Exception {
		Path runtime = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"))
				.resolve(BRIDGE.replace('.', '/') + ".class");
		assumeTrue(Files.isRegularFile(runtime), "compile the staged runtime source set before this test: " + runtime);
		Path classes = temporary.resolve("fixture-classes");
		Files.createDirectories(classes);
		List<String> arguments = new ArrayList<>(List.of("--release", "21", "-d", classes.toString()));
		Map<String, String> sources = Map.of(
				"net/minecraft/world/item/CreativeModeTab.java", """
						package net.minecraft.world.item;
						public class CreativeModeTab {
						  public static class ItemDisplayParameters { }
						  public enum TabVisibility { PARENT_TAB_ONLY, SEARCH_TAB_ONLY, PARENT_AND_SEARCH_TABS }
						  public interface Output { void accept(String item, TabVisibility visibility); }
						  public interface DisplayItemsGenerator { void accept(ItemDisplayParameters parameters, Output output); }
						}
						""",
				"fixture/Trace.java", """
						package fixture;
						public class Trace {
						  public static final java.util.List<String> calls = new java.util.ArrayList<>();
						  public static final java.util.List<Object> tabs = new java.util.ArrayList<>();
						  public static final java.util.List<Object> parameters = new java.util.ArrayList<>();
						}
						""",
				"net/minecraftforge/common/ForgeHooks.java", recordingHook("net.minecraftforge.common", "ForgeHooks", "forge"),
				"net/neoforged/neoforge/event/EventHooks.java", recordingHook("net.neoforged.neoforge.event", "EventHooks", "neo"));
		for (var source : sources.entrySet()) {
			Path path = temporary.resolve("fixture-src").resolve(source.getKey());
			Files.createDirectories(path.getParent()); Files.writeString(path, source.getValue()); arguments.add(path.toString());
		}
		var compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "the tests require the JDK used to build the kernel");
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		assertEquals(0, compiler.run(null, null, errors, arguments.toArray(String[]::new)), errors.toString(StandardCharsets.UTF_8));
		byte[] bridge = Files.readAllBytes(runtime);
		URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader()) {
			@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (hideForge && name.equals("net.minecraftforge.common.ForgeHooks")) throw new ClassNotFoundException(name);
				if (name.equals(BRIDGE)) return defineClass(name, bridge, 0, bridge.length);
				return super.findClass(name);
			}
		};
		return new Fixture(loader);
	}

	private static String recordingHook(String pkg, String name, String family) {
		// These boundaries record nesting and preserve callback arguments. They do not reproduce either
		// carrier's visibility merge; the staged bytecode test pins that independent premise on the real jars.
		return """
				package %s;
				import net.minecraft.world.item.CreativeModeTab;
				public class %s {
				  public static void onCreativeModeTabBuildContents(CreativeModeTab tab,
				      CreativeModeTab.DisplayItemsGenerator generator, CreativeModeTab.ItemDisplayParameters parameters,
				      CreativeModeTab.Output output) {
				    fixture.Trace.calls.add("%s"); fixture.Trace.tabs.add(tab); fixture.Trace.parameters.add(parameters);
				    generator.accept(parameters, (item, visibility) -> output.accept("%s(" + item + ")", visibility));
				    fixture.Trace.calls.add("%s-event");
				    output.accept("%s-event", CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				  }
				}
				""".formatted(pkg, name, family, family, family, family);
	}

	private static final class Fixture implements AutoCloseable {
		final URLClassLoader loader;
		final Class<?> tabClass, parameterClass, generatorClass, outputClass, visibilityClass, trace;
		final Object tab, parameters;
		final Method accept;
		final List<String> output = new ArrayList<>(), visibility = new ArrayList<>();

		Fixture(URLClassLoader loader) throws Exception {
			this.loader = loader;
			tabClass = loader.loadClass(TAB); parameterClass = loader.loadClass(TAB + "$ItemDisplayParameters");
			generatorClass = loader.loadClass(TAB + "$DisplayItemsGenerator"); outputClass = loader.loadClass(TAB + "$Output");
			visibilityClass = loader.loadClass(TAB + "$TabVisibility"); trace = loader.loadClass("fixture.Trace");
			tab = tabClass.getConstructor().newInstance(); parameters = parameterClass.getConstructor().newInstance();
			accept = outputClass.getMethod("accept", String.class, visibilityClass);
		}

		void run(RuntimeException generatorFailure) throws Exception {
			Object sink = Proxy.newProxyInstance(loader, new Class<?>[] {outputClass}, (p, method, args) -> {
				output.add((String) args[0]); visibility.add(String.valueOf(args[1])); return null;
			});
			Object generator = Proxy.newProxyInstance(loader, new Class<?>[] {generatorClass}, (p, method, args) -> {
				calls().add("generator"); assertSame(parameters, args[0]);
				if (generatorFailure != null) throw generatorFailure;
				accept.invoke(args[1], "vanilla", visibilityClass.getField("PARENT_TAB_ONLY").get(null));
				return null;
			});
			Class<?> bridge = loader.loadClass(BRIDGE);
			bridge.getMethod("buildContents", tabClass, generatorClass, parameterClass, outputClass)
					.invoke(null, tab, generator, parameters, sink);
		}

		@SuppressWarnings("unchecked") List<String> calls() throws Exception { return (List<String>) trace.getField("calls").get(null); }
		@SuppressWarnings("unchecked") List<Object> recorded(String field) throws Exception { return (List<Object>) trace.getField(field).get(null); }
		@Override public void close() throws Exception { loader.close(); }
	}
}
