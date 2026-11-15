/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package forbric.registerstop.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import forbric.registerstop.ForbricRegisterStop;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.GameData;

/**
 * supermartijn642corelib's shape on {@code GameData.postRegisterEvents}: a handler right after each registry's
 * {@code ModLoader.postEventWrapContainerInModOrder}, so inside NeoForge's per-registry try. With
 * {@code -Dforbric.registerstop=try} it throws after {@code minecraft:item}'s dispatch.
 */
@Mixin(value = GameData.class, remap = false)
public abstract class GameDataMixin {
	@Inject(method = "postRegisterEvents", at = @At(value = "INVOKE",
			target = "Lnet/neoforged/fml/ModLoader;postEventWrapContainerInModOrder(Lnet/neoforged/bus/api/Event;)V",
			shift = At.Shift.AFTER), remap = false)
	private static void forbricregisterstop$afterDispatch(CallbackInfo info) {
		if ("try".equals(ForbricRegisterStop.MODE) && Registries.ITEM.equals(ForbricRegisterStop.current)) {
			System.out.println("[ForbricRegisterStop] mixin after minecraft:item's dispatch, about to fail on purpose");
			throw new IllegalStateException("forbricregisterstop's GameData.postRegisterEvents mixin fails on purpose after "
					+ "minecraft:item's dispatch");
		}
	}
}
