package forbric.behavior.mixin;

import java.util.Optional;
import forbric.behavior.WorldProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraftforge.event.ForgeEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An explicit mod transformation, NOT a claim that native portal events have a shape setter. */
@Mixin(value = ForgeEventFactory.class, remap = false)
public abstract class ForgePortalResultMixin {
    @Inject(method = "onTrySpawnPortal", at = @At("RETURN"), cancellable = true, require = 1, remap = false)
    private static void replaceForWorldProbe(LevelAccessor level, BlockPos pos, Optional<PortalShape> original,
                                            CallbackInfoReturnable<Optional<PortalShape>> callback) {
        if (WorldProbe.replacePortalAt(pos) && callback.getReturnValue().isPresent()) {
            WorldProbe.portalReplacementCalls++;
            callback.setReturnValue(Optional.of(WorldProbe.replacementShape));
        }
    }
}
