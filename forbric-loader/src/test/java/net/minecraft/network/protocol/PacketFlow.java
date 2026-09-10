package net.minecraft.network.protocol;

public enum PacketFlow {
	CLIENTBOUND("clientbound"),
	SERVERBOUND("serverbound");

	private final String id;

	PacketFlow(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}
}
