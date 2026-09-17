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

package net.neoforged.neoforge.network.payload;

import java.util.Collection;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public class MinecraftRegisterPayload implements CustomPacketPayload {
	private static final CustomPacketPayload.Type<MinecraftRegisterPayload> TYPE =
			new CustomPacketPayload.Type<>(new Identifier("minecraft", "register"));
	private final Collection<Identifier> newChannels;

	public MinecraftRegisterPayload(Collection<Identifier> newChannels) {
		this.newChannels = newChannels;
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public Collection<Identifier> newChannels() {
		return newChannels;
	}
}
