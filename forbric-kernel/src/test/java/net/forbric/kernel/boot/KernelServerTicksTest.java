package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The accounting, driven with a known clock.
 *
 * <p>This is the only performance measurement in the tree, so the ways it could quietly be wrong each get a
 * case: the first sample has nothing to subtract from, a pause is not a slow tick, and the budget is counted
 * rather than described.
 */
class KernelServerTicksTest {
	private static final long MS = 1_000_000L;

	@BeforeEach
	@AfterEach
	void clear() {
		System.clearProperty(KernelServerTicks.SWITCH);
		KernelServerTicks.reset();
	}

	@Test void theFirstTickHasNothingToSubtractFromAndIsNotAnInterval() {
		KernelServerTicks.sample(1000 * MS);
		assertEquals(0, KernelServerTicks.sampled());
		assertTrue(KernelServerTicks.summary().contains("no server ticks sampled yet"), KernelServerTicks.summary());
	}

	@Test void intervalsBetweenTickStartsAreWhatIsMeasured() {
		long t = 1000 * MS;
		for (long step : new long[] {50, 50, 50, 50}) {
			KernelServerTicks.sample(t);
			t += step * MS;
		}
		KernelServerTicks.sample(t);
		assertEquals(4, KernelServerTicks.sampled());
		String s = KernelServerTicks.summary();
		assertTrue(s.contains("4 tick(s)"), s);
		assertTrue(s.contains("mean 50.00ms"), s);
	}

	@Test void onlyATickAtTwiceTheBudgetCountsAsLate() {
		// A server that is keeping up holds 50ms exactly, by sleeping off what the tick did not use. The first
		// live run read "mean 50.00ms" and, with the threshold at the budget itself, called 48% of ticks late —
		// which was jitter around the target. 52ms is not lateness; 120ms is.
		long t = 0;
		for (long step : new long[] {0, 50, 52, 120, 48}) {
			t += step * MS;
			KernelServerTicks.sample(t);
		}
		String s = KernelServerTicks.summary();
		assertTrue(s.contains("4 tick(s)"), s);
		assertTrue(s.contains("max 120.00ms"), s);
		assertTrue(s.contains("at twice the budget or worse 1"), s);
	}

	@Test void aPauseResumingIsNotOneVerySlowTick() {
		// A dedicated server with nobody on it stops ticking. Counting the gap as a tick would put a number in
		// the log that is true of the clock and false of the server.
		long t = 0;
		KernelServerTicks.sample(t);
		t += 10 * MS;
		KernelServerTicks.sample(t);
		t += 600_000 * MS;            // ten minutes of nobody online
		KernelServerTicks.sample(t);
		t += 10 * MS;
		KernelServerTicks.sample(t);
		String s = KernelServerTicks.summary();
		assertEquals(2, KernelServerTicks.sampled(), s);
		assertTrue(s.contains("max 10.00ms"), s);
		assertTrue(s.contains("resumes after a pause 1"), s);
	}

	@Test void theSwitchTurnsItOff() {
		System.setProperty(KernelServerTicks.SWITCH, "off");
		KernelServerTicks.reset();
		KernelServerTicks.onServerTick();
		KernelServerTicks.onServerTick();
		assertEquals(0, KernelServerTicks.sampled());
	}
}
