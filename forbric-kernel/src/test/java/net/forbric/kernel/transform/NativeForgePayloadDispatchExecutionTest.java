/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;

/**
 * {@link CommonNetworkInteropInjector} on a merged listener that kept MinecraftForge's own call to
 * {@code ForgeHooks.onCustomPayload(payload, connection)} — what a base that splices both families' edits of
 * {@code handleCustomPayload} produces — run against the kernel's real boot-side {@code PayloadInterop}. The two
 * listeners keep it in two shapes: the client's {@code if (ForgeHooks.onCustomPayload(...)) return;}, and the
 * server's bare call whose answer is POPped (MinecraftForge's own server body is empty), with NeoForge's body behind
 * it either way.
 *
 * <p>The stand-in {@code ForgeHooks} does what MinecraftForge's does with a payload for a channel it has a target for:
 * it builds a {@code CustomPayloadEvent}, whose data buffer is taken from a {@code ForgePayload} and is null for
 * anything else, and the channel throws "Received empty payload" on null. {@code minecraft:unregister} has a target
 * (Forge's channel-registration channel claims it) but on a merged base decodes as NeoForge's record, because the
 * decode puts Forge last. As merged, that one packet ends every join without fabric-api.
 *
 * <p>The negative controls are the other shapes: a listener that does NOT carry Forge's call, and one whose call's
 * answer ends nothing, still get the kernel's dispatch prologue, so a stand-down keyed on the call cannot leave a base
 * without a Forge dispatcher or hand a ForgePayload on to NeoForge's body after Forge took it.
 */
@ExecutesInjector(CommonNetworkInteropInjector.class)
@ResourceLock("system-properties")
class NativeForgePayloadDispatchExecutionTest {
	private static final String CLIENT_COMMON = "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl";
	private static final String SERVER_COMMON = "net.minecraft.server.network.ServerCommonPacketListenerImpl";
	private static final String SERVER_GAME = "net.minecraft.server.network.ServerGamePacketListenerImpl";
	private static final String INTEROP = "net/forbric/kernel/interop/PayloadInterop";
	private static final String HOOK_DESC = "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;"
			+ "Lnet/minecraft/network/Connection;)Z";
	private static final String SWITCH = "forbric.playPayloadFallThrough";

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

				import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

				public record %s(CustomPacketPayload payload) {
				}
				""".formatted(name);
	}

	/** How a merged listener carries MinecraftForge's call, if at all, in front of NeoForge's body. */
	enum Forge {
		/** NeoForge won the method: its body alone. */
		ABSENT(""),
		/** The client listener's patch: Forge's answer ends the method. */
		RETURNS("if (net.minecraftforge.common.ForgeHooks.onCustomPayload(packet.payload(), this.connection)) return;"),
		/** The server listener's patch: Forge's answer is discarded, and NeoForge's body runs whatever it was. */
		DISCARDED("net.minecraftforge.common.ForgeHooks.onCustomPayload(packet.payload(), this.connection);");

		final String statement;

		Forge(String statement) {
			this.statement = statement;
		}
	}

	/**
	 * A common listener as the merge leaves it: MinecraftForge's call in {@code forge}'s shape, then NeoForge's body —
	 * its own unregister bookkeeping, then {@code isModdedPayload} into its dispatcher, which disconnects on a channel
	 * it never registered, and vanilla's path for anything else.
	 */
	private static String commonListener(String pkg, String name, String packet, Forge forge) {
		return """
				package %s;

				import java.util.ArrayList;
				import java.util.List;
				import net.minecraft.network.Connection;
				import net.minecraft.network.protocol.common.%s;
				import net.neoforged.neoforge.network.payload.MinecraftUnregisterPayload;
				import net.neoforged.neoforge.network.registration.NetworkRegistry;

				public class %s {
					protected final Connection connection;
					public final List<String> handled = new ArrayList<>();

					public %s(Connection connection) {
						this.connection = connection;
					}

					public void handleCustomPayload(%s packet) {
						%s
						if (packet.payload() instanceof MinecraftUnregisterPayload unregister) {
							handled.add("neoforge unregister " + unregister.channels());
							return;
						}
						String id = packet.payload().type().id().toString();
						if (NetworkRegistry.isModdedPayload(packet.payload())) {
							if (NetworkRegistry.registered(id)) handled.add("neoforge " + id);
							else connection.disconnect("No Channel for " + id);
							return;
						}
						handled.add("vanilla " + id);
					}
				}
				""".formatted(pkg, packet, name, name, packet, forge.statement);
	}

	private static Map<String, String> standIns(Forge forge) {
		Map<String, String> sources = new HashMap<>();
		sources.put("net.minecraft.resources.Identifier", """
				package net.minecraft.resources;

				public record Identifier(String id) {
					@Override
					public String toString() {
						return id;
					}
				}
				""");
		sources.put("net.minecraft.network.protocol.common.custom.CustomPacketPayload", """
				package net.minecraft.network.protocol.common.custom;

				import net.minecraft.resources.Identifier;

				public interface CustomPacketPayload {
					record Type(Identifier id) {
					}

					Type type();
				}
				""");
		sources.put("net.minecraft.network.Connection", """
				package net.minecraft.network;

				public class Connection {
					public String disconnected;

					public void disconnect(String reason) {
						disconnected = reason;
					}
				}
				""");
		sources.put("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket", customPacket("ClientboundCustomPayloadPacket"));
		sources.put("net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket", customPacket("ServerboundCustomPayloadPacket"));
		sources.put("net.minecraftforge.network.ForgePayload", payload("net.minecraftforge.network.ForgePayload",
				"Identifier channel, String data", "channel"));
		sources.put("fixture.ModPayload", payload("fixture.ModPayload", "String id", "new Identifier(id)"));
		sources.put("net.neoforged.neoforge.network.payload.MinecraftUnregisterPayload",
				payload("net.neoforged.neoforge.network.payload.MinecraftUnregisterPayload", "Set<Identifier> channels",
						"new Identifier(\"minecraft:unregister\")").replace("import net.minecraft.resources.Identifier;",
						"import java.util.Set;\nimport net.minecraft.resources.Identifier;"));
		sources.put("net.minecraftforge.common.ForgeHooks", """
				package net.minecraftforge.common;

				import java.util.ArrayList;
				import java.util.List;
				import net.minecraft.network.Connection;
				import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
				import net.minecraftforge.network.ForgePayload;

				public class ForgeHooks {
					public static final List<String> received = new ArrayList<>();

					/**
					 * MinecraftForge's dispatch: a channel it has a target for gets an event whose data is the
					 * ForgePayload's buffer, or null for any other payload — and the channel refuses null.
					 */
					public static boolean onCustomPayload(CustomPacketPayload payload, Connection connection) {
						String id = payload.type().id().toString();
						String target = id.equals("minecraft:register") || id.equals("minecraft:unregister")
								? "forge:channel_registration" : id.startsWith("forgemod:") ? id : null;
						if (target == null) return false;
						String data = payload instanceof ForgePayload forge ? forge.data() : null;
						if (data == null) {
							throw new IllegalArgumentException("Received empty payload on channel " + target + ": " + id);
						}
						received.add(id + "=" + data);
						return true;
					}
				}
				""");
		sources.put(CLIENT_COMMON, commonListener("net.minecraft.client.multiplayer", "ClientCommonPacketListenerImpl",
				"ClientboundCustomPayloadPacket", forge));
		sources.put(SERVER_COMMON, commonListener("net.minecraft.server.network", "ServerCommonPacketListenerImpl",
				"ServerboundCustomPayloadPacket", forge));
		sources.put("net.minecraft.network.protocol.common.ServerCommonPacketListener",
				"package net.minecraft.network.protocol.common; public interface ServerCommonPacketListener { }");
		sources.put("net.neoforged.neoforge.network.registration.NetworkRegistry", """
				package net.neoforged.neoforge.network.registration;

				import java.util.ArrayList;
				import java.util.List;
				import java.util.Map;
				import net.minecraft.network.protocol.common.ServerCommonPacketListener;
				import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
				import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
				import net.minecraft.resources.Identifier;

				public class NetworkRegistry {
					public static final List<String> dispatched = new ArrayList<>();
					static final Map<String, Map<Identifier, String>> PAYLOAD_REGISTRATIONS =
							Map.of("play", Map.of(new Identifier("neomod:key"), "registered by a NeoForge mod"));

					/** NeoForge's question: anything outside the minecraft namespace goes to its dispatcher. */
					public static boolean isModdedPayload(CustomPacketPayload payload) {
						return !payload.type().id().toString().startsWith("minecraft:");
					}

					public static boolean registered(String id) {
						for (Map<Identifier, String> protocol : PAYLOAD_REGISTRATIONS.values()) {
							if (protocol.containsKey(new Identifier(id))) return true;
						}
						return false;
					}

					public static void handleModdedPayload(ServerCommonPacketListener listener, ServerboundCustomPayloadPacket packet) {
						dispatched.add("neoforge " + packet.payload().type().id());
					}
				}
				""");
		// The play listener: MinecraftForge's override on every base — ask, drop the answer, return.
		sources.put(SERVER_GAME, """
				package net.minecraft.server.network;

				import net.minecraft.network.Connection;
				import net.minecraft.network.protocol.common.ServerCommonPacketListener;
				import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
				import net.minecraftforge.common.ForgeHooks;

				public class ServerGamePacketListenerImpl implements ServerCommonPacketListener {
					protected final Connection connection = new Connection();

					public void handleCustomPayload(ServerboundCustomPayloadPacket packet) {
						ForgeHooks.onCustomPayload(packet.payload(), this.connection);
					}
				}
				""");
		return sources;
	}

	private record Game(Map<String, byte[]> merged, Map<String, byte[]> repaired) {
		ClassLoader mergedLoader() {
			return InjectorExecution.load(merged);
		}

		ClassLoader repairedLoader() {
			return InjectorExecution.load(repaired);
		}
	}

	private static Game game(Path work, Forge forge) throws Exception {
		return game(work, forge, false);
	}

	/**
	 * The stand-ins compiled, and the repaired copy as the kernel's chain leaves it: with {@code receive}, first
	 * {@link ForeignPayloadReceiveInjector} (registered ahead of this repair in the same phase), then this repair.
	 */
	private static Game game(Path work, Forge forge, boolean receive) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, standIns(forge));
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : List.of(CLIENT_COMMON, SERVER_COMMON, SERVER_GAME)) {
			String internal = target.replace('.', '/');
			byte[] in = original.get(internal);
			if (receive && !target.equals(SERVER_GAME)) {
				byte[] received = InjectorExecution.transform(new ForeignPayloadReceiveInjector(), target, in, EnvType.CLIENT);
				assertNotSame(in, received, target + " asks isModdedPayload, which the receive repair follows");
				in = received;
			}
			byte[] out = InjectorExecution.transform(new CommonNetworkInteropInjector(), target, in, EnvType.CLIENT);
			assertNotSame(in, out, target + " is a shape this repair edits");
			classes.put(internal, out);
		}
		ClassLoader repaired = InjectorExecution.load(classes);
		for (String target : List.of(CLIENT_COMMON, SERVER_COMMON, SERVER_GAME)) {
			assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), repaired), target);
		}
		return new Game(original, classes);
	}

	@AfterEach void reset() {
		System.clearProperty(SWITCH);
	}

	/** Each common listener receives a MinecraftForge mod's payload, then NeoForge's unregister, then a NeoForge mod's. */
	private static String receive(ClassLoader loader, String listenerName, String packetName, List<Object> outcome) throws Throwable {
		Object connection = InjectorExecution.construct(loader.loadClass("net.minecraft.network.Connection"));
		Object listener = InjectorExecution.construct(loader.loadClass(listenerName), connection);
		Class<?> packet = loader.loadClass("net.minecraft.network.protocol.common." + packetName);
		Class<?> id = loader.loadClass("net.minecraft.resources.Identifier");
		Object forge = InjectorExecution.construct(loader.loadClass("net.minecraftforge.network.ForgePayload"),
				InjectorExecution.construct(id, "forgemod:sync"), "hello");
		Object unregister = InjectorExecution.construct(loader.loadClass("net.neoforged.neoforge.network.payload.MinecraftUnregisterPayload"),
				Set.of(InjectorExecution.construct(id, "neomod:key")));
		Object neo = InjectorExecution.construct(loader.loadClass("fixture.ModPayload"), "neomod:key");
		try {
			for (Object payload : List.of(forge, unregister, neo)) {
				InjectorExecution.invoke(listener, "handleCustomPayload", InjectorExecution.construct(packet, payload));
			}
		} catch (IllegalArgumentException thrown) {
			return thrown.getMessage();
		} finally {
			outcome.add(InjectorExecution.getStatic(loader.loadClass("net.minecraftforge.common.ForgeHooks"), "received"));
			outcome.add(listener.getClass().getField("handled").get(listener));
			outcome.add(connection.getClass().getField("disconnected").get(connection));
		}
		return null;
	}

	@Test void aListenerThatReturnsOnForgesOwnCallOffersForgeOnlyItsOwnPayloadOnce(@TempDir Path work) throws Throwable {
		Game game = game(work, Forge.RETURNS);
		for (String[] side : new String[][] {{CLIENT_COMMON, "ClientboundCustomPayloadPacket"}, {SERVER_COMMON, "ServerboundCustomPayloadPacket"}}) {
			MethodNode handler = handler(game.repaired.get(side[0].replace('.', '/')), side[0]);
			assertEquals(0, calls(handler, "net/minecraftforge/common/ForgeHooks", "onCustomPayload"),
					side[0] + ": no call hands Forge's dispatcher an unfiltered payload");
			assertEquals(1, calls(handler, INTEROP, "offerToForge"), side[0] + ": the base's own call is the one dispatch");
			assertEquals(0, calls(handler, INTEROP, "dispatchForgePayload"),
					side[0] + ": the prologue stands by — in front of that call it would be a second dispatcher");

			List<Object> outcome = new java.util.ArrayList<>();
			assertNull(receive(game.repairedLoader(), side[0], side[1], outcome), side[0] + ": nothing throws");
			assertEquals(List.of("forgemod:sync=hello"), outcome.get(0),
					side[0] + ": MinecraftForge's payload reached its dispatcher exactly once, and NeoForge's unregister never did");
			assertEquals(List.of("neoforge unregister [neomod:key]", "neoforge neomod:key"), outcome.get(1),
					side[0] + ": NeoForge's unregister and its mod's payload reached NeoForge's body, and Forge's did not");
			assertNull(outcome.get(2), side[0] + ": the connection stays up");

			List<Object> stock = new java.util.ArrayList<>();
			assertEquals("Received empty payload on channel forge:channel_registration: minecraft:unregister",
					receive(game.mergedLoader(), side[0], side[1], stock),
					side[0] + ": premise: as merged, NeoForge's unregister reaches Forge's channel without data and the join dies");
		}
	}

	/**
	 * The merged server listener's shape: Forge's call with its answer POPped and NeoForge's body right behind. A
	 * prologue standing by there let every ForgePayload, after Forge's dispatcher had it, go on into NeoForge's body,
	 * where only the receive repair's registry lookup kept it out of NeoForge's dispatcher — and with
	 * {@code -Dforbric.foreignPayloadReceive=off}, a switch about other families' channels, every MinecraftForge
	 * handshake ended in "No Channel for". The prologue goes in front; the filtered call behind it answers false.
	 */
	@Test void aListenerThatDiscardsForgesAnswerStillGetsTheKernelsDispatcherInFront(@TempDir Path work) throws Throwable {
		for (boolean receive : new boolean[] {true, false}) {
			Path dir = java.nio.file.Files.createDirectories(work.resolve("receive-" + receive));
			Game game = game(dir.resolve("repaired"), Forge.DISCARDED, receive);
			for (String[] side : new String[][] {{CLIENT_COMMON, "ClientboundCustomPayloadPacket"}, {SERVER_COMMON, "ServerboundCustomPayloadPacket"}}) {
				String where = side[0] + " (foreignPayloadReceive " + (receive ? "on" : "off") + ")";
				MethodNode handler = handler(game.repaired.get(side[0].replace('.', '/')), side[0]);
				assertEquals(0, calls(handler, "net/minecraftforge/common/ForgeHooks", "onCustomPayload"),
						where + ": no call hands Forge's dispatcher an unfiltered payload");
				assertEquals(1, calls(handler, INTEROP, "offerToForge"), where + ": the base's own call is filtered in place");
				assertEquals(1, calls(handler, INTEROP, "dispatchForgePayload"),
						where + ": that call's answer ends nothing, so the kernel's prologue is the dispatcher, in front of it");

				List<Object> outcome = new java.util.ArrayList<>();
				assertNull(receive(game.repairedLoader(), side[0], side[1], outcome), where + ": nothing throws");
				assertEquals(List.of("forgemod:sync=hello"), outcome.get(0),
						where + ": MinecraftForge's payload reached its dispatcher exactly once, and NeoForge's unregister never did");
				assertEquals(List.of("neoforge unregister [neomod:key]", "neoforge neomod:key"), outcome.get(1),
						where + ": NeoForge's body saw its own two payloads and never MinecraftForge's");
				assertNull(outcome.get(2), where + ": the connection stays up");

				// The defect, as the stand-by left it: the base's call filtered and no prologue in front of it.
				Map<String, byte[]> standingBy = new HashMap<>(game.merged);
				String internal = side[0].replace('.', '/');
				byte[] in = game.merged.get(internal);
				if (receive) in = InjectorExecution.transform(new ForeignPayloadReceiveInjector(), side[0], in, EnvType.CLIENT);
				standingBy.put(internal, filterOnly(in));
				List<Object> stood = new java.util.ArrayList<>();
				assertNull(receive(InjectorExecution.load(standingBy), side[0], side[1], stood), where);
				assertEquals(List.of("forgemod:sync=hello"), stood.get(0), where + ": premise: Forge took its payload there too");
				if (receive) {
					assertEquals(List.of("vanilla forgemod:sync", "neoforge unregister [neomod:key]", "neoforge neomod:key"), stood.get(1),
							where + ": premise: standing by, the ForgePayload went on into NeoForge's body after Forge took it");
					assertNull(stood.get(2), where + ": premise: and only the receive repair's lookup kept the connection up");
				} else {
					assertEquals("No Channel for forgemod:sync", stood.get(2),
							where + ": premise: standing by, NeoForge's dispatcher closed the connection on Forge's payload");
				}
			}
		}
	}

	@Test void aListenerWithoutForgesCallStillGetsTheKernelsDispatcher(@TempDir Path work) throws Throwable {
		Game game = game(work, Forge.ABSENT);
		for (String[] side : new String[][] {{CLIENT_COMMON, "ClientboundCustomPayloadPacket"}, {SERVER_COMMON, "ServerboundCustomPayloadPacket"}}) {
			MethodNode handler = handler(game.repaired.get(side[0].replace('.', '/')), side[0]);
			assertEquals(1, calls(handler, INTEROP, "dispatchForgePayload"),
					side[0] + ": with no call of Forge's to filter, the kernel's prologue is the dispatcher");
			assertEquals(0, calls(handler, INTEROP, "offerToForge"), side[0]);

			List<Object> outcome = new java.util.ArrayList<>();
			assertNull(receive(game.repairedLoader(), side[0], side[1], outcome), side[0] + ": nothing throws");
			assertEquals(List.of("forgemod:sync=hello"), outcome.get(0), side[0] + ": MinecraftForge's payload reached its dispatcher once");
			assertEquals(List.of("neoforge unregister [neomod:key]", "neoforge neomod:key"), outcome.get(1), side[0]);
			assertNull(outcome.get(2), side[0] + ": the connection stays up");

			List<Object> stock = new java.util.ArrayList<>();
			assertNull(receive(game.mergedLoader(), side[0], side[1], stock), side[0]);
			assertEquals("No Channel for forgemod:sync", stock.get(2),
					side[0] + ": premise: as merged, NeoForge's body disconnects on MinecraftForge's payload");
		}
	}

	/** The play listener's override carries the same call on every base, and gets the same filter behind its fall-through. */
	@Test void thePlayOverrideOffersForgeOnlyItsOwnPayloadAndNeoForgeStillGetsItsOwn(@TempDir Path work) throws Throwable {
		Game game = game(work, Forge.ABSENT);
		MethodNode handler = handler(game.repaired.get(SERVER_GAME.replace('.', '/')), SERVER_GAME);
		assertEquals(0, calls(handler, "net/minecraftforge/common/ForgeHooks", "onCustomPayload"));
		assertEquals(1, calls(handler, INTEROP, "offerToForge"));
		assertEquals(1, calls(handler, INTEROP, "neoForgeWillHandle"), "the fall-through still landed");

		assertEquals(List.of(List.of("forgemod:sync=hello"), List.of("neoforge neomod:key")), play(game.repairedLoader()),
				"Forge's payload to Forge once; NeoForge's unregister to nobody (NeoForge registered no handler for it); "
						+ "the NeoForge mod's payload to NeoForge");
		assertThrows(IllegalArgumentException.class, () -> play(game.mergedLoader()),
				"premise: as merged, NeoForge's unregister reaches Forge's channel without data");
	}

	/** The off switch leaves out the fall-through; the filter is not the fall-through and stays. */
	@Test void theFallThroughSwitchDoesNotTurnTheFilterOff(@TempDir Path work) throws Throwable {
		System.setProperty(SWITCH, "off");
		Map<String, byte[]> original = InjectorExecution.compile(work, standIns(Forge.ABSENT));
		String internal = SERVER_GAME.replace('.', '/');
		byte[] out = InjectorExecution.transform(new CommonNetworkInteropInjector(), SERVER_GAME, original.get(internal), EnvType.CLIENT);
		MethodNode was = handler(original.get(internal), SERVER_GAME);
		MethodNode now = handler(out, SERVER_GAME);
		assertEquals(0, calls(now, INTEROP, "neoForgeWillHandle"), "no fall-through with the switch off");
		assertEquals(1, calls(now, INTEROP, "offerToForge"), "the call is filtered");
		assertEquals(was.instructions.size(), now.instructions.size(), "and nothing else about the override moved");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, out);
		assertEquals(List.of(List.of("forgemod:sync=hello"), List.of()), play(InjectorExecution.load(classes)),
				"off: the NeoForge mod's payload is dropped as merged, and nothing throws");
	}

	private static List<Object> play(ClassLoader loader) throws Throwable {
		Object listener = InjectorExecution.construct(loader.loadClass(SERVER_GAME));
		Class<?> packet = loader.loadClass("net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket");
		Class<?> id = loader.loadClass("net.minecraft.resources.Identifier");
		Object forge = InjectorExecution.construct(loader.loadClass("net.minecraftforge.network.ForgePayload"),
				InjectorExecution.construct(id, "forgemod:sync"), "hello");
		Object unregister = InjectorExecution.construct(loader.loadClass("net.neoforged.neoforge.network.payload.MinecraftUnregisterPayload"),
				Set.of(InjectorExecution.construct(id, "neomod:key")));
		Object neo = InjectorExecution.construct(loader.loadClass("fixture.ModPayload"), "neomod:key");
		for (Object payload : List.of(forge, unregister, neo)) {
			InjectorExecution.invoke(listener, "handleCustomPayload", InjectorExecution.construct(packet, payload));
		}
		return List.of(InjectorExecution.getStatic(loader.loadClass("net.minecraftforge.common.ForgeHooks"), "received"),
				InjectorExecution.getStatic(loader.loadClass("net.neoforged.neoforge.network.registration.NetworkRegistry"), "dispatched"));
	}

	/** What the stand-by did: every call of Forge's hook pointed at the filter, and nothing else. */
	private static byte[] filterOnly(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals("net/minecraftforge/common/ForgeHooks")
						&& call.name.equals("onCustomPayload")) {
					call.owner = INTEROP;
					call.name = "offerToForge";
					call.desc = "(Ljava/lang/Object;Ljava/lang/Object;)Z";
				}
			}
		}
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static MethodNode handler(byte[] bytes, String owner) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (m.name.equals("handleCustomPayload")) return m;
		}
		throw new AssertionError("no handleCustomPayload in " + owner);
	}

	private static int calls(MethodNode m, String owner, String name) {
		int n = 0;
		for (AbstractInsnNode insn : m.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& owner.equals(call.owner) && name.equals(call.name)) {
				if (owner.equals("net/minecraftforge/common/ForgeHooks")) assertEquals(HOOK_DESC, call.desc);
				n++;
			}
		}
		return n;
	}
}
