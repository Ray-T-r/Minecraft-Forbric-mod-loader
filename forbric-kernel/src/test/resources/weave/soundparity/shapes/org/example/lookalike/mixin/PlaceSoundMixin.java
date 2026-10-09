package org.example.lookalike.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The same wrap of the same query, in a method the platform did not move: it binds as written and is left alone. */
@Mixin(Entity.class)
public abstract class PlaceSoundMixin {
	@WrapOperation(method = "playPlaceSound",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getSoundType()Lnet/minecraft/world/level/block/SoundType;"))
	private SoundType lookalike$place(BlockState state, Operation<SoundType> original) {
		return new SoundType(original.call(state).name() + "+placed");
	}
}
