package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** Runs the production pure seams, plus the unmodified Forge tooltip method on inert parameter interfaces. */
@ResourceLock("system-properties")
class ForgeClientConsumerFlowTest {
    @Test
    void bothContributorsMutateTheSameMapInNeoThenForgeOrder() throws Exception {
        try (Api api = api()) {
            Map<String, Object> target = new LinkedHashMap<>();
            Object vanilla = new Object(), neo = new Object(), forge = new Object();
            target.put("vanilla", vanilla);
            List<String> order = new ArrayList<>();
            api.both(target, map -> {
                assertSame(target, map);
                order.add("neo");
                map.put("neo", neo);
            }, map -> {
                assertSame(target, map);
                assertSame(neo, map.get("neo"), "Forge must see Neo's completed contribution");
                order.add("forge");
                map.put("forge", forge);
            });
            assertEquals(List.of("neo", "forge"), order);
            assertEquals(List.of("vanilla", "neo", "forge"), new ArrayList<>(target.keySet()));
            assertSame(vanilla, target.get("vanilla"));
            assertSame(forge, target.get("forge"));
        }
    }

    @Test
    void contributorFailurePropagatesAndDoesNotPretendTheOtherFamilyRan() throws Exception {
        try (Api api = api()) {
            Object target = new Object();
            RuntimeException failure = new IllegalStateException("broken carrier");
            assertSame(failure, assertThrows(IllegalStateException.class, () -> api.both(target,
                    ignored -> { throw failure; }, ignored -> fail("Forge cannot follow a failed Neo contribution"))));
            AtomicInteger neo = new AtomicInteger();
            assertSame(failure, assertThrows(IllegalStateException.class, () -> api.both(target,
                    ignored -> neo.incrementAndGet(), ignored -> { throw failure; })));
            assertEquals(1, neo.get());
        }
    }

    @Test
    void tooltipKeepsNeoResultsAndFailuresWithoutCallingForge() throws Exception {
        try (Api api = api()) {
            Object neo = new Object();
            assertSame(neo, api.tooltip(() -> neo, () -> fail("do not replace a Neo factory")));
            RuntimeException failure = new IllegalArgumentException("Neo factory failed");
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> api.tooltip(
                    () -> { throw failure; }, () -> fail("do not retry a broken Neo factory through Forge"))));
        }
    }

    @Test
    void tooltipFallsBackAndOnlyRecognizesTheRealForgeManagersUnknownSentinel() throws Exception {
        try (Api api = api()) {
            Object forge = new Object();
            assertSame(forge, api.tooltip(() -> null, () -> forge));
            ColdForgeTooltip carrier = new ColdForgeTooltip();
            Object component = carrier.component();
            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> carrier.create(component));
            assertEquals("Unknown TooltipComponent", unknown.getMessage());
            assertEquals("net.minecraftforge.client.gui.ClientTooltipComponentManager", unknown.getStackTrace()[0].getClassName());
            assertNull(api.tooltip(() -> null, () -> carrier.create(component)));
            carrier.factory(component, ignored -> null);
            assertNull(api.tooltip(() -> null, () -> carrier.create(component)), "Forge also treats a factory's null as unknown");
            Object output = carrier.output();
            carrier.factory(component, ignored -> output);
            assertSame(output, api.tooltip(() -> null, () -> carrier.create(component)));
        }
    }

    @Test
    void factoryIllegalArgumentsIncludingTheSameMessageAreNeverSwallowed() throws Exception {
        try (Api api = api()) {
            ColdForgeTooltip carrier = new ColdForgeTooltip();
            Object component = carrier.component();
            for (String message : List.of("factory rejected its data", "Unknown TooltipComponent")) {
                IllegalArgumentException failure = new IllegalArgumentException(message);
                carrier.factory(component, ignored -> { throw failure; });
                assertSame(failure, assertThrows(IllegalArgumentException.class,
                        () -> api.tooltip(() -> null, () -> carrier.create(component))));
            }
            IllegalArgumentException noTrace = new IllegalArgumentException("Unknown TooltipComponent");
            noTrace.setStackTrace(new StackTraceElement[0]);
            assertSame(noTrace, assertThrows(IllegalArgumentException.class,
                    () -> api.tooltip(() -> null, () -> { throw noTrace; })));
            NullPointerException notInitialized = new NullPointerException("FACTORIES");
            assertSame(notInitialized, assertThrows(NullPointerException.class,
                    () -> api.tooltip(() -> null, () -> { throw notInitialized; })));
        }
    }

    @Test
    void presetDefaultMustNotHideEitherFamilysOverrideOfAVanillaKey() throws Exception {
        try (Api api = api()) {
            Object vanilla = new Object(), neo = new Object(), forge = new Object();
            Runnable noConflict = () -> fail("only one family changed this preset");
            assertSame(vanilla, api.preset(vanilla, vanilla, vanilla, noConflict));
            assertSame(forge, api.preset(vanilla, vanilla, forge, noConflict),
                    "Neo contains the copied vanilla editor; Forge's actual override must win");
            assertSame(neo, api.preset(vanilla, neo, vanilla, noConflict));
            assertSame(forge, api.preset(null, null, forge, noConflict));
            assertSame(neo, api.preset(null, neo, null, noConflict));
            assertNull(api.preset(null, null, null, noConflict));
            assertSame(neo, api.preset(null, neo, neo, noConflict));
        }
    }

    @Test
    void presetUsesIdentityAndReportsAnActualTwoCustomEditorConflict() throws Exception {
        try (Api api = api()) {
            Object vanilla = new String("editor"), forge = new String("editor");
            assertEquals(vanilla, forge);
            assertNotSame(vanilla, forge);
            assertSame(forge, api.preset(vanilla, vanilla, forge, () -> fail("Forge is the only override")),
                    "equals() is not evidence that a mod supplied the vanilla editor instance");
            Object neo = new Object();
            AtomicInteger conflicts = new AtomicInteger();
            assertSame(neo, api.preset(vanilla, neo, forge, conflicts::incrementAndGet));
            assertEquals(1, conflicts.get(), "different custom editors cannot be silently discarded");
        }
    }

    @Test
    void independentConsumerAndInitSwitchesBothDisableTheFlow() throws Exception {
        Map<String, String> previous = new LinkedHashMap<>();
        for (String property : List.of("forbric.forgeClientConsumers", "forbric.forgeClientInit")) previous.put(property, System.getProperty(property));
        try (Api api = api()) {
            previous.keySet().forEach(System::clearProperty);
            assertTrue(api.enabled());
            System.setProperty("forbric.forgeClientConsumers", "off");
            assertFalse(api.enabled());
            System.setProperty("forbric.forgeClientConsumers", "on");
            System.setProperty("forbric.forgeClientInit", "OFF");
            assertFalse(api.enabled(), "no reads of Forge's uninitialized tables under the init negative control");
            System.setProperty("forbric.forgeClientInit", "on");
            assertTrue(api.enabled());
        } finally {
            previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
        }
    }

    private static Api api() throws Exception {
        Path classes = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
        String binary = "net.forbric.kernel.runtime.ForgeClientConsumerFlow";
        assumeTrue(Files.isRegularFile(classes.resolve(binary.replace('.', '/') + ".class")), "runtime source set not compiled");
        URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        Class<?> type = Class.forName(binary, true, loader);
        return new Api(loader, type.getMethod("appendBoth", Object.class, Consumer.class, Consumer.class),
                type.getMethod("tooltip", Supplier.class, Supplier.class),
                type.getMethod("preset", Object.class, Object.class, Object.class, Runnable.class), type.getMethod("enabled"));
    }

    private record Api(URLClassLoader loader, Method bothMethod, Method tooltipMethod, Method presetMethod, Method enabledMethod) implements AutoCloseable {
        <T> void both(T target, Consumer<T> neo, Consumer<T> forge) { invoke(bothMethod, target, neo, forge); }
        Object tooltip(Supplier<?> neo, Supplier<?> forge) { return invoke(tooltipMethod, neo, forge); }
        Object preset(Object vanilla, Object neo, Object forge, Runnable conflict) { return invoke(presetMethod, vanilla, neo, forge, conflict); }
        boolean enabled() { return (boolean) invoke(enabledMethod); }
        @Override public void close() throws Exception { loader.close(); }
    }

    private static Object invoke(Method method, Object... arguments) {
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException wrapped) {
            Throwable failure = wrapped.getCause();
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new AssertionError(failure);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    /** Defines the carrier's exact class bytes. Minecraft interfaces and the unused init-event linkage are inert stubs. */
    private static final class ColdForgeTooltip extends ClassLoader {
        private final Class<?> tooltip;
        private final Class<?> clientTooltip;
        private final Class<?> manager;
        private final Method create;
        ColdForgeTooltip() throws Exception {
            super(ClassLoader.getPlatformClassLoader());
            tooltip = defineInterface("net.minecraft.world.inventory.tooltip.TooltipComponent");
            clientTooltip = defineInterface("net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent");
            defineInterface("net.minecraftforge.eventbus.internal.Event");
            defineInterface("net.minecraftforge.eventbus.api.bus.EventBus");
            defineUnusedInitializationEvent();
            Path jar = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/forge-runtime/forge-runtime.jar");
            assumeTrue(Files.isRegularFile(jar), "staged Forge carrier absent");
            String binary = "net.minecraftforge.client.gui.ClientTooltipComponentManager";
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                byte[] bytes = zip.getInputStream(zip.getEntry(binary.replace('.', '/') + ".class")).readAllBytes();
                manager = defineClass(binary, bytes, 0, bytes.length);
            }
            create = manager.getMethod("createClientTooltipComponent", tooltip);
            setFactories(Map.of());
        }
        Object component() { return Proxy.newProxyInstance(this, new Class<?>[] {tooltip}, (proxy, method, args) -> null); }
        Object output() { return Proxy.newProxyInstance(this, new Class<?>[] {clientTooltip}, (proxy, method, args) -> null); }
        Object create(Object input) { return invoke(create, input); }
        void factory(Object input, Function<Object, Object> factory) throws Exception { setFactories(Map.of(input.getClass(), factory)); }
        private void setFactories(Map<?, ?> value) throws Exception {
            var field = manager.getDeclaredField("FACTORIES"); field.setAccessible(true); field.set(null, value);
        }
        private void defineUnusedInitializationEvent() {
            String name = "net/minecraftforge/client/event/RegisterClientTooltipComponentFactoriesEvent";
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object",
                    new String[] {"net/minecraftforge/eventbus/internal/Event"});
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "BUS", "Lnet/minecraftforge/eventbus/api/bus/EventBus;", null, null).visitEnd();
            var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/util/Map;)V", null, null);
            constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(1, 2); constructor.visitEnd();
            writer.visitEnd();
            byte[] bytes = writer.toByteArray();
            defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
        }
        private Class<?> defineInterface(String binary) {
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                    binary.replace('.', '/'), null, "java/lang/Object", null);
            writer.visitEnd();
            byte[] bytes = writer.toByteArray();
            return defineClass(binary, bytes, 0, bytes.length);
        }
    }
}
