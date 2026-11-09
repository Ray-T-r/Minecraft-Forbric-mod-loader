package net.fabricmc.fabric.mixin.item;

import java.util.function.Consumer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.fabricmc.fabric.api.item.v1.CustomDamageHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * fabric-item-api-v1 14.3.0's ItemStackMixin.hookDamage in shape (javap): a @WrapOperation of vanilla's
 * hurtAndBreak(int, ServerLevel, ServerPlayer, Consumer) call in hurtAndBreak(int, LivingEntity, EquipmentSlot), the
 * call's operands and its Operation, then the host's entity and slot as @Local(argsOnly = true) sugar; it asks the item's
 * CustomDamageHandler for the amount before the call. The body is the fixture's own, without the break callback.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	@Shadow
	public abstract Item getItem();

	@WrapOperation(method = "hurtAndBreak(ILnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V"))
	private void hookDamage(ItemStack instance, int amount, ServerLevel level, ServerPlayer player, Consumer<Item> onBroken,
			Operation<Void> original, @Local(argsOnly = true) LivingEntity entity, @Local(argsOnly = true) EquipmentSlot slot) {
		CustomDamageHandler handler = getItem().getCustomDamageHandler();
		if (handler != null && !entity.hasInfiniteMaterials()) {
			amount = handler.hurtAndBreak((ItemStack) (Object) this, amount, entity, slot);
		}
		original.call(instance, amount, level, player, onBroken);
	}
}
