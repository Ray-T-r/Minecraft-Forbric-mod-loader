package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import net.forbric.api.Ecosystem;

@ResourceLock("system-properties")
class ForgeClientConsumersInjectorTest {
    private static final Path STAGED = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run");
    private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
    private static final Path FORGE = STAGED.resolve("forge-runtime/forge-runtime.jar");
    private static final Path NEO = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
    private static final List<String> SWITCHES = List.of("forbric.forgeClientConsumers", "forbric.forgeClientInit");
    private final ForgeClientConsumersInjector injector = new ForgeClientConsumersInjector();

    @Test
    void fourRealMergedConsumersRedirectWithoutChangingDescriptorsOrInstructionCounts() throws Exception {
        for (var rule : ForgeClientConsumersInjector.RULES) {
            byte[] original = bytes(MERGED, rule.host().replace('.', '/') + ".class");
            ClassNode before = node(original);
            MethodNode source = target(before, rule);
            assertEquals(1, refs(source, rule.owner(Ecosystem.NEOFORGE), rule.call(), rule.descriptor(), rule.handle()), rule.host());
            assertEquals(0, refs(source, rule.owner(Ecosystem.FORGE), rule.call(), rule.descriptor(), rule.handle()));
            byte[] changed = injector.transform(rule.host(), original, null);
            assertNotSame(original, changed, rule.host());
            ClassNode after = node(changed);
            assertEquals(opcodeCounts(before), opcodeCounts(after), "a redirect must not insert or delete game instructions");
            assertEquals(0, refs(target(after, rule), rule.owner(Ecosystem.NEOFORGE), rule.call(), rule.descriptor(), rule.handle()));
            assertEquals(1, refs(target(after, rule), ForgeClientConsumersInjector.RUNTIME, rule.runtimeCall(), rule.descriptor(), rule.handle()));
            assertEquals(source.maxStack, target(after, rule).maxStack);
            assertEquals(source.maxLocals, target(after, rule).maxLocals);
            new ClassReader(changed).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
            assertSame(changed, injector.transform(rule.host(), changed, null), "idempotent: " + rule.host());
        }
    }

    @Test
    void presetChangesTheBootstrapArgumentAndPreservesTheRestOfTheLambda() throws Exception {
        var rule = ForgeClientConsumersInjector.RULES.stream().filter(ForgeClientConsumersInjector.Rule::handle).findFirst().orElseThrow();
        byte[] original = bytes(MERGED, rule.host().replace('.', '/') + ".class");
        MethodNode before = target(node(original), rule);
        MethodNode after = target(node(injector.transform(rule.host(), original, null)), rule);
        assertEquals(0, refs(before, rule.owner(Ecosystem.NEOFORGE), rule.call(), rule.descriptor(), false),
                "this carrier uses a method reference, not an invokestatic at this site");
        List<InvokeDynamicInsnNode> a = dynamics(before), b = dynamics(after);
        assertEquals(a.size(), b.size());
        int replacements = 0;
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).name, b.get(i).name);
            assertEquals(a.get(i).desc, b.get(i).desc);
            assertEquals(a.get(i).bsm, b.get(i).bsm);
            for (int j = 0; j < a.get(i).bsmArgs.length; j++) {
                Object old = a.get(i).bsmArgs[j], now = b.get(i).bsmArgs[j];
                if (old instanceof Handle handle && handle.getOwner().equals(rule.owner(Ecosystem.NEOFORGE))) {
                    Handle replacement = assertInstanceOf(Handle.class, now);
                    assertEquals(handle.getTag(), replacement.getTag());
                    assertEquals(handle.getDesc(), replacement.getDesc());
                    assertEquals(handle.isInterface(), replacement.isInterface());
                    assertEquals(ForgeClientConsumersInjector.RUNTIME, replacement.getOwner());
                    assertEquals("getPresetEditor", replacement.getName());
                    replacements++;
                } else assertEquals(old, now, "non-target bootstrap argument must survive");
            }
        }
        assertEquals(1, replacements);
    }

    @Test
    void eitherNegativeControlReturnsTheOriginalBytesAndDeclaresNoRequiredAnchors() throws Exception {
        for (String property : SWITCHES) {
            String old = System.getProperty(property);
            try {
                System.setProperty(property, "off");
                assertTrue(injector.anchors().anchors().isEmpty());
                for (var rule : ForgeClientConsumersInjector.RULES) {
                    byte[] original = bytes(MERGED, rule.host().replace('.', '/') + ".class");
                    assertSame(original, injector.transform(rule.host(), original, null), property);
                }
            } finally { restore(property, old); }
        }
        assertEquals(4, injector.anchors().anchors().size());
    }

    @Test
    void nativeForgeEdgesStandDownAndUnrelatedClassesAreNotParsed() throws Exception {
        byte[] unrelated = {1, 2, 3};
        assertSame(unrelated, injector.transform("example.ForeignConsumer", unrelated, null));
        for (var rule : ForgeClientConsumersInjector.RULES) {
            ClassNode nativeNode = node(bytes(MERGED, rule.host().replace('.', '/') + ".class"));
            replaceOwner(target(nativeNode, rule), rule.owner(Ecosystem.NEOFORGE), rule.owner(Ecosystem.FORGE));
            byte[] nativeBytes = write(nativeNode);
            assertSame(nativeBytes, injector.transform(rule.host(), nativeBytes, null),
                    "do not re-serve an already-native Forge consumer: " + rule.host());
        }
    }

    @Test
    void anExistingDualFamilyCallOrAnAmbiguousNeoAnchorDoesNotGetAnotherBridge() throws Exception {
        for (var rule : ForgeClientConsumersInjector.RULES) {
            byte[] original = bytes(MERGED, rule.host().replace('.', '/') + ".class");
            ClassNode dual = node(original);
            target(dual, rule).instructions.insert(callPrefix(rule.owner(Ecosystem.FORGE), rule.call(), rule.descriptor()));
            byte[] alreadyBoth = write(dual);
            assertSame(alreadyBoth, injector.transform(rule.host(), alreadyBoth, null), "native calls already serve both families");
            ClassNode duplicate = node(original);
            MethodNode method = target(duplicate, rule);
            if (rule.handle()) {
                InvokeDynamicInsnNode dynamic = dynamics(method).stream().filter(d -> java.util.Arrays.stream(d.bsmArgs)
                        .anyMatch(a -> a instanceof Handle h && h.getOwner().equals(rule.owner(Ecosystem.NEOFORGE)))).findFirst().orElseThrow();
                assertEquals(0, Type.getArgumentTypes(dynamic.desc).length);
                InsnList prefix = new InsnList();
                prefix.add(new InvokeDynamicInsnNode(dynamic.name, dynamic.desc, dynamic.bsm, dynamic.bsmArgs.clone()));
                prefix.add(new InsnNode(Opcodes.POP));
                method.instructions.insert(prefix);
            } else method.instructions.insert(callPrefix(rule.owner(Ecosystem.NEOFORGE), rule.call(), rule.descriptor()));
            byte[] ambiguous = write(duplicate);
            assertSame(ambiguous, injector.transform(rule.host(), ambiguous, null), "do not guess between two matching consumers");
        }
    }

    @Test
    void movedMethodsAndChangedSignaturesAreNotMatchedByNameAlone() throws Exception {
        for (var rule : ForgeClientConsumersInjector.RULES) {
            ClassNode moved = node(bytes(MERGED, rule.host().replace('.', '/') + ".class"));
            target(moved, rule).name += "$moved";
            byte[] movedBytes = write(moved);
            assertSame(movedBytes, injector.transform(rule.host(), movedBytes, null));
            ClassNode changed = node(bytes(MERGED, rule.host().replace('.', '/') + ".class"));
            MethodNode method = target(changed, rule);
            for (var i : method.instructions) {
                if (i instanceof MethodInsnNode call && call.owner.equals(rule.owner(Ecosystem.NEOFORGE))
                        && call.name.equals(rule.call())) call.name = "differentConsumer";
                if (i instanceof InvokeDynamicInsnNode dynamic) for (int j = 0; j < dynamic.bsmArgs.length; j++) {
                    if (dynamic.bsmArgs[j] instanceof Handle handle && handle.getOwner().equals(rule.owner(Ecosystem.NEOFORGE))) {
                        dynamic.bsmArgs[j] = new Handle(handle.getTag(), handle.getOwner(), "differentConsumer", handle.getDesc(), handle.isInterface());
                    }
                }
            }
            byte[] changedBytes = write(changed);
            assertSame(changedBytes, injector.transform(rule.host(), changedBytes, null));
            ClassNode signature = node(bytes(MERGED, rule.host().replace('.', '/') + ".class"));
            for (var i : target(signature, rule).instructions) {
                if (i instanceof MethodInsnNode call && call.owner.equals(rule.owner(Ecosystem.NEOFORGE))
                        && call.name.equals(rule.call())) call.desc = call.desc.replaceFirst("L[^;]+;", "Ljava/lang/Object;");
                if (i instanceof InvokeDynamicInsnNode dynamic) for (int j = 0; j < dynamic.bsmArgs.length; j++) {
                    if (dynamic.bsmArgs[j] instanceof Handle h && h.getOwner().equals(rule.owner(Ecosystem.NEOFORGE))) {
                        dynamic.bsmArgs[j] = new Handle(h.getTag(), h.getOwner(), h.getName(),
                                h.getDesc().replaceFirst("L[^;]+;", "Ljava/lang/Object;"), h.isInterface());
                    }
                }
            }
            byte[] signatureBytes = write(signature);
            assertSame(signatureBytes, injector.transform(rule.host(), signatureBytes, null), "the descriptor is part of the seam");
        }
    }

    @Test
    void bothRealCarrierApisExistAndBothPresetMapsAreSeededFromTheSameVanillaObjects() throws Exception {
        for (var rule : ForgeClientConsumersInjector.RULES) for (var family : List.of(Ecosystem.FORGE, Ecosystem.NEOFORGE)) {
            Path jar = family == Ecosystem.FORGE ? FORGE : NEO;
            ClassNode carrier = node(bytes(jar, rule.owner(family) + ".class"));
            assertTrue(carrier.methods.stream().anyMatch(m -> m.name.equals(rule.call()) && m.desc.equals(rule.descriptor())
                    && (m.access & Opcodes.ACC_STATIC) != 0), rule.owner(family) + '.' + rule.call());
        }
        for (var family : List.of(Ecosystem.FORGE, Ecosystem.NEOFORGE)) {
            String prefix = family == Ecosystem.FORGE ? "net/minecraftforge" : "net/neoforged/neoforge";
            Path jar = family == Ecosystem.FORGE ? FORGE : NEO;
            ClassNode carrier = node(bytes(jar, prefix + "/client/PresetEditorManager.class"));
            MethodNode init = carrier.methods.stream().filter(m -> m.name.equals("init")).findFirst().orElseThrow();
            boolean vanillaObjects = false, posted = false, published = false;
            for (var instruction : init.instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                        && field.owner.equals("net/minecraft/client/gui/screens/worldselection/PresetEditor")
                        && field.name.equals("EDITORS")) vanillaObjects = true;
                if (instruction instanceof MethodInsnNode call && call.name.equals("postEventWrapContainerInModOrder")) {
                    assertTrue(vanillaObjects, "vanilla entries are copied before registrations");
                    posted = true;
                }
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC && field.name.equals("editors")) {
                    assertTrue(posted, "publish the map after registrations");
                    published = true;
                }
            }
            assertTrue(vanillaObjects && posted && published);
            ClassNode event = node(bytes(jar, prefix + "/client/event/RegisterPresetEditorsEvent.class"));
            assertTrue(event.methods.stream().anyMatch(m -> {
                for (var i : m.instructions) if (i instanceof MethodInsnNode call && call.owner.equals("java/util/Map")
                        && call.name.equals("put")) return true;
                return false;
            }), "registration can replace a vanilla-key entry, not only add a new key");
        }
    }

    @Test
    void runtimeEntryDescriptorsAndCarrierDispatchAgreeWithTheInjectorTable() throws Exception {
        Path classes = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
        Path runtime = classes.resolve(ForgeClientConsumersInjector.RUNTIME + ".class");
        assumeTrue(Files.isRegularFile(runtime), "runtime source set has not been compiled");
        ClassNode node = node(Files.readAllBytes(runtime));
        for (var rule : ForgeClientConsumersInjector.RULES) {
            MethodNode entry = node.methods.stream().filter(m -> m.name.equals(rule.runtimeCall())
                    && m.desc.equals(rule.descriptor())).findFirst().orElseThrow();
            assertTrue((entry.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
            assertTrue(refs(entry, "net/forbric/kernel/runtime/ForgeClientConsumerFlow", "enabled", "()Z", false) > 0,
                    "the typed entry must honor init=off even if its caller was already transformed");
            var guard = java.util.Arrays.stream(entry.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
                    && call.owner.equals("net/forbric/kernel/runtime/ForgeClientConsumerFlow") && call.name.equals("enabled"))
                    .findFirst().orElseThrow();
            var next = guard.getNext();
            while (next.getOpcode() < 0) next = next.getNext();
            JumpInsnNode branch = assertInstanceOf(JumpInsnNode.class, next);
            assertEquals(Opcodes.IFNE, branch.getOpcode(), "enabled=true jumps over the Neo-only return");
            boolean returnsNeoOnly = false;
            for (var instruction = branch.getNext(); instruction != branch.label; instruction = instruction.getNext()) {
                if (instruction.getOpcode() == Opcodes.RETURN || instruction.getOpcode() == Opcodes.ARETURN) returnsNeoOnly = true;
                if (instruction instanceof MethodInsnNode call) assertFalse(call.owner.startsWith("net/minecraftforge/"),
                        "the disabled branch must not read uninitialized Forge manager state");
            }
            assertTrue(returnsNeoOnly, "disabled entry must return before any Forge dispatch");
            for (var family : List.of(Ecosystem.FORGE, Ecosystem.NEOFORGE)) {
                assertTrue(node.methods.stream().anyMatch(m -> refs(m, rule.owner(family), rule.call(), rule.descriptor(), false)
                        + refs(m, rule.owner(family), rule.call(), rule.descriptor(), true) > 0),
                        "runtime delegates to the actual carrier: " + rule.owner(family));
            }
        }
    }

    private static InsnList callPrefix(String owner, String method, String descriptor) {
        InsnList prefix = new InsnList();
        for (Type argument : Type.getArgumentTypes(descriptor)) {
            assertEquals(Type.OBJECT, argument.getSort());
            prefix.add(new InsnNode(Opcodes.ACONST_NULL));
        }
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, method, descriptor, false));
        if (Type.getReturnType(descriptor).getSort() != Type.VOID) prefix.add(new InsnNode(Opcodes.POP));
        return prefix;
    }

    private static void restore(String property, String old) { if (old == null) System.clearProperty(property); else System.setProperty(property, old); }
    private static List<InvokeDynamicInsnNode> dynamics(MethodNode method) {
        List<InvokeDynamicInsnNode> result = new ArrayList<>();
        for (var i : method.instructions) if (i instanceof InvokeDynamicInsnNode dynamic) result.add(dynamic);
        return result;
    }
    private static int refs(MethodNode m, String owner, String name, String descriptor, boolean handles) {
        int count = 0;
        for (var i : m.instructions) {
            if (!handles && i instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor)) count++;
            if (handles && i instanceof InvokeDynamicInsnNode dynamic) for (Object argument : dynamic.bsmArgs) {
                if (argument instanceof Handle h && h.getOwner().equals(owner) && h.getName().equals(name) && h.getDesc().equals(descriptor)) count++;
            }
        }
        return count;
    }
    private static void replaceOwner(MethodNode method, String old, String replacement) {
        for (var i : method.instructions) {
            if (i instanceof MethodInsnNode call && call.owner.equals(old)) call.owner = replacement;
            if (i instanceof InvokeDynamicInsnNode dynamic) for (int j = 0; j < dynamic.bsmArgs.length; j++) {
                if (dynamic.bsmArgs[j] instanceof Handle h && h.getOwner().equals(old)) {
                    dynamic.bsmArgs[j] = new Handle(h.getTag(), replacement, h.getName(), h.getDesc(), h.isInterface());
                }
            }
        }
    }
    private static Map<String, Integer> opcodeCounts(ClassNode node) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (MethodNode method : node.methods) {
            int count = 0;
            for (var i : method.instructions) if (i.getOpcode() >= 0) count++;
            counts.put(method.name + method.desc, count);
        }
        return counts;
    }
    private static MethodNode target(ClassNode node, ForgeClientConsumersInjector.Rule rule) {
        return node.methods.stream().filter(m -> m.name.equals(rule.method()) && m.desc.equals(rule.methodDescriptor())).findFirst().orElseThrow();
    }
    private static ClassNode node(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static byte[] bytes(Path jar, String entry) throws Exception {
        assumeTrue(Files.isRegularFile(jar), "staged carrier absent: " + jar);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var value = zip.getEntry(entry); assertNotNull(value, entry); return zip.getInputStream(value).readAllBytes();
        }
    }
}
