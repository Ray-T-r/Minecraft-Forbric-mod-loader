package net.minecraft.world.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** A stand-in for vanilla's movement code: it asks the block under it for its friction. */
public class Entity {
	private final Level level;

	public Entity(Level level) {
		this.level = level;
	}

	public Level level() {
		return level;
	}

	public float slide(BlockPos pos) {
		return this.level().getBlockState(pos).getBlock().getFriction() * 0.5F;
	}

	public float drift(BlockPos from, BlockPos to) {
		return this.level().getBlockState(from).getBlock().getFriction() + 10.0F * this.level().getBlockState(to).getBlock().getFriction();
	}

	/** Vanilla asks the first position's block; the merged stand-in asks the second's (a call on another receiver). */
	public float shifted(BlockPos from, BlockPos to) {
		return this.level().getBlockState(from).getBlock().getFriction();
	}

	/** Vanilla asks the block; the merged stand-in asks a fluid, from which no getter leads back to a block. */
	public float wobble(BlockPos pos) {
		return this.level().getBlockState(pos).getBlock().getFriction() * 2.0F;
	}
}
