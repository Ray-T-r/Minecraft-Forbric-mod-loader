/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel;

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

import net.forbric.kernel.transform.DuplicateLambdaPruneInjector;

/**
 * {@link DroppedLambdaRecordRestore} does what the classes carrying it rely on: a test class that fills the drop record
 * in {@code @BeforeAll} and in a test leaves it exactly as it found it, and each of its tests starts from what the class
 * started from plus what its {@code @BeforeAll} added, never from what an earlier test added.
 *
 * <p>The probes are run through a launcher of their own, as FixturePolicyExtensionTest runs its, so what is checked is
 * the record after a whole class has finished. They do nothing unless that launcher asked for them, since Gradle finds
 * them as test classes too. The unrestored probe is the control: without the extension the same class leaks, so the
 * check would see a restore that did not happen.
 */
@ExtendWith(DroppedLambdaRecordRestore.class)
@ResourceLock("system-properties")
class DroppedLambdaRecordRestoreTest {
	private static final String PROBE = "forbric.test.droppedLambdaRecordProbe";
	private static final String OWNER = "net/example/DroppedLambdaRecordRestoreProbe";

	@Test
	void aClassThatFillsTheRecordLeavesItAsItFoundIt() {
		DuplicateLambdaPruneInjector.recordDroppedForTest(OWNER, "lambda$before$0", "()V");
		Map<String, List<String>> before = DuplicateLambdaPruneInjector.droppedForTest();

		TestExecutionSummary restored = run(RestoredProbe.class);
		assertEquals(0, restored.getTotalFailureCount(), () -> String.valueOf(restored.getFailures().stream()
				.map(f -> f.getTestIdentifier().getDisplayName() + ": " + f.getException()).toList()));
		assertEquals(2, restored.getTestsSucceededCount(), "both probe tests ran");
		assertEquals(before, DuplicateLambdaPruneInjector.droppedForTest(), "what was there is there, and nothing else");

		run(UnrestoredProbe.class);
		assertTrue(DuplicateLambdaPruneInjector.droppedDescriptors(OWNER, "lambda$leaked$0").contains("()V"),
				"the control: without the extension the class's drop stays behind");
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

	@ExtendWith(DroppedLambdaRecordRestore.class)
	@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
	static class RestoredProbe {
		@BeforeAll
		static void fillForTheClass() {
			if (Boolean.getBoolean(PROBE)) DuplicateLambdaPruneInjector.recordDroppedForTest(OWNER, "lambda$beforeAll$0", "()V");
		}

		@Test
		@Order(1)
		void fills() {
			if (!Boolean.getBoolean(PROBE)) return;
			DuplicateLambdaPruneInjector.recordDroppedForTest(OWNER, "lambda$first$0", "()V");
			DuplicateLambdaPruneInjector.forgetDroppedForTest();
		}

		@Test
		@Order(2)
		void startsFromTheClassNotFromTheTestBefore() {
			if (!Boolean.getBoolean(PROBE)) return;
			assertEquals(List.of("()V"), DuplicateLambdaPruneInjector.droppedDescriptors(OWNER, "lambda$beforeAll$0"),
					"what @BeforeAll recorded is what every test starts from, though the test before forgot everything");
			assertEquals(List.of("()V"), DuplicateLambdaPruneInjector.droppedDescriptors(OWNER, "lambda$before$0"),
					"and so is what the class found");
			assertEquals(List.of(), DuplicateLambdaPruneInjector.droppedDescriptors(OWNER, "lambda$first$0"));
		}
	}

	static class UnrestoredProbe {
		@Test
		void leaks() {
			if (Boolean.getBoolean(PROBE)) DuplicateLambdaPruneInjector.recordDroppedForTest(OWNER, "lambda$leaked$0", "()V");
		}
	}
}
