/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
