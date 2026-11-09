package net.minecraft.server.network;

import fixture.commonpayload.Trail;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.neoforged.neoforge.network.payload.ModdedNetworkQueryPayload;

/**
 * Fixture stand-in for NeoForge's configuration listener: its override answers NeoForge's network query itself and returns
 * (javap, NeoForge 26.2.0.93, bci 0-32: {@code instanceof ModdedNetworkQueryPayload}, then
 * {@code NetworkRegistry.initializeNeoForgeConnection}, here a recorded step), and hands every other payload to super (bci
 * 35: {@code invokespecial ServerCommonPacketListenerImpl.handleCustomPayload}).
 */
public class ServerConfigurationPacketListenerImpl extends ServerCommonPacketListenerImpl {
	@Override
	public void handleCustomPayload(ServerboundCustomPayloadPacket packet) {
		if (packet.payload() instanceof ModdedNetworkQueryPayload) {
			Trail.log("neoforge-query");
			return;
		}
		super.handleCustomPayload(packet);
	}
}
