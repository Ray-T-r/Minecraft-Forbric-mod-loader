package forbric.transferworld.energy;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import forbric.transferworld.EnergyMachines;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraftforge.energy.EnergyStorage;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import team.reborn.energy.api.EnergyStorageUtil;
import team.reborn.energy.api.base.SimpleEnergyStorage;

/**
 * The Fabric energy cell: Team Reborn Energy's SimpleEnergyStorage on EnergyStorage.SIDED, NORTH/null only, registered
 * the way Fabric energy mods register theirs. The only fixture that depends on Reborn; it also hands the probe the
 * Fabric consumer API (EnergyStorage.SIDED.find plus Reborn's own move helper).
 */
public final class ForbricEnergyFabric implements ModInitializer {
	@Override public void onInitialize() {
		for (var spec : java.util.List.of(new Object[] {"cell", EnergyMachines.Spec.CELL}, new Object[] {"reservoir", EnergyMachines.Spec.RESERVOIR})) {
			String path = (String) spec[0];
			Block block = Registry.register(BuiltInRegistries.BLOCK, EnergyMachines.id(EnergyMachines.FABRIC, path), EnergyMachines.block(EnergyMachines.FABRIC, path));
			AtomicReference<BlockEntityType<RebornCell>> self = new AtomicReference<>();
			BlockEntityType<RebornCell> type = new BlockEntityType<>((pos, state) -> new RebornCell(self.get(), (EnergyMachines.Spec) spec[1], pos, state), Set.of(block));
			self.set(type);
			Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, EnergyMachines.id(EnergyMachines.FABRIC, path), type);
			EnergyMachines.TYPES.put(EnergyMachines.FABRIC + ":" + path, type);
			team.reborn.energy.api.EnergyStorage.SIDED.registerForBlockEntity((cell, face) -> { cell.face(face); return EnergyMachines.permits(face) ? cell.fabricEnergy : null; }, type);
		}
		EnergyMachines.FABRIC_CONSUMER.set(new FabricConsumer());
		System.out.println("[M40Energy] REGISTERED fabric " + EnergyMachines.FABRIC);
	}

	public static final class RebornCell extends BlockEntity implements EnergyMachines.Cell {
		public final SimpleEnergyStorage fabricEnergy;
		// NeoForge and Forge stores of their own, handed out only at the priority probe.
		private final SimpleEnergyHandler neoExtra = new SimpleEnergyHandler(1000);
		private final EnergyStorage forgeExtra = new EnergyStorage(1000);
		private boolean loadedFromDisk;
		private Direction lastFace = Direction.UP;
		private int changes;
		RebornCell(BlockEntityType<?> type, EnergyMachines.Spec spec, BlockPos pos, BlockState state) {
			super(type, pos, state);
			fabricEnergy = new SimpleEnergyStorage(spec.capacity(), spec.maxInsert(), spec.maxExtract()) {
				@Override protected void onFinalCommit() { setChanged(); }
			};
		}
		public String family() { return EnergyMachines.FABRIC; }
		public long energy() { return fabricEnergy.amount; }
		public void seed(long energy) { fabricEnergy.amount = energy; setChanged(); }
		public EnergyStorage forgeStore() { return forgeExtra; }
		public SimpleEnergyHandler neoStore() { return neoExtra; }
		public Object fabricStore() { return fabricEnergy; }
		public boolean loadedFromDisk() { return loadedFromDisk; }
		public Direction lastFace() { return lastFace; }
		public void face(Direction face) { lastFace = face; }
		public int changes() { return changes; }
		public void resetChanges() { changes = 0; }
		@Override public void setChanged() { changes++; super.setChanged(); }
		@Override protected void saveAdditional(ValueOutput output) { super.saveAdditional(output); output.putLong("m40_energy", fabricEnergy.amount); }
		@Override protected void loadAdditional(ValueInput input) { super.loadAdditional(input); fabricEnergy.amount = input.getLongOr("m40_energy", 0); loadedFromDisk = true; }
	}

	/** Fabric's public energy API: EnergyStorage.SIDED and Reborn's transactions. */
	static final class FabricConsumer implements EnergyMachines.Consumer {
		public String family() { return EnergyMachines.FABRIC; }
		public Object find(ServerLevel level, BlockPos pos, Direction face) { return team.reborn.energy.api.EnergyStorage.SIDED.find(level, pos, face); }
		private static team.reborn.energy.api.EnergyStorage of(Object port) { return (team.reborn.energy.api.EnergyStorage) port; }
		public long amount(Object port) { return of(port).getAmount(); }
		public long capacity(Object port) { return of(port).getCapacity(); }
		public long insert(Object port, long max, boolean commit) {
			try (Transaction tx = Transaction.openOuter()) { long moved = of(port).insert(max, tx); if (commit) tx.commit(); return moved; }
		}
		public long extract(Object port, long max, boolean commit) {
			try (Transaction tx = Transaction.openOuter()) { long moved = of(port).extract(max, tx); if (commit) tx.commit(); return moved; }
		}
		public long move(Object from, Object to, long max) {
			try (Transaction tx = Transaction.openOuter()) { long moved = EnergyStorageUtil.move(of(from), of(to), max, tx); tx.commit(); return moved; }
		}
		public long nestedThenAbort(Object from, Object to, long max) {
			try (Transaction root = Transaction.openOuter()) {
				long kept;
				try (Transaction child = root.openNested()) { kept = EnergyStorageUtil.move(of(from), of(to), max, child); child.commit(); }
				try (Transaction child = root.openNested()) { EnergyStorageUtil.move(of(from), of(to), max, child); }
				return kept;
			}
		}
	}
}
