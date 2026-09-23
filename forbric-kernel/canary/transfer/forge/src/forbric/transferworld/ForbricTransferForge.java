package forbric.transferworld;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;

@Mod(Machines.FORGE)
public final class ForbricTransferForge {
	private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, Machines.FORGE);
	private static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Machines.FORGE);
	private static final java.util.function.Supplier<Block> BLOCK = BLOCKS.register("machine", () -> Machines.block(Machines.FORGE));
	private static final java.util.function.Supplier<BlockEntityType<Machines.Machine>> TYPE = TYPES.register("machine", () -> Machines.type(Machines.FORGE, BLOCK.get()));
	private static final java.util.function.Supplier<Block> CRATE_BLOCK = BLOCKS.register("crate", () -> Machines.crateBlock(Machines.FORGE));
	private static final java.util.function.Supplier<BlockEntityType<Machines.Crate>> CRATE = TYPES.register("crate", () -> Machines.crateType(Machines.FORGE, CRATE_BLOCK.get()));
	// A bin and a kiln on BaseContainerBlockEntity with no capability of their own: Forge answers with its InvWrapper.
	private static final java.util.function.Supplier<Block> BIN_BLOCK = BLOCKS.register("bin", () -> Machines.binBlock(Machines.FORGE));
	private static final java.util.function.Supplier<BlockEntityType<Machines.Bin>> BIN = TYPES.register("bin", () -> Machines.binType(Machines.FORGE, BIN_BLOCK.get()));
	private static final java.util.function.Supplier<Block> KILN_BLOCK = BLOCKS.register("kiln", Machines::kilnBlock);
	private static final java.util.function.Supplier<BlockEntityType<Machines.Kiln>> KILN = TYPES.register("kiln", () -> Machines.kilnType(KILN_BLOCK.get()));
	public ForbricTransferForge(FMLJavaModLoadingContext context) {
		BLOCKS.register(context.getModBusGroup()); TYPES.register(context.getModBusGroup());
		AttachCapabilitiesEvent.BlockEntities.BUS.addListener(event -> {
			if (!(event.getObject() instanceof Machines.Machine be) || be.getType() != TYPE.get()) return;
			LazyOptional<IItemHandler> items = LazyOptional.of(() -> be.forgeItems);
			LazyOptional<IFluidHandler> fluids = LazyOptional.of(() -> be.forgeFluids);
			event.addCapability(Machines.id(Machines.FORGE), new ICapabilityProvider() {
				public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction face) {
					be.lastForge = face;
					if (!Machines.permits(face)) return LazyOptional.empty();
					if (capability == ForgeCapabilities.ITEM_HANDLER) return items.cast();
					if (capability == ForgeCapabilities.FLUID_HANDLER) return fluids.cast();
					return LazyOptional.empty();
				}
			});
			event.addListener(() -> { items.invalidate(); fluids.invalidate(); });
		});
		// The Forge crate is also a plain Container; its OWN item handler is the exact standard class, NORTH/null only.
		AttachCapabilitiesEvent.BlockEntities.BUS.addListener(event -> {
			if (!(event.getObject() instanceof Machines.Crate crate) || crate.getType() != CRATE.get()) return;
			LazyOptional<IItemHandler> items = LazyOptional.of(() -> crate.forgeItems);
			event.addCapability(Machines.id(Machines.FORGE, "crate"), new ICapabilityProvider() {
				public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction face) {
					return capability == ForgeCapabilities.ITEM_HANDLER && Machines.permits(face) ? items.cast() : LazyOptional.empty();
				}
			});
			event.addListener(items::invalidate);
		});
		System.out.println("[M33Transfer] REGISTERED forge " + Machines.FORGE);
	}
}
