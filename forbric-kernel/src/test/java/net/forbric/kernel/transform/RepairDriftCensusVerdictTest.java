package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The census's verdict, with no game jar: the all-clear is printed only when every declared claim landed. The ledger files
 * a declined hedge apart from its misses, and the vanilla twins' anchors are all hedges, so a census that judged by its
 * declines alone printed the all-clear while twins stood down (26 of 29 landed, 0 declined).
 */
class RepairDriftCensusVerdictTest {
	private static final String ALL_CLEAR = "every repair still lands";

	@Test
	void hedgesThatStoodDownAreNamedAndWithholdTheAllClear() {
		RepairDriftCensus.Drift drift = new RepairDriftCensus.Drift(29, 26, List.of(),
				List.of("hud-twin", "crop-twin", "axe-twin"), List.of(), true);
		assertFalse(drift.everyClaimLanded());
		List<String> lines = drift.lines();
		assertEquals("[Forbric/RepairDrift] 29 claim(s) declared, 26 landed, 0 declined, 0 had no target in these jars,"
				+ " 3 hedge(s) stood down", lines.get(0));
		assertEquals(3, lines.stream().filter(l -> l.startsWith("    HEDGED    ")).count(), lines.toString());
		assertTrue(lines.stream().noneMatch(l -> l.contains(ALL_CLEAR)), lines.toString());
	}

	@Test
	void everyClaimLandedIsTheOnlyAllClear() {
		RepairDriftCensus.Drift clean = new RepairDriftCensus.Drift(29, 29, List.of(), List.of(), List.of(), true);
		assertTrue(clean.everyClaimLanded());
		assertTrue(clean.lines().get(clean.lines().size() - 1).contains(ALL_CLEAR), clean.lines().toString());

		RepairDriftCensus.Drift declined = new RepairDriftCensus.Drift(29, 28, List.of("a repair"), List.of(), List.of(), true);
		assertFalse(declined.everyClaimLanded());
		assertTrue(declined.lines().stream().noneMatch(l -> l.contains(ALL_CLEAR)), declined.lines().toString());

		RepairDriftCensus.Drift absent = new RepairDriftCensus.Drift(29, 28, List.of(), List.of(), List.of("a class"), true);
		assertFalse(absent.everyClaimLanded());
		assertTrue(absent.lines().stream().noneMatch(l -> l.contains(ALL_CLEAR)), absent.lines().toString());
	}

	@Test
	void aReplayWithoutAVanillaReferenceSaysSo() {
		RepairDriftCensus.Drift drift = new RepairDriftCensus.Drift(29, 25, List.of("the pip bridge"), List.of(), List.of(), false);
		assertTrue(drift.lines().get(1).contains("no vanilla reference among these jars"), drift.lines().toString());
		assertTrue(new RepairDriftCensus.Drift(29, 29, List.of(), List.of(), List.of(), true).lines().stream()
				.noneMatch(l -> l.contains("no vanilla reference")));
	}
}
