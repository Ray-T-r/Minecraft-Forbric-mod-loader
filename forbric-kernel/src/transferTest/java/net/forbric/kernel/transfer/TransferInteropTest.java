package net.forbric.kernel.transfer;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.forbric.kernel.transform.TransferTransactionHooks;

import static org.junit.jupiter.api.Assertions.*;

/** Each scenario owns genuine, transformed native transaction engines, with no thread-local state shared across tests. */
class TransferInteropTest {
	@Test void fabricNestedRollback() throws Exception { scenario("fabricNestedRollback", true); }
	@Test void neoNestedRollback() throws Exception { scenario("neoNestedRollback", true); }
	@Test void finalNotificationsAfterBothEnginesClose() throws Exception { scenario("notifications", true); }
	@Test void fluidQuantizationNeverRoundsAMutation() throws Exception { scenario("quantization", true); }
	@Test void exceptionsRollBackAndReleaseBothScopes() throws Exception { scenario("providerFailure", true); }
	@Test void oppositeDirectionReentryUsesPairedParents() throws Exception { scenario("reentry", true); }
	@Test void repeatedSimulationDoesNotReserveOrChangeAnything() throws Exception { scenario("simulation", true); }
	@Test void missingHooksRefuseBeforeMutation() throws Exception { scenario("missingHooks", false); }
	@Test void nativeScopesStayNativeWhenNoBridgeIsUsed() throws Exception { scenario("nativeOnly", true); }
	@Test void unrelatedRootsAreNotJoined() throws Exception { scenario("unrelatedRoots", true); }
	@Test void transferBetweenTwoEndpointsConservesEveryUnit() throws Exception { scenario("conservation", true); }
	@Test void cachedViewsResolveNewProvidersAndRejectMidTransferInvalidation() throws Exception { scenario("liveEndpoints", true); }
	@Test void finalCallbackFailureStillNotifiesAndClosesTheOtherSide() throws Exception { scenario("notificationFailure", true); }
	@Test void invalidProviderCountsRollBack() throws Exception { scenario("invalidAmounts", true); }
	@Test void openNeoChildCannotCauseHalfCommittedFabricRoot() throws Exception { scenario("unbalancedNeoChild", true); }
	@Test void openFabricChildCannotCauseHalfCommittedNeoRoot() throws Exception { scenario("unbalancedFabricChild", true); }
	@Test void capabilityWatchReplacesSubscriptionsAndReleasesInvalidValues() throws Exception { scenario("optionalWatch", true); }
	@Test void transientWatchesHoldOneSubscriptionPerCachedOptional() throws Exception { scenario("transientWatchesShareOneSubscription", true); }
	@Test void invalidationCannotHideNeoRollbackFailure() throws Exception { scenario("neoRollbackFailure", true); }
	@Test void invalidationCannotHideFabricRollbackFailure() throws Exception { scenario("fabricRollbackFailure", true); }
	@Test void fabricCloseCallbackCannotOpenPeerChild() throws Exception { scenario("fabricCallbackOpensPeer", true); }
	@Test void fabricCloseCallbackCannotClosePeerEarly() throws Exception { scenario("fabricCallbackClosesPeer", true); }
	@Test void neoCloseCallbackCannotOpenPeerChild() throws Exception { scenario("neoCallbackOpensPeer", true); }
	@Test void neoCloseCallbackCannotClosePeerEarly() throws Exception { scenario("neoCallbackClosesPeer", true); }
	@Test void neoFinalNotificationMayTransferAfterAPairedCommit() throws Exception { scenario("neoFinalCommitMayTransferAgain", true); }
	@Test void fabricFinalNotificationMayTransferAfterAPairedCommit() throws Exception { scenario("fabricFinalCommitMayTransferAgain", true); }
	@Test void fabricOriginValidatesRootInvariantsOnce() throws Exception { scenario("fabricOriginCommitValidatesOnce", true); }
	@Test void neoOriginNestedCommitValidatesOnceAndClosesItsPeer() throws Exception { scenario("neoOriginNestedCommitValidatesOnce", true); }

	/**
	 * Fabric's transfer module with its implementation moved to other packages and simple names, and its private
	 * methods renamed (TransferTestClasspath). The hooks find that engine from the public Transaction and pair it as the
	 * original; without them the same transfer is refused before either side changes.
	 */
	@Test void aRelocatedFabricEngineIsPairedByWhatItDoes() throws Exception {
		URL[] urls = TransferTestClasspath.urls(true);
		try (URLClassLoader game = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
			var engine = TransferTestClasspath.hooks(game).engine();
			assertNotNull(engine, "the relocated engine is not reachable from the public Transaction");
			assertTrue(engine.manager().startsWith(TransferTestClasspath.HIDDEN) && engine.transaction().startsWith(TransferTestClasspath.HIDDEN), engine::toString);
			for (URL url : urls) if (url.getPath().endsWith("fabric-transfer-api-relocated.jar")) {
				assertTrue(TransferTestClasspath.classes(url).stream().noneMatch(name -> name.startsWith(TransferTestClasspath.IMPL)));
			}
		}
		for (String method : List.of("fabricNestedRollback", "neoNestedRollback", "conservation", "notifications", "fabricCallbackOpensPeer", "fabricFinalCommitMayTransferAgain")) {
			scenario(method, true, true);
		}
		scenario("missingHooks", false, true);
	}

	private void scenario(String method, boolean transform) throws Exception { scenario(method, transform, false); }

	private void scenario(String method, boolean transform, boolean relocated) throws Exception {
		URL[] urls = TransferTestClasspath.urls(relocated);
		try (URLClassLoader game = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) {
			private final TransferTransactionHooks hooks = TransferTestClasspath.hooks(this);
			@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (!transform || !hooks.targets(name)) return super.findClass(name);
				try (var in = findResource(name.replace('.', '/') + ".class").openStream()) {
					byte[] bytes = hooks.transform(name, in.readAllBytes(), null);
					return defineClass(name, bytes, 0, bytes.length);
				} catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
			}
		}) {
			try { Class.forName("net.forbric.kernel.transfer.NativeTransferScenarios", true, game).getMethod(method).invoke(null); }
			catch (InvocationTargetException failure) {
				if (failure.getCause() instanceof Error error) throw error;
				if (failure.getCause() instanceof Exception exception) throw exception;
				throw failure;
			}
		}
	}
}
