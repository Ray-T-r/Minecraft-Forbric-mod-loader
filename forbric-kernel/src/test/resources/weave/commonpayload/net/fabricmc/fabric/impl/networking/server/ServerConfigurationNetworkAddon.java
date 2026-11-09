package net.fabricmc.fabric.impl.networking.server;

import java.util.Set;

import fixture.commonpayload.Trail;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Fixture stand-in: fabric-api's configuration addon takes the channels a Fabric mod registered. */
public final class ServerConfigurationNetworkAddon {
	private final Set<String> channels;

	public ServerConfigurationNetworkAddon(Set<String> channels) {
		this.channels = channels;
	}

	public boolean handle(CustomPacketPayload payload) {
		if (!channels.contains(payload.id())) return false;
		Trail.log("fabric");
		return true;
	}
}
