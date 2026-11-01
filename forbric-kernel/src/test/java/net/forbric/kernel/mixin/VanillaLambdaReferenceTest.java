/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;


/**
 * {@link VanillaLambdaReference} does what the classes carrying it rely on: a test class that declares vanilla lambda
 * shapes in {@code @BeforeAll} and in a test leaves the declarations exactly as it found them, and each of its tests starts from what the class
 * started from plus what its {@code @BeforeAll} added, never from what an earlier test added.
 *
 * <p>The probes are run through a launcher of their own, as FixturePolicyExtensionTest runs its, so what is checked is
 * the record after a whole class has finished. They do nothing unless that launcher asked for them, since Gradle finds
 * them as test classes too. The unrestored probe is the control: without the extension the same class leaks, so the
 * check would see a restore that did not happen.
 */
@ExtendWith(VanillaLambdaReference.class)
@ResourceLock("system-properties")
class VanillaLambdaReferenceTest {
	private static final String PROBE = "forbric.test.vanillaLambdaReferenceProbe";
	private static final String OWNER = "net/example/VanillaLambdaReferenceProbe";

	@Test
	void aClassThatFillsTheRecordLeavesItAsItFoundIt() {
		VanillaLambdaReference.declare(OWNER, "lambda$before$0", "()V");
		Map<String, Map<String, List<String>>> before = VanillaLambdaReference.snapshot();

		TestExecutionSummary restored = run(RestoredProbe.class);
		assertEquals(0, restored.getTotalFailureCount(), () -> String.valueOf(restored.getFailures().stream()
				.map(f -> f.getTestIdentifier().getDisplayName() + ": " + f.getException()).toList()));
		assertEquals(2, restored.getTestsSucceededCount(), "both probe tests ran");
		assertEquals(before, VanillaLambdaReference.snapshot(), "what was there is there, and nothing else");

		run(UnrestoredProbe.class);
		assertTrue(VanillaLambdaReference.declared(OWNER, "lambda$leaked$0").contains("()V"),
				"the control: without the extension the class's declaration stays behind");
	}

	private static TestExecutionSummary run(Class<?> probe) {
		SummaryGeneratingListener listener = new SummaryGeneratingListener();
		System.setProperty(PROBE, "true");
		try {
			LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass(probe)).build(), listener);
		} finally {
			System.clearProperty(PROBE);
		}
		assertFalse(listener.getSummary().getTestsFoundCount() == 0, "the probe was not discovered");
		return listener.getSummary();
	}

	@ExtendWith(VanillaLambdaReference.class)
	@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
	static class RestoredProbe {
		@BeforeAll
		static void fillForTheClass() {
			if (Boolean.getBoolean(PROBE)) VanillaLambdaReference.declare(OWNER, "lambda$beforeAll$0", "()V");
		}

		@Test
		@Order(1)
		void fills() {
			if (!Boolean.getBoolean(PROBE)) return;
			VanillaLambdaReference.declare(OWNER, "lambda$first$0", "()V");
			VanillaLambdaReference.forget();
		}

		@Test
		@Order(2)
		void startsFromTheClassNotFromTheTestBefore() {
			if (!Boolean.getBoolean(PROBE)) return;
			assertEquals(List.of("()V"), VanillaLambdaReference.declared(OWNER, "lambda$beforeAll$0"),
					"what @BeforeAll declared is what every test starts from, though the test before forgot everything");
			assertEquals(List.of("()V"), VanillaLambdaReference.declared(OWNER, "lambda$before$0"),
					"and so is what the class found");
			assertEquals(List.of(), VanillaLambdaReference.declared(OWNER, "lambda$first$0"));
		}
	}

	static class UnrestoredProbe {
		@Test
		void leaks() {
			if (Boolean.getBoolean(PROBE)) VanillaLambdaReference.declare(OWNER, "lambda$leaked$0", "()V");
		}
	}
}
