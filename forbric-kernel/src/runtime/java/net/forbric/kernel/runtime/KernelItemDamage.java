/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.server.level.ServerPlayer;

/**
 * What a Fabric wrap written for vanilla's {@code hurtAndBreak(int, ServerLevel, ServerPlayer, Consumer)} call is handed where
 * NeoForge's base calls {@code hurtAndBreak(int, ServerLevel, LivingEntity, Consumer)} instead (FabricItemDamageMixinAdapter).
 *
 * <p>Vanilla's {@code ItemStack.hurtAndBreak(int, LivingEntity, EquipmentSlot)} passes the entity on as a player or null;
 * NeoForge's passes the entity itself, to an overload that serves every living entity. The handler gets the value vanilla's
 * call had in that place ({@link #playerOf}), and an {@code Operation} that, when the handler calls it with that same value,
 * makes NeoForge's call with the entity it stood for, so NeoForge's damage path still sees who held the item.
 */
public final class KernelItemDamage {
	private KernelItemDamage() {
	}

	/**
	 * Vanilla's argument: the entity as a server player, or null. Typed {@code Object} (the adapter casts the answer) so this
	 * class names no game type in a signature and its unit test loads it without the game.
	 */
	public static Object playerOf(Object entity) {
		return entity instanceof ServerPlayer ? entity : null;
	}

	/**
	 * @param original NeoForge's call: {@code (ItemStack, Integer, ServerLevel, LivingEntity, Consumer) → Void}
	 * @param entity   the {@code LivingEntity} NeoForge's call was made with
	 * @param player   what the handler was handed for it ({@link #playerOf})
	 */
	public static Operation<Void> relay(Operation<Void> original, Object entity, Object player) {
		return args -> {
			Object[] passed = args.clone();
			// The value the handler was handed stands for the entity; any other player the handler chose is passed as it is.
			if (passed.length > 3 && passed[3] == player) passed[3] = entity;
			return original.call(passed);
		};
	}
}
