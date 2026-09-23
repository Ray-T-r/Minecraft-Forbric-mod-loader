package forbric.behavior;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Live ServerLevel callers only: no manual event post, kernel helper invocation, or substituted game class. */
public final class WorldProbe {
    public static final Identifier MOB_ID = Identifier.fromNamespaceAndPath("forbricbehaviorprobe", "probe_mob");
    public static final SpawnGroupData NEO_DATA = new SpawnGroupData() { }, FORGE_DATA = new SpawnGroupData() { }, RETURNED_DATA = new SpawnGroupData() { };
    public static final List<ProbeMob> created = new ArrayList<>();
    public static String activeSpawner;
    public static PortalShape replacementShape;
    public static int portalReplacementCalls;
    private static String activePortal, phase, token;
    private static BlockPos activeFire;
    private static int portalNeo, portalForge, spawnerNeo, spawnerForge, inputKeys, markerValue;
    private static int itemNeo, itemForge, elapsed;
    private static FinalizeSpawnEvent neoFinalize;
    private static ProbeMob itemUser;
    private static FakePlayer player;
    private static MinecraftServer armed;
    private static Path root, output;
    private static boolean initialized, finished;
    private static final List<Map<String, Object>> results = new ArrayList<>();
    private WorldProbe() { }

    public static void installListeners() {
        NeoForge.EVENT_BUS.addListener(BlockEvent.PortalSpawnEvent.class, event -> {
            if (activePortal == null || !event.getPos().equals(activeFire)) return;
            portalNeo++;
            if (activePortal.equals("neo-veto")) event.setCanceled(true);
        });
        net.minecraftforge.event.level.BlockEvent.PortalSpawnEvent.BUS.addListener((java.util.function.Predicate<net.minecraftforge.event.level.BlockEvent.PortalSpawnEvent>) event -> {
            if (activePortal == null || !event.getPos().equals(activeFire)) return false;
            portalForge++; return activePortal.equals("forge-veto");
        });
        NeoForge.EVENT_BUS.addListener(FinalizeSpawnEvent.class, event -> {
            if (activeSpawner == null || !(event.getEntity() instanceof ProbeMob)) return;
            spawnerNeo++; neoFinalize = event; event.setSpawnData(NEO_DATA);
            if (activeSpawner.equals("neo-cancel-finalize")) event.setCanceled(true);
            if (activeSpawner.equals("neo-veto-spawn")) event.setSpawnCancelled(true);
        });
        net.minecraftforge.event.entity.living.MobSpawnEvent.FinalizeSpawn.BUS.addListener((java.util.function.Predicate<net.minecraftforge.event.entity.living.MobSpawnEvent.FinalizeSpawn>) event -> {
            if (activeSpawner == null || !(event.getEntity() instanceof ProbeMob)) return false;
            spawnerForge++;
            require(event.getSpawnData() == NEO_DATA, "Forge did not see Neo's data");
            require(event.getSpawnTag() != null, "Forge spawner ValueInput is null");
            require(event.getSpawnTag() == ((ProbeMob) event.getEntity()).loadedInput, "Forge did not receive the same ValueInput used by Entity.load");
            require(event.getSpawnTag().getStringOr("id", "").equals(MOB_ID.toString()), "Forge received the wrong entity ValueInput");
            inputKeys = event.getSpawnTag().keySet().size(); markerValue = event.getSpawnTag().getIntOr("m35_marker", -1);
            event.setSpawnData(FORGE_DATA);
            // A later family may not revive the Neo world-insertion veto.
            if (activeSpawner.equals("neo-veto-spawn")) event.setSpawnCancelled(false);
            if (activeSpawner.equals("forge-veto-spawn")) event.setSpawnCancelled(true);
            return activeSpawner.equals("forge-cancel-finalize");
        });
        NeoForge.EVENT_BUS.addListener(LivingEntityUseItemEvent.Finish.class, event -> {
            if (event.getEntity() != itemUser) return;
            itemNeo++; event.setResultStack(tagged(new ItemStack(Items.GOLD_INGOT, 1)));
        });
        net.minecraftforge.event.entity.living.LivingEntityUseItemEvent.Finish.BUS.addListener(event -> {
            if (event.getEntity() != itemUser) return;
            itemForge++;
            require(event.getResultStack().is(Items.GOLD_INGOT), "Forge item finish did not see Neo's result");
            event.setResultStack(tagged(new ItemStack(Items.DIAMOND, 2)));
        });
    }

    public static boolean replacePortalAt(BlockPos pos) { return "replacement".equals(activePortal) && pos.equals(activeFire); }

    public static void arm(MinecraftServer server) {
        try {
            root = Path.of(System.getProperty("forbric.behaviorRoot", ".")).toAbsolutePath().normalize();
            token = System.getProperty("forbric.behaviorToken", ""); phase = System.getProperty("forbric.behaviorPhase", "");
            if (token.isBlank() || !List.of("positive", "portal-off", "spawner-off", "item-off").contains(phase)
                    || !Files.readString(root.resolve(".m35-owned")).trim().equals(token)
                    || !server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(root.resolve("world"))) {
                System.out.println("[M35Behavior] DISARMED: gate ownership absent"); return;
            }
            output = Path.of(System.getProperty("forbric.behaviorOutput")); armed = server;
        } catch (Exception failure) { System.out.println("[M35Behavior] DISARMED: " + failure); }
    }

    public static void tick(MinecraftServer server) {
        if (server != armed || finished) return;
        if (!initialized) {
            initialized = true;
            try { initializeWorld(server); }
            catch (Throwable failure) { failure.printStackTrace(); result("driver-setup", false, Map.of("detail", failure.toString())); finish(server); }
            return;
        }
        elapsed++;
        if (itemUser == null || itemNeo > 0 || elapsed >= 160) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("neoEvents", itemNeo); evidence.put("forgeEvents", itemForge); evidence.put("serverTicks", elapsed);
            evidence.put("entityWorldTicks", itemUser == null ? -1 : itemUser.worldTicks);
            evidence.put("held", itemUser == null ? "missing" : itemUser.getMainHandItem().toString());
            boolean good = itemUser != null && itemUser.worldTicks >= 1 && itemNeo == 1 && itemForge == 1
                    && itemUser.getMainHandItem().getCount() == 2 && ItemStack.isSameItemSameComponents(tagged(new ItemStack(Items.DIAMOND)), itemUser.getMainHandItem());
            result("item-result", good, evidence); finish(server);
        }
    }

    private static void initializeWorld(MinecraftServer server) {
        ServerLevel level = server.overworld(); require(server.isSameThread(), "not server thread");
        for (int i = 0; i < 4; i++) portal(level, List.of("allow", "neo-veto", "forge-veto", "replacement").get(i), new BlockPos(i * 32 + 1, 80, 32));
        player = new FakePlayer(level, new GameProfile(UUID.fromString("3b38bc55-d92e-4b10-9510-33b132f70235"), "M35Probe"));
        player.snapTo(68, 80, 68); level.addNewPlayer(player);
        List<String> cases = List.of("data", "neo-cancel-finalize", "forge-cancel-finalize", "neo-veto-spawn", "forge-veto-spawn", "nonempty-input");
        for (int i = 0; i < cases.size(); i++) spawner(level, cases.get(i), new BlockPos(64 + i * 16, 80, 64));
        BlockPos itemPos = new BlockPos(176, 80, 64); level.getChunk(itemPos.getX() >> 4, itemPos.getZ() >> 4);
        level.setChunkForced(itemPos.getX() >> 4, itemPos.getZ() >> 4, true); player.snapTo(180, 80, 68);
        itemUser = BehaviorCanary.TYPE.get().create(level, EntitySpawnReason.COMMAND);
        require(itemUser != null, "item consumer not constructed"); itemUser.snapTo(176.5, 80, 64.5);
        require(level.addFreshEntity(itemUser), "item consumer was not inserted into the real world");
        itemUser.setItemInHand(InteractionHand.MAIN_HAND, tagged(new ItemStack(Items.MILK_BUCKET)));
        itemUser.startUsingItem(InteractionHand.MAIN_HAND);
        require(itemUser.isUsingItem(), "milk consumption did not start");
        // No completeUsingItem call or manual entity tick: the actual world advances it to the finish hook.
        System.out.println("[M35Behavior] ARMED natural item consumption in real forced world chunk");
    }

    private static void portal(ServerLevel level, String mode, BlockPos fire) {
        Map<String, Object> evidence = new LinkedHashMap<>(); boolean pass = false;
        activePortal = mode; activeFire = fire; portalNeo = portalForge = portalReplacementCalls = 0;
        BlockPos replacement = fire.offset(12, 0, 0);
        try {
            frame(level, fire); frame(level, replacement);
            require(PortalShape.findEmptyPortalShape(level, fire, Direction.Axis.X).isPresent(), "first frame is invalid");
            replacementShape = PortalShape.findEmptyPortalShape(level, replacement, Direction.Axis.X).orElseThrow();
            level.setBlockAndUpdate(fire, Blocks.FIRE.defaultBlockState());
            int first = portalBlocks(level, fire), second = portalBlocks(level, replacement);
            evidence.put("originalPortalBlocks", first); evidence.put("replacementPortalBlocks", second);
            evidence.put("neoEvents", portalNeo); evidence.put("forgeEvents", portalForge); evidence.put("replacementReturns", portalReplacementCalls);
            require(portalNeo == 1 && portalForge == (mode.equals("neo-veto") ? 0 : 1), "portal callback count differs");
            require(first == (mode.equals("allow") ? 6 : 0), "original frame result differs");
            require(second == (mode.equals("replacement") ? 6 : 0), "replacement frame result differs");
            if (mode.equals("replacement")) require(portalReplacementCalls == 1, "real Forge hook-return mixin did not run exactly once");
            pass = true;
        } catch (Throwable failure) { evidence.put("detail", failure.toString()); failure.printStackTrace(); }
        finally { result("portal-" + mode, pass, evidence); activePortal = null; replacementShape = null; }
    }

    private static void frame(ServerLevel level, BlockPos inside) {
        for (int x = -1; x <= 2; x++) for (int y = -1; y <= 3; y++) {
            BlockPos pos = inside.offset(x, y, 0); level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            level.setBlockAndUpdate(pos, (x == -1 || x == 2 || y == -1 || y == 3) ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
    }
    private static int portalBlocks(ServerLevel level, BlockPos inside) {
        int count = 0;
        for (int x = 0; x < 2; x++) for (int y = 0; y < 3; y++) if (level.getBlockState(inside.offset(x, y, 0)).is(Blocks.NETHER_PORTAL)) count++;
        return count;
    }

    private static void spawner(ServerLevel level, String mode, BlockPos pos) {
        Map<String, Object> evidence = new LinkedHashMap<>(); boolean pass = false;
        activeSpawner = mode; created.clear(); spawnerNeo = spawnerForge = inputKeys = 0; markerValue = -1; neoFinalize = null;
        try {
            level.getChunk(pos.getX() >> 4, pos.getZ() >> 4); player.snapTo(pos.getX() + 6, 80, 68);
            for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) level.setBlockAndUpdate(pos.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(pos, Blocks.SPAWNER.defaultBlockState());
            SpawnerBlockEntity be = (SpawnerBlockEntity) level.getBlockEntity(pos); require(be != null, "real spawner entity absent");
            CompoundTag entity = new CompoundTag(); entity.putString("id", MOB_ID.toString());
            if (mode.equals("nonempty-input")) entity.putInt("m35_marker", 35);
            var data = new SpawnData(entity, Optional.empty(), Optional.empty());
            var fields = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            fields.putShort("Delay", (short) 0); fields.putInt("SpawnCount", 1); fields.putInt("SpawnRange", 4);
            fields.putInt("MaxNearbyEntities", 10); fields.putInt("RequiredPlayerRange", 16); fields.store("SpawnData", SpawnData.CODEC, data);
            // Only collision/random placement may cause retries. Once the event occurs, another spawn is forbidden.
            int attempts = 0;
            while (spawnerNeo == 0 && attempts++ < 32) {
                be.getSpawner().load(level, pos, TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), fields.buildResult()));
                SpawnerBlockEntity.serverTick(level, pos, level.getBlockState(pos), be);
            }
            require(created.size() == 1, "expected exactly one real candidate mob, got " + created.size());
            ProbeMob mob = created.getFirst();
            // The public entity lookup only exposes entities in currently accessible sections. All these
            // cases run in one post-tick, before moving the fake player updates section visibility. Native
            // ServerLevel.addEntity calls onAddedToLevel only after the real section manager accepts it.
            boolean added = mob.isAddedToLevel();
            evidence.put("attempts", attempts); evidence.put("neoEvents", spawnerNeo); evidence.put("forgeEvents", spawnerForge);
            evidence.put("finalizations", mob.finalizations); evidence.put("worldInsertion", added); evidence.put("spawnVeto", mob.isSpawnCancelled());
            evidence.put("publicLookupVisible", level.getEntity(mob.getUUID()) == mob);
            evidence.put("valueInputKeys", inputKeys); evidence.put("valueInputMarker", markerValue); evidence.put("forgeDataUsed", mob.finalizedWith == FORGE_DATA);
            int expectedForge = mode.equals("neo-cancel-finalize") ? 0 : 1;
            int expectedFinal = mode.contains("cancel-finalize") || mode.equals("nonempty-input") ? 0 : 1;
            require(spawnerNeo == 1 && spawnerForge == expectedForge, "spawner callback count differs");
            require(mob.finalizations == expectedFinal, "finalization count differs");
            require(added == !mode.contains("veto-spawn"), "event cancellation and world-insertion veto were conflated");
            if (expectedFinal == 1) require(mob.finalizedWith == FORGE_DATA, "Forge replacement SpawnGroupData did not reach finalizer");
            if (expectedForge == 1) {
                require(inputKeys >= 1, "actual nonempty entity ValueInput was not observed");
                require(neoFinalize.getSpawnData() == (mode.equals("forge-cancel-finalize") ? NEO_DATA : FORGE_DATA),
                        "native discarded finalizer return was written back, or cancelled event data was incorrectly copied");
            }
            if (mode.equals("nonempty-input")) require(markerValue == 35, "custom ValueInput field did not survive");
            pass = true;
        } catch (Throwable failure) { evidence.put("detail", failure.toString()); failure.printStackTrace(); }
        finally {
            for (ProbeMob mob : created) mob.discard(); level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            result("spawner-" + mode, pass, evidence); activeSpawner = null;
        }
    }

    private static void finish(MinecraftServer server) {
        finished = true;
        try {
            if (itemUser != null) itemUser.discard();
            if (player != null) server.overworld().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            server.overworld().setChunkForced(11, 4, false);
            boolean pass = results.size() == 11 && results.stream().allMatch(row -> Boolean.TRUE.equals(row.get("pass")));
            Map<String, Object> report = new LinkedHashMap<>(); report.put("schemaVersion", 1); report.put("phase", phase); report.put("token", token);
            report.put("pass", pass); report.put("expectedCases", 11); report.put("cases", results);
            Files.createDirectories(output.toAbsolutePath().getParent()); Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
            System.out.println("[M35Behavior] " + (pass ? "PASS" : "FAIL") + " phase=" + phase + " cases=" + results.size() + "/11");
        } catch (Throwable failure) { System.out.println("[M35Behavior] FAIL result-write " + failure); failure.printStackTrace(); }
        finally { server.halt(false); }
    }
    private static ItemStack tagged(ItemStack stack) { CompoundTag tag = new CompoundTag(); tag.putInt("m35_item", 35); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); return stack; }
    private static void result(String name, boolean pass, Map<String, Object> evidence) {
        Map<String, Object> row = new LinkedHashMap<>(); row.put("name", name); row.put("pass", pass); row.putAll(evidence); results.add(row);
        System.out.println("[M35Behavior] " + (pass ? "PASS" : "FAIL") + " case=" + name + " " + evidence);
    }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
}
