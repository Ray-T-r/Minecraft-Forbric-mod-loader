package net.fabricmc.fabric.impl.networking;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public abstract class AbstractChanneledNetworkAddon<H> {
	protected final Connection connection;
	public boolean lastRegister;
	public RegistrationPayload lastPayload;
	public int lastCommonVersion;
	public CommonRegisterPayload lastCommonRegister;

	protected AbstractChanneledNetworkAddon(Connection connection) {
		this.connection = connection;
	}

	public boolean handle(CustomPacketPayload payload) {
		return false;
	}

	protected void receiveRegistration(boolean register, RegistrationPayload payload) {
		this.lastRegister = register;
		this.lastPayload = payload;
	}

	public void onCommonVersionPacket(int version) {
		this.lastCommonVersion = version;
	}

	public void onCommonRegisterPacket(CommonRegisterPayload payload) {
		this.lastCommonRegister = payload;
	}
}
