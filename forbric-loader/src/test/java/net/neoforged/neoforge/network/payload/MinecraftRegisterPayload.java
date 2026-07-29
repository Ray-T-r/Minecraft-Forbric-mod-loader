package net.neoforged.neoforge.network.payload;

import java.util.Collection;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public class MinecraftRegisterPayload implements CustomPacketPayload {
	private static final CustomPacketPayload.Type<MinecraftRegisterPayload> TYPE =
			new CustomPacketPayload.Type<>(new Identifier("minecraft", "register"));
	private final Collection<Identifier> newChannels;

	public MinecraftRegisterPayload(Collection<Identifier> newChannels) {
		this.newChannels = newChannels;
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public Collection<Identifier> newChannels() {
		return newChannels;
	}
}
