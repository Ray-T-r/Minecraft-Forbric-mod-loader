package net.minecraft.network;

public enum ConnectionProtocol {
	CONFIGURATION("configuration"),
	PLAY("play");

	private final String id;

	ConnectionProtocol(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}
}
