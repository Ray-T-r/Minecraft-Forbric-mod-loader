/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package forbric.registerstop;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * A NeoForge mod that fails inside NeoForge's registration on purpose, the two ways owner ruling D82 names.
 *
 * <ul>
 * <li>{@code -Dforbric.registerstop=listener} (the default): its RegisterEvent listener throws on {@code minecraft:item}.
 * NeoForge's {@code ModContainer.acceptEvent} turns that into a {@code ModLoadingException} naming this mod.</li>
 * <li>{@code =try}: the listener is fine, and its mixin on {@code GameData.postRegisterEvents} throws right after
 * {@code minecraft:item}'s dispatch: inside NeoForge's per-registry try, outside any mod's listener.</li>
 * <li>{@code =off}: nothing throws (the control).</li>
 * </ul>
 *
 * <p>Native NeoForge 26.2.0.93 rolls every registry back to vanilla and stops the boot in both failing modes. The same jar
 * runs unchanged on native NeoForge, which is what the comparison runs use.
 */
@Mod(ForbricRegisterStop.ID)
public final class ForbricRegisterStop {
	public static final String ID = "forbricregisterstop";
	public static final String MODE = System.getProperty("forbric.registerstop", "listener");
	/** The registry whose RegisterEvent this mod's listener saw last; the mixin reads it. */
	public static volatile ResourceKey<?> current;

	public ForbricRegisterStop(IEventBus bus) {
		System.out.println("[ForbricRegisterStop] constructed, mode=" + MODE);
		bus.addListener(RegisterEvent.class, ForbricRegisterStop::onRegister);
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
