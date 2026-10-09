package net.minecraft.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/** A stand-in for the merged Block: its own friction, and NeoForge's context-aware friction, which defaults to it. */
public class Block {
	private final float friction;

	public Block(float friction) {
		this.friction = friction;
	}

	public float getFriction() {
		return friction;
	}

	public float getFriction(BlockState state, LevelReader level, BlockPos pos, Entity entity) {
		return getFriction();
	}
}
