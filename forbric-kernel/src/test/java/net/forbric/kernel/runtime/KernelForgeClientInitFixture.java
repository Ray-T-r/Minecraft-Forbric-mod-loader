package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.tools.ToolProvider;

/** Public API spies only: all three kernel classes come from the independently compiled runtime directory. */
final class KernelForgeClientInitFixture implements AutoCloseable {

	private final URLClassLoader loader;
	private final Class<?> probe;
	private final Object minecraft;
	private final Object resources;

	KernelForgeClientInitFixture(Path stubs, Path runtime) throws Exception {
		loader = new URLClassLoader(new URL[] {stubs.toUri().toURL(), runtime.toUri().toURL()},
				ClassLoader.getPlatformClassLoader());
		probe = type("fixture.ClientProbe");
		minecraft = type("net.minecraft.client.Minecraft").getConstructor().newInstance();
		Class<?> packType = type("net.minecraft.server.packs.PackType");
		resources = type("net.minecraft.server.packs.resources.ReloadableResourceManager")
				.getConstructor(packType).newInstance(packType.getField("CLIENT_RESOURCES").get(null));
	}

	void installBridge() throws Exception {
		call(type("net.forbric.kernel.runtime.KernelGameClientReload").getMethod("install", Object.class), null, value("bus"));
	}

	void init() throws Exception {
		call(type("net.forbric.kernel.runtime.KernelForgeClientInit").getMethod("initClientHooks",
				minecraft.getClass(), resources.getClass()), null, minecraft, resources);
	}

	void geometry() throws Exception {
		call(type("net.forbric.kernel.runtime.KernelForgeClientInit").getMethod("initGeometryLoaders"), null);
	}

	void particles() throws Exception {
		Class<?> particles = type("net.minecraft.client.particle.ParticleResources");
		call(type("net.forbric.kernel.runtime.KernelForgeClientInit").getMethod("onRegisterParticleProviders", particles),
				null, particles.getConstructor().newInstance());
	}

	Object drain() throws Exception {
		return call(type("net.forbric.kernel.runtime.ForgeClientReloadCapture").getMethod("drain"), null);
	}

	void reload() throws Exception {
		call(resources.getClass().getMethod("reloadAll"), resources);
	}

	int applies(Object listener) throws Exception {
		return listener.getClass().getField("applies").getInt(listener);
	}

	int count(String name) throws Exception {
		return probe.getField(name).getInt(null);
	}

	Object value(String name) throws Exception {
		return probe.getField(name).get(null);
	}

	void value(String name, Object value) throws Exception {
		probe.getField(name).set(null, value);
	}

	@SuppressWarnings("unchecked")
	List<Object> listeners() throws Exception {
		return (List<Object>) value("listeners");
	}

	@SuppressWarnings("unchecked")
	List<String> trace() throws Exception {
		return (List<String>) value("trace");
	}

	@SuppressWarnings("unchecked")
	List<String> warnings() throws Exception {
		return (List<String>) value("warnings");
	}

	List<?> registered() throws Exception {
		Object event = value("event");
		return new ArrayList<>(((Map<?, ?>) call(event.getClass().getMethod("getRegistry"), event)).values());
	}

	List<?> realListeners() throws Exception {
		return (List<?>) call(resources.getClass().getMethod("getListeners"), resources);
	}

	boolean scratchClosed() throws Exception {
		Object scratch = value("scratch");
		return scratch != null && scratch.getClass().getField("closed").getBoolean(scratch);
	}

	boolean realClosed() throws Exception {
		return resources.getClass().getField("closed").getBoolean(resources);
	}

	@Override
	public void close() throws Exception {
		loader.close();
	}

	private Class<?> type(String name) throws ClassNotFoundException {
		return Class.forName(name, true, loader);
	}

	private static Object call(Method method, Object receiver, Object... arguments) throws Exception {
		try {
			return method.invoke(receiver, arguments);
		} catch (InvocationTargetException wrapped) {
			Throwable failure = wrapped.getCause();
			if (failure instanceof Exception exception) throw exception;
			if (failure instanceof Error error) throw error;
			throw new AssertionError(failure);
		}
	}

	static Path compileSpies(Path directory) throws Exception {
		Path classes = directory.resolve("classes");
		Files.createDirectories(classes);
		List<String> arguments = new ArrayList<>(List.of("-proc:none", "-classpath", classes.toString(), "-d", classes.toString()));
		for (Map.Entry<String, String> source : sources().entrySet()) {
			Path file = directory.resolve("src").resolve(source.getKey().replace('.', '/') + ".java");
			Files.createDirectories(file.getParent());
			Files.writeString(file, source.getValue());
			arguments.add(file.toString());
		}
		var compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "a JDK is required to compile the isolated public API spies");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertEquals(0, compiler.run(null, output, output, arguments.toArray(String[]::new)),
				() -> output.toString(StandardCharsets.UTF_8));
		return classes;
	}

	private static Map<String, String> sources() {
		return Map.ofEntries(
			Map.entry("fixture.ClientProbe", """
				package fixture;
				import java.util.*;
				import java.util.concurrent.*;
				import java.util.function.Consumer;
				import net.minecraft.server.packs.resources.*;
				import net.neoforged.bus.api.*;
				import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
				public final class ClientProbe {
				  public static final List<String> trace = new ArrayList<>(), warnings = new ArrayList<>();
				  public static final List<PreparableReloadListener> listeners = new ArrayList<>(List.of(new Listener()));
				  public static final SpyBus bus = new SpyBus();
				  public static int forgeCalls, forgePosts, neoCalls, optionLoads, geometryCalls;
				  public static RuntimeException forgeFailure, neoFailureBefore, neoFailureAfter, graphFailure;
				  public static ReloadableResourceManager scratch;
				  public static AddClientReloadListenersEvent event;
				  public static String priority;
				  public static boolean receiveCanceled;
				  public static Class<?> eventType;
				  public static final class Listener implements PreparableReloadListener {
				    public int applies;
				    public CompletableFuture<Void> reload(SharedState state, Executor prepare, PreparationBarrier barrier, Executor apply) {
				      return CompletableFuture.runAsync(() -> { applies++; trace.add("listener:apply"); }, apply);
				    }
				  }
				  public static final class SpyBus implements IEventBus {
				    private Consumer<AddClientReloadListenersEvent> listener;
				    @SuppressWarnings("unchecked")
				    public <T extends Event> void addListener(EventPriority p, boolean canceled, Class<T> eventClass, Consumer<T> callback) {
				      priority = p.name(); receiveCanceled = canceled; eventType = eventClass;
				      listener = event -> callback.accept((T) event);
				    }
				    public void dispatch(AddClientReloadListenersEvent event) { if (listener != null) listener.accept(event); }
				  }
				}
				"""),
			Map.entry("net.minecraft.client.KeyMapping", "package net.minecraft.client; public class KeyMapping {}"),
			Map.entry("net.minecraft.client.Options", """
				package net.minecraft.client;
				public class Options {
				  public KeyMapping[] keyMappings = new KeyMapping[] {new KeyMapping()};
				  public void load(boolean keysOnly) { fixture.ClientProbe.optionLoads++; fixture.ClientProbe.trace.add("options:" + keysOnly); }
				}
				"""),
			Map.entry("net.minecraft.client.Minecraft", """
				package net.minecraft.client;
				public class Minecraft { public final Options options = new Options(); }
				"""),
			Map.entry("net.minecraft.client.particle.ParticleResources", "package net.minecraft.client.particle; public class ParticleResources {}"),
			Map.entry("net.minecraft.server.packs.PackType", "package net.minecraft.server.packs; public enum PackType { CLIENT_RESOURCES }"),
			Map.entry("net.minecraft.server.packs.resources.PreparableReloadListener", """
				package net.minecraft.server.packs.resources;
				import java.util.concurrent.*;
				public interface PreparableReloadListener {
				  CompletableFuture<Void> reload(SharedState state, Executor preparation, PreparationBarrier barrier, Executor apply);
				  final class SharedState {}
				  interface PreparationBarrier {}
				}
				"""),
			Map.entry("net.minecraft.server.packs.resources.ReloadableResourceManager", """
				package net.minecraft.server.packs.resources;
				import java.util.*;
				import net.minecraft.server.packs.PackType;
				import net.neoforged.neoforge.event.SortedReloadListenerEvent;
				public class ReloadableResourceManager implements AutoCloseable {
				  private List<PreparableReloadListener> listeners = new ArrayList<>();
				  public boolean closed;
				  public ReloadableResourceManager(PackType type) {}
				  public List<PreparableReloadListener> getListeners() { return listeners; }
				  public void registerReloadListener(PreparableReloadListener listener) { listeners.add(listener); }
				  public void updateListenersFrom(SortedReloadListenerEvent event) {
				    listeners = new ArrayList<>(event.getRegistry().values()); fixture.ClientProbe.trace.add("neo:update");
				  }
				  public void reloadAll() { for (var listener : listeners) listener.reload(null, Runnable::run, null, Runnable::run).join(); }
				  public void close() { closed = true; fixture.ClientProbe.trace.add("scratch:close"); }
				}
				"""),
			Map.entry("net.minecraft.resources.Identifier", """
				package net.minecraft.resources;
				public record Identifier(String namespace, String path) {
				  public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace, path); }
				}
				"""),
			Map.entry("net.minecraftforge.eventbus.internal.Event", "package net.minecraftforge.eventbus.internal; public interface Event {}"),
			Map.entry("net.minecraftforge.eventbus.api.bus.EventBus", """
				package net.minecraftforge.eventbus.api.bus;
				public interface EventBus<T extends net.minecraftforge.eventbus.internal.Event> { boolean post(T event); }
				"""),
			Map.entry("net.minecraftforge.client.event.RegisterClientReloadListenersEvent", """
				package net.minecraftforge.client.event;
				import net.minecraft.server.packs.resources.*;
				import net.minecraftforge.eventbus.api.bus.EventBus;
				import fixture.ClientProbe;
				public final class RegisterClientReloadListenersEvent implements net.minecraftforge.eventbus.internal.Event {
				  private final ReloadableResourceManager resources;
				  private static boolean dispatched;
				  public static final EventBus<RegisterClientReloadListenersEvent> BUS = event -> {
				    ClientProbe.forgePosts++; ClientProbe.trace.add("forge:post"); ClientProbe.scratch = event.resources;
				    if (!dispatched) { for (var listener : ClientProbe.listeners) event.registerReloadListener(listener); dispatched = true; }
				    return false;
				  };
				  public RegisterClientReloadListenersEvent(ReloadableResourceManager resources) { this.resources = resources; }
				  public void registerReloadListener(PreparableReloadListener listener) { resources.registerReloadListener(listener); }
				}
				"""),
			Map.entry("net.minecraftforge.client.ForgeHooksClient", """
				package net.minecraftforge.client;
				import fixture.ClientProbe;
				import net.minecraft.client.Minecraft;
				import net.minecraft.client.particle.ParticleResources;
				import net.minecraft.server.packs.resources.ReloadableResourceManager;
				import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
				public final class ForgeHooksClient {
				  private static boolean initialized;
				  public static void initClientHooks(Minecraft mc, ReloadableResourceManager manager) {
				    ClientProbe.forgeCalls++; ClientProbe.trace.add("forge:init"); ClientProbe.scratch = manager;
				    if (initialized) throw new IllegalStateException("Client hooks initialized more than once");
				    initialized = true;
				    if (ClientProbe.forgeFailure != null) throw ClientProbe.forgeFailure;
				    RegisterClientReloadListenersEvent.BUS.post(new RegisterClientReloadListenersEvent(manager));
				  }
				  public static void onRegisterParticleProviders(ParticleResources particles) { ClientProbe.trace.add("forge:particles"); }
				}
				"""),
			Map.entry("net.minecraftforge.client.model.geometry.GeometryLoaderManager", """
				package net.minecraftforge.client.model.geometry;
				public final class GeometryLoaderManager {
				  public static void init() { fixture.ClientProbe.geometryCalls++; fixture.ClientProbe.trace.add("geometry"); }
				}
				"""),
			Map.entry("net.neoforged.bus.api.Event", "package net.neoforged.bus.api; public class Event {}"),
			Map.entry("net.neoforged.bus.api.EventPriority", "package net.neoforged.bus.api; public enum EventPriority { HIGHEST, HIGH, NORMAL, LOW, LOWEST }"),
			Map.entry("net.neoforged.bus.api.IEventBus", """
				package net.neoforged.bus.api;
				import java.util.function.Consumer;
				public interface IEventBus {
				  <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer);
				}
				"""),
			Map.entry("net.neoforged.neoforge.event.SortedReloadListenerEvent", """
				package net.neoforged.neoforge.event;
				import java.util.*;
				import fixture.ClientProbe;
				import net.minecraft.resources.Identifier;
				import net.minecraft.server.packs.resources.PreparableReloadListener;
				public class SortedReloadListenerEvent extends net.neoforged.bus.api.Event {
				  private final Map<Identifier, PreparableReloadListener> registry = new LinkedHashMap<>();
				  public void addListener(Identifier id, PreparableReloadListener listener) {
				    if (ClientProbe.graphFailure != null) throw ClientProbe.graphFailure;
				    if (registry.containsKey(id) || registry.containsValue(listener)) throw new IllegalArgumentException("duplicate listener");
				    registry.put(id, listener); ClientProbe.trace.add("graph:add");
				  }
				  public Map<Identifier, PreparableReloadListener> getRegistry() { return Collections.unmodifiableMap(registry); }
				}
				"""),
			Map.entry("net.neoforged.neoforge.client.event.AddClientReloadListenersEvent", """
				package net.neoforged.neoforge.client.event;
				public class AddClientReloadListenersEvent extends net.neoforged.neoforge.event.SortedReloadListenerEvent {
				  public AddClientReloadListenersEvent(net.minecraft.server.packs.resources.ReloadableResourceManager manager) {}
				}
				"""),
			Map.entry("net.neoforged.neoforge.client.ClientHooks", """
				package net.neoforged.neoforge.client;
				import fixture.ClientProbe;
				import net.minecraft.client.Minecraft;
				import net.minecraft.client.particle.ParticleResources;
				import net.minecraft.server.packs.resources.ReloadableResourceManager;
				import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
				public final class ClientHooks {
				  public static void initClientHooks(Minecraft mc, ReloadableResourceManager manager) {
				    ClientProbe.neoCalls++; ClientProbe.trace.add("neo:init");
				    if (ClientProbe.neoFailureBefore != null) throw ClientProbe.neoFailureBefore;
				    ClientProbe.event = new AddClientReloadListenersEvent(manager);
				    ClientProbe.bus.dispatch(ClientProbe.event);
				    if (ClientProbe.neoFailureAfter != null) throw ClientProbe.neoFailureAfter;
				    manager.updateListenersFrom(ClientProbe.event);
				  }
				  public static void onRegisterParticleProviders(ParticleResources particles) { ClientProbe.trace.add("neo:particles"); }
				}
				"""),
			Map.entry("net.forbric.kernel.util.ForbricLog", """
				package net.forbric.kernel.util;
				public final class ForbricLog {
				  public static void info(String message, Object... arguments) {}
				  public static void warn(String message, Object... arguments) { fixture.ClientProbe.warnings.add(String.format(message, arguments)); }
				  public static void warn(String message, Throwable failure) { fixture.ClientProbe.warnings.add(message); }
				}
				"""),
			Map.entry("net.forbric.kernel.util.Reflect", """
				package net.forbric.kernel.util;
				public final class Reflect { public static Throwable unwrap(Throwable failure) { return failure; } }
				""")
		);
	}
}
