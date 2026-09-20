package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import javax.tools.ToolProvider;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** The actual typed runtime helper runs against a minimal carrier-shaped fixture; TOML parsing is real NightConfig. */
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
class KernelForgeConfigLoadTest {
    private static final String HOOK = "net.forbric.kernel.runtime.KernelForgeConfigLoad";
    private static final String CONFIG = "net/minecraftforge/fml/config/ModConfig";
    private static final String TRACKER = "net/minecraftforge/fml/config/ConfigTracker";
    private static final Path FORGE = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/forge-runtime/forge-runtime.jar");
    @TempDir Path temporary;

    @Test
    void actualCarrierHasOnlyTheThreeForgeTypesAndThePrivateStaticTwoArgumentOpen() throws Exception {
        assumeTrue(Files.isRegularFile(FORGE), "staged Forge carrier absent");
        try (ZipFile zip = new ZipFile(FORGE.toFile())) {
            ClassNode tracker = read(zip, TRACKER);
            var open = tracker.methods.stream().filter(m -> m.name.equals("openConfig")).findFirst().orElseThrow();
            assertEquals("(L" + CONFIG + ";Ljava/nio/file/Path;)V", open.desc);
            assertEquals(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, open.access);
            var config = read(zip, CONFIG);
            assertTrue(config.methods.stream().anyMatch(m -> m.name.equals("getConfigData")
                    && m.desc.equals("()Lcom/electronwill/nightconfig/core/CommentedConfig;")));
            var type = read(zip, CONFIG + "$Type");
            assertEquals(Set.of("CLIENT", "COMMON", "SERVER"), type.fields.stream()
                    .filter(f -> (f.access & Opcodes.ACC_ENUM) != 0).map(f -> f.name).collect(java.util.stream.Collectors.toSet()));
            ClassNode states = read(zip, "net/minecraftforge/fml/core/ModStateProvider");
            var stage = states.methods.stream().filter(m -> {
                for (var i : m.instructions) if (i instanceof MethodInsnNode call && call.owner.equals(TRACKER)
                        && call.name.equals("loadConfigs")) return true;
                return false;
            }).findFirst().orElseThrow();
            List<String> order = new ArrayList<>();
            for (var i : stage.instructions) if (i instanceof FieldInsnNode f && f.owner.equals(CONFIG + "$Type")) order.add(f.name);
            assertEquals(List.of("CLIENT", "COMMON"), order);
            List<String> transaction = new ArrayList<>();
            for (var i : open.instructions) if (i instanceof MethodInsnNode call && call.owner.equals(CONFIG)) transaction.add(call.name);
            assertTrue(transaction.indexOf("setConfigData") < transaction.indexOf("fireEvent"),
                    "a failed Loading callback may already have non-null config data");
        }
        ClassNode runtime = runtime();
        for (var method : runtime.methods) for (var i : method.instructions) {
            if (i instanceof MethodInsnNode call) {
                assertFalse(call.owner.startsWith("net/neoforged/"), "do not use NeoForge's incompatible config ABI");
                assertFalse(call.name.equals("loadConfigs"), "never open the whole type and then retry its successful prefix");
                assertFalse(call.owner.equals(CONFIG + "$Type") && call.name.equals("valueOf"), "do not ask Forge for Neo's STARTUP type");
            }
        }
    }

    @Test
    void actualTomlFailureOnlyDegradesItsOwnerAndTheFollowingConfigStillLoads() throws Exception {
        try (Fixture f = fixture()) {
            f.catalog("broken", "good");
            Object bad = f.add("broken", "COMMON", "broken-common.toml", "");
            Object good = f.add("good", "COMMON", "good-common.toml", "");
            Files.writeString(f.directory.resolve("broken-common.toml"), "probe = [\n");
            Files.writeString(f.directory.resolve("good-common.toml"), "probe = 47\n");
            f.early(List.of("COMMON"));
            assertNull(f.data(bad));
            assertEquals(47, f.data(good).getInt("probe"));
            assertEquals(1, f.count(good, "loadingEvents"));
            assertEquals(1, f.count(good, "saves"));
            ModCatalog.Entry failure = ModCatalog.failures().getFirst();
            assertEquals("broken", failure.modId());
            assertEquals(ModCatalog.Status.DEGRADED, failure.status());
            assertTrue(failure.statusDetail().contains("broken-common.toml"));
            assertTrue(failure.statusDetail().contains("COMMON"));
            assertEquals(1, ModCatalog.failures().size());
        }
    }

    @Test
    void clientThenCommonUsesTheGlobalDirectoryWhileServerRemainsUnopened() throws Exception {
        try (Fixture f = fixture()) {
            Object common = f.add("sample", "COMMON", "sample-common.toml", "");
            Object client = f.add("sample", "CLIENT", "sample-client.toml", "");
            Object server = f.add("sample", "SERVER", "sample-server.toml", "");
            f.early(List.of("COMMON", "CLIENT", "COMMON"));
            assertEquals(List.of("sample:CLIENT", "sample:COMMON"), f.calls());
            assertNotNull(f.data(client)); assertNotNull(f.data(common)); assertNull(f.data(server));
            assertFalse(Files.exists(f.directory.resolve("sample-server.toml")));
        }
        try (Fixture f = fixture()) {
            Object client = f.add("sample", "CLIENT", "sample-client.toml", "");
            Object common = f.add("sample", "COMMON", "sample-common.toml", "");
            f.early(List.of("COMMON"));
            assertNull(f.data(client)); assertNotNull(f.data(common));
            assertFalse(Files.exists(f.directory.resolve("sample-client.toml")));
        }
    }

    @Test
    void neitherPassReopensExistingDataAndLatePassCatchesANewRegistration() throws Exception {
        try (Fixture f = fixture()) {
            Object first = f.add("first", "COMMON", "first-common.toml", "");
            f.early(List.of("COMMON"));
            Object saved = f.data(first);
            f.early(List.of("COMMON"));
            Object late = f.add("late", "COMMON", "late-common.toml", "");
            assertEquals(List.of("late:COMMON"), f.late(List.of("COMMON")));
            assertSame(saved, f.data(first));
            assertEquals(1, f.count(first, "attempts")); assertEquals(1, f.count(first, "loadingEvents"));
            assertEquals(1, f.count(late, "attempts"));
            assertTrue(f.late(List.of("COMMON")).isEmpty());
        }
    }

    @Test
    void acceptLoadingAndSaveFailuresWithPublishedDataAreNotRetriedByLateLoading() throws Exception {
        try (Fixture f = fixture()) {
            f.catalog("accept", "listener", "save", "last");
            List<Object> failed = new ArrayList<>();
            for (String stage : List.of("accept", "listener", "save")) failed.add(f.add(stage, "COMMON", stage + ".toml", stage));
            Object last = f.add("last", "COMMON", "last.toml", "");
            f.early(List.of("COMMON"));
            assertNotNull(f.data(last));
            assertEquals(3, ModCatalog.failures().size());
            for (Object config : failed) assertNotNull(f.data(config));
            assertTrue(f.late(List.of("COMMON")).isEmpty());
            for (Object config : failed) assertEquals(1, f.count(config, "attempts"));
            assertEquals(0, f.count(failed.getFirst(), "loadingEvents"));
            assertEquals(1, f.count(failed.get(1), "loadingEvents"));
        }
    }

    @Test
    void registrationDuringLoadingUsesASnapshotAndIsPickedUpOnlyByTheNextPass() throws Exception {
        try (Fixture f = fixture()) {
            Object first = f.add("first", "COMMON", "first.toml", "");
            Object second = f.add("second", "COMMON", "second.toml", "");
            Object[] added = new Object[1];
            f.callback(first, "onLoading", () -> {
                try { added[0] = f.add("nested", "COMMON", "nested.toml", ""); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            f.early(List.of("COMMON"));
            assertNotNull(f.data(first)); assertNotNull(f.data(second)); assertNotNull(added[0]);
            assertNull(f.data(added[0]), "a callback must not mutate the pass currently being iterated");
            assertEquals(List.of("nested:COMMON"), f.late(List.of("COMMON")));
            assertEquals(1, f.count(first, "loadingEvents"));
        }
    }

    @Test
    void invalidTypeListsAreRejectedBeforeAnythingOpensAndOffDoesNotTouchFiles() throws Exception {
        try (Fixture f = fixture()) {
            Object common = f.add("sample", "COMMON", "sample.toml", "");
            assertThrows(IllegalArgumentException.class, () -> f.early(List.of("COMMON", "SERVER")));
            assertThrows(IllegalArgumentException.class, () -> f.late(List.of("COMMON", "STARTUP")));
            assertNull(f.data(common)); assertTrue(f.calls().isEmpty());
            String previous = System.getProperty("forbric.earlyConfigs");
            try {
                System.setProperty("forbric.earlyConfigs", "off");
                f.early(List.of("COMMON")); assertTrue(f.late(List.of("COMMON")).isEmpty());
                assertTrue(f.calls().isEmpty()); assertFalse(Files.exists(f.directory.resolve("sample.toml")));
            } finally { if (previous == null) System.clearProperty("forbric.earlyConfigs"); else System.setProperty("forbric.earlyConfigs", previous); }
            assertEquals(List.of("sample:COMMON"), f.late(List.of("COMMON")));
        }
    }

    @Test
    void forgeBaselineLoadsWithoutAnyGuestCatalogRows() throws Exception {
        try (Fixture f = fixture()) {
            f.catalog();
            Object baseline = f.add("forge", "COMMON", "forge-common.toml", "");
            f.early(List.of("COMMON"));
            assertNotNull(f.data(baseline));
            assertTrue(Files.isRegularFile(f.directory.resolve("forge-common.toml")));
            assertTrue(ModCatalog.everything().isEmpty(), "loading the baseline must not invent a guest catalog row");
        }
    }

    @Test
    void overlappingEarlyAndLatePassesStillOpenEachConfigOnlyOnce() throws Exception {
        try (Fixture f = fixture()) {
            Object config = f.add("sample", "COMMON", "sample.toml", "");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            f.callback(config, "beforeData", () -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            });
            var workers = Executors.newFixedThreadPool(2);
            try {
                var first = workers.submit(() -> f.early(List.of("COMMON")));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var second = workers.submit(() -> f.late(List.of("COMMON")));
                release.countDown();
                first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
                assertEquals(1, f.count(config, "attempts")); assertEquals(1, f.count(config, "loadingEvents"));
            } finally { release.countDown(); workers.shutdownNow(); }
        }
    }

    private Fixture fixture() throws Exception { return new Fixture(Files.createTempDirectory(temporary, "fixture-")); }

    private static final class Fixture implements AutoCloseable {
        final Path directory;
        final URLClassLoader loader;
        final Class<?> config, tracker, type;
        final Method early, late;
        final List<ModCatalog.Entry> previous = ModCatalog.everything();
        Fixture(Path base) throws Exception {
            directory = Files.createDirectory(base.resolve("config"));
            Path source = Files.createDirectories(base.resolve("sources"));
            Path classes = Files.createDirectory(base.resolve("classes"));
            List<Path> files = new ArrayList<>();
            files.add(write(source, "net/minecraftforge/fml/config/ModConfig.java", CONFIG_SOURCE));
            files.add(write(source, "net/minecraftforge/fml/config/ConfigTracker.java", TRACKER_SOURCE));
            files.add(write(source, "net/minecraftforge/fml/loading/FMLPaths.java", PATH_SOURCE));
            String dependencies = String.join(java.io.File.pathSeparator,
                    Path.of(CommentedConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                    Path.of(TomlParser.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            List<String> arguments = new ArrayList<>(List.of("--release", "21", "-proc:none", "-classpath", dependencies, "-d", classes.toString()));
            files.forEach(file -> arguments.add(file.toString()));
            var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler, "a JDK is required");
            assertEquals(0, compiler.run(null, null, null, arguments.toArray(String[]::new)), "fixture compilation");
            Path runtime = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
            assumeTrue(Files.isRegularFile(runtime.resolve(HOOK.replace('.', '/') + ".class")), "runtime helper not compiled");
            loader = new URLClassLoader(new URL[] {classes.toUri().toURL(), runtime.toUri().toURL()}, KernelForgeConfigLoadTest.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.startsWith("net.minecraftforge.") || name.equals(HOOK)) {
                        synchronized (getClassLoadingLock(name)) {
                            Class<?> found = findLoadedClass(name); if (found == null) found = findClass(name);
                            if (resolve) resolveClass(found); return found;
                        }
                    }
                    return super.loadClass(name, resolve);
                }
            };
            config = loader.loadClass(CONFIG.replace('/', '.')); type = loader.loadClass(CONFIG.replace('/', '.') + "$Type");
            tracker = loader.loadClass(TRACKER.replace('/', '.'));
            Class<?> paths = loader.loadClass("net.minecraftforge.fml.loading.FMLPaths");
            Object configdir = paths.getField("CONFIGDIR").get(null); paths.getField("path").set(configdir, directory);
            Class<?> helper = loader.loadClass(HOOK); early = helper.getMethod("loadEarly", List.class); late = helper.getMethod("openLate", List.class);
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object add(String id, String kind, String filename, String failure) throws Exception {
            Object object = config.getConstructor(String.class, type, String.class).newInstance(id, Enum.valueOf((Class) type, kind), filename);
            config.getField("failure").set(object, failure); return object;
        }
        void callback(Object object, String name, Runnable action) throws Exception { config.getField(name).set(object, action); }
        void early(List<String> types) { invoke(early, types); }
        @SuppressWarnings("unchecked") List<String> late(List<String> types) { return (List<String>) invoke(late, types); }
        CommentedConfig data(Object object) throws Exception { return (CommentedConfig) config.getMethod("getConfigData").invoke(object); }
        int count(Object object, String field) throws Exception { return config.getField(field).getInt(object); }
        @SuppressWarnings("unchecked") List<String> calls() throws Exception { return (List<String>) tracker.getField("calls").get(null); }
        void catalog(String... ids) {
            ModCatalog.publish(java.util.Arrays.stream(ids).map(id -> new ModCatalog.Entry(Ecosystem.FORGE, id, id, "1", "", List.of(), "", "", "")).toList());
        }
        @Override public void close() throws Exception { loader.close(); ModCatalog.publish(previous); }
    }

    private static Path write(Path root, String name, String content) throws Exception {
        Path path = root.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path, content); return path;
    }
    private static Object invoke(Method method, Object... args) {
        try { return method.invoke(null, args); }
        catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static ClassNode runtime() throws Exception {
        Path path = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"), HOOK.replace('.', '/') + ".class");
        assumeTrue(Files.isRegularFile(path), "runtime helper not compiled");
        ClassNode node = new ClassNode(); new ClassReader(Files.readAllBytes(path)).accept(node, 0); return node;
    }
    private static ClassNode read(ZipFile zip, String binary) throws Exception {
        var entry = zip.getEntry(binary + ".class"); assertNotNull(entry, binary);
        ClassNode node = new ClassNode(); new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, 0); return node;
    }

    private static final String CONFIG_SOURCE = """
            package net.minecraftforge.fml.config;
            import com.electronwill.nightconfig.core.CommentedConfig;
            public class ModConfig {
                public enum Type { COMMON, CLIENT, SERVER }
                private final String id, file; private final Type type;
                public volatile CommentedConfig data;
                public String failure = ""; public Runnable onLoading, beforeData;
                public int attempts, loadingEvents, saves;
                public ModConfig(String id, Type type, String file) {
                    this.id=id; this.type=type; this.file=file; ConfigTracker.configSets().get(type).add(this);
                }
                public String getModId() { return id; }
                public String getFileName() { return file; }
                public Type getType() { return type; }
                public CommentedConfig getConfigData() { return data; }
            }
            """;
    private static final String TRACKER_SOURCE = """
            package net.minecraftforge.fml.config;
            import java.nio.file.*;
            import java.util.*;
            import com.electronwill.nightconfig.toml.TomlParser;
            public final class ConfigTracker {
                private static final Map<ModConfig.Type,Set<ModConfig>> configs = new EnumMap<>(ModConfig.Type.class);
                public static final List<String> calls = Collections.synchronizedList(new ArrayList<>());
                static { for (var type:ModConfig.Type.values()) configs.put(type,Collections.synchronizedSet(new LinkedHashSet<>())); }
                public static Map<ModConfig.Type,Set<ModConfig>> configSets() { return configs; }
                public static void loadConfigs(ModConfig.Type type, Path directory) { throw new AssertionError("bulk opening is forbidden"); }
                private static void openConfig(ModConfig config, Path directory) throws Exception {
                    config.attempts++; calls.add(config.getModId()+":"+config.getType());
                    if(config.beforeData!=null) config.beforeData.run();
                    if(config.failure.equals("read")) throw new java.io.IOException("reader failed");
                    Path file=directory.resolve(config.getFileName());
                    if(!Files.exists(file)) Files.writeString(file,"probe = 11\\n");
                    config.data=new TomlParser().parse(Files.readString(file));
                    if(config.failure.equals("accept")) throw new IllegalArgumentException("acceptConfig failed");
                    config.loadingEvents++;
                    if(config.onLoading!=null) config.onLoading.run();
                    if(config.failure.equals("listener")) throw new IllegalStateException("Loading listener failed");
                    config.saves++;
                    if(config.failure.equals("save")) throw new java.io.IOException("save failed");
                    Files.writeString(file,"probe = "+config.data.getInt("probe")+"\\n");
                }
            }
            """;
    private static final String PATH_SOURCE = """
            package net.minecraftforge.fml.loading;
            public final class FMLPaths {
                public static final FMLPaths CONFIGDIR=new FMLPaths();
                public java.nio.file.Path path;
                public java.nio.file.Path get() { return path; }
            }
            """;
}
