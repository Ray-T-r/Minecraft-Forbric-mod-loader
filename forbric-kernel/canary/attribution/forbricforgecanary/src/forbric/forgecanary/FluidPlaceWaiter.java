package forbric.forgecanary;

import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers fine and waits for MinecraftForge's FluidPlaceBlockEvent, which the merged game never posts.
 *
 * <p>The merged {@code LiquidBlock} asks only NeoForge's hook when lava or water turns into stone, cobblestone or
 * obsidian, so this listener is wired onto something nothing calls. It is here so the gate can prove the kernel
 * NAMES the mod that is waiting — a listener that never fires is otherwise indistinguishable from one whose
 * event simply has not happened yet.
 */
@Mod.EventBusSubscriber(modid = "forbricforgecanary")
public final class FluidPlaceWaiter {
	@net.minecraftforge.eventbus.api.listener.SubscribeEvent
	public static void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
	}
}
