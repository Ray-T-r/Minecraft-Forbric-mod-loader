package forbric.forgecanary;

import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers fine and waits for MinecraftForge's CreateFluidSourceEvent, whose game hook remains absent.
 *
 * <p>The merged {@code FlowingFluid.canConvertToSource} asks only NeoForge's source-formation hook.
 * Unlike FluidPlaceBlockEvent, this event remains in the current audited dead-hook table. It proves the kernel
 * NAMES the mod that is waiting — a listener that never fires is otherwise indistinguishable from one whose
 * event simply has not happened yet.
 */
@Mod.EventBusSubscriber(modid = "forbricforgecanary")
public final class FluidSourceWaiter {
	@net.minecraftforge.eventbus.api.listener.SubscribeEvent
	public static void onFluidSource(BlockEvent.CreateFluidSourceEvent event) {
	}
}
