/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.forbric.kernel.ExpectCaught;
import net.forbric.kernel.GateLogContract;

/** What a catch that reports to {@link Caught} leaves behind: in the log, in the count, and at which line. */
@ExpectCaught
class CaughtTest {
	@BeforeEach
	@AfterEach
	void forget() {
		Caught.reset();
	}

	@Test
	void aFailureIsLoggedWithItsStackTheFirstTimeAndOneLineAfterUntilTheCap() throws Exception {
		String[] out = new String[1];
		GateLogContract.capture(() -> {
			for (int i = 0; i < Caught.DEFAULT_PRINTED_PER_SITE + 5; i++) {
				Caught.warn(new IllegalStateException("bridge lost " + i), "[Forbric/Test] forwarding %s failed", "tick");
			}
			return null;
		}, out);

		List<Caught.Site> sites = Caught.failureSites();
		assertEquals(1, sites.size(), sites.toString());
		assertEquals(Caught.DEFAULT_PRINTED_PER_SITE + 5, sites.get(0).count());
		assertEquals(Caught.DEFAULT_PRINTED_PER_SITE + 5, Caught.errorCount(), "every occurrence counts, printed or not");
		String log = out[0];
		assertEquals(Caught.DEFAULT_PRINTED_PER_SITE, count(log, "[Forbric/Caught] [Forbric/Test] forwarding tick failed"),
				log);
		assertEquals(1, count(log, "counted, not printed"), log);
		assertTrue(log.contains("java.lang.IllegalStateException: bridge lost 0"), log);
		assertTrue(log.contains("again (#2): java.lang.IllegalStateException: bridge lost 1"), log);
		assertFalse(log.contains("bridge lost " + Caught.DEFAULT_PRINTED_PER_SITE), "past the cap: " + log);
		assertEquals(1, count(log, "\tat net.forbric.kernel.util.CaughtTest.aFailureIsLogged"),
				"one stack trace, the first: " + log);
	}

	@Test
	void pastTheCapAMessageTheSiteHasNotSaidBeforeIsStillPrinted() throws Exception {
		String[] out = new String[1];
		GateLogContract.capture(() -> {
			int repeats = Caught.DEFAULT_PRINTED_PER_SITE + 5;
			for (int i = 0; i <= repeats; i++) {
				// One site, as one forwarding helper is for every event it forwards.
				Caught.warn(new RuntimeException("x"), "[Forbric/Test] forwarding %s failed", i < repeats ? "tick" : "ServerStartedEvent");
			}
			return null;
		}, out);
		assertTrue(out[0].contains("forwarding ServerStartedEvent failed — caught at"), out[0]);
		assertTrue(Caught.failureSites().get(0).toString().contains("(and 1 other message(s))"),
				Caught.failureSites().toString());
	}

	@Test
	void theSiteIsTheCatchThatReported() {
		int line = 0;
		try {
			throw new IllegalArgumentException("x");
		} catch (IllegalArgumentException e) {
			line = new Throwable().getStackTrace()[0].getLineNumber() + 1;
			Caught.error(e, "failed");
		}
		assertEquals(getClass().getName() + ".theSiteIsTheCatchThatReported:" + line, Caught.sites().get(0).where());
		assertEquals(Caught.Kind.ERROR, Caught.sites().get(0).kind());
	}

	@Test
	void twoCatchesAreTwoSitesAndExpectedIsNoFailure() throws Exception {
		String[] out = new String[1];
		GateLogContract.capture(() -> {
			Caught.warn(new RuntimeException("a"), "first");
			Caught.warn(new RuntimeException("b"), "second");
			Caught.expected(new ClassNotFoundException("optional.Mod"));
			return null;
		}, out);
		assertEquals(2, Caught.errorCount());
		assertEquals(2, Caught.failureSites().size());
		assertEquals(3, Caught.sites().size());
		assertFalse(out[0].contains("optional.Mod"), "expected is debug-only: " + out[0]);
	}

	@Test
	void anExpectedCatchThatCaughtABugReportsItAsAFailure() throws Exception {
		String[] out = new String[1];
		GateLogContract.capture(() -> {
			// What ASM's Analyzer hands a recogniser when the kernel's own interpreter dereferenced null.
			Caught.expected(new Exception("Error at instruction 12", new NullPointerException("frame")));
			Caught.expected(new IllegalStateException("not this shape"));
			Caught.expected(new NoSuchMethodError("an older optional API"));
			// ASM's own word for bytecode that does not balance: about the code analysed, not the analyser.
			IndexOutOfBoundsException underflow = new IndexOutOfBoundsException("Cannot pop operand off an empty stack.");
			underflow.setStackTrace(new StackTraceElement[] {
					new StackTraceElement("org.objectweb.asm.tree.analysis.Frame", "pop", "Frame.java", 1)});
			Caught.expected(new Exception("Error at instruction 0", underflow));
			return null;
		}, out);
		assertEquals(1, Caught.errorCount(), Caught.sites().toString());
		assertEquals(Caught.Kind.WARN, Caught.failureSites().get(0).kind());
		assertTrue(out[0].contains("programming error (java.lang.NullPointerException: frame)"), out[0]);
		assertEquals(4, Caught.sites().size());
	}

	@Test
	void aMessageThatIsNotAFormatIsKeptAsWritten() throws Exception {
		String[] out = new String[1];
		GateLogContract.capture(() -> {
			Caught.warn(new RuntimeException("x"), "100% of mod-id%d failed");
			return null;
		}, out);
		assertTrue(out[0].contains("100% of mod-id%d failed"), out[0]);
	}

	@Test
	void theSummaryNamesEverySiteWithItsCountAndTheWrappedCause() throws Exception {
		String[] out = new String[1];
		GateLogContract.capture(() -> {
			for (int i = 0; i < 3; i++) {
				Caught.warn(new InvocationTargetException(new NoSuchFieldError("MAP")), "[Forbric/Test] reflective read failed");
			}
			Caught.logSummary("for the test");
			return null;
		}, out);
		assertTrue(out[0].contains("summary for the test: 3 failure(s) caught at 1 site(s)"), out[0]);
		assertTrue(out[0].contains("3x WARN at " + getClass().getName()), out[0]);
		assertTrue(out[0].contains("java.lang.NoSuchFieldError: MAP"), "the cause, not the wrapper: " + out[0]);
	}

	@Test
	void aSummaryOfNothingSaysNothing() throws Exception {
		String[] out = new String[1];
		GateLogContract.capture(() -> {
			Caught.expected(new ClassNotFoundException("x"));
			Caught.logSummary("for the test");
			return null;
		}, out);
		assertEquals("", out[0]);
	}

	private static int count(String text, String needle) {
		int n = 0;
		for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) n++;
		return n;
	}
}
