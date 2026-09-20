package forbric.mixincanary.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.MinecraftServer;

/** Names a method the game does not have, so the kernel's fit check leaves it out — and must say whose it was. */
@Mixin(MinecraftServer.class)
public abstract class UnfitMixin {
	@Inject(method = "forbricMethodThatDoesNotExist", at = @At("HEAD"))
	private void forbric$unfit(CallbackInfo ci) {
	}
}
