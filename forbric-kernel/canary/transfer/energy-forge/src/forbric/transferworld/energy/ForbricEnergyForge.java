package forbric.transferworld.energy;

import forbric.transferworld.EnergyMachines;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;

/**
 * The Forge energy cell: Forge's exact EnergyStorage on ForgeCapabilities.ENERGY, NORTH/null only, attached the usual
 * way. The rogue cell hands out a custom IEnergyStorage, which no bridge may write; the battery a Forge-shaped subclass.
 */
@Mod(EnergyMachines.FORGE)
public final class ForbricEnergyForge {
	private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, EnergyMachines.FORGE);
	private static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, EnergyMachines.FORGE);
	private static final java.util.function.Supplier<Block> CELL_BLOCK = BLOCKS.register("cell", () -> EnergyMachines.block(EnergyMachines.FORGE, "cell"));
	private static final java.util.function.Supplier<BlockEntityType<EnergyMachines.EnergyCell>> CELL = TYPES.register("cell",
			() -> EnergyMachines.cellType(EnergyMachines.FORGE, "cell", EnergyMachines.Spec.CELL, CELL_BLOCK.get()));
	private static final java.util.function.Supplier<Block> BATTERY_BLOCK = BLOCKS.register("battery", () -> EnergyMachines.block(EnergyMachines.FORGE, "battery"));
	private static final java.util.function.Supplier<BlockEntityType<EnergyMachines.EnergyCell>> BATTERY = TYPES.register("battery",
			() -> EnergyMachines.cellType(EnergyMachines.FORGE, "battery", EnergyMachines.Spec.BATTERY, BATTERY_BLOCK.get()));
	public ForbricEnergyForge(FMLJavaModLoadingContext context) {
		BLOCKS.register(context.getModBusGroup()); TYPES.register(context.getModBusGroup());
		AttachCapabilitiesEvent.BlockEntities.BUS.addListener(event -> {
			if (!(event.getObject() instanceof EnergyMachines.Cell cell)) return;
			boolean own = event.getObject().getType() == CELL.get() || event.getObject().getType() == BATTERY.get();
			boolean fabric = cell.family().equals(EnergyMachines.FABRIC);
			if (!own && !fabric) return;
			LazyOptional<IEnergyStorage> standard = LazyOptional.of(cell::forgeStore);
			LazyOptional<IEnergyStorage> rogue = LazyOptional.of(() -> new Rogue());
			event.addCapability(EnergyMachines.id(EnergyMachines.FORGE, "energy"), new ICapabilityProvider() {
				public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction face) {
					if (capability != ForgeCapabilities.ENERGY) return LazyOptional.empty();
					var pos = event.getObject().getBlockPos();
					// On a Fabric cell only at the priority probe: Forge's own answer must win over the bridge there.
					if (fabric) return pos.equals(EnergyMachines.PRIORITY_FABRIC) ? standard.cast() : LazyOptional.empty();
					cell.face(face);
					if (!EnergyMachines.permits(face)) return LazyOptional.empty();
					return pos.equals(EnergyMachines.ROGUE) ? rogue.cast() : standard.cast();
				}
			});
			event.addListener(() -> { standard.invalidate(); rogue.invalidate(); });
		});
		System.out.println("[M40Energy] REGISTERED forge " + EnergyMachines.FORGE);
	}

	/** A perfectly ordinary custom store, as many mods write one. Its writes cannot be taken back, so no bridge gets it. */
	static final class Rogue implements IEnergyStorage {
		int energy;
		public int receiveEnergy(int max, boolean simulate) { int moved = Math.max(0, Math.min(max, 1000 - energy)); if (!simulate) energy += moved; return moved; }
		public int extractEnergy(int max, boolean simulate) { int moved = Math.max(0, Math.min(max, energy)); if (!simulate) energy -= moved; return moved; }
		public int getEnergyStored() { return energy; }
		public int getMaxEnergyStored() { return 1000; }
		public boolean canExtract() { return true; }
		public boolean canReceive() { return true; }
	}
}
