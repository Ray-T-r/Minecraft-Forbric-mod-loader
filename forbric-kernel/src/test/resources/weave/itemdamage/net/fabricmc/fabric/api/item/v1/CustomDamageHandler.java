package net.fabricmc.fabric.api.item.v1;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Fixture stand-in for fabric-item-api's handler: how much damage an item really takes (the real one can also break it). */
@FunctionalInterface
public interface CustomDamageHandler {
	int hurtAndBreak(ItemStack stack, int amount, LivingEntity entity, EquipmentSlot slot);
}
