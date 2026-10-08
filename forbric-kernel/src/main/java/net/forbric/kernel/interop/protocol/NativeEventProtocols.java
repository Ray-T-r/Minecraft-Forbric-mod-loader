/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop.protocol;
import net.forbric.api.NativeEventDelivery;
import net.forbric.api.ForeignType;
import net.forbric.api.Ecosystem;

/** The observer forwards' published SDK hook/event contracts. The generic proof discovers all caller classes. */
public final class NativeEventProtocols {
    public static final String START_TRACKING="forbric:tracking-start",STOP_TRACKING="forbric:tracking-stop",CONVERSION_POST="forbric:conversion-post";
    private NativeEventProtocols(){ }
    public static void register(){
        observer(START_TRACKING,"onStartEntityTracking","(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/player/Player;)V","net/neoforged/neoforge/event/entity/player/PlayerEvent$StartTracking");
        observer(STOP_TRACKING,"onStopEntityTracking","(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/player/Player;)V","net/neoforged/neoforge/event/entity/player/PlayerEvent$StopTracking");
        observer(CONVERSION_POST,"onLivingConvert","(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/LivingEntity;)V",ForeignType.LIVING_CONVERSION_EVENT.internal(Ecosystem.NEOFORGE)+"$Post");
    }
    private static void observer(String id,String method,String descriptor,String event){
        NativeEventDelivery.register(new NativeEventDelivery.Contract(id,
            new NativeEventDelivery.Hook(ForeignType.EVENT_FACTORY.internal(Ecosystem.NEOFORGE),method,descriptor),
            new NativeEventDelivery.Hook(ForeignType.EVENT_FACTORY.internal(Ecosystem.FORGE),method,descriptor),event,
            new NativeEventDelivery.Hook("net/neoforged/bus/api/IEventBus","post","(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;")));
    }
}
