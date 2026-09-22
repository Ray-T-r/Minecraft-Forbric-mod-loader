package forbric.transferworld;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public final class ForbricTransferFabric implements ModInitializer {
	@Override public void onInitialize() {
		var block = Registry.register(BuiltInRegistries.BLOCK, Machines.id(Machines.FABRIC), Machines.block(Machines.FABRIC));
		var type = Machines.type(Machines.FABRIC, block);
		Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Machines.id(Machines.FABRIC), type);
		ItemStorage.SIDED.registerForBlockEntity((be, face) -> { be.lastFabric = face; return Machines.permits(face) ? be.fabricItems : null; }, type);
		FluidStorage.SIDED.registerForBlockEntity((be, face) -> { be.lastFabric = face; return Machines.permits(face) ? be.fabricFluids : null; }, type);
		System.out.println("[M33Transfer] REGISTERED fabric " + Machines.FABRIC);
	}
}
