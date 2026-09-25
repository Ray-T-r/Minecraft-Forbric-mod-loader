package forbric.hopper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Three Fabric item storages as Fabric mods write them: a slotted one on a block entity (NeoForge sees it through the
 * kernel's capability bridge), the same store behind a CombinedStorage (not slotted, so NeoForge cannot), and a slotted
 * store on a half-height block with no block entity (registered for the block). Each provider records every face it
 * is asked for and answers only UP and DOWN.
 */
public final class Stores {
	public static final Map<BlockPos, List<Direction>> FACES = new ConcurrentHashMap<>();
	public static final Map<BlockPos, SingleVariantStorage<ItemVariant>> BLOCK_ONLY = new ConcurrentHashMap<>();
	public static final Set<BlockPos> PREMISE = ConcurrentHashMap.newKeySet();
	public static Block slotted, unslotted, blockOnly;
	static BlockEntityType<StoreEntity> slottedType, unslottedType;

	static void record(BlockPos pos, Direction face) {
		if (!PREMISE.contains(pos)) FACES.computeIfAbsent(pos.immutable(), p -> new ArrayList<>()).add(face);
	}

	static boolean permits(Direction face) { return face == Direction.UP || face == Direction.DOWN; }

	static Identifier id(String path) { return Identifier.fromNamespaceAndPath("forbrichopper", path); }

	static BlockBehaviour.Properties properties(String path) {
		return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id(path))).strength(1);
	}

	public static SingleVariantStorage<ItemVariant> newStore(Runnable changed) {
		return new SingleVariantStorage<>() {
			@Override protected ItemVariant getBlankVariant() { return ItemVariant.blank(); }
			@Override protected long getCapacity(ItemVariant variant) { return 64; }
			@Override protected void onFinalCommit() { changed.run(); }
		};
	}

	static void register() {
		AtomicReference<BlockEntityType<StoreEntity>> slottedSelf = new AtomicReference<>(), unslottedSelf = new AtomicReference<>();
		slotted = Registry.register(BuiltInRegistries.BLOCK, id("slotted"), new StoreBlock(properties("slotted"), slottedSelf));
		unslotted = Registry.register(BuiltInRegistries.BLOCK, id("unslotted"), new StoreBlock(properties("unslotted"), unslottedSelf));
		blockOnly = Registry.register(BuiltInRegistries.BLOCK, id("blockonly"), new HalfBlock(properties("blockonly")));
		slottedType = new BlockEntityType<>((pos, state) -> new StoreEntity(slottedSelf.get(), pos, state), Set.of(slotted));
		unslottedType = new BlockEntityType<>((pos, state) -> new StoreEntity(unslottedSelf.get(), pos, state), Set.of(unslotted));
		slottedSelf.set(slottedType);
		unslottedSelf.set(unslottedType);
		Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("slotted"), slottedType);
		Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("unslotted"), unslottedType);
		ItemStorage.SIDED.registerForBlockEntity((be, face) -> {
			record(be.getBlockPos(), face);
			return permits(face) ? be.store : null;
		}, slottedType);
		ItemStorage.SIDED.registerForBlockEntity((be, face) -> {
			record(be.getBlockPos(), face);
			return permits(face) ? new CombinedStorage<>(List.of(be.store)) : null;
		}, unslottedType);
		ItemStorage.SIDED.registerForBlocks((level, pos, state, be, face) -> {
			record(pos, face);
			return permits(face) ? BLOCK_ONLY.computeIfAbsent(pos.immutable(), p -> newStore(() -> { })) : null;
		}, blockOnly);
	}

	public static final class StoreEntity extends BlockEntity {
		public final SingleVariantStorage<ItemVariant> store = newStore(this::setChanged);

		public StoreEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
	}

	static final class StoreBlock extends Block implements EntityBlock {
		private final AtomicReference<BlockEntityType<StoreEntity>> type;

		StoreBlock(BlockBehaviour.Properties properties, AtomicReference<BlockEntityType<StoreEntity>> type) {
			super(properties);
			this.type = type;
		}

		@Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return type.get().create(pos, state); }
	}

	/** Half a block high, so a hopper under it still reaches item entities lying on it. */
	static final class HalfBlock extends Block {
		private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 8, 16);

		HalfBlock(BlockBehaviour.Properties properties) { super(properties); }

		@Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }
	}
}
