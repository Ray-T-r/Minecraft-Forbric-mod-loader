package net.minecraft.world.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;

/** A stand-in for the merged movement code: it asks the state under it, with the level, position and entity. */
public class Entity {
	private final Level level;

	public Entity(Level level) {
		this.level = level;
	}

	public Level level() {
		return level;
	}

	public float slide(BlockPos pos) {
		return this.level().getBlockState(pos).getFriction(this.level(), pos, this) * 0.5F;
	}

	public float drift(BlockPos from, BlockPos to) {
		return this.level().getBlockState(from).getFriction(this.level(), from, this)
				+ 10.0F * this.level().getBlockState(to).getFriction(this.level(), to, this);
	}

	public float shifted(BlockPos from, BlockPos to) {
		return this.level().getBlockState(to).getFriction(this.level(), to, this);
	}

	public float wobble(BlockPos pos) {
		return new Fluid().getFriction(this.level(), pos, this) * 2.0F;
	}
}
