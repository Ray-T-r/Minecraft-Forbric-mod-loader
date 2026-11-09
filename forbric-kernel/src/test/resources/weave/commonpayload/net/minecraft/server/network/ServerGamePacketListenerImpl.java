package net.minecraft.server.network;

import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * Fixture stand-in for NeoForge's play listener: its override hands the packet to super (javap, NeoForge 26.2.0.93:
 * aload_0, aload_1, invokespecial ServerCommonPacketListenerImpl.handleCustomPayload, return), where vanilla's is empty.
 */
public class ServerGamePacketListenerImpl extends ServerCommonPacketListenerImpl {
	@Override
	public void handleCustomPayload(ServerboundCustomPayloadPacket packet) {
		super.handleCustomPayload(packet);
	}
}
