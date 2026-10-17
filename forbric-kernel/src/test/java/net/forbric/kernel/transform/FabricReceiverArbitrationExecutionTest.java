/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.ExpectCaught;

/**
 * {@link CommonNetworkInteropInjector}'s Fabric receiver arbitration, run against the kernel's real boot-side
 * {@code PayloadInterop}, on a receiver that shares no name with fabric-api's.
 *
 * <p>{@code org.relay.wire.Courier} implements fabric-api's public {@code PacketSender} — the only name the injector knows —
 * and handles payloads in its own classes: {@code VersionNote} for {@code c:version}, {@code Roster} for the channel
 * declarations, each registered with the public {@code PayloadTypeRegistry} (behind which sits a {@code ChannelBook} whose
 * fields are named nothing like fabric-api's). NeoForge reads the same channels into its own payload classes. As merged, the
 * courier is handed NeoForge's class and casts it: {@code ClassCastException}. Arbitrated:
 * <ul>
 *   <li>the courier's own body gets the payload as its own codec reads the same bytes, and keeps its own state;</li>
 *   <li>for the negotiation, its own answer stands aside and NeoForge's handler answers, once, after it;</li>
 *   <li>for a channel declaration, NeoForge is told first, the courier second, MinecraftForge's declaration last;</li>
 *   <li>a subclass's override handing the payload on to the handle it overrides does not arbitrate it again;</li>
 *   <li>any other payload reaches the courier untouched.</li>
 * </ul>
 */
@ExecutesInjector(CommonNetworkInteropInjector.class)
class FabricReceiverArbitrationExecutionTest {
	private static final String COURIER = "org.relay.wire.Courier";
	private static final String RELAY = "org.relay.wire.RelayCourier";
	private static final String NEO_REGISTRY = "net.neoforged.neoforge.network.registration.NetworkRegistry";

	private static final Map<String, String> SOURCES = new HashMap<>();

	static {
		SOURCES.put("net.minecraft.resources.Identifier", """
				package net.minecraft.resources;
				public record Identifier(String id) {
					@Override public String toString() { return id; }
				}
				""");
		SOURCES.put("io.netty.buffer.ByteBuf", """
				package io.netty.buffer;
				import java.util.ArrayDeque;
				public class ByteBuf {
					public final ArrayDeque<Object> items = new ArrayDeque<>();
				}
				""");
		SOURCES.put("io.netty.buffer.Unpooled", """
				package io.netty.buffer;
				public final class Unpooled {
					public static ByteBuf buffer() { return new ByteBuf(); }
				}
				""");
		SOURCES.put("io.netty.channel.ChannelFutureListener", "package io.netty.channel; public interface ChannelFutureListener { }");
		SOURCES.put("net.minecraft.core.RegistryAccess", """
				package net.minecraft.core;
				public class RegistryAccess {
					public static final RegistryAccess EMPTY = new RegistryAccess();
				}
				""");
		SOURCES.put("net.minecraft.network.RegistryFriendlyByteBuf", """
				package net.minecraft.network;
				import io.netty.buffer.ByteBuf;
				import net.minecraft.core.RegistryAccess;
				public class RegistryFriendlyByteBuf {
					private final ByteBuf buf;
					public RegistryFriendlyByteBuf(ByteBuf buf, RegistryAccess access) { this.buf = buf; }
					public void writeInt(int value) { buf.items.add(value); }
					public int readInt() { return (Integer) buf.items.poll(); }
					public void writeUtf(String value) { buf.items.add(value); }
					public String readUtf() { return (String) buf.items.poll(); }
				}
				""");
		SOURCES.put("net.minecraft.network.codec.StreamEncoder",
				"package net.minecraft.network.codec; public interface StreamEncoder<O, T> { void encode(O out, T value); }");
		SOURCES.put("net.minecraft.network.codec.StreamDecoder",
				"package net.minecraft.network.codec; public interface StreamDecoder<I, T> { T decode(I in); }");
		SOURCES.put("net.minecraft.network.codec.StreamCodec",
				"package net.minecraft.network.codec; public interface StreamCodec<B, V> extends StreamDecoder<B, V>, StreamEncoder<B, V> { }");
		SOURCES.put("net.minecraft.network.ConnectionProtocol",
				"package net.minecraft.network; public enum ConnectionProtocol { CONFIGURATION, PLAY }");
		SOURCES.put("net.minecraft.network.protocol.PacketFlow",
				"package net.minecraft.network.protocol; public enum PacketFlow { CLIENTBOUND, SERVERBOUND }");
		SOURCES.put("net.minecraft.network.PacketListener",
				"package net.minecraft.network; public interface PacketListener { ConnectionProtocol protocol(); }");
		SOURCES.put("net.minecraft.network.protocol.Packet", "package net.minecraft.network.protocol; public interface Packet { }");
		SOURCES.put("net.minecraft.network.protocol.common.custom.CustomPacketPayload", """
				package net.minecraft.network.protocol.common.custom;
				import net.minecraft.network.codec.StreamCodec;
				import net.minecraft.resources.Identifier;
				public interface CustomPacketPayload {
					record Type<T extends CustomPacketPayload>(Identifier id) { }
					record TypeAndCodec<B, T extends CustomPacketPayload>(Type<T> type, StreamCodec<B, T> codec) { }
					Type<? extends CustomPacketPayload> type();
				}
				""");
		SOURCES.put("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket", """
				package net.minecraft.network.protocol.common;
				import net.minecraft.network.protocol.Packet;
				import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
				public record ClientboundCustomPayloadPacket(CustomPacketPayload payload) implements Packet { }
				""");
		SOURCES.put("net.minecraft.network.Connection", """
				package net.minecraft.network;
				import net.minecraft.network.protocol.PacketFlow;
				public class Connection {
					private final PacketListener listener;
					public Connection(PacketListener listener) { this.listener = listener; }
					public PacketListener getPacketListener() { return listener; }
					public PacketFlow getReceiving() { return PacketFlow.CLIENTBOUND; }
				}
				""");
		SOURCES.put("fixture.Trace", """
				package fixture;
				import java.util.ArrayList;
				import java.util.List;
				public final class Trace {
					public static final List<String> LINES = new ArrayList<>();
				}
				""");
		SOURCES.put("fixture.ConfigListener", """
				package fixture;
				import net.minecraft.network.ConnectionProtocol;
				import net.minecraft.network.PacketListener;
				public class ConfigListener implements PacketListener {
					public ConnectionProtocol protocol() { return ConnectionProtocol.CONFIGURATION; }
				}
				""");
		SOURCES.put("fixture.OtherPayload", """
				package fixture;
				import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
				import net.minecraft.resources.Identifier;
				public record OtherPayload(String what) implements CustomPacketPayload {
					public Type<OtherPayload> type() { return new Type<>(new Identifier("mod:other")); }
				}
				""");
		// NeoForge's side of the same channels: its own payload classes and codecs, its handler, and its bookkeeping.
		SOURCES.put("net.neoforged.neoforge.network.payload.CommonVersionPayload", """
				package net.neoforged.neoforge.network.payload;
				import java.util.ArrayList;
				import java.util.List;
				import net.minecraft.network.RegistryFriendlyByteBuf;
				import net.minecraft.network.codec.StreamCodec;
				import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
				import net.minecraft.resources.Identifier;
				public record CommonVersionPayload(List<Integer> versions) implements CustomPacketPayload {
					public static final Type<CommonVersionPayload> TYPE = new Type<>(new Identifier("c:version"));
					public static final StreamCodec<RegistryFriendlyByteBuf, CommonVersionPayload> CODEC = new StreamCodec<>() {
						public void encode(RegistryFriendlyByteBuf out, CommonVersionPayload value) {
							out.writeInt(value.versions().size());
							for (int version : value.versions()) out.writeInt(version);
						}
						public CommonVersionPayload decode(RegistryFriendlyByteBuf in) {
							List<Integer> versions = new ArrayList<>();
							for (int i = in.readInt(); i > 0; i--) versions.add(in.readInt());
							return new CommonVersionPayload(versions);
						}
					};
					public Type<CommonVersionPayload> type() { return TYPE; }
				}
				""");
		SOURCES.put("net.neoforged.neoforge.network.payload.MinecraftRegisterPayload", """
				package net.neoforged.neoforge.network.payload;
				import java.util.LinkedHashSet;
				import java.util.Set;
				import net.minecraft.network.RegistryFriendlyByteBuf;
				import net.minecraft.network.codec.StreamCodec;
				import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
				import net.minecraft.resources.Identifier;
				public record MinecraftRegisterPayload(Set<Identifier> newChannels) implements CustomPacketPayload {
					public static final Type<MinecraftRegisterPayload> TYPE = new Type<>(new Identifier("minecraft:register"));
					public static final StreamCodec<RegistryFriendlyByteBuf, MinecraftRegisterPayload> CODEC = new StreamCodec<>() {
						public void encode(RegistryFriendlyByteBuf out, MinecraftRegisterPayload value) {
							out.writeInt(value.newChannels().size());
							for (Identifier channel : value.newChannels()) out.writeUtf(channel.id());
						}
						public MinecraftRegisterPayload decode(RegistryFriendlyByteBuf in) {
							Set<Identifier> channels = new LinkedHashSet<>();
							for (int i = in.readInt(); i > 0; i--) channels.add(new Identifier(in.readUtf()));
							return new MinecraftRegisterPayload(channels);
						}
					};
					public Type<MinecraftRegisterPayload> type() { return TYPE; }
				}
				""");
		SOURCES.put(NEO_REGISTRY, """
				package net.neoforged.neoforge.network.registration;
				import java.util.Map;
				import java.util.Set;
				import fixture.Trace;
				import net.minecraft.network.Connection;
				import net.minecraft.resources.Identifier;
				import net.neoforged.neoforge.network.payload.CommonVersionPayload;
				import net.neoforged.neoforge.network.payload.MinecraftRegisterPayload;
				public class NetworkRegistry {
					public static final Map<Identifier, Object> BUILTIN_PAYLOADS = Map.of(
							CommonVersionPayload.TYPE.id(), CommonVersionPayload.CODEC,
							MinecraftRegisterPayload.TYPE.id(), MinecraftRegisterPayload.CODEC);
					/** NeoForge's handler: on the client it answers with its own payload. */
					public static void checkCommonVersion(Object listener, CommonVersionPayload payload) {
						Trace.LINES.add("neoforge version " + payload.versions() + ", answered");
					}
					public static void onMinecraftRegister(Connection connection, Set<Identifier> channels) {
						Trace.LINES.add("neoforge register " + channels);
					}
				}
				""");
		SOURCES.put("net.minecraftforge.network.ChannelListManager", """
				package net.minecraftforge.network;
				import fixture.Trace;
				import net.minecraft.network.Connection;
				public class ChannelListManager {
					public static void addChannels(Connection connection) { Trace.LINES.add("forge declares"); }
				}
				""");
		// The Fabric side: only its public API keeps fabric-api's names; the receiver and the registry behind it do not.
		SOURCES.put("net.fabricmc.fabric.api.networking.v1.PacketSender", """
				package net.fabricmc.fabric.api.networking.v1;
				import io.netty.channel.ChannelFutureListener;
				import net.minecraft.network.protocol.Packet;
				public interface PacketSender {
					void sendPacket(Packet packet, ChannelFutureListener listener);
				}
				""");
		SOURCES.put("net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry", """
				package net.fabricmc.fabric.api.networking.v1;
				import net.minecraft.network.codec.StreamCodec;
				import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
				public interface PayloadTypeRegistry<B> {
					<T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super B, T> register(CustomPacketPayload.Type<T> type,
							StreamCodec<? super B, T> codec);
					static PayloadTypeRegistry<net.minecraft.network.RegistryFriendlyByteBuf> clientboundConfiguration() {
						return org.relay.wire.ChannelBook.INBOUND_SETUP;
					}
					static PayloadTypeRegistry<net.minecraft.network.RegistryFriendlyByteBuf> serverboundConfiguration() {
						return org.relay.wire.ChannelBook.OUTBOUND_SETUP;
					}
				}
				""");
		SOURCES.put("org.relay.wire.ChannelBook", """
				package org.relay.wire;
				import java.util.HashMap;
				import java.util.Map;
				import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
				import net.minecraft.network.ConnectionProtocol;
				import net.minecraft.network.RegistryFriendlyByteBuf;
				import net.minecraft.network.codec.StreamCodec;
				import net.minecraft.network.protocol.PacketFlow;
				import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
				import net.minecraft.resources.Identifier;
				public class ChannelBook implements PayloadTypeRegistry<RegistryFriendlyByteBuf> {
					public static final ChannelBook INBOUND_SETUP = new ChannelBook(ConnectionProtocol.CONFIGURATION, PacketFlow.CLIENTBOUND);
					public static final ChannelBook OUTBOUND_SETUP = new ChannelBook(ConnectionProtocol.CONFIGURATION, PacketFlow.SERVERBOUND);
					private final ConnectionProtocol stage;
					private final PacketFlow heading;
					private final Map<Identifier, CustomPacketPayload.TypeAndCodec<RegistryFriendlyByteBuf, ? extends CustomPacketPayload>> pages = new HashMap<>();
					private ChannelBook(ConnectionProtocol stage, PacketFlow heading) {
						this.stage = stage;
						this.heading = heading;
					}
					@SuppressWarnings({"unchecked", "rawtypes"})
					public <T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super RegistryFriendlyByteBuf, T> register(
							CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
						CustomPacketPayload.TypeAndCodec entry = new CustomPacketPayload.TypeAndCodec(type, codec);
						pages.put(type.id(), entry);
						return entry;
					}
				}
				""");
		SOURCES.put("org.relay.wire.VersionNote", """
				package org.relay.wire;
				import net.minecraft.network.RegistryFriendlyByteBuf;
				import net.minecraft.network.codec.StreamCodec;
				import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
				import net.minecraft.resources.Identifier;
				public record VersionNote(int[] versions) implements CustomPacketPayload {
					public static final Type<VersionNote> TYPE = new Type<>(new Identifier("c:version"));
					public static final StreamCodec<RegistryFriendlyByteBuf, VersionNote> CODEC = new StreamCodec<>() {
						public void encode(RegistryFriendlyByteBuf out, VersionNote value) {
							out.writeInt(value.versions().length);
							for (int version : value.versions()) out.writeInt(version);
						}
						public VersionNote decode(RegistryFriendlyByteBuf in) {
							int[] versions = new int[in.readInt()];
							for (int i = 0; i < versions.length; i++) versions[i] = in.readInt();
							return new VersionNote(versions);
						}
					};
					public Type<VersionNote> type() { return TYPE; }
				}
				""");
		SOURCES.put("org.relay.wire.Roster", """
				package org.relay.wire;
				import java.util.ArrayList;
				import java.util.List;
				import net.minecraft.network.RegistryFriendlyByteBuf;
				import net.minecraft.network.codec.StreamCodec;
				import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
				import net.minecraft.resources.Identifier;
				public record Roster(boolean join, List<Identifier> names) implements CustomPacketPayload {
					public static final Type<Roster> JOIN = new Type<>(new Identifier("minecraft:register"));
					public static final StreamCodec<RegistryFriendlyByteBuf, Roster> JOIN_CODEC = new StreamCodec<>() {
						public void encode(RegistryFriendlyByteBuf out, Roster value) {
							out.writeInt(value.names().size());
							for (Identifier name : value.names()) out.writeUtf(name.id());
						}
						public Roster decode(RegistryFriendlyByteBuf in) {
							List<Identifier> names = new ArrayList<>();
							for (int i = in.readInt(); i > 0; i--) names.add(new Identifier(in.readUtf()));
							return new Roster(true, names);
						}
					};
					public Type<Roster> type() { return JOIN; }
				}
				""");
		SOURCES.put(COURIER, """
				package org.relay.wire;
				import java.util.ArrayList;
				import java.util.List;
				import fixture.Trace;
				import io.netty.channel.ChannelFutureListener;
				import net.fabricmc.fabric.api.networking.v1.PacketSender;
				import net.minecraft.network.Connection;
				import net.minecraft.network.protocol.Packet;
				import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
				import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
				public abstract class Courier implements PacketSender {
					protected final Connection link;
					public int agreed = -1;
					public final List<Packet> sent = new ArrayList<>();
					protected Courier(Connection link) { this.link = link; }
					/** Its own handling, in its own classes: a channel declaration, and the version it agrees and answers. */
					public boolean handle(CustomPacketPayload payload) {
						if (payload instanceof Roster roster) {
							Trace.LINES.add("courier " + (roster.join() ? "joins " : "leaves ") + roster.names());
							return true;
						}
						if (payload.type().id().toString().equals("c:version")) {
							VersionNote note = (VersionNote) payload;
							agreed = note.versions()[0];
							sendPacket(new ClientboundCustomPayloadPacket(new VersionNote(new int[] {agreed})), null);
							Trace.LINES.add("courier agrees " + agreed);
							return true;
						}
						return false;
					}
					public void sendPacket(Packet packet, ChannelFutureListener listener) {
						sent.add(packet);
					}
				}
				""");
		SOURCES.put(RELAY, """
				package org.relay.wire;
				import fixture.Trace;
				import net.minecraft.network.Connection;
				import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
				public class RelayCourier extends Courier {
					public RelayCourier(Connection link) { super(link); }
					@Override
					public boolean handle(CustomPacketPayload payload) {
						Trace.LINES.add("relay");
						return super.handle(payload);
					}
				}
				""");
		SOURCES.put("fixture.Setup", """
				package fixture;
				import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
				import org.relay.wire.Roster;
				import org.relay.wire.VersionNote;
				public class Setup {
					/** What the receiver's module does at start: its payload types, through the public registry. */
					public static void register() {
						PayloadTypeRegistry.clientboundConfiguration().register(VersionNote.TYPE, VersionNote.CODEC);
						PayloadTypeRegistry.clientboundConfiguration().register(Roster.JOIN, Roster.JOIN_CODEC);
					}
				}
				""");
	}

	private ClassLoader repaired;
	private ClassLoader merged;

	@BeforeEach void game(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, SOURCES);
		Map<String, byte[]> classes = new HashMap<>(original);
		CommonNetworkInteropInjector injector = new CommonNetworkInteropInjector(original::get);
		for (String target : List.of(COURIER, RELAY)) {
			String internal = target.replace('.', '/');
			byte[] out = InjectorExecution.transform(injector, target, original.get(internal), EnvType.CLIENT);
			assertNotSame(original.get(internal), out, target + " implements PacketSender and declares handle: a receiver");
			classes.put(internal, out);
		}
		for (String unrelated : List.of("fixture.Setup", "org.relay.wire.ChannelBook")) {
			String internal = unrelated.replace('.', '/');
			assertSame(original.get(internal), InjectorExecution.transform(injector, unrelated, original.get(internal), EnvType.CLIENT),
					unrelated + " is no receiver");
		}
		repaired = InjectorExecution.load(classes);
		for (String target : List.of(COURIER, RELAY)) assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), repaired), target);
		merged = InjectorExecution.load(original);
		for (ClassLoader loader : List.of(repaired, merged)) InjectorExecution.invokeStatic(loader.loadClass("fixture.Setup"), "register");
	}

	private static Object courier(ClassLoader loader) throws Throwable {
		Object connection = InjectorExecution.construct(loader.loadClass("net.minecraft.network.Connection"),
				InjectorExecution.construct(loader.loadClass("fixture.ConfigListener")));
		return InjectorExecution.construct(loader.loadClass(RELAY), connection);
	}

	@SuppressWarnings("unchecked")
	private static List<String> trace(ClassLoader loader) throws Throwable {
		return (List<String>) InjectorExecution.getStatic(loader.loadClass("fixture.Trace"), "LINES");
	}

	// The stub custom-payload packets have no GAMEPLAY_STREAM_CODEC for the packet census to read.
	@ExpectCaught("GAMEPLAY_STREAM_CODEC")
	@Test void theCourierAgreesTheVersionInItsOwnClassAndNeoForgeAnswersOnceAfterIt() throws Throwable {
		Object courier = courier(repaired);
		Object neoVersion = InjectorExecution.construct(repaired.loadClass("net.neoforged.neoforge.network.payload.CommonVersionPayload"), List.of(1));
		assertEquals(true, InjectorExecution.invoke(courier, "handle", neoVersion));
		assertEquals(1, courier.getClass().getField("agreed").get(courier), "its own state, from its own class");
		assertEquals(List.of(), courier.getClass().getField("sent").get(courier), "its own answer stood aside");
		assertEquals(List.of("relay", "courier agrees 1", "neoforge version [1], answered"), trace(repaired),
				"the courier first, NeoForge's handler once, after it — the override handing it on arbitrated nothing twice");
	}

	// The stub custom-payload packets have no GAMEPLAY_STREAM_CODEC for the packet census to read.
	@ExpectCaught("GAMEPLAY_STREAM_CODEC")
	@Test void aChannelDeclarationTellsNeoForgeFirstTheCourierSecondAndForgeLast() throws Throwable {
		Object courier = courier(repaired);
		Object channel = InjectorExecution.construct(repaired.loadClass("net.minecraft.resources.Identifier"), "mod:sync");
		Object neoRegister = InjectorExecution.construct(repaired.loadClass("net.neoforged.neoforge.network.payload.MinecraftRegisterPayload"),
				Set.of(channel));
		assertEquals(true, InjectorExecution.invoke(courier, "handle", neoRegister));
		assertEquals(List.of("neoforge register [mod:sync]", "relay", "courier joins [mod:sync]", "forge declares"), trace(repaired));
	}

	// The stub custom-payload packets have no GAMEPLAY_STREAM_CODEC for the packet census to read.
	@ExpectCaught("GAMEPLAY_STREAM_CODEC")
	@Test void anyOtherPayloadReachesTheCourierUntouched() throws Throwable {
		Object courier = courier(repaired);
		Object other = InjectorExecution.construct(repaired.loadClass("fixture.OtherPayload"), "x");
		assertEquals(false, InjectorExecution.invoke(courier, "handle", other));
		assertEquals(List.of("relay"), trace(repaired));
		assertEquals(List.of(), courier.getClass().getField("sent").get(courier));
	}

	@Test void premiseAsMergedTheCourierCastsNeoForgesPayload() throws Throwable {
		Object courier = courier(merged);
		Object neoVersion = InjectorExecution.construct(merged.loadClass("net.neoforged.neoforge.network.payload.CommonVersionPayload"), List.of(1));
		assertThrows(ClassCastException.class, () -> InjectorExecution.invoke(courier, "handle", neoVersion));
	}
}
