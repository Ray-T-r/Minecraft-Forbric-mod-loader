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
 * {@link CommonNetworkInteropInjector}'s output, run against the kernel's real boot-side {@code PayloadInterop}: its
 * repairs, each on stand-ins for the class it edits, each with the unrepaired failure it prevents. (A Fabric receiver
 * handed NeoForge's payload of a shared channel is {@code FabricReceiverArbitrationExecutionTest}'s.)
 * <ul>
 *   <li>the server finishing Fabric's {@code c:version} task while NeoForge's equivalent is current: the next task
 *       starts — unrepaired, "Unexpected request for task finish";</li>
 *   <li>NeoForge's {@code checkPacket}: a payload on a channel another ecosystem negotiated is not policed, and a
 *       NeoForge-registered or vanilla one still is — unrepaired, Polymer's client could not send on its own channel;</li>
 *   <li>and, unclaimed, the client's register answered by fabric-api before NeoForge.</li>
 * </ul>
 * Not run here: the play-phase hand-over to NeoForge, which {@code PlayPayloadHandOverExecutionTest} runs on stand-ins
 * of its own. The injector has no switch of its own:
 * {@code -Dforbric.commonNetworkInterop=off} is read where KernelBoot registers it.
 */
@ExecutesInjector(CommonNetworkInteropInjector.class)
class CommonNetworkInteropInjectorExecutionTest {
	private static final String SERVER_CONFIG = "net.minecraft.server.network.ServerConfigurationPacketListenerImpl";
	private static final String CLIENT_COMMON = "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl";
	private static final String SERVER_COMMON = "net.minecraft.server.network.ServerCommonPacketListenerImpl";
	private static final String CLIENT_CONFIG = "net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl";
	private static final String NEO_REGISTRY = "net.neoforged.neoforge.network.registration.NetworkRegistry";
	private static final List<String> TARGETS = List.of(SERVER_CONFIG, CLIENT_CONFIG, NEO_REGISTRY);

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
		STAND_INS.put("net.minecraft.server.network.ConfigurationTask", """
				package net.minecraft.server.network;

				import java.util.function.Consumer;

				public interface ConfigurationTask {
					record Type(String id) {
					}

					Type type();

					void start(Consumer<String> send);
				}
				""");
		STAND_INS.put(SERVER_CONFIG, """
				package net.minecraft.server.network;

				import java.util.ArrayDeque;
				import java.util.ArrayList;
				import java.util.List;
				import java.util.Queue;

				public class ServerConfigurationPacketListenerImpl {
					public final Queue<ConfigurationTask> tasks = new ArrayDeque<>();
					public final List<String> sent = new ArrayList<>();
					private ConfigurationTask currentTask;

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

					/** A vanilla-shaped task that runs next. */
					public static class Next implements ConfigurationTask {
						public Type type() {
							return new Type("minecraft:next");
						}

						public void start(Consumer<String> send) {
							send.accept("minecraft:next");
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
	}

	/** The same stand-ins as compiled, and with every target transformed. */
	private record Game(ClassLoader stock, ClassLoader repaired) {
		Class<?> stock(String name) throws ClassNotFoundException {
			return stock.loadClass(name);
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

	@Test void finishingFabricsTaskFinishesNeoForgesAndTheNextTaskStarts(@TempDir Path work) throws Throwable {
		Game game = game(work);
		Object listener = configuration(game.repaired);
		Object fabricVersion = InjectorExecution.construct(game.repaired("net.minecraft.server.network.ConfigurationTask$Type"), "c:version");
		InjectorExecution.invoke(listener, "finishCurrentTask", fabricVersion);
		assertEquals("minecraft:next", InjectorExecution.invoke(listener, "current"), "NeoForge's equivalent task finished");
		assertEquals(List.of("neoforge:common_version", "minecraft:next"), listener.getClass().getField("sent").get(listener),
				"and the next task started");
		Object unrelated = InjectorExecution.construct(game.repaired("net.minecraft.server.network.ConfigurationTask$Type"), "c:register");
		assertThrows(IllegalStateException.class, () -> InjectorExecution.invoke(listener, "finishCurrentTask", unrelated),
				"a task that is not the current one's equivalent is still refused, as vanilla refuses it");

		Object stock = configuration(game.stock);
		Object stockVersion = InjectorExecution.construct(game.stock("net.minecraft.server.network.ConfigurationTask$Type"), "c:version");
		assertTrue(assertThrows(IllegalStateException.class, () -> InjectorExecution.invoke(stock, "finishCurrentTask", stockVersion))
				.getMessage().startsWith("Unexpected request for task finish"), "premise: unrepaired, the client is refused");
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
		assertEquals("sent", send.apply(loader).apply(InjectorExecution.construct(loader.loadClass("fixture.ModPayload"), "polymer:hello")),
				"a channel another ecosystem negotiated is not NeoForge's to police");
		assertEquals("Payload neomod:sync may not be sent to the server!",
				send.apply(loader).apply(InjectorExecution.construct(loader.loadClass("fixture.ModPayload"), "neomod:sync")),
				"a channel NeoForge registered is still policed");
		assertEquals("Payload minecraft:register may not be sent to the server!",
				send.apply(loader).apply(InjectorExecution.construct(loader.loadClass("net.neoforged.neoforge.network.payload.MinecraftRegisterPayload"),
						Set.of())), "and so is NeoForge's own");

		ClassLoader stock = game.stock;
		assertEquals("Payload polymer:hello may not be sent to the server!",
				send.apply(stock).apply(InjectorExecution.construct(stock.loadClass("fixture.ModPayload"), "polymer:hello")),
				"premise: unrepaired, a Polymer client cannot send on its own channel");
	}

	/** A server configuring a client: NeoForge's common-version task, then a vanilla-shaped one; the first started. */
	private static Object configuration(ClassLoader loader) throws Throwable {
		Object listener = InjectorExecution.construct(loader.loadClass(SERVER_CONFIG));
		@SuppressWarnings("unchecked")
		java.util.Queue<Object> tasks = (java.util.Queue<Object>) listener.getClass().getField("tasks").get(listener);
		tasks.add(InjectorExecution.construct(loader.loadClass("fixture.Tasks$NeoVersion")));
		tasks.add(InjectorExecution.construct(loader.loadClass("fixture.Tasks$Next")));
		InjectorExecution.invoke(listener, "startConfiguration");
		return listener;
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
			ClassLoader loader = repaired ? game.repaired : game.stock;
			Object listener = InjectorExecution.construct(loader.loadClass(CLIENT_CONFIG),
					InjectorExecution.construct(loader.loadClass("net.minecraft.network.Connection")));
			Object register = InjectorExecution.construct(loader.loadClass("net.neoforged.neoforge.network.payload.MinecraftRegisterPayload"), Set.of());
			InjectorExecution.invoke(listener, "handleCustomPayload",
					InjectorExecution.construct(loader.loadClass("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket"), register));
			assertEquals(repaired ? List.of("fabric", "neoforge") : List.of("neoforge"), listener.getClass().getField("handled").get(listener),
					repaired ? "fabric-api's first register goes out ahead of NeoForge's" : "premise: unrepaired, fabric-api never sees it");
		}
	}
}
