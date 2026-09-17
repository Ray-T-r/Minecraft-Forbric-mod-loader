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

package net.minecraftforge.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.Connection;

/** Test stand-in: records which connections MinecraftForge was asked to start its networking for. */
public final class NetworkRegistry {
	public static final List<Connection> started = new ArrayList<>();

	private NetworkRegistry() {
	}

	public static void onConnectionStart(Connection connection) {
		started.add(connection);
	}

	public static void reset() {
		started.clear();
	}
}
