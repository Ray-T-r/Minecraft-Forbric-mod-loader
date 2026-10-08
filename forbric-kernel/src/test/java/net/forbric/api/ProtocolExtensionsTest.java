/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.transform.*;

class ProtocolExtensionsTest {
    @TempDir Path work;
    @Test void aPreviouslyUnknownServiceProviderGetsEveryHookThroughTheGameLoader() throws Exception {
        var sources = Map.of("unfamiliar.Protocol", """
            package unfamiliar;
            import java.util.*;
            import net.forbric.api.*;
            import net.forbric.kernel.transform.*;
            public class Protocol implements ProtocolExtension {
                public static final List<String> calls = new ArrayList<>();
                public String id() { return "unfamiliar-protocol"; }
                public void offerClasses(Context context) {
                    calls.add("offer:" + context.side());
                    org.objectweb.asm.ClassWriter offered = new org.objectweb.asm.ClassWriter(0);
                    offered.visit(65, 1, "unfamiliar/Offered", null, "java/lang/Object", null); offered.visitEnd();
                    ((net.forbric.kernel.classloading.ForbricClassLoader) context.gameLoader())
                        .putGeneratedClass("unfamiliar.Offered", offered.toByteArray());
                }
                public void registerTransformers(Context context, Transforms transforms) {
                    transforms.register("COREMOD", "unfamiliar-transform", (name, bytes, transformContext) ->
                        name.equals("unfamiliar.Target") ? new byte[]{42} : bytes);
                }
                public void beforeConfigurationLoading(Context context) { calls.add("initialize"); }
                public void configEvent(Context context, ConfigEvent event, Object config) { calls.add(event + ":" + config); }
                public ConfigurationScreen configurationScreen(Context context, ModCatalog.Entry mod) {
                    if (!mod.modId().equals("unfamiliar-consumer")) return null;
                    calls.add("query");
                    return new ConfigurationScreen(parent -> { calls.add("open"); return parent; });
                }
            }
            """, "unfamiliar.Target", "package unfamiliar; public class Target { }");
        Map<String, byte[]> compiled = InjectorExecution.compile(work, sources);
        Path jar = work.resolve("independent-provider.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var entry : compiled.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey() + ".class")); output.write(entry.getValue()); output.closeEntry();
            }
            output.putNextEntry(new ZipEntry("META-INF/services/" + ProtocolExtension.class.getName()));
            output.write("unfamiliar.Protocol\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.closeEntry();
        }
        try (ForbricClassLoader game = new ForbricClassLoader(new java.net.URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            var lazy = ProtocolExtensions.forLoader(game);
            var registry = ProtocolExtensions.discover(game, Side.CLIENT);
            assertSame(lazy, registry);
            assertSame(registry, ProtocolExtensions.forLoader(game));
            assertTrue(registry.ids().contains("unfamiliar-protocol"));
            assertSame(game, game.loadClass("unfamiliar.Protocol").getClassLoader());
            registry.offerClasses();
            assertSame(game, game.loadClass("unfamiliar.Offered").getClassLoader());
            TransformChain chain = new TransformChain(); registry.registerTransformers(net.forbric.kernel.interop.protocol.ProtocolTransformAdapters.registry(chain));
            assertArrayEquals(new byte[]{42}, chain.applyBeforeMixin("unfamiliar.Target", compiled.get("unfamiliar/Target"), null));
            registry.beforeConfigurationLoading();
            for (var event : ProtocolExtension.ConfigEvent.values()) registry.configEvent(event, "payload");
            ModCatalog.Entry consumer = new ModCatalog.Entry(Ecosystem.FABRIC, "unfamiliar-consumer", "", "", "", List.of(), "", "", "");
            var factory = registry.configurationScreen(consumer);
            List<?> calls = (List<?>) game.loadClass("unfamiliar.Protocol").getField("calls").get(null);
            assertFalse(calls.contains("open"), "availability queries must not construct screens");
            Object parent = new Object(); assertSame(parent, factory.open(parent));
            assertEquals(List.of("offer:CLIENT", "initialize", "LOADING:payload", "RELOADING:payload", "UNLOADING:payload", "query", "open"), calls);
            ProtocolExtensions.release(game);
        }
    }
    @Test void programmaticProvidersAlsoReceiveEventsAndOneFailureDoesNotHideAnotherProvider() {
        var registry = new ProtocolExtensions.Registry(new ProtocolExtension.Context(getClass().getClassLoader(), Side.CLIENT));
        registry.register(new ProtocolExtension() {
            public String id() { return "broken-independent-protocol"; }
            public void configEvent(Context context, ConfigEvent event, Object config) { throw new IllegalStateException("provider failure"); }
        });
        List<Object> received = new ArrayList<>();
        ProtocolExtension provider = new ProtocolExtension() {
            public String id() { return "registered-later"; }
            public void configEvent(Context context, ConfigEvent event, Object config) { received.add(config); }
        };
        registry.register(provider);
        assertThrows(IllegalArgumentException.class, () -> registry.register(provider));
        Object config = new Object(); registry.configEvent(ProtocolExtension.ConfigEvent.RELOADING, config);
        assertEquals(List.of(config), received);
    }
}
