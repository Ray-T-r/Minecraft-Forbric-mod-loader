package forbric.mixincanary.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.chunk.storage.RegionFileStorage;

/**
 * Fits — read(ChunkPos) exists — and fails at APPLY: a CompoundTag-returning target needs a CallbackInfoReturnable,
 * and Mixin refuses the handler's descriptor. RegionFileStorage is named only by IOWorker and the world upgrader,
 * so it is first loaded when the world's region storage opens — after load-complete — and this failure can only
 * reach the load report if the report is written again once the world is up.
 */
@Mixin(RegionFileStorage.class)
public abstract class ApplyFailingMixin {
	@Inject(method = "read", at = @At("HEAD"))
	private void forbric$wrongCallback(CallbackInfo ci) {
	}
}
