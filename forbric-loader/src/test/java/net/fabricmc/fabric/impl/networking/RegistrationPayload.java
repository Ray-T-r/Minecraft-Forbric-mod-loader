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

import java.util.List;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public class RegistrationPayload implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RegistrationPayload> REGISTER =
			new CustomPacketPayload.Type<>(new Identifier("minecraft", "register"));
	public static final CustomPacketPayload.Type<RegistrationPayload> UNREGISTER =
			new CustomPacketPayload.Type<>(new Identifier("minecraft", "unregister"));

	private final CustomPacketPayload.Type<RegistrationPayload> type;
	private final List<Identifier> channels;

	public RegistrationPayload(CustomPacketPayload.Type<RegistrationPayload> type, List<Identifier> channels) {
		this.type = type;
		this.channels = channels;
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return type;
	}

	public List<Identifier> channels() {
		return channels;
	}
}
