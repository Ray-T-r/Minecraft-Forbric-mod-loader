package net.fabricmc.fabric.impl.networking;

import java.util.List;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public class RegistrationPayload implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RegistrationPayload> REGISTER =
			new CustomPacketPayload.Type<>(new Identifier("minecraft", "register"));
	public static final CustomPacketPayload.Type<RegistrationPayload> UNREGISTER =
			new CustomPacketPayload.Type<>(new Identifier("minecraft", "unregister"));

	private final CustomPacketPayload.Type<RegistrationPayload> type;
	private final List<Identifier> channels;

	public RegistrationPayload(CustomPacketPayload.Type<RegistrationPayload> type, List<Identifier> channels) {
		this.type = type;
		this.channels = channels;
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return type;
	}

	public List<Identifier> channels() {
		return channels;
	}
}
