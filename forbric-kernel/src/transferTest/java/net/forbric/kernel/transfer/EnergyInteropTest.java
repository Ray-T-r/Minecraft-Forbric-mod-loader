package net.forbric.kernel.transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import net.forbric.kernel.transform.ForgeTransferShapeAudit;
import net.forbric.kernel.transform.TransferTransactionHooks;

/**
 * Block energy between Team Reborn Energy, NeoForge and MinecraftForge, on the real engines. Each scenario gets its
 * own loader over the transfer test classpath (which carries the Team Reborn Energy 5.0.0 jar the runtime compiles
 * against): the four transaction classes get the kernel's pairing hooks, and Forge's EnergyStorage passes through
 * ForgeTransferShapeAudit.certify exactly as its final definition does in the game, unless a scenario is about the
 * certificate being absent. The Reborn-absent scenario's loader refuses every team.reborn class and records the attempt.
 */
class EnergyInteropTest {
	private static final String FORGE_ENERGY = "net.minecraftforge.energy.EnergyStorage";

	@Test void fabricConsumerOfNeoNestsSimulatesAndNotifiesOnce() throws Exception { scenario("fabricConsumerOfNeo"); }
	@Test void neoConsumerOfFabricNestsSimulatesAndNotifiesOnce() throws Exception { scenario("neoConsumerOfFabric"); }
	@Test void transactionalConsumersOfForgeRestoreEveryDepthAndDirtyOncePerRoot() throws Exception { scenario("transactionalConsumersOfForge"); }
	@Test void forgeConsumerSimulatesByAbortAndExecutesByCommit() throws Exception { scenario("forgeConsumerOfTransactionalStores"); }
	@Test void allSixDirectionsConserveEnergy() throws Exception { scenario("conservationAcrossAllSixDirections"); }
	@Test void longRequestsClampToIntAndTheRemainderStaysInTheSource() throws Exception { scenario("longAmountsClampToIntWithoutLoss"); }
	@Test void unauditedForgeStoresGetNoWriteBridgeAndAreReportedOncePerClass() throws Exception { scenario("unauditedForgeStoresAreRefused"); }
	@Test void anUncertifiedStandardForgeStoreGetsNoWriteBridge() throws Exception {
		run(NativeEnergyScenarios.class.getName(), "uncertifiedStandardStoreIsRefused", false, null);
	}
	@Test void invalidProviderAmountsAreRolledBack() throws Exception { scenario("invalidProviderAmountsRollBack"); }
	@Test void anOutOfBoundsForgeStoreMovesNothingInsteadOfThrowing() throws Exception { scenario("outOfBoundsForgeStoresMoveNothing"); }
	@Test void aForgeConsumerOfAnEndpointInvalidatedMidOperationMovesNothing() throws Exception { scenario("forgeConsumerOfAnInvalidatedEndpointMovesNothing"); }
	@Test void forgesEmptyStoreIsAnEmptyAnswerNotARefusal() throws Exception { scenario("forgesEmptyStoreIsAnEmptyAnswer"); }
	@Test void liveRebornViewsResolveAgainAndMoveNothingOnceInvalidated() throws Exception { scenario("liveRebornStoresMoveNothingOnceInvalidated"); }
	@Test void liveViewsResolveAgainAndAnInvalidatedEndpointMovesNothing() throws Exception { scenario("liveEndpointsResolveAndRefuseAfterInvalidation"); }
	@Test void bridgeViewsAreNeverWrappedAgain() throws Exception { scenario("bridgesNeverStack"); }
	@Test void finalNotificationsWaitForBothRoots() throws Exception { scenario("finalNotificationsAfterBothRootsClose"); }
	@Test void withoutRebornForgeAndNeoStillBridgeAndNoRebornClassIsRequested() throws Exception {
		List<String> requested = new CopyOnWriteArrayList<>();
		run(RebornAbsentEnergyScenarios.class.getName(), "forgeAndNeoBridgeEachOtherWithoutReborn", true, requested);
		assertEquals(List.of(), requested, "a Reborn class was requested in a pack without Team Reborn Energy");
	}
	@Test void aFailedRebornInstallLeavesForgeAndNeoEnergyUntouched() throws Exception {
		List<String> requested = new CopyOnWriteArrayList<>();
		run(RebornAbsentEnergyScenarios.class.getName(), "aFailedRebornInstallExposesNothing", true, requested);
		assertTrue(requested.stream().anyMatch(name -> name.startsWith("team.reborn.")), "the install never reached Reborn: " + requested);
	}
	/** The control for the test above: the same loader does refuse Reborn, so an empty record is a real answer. */
	@Test void theRebornFreeLoaderReallyRefusesReborn() throws Exception {
		List<String> requested = new CopyOnWriteArrayList<>();
		Throwable failure = assertThrows(Throwable.class, () -> run(NativeEnergyScenarios.class.getName(), "bridgesNeverStack", true, requested));
		assertTrue(requested.stream().anyMatch(name -> name.startsWith("team.reborn.")), failure + " / " + requested);
	}

	private void scenario(String method) throws Exception { run(NativeEnergyScenarios.class.getName(), method, true, null); }

	/** {@code rebornRequests} non-null: refuse team.reborn classes and record every request for one. */
	private void run(String owner, String method, boolean certify, List<String> rebornRequests) throws Exception {
		String[] entries = System.getProperty("forbric.transferTestClasspath").split(File.pathSeparator);
		URL[] urls = new URL[entries.length];
		for (int i = 0; i < entries.length; i++) urls[i] = Path.of(entries[i]).toUri().toURL();
		List<String> hooked = List.of(TransferTransactionHooks.NEO, TransferTransactionHooks.NEO_MANAGER, TransferTransactionHooks.FABRIC,
				TransferTransactionHooks.FABRIC_MANAGER);
		try (URLClassLoader game = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) {
			@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (rebornRequests != null && name.startsWith("team.reborn.")) { rebornRequests.add(name); throw new ClassNotFoundException(name); }
				return super.loadClass(name, resolve);
			}
			@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
				boolean hook = hooked.contains(name), audit = certify && name.equals(FORGE_ENERGY);
				if (!hook && !audit) return super.findClass(name);
				try (var in = findResource(name.replace('.', '/') + ".class").openStream()) {
					byte[] bytes = in.readAllBytes();
					bytes = hook ? new TransferTransactionHooks().transform(name, bytes, null) : ForgeTransferShapeAudit.certify(name, bytes);
					return defineClass(name, bytes, 0, bytes.length);
				} catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
			}
		}) {
			try { Class.forName(owner, true, game).getMethod(method).invoke(null); }
			catch (InvocationTargetException failure) {
				if (failure.getCause() instanceof Error error) throw error;
				if (failure.getCause() instanceof Exception exception) throw exception;
				throw failure;
			}
		}
	}
}
