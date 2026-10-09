package net.minecraft.world.level.block.state;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;

/** A stand-in for the merged BlockState: its block, and NeoForge's context-aware friction query. */
public class BlockState {
	private final Block block;

	public BlockState(Block block) {
		this.block = block;
	}

	public Block getBlock() {
		return block;
	}

	public float getFriction(LevelReader level, BlockPos pos, Entity entity) {
		return block.getFriction(this, level, pos, entity);
	}
}
