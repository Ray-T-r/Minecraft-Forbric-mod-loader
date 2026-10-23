/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * For a test outside this package that runs the cross-host predicate island: plans it, as the kernel does at mixin time, from
 * the actual fabric-api drowning modifier, the staged merged base and the given NeoForge breathing hook (the bytes the island
 * injector will then transform), so {@code CrossHostPredicateIslandInjector} has a registered plan to put into that hook.
 * Needs the same staged fixtures as {@link MixinCrossHostPredicateIslandTest}.
 */
public final class CrossHostIslandFixture {
	private CrossHostIslandFixture() {
	}

	/** Plans the island on {@code hook} (CommonHooks' bytes as the chain hands them to the island injector). */
	public static void planOn(byte[] hook) throws Exception {
		String host = "net/neoforged/neoforge/common/CommonHooks";
		try (var in = new MixinCrossHostPredicateIslandTest.Inputs()) {
			in.cache.put(host, MixinCrossHostPredicateIslandTest.node(hook));
			assertEquals(1, in.bind((name, register) -> {
				register.run();
				return true;
			}), "the island is planned on the actual drowning modifier and this hook");
		}
	}

	public static void reset() {
		MixinCrossHostPredicateIsland.resetForTests();
	}
}
