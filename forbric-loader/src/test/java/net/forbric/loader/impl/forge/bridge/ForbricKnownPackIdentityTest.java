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

package net.forbric.loader.impl.forge.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ForbricKnownPackIdentityTest {
	@Test
	void buildsStableKnownPackIdentity() {
		ForbricKnownPackIdentity.Descriptor client =
				ForbricKnownPackIdentity.descriptor("forge", ForbricKnownPackIdentity.CLIENT_RESOURCES,
						"physicsmod", "3.1.45");
		ForbricKnownPackIdentity.Descriptor server =
				ForbricKnownPackIdentity.descriptor("forge", ForbricKnownPackIdentity.SERVER_DATA,
						"physicsmod", "3.1.45");

		assertEquals("forbric/forge/client_resources/physicsmod", client.locationId());
		assertEquals("forge/client_resources/physicsmod", client.knownPackId());
		assertEquals("forbric", client.knownPackNamespace());
		assertEquals("3.1.45", client.version());

		assertEquals("forbric/forge/server_data/physicsmod", server.locationId());
		assertEquals("forge/server_data/physicsmod", server.knownPackId());
		assertEquals(client.knownPackNamespace(), server.knownPackNamespace());
		assertEquals(client.version(), server.version());
	}

	@Test
	void normalizesBlankVersion() {
		ForbricKnownPackIdentity.Descriptor descriptor =
				ForbricKnownPackIdentity.descriptor("forge", ForbricKnownPackIdentity.CLIENT_RESOURCES,
						"example", "   ");
		assertEquals("0", descriptor.version());
	}
}
