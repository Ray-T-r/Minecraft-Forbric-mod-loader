package net.forbric.kernel.transfer;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.forbric.kernel.runtime.transfer.ForgeEnergyAdapters;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandlerUtil;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;

/**
 * A pack without Team Reborn Energy. EnergyInteropTest runs this class in a loader that refuses every
 * {@code team.reborn.*} class and records any attempt, so this file must not name a Reborn type anywhere: the verifier
 * would load it the moment this class links.
 */
public final class RebornAbsentEnergyScenarios {
	private RebornAbsentEnergyScenarios() { }
	private static void eq(long wanted, long actual) { if (wanted != actual) throw new AssertionError(wanted + " != " + actual); }
	private static void yes(boolean value, String what) { if (!value) throw new AssertionError(what); }

	/**
	 * Every class on the energy path of a Forge or NeoForge query, linked and initialised (so verified) with their
	 * nested classes. BlockTransferBridge is where a stray Reborn reference would have to live; it must link without one.
	 */
	static final List<String> ENERGY_PATH = List.of("BlockTransferBridge", "ForgeEnergyAdapters", "LiveTransferEndpoints",
			"EnergyAbilities", "EnergyUnits", "TransferPrecedence", "ForgeLegacyFacades", "NativeTransferAdapters",
			"PairedTransactions", "TransferIssues", "ForgeCapabilityWatch");

	public static void forgeAndNeoBridgeEachOtherWithoutReborn() throws Exception {
		ClassLoader loader = RebornAbsentEnergyScenarios.class.getClassLoader();
		for (String simple : ENERGY_PATH) link(Class.forName("net.forbric.kernel.runtime.transfer." + simple, true, loader));
		SimpleEnergyHandler neo = new SimpleEnergyHandler(10_000, 10_000, 10_000, 3000);
		EnergyStorage forge = new EnergyStorage(10_000, 10_000, 10_000, 3000);
		AtomicInteger changed = new AtomicInteger();
		EnergyHandler neoOnForge = ForgeEnergyAdapters.neo(forge, forge, changed::incrementAndGet);
		IEnergyStorage forgeOnNeo = ForgeEnergyAdapters.forge(neo);
		yes(neoOnForge != null, "the standard Forge store was refused without Reborn");
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
			eq(1000, EnergyHandlerUtil.move(neo, neoOnForge, 1000, tx));
			try (var child = net.neoforged.neoforge.transfer.transaction.Transaction.open(tx)) { eq(500, EnergyHandlerUtil.move(neoOnForge, neo, 500, child)); }
			tx.commit();
		}
		eq(2000, neo.getAmountAsLong()); eq(4000, forge.getEnergyStored()); eq(1, changed.get());
		eq(700, forgeOnNeo.receiveEnergy(forge.extractEnergy(700, false), false));
		eq(2700, neo.getAmountAsLong()); eq(3300, forge.getEnergyStored());
		// A Fabric transaction may still be open around a Forge consumer: Fabric transfer is present, Reborn is not.
		try (Transaction outer = Transaction.openOuter()) { eq(100, forgeOnNeo.extractEnergy(100, false)); eq(2600, neo.getAmountAsLong()); }
		eq(2700, neo.getAmountAsLong()); eq(6000, neo.getAmountAsLong() + forge.getEnergyStored());
		yes(!Transaction.isOpen() && net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction() == null, "a transaction was left open");
	}
	/**
	 * The Reborn half installed where Reborn cannot link (drifted, or absent while the seam thought otherwise) must fail
	 * with NOTHING exposed. It used to hand BlockTransferBridge its Fabric side first; the lookup then failed, and every
	 * later NeoForge or Forge energy query that reached that side threw NoClassDefFoundError.
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
		yes(side.get(null) == null, "a failed Reborn install left its Fabric side exposed to NeoForge and Forge queries");
		enabled.setBoolean(null, false);
	}
	private static void link(Class<?> type) throws Exception {
		type.getDeclaredMethods(); type.getDeclaredFields(); type.getDeclaredConstructors();
		for (Class<?> nested : type.getDeclaredClasses()) link(Class.forName(nested.getName(), true, type.getClassLoader()));
	}
}
