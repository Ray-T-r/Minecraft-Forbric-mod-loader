/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;

/**
 * {@link CommonNetworkInteropInjector}'s output, run against the kernel's real boot-side {@code PayloadInterop}: five of
 * its repairs, each on stand-ins for the class it edits, each with the merged failure it prevents. (A Fabric receiver
 * handed NeoForge's payload of a shared channel is {@code FabricReceiverArbitrationExecutionTest}'s.)
 * <ul>
 *   <li>the server finishing Fabric's {@code c:version} task while NeoForge's equivalent is current: the next task
 *       starts — as merged, "Unexpected request for task finish";</li>
 *   <li>the next task starting through MinecraftForge's task context, so a MinecraftForge task (which refuses the
 *       vanilla overload) starts and a vanilla-shaped one still does;</li>
 *   <li>a MinecraftForge payload reaching either common listener: it goes to {@code ForgeHooks.onCustomPayload} — as
 *       merged, NeoForge's body disconnects for an unknown channel;</li>
 *   <li>NeoForge's {@code checkPacket}: a MinecraftForge payload, and one another ecosystem negotiated, are not
 *       policed, and a NeoForge-registered or vanilla one still is;</li>
 *   <li>MinecraftForge's {@code SyncConfigTask} reading a per-world config that is gone: the kernel writes it back
 *       from the {@code ModConfig} — as merged, "Failed to read config on server";</li>
 *   <li>and, unclaimed, the client's register answered by fabric-api before NeoForge.</li>
 * </ul>
 * Not run here: the hooks that reflect into MinecraftForge's network registry and event factory
 * ({@code channelActive}, task gathering, configuration-finished, channel bookkeeping), the HEDGE initialisation guard
 * (inert since NeoForge 26.2.0.88), and the play-phase hand-over to NeoForge, which {@code PlayPayloadHandOverExecutionTest}
 * runs on stand-ins of its own. The injector has no switch of its own:
 * {@code -Dforbric.commonNetworkInterop=off} is read where KernelBoot registers it.
 */
@ExecutesInjector(CommonNetworkInteropInjector.class)
class CommonNetworkInteropInjectorExecutionTest {
	private static final String SERVER_CONFIG = "net.minecraft.server.network.ServerConfigurationPacketListenerImpl";
	private static final String CLIENT_COMMON = "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl";
	private static final String SERVER_COMMON = "net.minecraft.server.network.ServerCommonPacketListenerImpl";
	private static final String CLIENT_CONFIG = "net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl";
	private static final String NEO_REGISTRY = "net.neoforged.neoforge.network.registration.NetworkRegistry";
	private static final String SYNC_CONFIG = "net.minecraftforge.network.tasks.SyncConfigTask";
	private static final List<String> TARGETS = List.of(SERVER_CONFIG, CLIENT_COMMON, SERVER_COMMON, CLIENT_CONFIG,
			NEO_REGISTRY, SYNC_CONFIG);

	private static String payload(String binaryName, String fields, String id) {
		int dot = binaryName.lastIndexOf('.');
		return """
				package %s;

				import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
				import net.minecraft.resources.Identifier;

				public record %s(%s) implements CustomPacketPayload {
					public Type type() {
						return new Type(%s);
					}
				}
				""".formatted(binaryName.substring(0, dot), binaryName.substring(dot + 1), fields, id);
	}

	private static String customPacket(String name) {
		return """
				package net.minecraft.network.protocol.common;

				import net.minecraft.network.protocol.Packet;
				import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

				public record %s(CustomPacketPayload payload) implements Packet {
				}
				""".formatted(name);
	}

	/** A common listener: fabric-api's head injection answers minecraft:register, then NeoForge's body. */
	private static String commonListener(String pkg, String name, String packet) {
		return """
				package %s;

				import java.util.ArrayList;
				import java.util.List;
				import net.minecraft.network.Connection;
				import net.minecraft.network.protocol.common.%s;
				import net.neoforged.neoforge.network.registration.NetworkRegistry;

				public class %s {
					protected final Connection connection;
					public final List<String> handled = new ArrayList<>();

					public %s(Connection connection) {
						this.connection = connection;
					}

					public void handleCustomPayload(%s packet) {
						String id = packet.payload().type().id().toString();
						if (id.equals("minecraft:register")) {
							handled.add("fabric");
							return;
						}
						if (NetworkRegistry.registered(id)) handled.add("neoforge " + id);
						else connection.disconnect("No Channel for " + id);
					}
				}
				""".formatted(pkg, packet, name, name, packet);
	}

	private static final Map<String, String> STAND_INS = new HashMap<>(Map.of(
			"net.minecraft.resources.Identifier", """
					package net.minecraft.resources;

					public record Identifier(String id) {
						@Override
						public String toString() {
							return id;
						}
					}
					""",
			"net.minecraft.network.protocol.common.custom.CustomPacketPayload", """
					package net.minecraft.network.protocol.common.custom;

					import net.minecraft.resources.Identifier;

					public interface CustomPacketPayload {
						record Type(Identifier id) {
						}

						Type type();
					}
					""",
			"net.minecraft.network.Connection", """
					package net.minecraft.network;

					public class Connection {
						public String disconnected;

						public void disconnect(String reason) {
							disconnected = reason;
						}
					}
					""",
			"net.minecraft.network.protocol.Packet", "package net.minecraft.network.protocol; public interface Packet { }",
			"net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket", customPacket("ClientboundCustomPayloadPacket"),
			"net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket", customPacket("ServerboundCustomPayloadPacket"),
			"net.minecraftforge.network.ForgePayload", payload("net.minecraftforge.network.ForgePayload", "Identifier channel", "channel"),
			"fixture.ModPayload", payload("fixture.ModPayload", "String id", "new Identifier(id)"),
			"net.neoforged.neoforge.network.payload.CommonVersionPayload",
			payload("net.neoforged.neoforge.network.payload.CommonVersionPayload", "java.util.List<Integer> versions",
					"new Identifier(\"c:version\")")));

	static {
		STAND_INS.put("net.neoforged.neoforge.network.payload.MinecraftRegisterPayload",
				payload("net.neoforged.neoforge.network.payload.MinecraftRegisterPayload", "java.util.Set<Identifier> channels",
						"new Identifier(\"minecraft:register\")"));
		STAND_INS.put("net.minecraft.network.protocol.common.ServerCommonPacketListener", """
				package net.minecraft.network.protocol.common;

				public interface ServerCommonPacketListener {
					boolean negotiated(String channel);
				}
				""");
		STAND_INS.put("net.minecraftforge.common.ForgeHooks", """
				package net.minecraftforge.common;

				import java.util.ArrayList;
				import java.util.List;
				import net.minecraft.network.Connection;
				import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

				public class ForgeHooks {
					public static final List<String> received = new ArrayList<>();

					public static boolean onCustomPayload(CustomPacketPayload payload, Connection connection) {
						received.add(payload.type().id().toString());
						return true;
					}
				}
				""");
		STAND_INS.put(NEO_REGISTRY, """
				package net.neoforged.neoforge.network.registration;

				import java.util.ArrayList;
				import java.util.List;
				import java.util.Map;
				import net.minecraft.network.protocol.Packet;
				import net.minecraft.network.protocol.common.ServerCommonPacketListener;
				import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
				import net.minecraft.resources.Identifier;
				import net.neoforged.neoforge.network.payload.CommonVersionPayload;

				public class NetworkRegistry {
					public static final Map<String, Map<Identifier, String>> PAYLOAD_REGISTRATIONS =
							Map.of("play", Map.of(new Identifier("neomod:sync"), "registered"));
					public static final List<Object> commonVersions = new ArrayList<>();

					public static boolean registered(String id) {
						return PAYLOAD_REGISTRATIONS.get("play").containsKey(new Identifier(id));
					}

					public static void checkCommonVersion(Object listener, CommonVersionPayload payload) {
						commonVersions.add(payload.versions());
					}

					/** NeoForge's channel police: what this connection did not negotiate may not be sent. */
					public static void checkPacket(Packet packet, ServerCommonPacketListener listener) {
						if (packet instanceof ServerboundCustomPayloadPacket custom) {
							String id = custom.payload().type().id().toString();
							if (!listener.negotiated(id)) throw new UnsupportedOperationException("Payload " + id + " may not be sent to the server!");
						}
					}
				}
				""");
		STAND_INS.put("net.neoforged.neoforge.network.registration.ClientNetworkRegistry", """
				package net.neoforged.neoforge.network.registration;

				import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;

				public class ClientNetworkRegistry {
					public static void sendInitialListeningChannels(ClientCommonPacketListenerImpl listener) {
						listener.handled.add("neoforge");
					}
				}
				""");
		STAND_INS.put("net.minecraftforge.network.config.ConfigurationTaskContext", """
				package net.minecraftforge.network.config;

				import java.util.function.Consumer;

				public class ConfigurationTaskContext {
					private final Consumer<String> send;

					public ConfigurationTaskContext(Consumer<String> send) {
						this.send = send;
					}

					public void send(String packet) {
						send.accept(packet);
					}
				}
				""");
		STAND_INS.put("net.minecraft.server.network.ConfigurationTask", """
				package net.minecraft.server.network;

				import java.util.function.Consumer;
				import net.minecraftforge.network.config.ConfigurationTaskContext;

				public interface ConfigurationTask {
					record Type(String id) {
					}

					Type type();

					void start(Consumer<String> send);

					/** MinecraftForge's overload; its default goes back to the vanilla one. */
					default void start(ConfigurationTaskContext context) {
						start(context::send);
					}
				}
				""");
		STAND_INS.put(SERVER_CONFIG, """
				package net.minecraft.server.network;

				import java.util.ArrayDeque;
				import java.util.ArrayList;
				import java.util.List;
				import java.util.Queue;
				import net.minecraftforge.network.config.ConfigurationTaskContext;

				public class ServerConfigurationPacketListenerImpl {
					public final Queue<ConfigurationTask> tasks = new ArrayDeque<>();
					public final List<String> sent = new ArrayList<>();
					private ConfigurationTask currentTask;
					/** MinecraftForge's. */
					private final ConfigurationTaskContext taskContext = new ConfigurationTaskContext(this::send);

					public void startConfiguration() {
						startNextTask();
					}

					public String current() {
						return currentTask == null ? null : currentTask.type().id();
					}

					/** Vanilla's. */
					public void finishCurrentTask(ConfigurationTask.Type type) {
						ConfigurationTask.Type current = currentTask != null ? currentTask.type() : null;
						if (!type.equals(current)) {
							throw new IllegalStateException("Unexpected request for task finish, current task: " + current + ", requested: " + type);
						}
						currentTask = null;
						startNextTask();
					}

					/** NeoForge's body: the vanilla start overload. */
					private void startNextTask() {
						if (currentTask != null) throw new IllegalStateException("Task " + currentTask.type().id() + " has not finished yet");
						ConfigurationTask task = tasks.poll();
						if (task != null) {
							currentTask = task;
							task.start(this::send);
						}
					}

					void send(String packet) {
						sent.add(packet);
					}
				}
				""");
		STAND_INS.put("fixture.Tasks", """
				package fixture;

				import java.util.function.Consumer;
				import net.minecraft.server.network.ConfigurationTask;
				import net.minecraftforge.network.config.ConfigurationTaskContext;

				public class Tasks {
					/** NeoForge's common-version task, vanilla-shaped. */
					public static class NeoVersion implements ConfigurationTask {
						public Type type() {
							return new Type("neoforge:common_version");
						}

						public void start(Consumer<String> send) {
							send.accept("neoforge:common_version");
						}
					}

					/** A MinecraftForge handshake task: it refuses the vanilla overload. */
					public static class ForgeHandshake implements ConfigurationTask {
						public Type type() {
							return new Type("forge:handshake");
						}

						public void start(Consumer<String> send) {
							throw new UnsupportedOperationException("MinecraftForge tasks start through their context");
						}

						@Override
						public void start(ConfigurationTaskContext context) {
							context.send("forge:handshake");
						}
					}
				}
				""");
		STAND_INS.put(CLIENT_COMMON, commonListener("net.minecraft.client.multiplayer", "ClientCommonPacketListenerImpl",
				"ClientboundCustomPayloadPacket"));
		STAND_INS.put(SERVER_COMMON, commonListener("net.minecraft.server.network", "ServerCommonPacketListenerImpl",
				"ServerboundCustomPayloadPacket"));
		STAND_INS.put(CLIENT_CONFIG, """
				package net.minecraft.client.multiplayer;

				import net.minecraft.network.Connection;
				import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
				import net.neoforged.neoforge.network.payload.MinecraftRegisterPayload;
				import net.neoforged.neoforge.network.registration.ClientNetworkRegistry;

				public class ClientConfigurationPacketListenerImpl extends ClientCommonPacketListenerImpl {
					private boolean initializedConnection;

					public ClientConfigurationPacketListenerImpl(Connection connection) {
						super(connection);
					}

					/** NeoForge's override: it answers the server's register and returns before super. */
					@Override
					public void handleCustomPayload(ClientboundCustomPayloadPacket packet) {
						if (!initializedConnection && packet.payload() instanceof MinecraftRegisterPayload) {
							ClientNetworkRegistry.sendInitialListeningChannels(this);
							return;
						}
						super.handleCustomPayload(packet);
					}
				}
				""");
		STAND_INS.put("net.minecraftforge.fml.config.ModConfig", """
				package net.minecraftforge.fml.config;

				import java.io.IOException;
				import java.io.UncheckedIOException;
				import java.nio.file.Files;
				import java.nio.file.Path;

				public class ModConfig {
					private final Path path;
					private final String held;

					public ModConfig(Path path, String held) {
						this.path = path;
						this.held = held;
					}

					public Path getFullPath() {
						return path;
					}

					public void save() {
						try {
							Files.writeString(path, held);
						} catch (IOException e) {
							throw new UncheckedIOException(e);
						}
					}
				}
				""");
		STAND_INS.put("net.minecraftforge.fml.config.ConfigTracker", """
				package net.minecraftforge.fml.config;

				import java.util.HashMap;
				import java.util.Map;
				import java.util.Set;

				public class ConfigTracker {
					public static final Map<String, Set<ModConfig>> SETS = new HashMap<>();

					public static Map<String, Set<ModConfig>> configSets() {
						return SETS;
					}
				}
				""");
		STAND_INS.put(SYNC_CONFIG, """
				package net.minecraftforge.network.tasks;

				import java.io.IOException;
				import java.nio.file.Files;
				import net.minecraft.network.Connection;
				import net.minecraftforge.fml.config.ModConfig;

				public class SyncConfigTask {
					private final Connection connection;
					private final ModConfig config;
					public byte[] sent;

					public SyncConfigTask(Connection connection, ModConfig config) {
						this.connection = connection;
						this.config = config;
					}

					public void start() {
						try {
							sent = Files.readAllBytes(config.getFullPath());
						} catch (IOException e) {
							connection.disconnect("Connection closed - Failed to read config on server");
						}
					}
				}
				""");
	}

	/** The same stand-ins as merged, and with every target transformed. */
	private record Game(ClassLoader merged, ClassLoader repaired) {
		Class<?> merged(String name) throws ClassNotFoundException {
			return merged.loadClass(name);
		}

		Class<?> repaired(String name) throws ClassNotFoundException {
			return repaired.loadClass(name);
		}
	}

	private static Game game(Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : TARGETS) {
			String internal = target.replace('.', '/');
			byte[] out = InjectorExecution.transform(new CommonNetworkInteropInjector(), target, original.get(internal), EnvType.CLIENT);
			assertNotSame(original.get(internal), out, target + " is the shape this repair keys on");
			classes.put(internal, out);
		}
		ClassLoader repaired = InjectorExecution.load(classes);
		for (String target : TARGETS) assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), repaired), target);
		return new Game(InjectorExecution.load(original), repaired);
	}

	private static Object identifier(Class<?> type, String id) throws Throwable {
		return InjectorExecution.construct(type, id);
	}

	@Test void finishingFabricsTaskFinishesNeoForgesAndAForgeTaskStarts(@TempDir Path work) throws Throwable {
		Game game = game(work);
		Object listener = configuration(game.repaired);
		Object fabricVersion = InjectorExecution.construct(game.repaired("net.minecraft.server.network.ConfigurationTask$Type"), "c:version");
		InjectorExecution.invoke(listener, "finishCurrentTask", fabricVersion);
		assertEquals("forge:handshake", InjectorExecution.invoke(listener, "current"), "NeoForge's equivalent task finished");
		assertEquals(List.of("neoforge:common_version", "forge:handshake"), listener.getClass().getField("sent").get(listener),
				"and MinecraftForge's task started through its context");
		Object unrelated = InjectorExecution.construct(game.repaired("net.minecraft.server.network.ConfigurationTask$Type"), "c:register");
		assertThrows(IllegalStateException.class, () -> InjectorExecution.invoke(listener, "finishCurrentTask", unrelated),
				"a task that is not the current one's equivalent is still refused, as vanilla refuses it");

		Object stock = configuration(game.merged);
		Object stockVersion = InjectorExecution.construct(game.merged("net.minecraft.server.network.ConfigurationTask$Type"), "c:version");
		assertTrue(assertThrows(IllegalStateException.class, () -> InjectorExecution.invoke(stock, "finishCurrentTask", stockVersion))
				.getMessage().startsWith("Unexpected request for task finish"), "premise: as merged, the client is refused");
		Object stockNeo = InjectorExecution.construct(game.merged("net.minecraft.server.network.ConfigurationTask$Type"), "neoforge:common_version");
		assertThrows(UnsupportedOperationException.class, () -> InjectorExecution.invoke(stock, "finishCurrentTask", stockNeo),
				"premise: as merged, MinecraftForge's task is started through the overload it refuses");
	}

	/** A server configuring a client: NeoForge's common-version task, then a MinecraftForge one; the first started. */
	private static Object configuration(ClassLoader loader) throws Throwable {
		Object listener = InjectorExecution.construct(loader.loadClass(SERVER_CONFIG));
		@SuppressWarnings("unchecked")
		java.util.Queue<Object> tasks = (java.util.Queue<Object>) listener.getClass().getField("tasks").get(listener);
		tasks.add(InjectorExecution.construct(loader.loadClass("fixture.Tasks$NeoVersion")));
		tasks.add(InjectorExecution.construct(loader.loadClass("fixture.Tasks$ForgeHandshake")));
		InjectorExecution.invoke(listener, "startConfiguration");
		return listener;
	}

	@Test void aMinecraftForgePayloadGoesToForgeHooksOnBothSides(@TempDir Path work) throws Throwable {
		Game game = game(work);
		for (String[] side : new String[][] {{CLIENT_COMMON, "ClientboundCustomPayloadPacket"}, {SERVER_COMMON, "ServerboundCustomPayloadPacket"}}) {
			for (boolean repaired : new boolean[] {true, false}) {
				ClassLoader loader = repaired ? game.repaired : game.merged;
				Object connection = InjectorExecution.construct(loader.loadClass("net.minecraft.network.Connection"));
				Object listener = InjectorExecution.construct(loader.loadClass(side[0]), connection);
				Class<?> packet = loader.loadClass("net.minecraft.network.protocol.common." + side[1]);
				Object forge = InjectorExecution.construct(loader.loadClass("net.minecraftforge.network.ForgePayload"),
						identifier(loader.loadClass("net.minecraft.resources.Identifier"), "forgemod:sync"));
				InjectorExecution.invoke(listener, "handleCustomPayload", InjectorExecution.construct(packet, forge));
				Object neo = InjectorExecution.construct(loader.loadClass("fixture.ModPayload"), "neomod:sync");
				InjectorExecution.invoke(listener, "handleCustomPayload", InjectorExecution.construct(packet, neo));
				Object received = InjectorExecution.getStatic(loader.loadClass("net.minecraftforge.common.ForgeHooks"), "received");
				Object disconnected = connection.getClass().getField("disconnected").get(connection);
				if (repaired) {
					assertNull(disconnected, side[0] + ": the connection stays up");
					assertEquals(List.of("forgemod:sync"), received, side[0] + ": MinecraftForge's dispatcher took its payload");
					assertEquals(List.of("neoforge neomod:sync"), listener.getClass().getField("handled").get(listener),
							side[0] + ": NeoForge's payload still reaches NeoForge's body");
				} else {
					assertEquals("No Channel for forgemod:sync", disconnected, side[0] + ": premise: as merged, NeoForge's body disconnects");
				}
				((List<?>) received).clear();
			}
		}
	}

	@Test void neoForgeDoesNotPoliceChannelsItDidNotNegotiate(@TempDir Path work) throws Throwable {
		Game game = game(work);
		java.util.function.Function<ClassLoader, java.util.function.Function<Object, Object>> send = loader -> payload -> {
			try {
				Object packet = InjectorExecution.construct(loader.loadClass("net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket"), payload);
				Object listener = java.lang.reflect.Proxy.newProxyInstance(loader,
						new Class<?>[] {loader.loadClass("net.minecraft.network.protocol.common.ServerCommonPacketListener")}, (p, m, a) -> false);
				InjectorExecution.invokeStatic(loader.loadClass(NEO_REGISTRY), "checkPacket", packet, listener);
				return "sent";
			} catch (Throwable refused) {
				return refused.getMessage();
			}
		};
		ClassLoader loader = game.repaired;
		Class<?> id = loader.loadClass("net.minecraft.resources.Identifier");
		assertEquals("sent", send.apply(loader).apply(InjectorExecution.construct(loader.loadClass("net.minecraftforge.network.ForgePayload"),
				identifier(id, "forgemod:sync"))), "a MinecraftForge payload is not NeoForge's to police");
		assertEquals("sent", send.apply(loader).apply(InjectorExecution.construct(loader.loadClass("fixture.ModPayload"), "polymer:hello")),
				"nor is a channel another ecosystem negotiated");
		assertEquals("Payload neomod:sync may not be sent to the server!",
				send.apply(loader).apply(InjectorExecution.construct(loader.loadClass("fixture.ModPayload"), "neomod:sync")),
				"a channel NeoForge registered is still policed");
		assertEquals("Payload minecraft:register may not be sent to the server!",
				send.apply(loader).apply(InjectorExecution.construct(loader.loadClass("net.neoforged.neoforge.network.payload.MinecraftRegisterPayload"),
						Set.of())), "and so is NeoForge's own");

		ClassLoader stock = game.merged;
		assertEquals("Payload forgemod:sync may not be sent to the server!",
				send.apply(stock).apply(InjectorExecution.construct(stock.loadClass("net.minecraftforge.network.ForgePayload"),
						identifier(stock.loadClass("net.minecraft.resources.Identifier"), "forgemod:sync"))),
				"premise: as merged, a MinecraftForge mod's client cannot send on its own channel");
	}

	@Test void aMissingServerConfigIsWrittenBackInsteadOfDisconnecting(@TempDir Path work) throws Throwable {
		Game game = game(work.resolve("classes"));
		for (boolean repaired : new boolean[] {true, false}) {
			ClassLoader loader = repaired ? game.repaired : game.merged;
			Path file = work.resolve((repaired ? "repaired" : "merged") + "-server.toml");
			Object config = InjectorExecution.construct(loader.loadClass("net.minecraftforge.fml.config.ModConfig"), file, "spawnRate = 3\n");
			@SuppressWarnings("unchecked")
			Map<String, Set<Object>> sets = (Map<String, Set<Object>>) InjectorExecution.getStatic(
					loader.loadClass("net.minecraftforge.fml.config.ConfigTracker"), "SETS");
			sets.put("SERVER", Set.of(config));
			Object connection = InjectorExecution.construct(loader.loadClass("net.minecraft.network.Connection"));
			Object task = InjectorExecution.construct(loader.loadClass(SYNC_CONFIG), connection, config);
			assertFalse(Files.exists(file), "the per-world config is not on disk when the handshake reads it");
			InjectorExecution.invoke(task, "start");
			Object disconnected = connection.getClass().getField("disconnected").get(connection);
			if (repaired) {
				assertNull(disconnected, "the joining client stays connected");
				assertEquals("spawnRate = 3\n", new String((byte[]) task.getClass().getField("sent").get(task)),
						"and is sent the config the server holds, written back first");
				assertTrue(Files.exists(file));
			} else {
				assertEquals("Connection closed - Failed to read config on server", disconnected, "premise: as merged, the join ends");
			}
		}
	}

	/**
	 * A carrier whose override already hands the server's register to super before its own reply (NeoForge 26.2.0.88's
	 * shape) gets no second super call: fabric-api's addon hears each register once, ahead of NeoForge, as shipped.
	 */
	@Test void aCarrierThatAlreadySharesTheRegisterGetsNoSecondDelivery(@TempDir Path work) throws Throwable {
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put(CLIENT_CONFIG, sources.get(CLIENT_CONFIG).replace(
				"if (!initializedConnection && packet.payload() instanceof MinecraftRegisterPayload) {\n",
				"if (!initializedConnection && packet.payload() instanceof MinecraftRegisterPayload) {\n\t\t\t\t\t\t\tsuper.handleCustomPayload(packet);\n"));
		assertNotEquals(STAND_INS.get(CLIENT_CONFIG), sources.get(CLIENT_CONFIG), "premise: the stand-in now shares the register itself");
		Map<String, byte[]> original = InjectorExecution.compile(work, sources);
		String internal = CLIENT_CONFIG.replace('.', '/');
		byte[] out = InjectorExecution.transform(new CommonNetworkInteropInjector(), CLIENT_CONFIG, original.get(internal), EnvType.CLIENT);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, out);
		ClassLoader loader = InjectorExecution.load(classes);
		Object listener = InjectorExecution.construct(loader.loadClass(CLIENT_CONFIG),
				InjectorExecution.construct(loader.loadClass("net.minecraft.network.Connection")));
		Object register = InjectorExecution.construct(loader.loadClass("net.neoforged.neoforge.network.payload.MinecraftRegisterPayload"), Set.of());
		InjectorExecution.invoke(listener, "handleCustomPayload",
				InjectorExecution.construct(loader.loadClass("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket"), register));
		assertEquals(List.of("fabric", "neoforge"), listener.getClass().getField("handled").get(listener),
				"fabric-api hears the register once, ahead of NeoForge's reply");
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(original.get(internal)).accept(node, 0);
		org.objectweb.asm.tree.MethodNode handle = node.methods.stream().filter(m -> m.name.equals("handleCustomPayload")).findFirst().orElseThrow();
		assertEquals(0, CommonNetworkInteropInjector.shareMinecraftRegisterWithSuper(handle), "recognised as already shared");
	}

	@Test void fabricAnswersTheServersRegisterBeforeNeoForge(@TempDir Path work) throws Throwable {
		Game game = game(work);
		for (boolean repaired : new boolean[] {true, false}) {
			ClassLoader loader = repaired ? game.repaired : game.merged;
			Object listener = InjectorExecution.construct(loader.loadClass(CLIENT_CONFIG),
					InjectorExecution.construct(loader.loadClass("net.minecraft.network.Connection")));
			Object register = InjectorExecution.construct(loader.loadClass("net.neoforged.neoforge.network.payload.MinecraftRegisterPayload"), Set.of());
			InjectorExecution.invoke(listener, "handleCustomPayload",
					InjectorExecution.construct(loader.loadClass("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket"), register));
			assertEquals(repaired ? List.of("fabric", "neoforge") : List.of("neoforge"), listener.getClass().getField("handled").get(listener),
					repaired ? "fabric-api's first register goes out ahead of NeoForge's" : "premise: as merged, fabric-api never sees it");
		}
	}
}
