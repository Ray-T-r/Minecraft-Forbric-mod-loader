package net.forbric.kernel.boot;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
    @TempDir Path temporary;

    @Test
    void realClientBusesRegisterContentAndRealConsumersAreRead() throws Exception {
        TestFixtures.requireFiles(Fixture.STAGED, "staged canary", CANARY);
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
        TestFixtures.requireFiles(Fixture.STAGED, "staged canary", CANARY);
        try (ZipFile zip = new ZipFile(CANARY.toFile())) {
            ClassNode common = read(zip, "forbric/live/ForbricLiveMod.class");
            MethodNode register = method(common, "registerRegistrationProbes");
            assertEquals(1, subscriptions(register, "net/minecraftforge/event/entity/SpawnPlacementRegisterEvent"));
            assertEquals(1, subscriptions(register, "net/minecraftforge/event/BuildCreativeModeTabContentsEvent"));
            assertCall(common, "net/minecraftforge/event/entity/SpawnPlacementRegisterEvent", "register");
            assertCall(common, "net/minecraftforge/event/BuildCreativeModeTabContentsEvent", "accept");
            assertCall(common, "net/minecraft/world/entity/SpawnPlacements", "getHeightmapType");
            ClassNode gameEvents = read(zip, "forbric/live/ForbricLiveMod$GameEvents.class");
            MethodNode started = method(gameEvents, "onServerStarted");
            assertTrue(Arrays.stream(started.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/world/item/CreativeModeTab") && call.name.equals("buildContents")),
                    "creative stacks can only be observed after the game has bound their components");
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
    void clientGateSeedsTheNeoForgeF9AndBothScriptsParse() throws Exception {
        // The options section reads the world version out of the merged base, exactly as the gate does.
        TestFixtures.requireFiles(Fixture.STAGED, "staged merged base", System.getenv("MERGED") != null ? Path.of(System.getenv("MERGED"))
                : CANARY.toAbsolutePath().getParent().getParent().resolve(TestFixtures.BASE_JAR));
        String script = Files.readString(M26);
        String options = section(script, "M26_OPTIONS");
        Path result = temporary.resolve("options-command.log");
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", options);
        builder.environment().put("RUNDIR", temporary.toString());
        builder.environment().put("RUN_OLD", CANARY.toAbsolutePath().getParent().getParent().toString());
        assertEquals(0, execute(builder, result).exit());
        String seeded = Files.readString(temporary.resolve("options.txt"));
        assertTrue(seeded.matches("version:[1-9][0-9]*\\nonboardAccessibility:false\\nkey_key\\.forbricneolive\\.probe:key\\.keyboard\\.f9\\n"), seeded);
        for (Path gate : List.of(M21, M26)) {
            Result parsed = execute(new ProcessBuilder("bash", "-n", gate.toString()), result);
            assertEquals(0, parsed.exit(), parsed.output());
        }
        assertTrue(script.contains("# WORLD_CONFIRM_BEGIN"));
        assertTrue(script.contains("# CLIENT_CANARY_STAGE_BEGIN"));
        assertTrue(script.contains("SRC_MODS=\"$RUNDIR/empty-mods\""));
    }

    // Stage 3 of dropping MinecraftForge took the MinecraftForge canary out of gate-m26 and gate-m21: Forbric refuses it
    // now, so its client and common registration checks (the M26_ASSERTIONS block and the canary rows of
    // M21_REGISTRATION_ASSERTIONS) went, and the tests that ran those blocks against synthetic logs went with them; the
    // session block below, which outlives the canary, has its own.
    // The canary's own bytecode contracts above stay until Stage 4 deletes the canary. The NeoForge and Fabric blocks of
    // both gates are GateNeoCanaryContractTest's.

    private static final List<String> SESSION_GREEN = List.of(
            "[ClientSmoke] joined world via quick-play", "[ClientSmoke] client-ready after 60 ticks",
            "[ClientSmoke] clean disconnect observed",
            "[Forbric/ClientResources] client resource manager preloaded with 3 selected pack(s)",
            "[Forbric/Bridges] all 3 CLIENT_INIT bridge(s) installed", "[Forbric/Bridges] all 2 REGISTRATION bridge(s) installed",
            "[Forbric/HudBridge] MinecraftForge's overlay stack is on NeoForge's layer manager — silenced 23 vanilla leaf/leaves",
            "[Forbric/MergedBaseCompat] gave GuiRenderer's pooled picture-in-picture lookup a fallback to the orphaned vanilla "
                    + "map, and gave that map its only writer",
            "[ForbricNeoLive/FLUID] water at 3 64 -2: Block{minecraft:water}",
            "[Forbric/Fluids] MinecraftForge client extensions consulted for 4 fluid(s) so far");

    /**
     * The session block keeps every kernel line that prints with no MinecraftForge mod aboard — the HUD drop, the overlay
     * stack, the picture-in-picture writer and the fluid funnel are rewrites of vanilla and NeoForge code that guard
     * NeoForge, Fabric and vanilla drawing — and each row is load-bearing.
     */
    @Test
    void theSessionBlockAssertsTheRewritesThatRunWithNoMinecraftForgeMod() throws Exception {
        String green = String.join("\n", SESSION_GREEN) + "\n";
        assertEquals(0, gate(M26, "M26_SESSION_ASSERTIONS", green, 0, "HUD_TREE=absent").exit());
        for (String line : SESSION_GREEN) {
            assertEquals(1, gate(M26, "M26_SESSION_ASSERTIONS", green.replace(line + "\n", ""), 0, "HUD_TREE=absent").exit(),
                    "a missing row is red: " + line);
        }
        assertEquals(1, gate(M26, "M26_SESSION_ASSERTIONS", "", 0, "HUD_TREE=absent").exit());
        assertEquals(1, gate(M26, "M26_SESSION_ASSERTIONS", green, 1, "HUD_TREE=absent").exit(),
                "a preceding failure stays red");
    }

    /**
     * The HUD drop row follows the base the gate read before its assertions: where the merged Hud kept MinecraftForge's
     * overlay tree the kernel's drop line is required, and where it did not the same line is red.
     */
    @Test
    void theHudDropRowFollowsTheBaseTheGateRead() throws Exception {
        String green = String.join("\n", SESSION_GREEN) + "\n";
        String dropped = "[Forbric/HudBridge] net.minecraft.client.gui.Hud kept MinecraftForge's own overlay tree (built in 1 "
                + "place(s), drawn instead of NeoForge's layer manager in 1) — dropped: the kernel builds that tree once "
                + "every mod is loaded and puts it on NeoForge's manager, the one HUD pipeline\n";
        assertEquals(0, gate(M26, "M26_SESSION_ASSERTIONS", green + dropped, 0, "HUD_TREE=kept").exit());
        assertEquals(1, gate(M26, "M26_SESSION_ASSERTIONS", green, 0, "HUD_TREE=kept").exit(),
                "a base that kept the tree and no drop: both pipelines still run");
        assertEquals(0, gate(M26, "M26_SESSION_ASSERTIONS", green, 0, "HUD_TREE=absent").exit());
        assertEquals(1, gate(M26, "M26_SESSION_ASSERTIONS", green + dropped, 0, "HUD_TREE=absent").exit(),
                "a drop on a base that never carried the tree is a drop of something else");
    }

    /** {@code preamble}: what the gate computes before the fragment, from the base rather than the log. */
    private Result gate(Path gate, String fragment, String log, int precedingFailure, String preamble) throws Exception {
        Path input = temporary.resolve("input.log");
        Files.writeString(input, log);
        String program = ". \"$1\"\nLOG=\"$2\"\nFAIL=" + precedingFailure + "\n" + preamble + "\n"
                + section(Files.readString(gate), fragment) + "\nexit \"$FAIL\"\n";
        return execute(new ProcessBuilder("bash", "-c", program, "a2-contract",
                Path.of("run/lib.sh").toAbsolutePath().toString(), input.toString()), temporary.resolve("result.log"));
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
