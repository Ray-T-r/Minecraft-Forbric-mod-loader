package forbric.transferworld;

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

@Mod(Machines.NEO)
public final class ForbricTransferNeo {
	private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, Machines.NEO);
	private static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Machines.NEO);
	private static final java.util.function.Supplier<Block> BLOCK = BLOCKS.register("machine", () -> Machines.block(Machines.NEO));
	private static final java.util.function.Supplier<BlockEntityType<Machines.Machine>> TYPE = TYPES.register("machine", () -> Machines.type(Machines.NEO, BLOCK.get()));
	public ForbricTransferNeo(IEventBus bus) {
		BLOCKS.register(bus); TYPES.register(bus); bus.addListener(RegisterCapabilitiesEvent.class, ForbricTransferNeo::capabilities);
		var pending = new java.util.concurrent.atomic.AtomicReference<net.minecraft.server.MinecraftServer>();
		NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> pending.set(event.getServer()));
		NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
			if (pending.compareAndSet(event.getServer(), null)) TransferWorldProbe.run(event.getServer());
		});
		System.out.println("[M33Transfer] REGISTERED neo " + Machines.NEO);
	}
	private static void capabilities(RegisterCapabilitiesEvent event) {
		event.registerBlockEntity(Capabilities.Item.BLOCK, TYPE.get(), (be, face) -> { be.lastNeo = face; return Machines.permits(face) ? be.neoItems : null; });
		event.registerBlockEntity(Capabilities.Fluid.BLOCK, TYPE.get(), (be, face) -> { be.lastNeo = face; return Machines.permits(face) ? be.neoFluids : null; });
		// A competing provider ONLY at the separate priority probe. Fabric's native answer must still win.
		var fabric = Machines.TYPES.get(Machines.FABRIC);
		if (fabric == null) throw new IllegalStateException("Fabric machine was not registered before capability registration");
		event.registerBlockEntity(Capabilities.Item.BLOCK, fabric, (be, face) -> be.getBlockPos().equals(Machines.PRIORITY) ? be.neoItems : null);
		event.registerBlockEntity(Capabilities.Fluid.BLOCK, fabric, (be, face) -> be.getBlockPos().equals(Machines.PRIORITY) ? be.neoFluids : null);
	}
}
