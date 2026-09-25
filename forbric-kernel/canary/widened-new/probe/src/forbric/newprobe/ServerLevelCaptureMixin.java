package forbric.newprobe;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The last BLOCK particle option the server sent, for the driver to read back: a landing's dust. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelCaptureMixin {
	@Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I", at = @At("HEAD"))
	private void forbric$capture(ParticleOptions options, double x, double y, double z, int count, double dx, double dy, double dz,
			double speed, CallbackInfoReturnable<Integer> cir) {
		if (options instanceof BlockParticleOption block && block.getType() == ParticleTypes.BLOCK) System.getProperties().put("forbric.m53.sent", block);
	}

	@Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I", at = @At("HEAD"))
	private void forbric$captureForced(ParticleOptions options, boolean force, boolean always, double x, double y, double z, int count,
			double dx, double dy, double dz, double speed, CallbackInfoReturnable<Integer> cir) {
		if (options instanceof BlockParticleOption block && block.getType() == ParticleTypes.BLOCK) System.getProperties().put("forbric.m53.sent", block);
	}
}
