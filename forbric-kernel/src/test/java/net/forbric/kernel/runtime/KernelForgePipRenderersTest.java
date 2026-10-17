package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the compiled bridge with small API stand-ins; no graphics device is required. */
class KernelForgePipRenderersTest {
	@TempDir Path temporary;

	@Test
	void mixedConstructorListRetainsSingletonIdentityAndNativeRegistrationsWithoutMutatingInput() throws Exception {
		Path runtime = Path.of("build/classes/java/runtime");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isRegularFile(runtime.resolve("net/forbric/kernel/runtime/KernelForgePipRenderers.class")),
				"runtime bridge not compiled");
		Map<String, String> sources = Map.of(
				"net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState", "public interface PictureInPictureRenderState {}",
				"net.minecraft.client.gui.render.pip.PictureInPictureRenderer", """
						public class PictureInPictureRenderer {
						 public int closes;
						 public Class getRenderStateClass() { return getClass(); }
						 public void close() { closes++; }
						}
						""",
				"net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration", "public class PictureInPictureRendererRegistration {}",
				"com.google.common.collect.ImmutableMap", """
						public class ImmutableMap extends java.util.LinkedHashMap {
						 public static Builder builder() { return new Builder(); }
						 public static class Builder {
						  private final ImmutableMap values = new ImmutableMap();
						  public void put(Object key, Object value) { values.put(key, value); }
						  public ImmutableMap buildKeepingLast() { return values; }
						 }
						}
						""",
				"net.minecraftforge.client.event.RegisterPictureInPictureRendererEvent", """
						public class RegisterPictureInPictureRendererEvent implements net.minecraftforge.eventbus.internal.Event {
						 public static final Object REGISTERED = new net.minecraft.client.gui.render.pip.PictureInPictureRenderer();
						 public static final net.minecraftforge.eventbus.api.bus.EventBus BUS = event -> {
						  ((RegisterPictureInPictureRendererEvent)event).builder.put(String.class, REGISTERED);
						  return false;
						 };
						 private final com.google.common.collect.ImmutableMap.Builder builder;
						 public RegisterPictureInPictureRendererEvent(java.util.List list, com.google.common.collect.ImmutableMap.Builder builder) { this.builder = builder; }
						}
						""",
				"net.minecraftforge.eventbus.api.bus.EventBus", "public interface EventBus { boolean post(net.minecraftforge.eventbus.internal.Event event); }",
				"net.minecraftforge.eventbus.internal.Event", "public interface Event {}",
				"net.forbric.kernel.util.ForbricLog", """
						public class ForbricLog {
						 public static void info(String text, Object... args) {}
						 public static void warn(String text, Object... args) {}
						 public static void warn(String text, Throwable failure) { throw new AssertionError(text, failure); }
						 public static void debug(String text) {}
						}
						""",
				"net.forbric.kernel.util.Reflect", "public class Reflect { public static Throwable unwrap(Throwable t) { return t; } }",
				"net.forbric.kernel.util.Caught", """
						public class Caught {
						 public static void warn(Throwable failure, String text, Object... args) { throw new AssertionError(text, failure); }
						 public static void expected(Throwable failure) {}
						}
						""");
		List<String> args = new ArrayList<>(List.of("-d", temporary.toString()));
		for (var entry : sources.entrySet()) {
			Path source = temporary.resolve(entry.getKey().replace('.', '/') + ".java");
			Files.createDirectories(source.getParent());
			Files.writeString(source, "package " + entry.getKey().substring(0, entry.getKey().lastIndexOf('.'))
					+ ";\n" + entry.getValue());
			args.add(source.toString());
		}
		assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
		try (var loader = new URLClassLoader(new URL[] {temporary.toUri().toURL(), runtime.toUri().toURL()},
				ClassLoader.getPlatformClassLoader())) {
			Class<?> bridge = loader.loadClass("net.forbric.kernel.runtime.KernelForgePipRenderers");
			Object renderer = loader.loadClass("net.minecraft.client.gui.render.pip.PictureInPictureRenderer").getConstructor().newInstance();
			Object registration = loader.loadClass("net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration").getConstructor().newInstance();
			Object eventRenderer = loader.loadClass("net.minecraftforge.client.event.RegisterPictureInPictureRendererEvent").getField("REGISTERED").get(null);
			for (List<?> input : List.of(List.of(), List.of(renderer), List.of(registration), List.of(registration, renderer))) {
				List<?> pools = (List<?>) bridge.getMethod("poolRegistrations", List.class).invoke(null, input);
				Map<?, ?> plain = (Map<?, ?>) bridge.getMethod("build", List.class).invoke(null, input);
				assertEquals(input.contains(registration) ? List.of(registration) : List.of(), pools);
				assertEquals(input.contains(renderer) ? 2 : 1, plain.size());
				assertSame(eventRenderer, plain.get(String.class), "Forge event registrations must still be served");
				if (input.contains(renderer)) assertSame(renderer, plain.get(renderer.getClass()));
				assertEquals(0, renderer.getClass().getField("closes").getInt(renderer));
			}
			// Aliases in a mutable guest map must not close the same singleton twice.
			bridge.getMethod("close", Map.class).invoke(null, Map.of("first", renderer, "alias", renderer));
			assertEquals(1, renderer.getClass().getField("closes").getInt(renderer));
			var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
					() -> bridge.getMethod("poolRegistrations", List.class).invoke(null, List.of("invalid")));
			assertInstanceOf(IllegalArgumentException.class, failure.getCause());
		}
	}
}
