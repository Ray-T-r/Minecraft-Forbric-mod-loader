/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.forbric.kernel.ExpectCaught;
import net.forbric.kernel.util.Caught;

/**
 * Two different schemas registered under one key is an invariant that failed, and no catch may take it for an
 * answer.
 *
 * <p>The keys are hashes of the method bodies a repair proved, so two schemas under one key means the proof and the
 * registry disagree. The repairs register from inside the catch that turns "not this shape" into "decline", and
 * before {@link Caught.Invariant} the conflict was declined there as quietly as a shape that did not match: the
 * native tag tracker source repair stood down, the fluid tag view went stale, and nothing said so.
 */
class ContractConflictTest {
	@ExpectCaught("Ambiguous native source tag schema")
	@Test void aConflictCaughtWhereAShapeMismatchIsExpectedIsStillAFailure() {
		String key = "conflict-test-" + System.nanoTime();
		KernelTagSourceContracts.register(key, schema("a/Owner"));
		KernelTagSourceContracts.register(key, schema("a/Owner"));
		try {
			KernelTagSourceContracts.register(key, schema("b/Owner"));
			fail("a second, different schema under the same key must not be accepted");
		} catch (RuntimeException unsupported) {
			// The catch NativeTagTrackerSourceInjector.repair wraps its registration in, as it is written.
			Caught.expected(unsupported);
		}
		assertEquals(1, Caught.errorCount(), "the conflict was declined as if it were a shape that did not match");
	}

	private static KernelTagSourceContracts.Schema schema(String owner) {
		return new KernelTagSourceContracts.Schema(owner, "map", "ready", "tracker", "tag", null, List.of(), List.of(),
				Map.of(), List.of(), null, null);
	}
}
