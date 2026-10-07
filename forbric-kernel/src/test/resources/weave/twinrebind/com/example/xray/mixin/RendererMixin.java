package com.example.xray.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import fixture.twinrebind.Trace;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Synthetic guest mixin in the shape of LiquidBounce's X-Ray face test: selected by name, taking vanilla's four
 * arguments after the result, and showing every face of an ore. It reports what it was handed, where.
 */
@Mixin(ModelBlockRenderer.class)
public class RendererMixin {
	@ModifyReturnValue(method = "shouldRenderFace", at = @At("RETURN"))
	private boolean xray(boolean original, BlockAndTintGetter level, BlockState state, Direction direction, BlockPos neighborPos) {
		Trace.add("xray:" + state.name + "/" + direction.name().toLowerCase() + "/" + neighborPos.name);
		return original || state == BlockState.ORE;
	}
}
