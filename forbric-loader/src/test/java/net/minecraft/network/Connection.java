package net.minecraft.network;

import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.resources.Identifier;

public class Connection {
	public final Set<Identifier> channels = new LinkedHashSet<>();
	/** CLIENTBOUND on a client's connection, SERVERBOUND on a server's — what the client-only guards read. */
	public net.minecraft.network.protocol.PacketFlow receiving = net.minecraft.network.protocol.PacketFlow.SERVERBOUND;

	public net.minecraft.network.protocol.PacketFlow getReceiving() {
		return receiving;
	}
}
