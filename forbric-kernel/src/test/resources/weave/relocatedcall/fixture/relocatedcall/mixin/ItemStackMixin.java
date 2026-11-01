package fixture.relocatedcall.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A Fabric mod's wrap written against vanilla, where ItemStack.useOn makes the Item.useOn call itself (fabric-api's
 * ItemEvents.USE_ON hook has this shape). On NeoForge's own useOn that call is made outside the method (in
 * CommonHooks.onPlaceItemIntoWorld and a client lambda), and the kernel routes both through its relay instead.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	@WrapOperation(method = "useOn", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/item/Item;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"))
	private InteractionResult wrapUseOn(Item item, UseOnContext context, Operation<InteractionResult> original) {
		System.out.println("[RelocatedCall] guest wrap ran");
		return new InteractionResult("wrapped(" + original.call(item, context).name() + ")");
	}
}
