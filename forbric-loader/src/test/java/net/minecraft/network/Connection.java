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
