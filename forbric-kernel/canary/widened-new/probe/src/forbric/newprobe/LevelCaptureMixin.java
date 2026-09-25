package forbric.newprobe;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The last BLOCK particle option added to a level, for the driver to read back: a sprint's dust. */
@Mixin(Level.class)
public abstract class LevelCaptureMixin {
	@Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V", at = @At("HEAD"))
	private void forbric$capture(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz, CallbackInfo ci) {
		if (options instanceof BlockParticleOption block && block.getType() == ParticleTypes.BLOCK) System.getProperties().put("forbric.m53.added", block);
	}
}
