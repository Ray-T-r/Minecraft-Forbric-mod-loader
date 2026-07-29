package net.neoforged.neoforge.network.registration;

import java.util.List;
import java.util.Optional;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PayloadRegistration<T extends CustomPacketPayload>(
		CustomPacketPayload.Type<T> type,
		StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
		List<net.minecraft.network.ConnectionProtocol> protocols,
		Optional<PacketFlow> flow,
		String version,
		boolean optional) {
	public Identifier id() {
		return type.id();
	}
}
