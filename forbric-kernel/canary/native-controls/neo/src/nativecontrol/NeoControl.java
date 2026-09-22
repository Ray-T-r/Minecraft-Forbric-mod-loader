package nativecontrol;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod("nativecontrolneo")
public final class NeoControl {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, "nativecontrolneo");
    private static final java.util.function.Supplier<Block> BLOCK = BLOCKS.register("probe", () -> Probe.block("neo"));
    public NeoControl(IEventBus bus) {
        BLOCKS.register(bus); Probe.initialize("neo", BLOCK);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> Probe.started(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> Probe.tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> Probe.commands(event.getDispatcher()));
    }
}
