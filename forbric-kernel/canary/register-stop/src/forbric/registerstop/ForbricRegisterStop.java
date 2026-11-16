/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package forbric.registerstop;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.event.lifecycle.InterModEnqueueEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * A NeoForge mod that fails on purpose at one point of NeoForge's mod loading, picked by {@code -Dforbric.registerstop}.
 * Native NeoForge 26.2.0.93 stops the boot at every one of them (owner ruling D82 for the registration, D87 for the rest);
 * the same jar runs unchanged on native NeoForge, which is what the comparison runs use.
 *
 * <ul>
 * <li>{@code listener} (the default): its RegisterEvent listener throws on {@code minecraft:item}.</li>
 * <li>{@code try}: its mixin on {@code GameData.postRegisterEvents} throws right after {@code minecraft:item}'s dispatch,
 * inside NeoForge's per-registry try, outside any mod's listener.</li>
 * <li>{@code construct}: its constructor throws ("Mod Construction").</li>
 * <li>{@code constructevent}: its FMLConstructModEvent listener throws ("Mod Construction").</li>
 * <li>{@code ebs}: its {@code @EventBusSubscriber} class fails to initialise ("Mod Construction").</li>
 * <li>{@code newregistry}: its NewRegistryEvent listener throws ("Registry initialization").</li>
 * <li>{@code datapackreg}: its DataPackRegistryEvent.NewRegistry listener throws ("Registry initialization").</li>
 * <li>{@code tail}: its EntityAttributeModificationEvent listener throws, in postRegisterEvents' tail ("Registry
 * initialization").</li>
 * <li>{@code common}: its FMLCommonSetupEvent listener throws ("Common setup").</li>
 * <li>{@code deferred}: a task it enqueues from common setup throws ("Common setup: Deferred Queue").</li>
 * <li>{@code sided}: its FMLDedicatedServerSetupEvent / FMLClientSetupEvent listener throws ("Sided setup").</li>
 * <li>{@code regevents}: its RegisterCapabilitiesEvent listener throws ("Registration events").</li>
 * <li>{@code imc}: its InterModEnqueueEvent listener throws ("Enqueue IMC").</li>
 * <li>{@code complete}: its FMLLoadCompleteEvent listener throws ("Complete loading of N mods").</li>
 * <li>{@code network}: its RegisterPayloadHandlersEvent listener throws ("Network registry lock").</li>
 * <li>{@code clientnetwork}: on a client, its RegisterClientPayloadHandlersEvent listener throws ("Client network registry
 * lock", run by ClientModLoader.finish). Registered only in this mode, so a dedicated server never loads the client event.</li>
 * <li>{@code off}: nothing throws (the control).</li>
 * </ul>
 */
@Mod(ForbricRegisterStop.ID)
public final class ForbricRegisterStop {
	public static final String ID = "forbricregisterstop";
	public static final String MODE = System.getProperty("forbric.registerstop", "listener");
	/** The registry whose RegisterEvent this mod's listener saw last; the mixin reads it. */
	public static volatile ResourceKey<?> current;

	public ForbricRegisterStop(IEventBus bus) {
		System.out.println("[ForbricRegisterStop] constructed, mode=" + MODE);
		fail("construct", "its constructor");
		bus.addListener(RegisterEvent.class, ForbricRegisterStop::onRegister);
		bus.addListener(FMLConstructModEvent.class, event -> fail("constructevent", "its FMLConstructModEvent listener"));
		bus.addListener(NewRegistryEvent.class, event -> fail("newregistry", "its NewRegistryEvent listener"));
		bus.addListener(DataPackRegistryEvent.NewRegistry.class, event -> fail("datapackreg", "its DataPackRegistryEvent.NewRegistry listener"));
		bus.addListener(EntityAttributeModificationEvent.class, event -> fail("tail", "its EntityAttributeModificationEvent listener"));
		bus.addListener(FMLCommonSetupEvent.class, event -> {
			fail("common", "its FMLCommonSetupEvent listener");
			event.enqueueWork(() -> fail("deferred", "a deferred task it enqueued from common setup"));
		});
		bus.addListener(FMLDedicatedServerSetupEvent.class, event -> fail("sided", "its FMLDedicatedServerSetupEvent listener"));
		bus.addListener(FMLClientSetupEvent.class, event -> fail("sided", "its FMLClientSetupEvent listener"));
		bus.addListener(RegisterCapabilitiesEvent.class, event -> fail("regevents", "its RegisterCapabilitiesEvent listener"));
		bus.addListener(InterModEnqueueEvent.class, event -> fail("imc", "its InterModEnqueueEvent listener"));
		bus.addListener(FMLLoadCompleteEvent.class, event -> fail("complete", "its FMLLoadCompleteEvent listener"));
		bus.addListener(RegisterPayloadHandlersEvent.class, event -> fail("network", "its RegisterPayloadHandlersEvent listener"));
		if ("clientnetwork".equals(MODE)) {
			bus.addListener(RegisterClientPayloadHandlersEvent.class,
					event -> fail("clientnetwork", "its RegisterClientPayloadHandlersEvent listener"));
		}
	}

	/** Throws, saying where, when {@code mode} is this run's mode. */
	public static void fail(String mode, String where) {
		if (!mode.equals(MODE)) return;
		System.out.println("[ForbricRegisterStop] " + where + " is about to fail on purpose, mode=" + MODE);
		throw new IllegalStateException(ID + ": " + where + " fails on purpose (mode=" + MODE + ")");
	}

	private static void onRegister(RegisterEvent event) {
		current = event.getRegistryKey();
		if (!Registries.ITEM.equals(event.getRegistryKey())) return;
		System.out.println("[ForbricRegisterStop] reached RegisterEvent for minecraft:item, mode=" + MODE);
		if ("listener".equals(MODE)) {
			throw new IllegalStateException("forbricregisterstop's RegisterEvent listener fails on purpose on minecraft:item");
		}
	}
}
