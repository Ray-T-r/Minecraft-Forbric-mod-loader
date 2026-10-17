/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The version a channel mirrored into NeoForge's registry announces in NeoForge's negotiation, which the client and the
 * server compare channel by channel and refuse the client on any difference. Both peers build it from the same
 * registrations, but in an order the JVM picks ({@code Class.getMethods()}), so nothing in it may depend on that order:
 * a channel registered in both directions announced the direction mirrored second, and a real client was refused with
 * "Incompatible client!" when its JVM listed fabric-api's accessors the other way round.
 */
class MirrorVersionTest {
	enum Protocol { PLAY, CONFIGURATION }

	enum Flow { SERVERBOUND, CLIENTBOUND }

	@Test void theVersionNamesTheOriginAndThePhaseNotTheDirection() {
		assertEquals(PayloadInterop.mirrorVersion("fabric/PLAY/SERVERBOUND", "PLAY"),
				PayloadInterop.mirrorVersion("fabric/PLAY/CLIENTBOUND", "PLAY"));
		assertNotEquals(PayloadInterop.mirrorVersion("fabric/PLAY/CLIENTBOUND", "PLAY"),
				PayloadInterop.mirrorVersion("fabric/CONFIGURATION/CLIENTBOUND", "CONFIGURATION"));
		assertNotEquals(PayloadInterop.mirrorVersion("fabric/PLAY/CLIENTBOUND", "PLAY"),
				PayloadInterop.mirrorVersion("local/ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC", "PLAY"));
	}

	@Test void aChannelMirroredBothWaysAnnouncesTheSameWhicheverDirectionCameFirst() {
		String serverbound = PayloadInterop.mirrorVersion("fabric/PLAY/SERVERBOUND", "PLAY");
		String clientbound = PayloadInterop.mirrorVersion("fabric/PLAY/CLIENTBOUND", "PLAY");
		String oneWay = PayloadInterop.mergedMirrorVersion(serverbound, clientbound);
		String otherWay = PayloadInterop.mergedMirrorVersion(clientbound, serverbound);
		assertEquals(oneWay, otherWay);
		// and the same as a peer that registered the channel in one direction only
		assertEquals(serverbound, oneWay);
	}

	@Test void twoOriginsMergeIntoOneVersionInOneOrder() {
		String local = PayloadInterop.mirrorVersion("local/ServerboundCustomPayloadPacket.STREAM_CODEC", "PLAY");
		String fabric = PayloadInterop.mirrorVersion("fabric/PLAY/CLIENTBOUND", "PLAY");
		assertEquals(PayloadInterop.mergedMirrorVersion(local, fabric), PayloadInterop.mergedMirrorVersion(fabric, local));
		// a registration NeoForge made itself keeps its own version as one of the parts, whichever came first
		assertEquals(PayloadInterop.mergedMirrorVersion("1", fabric), PayloadInterop.mergedMirrorVersion(fabric, "1"));
		assertTrue(PayloadInterop.mergedMirrorVersion("1", fabric).contains("1"));
	}

	@Test void fabricsRegistriesComeInOneOrderWhateverOrderTheyWereFoundIn() {
		List<FabricPayloadRegistries.Registry> found = new ArrayList<>();
		for (Protocol protocol : Protocol.values()) {
			for (Flow flow : Flow.values()) found.add(new FabricPayloadRegistries.Registry(new Object(), protocol, flow, Map.of()));
		}
		List<FabricPayloadRegistries.Registry> expected = null;
		Random shuffle = new Random(26_2);
		for (int round = 0; round < 24; round++) {
			List<FabricPayloadRegistries.Registry> listed = new ArrayList<>(found);
			Collections.shuffle(listed, shuffle);
			listed.sort(FabricPayloadRegistries.IN_ONE_ORDER);
			if (expected == null) expected = listed;
			assertEquals(expected, listed, "round " + round);
		}
		assertEquals(List.of("CONFIGURATION/SERVERBOUND", "CONFIGURATION/CLIENTBOUND", "PLAY/SERVERBOUND", "PLAY/CLIENTBOUND"),
				expected.stream().map(registry -> registry.protocol() + "/" + registry.flow()).toList());
	}
}
