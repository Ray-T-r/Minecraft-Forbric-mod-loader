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
