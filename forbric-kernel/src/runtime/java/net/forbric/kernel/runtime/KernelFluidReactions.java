/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;

/**
 * Whether vanilla's {@code LiquidBlock.shouldSpreadLiquid} reaches its lava-meets-water reaction for a cell, asked where the
 * base's neighbour change asks NeoForge's {@code FluidInteractionRegistry.canInteract} instead.
 *
 * <p>On NeoForge 26.2.0.93 a liquid block's placement still calls vanilla's {@code shouldSpreadLiquid}, but its
 * {@code neighborChanged} calls the registry. A Fabric handler written at that reaction ({@code INVOKE FluidState.isSource} in
 * {@code shouldSpreadLiquid}: Carpet's renewable deepslate) binds there and runs on placement, and never on a neighbour change,
 * where vanilla runs it too. {@code MixinFluidReactionAdapter} gives the neighbour change's registry question a wrap that runs
 * the handler first, when vanilla would have reached its point. This is vanilla's own walk, read off the base's
 * {@code shouldSpreadLiquid} (the same body as vanilla's): a lava block; for each of {@code POSSIBLE_FLOW_DIRECTIONS}, the
 * neighbour behind it holding water reaches the point; else, over soul soil, the neighbour being blue ice is vanilla's basalt
 * reaction, which ends the walk first.
 */
public final class KernelFluidReactions {
	private KernelFluidReactions() {
	}

	/** Called from the wrap the adapter writes into a guest mixin of {@code LiquidBlock}, with the block as {@code self}. */
	public static boolean reachesWaterReaction(LiquidBlock self, Level level, BlockPos pos) {
		if (!self.fluid.is(FluidTags.LAVA)) return false;
		boolean soulSoil = level.getBlockState(pos.below()).is(Blocks.SOUL_SOIL);
		for (Direction direction : LiquidBlock.POSSIBLE_FLOW_DIRECTIONS) {
			BlockPos neighbour = pos.relative(direction.getOpposite());
			if (level.getFluidState(neighbour).is(FluidTags.WATER)) return true;
			if (soulSoil && level.getBlockState(neighbour).is(Blocks.BLUE_ICE)) return false;
		}
		return false;
	}
}
