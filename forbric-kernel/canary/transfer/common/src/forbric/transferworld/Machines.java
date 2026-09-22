package forbric.transferworld;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.ItemStackHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;

/** Shared fixture classes are packaged ONLY in the Fabric fixture jar; three distinct mods own registration. */
public final class Machines {
	public static final String FABRIC = "forbrictransferfabric", NEO = "forbrictransferneo", FORGE = "forbrictransferforge";
	public static final Map<String, Block> BLOCKS = new ConcurrentHashMap<>();
	public static final Map<String, BlockEntityType<Machine>> TYPES = new ConcurrentHashMap<>();
	public static final BlockPos PRIORITY = new BlockPos(22, 80, 16);
	private Machines() { }
	public static Identifier id(String owner) { return Identifier.fromNamespaceAndPath(owner, "machine"); }
	public static boolean permits(Direction side) { return side == null || side == Direction.NORTH; }
	public static Block block(String owner) {
		Block result = new MachineBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id(owner))).strength(1));
		BLOCKS.put(owner, result); return result;
	}
	public static BlockEntityType<Machine> type(String owner, Block block) {
		AtomicReference<BlockEntityType<Machine>> self = new AtomicReference<>();
		BlockEntityType<Machine> type = new BlockEntityType<>((pos, state) -> new Machine(self.get(), owner, pos, state), Set.of(block));
		self.set(type); TYPES.put(owner, type); return type;
	}
	public static final class MachineBlock extends Block implements EntityBlock {
		public MachineBlock(BlockBehaviour.Properties properties) { super(properties); }
		@Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			String owner = BuiltInRegistries.BLOCK.getKey(this).getNamespace();
			return TYPES.get(owner).create(pos, state);
		}
	}

	public static final class Machine extends BlockEntity {
		public final String family;
		public boolean loadedFromDisk;
		public Direction lastFabric = Direction.UP, lastNeo = Direction.UP, lastForge = Direction.UP;
		// The Forge objects are EXACT standard classes: unknown subclasses are intentionally not admitted.
		public final ItemStackHandler forgeItems = new ItemStackHandler(1);
		public final FluidTank forgeFluids = new FluidTank(1000);
		public final ItemStacksResourceHandler neoItems = new ItemStacksResourceHandler(1) {
			@Override protected void onContentsChanged(int slot, ItemStack previous) { setChanged(); }
		};
		public final FluidStacksResourceHandler neoFluids = new FluidStacksResourceHandler(1, 1000) {
			@Override protected void onContentsChanged(int slot, net.neoforged.neoforge.fluids.FluidStack previous) { setChanged(); }
		};
		public final SingleVariantStorage<ItemVariant> fabricItems = new SingleVariantStorage<>() {
			protected ItemVariant getBlankVariant() { return ItemVariant.blank(); }
			protected long getCapacity(ItemVariant variant) { return 64; }
			@Override protected void onFinalCommit() { setChanged(); }
		};
		public final SingleVariantStorage<FluidVariant> fabricFluids = new SingleVariantStorage<>() {
			protected FluidVariant getBlankVariant() { return FluidVariant.blank(); }
			protected long getCapacity(FluidVariant variant) { return 81_000; }
			@Override protected void onFinalCommit() { setChanged(); }
		};
		public Machine(BlockEntityType<?> type, String family, BlockPos pos, BlockState state) {
			super(type, pos, state); this.family = family;
		}
		public void seed(ItemStack stack, long fluidUnits) {
			switch (family) {
				case FABRIC -> { fabricItems.variant = ItemVariant.of(stack); fabricItems.amount = stack.getCount(); fabricFluids.variant = fluidUnits == 0 ? FluidVariant.blank() : FluidVariant.of(Fluids.WATER); fabricFluids.amount = fluidUnits; }
				case NEO -> { neoItems.set(0, ItemResource.of(stack), stack.getCount()); neoFluids.set(0, FluidResource.of(Fluids.WATER), Math.toIntExact(fluidUnits / 81)); }
				case FORGE -> { forgeItems.setStackInSlot(0, stack.copy()); forgeFluids.setFluid(fluidUnits == 0 ? FluidStack.EMPTY : new FluidStack(Fluids.WATER, Math.toIntExact(fluidUnits / 81))); }
				default -> throw new IllegalStateException(family);
			}
			setChanged();
		}
		public ItemStack itemSnapshot() {
			return switch (family) {
				case FABRIC -> fabricItems.variant.toStack(Math.toIntExact(fabricItems.amount));
				case NEO -> neoItems.getResource(0).toStack(Math.toIntExact(neoItems.getAmountAsLong(0)));
				case FORGE -> forgeItems.getStackInSlot(0).copy();
				default -> throw new IllegalStateException(family);
			};
		}
		public long fluidUnits() {
			return switch (family) { case FABRIC -> fabricFluids.amount; case NEO -> neoFluids.getAmountAsLong(0) * 81; case FORGE -> forgeFluids.getFluidAmount() * 81L; default -> throw new IllegalStateException(family); };
		}
		@Override protected void saveAdditional(ValueOutput output) {
			super.saveAdditional(output); output.store("m33_items", ItemStack.OPTIONAL_CODEC, itemSnapshot()); output.putLong("m33_fluid_units", fluidUnits());
		}
		@Override protected void loadAdditional(ValueInput input) {
			super.loadAdditional(input); seed(input.read("m33_items", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY), input.getLongOr("m33_fluid_units", 0)); loadedFromDisk = true;
		}
	}
}
