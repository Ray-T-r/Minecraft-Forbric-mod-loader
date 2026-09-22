package net.forbric.kernel.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.forbric.api.Ecosystem;

/** The difference the networking family never wrote down. */
class NetworkChannelCensusTest {

	@BeforeEach
	@AfterEach
	void clear() {
		System.clearProperty(NetworkChannelCensus.SWITCH);
		NetworkChannelCensus.reset();
	}

	@Test void aPayloadTypeWithNoDeclarationIsTheFindingThatKicksAPlayer() {
		// The Cardinal Components shape: the mod can build the packet, the peer never agreed to receive it, and
		// an unhandled payload is a disconnect rather than a skip.
		NetworkChannelCensus.registered(Ecosystem.FABRIC, List.of("cardinal-components:entity_sync", "mod:ok"));
		NetworkChannelCensus.declared(Ecosystem.FABRIC, List.of("mod:ok"));
		assertEquals(List.of("cardinal-components:entity_sync"), NetworkChannelCensus.registeredButNeverDeclared());
	}

	@Test void anotherEcosystemHavingDeclaredItIsEnough() {
		// Three protocols share one connection. A channel declared by any of them is on the wire, so counting
		// per ecosystem would report a difference that is not one.
		NetworkChannelCensus.registered(Ecosystem.NEOFORGE, List.of("shared:chan"));
		NetworkChannelCensus.declared(Ecosystem.FABRIC, List.of("shared:chan"));
		assertTrue(NetworkChannelCensus.registeredButNeverDeclared().isEmpty());
	}

	@Test void theSummaryLeadsWithWhatItCounted() {
		NetworkChannelCensus.registered(Ecosystem.FABRIC, List.of("a:1", "a:2"));
		NetworkChannelCensus.declared(Ecosystem.FABRIC, List.of("a:1"));
		String summary = NetworkChannelCensus.summary();
		assertTrue(summary.contains("registered {fabric=2}"), summary);
		assertTrue(summary.contains("declared {fabric=1}"), summary);
		assertTrue(summary.contains("registered-but-never-declared: 1 [a:2]"), summary);
	}

	@Test void nothingItIsHandedCanBreakAConnection() {
		// It runs on the networking path. Anything that throws here costs the player the connection, which is
		// strictly worse than the gap it is measuring.
		NetworkChannelCensus.registered(null, List.of("x"));
		NetworkChannelCensus.registered(Ecosystem.FABRIC, null);
		NetworkChannelCensus.declared(Ecosystem.FABRIC, Arrays.asList("y", null, "  ", "z"));
		NetworkChannelCensus.report();
		assertEquals(List.of(), NetworkChannelCensus.registeredButNeverDeclared());
		assertTrue(NetworkChannelCensus.summary().contains("declared {fabric=2}"),
				"a null and a blank id are dropped, not counted: " + NetworkChannelCensus.summary());
	}

	@Test void theSwitchTurnsTheRecordingOffEntirely() {
		System.setProperty(NetworkChannelCensus.SWITCH, "off");
		NetworkChannelCensus.registered(Ecosystem.FABRIC, List.of("a:1"));
		assertTrue(NetworkChannelCensus.registeredButNeverDeclared().isEmpty());
		assertTrue(NetworkChannelCensus.summary().contains("registered {}"), NetworkChannelCensus.summary());
	}
}
