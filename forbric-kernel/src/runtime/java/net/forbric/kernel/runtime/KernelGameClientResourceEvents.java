/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraftforge.client.ForgeHooksClient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;

/**
 * An atlas stitched and the models baked, for MinecraftForge mods — on NeoForge's mod bus, where the merged client
 * posts them ({@code TextureAtlas.upload}, {@code ModelManager.apply}). Nothing calls MinecraftForge's two hooks, so
 * Xaero's block colours and entity icons, built from these, went stale after every resource reload. Both hooks are
 * pure emitters.
 *
 * <p>NeoForge skips mod-bus posts entirely once a mod has failed to load, so these arrive only on a clean load — as
 * NeoForge's own do.
 */
public final class KernelGameClientResourceEvents {
	private KernelGameClientResourceEvents() {
	}

	public static void install(Object modBus) {
		KernelGameClientNetworkEvents.forward((IEventBus) modBus, TextureAtlasStitchedEvent.class, "TextureAtlasStitchedEvent",
				event -> ForgeHooksClient.onTextureStitchedPost(event.getAtlas()));
		KernelGameClientNetworkEvents.forward((IEventBus) modBus, ModelEvent.BakingCompleted.class, "ModelEvent.BakingCompleted",
				event -> ForgeHooksClient.onModelBake(event.getModelManager(), event.getModelBakery()));
	}
}
