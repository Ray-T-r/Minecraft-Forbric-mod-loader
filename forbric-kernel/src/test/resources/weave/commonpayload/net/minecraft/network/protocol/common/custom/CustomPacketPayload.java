package net.minecraft.network.protocol.common.custom;

/** Fixture stand-in: a payload, by its channel id (the real one is an interface too, answering its type). */
public interface CustomPacketPayload {
	String id();
}
