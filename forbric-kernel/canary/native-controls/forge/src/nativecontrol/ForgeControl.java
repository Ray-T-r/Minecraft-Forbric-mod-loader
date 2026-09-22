package nativecontrol;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.TickEvent;

@Mod("nativecontrolforge")
public final class ForgeControl {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, "nativecontrolforge");
    private static final java.util.function.Supplier<Block> BLOCK = BLOCKS.register("probe", () -> Probe.block("forge"));
    public ForgeControl(FMLJavaModLoadingContext context) {
        BLOCKS.register(context.getModBusGroup()); Probe.initialize("forge", BLOCK);
        ServerStartedEvent.BUS.addListener(event -> Probe.started(event.getServer()));
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> Probe.tick(event.server()));
        RegisterCommandsEvent.BUS.addListener(event -> Probe.commands(event.getDispatcher()));
    }
}
