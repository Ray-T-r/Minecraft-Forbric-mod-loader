package net.fabricmc.fabric.impl.networking;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public class PayloadTypeRegistryImpl<B extends FriendlyByteBuf> {
	public static final PayloadTypeRegistryImpl<FriendlyByteBuf> SERVERBOUND_CONFIGURATION =
			new PayloadTypeRegistryImpl<>(ConnectionProtocol.CONFIGURATION, PacketFlow.SERVERBOUND);
	public static final PayloadTypeRegistryImpl<FriendlyByteBuf> CLIENTBOUND_CONFIGURATION =
			new PayloadTypeRegistryImpl<>(ConnectionProtocol.CONFIGURATION, PacketFlow.CLIENTBOUND);
	public static final PayloadTypeRegistryImpl<RegistryFriendlyByteBuf> SERVERBOUND_PLAY =
			new PayloadTypeRegistryImpl<>(ConnectionProtocol.PLAY, PacketFlow.SERVERBOUND);
	public static final PayloadTypeRegistryImpl<RegistryFriendlyByteBuf> CLIENTBOUND_PLAY =
			new PayloadTypeRegistryImpl<>(ConnectionProtocol.PLAY, PacketFlow.CLIENTBOUND);

	private final Map<Identifier, CustomPacketPayload.TypeAndCodec<B, ? extends CustomPacketPayload>> packetTypes =
			new LinkedHashMap<>();
	private final ConnectionProtocol protocol;
	private final PacketFlow flow;

	private PayloadTypeRegistryImpl(ConnectionProtocol protocol, PacketFlow flow) {
		this.protocol = protocol;
		this.flow = flow;
	}

	public <T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super B, T> register(
			CustomPacketPayload.Type<T> type, StreamCodec<? super B, T> codec) {
		CustomPacketPayload.TypeAndCodec<B, T> value = new CustomPacketPayload.TypeAndCodec<>(type, codec);
		packetTypes.put(type.id(), value);
		return value;
	}

	public CustomPacketPayload.TypeAndCodec<B, ? extends CustomPacketPayload> get(Identifier id) {
		return packetTypes.get(id);
	}

	public ConnectionProtocol getProtocol() {
		return protocol;
	}

	public PacketFlow getFlow() {
		return flow;
	}

	public static void reset() {
		SERVERBOUND_CONFIGURATION.packetTypes.clear();
		CLIENTBOUND_CONFIGURATION.packetTypes.clear();
		SERVERBOUND_PLAY.packetTypes.clear();
		CLIENTBOUND_PLAY.packetTypes.clear();
	}
}
