/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.function.BiPredicate;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.TypedInstance;
import net.minecraft.world.item.Items;

/**
 * The {@code Operation} a Fabric handler written for vanilla's {@code stack.is(Items.SHEARS)} is handed where the merged
 * body asks the carrier's {@code stack.canPerformAction(<shears ability>)} instead (MixinShearsRelay).
 *
 * <p>The handler calls it as it would vanilla's call. Asked about shears — the question the site asked on vanilla — it
 * asks the carrier's question about the stack it was handed, so the carrier's answer is the handler's {@code original}
 * and whatever the handler does with it (BCLib: {@code original || tagged as shears}; a veto: {@code false}) is the
 * site's answer, as on vanilla. Asked about anything else, it makes the vanilla call it was written for.
 */
public final class KernelShears {
	private KernelShears() {
	}

	/**
	 * @param carrier the merged call's operation: {@code (ItemStack, ItemAbility|ToolAction) → Boolean}
	 * @param ability the carrier constant the merged site passes (NeoForge's {@code ItemAbilities.SHEARS_*} or
	 *                MinecraftForge's {@code ToolActions.SHEARS_HARVEST})
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static Operation<Boolean> relay(Operation<Boolean> carrier, Object ability) {
		return relay(carrier, ability, Items.SHEARS, (stack, item) -> ((TypedInstance) stack).is(item));
	}

	/** {@link #relay(Operation, Object)} over a given "shears" and vanilla {@code is}; the seam the unit test drives. */
	static Operation<Boolean> relay(Operation<Boolean> carrier, Object ability, Object shears, BiPredicate<Object, Object> vanillaIs) {
		return args -> args[1] == shears ? carrier.call(args[0], ability) : vanillaIs.test(args[0], args[1]);
	}
}
