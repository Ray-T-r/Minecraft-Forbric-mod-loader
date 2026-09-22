/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.ui.CompatibilityDecision;

@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
class CompatibilityLaunchBoundaryTest {
	@BeforeEach @AfterEach void reset() {
		CompatibilityDecision.reset(); CompatibilityFindings.reset(); ModCatalog.publish(List.of());
		System.clearProperty(CompatibilityDecision.PROPERTY);
	}

	private static void requireStop() {
		CompatibilityFindings.record(new CompatibilityFinding("initialization:test", "broken", "Initialization", "test",
				CompatibilityFinding.Confidence.CONFIRMED, true, "constructor failed", List.of("actually threw")));
		System.setProperty(CompatibilityDecision.PROPERTY, "strict");
		CompatibilityDecision.requireContinuation(false);
	}

	@Test void typedPolicyStopsRemainRecognizableThroughReflectionAndClassInitialization() throws Throwable {
		var stop = assertThrows(CompatibilityDecision.LaunchStopped.class, CompatibilityLaunchBoundaryTest::requireStop);
		for (Throwable wrapped : List.of(stop, new InvocationTargetException(stop),
				new ExceptionInInitializerError(new InvocationTargetException(stop)))) {
			assertTrue(CompatibilityDecision.isLaunchStop(wrapped));
			assertEquals(78, CompatibilityLaunchBoundary.run(() -> { throw wrapped; }));
		}
	}

	@Test void nonPolicyFailuresStillPropagateEvenAfterARecordedPolicyStop() {
		assertThrows(CompatibilityDecision.LaunchStopped.class, CompatibilityLaunchBoundaryTest::requireStop);
		var ordinary = new ExceptionInInitializerError(new IllegalStateException("Forbric compatibility policy stopped this launch"));
		assertFalse(CompatibilityDecision.isLaunchStop(ordinary), "text resembling a policy message is not evidence");
		assertSame(ordinary, assertThrows(ExceptionInInitializerError.class, () -> CompatibilityLaunchBoundary.run(() -> { throw ordinary; })));
	}

	@Test void aGameMainThatCatchesThePolicyStopCannotReturnASuccessExitStatus() throws Throwable {
		assertEquals(78, CompatibilityLaunchBoundary.run(() -> {
			try { requireStop(); } catch (CompatibilityDecision.LaunchStopped deliberatelyCaughtByGameMain) { }
		}));
		assertEquals(1, CompatibilityFindings.confirmedRequired().size());
	}

	@Test void aHealthyLaunchReturnsNormallyAndACyclicUnrelatedCauseIsNotAPolicyStop() throws Throwable {
		assertEquals(0, CompatibilityLaunchBoundary.run(() -> { }));
		Exception first = new Exception(), second = new Exception(first); first.initCause(second);
		assertFalse(CompatibilityDecision.isLaunchStop(first));
	}
}
