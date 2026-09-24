package net.forbric.kernel.runtime.transfer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import team.reborn.energy.api.EnergyStorage;

/**
 * The Fabric side of block energy: Team Reborn Energy's {@code EnergyStorage.SIDED}, the lookup Fabric energy mods
 * use (Fabric API ships no energy API of its own).
 *
 * <p>Invoked by the boot seam only when Team Reborn Energy is installed, after BlockTransferBridge.install and only if
 * that connected the bridge. It registers two fallbacks on the public lookup, exactly as the item and fluid lookups
 * get theirs: one in front of every other fallback, answering only for a Forge or NeoForge owner (so the owner's own
 * provider is found before any generic Fabric view), and one appended after them for everything else. Native Fabric
 * providers registered for a block or block entity type always answer before either. It also hands BlockTransferBridge
 * the Reborn lookup, so NeoForge and Forge consumers reach Fabric stores through the same endpoints.
 *
 * <p>Scope: placed block entities. Item energy (batteries in inventories, EnergyStorage.ITEM) is not bridged.
 */
public final class RebornEnergyBridge {
	private RebornEnergyBridge() { }
	private static final AtomicBoolean INSTALLED = new AtomicBoolean();

	/**
	 * Nothing is exposed until everything links. The Reborn half is handed to BlockTransferBridge LAST: exposed first,
	 * a Reborn API that drifted (or a Reborn that is not there at all) made every later NeoForge and Forge energy query
	 * that reached it fail with a LinkageError, so a failed install broke Forge/NeoForge energy instead of leaving it
	 * exactly as it was. Reborn's API is checked member by member before the lookup is touched.
	 */
	public static void install() {
		if (!BlockTransferBridge.installed() || !INSTALLED.compareAndSet(false, true)) return;
		requireApi();
		EnergyStorage.SIDED.registerFallback(RebornEnergyBridge::afterGeneric);
		BlockTransferBridge.ahead(EnergyStorage.SIDED, RebornEnergyBridge::beforeGeneric);
		BlockTransferBridge.fabricEnergy(new Side());
	}
	/** Every Reborn member the adapters call, resolved now rather than inside a player's query. */
	static void requireApi() {
		try {
			EnergyStorage.class.getMethod("insert", long.class, TransactionContext.class);
			EnergyStorage.class.getMethod("extract", long.class, TransactionContext.class);
			EnergyStorage.class.getMethod("getAmount");
			EnergyStorage.class.getMethod("getCapacity");
			EnergyStorage.class.getMethod("supportsInsertion");
			EnergyStorage.class.getMethod("supportsExtraction");
			BlockApiLookup.class.getMethod("find", Level.class, BlockPos.class, BlockState.class, BlockEntity.class, Object.class);
			BlockApiLookup.class.getMethod("getProvider", net.minecraft.world.level.block.Block.class);
			if (EnergyStorage.SIDED == null) throw new IllegalStateException("EnergyStorage.SIDED is null");
		} catch (NoSuchMethodException drift) {
			throw new IllegalStateException("Team Reborn Energy's API is not the one this bridge was built against: " + drift.getMessage(), drift);
		}
	}
	private static EnergyStorage beforeGeneric(Level level, BlockPos pos, BlockState state, BlockEntity entity, Direction face) {
		return fabric(BlockTransferBridge.energyForFabric(level, pos, entity, face, true));
	}
	private static EnergyStorage afterGeneric(Level level, BlockPos pos, BlockState state, BlockEntity entity, Direction face) {
		return fabric(BlockTransferBridge.energyForFabric(level, pos, entity, face, false));
	}
	private static EnergyStorage fabric(EnergyHandler view) { return view == null ? null : RebornEnergyAdapters.fabric(view); }

	private static final class Side implements BlockTransferBridge.FabricEnergy {
		public Object find(Level level, BlockPos pos, BlockState state, BlockEntity entity, Direction face, boolean generic) {
			if (generic) return EnergyStorage.SIDED.find(level, pos, state, entity, face);
			var provider = EnergyStorage.SIDED.getProvider(state.getBlock());
			return provider == null ? null : provider.find(level, pos, state, entity, face);
		}
		public EnergyHandler view(Supplier<Object> storage, BooleanSupplier valid, LongSupplier generation) {
			return RebornEnergyAdapters.neo(RebornEnergyAdapters.live(() -> (EnergyStorage) storage.get(), valid, generation));
		}
	}
}
