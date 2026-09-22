package nativecontrol;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public final class FabricControl implements ModInitializer {
    @Override public void onInitialize() {
        var block = Registry.register(BuiltInRegistries.BLOCK, Probe.id("fabric"), Probe.block("fabric"));
        Probe.initialize("fabric", () -> block);
        ServerLifecycleEvents.SERVER_STARTED.register(Probe::started);
        ServerTickEvents.END_SERVER_TICK.register(Probe::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> Probe.commands(dispatcher));
    }
}
