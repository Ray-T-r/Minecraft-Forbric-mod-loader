package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

@ResourceLock("system-properties")
class UntrackedFluidEyeQueryInjectorTest {
    private static final Path MERGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"), net.forbric.kernel.TestFixtures.BASE_JAR);
    @AfterEach void reset() { System.clearProperty(UntrackedFluidEyeQueryInjector.PROPERTY); }
    @Test void eyeQueriesUseTheInteractionAndKeepNativeQueriesBehindTheUntrackedGuard() throws Exception {
        var repair = new UntrackedFluidEyeQueryInjector();
        for (String name : new String[] {UntrackedFluidEyeQueryInjector.ENTITY, UntrackedFluidEyeQueryInjector.INTERACTION}) {
            byte[] original = NativeCoremodParityTest.read(MERGED, name.replace('.', '/'));
            byte[] bytes = repair.transform(name, original, null); assertNotSame(original, bytes);
            ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
            MethodNode method = node.methods.stream().filter(m -> m.name.equals("isEyeInFluid") && m.desc.equals(UntrackedFluidEyeQueryInjector.DESC)).findFirst().orElseThrow();
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
            assertSame(bytes, repair.transform(name, bytes, null));
            if (name.equals(UntrackedFluidEyeQueryInjector.INTERACTION)) {
                assertTrue(java.util.Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.name.equals("containsKey")));
                assertTrue(java.util.Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.name.equals("getFluidTypeByTag")), "registered trackers keep their native path");
                MethodNode tracks = tracksTag(node);
                assertTrue(calls(tracks, "getTracker"), "on the merged base a tracked tag is MinecraftForge's per-tag tracker, as the guard asked inline before");
                new Analyzer<>(new BasicVerifier()).analyze(node.name, tracks);
            } else {
                assertTrue(calls(method, UntrackedFluidEyeQueryInjector.TRACKS_TAG), "the entity asks its interaction whether the tag is tracked");
                assertFalse(calls(method, "getTracker"), "and names no member of the interaction's MinecraftForge half itself");
            }
            System.setProperty(UntrackedFluidEyeQueryInjector.PROPERTY, "off");
            assertSame(original, repair.transform(name, original, null)); System.clearProperty(UntrackedFluidEyeQueryInjector.PROPERTY);
        }
    }

    /**
     * The genuine NeoForge base: both guards land, the interaction's tracked-tag answer is false (NeoForge keeps trackers
     * per fluid type and has no getTracker), and nothing either edit added is a reference the base lacks. Measured
     * before this: the Entity guard called EntityFluidInteraction.getTracker(TagKey), declared only by the merged base's
     * MinecraftForge half — a NoSuchMethodError on the first untracked query, a hit on the anchor ledger.
     */
    @Test void onTheGenuineBaseBothGuardsLandAndLink() throws Exception {
        List<Path> genuine = ChainReferences.genuineJars();
        var repair = new UntrackedFluidEyeQueryInjector();
        Map<String, byte[]> before = new HashMap<>(), after = new HashMap<>();
        for (String name : new String[] {UntrackedFluidEyeQueryInjector.ENTITY, UntrackedFluidEyeQueryInjector.INTERACTION}) {
            String internal = name.replace('.', '/');
            before.put(internal, ChainReferences.read(genuine, internal));
            after.put(internal, repair.transform(name, before.get(internal), null));
            assertNotSame(before.get(internal), after.get(internal), name + " is guarded on the genuine base too");
        }
        ClassNode interaction = node(after.get(UntrackedFluidEyeQueryInjector.OWNER));
        assertTrue(interaction.methods.stream().noneMatch(m -> m.name.equals("getTracker")), "premise: NeoForge's interaction has no getTracker");
        List<AbstractInsnNode> body = Arrays.stream(tracksTag(interaction).instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
        assertEquals(List.of(Opcodes.ICONST_0, Opcodes.IRETURN), body.stream().map(AbstractInsnNode::getOpcode).toList(),
                "no tag is tracked by NeoForge's interaction, so only the kernel's types go on to NeoForge's lookup");
        ChainReferences.assertNoneDangling(before, after, genuine);

        // The control: the old inline query, against the same base, is the dangling reference this replaced.
        String entity = "net/minecraft/world/entity/Entity";
        List<String> old = ChainReferences.dangling(Map.of(entity, before.get(entity)), Map.of(entity, withInlineGetTracker(after.get(entity))), genuine);
        assertEquals(1, old.size(), old.toString());
        assertTrue(old.getFirst().contains("MEMBER_ABSENT M " + UntrackedFluidEyeQueryInjector.OWNER + " getTracker"), old.toString());
    }

    /** {@code bytes} with the guard's tracked-tag call spelled as it was before: the interaction's getTracker, inline. */
    private static byte[] withInlineGetTracker(byte[] bytes) {
        ClassNode node = node(bytes);
        for (MethodNode m : node.methods) for (AbstractInsnNode i : m.instructions.toArray()) {
            if (i instanceof MethodInsnNode c && c.name.equals(UntrackedFluidEyeQueryInjector.TRACKS_TAG)) {
                m.instructions.set(c, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, c.owner, "getTracker", UntrackedFluidEyeQueryInjector.GET_TRACKER_DESC, false));
            }
        }
        org.objectweb.asm.ClassWriter w = new org.objectweb.asm.ClassWriter(0); node.accept(w); return w.toByteArray();
    }

    private static MethodNode tracksTag(ClassNode node) {
        return node.methods.stream().filter(m -> m.name.equals(UntrackedFluidEyeQueryInjector.TRACKS_TAG) && m.desc.equals(UntrackedFluidEyeQueryInjector.DESC))
                .findFirst().orElseThrow(() -> new AssertionError("no " + UntrackedFluidEyeQueryInjector.TRACKS_TAG));
    }

    private static boolean calls(MethodNode method, String name) {
        return Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.name.equals(name));
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
    }
}
