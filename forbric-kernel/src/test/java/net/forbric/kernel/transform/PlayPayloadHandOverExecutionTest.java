/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;

/**
 * {@link CommonNetworkInteropInjector}'s play-phase hand-over, run: a NeoForge mod's packet to the server reaches
 * NeoForge's dispatcher, and the player stays connected.
 *
 * <p>The stand-ins keep the merged shapes that matter. {@code ServerGamePacketListenerImpl.handleCustomPayload} is
 * MinecraftForge's override: ask {@code ForgeHooks.onCustomPayload}, drop the answer, return. Its super,
 * {@code ServerCommonPacketListenerImpl.handleCustomPayload}, opens with fabric-api's injection, which serves only
 * the configuration listener and throws {@code IllegalStateException: Unknown addon} for the play listener — so the
 * stand-in super is exactly that throw. The hook deciding whether NeoForge owns a payload is the kernel's real
 * {@code PayloadInterop}, reading the stand-in registry's {@code PAYLOAD_REGISTRATIONS}.
 *
 * <p>Carry On is the payload: its client says "the carry key is held" with {@code carryon:key_pressed}, and as merged
 * the server never heard it, so nothing could ever be picked up.
 */
@ExecutesInjector(CommonNetworkInteropInjector.class)
@ResourceLock("system-properties")
class PlayPayloadHandOverExecutionTest {
	private static final String GAME_LISTENER = "net.minecraft.server.network.ServerGamePacketListenerImpl";
	private static final String SWITCH = "forbric.playPayloadFallThrough";

	private static final Map<String, String> STAND_INS = Map.ofEntries(
			Map.entry("fixture.Trace", """
					package fixture;
					public final class Trace {
						public static final java.util.List<String> events = new java.util.ArrayList<>();
					}
					"""),
			Map.entry("net.minecraft.resources.Identifier", """
					package net.minecraft.resources;

					public record Identifier(String id) {
						@Override
						public String toString() {
							return id;
						}
					}
					"""),
			Map.entry("net.minecraft.network.protocol.common.custom.CustomPacketPayload", """
					package net.minecraft.network.protocol.common.custom;

					import net.minecraft.resources.Identifier;

					public interface CustomPacketPayload {
						record Type(Identifier id) {
						}

						Type type();
					}
					"""),
			Map.entry("fixture.ModPayload", """
					package fixture;

					import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
					import net.minecraft.resources.Identifier;

					public record ModPayload(String id) implements CustomPacketPayload {
						public Type type() {
							return new Type(new Identifier(id));
						}
					}
					"""),
			// MinecraftForge decodes every channel it has a target for into its own envelope, and only that envelope is
			// offered to its dispatcher (CommonNetworkInteropInjector.offerOnlyForgePayloads).
			Map.entry("net.minecraftforge.network.ForgePayload", """
					package net.minecraftforge.network;

					import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
					import net.minecraft.resources.Identifier;

					public record ForgePayload(String id) implements CustomPacketPayload {
						public Type type() {
							return new Type(new Identifier(id));
						}
					}
					"""),
			Map.entry("net.minecraft.network.Connection", "package net.minecraft.network; public class Connection { }"),
			Map.entry("net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket", """
					package net.minecraft.network.protocol.common;

					import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

					public record ServerboundCustomPayloadPacket(CustomPacketPayload payload) {
					}
					"""),
			Map.entry("net.minecraft.network.protocol.common.ServerCommonPacketListener",
					"package net.minecraft.network.protocol.common; public interface ServerCommonPacketListener { }"),
			Map.entry("net.minecraftforge.common.ForgeHooks", """
					package net.minecraftforge.common;

					import fixture.Trace;
					import net.minecraft.network.Connection;
					import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

					public class ForgeHooks {
						/** Takes its own channels, declines everything else. */
						public static boolean onCustomPayload(CustomPacketPayload payload, Connection connection) {
							String id = payload.type().id().toString();
							if (!id.startsWith("forgemod:")) return false;
							Trace.events.add("forge " + id);
							return true;
						}
					}
					"""),
			Map.entry("net.neoforged.neoforge.network.registration.NetworkRegistry", """
					package net.neoforged.neoforge.network.registration;

					import fixture.Trace;
					import java.util.Map;
					import net.minecraft.network.protocol.common.ServerCommonPacketListener;
					import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
					import net.minecraft.resources.Identifier;

					public class NetworkRegistry {
						static final Map<String, Map<Identifier, String>> PAYLOAD_REGISTRATIONS =
								Map.of("play", Map.of(new Identifier("carryon:key_pressed"), "registered by Carry On"));

						/** NeoForge's dispatcher: strict about what it never registered. */
						public static void handleModdedPayload(ServerCommonPacketListener listener, ServerboundCustomPayloadPacket packet) {
							Identifier id = packet.payload().type().id();
							boolean known = PAYLOAD_REGISTRATIONS.values().stream().anyMatch(channels -> channels.containsKey(id));
							Trace.events.add(known ? "neoforge " + id : "disconnected: No Channel for " + id);
						}
					}
					"""),
			Map.entry("net.minecraft.server.network.ServerCommonPacketListenerImpl", """
					package net.minecraft.server.network;

					import net.minecraft.network.Connection;
					import net.minecraft.network.protocol.common.ServerCommonPacketListener;
					import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

					public class ServerCommonPacketListenerImpl implements ServerCommonPacketListener {
						protected final Connection connection = new Connection();

						/** fabric-api's head injection: the configuration listener's addon, or nothing. */
						public void handleCustomPayload(ServerboundCustomPayloadPacket packet) {
							throw new IllegalStateException("Unknown addon");
						}
					}
					"""),
			Map.entry(GAME_LISTENER, """
					package net.minecraft.server.network;

					import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
					import net.minecraftforge.common.ForgeHooks;

					public class ServerGamePacketListenerImpl extends ServerCommonPacketListenerImpl {
						/** MinecraftForge's override, as merged: the answer is dropped and super is never reached. */
						@Override
						public void handleCustomPayload(ServerboundCustomPayloadPacket packet) {
							ForgeHooks.onCustomPayload(packet.payload(), this.connection);
						}
					}
					"""));

	@AfterEach void reset() {
		System.clearProperty(SWITCH);
	}

	private static List<?> receive(ClassLoader loader, String... ids) throws Throwable {
		Object listener = InjectorExecution.construct(loader.loadClass(GAME_LISTENER));
		Class<?> packet = loader.loadClass("net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket");
		for (String id : ids) {
			Object payload = InjectorExecution.construct(loader.loadClass(id.startsWith("forgemod:")
					? "net.minecraftforge.network.ForgePayload" : "fixture.ModPayload"), id);
			InjectorExecution.invoke(listener, "handleCustomPayload", InjectorExecution.construct(packet, payload));
		}
		return (List<?>) InjectorExecution.getStatic(loader.loadClass("fixture.Trace"), "events");
	}

	@Test void aNeoForgeModsPlayPacketReachesNeoForgeAndThePlayerStaysConnected(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		String internal = GAME_LISTENER.replace('.', '/');
		byte[] edited = InjectorExecution.transform(new CommonNetworkInteropInjector(), GAME_LISTENER, original.get(internal),
				EnvType.CLIENT);
		assertNotSame(original.get(internal), edited, "the hand-over is on by default");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, edited);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(edited, loader));

		// Carry On's key reaches NeoForge; MinecraftForge's own channel stays MinecraftForge's alone; a channel nobody
		// registered is dropped as vanilla's empty play override drops it — not handed to a dispatcher that would
		// disconnect on it. And nothing goes through super, whose fabric-api handler would throw "Unknown addon".
		assertEquals(List.of("neoforge carryon:key_pressed", "forge forgemod:ping"),
				receive(loader, "carryon:key_pressed", "forgemod:ping", "fabricmod:no_receiver"));

		assertEquals(List.of("forge forgemod:ping"),
				receive(InjectorExecution.load(original), "carryon:key_pressed", "forgemod:ping"),
				"premise: as merged, Carry On's key packet reaches nobody");
		assertSame(edited, InjectorExecution.transform(new CommonNetworkInteropInjector(), GAME_LISTENER, edited,
				EnvType.CLIENT), "a second pass finds nothing left to edit");
	}

	/**
	 * Off leaves out the hand-over: the override still drops what MinecraftForge declines. The filter on the override's
	 * own Forge call is a separate repair and stays ({@code NativeForgePayloadDispatchExecutionTest}); it moves that one
	 * call's constants and nothing else.
	 */
	@Test void theOffSwitchLeavesOutTheHandOver(@TempDir Path work) throws Throwable {
		System.setProperty(SWITCH, "off");
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		String internal = GAME_LISTENER.replace('.', '/');
		byte[] bytes = original.get(internal);
		byte[] out = InjectorExecution.transform(new CommonNetworkInteropInjector(), GAME_LISTENER, bytes, EnvType.CLIENT);
		org.objectweb.asm.tree.ClassNode was = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(was, 0);
		org.objectweb.asm.tree.ClassNode now = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(out).accept(now, 0);
		for (int i = 0; i < was.methods.size(); i++) {
			assertEquals(was.methods.get(i).instructions.size(), now.methods.get(i).instructions.size(),
					was.methods.get(i).name + ": no instruction added or removed");
		}
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, out);
		assertEquals(List.of("forge forgemod:ping"), receive(InjectorExecution.load(classes), "carryon:key_pressed", "forgemod:ping"),
				"off: Carry On's key packet is dropped, as merged");
	}
}
