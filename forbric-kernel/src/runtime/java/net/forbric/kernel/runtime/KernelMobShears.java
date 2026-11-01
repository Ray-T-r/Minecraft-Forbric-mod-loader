/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.TypedInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShearsItem;

/**
 * What MixinShearsRelay's mob rows call: a Fabric wrap of vanilla's {@code stack.is(Items.SHEARS)} in a sheep's or snow
 * golem's {@code mobInteract}, where NeoForge's body asks no shears question and shears through
 * {@code ShearsItem.interactLivingEntity} instead.
 */
public final class KernelMobShears {
	private KernelMobShears() {
	}

	/** Vanilla's {@code stack.is(item)} as an {@code Operation}: the {@code original} the wrap is handed. */
	public static Operation<Boolean> vanillaIs() {
		return args -> ((TypedInstance) args[0]).is(args[1]);
	}

	/**
	 * NeoForge's shearing entry for a stack the wrap called shears: {@code ShearsItem.interactLivingEntity} (it reads only
	 * its arguments, so it is asked on {@code Items.SHEARS} with the held stack). Null for a {@code ShearsItem}, which
	 * NeoForge's own path shears after {@code mobInteract}, and where the entry did not shear (not shearable now), so
	 * {@code mobInteract} runs on as written. Typed {@code Object} so this class names no game type in a signature.
	 *
	 * @return the entry's answer when it sheared, else null
	 */
	public static Object shearAsShears(Object stack, Object player, Object mob, Object hand) {
		ItemStack held = (ItemStack) stack;
		if (held.getItem() instanceof ShearsItem) return null;
		InteractionResult result = Items.SHEARS.interactLivingEntity(held, (Player) player, (LivingEntity) mob, (InteractionHand) hand);
		return result.consumesAction() ? result : null;
	}
}
