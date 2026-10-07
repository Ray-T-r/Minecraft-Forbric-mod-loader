package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import net.fabricmc.api.EnvType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

@ExecutesInjector(EarlyGameDirectoryInjector.class)
class EarlyGameDirectoryInjectorTest {
    private static ClassNode parse(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static Map<String, String> sources(String body, String marker) {
        return Map.of(
            "org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin", "package org.spongepowered.asm.mixin.extensibility; public interface IMixinConfigPlugin {}",
            "net.fabricmc.loader.api.FabricLoader", "package net.fabricmc.loader.api; public interface FabricLoader { static FabricLoader getInstance(){return () -> java.nio.file.Path.of(\"kernel-directory\");} java.nio.file.Path getGameDir(); }",
            "fixture.platform.Paths", "package fixture.platform; public interface Paths { static Paths getInstance(){throw new AssertionError(\"provider initialized too early\");} java.nio.file.Path getGameDir(); }",
            "fixture.early.UnrecognizedPlugin", "package fixture.early; public class UnrecognizedPlugin " + marker + " { public static java.nio.file.Path path; static {" + body + "} }");
    }
    @Test void anUnknownPluginsUniqueDeclaredDirectoryPathRunsWithoutTheProvider(@TempDir Path work) throws Throwable {
        Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, sources("path=fixture.platform.Paths.getInstance().getGameDir();", "implements org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin")));
        var injector = new EarlyGameDirectoryInjector(name -> classes.containsKey(name) ? parse(classes.get(name)) : null);
        String name = "fixture/early/UnrecognizedPlugin"; byte[] original = classes.get(name);
        byte[] changed = InjectorExecution.transform(injector, name.replace('/', '.'), original, EnvType.CLIENT);
        assertNotSame(original, changed); classes.put(name, changed);
        assertSame(changed, injector.transform(name, changed, null));
        ClassLoader loader = InjectorExecution.load(classes);
        assertEquals("", InjectorExecution.verify(changed, loader));
        assertEquals(Path.of("kernel-directory"), InjectorExecution.getStatic(loader.loadClass(name.replace('/', '.')), "path"));
    }
    @Test void nonPluginAndAmbiguousDirectoryPathsAreRefused(@TempDir Path work) throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            String body = "path=fixture.platform.Paths.getInstance().getGameDir();";
            if (mode == 1) body += "path=fixture.platform.Paths.getInstance().getGameDir();";
            Map<String, byte[]> classes = InjectorExecution.compile(work.resolve("case" + mode), sources(body,
                mode == 0 ? "" : "implements org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin"));
            boolean wrong = mode == 2;
            var injector = new EarlyGameDirectoryInjector(name -> modeDeclaration(classes, name, wrong));
            byte[] original = classes.get("fixture/early/UnrecognizedPlugin");
            assertSame(original, injector.transform("fixture.early.UnrecognizedPlugin", original, null));
        }
    }
    private static ClassNode modeDeclaration(Map<String, byte[]> classes, String name, boolean wrong) {
        if (!classes.containsKey(name)) return null; ClassNode node = parse(classes.get(name));
        if (wrong) node.methods.removeIf(m -> m.name.equals("getGameDir")); return node;
    }
    @Test void theRealPluginRetainsTheSameDirectoryContract() throws Exception {
        Path jar = Path.of("build/compat-inputs/player-loading/mods/iris-neoforge-1.11.4+mc26.2.jar");
        TestFixtures.requireFiles(Fixture.THIRD_PARTY, "real plugin directory contract", jar);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            String plugin = "net/irisshaders/iris/mixin/IrisMixinPlugin";
            byte[] original = zip.getInputStream(zip.getEntry(plugin + ".class")).readAllBytes();
            var injector = new EarlyGameDirectoryInjector(name -> {
                try { var entry = zip.getEntry(name + ".class"); return entry == null ? null : parse(zip.getInputStream(entry).readAllBytes()); }
                catch (java.io.IOException error) { throw new IllegalStateException(error); }
            });
            assertNotSame(original, injector.transform(plugin, original, null));
        }
    }
}
