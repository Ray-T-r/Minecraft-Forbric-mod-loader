package forbric.transferworld;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.IntTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraftforge.energy.EnergyStorage;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;

/**
 * Energy machines for the energy world gate. Shipped in the M33 Fabric fixture jar with the other shared classes; it
 * names no Team Reborn Energy type, so a pack without Reborn (the gate's noreborn phase) still loads it. The Fabric
 * cell lives in the separate forbricenergyfabric mod, which is the only fixture that depends on Reborn.
 */
public final class EnergyMachines {
	private EnergyMachines() { }
	public static final String FABRIC = "forbricenergyfabric", NEO = "forbricenergyneo", FORGE = "forbricenergyforge";
	/** Every block and block-entity type, keyed "owner:path", as each mod registers them. */
	public static final Map<String, Block> BLOCKS = new ConcurrentHashMap<>();
	public static final Map<String, BlockEntityType<?>> TYPES = new ConcurrentHashMap<>();
	/** Only NORTH and the null face reach a cell's provider; SOUTH (and every other face) must stay refused. */
	public static boolean permits(Direction side) { return side == null || side == Direction.NORTH; }

	// Positions. The primary three are the conservation census; everything else is outside it.
	public static final BlockPos FABRIC_CELL = new BlockPos(16, 80, 48), NEO_CELL = new BlockPos(18, 80, 48), FORGE_CELL = new BlockPos(20, 80, 48);
	/** A Forge cell on which NeoForge ALSO registers a native provider (the cell's neo store). */
	public static final BlockPos PRIORITY_FORGE = new BlockPos(22, 80, 48);
	/** A Fabric cell on which NeoForge and Forge ALSO register native providers (Reborn phases only). */
	public static final BlockPos PRIORITY_FABRIC = new BlockPos(24, 80, 48);
	/** A Forge cell whose Forge provider is a custom IEnergyStorage: no write bridge for it. */
	public static final BlockPos ROGUE = new BlockPos(26, 80, 48);
	public static final BlockPos BATTERY = new BlockPos(28, 80, 48);
	public static final BlockPos INVALIDATE_FORGE = new BlockPos(30, 80, 48), INVALIDATE_NEO = new BlockPos(32, 80, 48);
	public static final BlockPos RESERVOIR = new BlockPos(34, 80, 48), SINK = new BlockPos(36, 80, 48);
	/** A Fabric (Reborn) cell whose cached NeoForge and Forge views must stop writing once it is replaced (Reborn phases). */
	public static final BlockPos INVALIDATE_FABRIC = new BlockPos(38, 80, 48);
	/** A NeoForge-owned block with no energy of its own, on which a Fabric addon registers a Reborn store (Reborn phases). */
	public static final BlockPos EXPLICIT = new BlockPos(40, 80, 48);
	/** A Forge battery loaded with more energy than its capacity, as Forge's own deserializeNBT allows. */
	public static final BlockPos OVERFULL = new BlockPos(42, 80, 48);
	/** Its own chunk: nothing else dirties it. */
	public static final BlockPos DIRTY = new BlockPos(112, 80, 48);

	/** Capacity and limits of a store, chosen by block type. */
	public record Spec(long capacity, long maxInsert, long maxExtract, boolean battery) {
		public static final Spec CELL = new Spec(100_000, 5_000, 4_000, false);
		public static final Spec BATTERY = new Spec(1_000, 1_000, 1_000, true);
		public static final Spec SINK = new Spec(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, false);
		public static final Spec RESERVOIR = new Spec(10_000_000_000L, Long.MAX_VALUE, Long.MAX_VALUE, false);
		public int intCapacity() { return (int) Math.min(capacity, Integer.MAX_VALUE); }
		public int intInsert() { return (int) Math.min(maxInsert, Integer.MAX_VALUE); }
		public int intExtract() { return (int) Math.min(maxExtract, Integer.MAX_VALUE); }
	}

	/** What the probe reads from any cell, whichever ecosystem owns it. */
	public interface Cell {
		String family();
		/** The owner's own store. */
		long energy();
		/** Sets the owner's own store; placement and loading only. */
		void seed(long energy);
		/** What a Forge provider hands out on this cell (the owner's store for a Forge cell). */
		EnergyStorage forgeStore();
		/** What a NeoForge provider hands out on this cell. */
		SimpleEnergyHandler neoStore();
		/** The Reborn store on a Fabric cell; null elsewhere. */
		Object fabricStore();
		boolean loadedFromDisk();
		Direction lastFace();
		void face(Direction face);
		int changes();
		void resetChanges();
	}

	/** A Forge-standard store plus state of its own: structurally Forge's EnergyStorage, so it is bridged. */
	public static final class PlainBattery extends EnergyStorage {
		public String label = "m40-battery";
		public PlainBattery(int capacity, int maxReceive, int maxExtract) { super(capacity, maxReceive, maxExtract, 0); }
		public String describe() { return label + ":" + energy; }
	}

	/** The Forge and NeoForge cell. Its Forge store is Forge's exact EnergyStorage (or a PlainBattery), never a subclass that overrides anything. */
	public static final class EnergyCell extends BlockEntity implements Cell {
		private final String family;
		public final EnergyStorage forgeEnergy;
		public final SimpleEnergyHandler neoEnergy;
		private boolean loadedFromDisk;
		private Direction lastFace = Direction.UP;
		private int changes;
		public EnergyCell(BlockEntityType<?> type, String family, Spec spec, BlockPos pos, BlockState state) {
			super(type, pos, state);
			this.family = family;
			this.forgeEnergy = spec.battery() ? new PlainBattery(spec.intCapacity(), spec.intInsert(), spec.intExtract())
					: new EnergyStorage(spec.intCapacity(), spec.intInsert(), spec.intExtract(), 0);
			this.neoEnergy = new SimpleEnergyHandler(spec.intCapacity(), spec.intInsert(), spec.intExtract(), 0) {
				@Override protected void onEnergyChanged(int previous) { setChanged(); }
			};
		}
		public String family() { return family; }
		public long energy() { return family.equals(FORGE) ? forgeEnergy.getEnergyStored() : neoEnergy.getAmountAsLong(); }
		public void seed(long energy) {
			// Forge's own persistence entry point sets the field; NeoForge's own setter notifies its owner.
			if (family.equals(FORGE)) forgeEnergy.deserializeNBT(null, IntTag.valueOf(Math.toIntExact(energy)));
			else neoEnergy.set(Math.toIntExact(energy));
			setChanged();
		}
		public EnergyStorage forgeStore() { return forgeEnergy; }
		public SimpleEnergyHandler neoStore() { return neoEnergy; }
		public Object fabricStore() { return null; }
		public boolean loadedFromDisk() { return loadedFromDisk; }
		public Direction lastFace() { return lastFace; }
		public void face(Direction face) { lastFace = face; }
		public int changes() { return changes; }
		public void resetChanges() { changes = 0; }
		@Override public void setChanged() { changes++; super.setChanged(); }
		@Override protected void saveAdditional(ValueOutput output) { super.saveAdditional(output); output.putLong("m40_energy", energy()); }
		@Override protected void loadAdditional(ValueInput input) { super.loadAdditional(input); seed(input.getLongOr("m40_energy", 0)); loadedFromDisk = true; }
	}

	public static final class CellBlock extends Block implements EntityBlock {
		private final String key;
		public CellBlock(String key, BlockBehaviour.Properties properties) { super(properties); this.key = key; }
		@Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return TYPES.get(key).create(pos, state); }
	}
	public static Identifier id(String owner, String path) { return Identifier.fromNamespaceAndPath(owner, path); }
	public static Block block(String owner, String path) {
		String key = owner + ":" + path;
		Block block = new CellBlock(key, BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id(owner, path))).strength(1));
		BLOCKS.put(key, block); return block;
	}
	/** A Forge or NeoForge cell type for {@code block}. */
	public static BlockEntityType<EnergyCell> cellType(String owner, String path, Spec spec, Block block) {
		AtomicReference<BlockEntityType<EnergyCell>> self = new AtomicReference<>();
		BlockEntityType<EnergyCell> type = new BlockEntityType<>((pos, state) -> new EnergyCell(self.get(), owner, spec, pos, state), Set.of(block));
		self.set(type); TYPES.put(owner + ":" + path, type); return type;
	}
	/** The owning mod of the block entity at {@code pos}, as its registry namespace says. */
	public static String owner(ServerLevel level, BlockPos pos) {
		BlockEntity entity = level.getBlockEntity(pos);
		return entity == null ? null : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).getNamespace();
	}

	/**
	 * One consumer ecosystem's PUBLIC energy API: its block lookup and its own way of moving energy. The probe uses
	 * these only; it never calls a kernel adapter. The Fabric one is supplied by forbricenergyfabric (Reborn).
	 */
	public interface Consumer {
		String family();
		/** The public block lookup on this face, or null. */
		Object find(ServerLevel level, BlockPos pos, Direction face);
		long amount(Object port);
		long capacity(Object port);
		/** One operation in its own root transaction (Forge: simulate unless {@code commit}). */
		long insert(Object port, long max, boolean commit);
		long extract(Object port, long max, boolean commit);
		/** The ecosystem's own move helper, committed. */
		long move(Object from, Object to, long max);
		/**
		 * One root: a nested move that commits, then a nested move that aborts, then the root aborts. Returns what the
		 * committed child moved. Forge has no transactions and answers -1.
		 */
		long nestedThenAbort(Object from, Object to, long max);
	}
	/** Set by forbricenergyfabric when Team Reborn Energy is installed. */
	public static final AtomicReference<Consumer> FABRIC_CONSUMER = new AtomicReference<>();

	/**
	 * A Fabric energy addon, as such addons are written: it registers Reborn's EnergyStorage.SIDED for exactly one
	 * block of ANOTHER mod (so Reborn's lookup has an explicit provider for a block Fabric does not own) and keeps one
	 * store per position. Supplied by forbricenergyfabric; its stores live in memory only.
	 */
	public interface FabricAddon {
		/** Registers the addon's provider for {@code block} through Reborn's public API (NORTH and null only). */
		void attach(Block block);
		/** The addon's own store at {@code pos}, created on first use. */
		Object store(BlockPos pos);
		long energy(BlockPos pos);
		/** The face the provider was last asked with; {@link #face} resets it. */
		Direction lastFace();
		void face(Direction face);
	}
	public static final AtomicReference<FabricAddon> FABRIC_ADDON = new AtomicReference<>();
}
