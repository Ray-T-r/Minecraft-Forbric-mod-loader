package net.neoforged.neoforge.network.payload;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Fixture stand-in for NeoForge's network query, the payload its configuration listener answers itself (channel
 * {@code neoforge:register}, javap of the NeoForge 26.2.0.93 universal jar's static initialiser).
 */
public record ModdedNetworkQueryPayload() implements CustomPacketPayload {
	@Override
	public String id() {
		return "neoforge:register";
	}
}
