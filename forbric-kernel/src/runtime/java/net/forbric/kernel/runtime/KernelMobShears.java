/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Method;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.Caught;
import net.minecraft.core.TypedInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
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
	 * <p>Null too for an item that brings an entity entry of its own ({@link #ownEntityEntry}) and is not a Fabric mod's
	 * ({@link #fabricCode}): {@code Player.interactOn} asks the item's {@code interactLivingEntity} only after
	 * {@code mobInteract} passes, so answering here would pre-empt that entry, which on NeoForge (and for an item of the base
	 * or of no single family) runs as written. A Fabric item's own entry is pre-empted, as native Fabric with BCLib
	 * pre-empts it: there vanilla's {@code mobInteract} shears first.
	 *
	 * @return the entry's answer when it sheared, else null
	 */
	public static Object shearAsShears(Object stack, Object player, Object mob, Object hand) {
		ItemStack held = (ItemStack) stack;
		if (held.getItem() instanceof ShearsItem) return null;
		if (ownEntityEntry(held.getItem().getClass()) && !fabricCode(held.getItem().getClass())) return null;
		InteractionResult result = Items.SHEARS.interactLivingEntity(held, (Player) player, (LivingEntity) mob, (InteractionHand) hand);
		return result.consumesAction() ? result : null;
	}

	/** Whether {@code type} or a superclass below {@code Item} declares {@code interactLivingEntity(ItemStack, Player, LivingEntity, InteractionHand)}. */
	private static final ClassValue<Boolean> OWN_ENTRY = new ClassValue<>() {
		@Override protected Boolean computeValue(Class<?> type) {
			try {
				for (Class<?> c = type; c != null && c != Item.class; c = c.getSuperclass()) {
					for (Method m : c.getDeclaredMethods()) {
						if (m.getName().equals("interactLivingEntity") && !m.isBridge() && m.getParameterCount() == 4
								&& m.getParameterTypes()[0] == ItemStack.class && m.getParameterTypes()[1] == Player.class
								&& m.getParameterTypes()[2] == LivingEntity.class && m.getParameterTypes()[3] == InteractionHand.class) return true;
					}
				}
				return false;
			} catch (LinkageError unreadable) {
				Caught.warn(unreadable, "[Forbric/Shears] could not read the methods of %s; it is treated as an item with an entity entry "
						+ "of its own, which BCLib's tag shears then leave to run", type.getName());
				return true;
			}
		}
	};

	static boolean ownEntityEntry(Class<?> type) {
		return OWN_ENTRY.get(type);
	}

	/** Whether {@code type} is a Fabric mod's code, as the kernel's loader recorded the jar it came from. */
	static boolean fabricCode(Class<?> type) {
		return type.getClassLoader() instanceof ForbricClassLoader loader && loader.ecosystemOfClass(type) == Ecosystem.FABRIC;
	}
}
