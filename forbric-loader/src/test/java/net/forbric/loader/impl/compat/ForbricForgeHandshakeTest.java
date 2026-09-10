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

package net.forbric.loader.impl.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.tasks.SyncConfigTask;
import net.minecraftforge.network.tasks.SyncRegistriesTask;

/**
 * The three hooks that re-tie MinecraftForge's handshake, each called from a prologue at the head of the merged
 * method it belongs to: who starts its networking (clients only), which of its configuration tasks are queued (all
 * but the registry sync), and that the client's configuration-complete hook runs once per connection.
 */
class ForbricForgeHandshakeTest {
	/** A stand-in for the listeners the prologues pass: the hooks read its {@code connection} field. */
	public static final class TestListener {
		public final Connection connection;

		TestListener(Connection connection) {
			this.connection = connection;
		}
	}

	/** …and for the server configuration listener, which also carries the task queue. */
	public static final class TestServerListener {
		public final Connection connection;
		public final Queue<ConfigurationTask> configurationTasks = new ArrayDeque<>();

		TestServerListener(Connection connection) {
			this.connection = connection;
		}
	}

	@BeforeEach
	void fresh() {
		NetworkRegistry.reset();
		ForgeHooks.reset();
		ForgeEventFactory.reset();
	}

	@Test
	void onlyAClientStartsForgesNetworkingFromChannelActive() {
		Connection client = new Connection();
		client.receiving = PacketFlow.CLIENTBOUND;
		Connection server = new Connection();
		server.receiving = PacketFlow.SERVERBOUND;

		ForbricCustomPayloadInterop.onConnectionActive(server);
		assertEquals(List.of(), NetworkRegistry.started,
				"the server installs Forge's handler from its own login hook; doing it here would type the "
						+ "connection before its intention is read");

		ForbricCustomPayloadInterop.onConnectionActive(client);
		assertEquals(List.of(client), NetworkRegistry.started);
	}

	@Test
	void aClientConnectionStartsForgesNetworkingOnlyOnce() {
		Connection client = new Connection();
		client.receiving = PacketFlow.CLIENTBOUND;
		ForbricCustomPayloadInterop.onConnectionActive(client);
		ForbricCustomPayloadInterop.onConnectionActive(client);
		assertEquals(List.of(client), NetworkRegistry.started);
	}

	@Test
	void everyForgeTaskIsQueuedExceptTheRegistrySync() {
		TestServerListener listener = new TestServerListener(new Connection());

		ForbricCustomPayloadInterop.gatherForgeConfigurationTasks(listener);

		assertTrue(ForgeEventFactory.gathered, "Forge's own gate decides whether it offers any task at all");
		assertEquals(1, listener.configurationTasks.size(), listener.configurationTasks.toString());
		assertTrue(listener.configurationTasks.peek() instanceof SyncConfigTask);
		for (ConfigurationTask task : listener.configurationTasks) {
			assertFalse(task instanceof SyncRegistriesTask,
					"the registries are already remapped by then; a second snapshot would remap the remap");
		}
	}

	@Test
	void theClientIsToldConfigurationFinishedOncePerConnection() {
		Connection first = new Connection();
		TestListener listener = new TestListener(first);

		ForbricCustomPayloadInterop.onClientConfigurationFinished(listener);
		ForbricCustomPayloadInterop.onClientConfigurationFinished(listener);
		assertEquals(List.of(first), ForgeHooks.completed,
				"a server that sends a code of conduct reaches Forge's own call site too — do not load defaults twice");

		Connection second = new Connection();
		ForbricCustomPayloadInterop.onClientConfigurationFinished(new TestListener(second));
		assertEquals(List.of(first, second), ForgeHooks.completed, "…but a new connection is its own occasion");
	}
}
