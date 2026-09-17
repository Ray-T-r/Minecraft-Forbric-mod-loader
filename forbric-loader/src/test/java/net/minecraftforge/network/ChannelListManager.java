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

/** Test stand-in for MinecraftForge's channel-list manager: records every declaration it is asked to send. */
public final class ChannelListManager {
	public static final List<Connection> declared = new ArrayList<>();

	private ChannelListManager() {
	}

	public static void addChannels(Connection connection) {
		declared.add(connection);
	}
}
