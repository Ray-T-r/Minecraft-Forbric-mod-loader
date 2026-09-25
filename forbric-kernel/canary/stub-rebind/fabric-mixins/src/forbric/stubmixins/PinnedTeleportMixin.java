package forbric.stubmixins;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A name-only HEAD injection with no argument capture: an entity tagged forbric_pinned never teleports. On the merged
 * base Mixin binds it to the carrier's randomTeleport(double, double, double, boolean) stub; chorus fruit and the game
 * call NeoForge's overload that also takes the item.
 */
@Mixin(LivingEntity.class)
public abstract class PinnedTeleportMixin {
	@Inject(method = "randomTeleport", at = @At("HEAD"), cancellable = true)
	private void forbric$pinned(CallbackInfoReturnable<Boolean> cir) {
		if (((LivingEntity) (Object) this).entityTags().contains("forbric_pinned")) cir.setReturnValue(false);
	}
}
