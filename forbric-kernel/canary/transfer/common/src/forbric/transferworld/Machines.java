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
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
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
	/** Container-shaped machines: a Forge crate and a NeoForge crate, and a NeoForge BaseContainerBlockEntity cabinet. */
	public static final Map<String, Block> CRATE_BLOCKS = new ConcurrentHashMap<>();
	public static final Map<String, BlockEntityType<Crate>> CRATE_TYPES = new ConcurrentHashMap<>();
	public static final AtomicReference<Block> CABINET_BLOCK = new AtomicReference<>();
	private static final AtomicReference<BlockEntityType<Cabinet>> CABINET_TYPE = new AtomicReference<>();
	/** BaseContainerBlockEntity machines that leave getCapability alone: a Forge bin and kiln, and a Fabric bin. */
	public static final Map<String, Block> BIN_BLOCKS = new ConcurrentHashMap<>();
	public static final Map<String, BlockEntityType<Bin>> BIN_TYPES = new ConcurrentHashMap<>();
	public static final AtomicReference<Block> KILN_BLOCK = new AtomicReference<>();
	private static final AtomicReference<BlockEntityType<Kiln>> KILN_TYPE = new AtomicReference<>();
	private Machines() { }
	public static Identifier id(String owner) { return Identifier.fromNamespaceAndPath(owner, "machine"); }
	public static Identifier id(String owner, String path) { return Identifier.fromNamespaceAndPath(owner, path); }
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
	public static Block crateBlock(String owner) {
		Block result = new CrateBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id(owner, "crate"))).strength(1));
		CRATE_BLOCKS.put(owner, result); return result;
	}
	public static BlockEntityType<Crate> crateType(String owner, Block block) {
		AtomicReference<BlockEntityType<Crate>> self = new AtomicReference<>();
		BlockEntityType<Crate> type = new BlockEntityType<>((pos, state) -> new Crate(self.get(), pos, state), Set.of(block));
		self.set(type); CRATE_TYPES.put(owner, type); return type;
	}
	/** Only the NeoForge fixture registers a cabinet; it is owned by NEO. */
	public static Block cabinetBlock() {
		Block result = new CabinetBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id(NEO, "cabinet"))).strength(1));
		CABINET_BLOCK.set(result); return result;
	}
	public static BlockEntityType<Cabinet> cabinetType(Block block) {
		BlockEntityType<Cabinet> type = new BlockEntityType<>((pos, state) -> new Cabinet(CABINET_TYPE.get(), pos, state), Set.of(block));
		CABINET_TYPE.set(type); return type;
	}
	public static Block binBlock(String owner) {
		Block result = new BinBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id(owner, "bin"))).strength(1));
		BIN_BLOCKS.put(owner, result); return result;
	}
	public static BlockEntityType<Bin> binType(String owner, Block block) {
		AtomicReference<BlockEntityType<Bin>> self = new AtomicReference<>();
		BlockEntityType<Bin> type = new BlockEntityType<>((pos, state) -> new Bin(self.get(), pos, state), Set.of(block));
		self.set(type); BIN_TYPES.put(owner, type); return type;
	}
	/** Only the Forge fixture registers a kiln; it is owned by FORGE. */
	public static Block kilnBlock() {
		Block result = new KilnBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id(FORGE, "kiln"))).strength(1));
		KILN_BLOCK.set(result); return result;
	}
	public static BlockEntityType<Kiln> kilnType(Block block) {
		BlockEntityType<Kiln> type = new BlockEntityType<>((pos, state) -> new Kiln(KILN_TYPE.get(), pos, state), Set.of(block));
		KILN_TYPE.set(type); return type;
	}
	public static final class BinBlock extends Block implements EntityBlock {
		public BinBlock(BlockBehaviour.Properties properties) { super(properties); }
		@Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			return BIN_TYPES.get(BuiltInRegistries.BLOCK.getKey(this).getNamespace()).create(pos, state);
		}
	}
	public static final class KilnBlock extends Block implements EntityBlock {
		public KilnBlock(BlockBehaviour.Properties properties) { super(properties); }
		@Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return KILN_TYPE.get().create(pos, state); }
	}
	public static final class CrateBlock extends Block implements EntityBlock {
		public CrateBlock(BlockBehaviour.Properties properties) { super(properties); }
		@Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			return CRATE_TYPES.get(BuiltInRegistries.BLOCK.getKey(this).getNamespace()).create(pos, state);
		}
	}
	public static final class CabinetBlock extends Block implements EntityBlock {
		public CabinetBlock(BlockBehaviour.Properties properties) { super(properties); }
		@Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return CABINET_TYPE.get().create(pos, state); }
	}
	/**
	 * A machine that is ALSO a plain Container, so Fabric API's generic Container fallback would answer for it with
	 * every slot writable on every face. Its owner's own capability is a separate one-slot handler on NORTH/null.
	 * Every Container write is counted: Fabric's generic wrapper writes (and rolls back) through setItem.
	 */
	public static final class Crate extends BlockEntity implements Container {
		public final NonNullList<ItemStack> slots = NonNullList.withSize(3, ItemStack.EMPTY);
		public int containerWrites;
		public final ItemStackHandler forgeItems = new ItemStackHandler(1);
		public final ItemStacksResourceHandler neoItems = new ItemStacksResourceHandler(1) {
			@Override protected void onContentsChanged(int slot, ItemStack previous) { setChanged(); }
		};
		public Crate(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
		@Override public int getContainerSize() { return slots.size(); }
		@Override public boolean isEmpty() { return slots.stream().allMatch(ItemStack::isEmpty); }
		@Override public ItemStack getItem(int slot) { return slots.get(slot); }
		@Override public ItemStack removeItem(int slot, int count) { containerWrites++; return ContainerHelper.removeItem(slots, slot, count); }
		@Override public ItemStack removeItemNoUpdate(int slot) { containerWrites++; return ContainerHelper.takeItem(slots, slot); }
		@Override public void setItem(int slot, ItemStack stack) { containerWrites++; slots.set(slot, stack); }
		@Override public boolean stillValid(Player player) { return true; }
		@Override public void clearContent() { containerWrites++; slots.clear(); }
	}
	/**
	 * A NeoForge machine built on BaseContainerBlockEntity. The merged game's Forge override of that class answers
	 * ITEM_HANDLER with a generic wrapper over the whole Container and hands every other capability to the
	 * super-call, so a Forge consumer met neither the owner's item handler nor any fluid at all.
	 */
	public static final class Cabinet extends BaseContainerBlockEntity {
		public NonNullList<ItemStack> slots = NonNullList.withSize(3, ItemStack.EMPTY);
		public final ItemStacksResourceHandler neoItems = new ItemStacksResourceHandler(1) {
			@Override protected void onContentsChanged(int slot, ItemStack previous) { setChanged(); }
		};
		public final FluidStacksResourceHandler neoFluids = new FluidStacksResourceHandler(1, 1000) {
			@Override protected void onContentsChanged(int slot, net.neoforged.neoforge.fluids.FluidStack previous) { setChanged(); }
		};
		public Cabinet(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
		@Override protected Component getDefaultName() { return Component.literal("M33 cabinet"); }
		@Override protected NonNullList<ItemStack> getItems() { return slots; }
		@Override protected void setItems(NonNullList<ItemStack> items) { slots = items; }
		@Override protected AbstractContainerMenu createMenu(int id, Inventory inventory) { return null; }
		@Override public int getContainerSize() { return slots.size(); }
	}
	/**
	 * A plain storage bin on BaseContainerBlockEntity that overrides nothing transfer touches, as most mod chests
	 * do. Every Forge query of it is answered by the merged override's generic InvWrapper over the whole Container.
	 */
	public static class Bin extends BaseContainerBlockEntity {
		public NonNullList<ItemStack> slots = NonNullList.withSize(3, ItemStack.EMPTY);
		public Bin(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
		@Override protected Component getDefaultName() { return Component.literal("M33 bin"); }
		@Override protected NonNullList<ItemStack> getItems() { return slots; }
		@Override protected void setItems(NonNullList<ItemStack> items) { slots = items; }
		@Override protected AbstractContainerMenu createMenu(int id, Inventory inventory) { return null; }
		@Override public int getContainerSize() { return slots.size(); }
	}
	/** A bin whose Container has writes of its own: every setItem restarts its work, as a furnace-like machine's does. */
	public static final class Kiln extends Bin {
		public int restarts;
		public Kiln(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
		@Override public void setItem(int slot, ItemStack stack) { restarts++; super.setItem(slot, stack); }
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
