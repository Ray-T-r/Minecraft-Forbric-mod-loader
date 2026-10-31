package net.forbric.kernel.transfer;

import java.util.List;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

/**
 * A pack without Team Reborn Energy. EnergyInteropTest runs this class in a loader that refuses every
 * {@code team.reborn.*} class and records any attempt, so this file must not name a Reborn type anywhere: the verifier
 * would load it the moment this class links.
 */
public final class RebornAbsentEnergyScenarios {
	private RebornAbsentEnergyScenarios() { }
	private static void yes(boolean value, String what) { if (!value) throw new AssertionError(what); }

	/**
	 * Every class on the energy path of a NeoForge query, linked and initialised (so verified) with their
	 * nested classes. BlockTransferBridge is where a stray Reborn reference would have to live; it must link without one.
	 */
	static final List<String> ENERGY_PATH = List.of("BlockTransferBridge", "LiveTransferEndpoints", "EnergyAbilities",
			"EnergyUnits", "TransferPrecedence", "NativeTransferAdapters", "PairedTransactions", "TransferIssues");

	public static void theEnergyPathLinksWithoutReborn() throws Exception {
		ClassLoader loader = RebornAbsentEnergyScenarios.class.getClassLoader();
		for (String simple : ENERGY_PATH) link(Class.forName("net.forbric.kernel.runtime.transfer." + simple, true, loader));
		yes(!Transaction.isOpen() && net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction() == null, "a transaction was left open");
	}
	/**
	 * The Reborn half installed where Reborn cannot link (drifted, or absent while the seam thought otherwise) must fail
	 * with NOTHING exposed. It used to hand BlockTransferBridge its Fabric side first; the lookup then failed, and every
	 * later NeoForge energy query that reached that side threw NoClassDefFoundError.
	 */
	public static void aFailedRebornInstallExposesNothing() throws Exception {
		ClassLoader loader = RebornAbsentEnergyScenarios.class.getClassLoader();
		Class<?> bridge = Class.forName("net.forbric.kernel.runtime.transfer.BlockTransferBridge", true, loader);
		var enabled = bridge.getDeclaredField("enabled"); enabled.setAccessible(true); enabled.setBoolean(null, true);
		var side = bridge.getDeclaredField("fabricEnergy"); side.setAccessible(true);
		Throwable failure = null;
		try { Class.forName("net.forbric.kernel.runtime.transfer.RebornEnergyBridge", true, loader).getMethod("install").invoke(null); }
		catch (java.lang.reflect.InvocationTargetException thrown) { failure = thrown.getCause(); }
		yes(failure instanceof LinkageError, "installing the Reborn half without Reborn did not fail: " + failure);
		yes(side.get(null) == null, "a failed Reborn install left its Fabric side exposed to NeoForge queries");
		enabled.setBoolean(null, false);
	}
	private static void link(Class<?> type) throws Exception {
		type.getDeclaredMethods(); type.getDeclaredFields(); type.getDeclaredConstructors();
		for (Class<?> nested : type.getDeclaredClasses()) link(Class.forName(nested.getName(), true, type.getClassLoader()));
	}
}
