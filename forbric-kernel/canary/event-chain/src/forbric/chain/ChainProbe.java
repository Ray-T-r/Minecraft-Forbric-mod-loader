package forbric.chain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * A MinecraftForge listener vetoes one tagged entity joining the level; NeoForge fires the join. The kernel's bridge
 * forwards NeoForge's event to MinecraftForge's and must carry that veto back, so the entity is never added and an
 * untagged control entity is. The event-chain audit sees the same thing from the buses.
 */
@Mod("forbricchainprobe")
public final class ChainProbe {
    static final String TAG = "forbric_m41_veto";
    static volatile int forgeVetoes;

    public ChainProbe(IEventBus bus) {
        net.minecraftforge.event.entity.EntityJoinLevelEvent.BUS.addListener(
                (Predicate<net.minecraftforge.event.entity.EntityJoinLevelEvent>) event -> {
                    if (!event.getEntity().entityTags().contains(TAG)) return false;
                    forgeVetoes++;
                    return true;
                });
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> run(event.getServer()));
        System.out.println("[M41Chain] REGISTERED a MinecraftForge EntityJoinLevelEvent veto");
    }

    private static void run(MinecraftServer server) {
        String output = System.getProperty("forbric.chainProbe.output");
        try {
            ServerLevel level = server.overworld();
            ArmorStand vetoed = new ArmorStand(level, 0.5, 200, 0.5);
            vetoed.addTag(TAG);
            boolean vetoedAdded = level.addFreshEntity(vetoed);
            boolean vetoedPresent = level.getEntity(vetoed.getUUID()) != null;
            ArmorStand control = new ArmorStand(level, 2.5, 200, 0.5);
            boolean controlAdded = level.addFreshEntity(control);
            boolean controlPresent = level.getEntity(control.getUUID()) != null;
            if (controlPresent) control.discard();
            if (vetoedPresent) vetoed.discard();
            String json = "{\"forgeVetoes\": " + forgeVetoes + ", \"vetoedAdded\": " + vetoedAdded + ", \"vetoedPresent\": " + vetoedPresent
                    + ", \"controlAdded\": " + controlAdded + ", \"controlPresent\": " + controlPresent + "}\n";
            if (output != null) { Path path = Path.of(output); Files.createDirectories(path.toAbsolutePath().getParent()); Files.writeString(path, json); }
            System.out.println("[M41Chain] RESULT " + json.trim());
        } catch (Exception failed) {
            System.out.println("[M41Chain] FAILED " + failed);
        } finally {
            server.halt(false);
        }
    }
}
