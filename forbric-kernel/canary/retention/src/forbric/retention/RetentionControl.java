package forbric.retention;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Read-only inspection of the exact upstream mod cache after an ordinary native server shutdown. */
@Mod("forbricretentioncontrol")
public final class RetentionControl {
    private static WeakReference<MinecraftServer> stopped = new WeakReference<>(null);
    private static String token;
    private static Path root;
    public RetentionControl() {
        token = System.getProperty("retention.token", "");
        root = Path.of(System.getProperty("retention.root", ".")).toAbsolutePath().normalize();
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> prepare(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> stopped = new WeakReference<>(event.getServer()));
        Runtime.getRuntime().addShutdownHook(new Thread(RetentionControl::verify, "retention-control-evidence"));
    }
    private static void prepare(MinecraftServer server) {
        try {
            if (token.isEmpty() || !Files.readString(root.resolve(".retention-owned")).strip().equals(token)
                    || !server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(root.resolve("world")))
                throw new IllegalStateException("not the runner-owned world");
            var level = server.overworld(); var pos = new BlockPos(0, 80, 0);
            level.getChunk(0, 0);
            if (!level.setBlockAndUpdate(pos, Blocks.CAMPFIRE.defaultBlockState())) throw new IllegalStateException("placement failed");
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity == null) throw new IllegalStateException("native campfire was not created");
            entity.saveWithFullMetadata(level.registryAccess());
            if (!campfires().contains(entity)) throw new IllegalStateException("actual upstream cache did not receive the saved campfire");
            Files.writeString(root.resolve("prepared.json"), "{\"token\":\"" + token + "\",\"savedCampfireTracked\":true}\n");
            System.out.println("[RetentionControl] actual saved campfire is in the unmodified upstream cache");
        } catch (Throwable failure) { failure.printStackTrace(); server.halt(false); }
    }
    private static Set<?> campfires() throws Exception {
        var field = Class.forName("de.cech12.unlitcampfire.CommonLoader").getDeclaredField("CAMPFIRES");
        field.setAccessible(true); return (Set<?>) field.get(null);
    }
    private static void verify() {
        try {
            MinecraftServer server = stopped.get(); int retained = 0;
            for (Object value : campfires()) if (value instanceof BlockEntity entity && entity.getLevel() != null
                    && entity.getLevel().getServer() == server && server != null) retained++;
            boolean stoppedNormally = server != null && server.isStopped();
            Files.writeString(root.resolve("retention.json"), "{\"token\":\"" + token + "\",\"stoppedNormally\":" + stoppedNormally
                    + ",\"cacheSize\":" + campfires().size() + ",\"campfiresReferencingStoppedServer\":" + retained
                    + ",\"root\":\"de.cech12.unlitcampfire.CommonLoader.CAMPFIRES\"}\n");
            System.out.println("[RetentionControl] stopped=" + stoppedNormally + " retainedCampfires=" + retained);
        } catch (Throwable failure) { failure.printStackTrace(); }
    }
}
