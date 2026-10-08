/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop.protocol.configapi;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.forbric.api.*;
import net.forbric.kernel.transform.InjectorExecution;

class ConfigApiPortProtocolExtensionTest {
    @TempDir Path work;
    @Test void thePublicOptionalProtocolReceivesAllThreeEventsWithGameClassIdentity() throws Exception {
        ClassLoader game = InjectorExecution.load(InjectorExecution.compile(work, Map.of(
            "net.neoforged.fml.config.ModConfig", """
                package net.neoforged.fml.config;
                public class ModConfig { public String getModId() { return "an-unfamiliar-consumer"; } }
                """,
            "net.fabricmc.fabric.api.event.Event", """
                package net.fabricmc.fabric.api.event;
                public class Event<T> {
                    private final T listener;
                    public Event(T listener) { this.listener = listener; }
                    public T invoker() { return listener; }
                }
                """,
            "fuzs.forgeconfigapiport.fabric.api.v5.ModConfigEvents", """
                package fuzs.forgeconfigapiport.fabric.api.v5;
                import java.util.*;
                import net.fabricmc.fabric.api.event.Event;
                import net.neoforged.fml.config.ModConfig;
                public class ModConfigEvents {
                    public static final List<String> delivered = new ArrayList<>();
                    public interface Loading { void onModConfigLoading(ModConfig config); }
                    public interface Reloading { void onModConfigReloading(ModConfig config); }
                    public interface Unloading { void onModConfigUnloading(ModConfig config); }
                    public static Event<Loading> loading(String id) { return new Event<>(c -> delivered.add("loading:" + id + ":" + c.getModId())); }
                    public static Event<Reloading> reloading(String id) { return new Event<>(c -> delivered.add("reloading:" + id + ":" + c.getModId())); }
                    public static Event<Unloading> unloading(String id) { return new Event<>(c -> delivered.add("unloading:" + id + ":" + c.getModId())); }
                }
                """)));
        Object config = game.loadClass("net.neoforged.fml.config.ModConfig").getConstructor().newInstance();
        var registry = new ProtocolExtensions.Registry(new ProtocolExtension.Context(game, Side.CLIENT));
        registry.register(new ConfigApiPortProtocolExtension());
        for (var event : ProtocolExtension.ConfigEvent.values()) registry.configEvent(event, config);
        assertEquals(List.of("loading:an-unfamiliar-consumer:an-unfamiliar-consumer",
            "reloading:an-unfamiliar-consumer:an-unfamiliar-consumer", "unloading:an-unfamiliar-consumer:an-unfamiliar-consumer"),
            game.loadClass("fuzs.forgeconfigapiport.fabric.api.v5.ModConfigEvents").getField("delivered").get(null));
    }
    @Test void anAbsentOptionalProtocolDoesNotInventCallbacksOrFailConfigurationLoading() {
        new ConfigApiPortProtocolExtension().configEvent(new ProtocolExtension.Context(getClass().getClassLoader(), Side.CLIENT),
            ProtocolExtension.ConfigEvent.LOADING, new Object());
    }
}
