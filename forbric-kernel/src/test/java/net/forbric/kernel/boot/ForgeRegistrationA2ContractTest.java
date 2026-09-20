package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Contracts for actual canary bytecode and production gate assertions; no game is started. */
class ForgeRegistrationA2ContractTest {
    private static final Path CANARY = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"))
            .resolve("run/forge-runtime/forbriclive.jar");
    private static final Path M26 = Path.of("run/gate-m26-forgeclient.sh");
    private static final Path M21 = Path.of("run/gate-m21-forgesetup.sh");
    private static final List<String> CLIENT_EVENTS = List.of(
            "RegisterKeyMappingsEvent", "EntityRenderersEvent$RegisterRenderers",
            "EntityRenderersEvent$RegisterLayerDefinitions", "RegisterParticleProvidersEvent",
            "RegisterColorHandlersEvent$Block", "RegisterClientReloadListenersEvent",
            "RegisterClientTooltipComponentFactoriesEvent", "ModelEvent$RegisterGeometryLoaders",
            "RegisterPresetEditorsEvent");
    private static final String CLIENT = "[ForbricLive/CLIENT] ";
    private static final String COMMON = "[ForbricLive/REGISTRATION] ";
    @TempDir Path temporary;

    @Test
    void realClientBusesRegisterContentAndRealConsumersAreRead() throws Exception {
        try (ZipFile zip = new ZipFile(CANARY.toFile())) {
            ClassNode node = read(zip, "forbric/live/ForbricLiveClient.class");
            MethodNode init = method(node, "init");
            for (String event : CLIENT_EVENTS) {
                assertEquals(1, subscriptions(init, "net/minecraftforge/client/event/" + event), event);
            }
            assertEquals(1, subscriptions(init, "net/minecraftforge/event/BuildCreativeModeTabContentsEvent"));
            assertCall(node, "net/minecraftforge/client/event/RegisterKeyMappingsEvent", "register");
            assertCall(node, "net/minecraftforge/client/event/EntityRenderersEvent$RegisterLayerDefinitions", "registerLayerDefinition");
            assertCall(node, "net/minecraftforge/client/event/RegisterColorHandlersEvent$Block", "register");
            assertCall(node, "net/minecraftforge/client/event/RegisterClientReloadListenersEvent", "registerReloadListener");
            assertCall(node, "net/minecraftforge/client/event/RegisterClientTooltipComponentFactoriesEvent", "register");
            assertCall(node, "net/minecraftforge/client/event/ModelEvent$RegisterGeometryLoaders", "register");
            assertCall(node, "net/minecraft/client/model/geom/EntityModelSet", "bakeLayer");
            assertCall(node, "net/minecraft/client/color/block/BlockColors", "getTintSources");
            assertCall(node, "net/minecraft/client/KeyMapping", "saveString");
            assertCall(node, "net/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent", "create");
            assertCall(node, "net/minecraftforge/client/model/geometry/GeometryLoaderManager", "get");
            assertCall(node, "java/util/concurrent/atomic/AtomicInteger", "incrementAndGet");
            assertCall(node, "java/util/concurrent/atomic/AtomicInteger", "get");
            boolean optionsArrayRead = false;
            boolean defaultF7 = false;
            boolean tick100 = false;
            for (MethodNode m : node.methods) for (var i : m.instructions) {
                if (i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
                        && f.owner.equals("net/minecraft/client/Options") && f.name.equals("keyMappings")) optionsArrayRead = true;
                if (i instanceof IntInsnNode integer && integer.operand == 296) defaultF7 = true;
                if (i instanceof IntInsnNode integer && integer.operand == 100) tick100 = true;
                if (i instanceof MethodInsnNode call && call.owner.equals("net/minecraft/client/KeyMapping")) {
                    assertFalse(call.name.equals("setKey") || call.name.equals("setKeyModifierAndCode"),
                            "the probe must not repair its own F6 binding");
                }
            }
            assertTrue(optionsArrayRead, "read the live Options array, not the event's request list");
            assertTrue(defaultF7, "F7 must differ from persisted F6, otherwise the reload test is vacuous");
            assertTrue(tick100, "observe after 100 live world ticks");
        }
    }

    @Test
    void commonProbeUsesTheGameTablesAndNoCanaryPostsItsOwnRegistrationEvents() throws Exception {
        try (ZipFile zip = new ZipFile(CANARY.toFile())) {
            ClassNode common = read(zip, "forbric/live/ForbricLiveMod.class");
            MethodNode register = method(common, "registerRegistrationProbes");
            assertEquals(1, subscriptions(register, "net/minecraftforge/event/entity/SpawnPlacementRegisterEvent"));
            assertEquals(1, subscriptions(register, "net/minecraftforge/event/BuildCreativeModeTabContentsEvent"));
            assertCall(common, "net/minecraftforge/event/entity/SpawnPlacementRegisterEvent", "register");
            assertCall(common, "net/minecraftforge/event/BuildCreativeModeTabContentsEvent", "accept");
            assertCall(common, "net/minecraft/world/entity/SpawnPlacements", "getHeightmapType");
            assertCall(common, "net/minecraft/world/item/CreativeModeTab", "buildContents");
            assertCall(common, "net/minecraft/world/item/CreativeModeTab", "getDisplayItems");
            assertCall(common, "net/minecraft/world/item/CreativeModeTab", "getSearchTabDisplayItems");
            for (var entry : zip.stream().filter(e -> e.getName().startsWith("forbric/live/ForbricLive")
                    && e.getName().endsWith(".class")).toList()) {
                byte[] bytes = zip.getInputStream(entry).readAllBytes();
                if (entry.getName().startsWith("forbric/live/ForbricLiveMod")) {
                    String pool = new String(bytes, StandardCharsets.ISO_8859_1);
                    assertFalse(pool.contains("net/minecraftforge/client/"), entry.getName());
                    assertFalse(pool.contains("net/minecraft/client/"), entry.getName());
                }
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, 0);
                for (MethodNode m : node.methods) for (var i : m.instructions) {
                    if (!(i instanceof MethodInsnNode call)) continue;
                    assertFalse(call.name.equals("post") && call.owner.contains("eventbus"),
                            "a canary may subscribe but never deliver its own registration event");
                    assertFalse(List.of("initClientHooks", "onRegisterParticleProviders", "fireSpawnPlacementEvent",
                            "onCreativeModeTabBuildContents").contains(call.name), "the canary must not install its own bridge");
                    assertFalse(call.owner.equals("net/minecraftforge/client/model/geometry/GeometryLoaderManager")
                            && call.name.equals("init"), "geometry init belongs to the real game lifecycle");
                }
            }
        }
    }

    @Test
    void clientGateSeedsF6AndBothScriptsParse() throws Exception {
        String script = Files.readString(M26);
        String options = section(script, "M26_OPTIONS");
        Path result = temporary.resolve("options-command.log");
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", options);
        builder.environment().put("RUNDIR", temporary.toString());
        builder.environment().put("RUN_OLD", CANARY.toAbsolutePath().getParent().getParent().toString());
        assertEquals(0, execute(builder, result).exit());
        String seeded = Files.readString(temporary.resolve("options.txt"));
        assertTrue(seeded.matches("version:[1-9][0-9]*\\nonboardAccessibility:false\\nkey_key\\.forbriclive\\.probe:key\\.keyboard\\.f6\\n"), seeded);
        for (Path gate : List.of(M21, M26)) {
            Result parsed = execute(new ProcessBuilder("bash", "-n", gate.toString()), result);
            assertEquals(0, parsed.exit(), parsed.output());
        }
        assertTrue(script.contains("# WORLD_CONFIRM_BEGIN"));
        assertTrue(script.contains("# CLIENT_CANARY_STAGE_BEGIN"));
        assertTrue(script.contains("SRC_MODS=\"$RUNDIR/empty-mods\""));
    }

    @Test
    void allClientRegistrationResultsPassTogether() throws Exception {
        Result result = gate(M26, "M26_ASSERTIONS", clientGreen(), 0);
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("M26 FORGE-CLIENT GATE GREEN"));
    }

    @Test
    void eachMissingClientEventAndEachFalseConsumerIsExpectedRed() throws Exception {
        for (String event : CLIENT_EVENTS) {
            String missing = clientGreen().replace(CLIENT + event.replace('$', '.') + " RECEIVED\n", "");
            Result result = gate(M26, "M26_ASSERTIONS", missing, 0);
            assertEquals(2, result.exit(), event + ": " + result.output());
        }
        for (String row : List.of("BuildCreativeModeTabContentsEvent RECEIVED", "key in Options.keyMappings: true",
                "key saved binding: key.keyboard.f6", "layer forbriclive:probe baked: true", "stone tint sources: 1",
                "stone probe tint present: true", "reload posts=1", "reload listener applies=1",
                "tooltip factory consumed: true", "forbriclive:probe geometry loader present: true",
                "forge:obj geometry loader present: true")) {
            Result result = gate(M26, "M26_ASSERTIONS", clientGreen().replace(CLIENT + row + "\n", ""), 0);
            assertEquals(2, result.exit(), row + ": " + result.output());
        }
        assertEquals(2, gate(M26, "M26_ASSERTIONS", clientGreen().replace(
                "EntityRenderersEvent.RegisterLayerDefinitions RECEIVED",
                "EntityRenderersEventXRegisterLayerDefinitions RECEIVED"), 0).exit(),
                "the event name separator must be literal, not a regex wildcard");
        for (String replacement : List.of("key saved binding: key.keyboard.f7", "key saved binding: key.keyboard.f60")) {
            assertEquals(2, gate(M26, "M26_ASSERTIONS", clientGreen().replace(
                    "key saved binding: key.keyboard.f6", replacement), 0).exit());
        }
        for (String replacement : List.of("injection VISIBLE: false search=true", "injection VISIBLE: true search=false")) {
            assertEquals(2, gate(M26, "M26_ASSERTIONS", clientGreen().replace(
                    "injection VISIBLE: true search=true", replacement), 0).exit());
        }
        for (String pass : List.of("all 3 CLIENT_INIT bridge(s) installed", "all 2 REGISTRATION bridge(s) installed")) {
            assertEquals(2, gate(M26, "M26_ASSERTIONS", clientGreen().replace(pass, ""), 0).exit());
        }
    }

    @Test
    void clientControlFailuresEmptyLogsAndDuplicateReloadsAreOrdinaryRed() throws Exception {
        for (String log : List.of("", clientGreen().replace("registration observations completed at world tick 100", "lost probe"),
                clientGreen().replace("ClientSmoke] joined world via quick-play", "world failed"),
                clientGreen().replace("reload posts=1", "reload posts=2"),
                clientGreen().replace("reload posts=1", "reload posts=10"),
                clientGreen() + "NoClassDefFoundError: missing.game.Type\n")) {
            Result result = gate(M26, "M26_ASSERTIONS", log, 0);
            assertEquals(1, result.exit(), result.output());
            assertFalse(result.output().contains("EXPECTED-RED"), result.output());
        }
    }

    @Test
    void commonRegistrationRequiresBothEventsAndBothMaterializedResults() throws Exception {
        Result green = gate(M21, "M21_REGISTRATION_ASSERTIONS", commonGreen(), 0);
        assertEquals(0, green.exit(), green.output());
        for (String row : List.of("SpawnPlacementRegisterEvent RECEIVED", "zombie heightmap=WORLD_SURFACE",
                "BuildCreativeModeTabContentsEvent RECEIVED: minecraft:building_blocks",
                "injection VISIBLE: true search=true phase=load complete")) {
            Result result = gate(M21, "M21_REGISTRATION_ASSERTIONS", commonGreen().replace(COMMON + row + "\n", ""), 0);
            assertEquals(2, result.exit(), row + ": " + result.output());
        }
        for (String replacement : List.of("injection VISIBLE: false search=true", "injection VISIBLE: true search=false")) {
            assertEquals(2, gate(M21, "M21_REGISTRATION_ASSERTIONS", commonGreen().replace(
                    "injection VISIBLE: true search=true", replacement), 0).exit());
        }
        assertEquals(1, gate(M21, "M21_REGISTRATION_ASSERTIONS", commonGreen(), 1).exit(),
                "a preceding setup regression must not be reclassified EXPECTED-RED");
        assertEquals(1, gate(M21, "M21_REGISTRATION_ASSERTIONS", "", 0).exit());
        assertEquals(1, gate(M21, "M21_REGISTRATION_ASSERTIONS", commonGreen().replace(
                "common registration observations completed", "probe aborted"), 0).exit());
    }

    private Result gate(Path gate, String fragment, String log, int precedingFailure) throws Exception {
        Path input = temporary.resolve("input.log");
        Files.writeString(input, log);
        String program = ". \"$1\"\nLOG=\"$2\"\nFAIL=" + precedingFailure + "\n"
                + section(Files.readString(gate), fragment);
        return execute(new ProcessBuilder("bash", "-c", program, "a2-contract",
                Path.of("run/lib.sh").toAbsolutePath().toString(), input.toString()), temporary.resolve("result.log"));
    }

    private static String clientGreen() {
        List<String> lines = new ArrayList<>(List.of(
                CLIENT + "subscribed to ten Forge registration events",
                "[ClientSmoke] joined world via quick-play", "[ClientSmoke] client-ready after 60 ticks",
                CLIENT + "creative contents builder exercised in a live world",
                CLIENT + "registration observations completed at world tick 100", "[ClientSmoke] clean disconnect observed"));
        for (String event : CLIENT_EVENTS) lines.add(CLIENT + event.replace('$', '.') + " RECEIVED");
        for (String result : List.of("BuildCreativeModeTabContentsEvent RECEIVED", "key in Options.keyMappings: true",
                "key saved binding: key.keyboard.f6", "layer forbriclive:probe baked: true", "stone tint sources: 1",
                "stone probe tint present: true", "reload posts=1", "reload listener applies=1",
                "tooltip factory consumed: true", "forbriclive:probe geometry loader present: true",
                "forge:obj geometry loader present: true")) lines.add(CLIENT + result);
        lines.add(COMMON + "injection VISIBLE: true search=true phase=client tick 100");
        lines.add("[EventMux] all 3 CLIENT_INIT bridge(s) installed");
        lines.add("[EventMux] all 2 REGISTRATION bridge(s) installed");
        return String.join("\n", lines) + "\n";
    }

    private static String commonGreen() {
        return COMMON + "subscribed to Forge spawn and creative registration events\n"
                + COMMON + "common registration observations completed\n"
                + COMMON + "SpawnPlacementRegisterEvent RECEIVED\n"
                + COMMON + "zombie heightmap=WORLD_SURFACE\n"
                + COMMON + "BuildCreativeModeTabContentsEvent RECEIVED: minecraft:building_blocks\n"
                + COMMON + "injection VISIBLE: true search=true phase=load complete\n"
                + "[EventMux] all 2 REGISTRATION bridge(s) installed\n";
    }

    private static int subscriptions(MethodNode method, String owner) {
        int result = 0;
        for (var instruction : method.instructions) {
            boolean globalBus = instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals(owner) && field.name.equals("BUS");
            boolean modBus = instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(owner) && call.name.equals("getBus");
            if (!globalBus && !modBus) continue;
            var listener = instruction.getNext();
            while (listener != null && listener.getOpcode() < 0) listener = listener.getNext();
            assertTrue(listener instanceof InvokeDynamicInsnNode, "BUS must create a real consumer: " + owner);
            var call = listener.getNext();
            while (call != null && call.getOpcode() < 0) call = call.getNext();
            assertTrue(call instanceof MethodInsnNode invoke && invoke.name.equals("addListener")
                    && invoke.owner.equals("net/minecraftforge/eventbus/api/bus/EventBus"), "subscribe: " + owner);
            result++;
        }
        return result;
    }

    private static void assertCall(ClassNode node, String owner, String name) {
        assertTrue(node.methods.stream().anyMatch(method -> {
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) return true;
            return false;
        }), "missing real consumer/registration: " + owner + "." + name);
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static ClassNode read(ZipFile zip, String name) throws Exception {
        var entry = zip.getEntry(name);
        assertNotNull(entry, "rebuild testmods; missing " + name);
        ClassNode node = new ClassNode();
        new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }

    private static String section(String script, String name) {
        int start = script.indexOf("# " + name + "_BEGIN");
        assertTrue(start >= 0, "missing production fragment: " + name);
        start = script.indexOf('\n', start) + 1;
        int end = script.indexOf("# " + name + "_END", start);
        assertTrue(end > start, "missing production fragment end: " + name);
        return script.substring(start, end);
    }

    private static Result execute(ProcessBuilder builder, Path output) throws Exception {
        Process process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean ended = process.waitFor(15, TimeUnit.SECONDS);
        if (!ended) process.destroyForcibly();
        assertTrue(ended, "canary log-only contract timed out");
        return new Result(process.exitValue(), Files.readString(output));
    }

    private record Result(int exit, String output) {}
}
