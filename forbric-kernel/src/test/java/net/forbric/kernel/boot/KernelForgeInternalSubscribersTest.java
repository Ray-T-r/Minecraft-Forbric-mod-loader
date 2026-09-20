/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.Side;

@ResourceLock("system-properties")
class KernelForgeInternalSubscribersTest {
    private static final Path FORGE = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/forge-runtime/forge-runtime.jar");
    private static final String GROUP = "net/minecraftforge/eventbus/api/bus/BusGroup";
    private static final String LOGIC = KernelForgeInternalSubscribers.FML_LOGIC.replace('.', '/');
    private static final String CONTEXT = "net/minecraftforge/fml/ModLoadingContext";
    private static final String CONTAINER = "net/minecraftforge/fml/ModContainer";
    @TempDir Path temporary;

    @Test
    void realCarrierContainsTwoClientSubscribersWithTheirActualEventsAndNativeRegistrar() throws Exception {
        assumeTrue(Files.isRegularFile(FORGE), "staged Forge carrier absent");
        var subscribers = KernelForgeInternalSubscribers.scan(List.of(FORGE, FORGE));
        var internal = subscribers.stream().filter(s -> s.family() == Ecosystem.FORGE && "forge".equals(s.modId())).toList();
        assertEquals(Set.of("net.minecraftforge.client.ClientForgeMod", "net.minecraftforge.client.model.data.ModelDataManager"),
                internal.stream().map(KernelEventSubscribers.Subscriber::className).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, internal.size(), "duplicate jar inputs must not duplicate classes");
        for (var sub : internal) assertEquals(Set.of("CLIENT"), sub.dists());
        var client = internal.stream().filter(s -> s.className().endsWith("ClientForgeMod")).findFirst().orElseThrow();
        assertEquals("BOTH", client.bus());
        assertEquals(Set.of("net/minecraftforge/client/event/ModelEvent$RegisterGeometryLoaders",
                "net/minecraftforge/client/event/RegisterClientReloadListenersEvent",
                "net/minecraftforge/client/event/RegisterNamedRenderTypesEvent"), client.subscribedEvents());
        var model = internal.stream().filter(s -> s.className().endsWith("ModelDataManager")).findFirst().orElseThrow();
        assertEquals("FORGE", model.bus());
        assertEquals(Set.of("net/minecraftforge/event/level/ChunkEvent$Unload"), model.subscribedEvents());
        try (ZipFile zip = new ZipFile(FORGE.toFile())) {
            ClassNode logic = read(zip, LOGIC);
            assertTrue(logic.methods.stream().anyMatch(m -> m.name.equals("register")
                    && m.desc.equals("(L" + GROUP + ";Ljava/lang/Class;)V")
                    && (m.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)));
            ClassNode context = read(zip, CONTEXT);
            assertTrue(context.fields.stream().anyMatch(f -> f.name.equals("activeContainer") && f.desc.equals("L" + CONTAINER + ";")));
            assertTrue(context.methods.stream().anyMatch(m -> m.name.equals("setActiveContainer") && m.desc.equals("(L" + CONTAINER + ";)V")));
            Set<String> literals = new LinkedHashSet<>();
            for (var m : read(zip, client.className().replace('.', '/')).methods) for (var i : m.instructions)
                if (i instanceof LdcInsnNode ldc && ldc.cst instanceof String text) literals.add(text);
            assertTrue(literals.containsAll(Set.of("empty", "obj", "fluid_container", "item_unlit")));
            assertFalse(literals.contains("composite"), "current Forge does not register a composite loader");
            for (var sub : internal) for (String event : sub.subscribedEvents())
                assertFalse(implementsModEvent(zip, event), "today's AUTO listeners need no manufactured mod context: " + event);
        }
        var server = KernelForgeInternalSubscribers.registerSelected(internal, Side.DEDICATED_SERVER, false,
                new LinkedHashSet<>(), (s, bus) -> fail("dedicated server must not load the client subscriber"));
        assertEquals(0, server.registered());
        assertEquals(2, server.wrongSide());
    }

    @Test
    void selectionKeepsForgeOwnerSideAndBusAndDoesNotRegisterGuestsOrNeoForge() {
        var auto = sub("Auto", Ecosystem.FORGE, "forge", "BOTH", "CLIENT");
        var game = sub("Game", Ecosystem.FORGE, "forge", "FORGE");
        List<String> seen = new ArrayList<>();
        var result = KernelForgeInternalSubscribers.registerSelected(List.of(auto, game, auto,
                sub("Mod", Ecosystem.FORGE, "forge", "MOD"),
                sub("Server", Ecosystem.FORGE, "forge", "FORGE", "DEDICATED_SERVER"),
                sub("Guest", Ecosystem.FORGE, "guest", "FORGE"),
                sub("UnknownOwner", Ecosystem.FORGE, null, "FORGE"),
                sub("Neo", Ecosystem.NEOFORGE, "forge", "FORGE")), Side.CLIENT, false,
                new LinkedHashSet<>(), (s, bus) -> seen.add(s.className() + ':' + bus));
        assertEquals(List.of("Auto:AUTO", "Game:DEFAULT"), seen);
        assertEquals(new KernelForgeInternalSubscribers.Result(2, 1, 1, 3, 1, 0), result);
    }

    @Test
    void missingModBusIsNotDowngradedAndCanBeSuppliedBeforeAnyAttempt() {
        Set<String> attempts = new LinkedHashSet<>();
        var sub = sub("NeedsMod", Ecosystem.FORGE, "forge", "MOD");
        var skipped = KernelForgeInternalSubscribers.registerSelected(List.of(sub), Side.CLIENT, false, attempts,
                (s, bus) -> fail("missing mod bus must not fall back to DEFAULT"));
        assertEquals(1, skipped.missingModBus());
        assertTrue(attempts.isEmpty());
        var supplied = KernelForgeInternalSubscribers.registerSelected(List.of(sub), Side.CLIENT, true, attempts,
                (s, bus) -> assertEquals(KernelEventSubscribers.BusChoice.MOD, bus));
        assertEquals(1, supplied.registered());
    }

    @Test
    void aFailedPartialNativeRegistrationIsNamedAndNeverRetriedWhileOtherClassesContinue() {
        Set<String> attempts = new LinkedHashSet<>();
        AtomicInteger partial = new AtomicInteger();
        var broken = sub("Broken", Ecosystem.FORGE, "forge", "BOTH");
        var good = sub("Good", Ecosystem.FORGE, "forge", "BOTH");
        var result = KernelForgeInternalSubscribers.registerSelected(List.of(broken, good), Side.CLIENT, false, attempts,
                (s, bus) -> { if (s == broken) { partial.incrementAndGet(); throw new IllegalStateException("second listener failed"); } });
        assertEquals(1, result.registered());
        assertEquals(1, result.failed());
        var second = KernelForgeInternalSubscribers.registerSelected(List.of(broken, good), Side.CLIENT, false, attempts,
                (s, bus) -> fail("a retry could double the first listener from the failed class"));
        assertEquals(2, second.alreadyAttempted());
        assertEquals(1, partial.get());
    }

    @Test
    void concurrentCallsCannotWireTheSameClassTwice() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        Set<String> attempts = new LinkedHashSet<>();
        AtomicInteger actual = new AtomicInteger();
        var sub = sub("Concurrent", Ecosystem.FORGE, "forge", "BOTH");
        try {
            var task = (java.util.concurrent.Callable<KernelForgeInternalSubscribers.Result>) () -> {
                ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                return KernelForgeInternalSubscribers.registerSelected(List.of(sub), Side.CLIENT, false, attempts,
                        (s, bus) -> actual.incrementAndGet());
            };
            var first = executor.submit(task); var second = executor.submit(task);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            var a = first.get(5, TimeUnit.SECONDS); var b = second.get(5, TimeUnit.SECONDS);
            assertEquals(1, a.registered() + b.registered());
            assertEquals(1, a.alreadyAttempted() + b.alreadyAttempted());
            assertEquals(1, actual.get());
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test
    void publicEntryUsesFmlNativeRegistrarOncePerClassLoaderWithAutoAndDefaultGroups() throws Exception {
        Fixture fixture = fixture();
        TrackingLoader loader = fixture.loader();
        var first = KernelForgeInternalSubscribers.register(loader, List.of(fixture.jar(), fixture.jar()), Side.CLIENT);
        assertEquals(2, first.registered());
        Class<?> logic = loader.loadClass(LOGIC.replace('/', '.'));
        assertEquals(2, ((Number) fieldValue(logic, "calls")).intValue());
        assertEquals(List.of("example.InternalAuto", "example.InternalGame"), fieldValue(logic, "classes"));
        List<?> groups = (List<?>) fieldValue(logic, "groups");
        assertNull(groups.get(0), "AUTO passes null so FML selects the bus per event type");
        assertSame(loader.loadClass(GROUP.replace('/', '.')).getField("DEFAULT").get(null), groups.get(1));
        var repeat = KernelForgeInternalSubscribers.register(loader, List.of(fixture.jar()), Side.CLIENT);
        assertEquals(0, repeat.registered()); assertEquals(2, repeat.alreadyAttempted());
        assertEquals(2, ((Number) fieldValue(logic, "calls")).intValue());
        var secondLoader = new TrackingLoader(loader.definitions);
        assertEquals(2, KernelForgeInternalSubscribers.register(secondLoader, List.of(fixture.jar()), Side.CLIENT).registered(),
                "a new game class loader has its own native buses and must not inherit old registrations");
    }

    @Test
    void nativeModBusAndAutoContextUseTheForgeBaselineAndRestoreTheOuterContext() throws Exception {
        for (boolean fail : List.of(false, true)) {
            Fixture fixture = fixture(true, fail);
            TrackingLoader loader = fixture.loader();
            Class<?> contextType = loader.loadClass(CONTEXT.replace('/', '.'));
            Object context = contextType.getMethod("get").invoke(null);
            var active = contextType.getDeclaredField("activeContainer"); active.setAccessible(true);
            Object outer = loader.loadClass(CONTAINER.replace('/', '.')).getConstructor().newInstance();
            Object baseline = loader.loadClass(CONTAINER.replace('/', '.')).getConstructor().newInstance();
            Object group = loader.loadClass(GROUP.replace('/', '.')).getConstructor().newInstance();
            active.set(context, outer);
            var handle = new KernelForgeModContext.Handle("forge", group, baseline, null);
            var result = KernelForgeInternalSubscribers.register(loader, List.of(fixture.jar()), Side.CLIENT, handle);
            assertSame(outer, active.get(context), "restore the real outer active container on success and failure");
            assertEquals(fail ? 3 : 0, result.failed());
            assertEquals(fail ? 0 : 3, result.registered());
            Class<?> logic = loader.loadClass(LOGIC.replace('/', '.'));
            List<?> contexts = (List<?>) fieldValue(logic, "contexts");
            assertEquals(3, contexts.size());
            contexts.forEach(value -> assertSame(baseline, value));
            List<?> groups = (List<?>) fieldValue(logic, "groups");
            assertSame(group, groups.get(2), "explicit MOD subscriber gets the baseline's real group");
        }
    }

    @Test
    void dedicatedServerSelectionNeverEvenResolvesClientClassesOrTheNativeApi() throws Exception {
        Fixture fixture = fixture();
        var result = KernelForgeInternalSubscribers.register(fixture.loader(), List.of(fixture.jar()), Side.DEDICATED_SERVER);
        assertEquals(2, result.wrongSide());
        assertTrue(fixture.loader().defined.isEmpty(), "side filtering must precede Class.forName and BusGroup lookup");
    }

    @Test
    void negativeControlLeavesTheRealRegistrationInputUntouched() throws Exception {
        Fixture fixture = fixture();
        String old = System.getProperty("forbric.forgeInternalSubscribers");
        try {
            System.setProperty("forbric.forgeInternalSubscribers", "off");
            assertEquals(0, KernelForgeInternalSubscribers.register(fixture.loader(), List.of(fixture.jar()), Side.CLIENT).registered());
            assertTrue(fixture.loader().defined.isEmpty());
        } finally { if (old == null) System.clearProperty("forbric.forgeInternalSubscribers"); else System.setProperty("forbric.forgeInternalSubscribers", old); }
        assertEquals(2, KernelForgeInternalSubscribers.register(fixture.loader(), List.of(fixture.jar()), Side.CLIENT).registered(),
                "the off control must not consume the attempt ledger");
    }

    private static KernelEventSubscribers.Subscriber sub(String name, Ecosystem family, String owner, String bus, String... dists) {
        return new KernelEventSubscribers.Subscriber(name, family, Set.of(dists), owner, bus);
    }

    private static boolean implementsModEvent(ZipFile zip, String name) throws Exception {
        if (name.equals("net/minecraftforge/fml/event/IModBusEvent")) return true;
        if (name.startsWith("java/")) return false;
        ClassNode node = read(zip, name);
        for (String face : node.interfaces) if (implementsModEvent(zip, face)) return true;
        return node.superName != null && implementsModEvent(zip, node.superName);
    }

    private static ClassNode read(ZipFile zip, String name) throws Exception {
        var entry = zip.getEntry(name + ".class"); assertNotNull(entry, name);
        ClassNode node = new ClassNode(); new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, 0); return node;
    }

    private Fixture fixture() throws Exception { return fixture(false, false); }

    private Fixture fixture(boolean modSubscriber, boolean failRegistration) throws Exception {
        Map<String, byte[]> definitions = new HashMap<>();
        definitions.put(GROUP.replace('/', '.'), group());
        definitions.put(LOGIC.replace('/', '.'), logic(failRegistration));
        definitions.put(CONTEXT.replace('/', '.'), context());
        ClassWriter container = start(CONTAINER); container.visitEnd();
        definitions.put(CONTAINER.replace('/', '.'), container.toByteArray());
        definitions.put("example.InternalAuto", subscriber("example/InternalAuto", "BOTH"));
        definitions.put("example.InternalGame", subscriber("example/InternalGame", "FORGE"));
        if (modSubscriber) definitions.put("example.InternalMod", subscriber("example/InternalMod", "MOD"));
        Path jar = temporary.resolve("carrier.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var entry : definitions.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey().replace('.', '/') + ".class"));
                zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return new Fixture(jar, new TrackingLoader(definitions));
    }

    private record Fixture(Path jar, TrackingLoader loader) {}
    private static final class TrackingLoader extends ClassLoader {
        final Map<String, byte[]> definitions; final List<String> defined = new ArrayList<>();
        TrackingLoader(Map<String, byte[]> definitions) { super(ClassLoader.getPlatformClassLoader()); this.definitions = definitions; }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name); if (bytes == null) throw new ClassNotFoundException(name);
            defined.add(name); return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static Object fieldValue(Class<?> type, String name) throws Exception {
        var field = type.getField(name); field.setAccessible(true); return field.get(null);
    }
    private static ClassWriter start(String name) { return start(name, Opcodes.ACC_PUBLIC); }
    private static ClassWriter start(String name, int access) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, access, name, null, "java/lang/Object", null);
        var ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0); ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0); ctor.visitEnd(); return writer;
    }
    private static byte[] subscriber(String name, String bus) {
        ClassWriter writer = start(name);
        var annotation = writer.visitAnnotation("Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;", true);
        annotation.visit("modid", "forge");
        annotation.visitEnum("bus", "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus;", bus);
        var sides = annotation.visitArray("value"); sides.visitEnum(null, "Lnet/minecraftforge/api/distmarker/Dist;", "CLIENT"); sides.visitEnd();
        annotation.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] group() {
        ClassWriter writer = start(GROUP);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "DEFAULT", "L" + GROUP + ";", null, null).visitEnd();
        var init = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null); init.visitCode();
        init.visitTypeInsn(Opcodes.NEW, GROUP); init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, GROUP, "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, GROUP, "DEFAULT", "L" + GROUP + ";");
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] logic(boolean fail) {
        ClassWriter writer = start(LOGIC, 0); // the genuine FML registrar's owning class is package-private
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null).visitEnd();
        for (String field : List.of("classes", "groups", "contexts")) writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, "Ljava/util/List;", null, null).visitEnd();
        var init = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null); init.visitCode();
        for (String field : List.of("classes", "groups", "contexts")) {
            init.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList"); init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
            init.visitFieldInsn(Opcodes.PUTSTATIC, LOGIC, field, "Ljava/util/List;");
        }
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd();
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register", "(L" + GROUP + ";Ljava/lang/Class;)V", null, null); method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, LOGIC, "calls", "I"); method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IADD);
        method.visitFieldInsn(Opcodes.PUTSTATIC, LOGIC, "calls", "I");
        method.visitFieldInsn(Opcodes.GETSTATIC, LOGIC, "classes", "Ljava/util/List;"); method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true); method.visitInsn(Opcodes.POP);
        method.visitFieldInsn(Opcodes.GETSTATIC, LOGIC, "groups", "Ljava/util/List;"); method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true); method.visitInsn(Opcodes.POP);
        method.visitFieldInsn(Opcodes.GETSTATIC, LOGIC, "contexts", "Ljava/util/List;");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT, "get", "()L" + CONTEXT + ";", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONTEXT, "getContainer", "()L" + CONTAINER + ";", false);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true); method.visitInsn(Opcodes.POP);
        if (fail) {
            method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException"); method.visitInsn(Opcodes.DUP); method.visitLdcInsn("native registration failed");
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
            method.visitInsn(Opcodes.ATHROW);
        } else method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] context() {
        ClassWriter writer = start(CONTEXT);
        writer.visitField(Opcodes.ACC_PRIVATE, "activeContainer", "L" + CONTAINER + ";", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "INSTANCE", "L" + CONTEXT + ";", null, null).visitEnd();
        var init = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null); init.visitCode();
        init.visitTypeInsn(Opcodes.NEW, CONTEXT); init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, CONTEXT, "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, CONTEXT, "INSTANCE", "L" + CONTEXT + ";");
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd();
        var get = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get", "()L" + CONTEXT + ";", null, null); get.visitCode();
        get.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT, "INSTANCE", "L" + CONTEXT + ";");
        get.visitInsn(Opcodes.ARETURN); get.visitMaxs(0, 0); get.visitEnd();
        var container = writer.visitMethod(Opcodes.ACC_PUBLIC, "getContainer", "()L" + CONTAINER + ";", null, null); container.visitCode();
        container.visitVarInsn(Opcodes.ALOAD, 0); container.visitFieldInsn(Opcodes.GETFIELD, CONTEXT, "activeContainer", "L" + CONTAINER + ";");
        container.visitInsn(Opcodes.ARETURN); container.visitMaxs(0, 0); container.visitEnd();
        var set = writer.visitMethod(0, "setActiveContainer", "(L" + CONTAINER + ";)V", null, null); set.visitCode();
        set.visitVarInsn(Opcodes.ALOAD, 0); set.visitVarInsn(Opcodes.ALOAD, 1);
        set.visitFieldInsn(Opcodes.PUTFIELD, CONTEXT, "activeContainer", "L" + CONTAINER + ";");
        set.visitInsn(Opcodes.RETURN); set.visitMaxs(0, 0); set.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
}
