package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import net.forbric.kernel.boot.DefaultPredicateDispatch;
import net.forbric.kernel.boot.DefinedMethodContracts;

@ResourceLock("DefinedMethodContracts")
class DefaultPredicateTransportTest {
    @BeforeEach @AfterEach void reset() { DefinedMethodContracts.resetForTests(); DefaultPredicateDispatch.resetForTests(); }

    @Test void pureDefaultForwarderRetainsContextAndConcreteGuestFlagsOverride() throws Exception {
        RuntimeFixture ordinary = fixture(DefaultModel.class, false);
        assertTrue(ordinary.invoke()); assertEquals(1, ordinary.sourceCalls()); assertEquals(0, ordinary.operations.get());
        assertEquals(1, ordinary.world.calls);
        RuntimeFixture guest = fixture(GuestModel.class, false);
        assertTrue(guest.invoke()); assertEquals(1, guest.sourceCalls()); assertEquals(0, guest.operations.get());
        assertEquals(1, guest.model.getClass().getField("guestCalls").getInt(guest.model));
        assertEquals(0, guest.world.calls);
    }

    @Test void nativePredicateOverrideWinsEvenWhenGuestAlsoOverridesFlagsAndExecutesOnce() throws Exception {
        RuntimeFixture fixture = fixture(NativeOverride.class, false);
        assertFalse(fixture.invoke()); assertEquals(0, fixture.sourceCalls()); assertEquals(1, fixture.operations.get());
        assertEquals(1, fixture.model.getClass().getField("nativeCalls").getInt(fixture.model));
        assertEquals(0, fixture.model.getClass().getField("guestCalls").getInt(fixture.model));
    }

    @Test void aConcreteGuestPredicateKeepsItsOriginalVirtualOverride() throws Exception {
        RuntimeFixture fixture = fixture(GuestPredicateModel.class, false);
        assertFalse(fixture.invoke()); assertEquals(1, fixture.sourceCalls()); assertEquals(0, fixture.operations.get());
        assertEquals(1, fixture.model.getClass().getField("predicateCalls").getInt(fixture.model));
    }

    @Test void aFinalDefinedDefaultMutationCannotBorrowTheRawSourceProof() throws Exception {
        RuntimeFixture fixture = fixture(DefaultModel.class, true);
        assertTrue(fixture.invoke()); assertEquals(0, fixture.sourceCalls()); assertEquals(1, fixture.operations.get());
        assertEquals(1, fixture.world.calls);
    }

    @Test void aChangedFinalIdentityProjectionCannotBorrowAnUnchangedPredicateHash() throws Exception {
        RuntimeFixture fixture = fixture(DefaultModel.class, false, true);
        assertTrue(fixture.invoke()); assertEquals(0, fixture.sourceCalls()); assertEquals(1, fixture.operations.get());
    }

    @Test void anOverridableProjectionCannotBorrowTheRawDefaultHelperProof() throws Exception {
        Map<String, ClassNode> nodes = nodes();
        MethodNode projection = method(nodes.get(name(NativeDefaults.class)), "projection");
        projection.access = (projection.access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC;
        assertEquals(0, MixinDefaultPredicateAdapter.adapt(nodes.get(name(GuestHandler.class)), nodes::get));
    }

    @Test void ambiguousNativeOverloadsOrModifiedPredicateBodyDeclineWithoutEditing() throws Exception {
        Map<String, ClassNode> nodes = nodes(); ClassNode api = nodes.get(name(GuestDefaults.class));
        ClassNode nativeType = nodes.get(name(NativeDefaults.class));
        MethodNode ambiguous = new MethodNode(Opcodes.ACC_PUBLIC, "bits", "(" + Type.getDescriptor(World.class)
                + Type.getDescriptor(Position.class) + ")I", null, null);
        ambiguous.instructions.add(new InsnNode(Opcodes.ICONST_0)); ambiguous.instructions.add(new InsnNode(Opcodes.IRETURN));
        nativeType.methods.add(ambiguous);
        assertEquals(0, DefaultMethodOverloadBridge.adapt(api, nodes::get));
        assertEquals(0, MixinDefaultPredicateAdapter.adapt(nodes.get(name(GuestHandler.class)), nodes::get));
        nodes = nodes();
        method(nodes.get(name(NativeDefaults.class)), "matches").instructions.insert(new FieldInsnNode(Opcodes.GETSTATIC, "fixture/Effects", "value", "I"));
        assertEquals(0, MixinDefaultPredicateAdapter.adapt(nodes.get(name(GuestHandler.class)), nodes::get));
    }

    @Test void actualRendererContractsBindFromJarBodiesAndDeclaredInterfaceInjection() throws Exception {
        ActualApi actual = actualApi();
        ClassNode api = actual.resolve("net/fabricmc/fabric/api/client/renderer/v1/model/FabricBlockStateModel");
        assertEquals(1, DefaultMethodOverloadBridge.adapt(api, actual::resolve));
        for (String name : List.of("net/fabricmc/fabric/mixin/client/renderer/block/render/LevelExtractorMixin",
                "net/fabricmc/fabric/mixin/client/renderer/submit/SubmitNodeCollectionMixin")) {
            ClassNode mixin = actual.resolve(name);
            assertEquals(1, MixinDefaultPredicateAdapter.adapt(mixin, actual::resolve), name);
            MethodNode wrapped = mixin.methods.stream().filter(m -> m.name.equals("hasMaterialFlagProxy")).findFirst().orElseThrow();
            assertEquals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", MixinFit.injectorOf(wrapped).desc);
            assertTrue(wrapped.desc.contains("Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;"));
        }
    }

    public static class World { public int calls; public int value() { calls++; return 1; } }
    public static class Position { }
    public static class State { }
    public interface NativeDefaults {
        default int bits(World world, Position position, State state) { return world.value(); }
        default boolean matches(World world, Position position, State state, int mask) {
            return (projection().bits(world, position, state) & mask) != 0;
        }
        private Core projection() { return (Core) this; }
    }
    public interface GuestDefaults {
        default int bits(World world, Position position, State state, double random) { return ((Core) this).bits(); }
        default boolean matches(World world, Position position, State state, double random, int mask) {
            return (bits(world, position, state, random) & mask) != 0;
        }
    }
    public interface Core extends NativeDefaults, GuestDefaults {
        int bits();
        default boolean matches(int mask) { return (bits() & mask) != 0; }
    }
    public static class DefaultModel implements Core {
        public DefaultModel() { }
        @Override public int bits() { return 0; }
    }
    public static class GuestModel extends DefaultModel {
        public int guestCalls;
        public GuestModel() { }
        @Override public int bits(World world, Position position, State state, double random) { guestCalls++; return 1; }
    }
    public static class NativeOverride extends GuestModel {
        public int nativeCalls;
        public NativeOverride() { }
        @Override public boolean matches(World world, Position position, State state, int mask) { nativeCalls++; return false; }
    }
    public static class GuestPredicateModel extends DefaultModel {
        public int predicateCalls;
        public GuestPredicateModel() { }
        @Override public boolean matches(World world, Position position, State state, double random, int mask) {
            predicateCalls++; return false;
        }
    }
    public static class Host {
        public static boolean draw(Core model, World world, Position position, State state, int mask, double random) {
            return model.matches(world, position, state, mask);
        }
    }
    public static class GuestHandler {
        public static int calls;
        private static boolean choose(Core model, int mask, World world, Position position, State state, double random) {
            calls++; return model.matches(world, position, state, random, mask);
        }
    }

    private RuntimeFixture fixture(Class<?> model, boolean mutateDefault) throws Exception {
        return fixture(model, mutateDefault, false);
    }

    private RuntimeFixture fixture(Class<?> model, boolean mutateDefault, boolean mutateProjection) throws Exception {
        Map<String, ClassNode> nodes = nodes();
        assertEquals(1, MixinDefaultPredicateAdapter.adapt(nodes.get(name(GuestHandler.class)), nodes::get));
        ClassNode api = nodes.get(name(GuestDefaults.class));
        assertEquals(1, DefaultMethodOverloadBridge.adapt(api, nodes::get));
        if (mutateDefault) method(api, "matches").instructions.insert(new InsnNode(Opcodes.NOP));
        if (mutateProjection) method(nodes.get(name(NativeDefaults.class)), "projection").instructions.insert(new InsnNode(Opcodes.NOP));
        Map<String, byte[]> definitions = new HashMap<>();
        for (var entry : nodes.entrySet()) definitions.put(entry.getKey().replace('/', '.'), write(entry.getValue()));
        String extras = System.getProperty("forbric.mixinExtrasForTests", "");
        ClassLoader parent = extras.isBlank() ? getClass().getClassLoader()
                : new java.net.URLClassLoader(new java.net.URL[] {java.nio.file.Path.of(extras).toUri().toURL()}, getClass().getClassLoader());
        ClassLoader loader = new ClassLoader(parent) {
            @Override protected Class<?> loadClass(String binary, boolean resolve) throws ClassNotFoundException {
                if (!definitions.containsKey(binary)) return super.loadClass(binary, resolve);
                synchronized (getClassLoadingLock(binary)) {
                    Class<?> type = findLoadedClass(binary);
                    if (type == null) {
                        byte[] bytes = definitions.get(binary); type = defineClass(binary, bytes, 0, bytes.length);
                        DefinedMethodContracts.observe(this, binary, bytes);
                    }
                    if (resolve) resolveClass(type); return type;
                }
            }
        };
        Object receiver = loader.loadClass(model.getName()).getConstructor().newInstance();
        Class<?> handler = loader.loadClass(GuestHandler.class.getName());
        return new RuntimeFixture(receiver, handler);
    }

    private static class RuntimeFixture {
        final Object model; final Class<?> handler;
        final World world = new World(); final Position position = new Position(); final State state = new State();
        final AtomicInteger operations = new AtomicInteger();
        RuntimeFixture(Object model, Class<?> handler) { this.model = model; this.handler = handler; }
        boolean invoke() throws Exception {
            Class<?> operationType = Class.forName("com.llamalad7.mixinextras.injector.wrapoperation.Operation", false, handler.getClassLoader());
            Object operation = java.lang.reflect.Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[] {operationType}, (proxy, method, arguments) -> {
                Object[] args = (Object[]) arguments[0];
                operations.incrementAndGet();
                assertSame(model, args[0]); assertSame(world, args[1]); assertSame(position, args[2]); assertSame(state, args[3]);
                assertEquals(1, args[4]);
                try { return (Boolean) model.getClass().getMethod("matches", World.class, Position.class, State.class, int.class)
                        .invoke(model, world, position, state, 1); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            var wrapper = java.util.Arrays.stream(handler.getDeclaredMethods()).filter(method -> method.getName().equals("choose")).findFirst().orElseThrow();
            wrapper.setAccessible(true);
            return (Boolean) wrapper.invoke(null, model, world, position, state, 1, operation, world, position, state, 2.5);
        }
        int sourceCalls() throws Exception { return handler.getField("calls").getInt(null); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ClassNode> nodes() throws Exception {
        Map<String, ClassNode> nodes = new HashMap<>();
        for (Class<?> type : List.of(Core.class, NativeDefaults.class, GuestDefaults.class, DefaultModel.class,
                GuestModel.class, NativeOverride.class, GuestPredicateModel.class, GuestHandler.class, Host.class)) nodes.put(name(type), parse(bytes(type)));
        ClassNode mixin = nodes.get(name(GuestHandler.class));
        AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
        target.values = new ArrayList<>(List.of("value", List.of(Type.getType(Host.class)))); mixin.visibleAnnotations = List.of(target);
        MethodNode handler = method(mixin, "choose");
        AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        at.values = new ArrayList<>(List.of("value", "INVOKE", "target", "L" + name(Core.class) + ";matches(I)Z"));
        AnnotationNode injection = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Redirect;");
        injection.values = new ArrayList<>(List.of("method", List.of("draw"), "at", at)); handler.visibleAnnotations = new ArrayList<>(List.of(injection));
        handler.invisibleParameterAnnotations = new List[6];
        for (int index = 2; index < 6; index++) {
            AnnotationNode capture = new AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;");
            capture.values = List.of("argsOnly", true); handler.invisibleParameterAnnotations[index] = List.of(capture);
        }
        return nodes;
    }

    private static String name(Class<?> type) { return Type.getInternalName(type); }
    private static MethodNode method(ClassNode type, String name) { return type.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(); }
    private static ClassNode parse(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static byte[] bytes(Class<?> type) throws Exception {
        try (var in = type.getResourceAsStream("/" + name(type) + ".class")) { return in.readAllBytes(); }
    }
    private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray(); }

    private static ActualApi actualApi() throws Exception {
        String configured = System.getProperty("forbric.fabricApi");
        java.nio.file.Path api = configured == null ? net.forbric.kernel.TestFixtures.fabricApi() : java.nio.file.Path.of(configured);
        var root = net.forbric.kernel.TestFixtures.stagedRoot();
        net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,
                java.nio.file.Files.isRegularFile(api), "actual Fabric API required");
        ActualApi out = new ActualApi();
        for (String jar : List.of("merged-base/patched-mc-merged-26.2.jar", "neoforge-runtime/neoforge-runtime.jar", "forge-runtime/forge-runtime.jar")) {
            var path = root.resolve(jar); net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,
                    java.nio.file.Files.isRegularFile(path), "actual staged carrier required");
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(path.toFile())) {
                for (var entry : zip.stream().filter(e -> e.getName().endsWith(".class")).toList())
                    out.bytes.putIfAbsent(entry.getName().substring(0, entry.getName().length() - 6), zip.getInputStream(entry).readAllBytes());
            }
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(api.toFile())) {
            var entry = zip.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-renderer-api-v1-")).findFirst().orElseThrow();
            try (java.util.zip.ZipInputStream inner = new java.util.zip.ZipInputStream(zip.getInputStream(entry))) {
                for (java.util.zip.ZipEntry item; (item = inner.getNextEntry()) != null;) {
                    byte[] bytes = inner.readAllBytes();
                    if (item.getName().endsWith(".class")) out.bytes.put(item.getName().substring(0, item.getName().length() - 6), bytes);
                    if (item.getName().endsWith(".classtweaker")) out.tweaker = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return out;
    }
    private static class ActualApi {
        final Map<String, byte[]> bytes = new HashMap<>(); final Map<String, ClassNode> nodes = new HashMap<>(); String tweaker;
        ClassNode resolve(String name) {
            if (!bytes.containsKey(name)) return null;
            return nodes.computeIfAbsent(name, key -> {
                ClassNode node = parse(bytes.get(key));
                for (String line : tweaker.split("\\R")) {
                    String[] columns = line.split("\\s+");
                    if (columns.length == 3 && columns[0].equals("transitive-inject-interface") && columns[1].equals(key)
                            && !node.interfaces.contains(columns[2])) node.interfaces.add(columns[2]);
                }
                return node;
            });
        }
    }
}
