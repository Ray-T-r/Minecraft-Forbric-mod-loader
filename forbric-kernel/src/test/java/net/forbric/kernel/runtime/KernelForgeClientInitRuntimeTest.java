package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/** Runs the compiled typed funnel and bridge with isolated API spies, without initializing a real client. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class KernelForgeClientInitRuntimeTest {

	@TempDir static Path temporary;
	private static Path stubs;
	private static Path runtime;
	private String previousInit;
	private String previousEvents;

	@BeforeAll
	static void compileSpies() throws Exception {
		runtime = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		for (String name : List.of("KernelForgeClientInit", "KernelGameClientReload", "ForgeClientReloadCapture")) {
			assumeTrue(Files.isRegularFile(runtime.resolve("net/forbric/kernel/runtime/" + name + ".class")),
					"runtime class has not been compiled: " + name);
		}
		stubs = KernelForgeClientInitFixture.compileSpies(temporary.resolve("spies"));
	}

	@BeforeEach
	void enableBothFeatures() {
		previousInit = System.setProperty("forbric.forgeClientInit", "on");
		previousEvents = System.setProperty("forbric.unifiedEvents", "on");
	}

	@AfterEach
	void restoreProperties() {
		restore("forbric.forgeClientInit", previousInit);
		restore("forbric.unifiedEvents", previousEvents);
	}

	@Test
	void forgeCaptureFlowsThroughTheInstalledLowestBridgeAndTheSameListenerReloadsOnce() throws Exception {
		try (var fixture = fixture()) {
			Object listener = fixture.listeners().getFirst();
			fixture.installBridge();
			assertEquals("LOWEST", fixture.value("priority"));
			assertEquals(false, fixture.value("receiveCanceled"));
			assertEquals("net.neoforged.neoforge.client.event.AddClientReloadListenersEvent",
					((Class<?>) fixture.value("eventType")).getName());
			fixture.init();
			assertEquals(List.of("forge:init", "forge:post", "scratch:close", "options:true", "neo:init",
					"graph:add", "neo:update"), fixture.trace());
			assertEquals(1, fixture.count("forgeCalls"));
			assertEquals(1, fixture.count("forgePosts"), "the already posted self-destructing event must not be posted again");
			assertEquals(1, fixture.count("neoCalls"));
			assertSame(listener, fixture.registered().getFirst());
			assertSame(listener, fixture.realListeners().getFirst(), "Neo's list replacement must retain the capture");
			assertEquals(0, fixture.applies(listener));
			fixture.reload();
			assertEquals(1, fixture.applies(listener), "execute the listener that actually reached the published graph");
			assertTrue(fixture.scratchClosed());
			assertFalse(fixture.realClosed());
			assertNull(fixture.drain());
		}
	}

	@Test
	void anEmptyCaptureStillSuppressesTheLegacyPost() throws Exception {
		try (var fixture = fixture()) {
			fixture.listeners().clear();
			fixture.installBridge();
			fixture.init();
			assertEquals(1, fixture.count("forgePosts"));
			assertEquals(1, fixture.count("forgeCalls"));
			assertEquals(1, fixture.count("optionLoads"));
			assertEquals(1, fixture.count("neoCalls"));
			assertTrue(fixture.registered().isEmpty());
			assertTrue(fixture.realListeners().isEmpty());
			assertNull(fixture.drain());
		}
	}

	@Test
	void disablingTheFunnelCallsOnlyNeoAndLeavesTheExistingLegacyBridgeWorking() throws Exception {
		System.setProperty("forbric.forgeClientInit", "off");
		try (var fixture = fixture()) {
			Object listener = fixture.listeners().getFirst();
			fixture.installBridge();
			fixture.init();
			assertEquals(0, fixture.count("forgeCalls"));
			assertEquals(0, fixture.count("optionLoads"));
			assertEquals(1, fixture.count("neoCalls"));
			assertEquals(1, fixture.count("forgePosts"), "only the legacy bridge should post Forge registration");
			assertEquals(List.of("neo:init", "forge:post", "graph:add", "scratch:close", "neo:update"), fixture.trace());
			assertSame(listener, fixture.realListeners().getFirst());
			fixture.reload();
			assertEquals(1, fixture.applies(listener));
			assertTrue(fixture.scratchClosed());
			assertNull(fixture.drain());
		}
	}

	@Test
	void aForgeFailureClosesScratchAndPropagatesWithoutRetryOrNeoDispatch() throws Exception {
		try (var fixture = fixture()) {
			RuntimeException failure = new IllegalStateException("Forge failed after taking ownership of init");
			fixture.value("forgeFailure", failure);
			fixture.installBridge();
			assertSame(failure, assertThrows(IllegalStateException.class, fixture::init));
			assertEquals(1, fixture.count("forgeCalls"));
			assertEquals(0, fixture.count("forgePosts"));
			assertEquals(0, fixture.count("optionLoads"));
			assertEquals(0, fixture.count("neoCalls"));
			assertTrue(fixture.scratchClosed());
			assertNull(fixture.drain());
		}
	}

	@Test
	void neoFailuresBeforeAndAfterBridgeDispatchPropagateAndClearTheCapture() throws Exception {
		for (String phase : List.of("neoFailureBefore", "neoFailureAfter")) {
			try (var fixture = fixture()) {
				RuntimeException failure = new IllegalArgumentException(phase);
				fixture.value(phase, failure);
				fixture.installBridge();
				assertSame(failure, assertThrows(IllegalArgumentException.class, fixture::init));
				assertEquals(1, fixture.count("forgeCalls"));
				assertEquals(1, fixture.count("forgePosts"));
				assertEquals(1, fixture.count("neoCalls"));
				assertTrue(fixture.scratchClosed());
				assertNull(fixture.drain(), phase + " must not leave captured listeners on this thread");
			}
		}
	}

	@Test
	void capturedGraphFailuresReachTheCallerInsteadOfBecomingAWarningAndPartialSuccess() throws Exception {
		try (var fixture = fixture()) {
			RuntimeException failure = new IllegalArgumentException("graph rejected the listener");
			fixture.value("graphFailure", failure);
			fixture.installBridge();
			assertSame(failure, assertThrows(IllegalArgumentException.class, fixture::init));
			assertEquals(1, fixture.count("forgePosts"));
			assertTrue(fixture.warnings().isEmpty(), "captured registration failure must not take the legacy catch path");
			assertTrue(fixture.realListeners().isEmpty());
			assertNull(fixture.drain());
		}
	}

	@Test
	void geometryRunsOnEveryCallAndTheFeatureSwitchAlsoControlsForgeParticleDispatch() throws Exception {
		try (var fixture = fixture()) {
			fixture.geometry();
			fixture.geometry();
			assertEquals(2, fixture.count("geometryCalls"));
			fixture.particles();
			assertEquals(List.of("geometry", "geometry", "forge:particles", "neo:particles"), fixture.trace());
			System.setProperty("forbric.forgeClientInit", "off");
			fixture.geometry();
			fixture.particles();
			assertEquals(2, fixture.count("geometryCalls"));
			assertEquals(List.of("geometry", "geometry", "forge:particles", "neo:particles", "neo:particles"), fixture.trace());
			assertNull(fixture.drain());
		}
	}

	@Test
	void deliberatelyDisabledEventBridgingWarnsAndDoesNotLeakAnUnconsumedScope() throws Exception {
		System.setProperty("forbric.unifiedEvents", "off");
		try (var fixture = fixture()) {
			fixture.init(); // The boot installer deliberately does not install the bridge with unifiedEvents=off.
			assertEquals(1, fixture.count("forgeCalls"));
			assertEquals(1, fixture.count("neoCalls"));
			assertEquals(1, fixture.count("forgePosts"));
			assertTrue(fixture.realListeners().isEmpty());
			assertEquals(1, fixture.warnings().size());
			assertTrue(fixture.warnings().getFirst().contains("bridge is disabled"));
			assertNull(fixture.drain());
		}
	}

	@Test
	void anUnexpectedMissingBridgeIsAnErrorForANonemptyCaptureAndStillClearsItsScope() throws Exception {
		try (var fixture = fixture()) {
			IllegalStateException failure = assertThrows(IllegalStateException.class, fixture::init);
			assertTrue(failure.getMessage().contains("capture was not consumed"));
			assertEquals(1, fixture.count("forgeCalls"));
			assertEquals(1, fixture.count("neoCalls"));
			assertTrue(fixture.warnings().isEmpty());
			assertNull(fixture.drain());
		}
	}

	private static KernelForgeClientInitFixture fixture() throws Exception {
		return new KernelForgeClientInitFixture(stubs, runtime);
	}

	private static void restore(String name, String previous) {
		if (previous == null) System.clearProperty(name);
		else System.setProperty(name, previous);
	}
}
