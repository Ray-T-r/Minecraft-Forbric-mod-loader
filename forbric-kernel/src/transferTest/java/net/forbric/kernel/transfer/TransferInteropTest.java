package net.forbric.kernel.transfer;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import net.forbric.kernel.transform.TransferTransactionHooks;

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

	private void scenario(String method, boolean transform) throws Exception {
		String[] entries = System.getProperty("forbric.transferTestClasspath").split(File.pathSeparator);
		URL[] urls = new URL[entries.length];
		for (int i = 0; i < entries.length; i++) urls[i] = Path.of(entries[i]).toUri().toURL();
		try (URLClassLoader game = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) {
			@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (!transform || !(name.equals(TransferTransactionHooks.NEO) || name.equals(TransferTransactionHooks.NEO_MANAGER)
						|| name.equals(TransferTransactionHooks.FABRIC) || name.equals(TransferTransactionHooks.FABRIC_MANAGER))) return super.findClass(name);
				try (var in = findResource(name.replace('.', '/') + ".class").openStream()) {
					byte[] bytes = new TransferTransactionHooks().transform(name, in.readAllBytes(), null);
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
