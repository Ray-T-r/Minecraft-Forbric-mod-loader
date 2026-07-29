package net.neoforged.neoforge.network.payload;

import java.util.Collection;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public class MinecraftUnregisterPayload implements CustomPacketPayload {
	private static final CustomPacketPayload.Type<MinecraftUnregisterPayload> TYPE =
			new CustomPacketPayload.Type<>(new Identifier("minecraft", "unregister"));
	private final Collection<Identifier> forgottenChannels;

	public MinecraftUnregisterPayload(Collection<Identifier> forgottenChannels) {
		this.forgottenChannels = forgottenChannels;
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public Collection<Identifier> forgottenChannels() {
		return forgottenChannels;
	}
}
