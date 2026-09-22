package nativecontrol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.storage.LevelResource;

/** JDK + Minecraft only: the identical compiled jar runs on its native loader and on Forbric. */
public final class Probe {
    private static String family;
    private static Supplier<Block> block;
    private static int initialized, started, commandRegistrations, ticks, actions;
    private Probe() { }

    public static Identifier id(String ecosystem) { return Identifier.fromNamespaceAndPath("nativecontrol" + ecosystem, "probe"); }
    public static Block block(String ecosystem) {
        return new Block(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id(ecosystem))).strength(1));
    }
    public static void initialize(String ecosystem, Supplier<Block> registeredBlock) {
        family = ecosystem; block = registeredBlock; initialized++;
        System.out.println("[NativeControl] INITIALIZED family=" + family + " count=" + initialized);
    }
    public static void started(MinecraftServer server) {
        started++;
        System.out.println("[NativeControl] SERVER_STARTED family=" + family + " count=" + started);
    }
    public static void tick(MinecraftServer server) {
        ticks++;
        if (ticks == 20) System.out.println("[NativeControl] READY family=" + family + " ticks=20");
    }
    public static void commands(CommandDispatcher<CommandSourceStack> dispatcher) {
        commandRegistrations++;
        dispatcher.register(Commands.literal("nativecontrol").executes(context -> run(context.getSource().getServer())));
    }
    private static int run(MinecraftServer server) {
        String token = System.getProperty("nativecontrol.token", "");
        Path root = Path.of(System.getProperty("nativecontrol.root", ".")).toAbsolutePath().normalize();
        Path output = root.resolve("probe.json");
        boolean pass = false; String detail = "", observed = ""; long seed = Long.MIN_VALUE;
        try {
            // A manually installed test jar cannot modify a player world: the runner must own this instance.
            require(!token.isBlank() && Files.readString(root.resolve(".native-control-owned")).trim().equals(token), "missing runner ownership");
            require(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(root.resolve("world")), "unexpected world path");
            require(server.isSameThread(), "command did not run on server thread");
            require(initialized == 1 && started == 1 && commandRegistrations == 1 && ticks >= 20, "lifecycle denominator mismatch");
            var level = server.overworld(); seed = level.getSeed();
            require(seed == 8035262L, "seed mismatch");
            BlockPos pos = new BlockPos(0, 80, 0); level.getChunk(0, 0);
            require(BuiltInRegistries.BLOCK.getKey(block.get()).equals(id(family)), "registered block id mismatch"); actions++;
            require(level.setBlockAndUpdate(pos, block.get().defaultBlockState()), "block placement failed"); actions++;
            observed = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
            require(observed.equals(id(family).toString()), "world block readback mismatch"); actions++;
            pass = true;
        } catch (Throwable failure) {
            detail = failure.toString(); failure.printStackTrace();
        }
        try {
            Files.writeString(output, "{\"schemaVersion\":1,\"family\":" + json(family) + ",\"token\":" + json(token)
                    + ",\"pass\":" + pass + ",\"initialized\":" + initialized + ",\"started\":" + started
                    + ",\"commandRegistrations\":" + commandRegistrations + ",\"ticks\":" + ticks
                    + ",\"actions\":" + actions + ",\"expectedActions\":3,\"seed\":" + seed
                    + ",\"observedBlock\":" + json(observed) + ",\"detail\":" + json(detail) + "}\n");
        } catch (Exception failure) { failure.printStackTrace(); pass = false; }
        System.out.println("[NativeControl] " + (pass ? "PASS" : "FAIL") + " family=" + family
                + " lifecycle=" + initialized + "/1," + started + "/1," + commandRegistrations + "/1 ticks=" + ticks + "/20 actions=" + actions + "/3 " + detail);
        return pass ? 1 : 0;
    }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
    private static String json(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
