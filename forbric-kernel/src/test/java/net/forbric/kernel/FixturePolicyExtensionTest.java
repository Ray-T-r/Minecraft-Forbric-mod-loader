/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The fixture policy, both where it is thrown ({@link TestFixtures}) and where a foreign skip is judged
 * ({@link FixturePolicyExtension}).
 *
 * <p>The one file besides TestFixtures that must make raw skips: the extension's whole job is what happens to a
 * skip it did not make, so its test has to make one. That is why it is in raw-assumption-allowlist.txt for good.
 */
class FixturePolicyExtensionTest {
	private static final boolean LEGACY_ALL = "1".equals(System.getenv("FORBRIC_COMPAT_FIXTURES_REQUIRED"));
	/** Set only while {@link #runNested} runs the probes; anywhere else they pass without doing anything. */
	private static final String PROBE = "forbric.fixturePolicyProbe";

	@TempDir
	Path tmp;

	private String before;

	@BeforeEach
	void remember() {
		before = System.getProperty(TestFixtures.POLICY);
	}

	@AfterEach
	void restore() {
		if (before == null) System.clearProperty(TestFixtures.POLICY);
		else System.setProperty(TestFixtures.POLICY, before);
	}

	private static void policy(String value) {
		System.setProperty(TestFixtures.POLICY, value);
	}

	private static TestAbortedException abort(Runnable skip) {
		return assertThrows(TestAbortedException.class, skip::run);
	}

	@Test
	void withNoPolicyEverySkipIsLeftASkip() {
		policy("");
		TestAbortedException tagged = abort(() -> TestFixtures.require(Fixture.STAGED, false, "staged base"));
		TestAbortedException raw = abort(() -> Assumptions.assumeTrue(false, "anything"));
		if (LEGACY_ALL) {
			assertInstanceOf(AssertionFailedError.class, FixturePolicyExtension.judge(raw),
					"FORBRIC_COMPAT_FIXTURES_REQUIRED=1 is the older spelling of all");
			return;
		}
		assertSame(tagged, FixturePolicyExtension.judge(tagged));
		assertSame(raw, FixturePolicyExtension.judge(raw));
	}

	@Test
	void aTaggedSkipFailsOnlyWhenItsOwnKindIsRequired() {
		policy("staged");
		TestAbortedException gameSide = abort(() -> {
			throw new TestAbortedException(TestFixtures.tag(Fixture.GAME_SIDE) + "not compiled");
		});
		if (!LEGACY_ALL) assertSame(gameSide, FixturePolicyExtension.judge(gameSide), "game-side is not required here");

		TestAbortedException staged = abort(() -> {
			throw new TestAbortedException(TestFixtures.tag(Fixture.STAGED) + "staged base");
		});
		AssertionFailedError failure = assertInstanceOf(AssertionFailedError.class, FixturePolicyExtension.judge(staged));
		assertTrue(failure.getMessage().contains("fixture 'staged'"), failure.getMessage());
		assertTrue(failure.getMessage().contains("-Dforbric.requireFixtures=staged"),
				"the failure must name the policy that turned the skip into it: " + failure.getMessage());
		assertTrue(failure.getMessage().endsWith("[fixture:staged] staged base"), failure.getMessage());
		assertSame(staged, failure.getCause(), "the original skip stays attached, with its stack");
	}

	@Test
	void theTagIsFoundBehindJUnitsOwnPrefix() {
		policy("staged");
		TestAbortedException raw = abort(() -> Assumptions.assumeTrue(false, "[fixture:staged] staged base"));
		assertTrue(raw.getMessage().startsWith("Assumption failed: "), "the shape this has to see through: " + raw.getMessage());
		assertInstanceOf(AssertionFailedError.class, FixturePolicyExtension.judge(raw));
		// And it is read as THAT kind, not as untagged: under this policy a game-side skip stays a skip.
		TestAbortedException other = abort(() -> Assumptions.assumeTrue(false, "[fixture:game-side] not compiled"));
		if (!LEGACY_ALL) assertSame(other, FixturePolicyExtension.judge(other));
	}

	@Test
	void anUntaggedSkipFailsUnderAnyPolicyAtAll() {
		policy("opt-in");
		TestAbortedException untagged = abort(() -> Assumptions.assumeTrue(false, "staged base absent"));
		AssertionFailedError failure = assertInstanceOf(AssertionFailedError.class, FixturePolicyExtension.judge(untagged));
		assertTrue(failure.getMessage().contains("untagged"), failure.getMessage());

		TestAbortedException misspelt = abort(() -> Assumptions.assumeTrue(false, "[fixture:stagd] staged base"));
		assertInstanceOf(AssertionFailedError.class, FixturePolicyExtension.judge(misspelt),
				"a tag naming no fixture is untagged: a typo must not be what lets a required run skip");
	}

	@Test
	void allRequiresEveryKindAndSoDoesTheEnumSpelling() {
		policy("all");
		assertEquals(EnumSet.allOf(Fixture.class), TestFixtures.requiredFixtures());
		for (Fixture kind : Fixture.values()) {
			TestAbortedException tagged = abort(() -> {
				throw new TestAbortedException(TestFixtures.tag(kind) + "x");
			});
			assertInstanceOf(AssertionFailedError.class, FixturePolicyExtension.judge(tagged), kind.id());
		}
		policy(" GAME_SIDE , mc-libraries ");
		assertEquals(LEGACY_ALL ? EnumSet.allOf(Fixture.class) : EnumSet.of(Fixture.GAME_SIDE, Fixture.MC_LIBRARIES),
				TestFixtures.requiredFixtures());
	}

	@Test
	void aMisspeltPolicyIsAnErrorRatherThanRequiringNothing() {
		policy("staged,gameside");
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, TestFixtures::requiredFixtures);
		assertTrue(error.getMessage().contains("'gameside'") && error.getMessage().contains("game-side"), error.getMessage());
	}

	@Test
	void thePolicyIsReadOnEveryCall() {
		policy("java-25");
		assertTrue(TestFixtures.required(Fixture.JAVA_25));
		policy("");
		assertEquals(LEGACY_ALL, TestFixtures.required(Fixture.JAVA_25), "a cached answer would leak into every later test");
	}

	@Test
	void onlySkipsAreJudged() {
		policy("all");
		AssertionError failure = new AssertionError("a real failure");
		assertSame(failure, FixturePolicyExtension.judge(failure));
		IllegalStateException error = new IllegalStateException("an error");
		assertSame(error, FixturePolicyExtension.judge(error));
	}

	@Test
	void everyLifecycleHandlerJudgesTheSameWay() {
		policy("staged");
		FixturePolicyExtension extension = new FixturePolicyExtension();
		TestAbortedException skip = abort(() -> Assumptions.assumeTrue(false, "[fixture:staged] x"));
		List<ThrowingHandler> handlers = List.of(
				t -> extension.handleTestExecutionException(null, t),
				t -> extension.handleBeforeAllMethodExecutionException(null, t),
				t -> extension.handleBeforeEachMethodExecutionException(null, t),
				t -> extension.handleAfterEachMethodExecutionException(null, t),
				t -> extension.handleAfterAllMethodExecutionException(null, t));
		for (ThrowingHandler handler : handlers) {
			assertThrows(AssertionFailedError.class, () -> handler.handle(skip));
		}
	}

	private interface ThrowingHandler {
		void handle(Throwable thrown) throws Throwable;
	}

	@Test
	void requireSkipsWithTheTagFirstOrFailsWhenRequired() {
		policy("");
		if (!LEGACY_ALL) {
			TestAbortedException skip = abort(() -> TestFixtures.require(Fixture.THIRD_PARTY, false, "sweep pack"));
			assertEquals("[fixture:third-party] sweep pack", skip.getMessage(),
					"thrown directly, not through assumeTrue, which would put \"Assumption failed: \" first");
		}
		TestFixtures.require(Fixture.THIRD_PARTY, true, "present is never a skip");

		policy("third-party");
		AssertionFailedError failure = assertThrows(AssertionFailedError.class,
				() -> TestFixtures.require(Fixture.THIRD_PARTY, false, "sweep pack"));
		assertTrue(failure.getMessage().startsWith("[fixture:third-party] sweep pack"), failure.getMessage());
		assertTrue(failure.getMessage().contains("-Dforbric.requireFixtures=third-party"), failure.getMessage());

	}

	@Test
	void anEntryIsReadFromAPresentJarAndItsAbsenceIsDriftUnderAnyPolicy() throws Exception {
		policy("");
		Path jar = tmp.resolve("fixture.jar");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
			out.putNextEntry(new ZipEntry("a/B.class"));
			out.write("bytes".getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		assertArrayEquals("bytes".getBytes(StandardCharsets.UTF_8), TestFixtures.requireEntry(Fixture.STAGED, jar, "a/B.class"));

		AssertionFailedError drift = assertThrows(AssertionFailedError.class,
				() -> TestFixtures.requireEntry(Fixture.STAGED, jar, "a/C.class"));
		assertEquals("content drift: " + jar + " has no a/C.class", drift.getMessage(),
				"a present fixture that lost what the test reads has changed under it; skipping would hide that");

		Path notAJar = Files.writeString(tmp.resolve("broken.jar"), "not a zip");
		assertThrows(AssertionFailedError.class, () -> TestFixtures.requireEntry(Fixture.STAGED, notAJar, "a/B.class"));

		if (!LEGACY_ALL) {
			TestAbortedException absent = abort(() -> TestFixtures.requireEntry(Fixture.STAGED, tmp.resolve("gone.jar"), "a/B.class"));
			assertTrue(absent.getMessage().startsWith("[fixture:staged] "), absent.getMessage());
		}
		policy("staged");
		assertThrows(AssertionFailedError.class, () -> TestFixtures.requireEntry(Fixture.STAGED, tmp.resolve("gone.jar"), "a/B.class"));
	}

	@Test
	void theStagedRootIsTheOneGradleHandsOverFirst() {
		String handed = System.getProperty("forbric.stagedRoot");
		try {
			System.setProperty("forbric.stagedRoot", tmp.toString());
			assertEquals(tmp, TestFixtures.stagedRoot());
			System.clearProperty("forbric.stagedRoot");
			String old = System.getenv("FORBRIC_OLD");
			Path expected = old != null && !old.isBlank() ? Path.of(old, "run")
					: Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run").normalize();
			assertEquals(expected, TestFixtures.stagedRoot());
		} finally {
			if (handed == null) System.clearProperty("forbric.stagedRoot");
			else System.setProperty("forbric.stagedRoot", handed);
		}
	}

	/**
	 * The extension is reached at all. Everything above calls it by hand, which would stay green with the
	 * service file or junit-platform.properties gone — and then no raw skip anywhere in the suite would ever
	 * meet the policy, which is the dead switch this replaces.
	 */
	@Test
	void theSuiteLoadsTheExtensionForRawSkipsInATestAndInBeforeAll() {
		policy("staged");
		TestExecutionSummary required = runNested(TestProbe.class, BeforeAllProbe.class);
		assertEquals(2, required.getTotalFailureCount(), "both raw skips must fail under -Dforbric.requireFixtures=staged");
		for (TestExecutionSummary.Failure failure : required.getFailures()) {
			assertInstanceOf(AssertionFailedError.class, failure.getException(), failure.getTestIdentifier().getDisplayName());
		}
		assertEquals(0, required.getTestsAbortedCount());

		if (LEGACY_ALL) return;
		policy("");
		TestExecutionSummary free = runNested(TestProbe.class, BeforeAllProbe.class);
		assertEquals(0, free.getTotalFailureCount(), "with no policy a skip is a skip");
		assertEquals(1, free.getTestsAbortedCount());
		assertEquals(1, free.getContainersAbortedCount());
	}

	private static TestExecutionSummary runNested(Class<?>... probes) {
		SummaryGeneratingListener listener = new SummaryGeneratingListener();
		System.setProperty(PROBE, "true");
		try {
			var request = LauncherDiscoveryRequestBuilder.request();
			for (Class<?> probe : probes) request.selectors(selectClass(probe));
			LauncherFactory.create().execute(request.build(), listener);
		} finally {
			System.clearProperty(PROBE);
		}
		assertFalse(listener.getSummary().getTestsFoundCount() == 0, "the probes were not discovered");
		return listener.getSummary();
	}

	static class TestProbe {
		@Test
		void skips() {
			if (Boolean.getBoolean(PROBE)) Assumptions.assumeTrue(false, "[fixture:staged] probe");
		}
	}

	static class BeforeAllProbe {
		@BeforeAll
		static void skips() {
			if (Boolean.getBoolean(PROBE)) Assumptions.assumeTrue(false, "[fixture:staged] probe in @BeforeAll");
		}

		@Test
		void runs() {
		}
	}
}
