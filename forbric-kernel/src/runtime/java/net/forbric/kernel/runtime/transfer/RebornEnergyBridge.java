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

	public static void install() {
		if (!BlockTransferBridge.installed() || !INSTALLED.compareAndSet(false, true)) return;
		BlockTransferBridge.fabricEnergy(new Side());
		EnergyStorage.SIDED.registerFallback(RebornEnergyBridge::afterGeneric);
		BlockTransferBridge.ahead(EnergyStorage.SIDED, RebornEnergyBridge::beforeGeneric);
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
