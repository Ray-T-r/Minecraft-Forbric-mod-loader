package forbric.transferworld.energy;

import forbric.transferworld.EnergyMachines;
import forbric.transferworld.EnergyWorldProbe;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The NeoForge energy cell: a SimpleEnergyHandler registered on Capabilities.Energy.BLOCK, NORTH/null only. Runs the
 * probe. Its "bare" block entity deliberately gets no energy capability at all.
 */
@Mod(EnergyMachines.NEO)
public final class ForbricEnergyNeo {
	private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, EnergyMachines.NEO);
	private static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, EnergyMachines.NEO);
	private static final java.util.function.Supplier<Block> CELL_BLOCK = BLOCKS.register("cell", () -> EnergyMachines.block(EnergyMachines.NEO, "cell"));
	private static final java.util.function.Supplier<BlockEntityType<EnergyMachines.EnergyCell>> CELL = TYPES.register("cell",
			() -> EnergyMachines.cellType(EnergyMachines.NEO, "cell", EnergyMachines.Spec.CELL, CELL_BLOCK.get()));
	private static final java.util.function.Supplier<Block> SINK_BLOCK = BLOCKS.register("sink", () -> EnergyMachines.block(EnergyMachines.NEO, "sink"));
	private static final java.util.function.Supplier<BlockEntityType<EnergyMachines.EnergyCell>> SINK = TYPES.register("sink",
			() -> EnergyMachines.cellType(EnergyMachines.NEO, "sink", EnergyMachines.Spec.SINK, SINK_BLOCK.get()));
	/** A NeoForge block entity with NO energy capability registered: only a Fabric addon gives it energy. */
	private static final java.util.function.Supplier<Block> BARE_BLOCK = BLOCKS.register("bare", () -> EnergyMachines.block(EnergyMachines.NEO, "bare"));
	private static final java.util.function.Supplier<BlockEntityType<EnergyMachines.EnergyCell>> BARE = TYPES.register("bare",
			() -> EnergyMachines.cellType(EnergyMachines.NEO, "bare", EnergyMachines.Spec.CELL, BARE_BLOCK.get()));
	public ForbricEnergyNeo(IEventBus bus) {
		BLOCKS.register(bus); TYPES.register(bus); bus.addListener(RegisterCapabilitiesEvent.class, ForbricEnergyNeo::capabilities);
		var pending = new java.util.concurrent.atomic.AtomicReference<net.minecraft.server.MinecraftServer>();
		NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> pending.set(event.getServer()));
		NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> { if (pending.compareAndSet(event.getServer(), null)) EnergyWorldProbe.run(event.getServer()); });
		System.out.println("[M40Energy] REGISTERED neo " + EnergyMachines.NEO);
	}
	@SuppressWarnings("unchecked")
	private static void capabilities(RegisterCapabilitiesEvent event) {
		for (var type : java.util.List.of(CELL.get(), SINK.get())) event.registerBlockEntity(Capabilities.Energy.BLOCK, type, (cell, face) -> {
			cell.face(face); return EnergyMachines.permits(face) ? cell.neoStore() : null;
		});
		// Competing native providers, only at the priority probes: NeoForge's own answer must win over any bridge.
		var forge = (BlockEntityType<EnergyMachines.EnergyCell>) EnergyMachines.TYPES.get(EnergyMachines.FORGE + ":cell");
		if (forge == null) throw new IllegalStateException("the Forge energy cell was not registered before capability registration");
		event.registerBlockEntity(Capabilities.Energy.BLOCK, forge, (cell, face) -> cell.getBlockPos().equals(EnergyMachines.PRIORITY_FORGE) ? cell.neoStore() : null);
		var fabric = (BlockEntityType<net.minecraft.world.level.block.entity.BlockEntity>) EnergyMachines.TYPES.get(EnergyMachines.FABRIC + ":cell");
		if (fabric != null) event.registerBlockEntity(Capabilities.Energy.BLOCK, fabric,
				(entity, face) -> entity.getBlockPos().equals(EnergyMachines.PRIORITY_FABRIC) ? ((EnergyMachines.Cell) entity).neoStore() : null);
	}
}
