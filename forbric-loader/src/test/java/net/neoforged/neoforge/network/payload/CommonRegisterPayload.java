package net.neoforged.neoforge.network.payload;

import java.util.Set;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public class CommonRegisterPayload implements CustomPacketPayload {
	private static final CustomPacketPayload.Type<CommonRegisterPayload> TYPE =
			new CustomPacketPayload.Type<>(new Identifier("c", "register"));
	private final int version;
	private final ConnectionProtocol protocol;
	private final Set<Identifier> channels;

	public CommonRegisterPayload(int version, ConnectionProtocol protocol, Set<Identifier> channels) {
		this.version = version;
		this.protocol = protocol;
		this.channels = channels;
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public int version() {
		return version;
	}

	public ConnectionProtocol protocol() {
		return protocol;
	}

	public Set<Identifier> channels() {
		return channels;
	}
}
