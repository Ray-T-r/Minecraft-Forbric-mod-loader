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

package net.minecraftforge.event;

import java.util.function.Consumer;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraftforge.network.tasks.SyncConfigTask;
import net.minecraftforge.network.tasks.SyncRegistriesTask;

/** Test stand-in: offers the tasks MinecraftForge's own handler would, in its order. */
public final class ForgeEventFactory {
	public static boolean gathered;

	private ForgeEventFactory() {
	}

	public static void gatherLoginConfigTasks(Connection connection, Consumer<ConfigurationTask> sink) {
		gathered = true;
		sink.accept(new SyncRegistriesTask());
		sink.accept(new SyncConfigTask());
	}

	public static void reset() {
		gathered = false;
	}
}
