package net.neoforged.neoforge.network.registration;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

public class NetworkRegistry {
	protected static final Map<ConnectionProtocol, Map<Identifier, PayloadRegistration<?>>> PAYLOAD_REGISTRATIONS =
			new EnumMap<>(ConnectionProtocol.class);
	protected static final Map<ConnectionProtocol, Map<Identifier, IPayloadHandler<?>>> SERVERBOUND_HANDLERS =
			new EnumMap<>(ConnectionProtocol.class);
	protected static final Map<ConnectionProtocol, Map<Identifier, IPayloadHandler<?>>> CLIENTBOUND_HANDLERS =
			new EnumMap<>(ConnectionProtocol.class);
	private static boolean setup;
	private static int setupCalls;

	static {
		for (ConnectionProtocol protocol : ConnectionProtocol.values()) {
			PAYLOAD_REGISTRATIONS.put(protocol, new LinkedHashMap<>());
			SERVERBOUND_HANDLERS.put(protocol, new LinkedHashMap<>());
			CLIENTBOUND_HANDLERS.put(protocol, new LinkedHashMap<>());
		}
	}

	public static void setup() {
		if (setup) throw new IllegalStateException("The network registry can only be setup once.");
		setup = true;
		setupCalls++;
	}

	public static StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload> getCodec(Identifier id,
			ConnectionProtocol protocol, PacketFlow flow) {
		PayloadRegistration<?> registration = PAYLOAD_REGISTRATIONS.get(protocol).get(id);
		if (registration == null) return null;
		if (registration.flow().isPresent() && registration.flow().get() != flow) return null;
		@SuppressWarnings("unchecked")
		StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload> codec =
				(StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload>) registration.codec();
		return codec;
	}

	// The kernel's CommonNetworkInteropInjector puts this call at the head of both methods on the live class.
	public static void onMinecraftRegister(Connection connection, Set<Identifier> channels) {
		net.forbric.loader.impl.compat.ForbricCustomPayloadInterop.onNeoChannelRegistration(connection, channels, true);
		connection.channels.addAll(channels);
	}

	public static void onMinecraftUnregister(Connection connection, Set<Identifier> channels) {
		net.forbric.loader.impl.compat.ForbricCustomPayloadInterop.onNeoChannelRegistration(connection, channels, false);
		connection.channels.removeAll(channels);
	}

	public static void checkCommonVersion(Object listener, Object payload) {
	}

	public static void onCommonRegister(Object listener, Object payload) {
	}

	public static void registerDirect(CustomPacketPayload.Type<?> type, StreamCodec<?, ?> codec, ConnectionProtocol protocol,
			PacketFlow flow) {
		@SuppressWarnings("unchecked")
		StreamCodec<? super RegistryFriendlyByteBuf, CustomPacketPayload> typedCodec =
				(StreamCodec<? super RegistryFriendlyByteBuf, CustomPacketPayload>) codec;
		@SuppressWarnings("unchecked")
		CustomPacketPayload.Type<CustomPacketPayload> typedType = (CustomPacketPayload.Type<CustomPacketPayload>) type;
		PAYLOAD_REGISTRATIONS.get(protocol).put(type.id(),
				new PayloadRegistration<>(typedType, typedCodec, java.util.List.of(protocol), java.util.Optional.of(flow),
						"test", true));
	}

	public static PayloadRegistration<?> registration(ConnectionProtocol protocol, Identifier id) {
		return PAYLOAD_REGISTRATIONS.get(protocol).get(id);
	}

	public static void reset() {
		setup = false;
		setupCalls = 0;
		for (Map<Identifier, PayloadRegistration<?>> value : PAYLOAD_REGISTRATIONS.values()) value.clear();
		for (Map<Identifier, IPayloadHandler<?>> value : SERVERBOUND_HANDLERS.values()) value.clear();
		for (Map<Identifier, IPayloadHandler<?>> value : CLIENTBOUND_HANDLERS.values()) value.clear();
	}

	public static int setupCalls() {
		return setupCalls;
	}
}
