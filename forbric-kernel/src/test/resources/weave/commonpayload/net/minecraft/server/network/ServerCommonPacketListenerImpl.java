package net.minecraft.server.network;

import fixture.commonpayload.Trail;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * Fixture stand-in for NeoForge's common listener: its handleCustomPayload is where NeoForge's whole payload dispatcher
 * lives (register/unregister, common version, NetworkRegistry.handleModdedPayload); here it records that it ran.
 */
public abstract class ServerCommonPacketListenerImpl {
	public void handleCustomPayload(ServerboundCustomPayloadPacket packet) {
		Trail.log("neoforge");
	}
}
