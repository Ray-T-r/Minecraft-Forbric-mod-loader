/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraftforge.client.ForgeHooksClient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;

/**
 * An atlas stitched and the models baked, for MinecraftForge mods — on NeoForge's mod bus, where the merged client
 * posts them ({@code TextureAtlas.upload}, {@code ModelManager.apply}). A base whose merge kept only NeoForge's hooks
 * there never calls MinecraftForge's two, so Xaero's block colours and entity icons, built from these, went stale
 * after every resource reload. Both hooks are pure emitters.
 *
 * <p>Each goes on its own bus view: where the merged game calls MinecraftForge's hook beside NeoForge's, the view stands
 * the forward down for that post (GameEventMultiplexer's guard).
 *
 * <p>NeoForge skips mod-bus posts entirely once a mod has failed to load, so these arrive only on a clean load — as
 * NeoForge's own do.
 */
public final class KernelGameClientResourceEvents {
	private KernelGameClientResourceEvents() {
	}

	public static void installStitched(Object modBus) {
		KernelGameClientNetworkEvents.forward((IEventBus) modBus, TextureAtlasStitchedEvent.class, "TextureAtlasStitchedEvent",
				event -> ForgeHooksClient.onTextureStitchedPost(event.getAtlas()));
	}

	public static void installBaked(Object modBus) {
		KernelGameClientNetworkEvents.forward((IEventBus) modBus, ModelEvent.BakingCompleted.class, "ModelEvent.BakingCompleted",
				event -> ForgeHooksClient.onModelBake(event.getModelManager(), event.getModelBakery()));
	}
}
