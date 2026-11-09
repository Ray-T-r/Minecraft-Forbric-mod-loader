package org.betterx.bclib.mixin.common.shears;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fixture.shearsrelaymobs.ShearTags;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * BCLib 26.201.2's SnowGolemMixin in shape (javap): a Fabric mod's @WrapOperation of vanilla's stack.is(Object) in
 * mobInteract, (ItemStack, Object, Operation) -> boolean, extending the original for a tagged shears item. On NeoForge's
 * base the call is gone from mobInteract, so as compiled it binds nowhere. The body is the fixture's own.
 */
@Mixin(SnowGolem.class)
public class SnowGolemMixin {
	@WrapOperation(method = "mobInteract", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;is(Ljava/lang/Object;)Z"))
	private boolean bclib_isShears(ItemStack instance, Object item, Operation<Boolean> original) {
		return original.call(instance, item) || (item == Items.SHEARS && ShearTags.isShear(instance));
	}
}
