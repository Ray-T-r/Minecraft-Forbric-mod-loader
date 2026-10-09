package net.minecraft.world.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A stand-in for vanilla's Entity step sounds: each of the three methods asks the state for its sound group and plays it
 * itself. The muffled and combination steps never call playStepSound.
 */
public class Entity {
	private final Level level;
	private double x, y, z;

	public Entity(Level level) {
		this.level = level;
	}

	public Level level() {
		return level;
	}

	public double getX() {
		return x;
	}

	public double getY() {
		return y;
	}

	public double getZ() {
		return z;
	}

	public void setPos(double x, double y, double z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	protected void playStepSound(BlockPos pos, BlockState state) {
		SoundType sound = state.getSoundType();
		this.level().playSound(this, pos, sound, 0.15F, 1.0F);
	}

	protected void playCombinationStepSounds(BlockState primary, BlockState secondary) {
		SoundType sound = primary.getSoundType();
		this.level().playSound(this, null, sound, 0.15F, 1.0F);
		this.playMuffledStepSound(secondary);
	}

	protected void playMuffledStepSound(BlockState state) {
		SoundType sound = state.getSoundType();
		this.level().playSound(this, null, sound, 0.05F, 0.8F);
	}

	/** A sound the platform did not move: both shapes ask the state here themselves. */
	protected void playPlaceSound(BlockState state) {
		this.level().playSound(this, null, state.getSoundType(), 1.0F, 1.0F);
	}

	/** One step onto {@code state}, as movement makes it. */
	public void stepOn(BlockPos pos, BlockState state) {
		playStepSound(pos, state);
	}

	/** One step onto {@code primary} with {@code secondary} sounding under it. */
	public void stepOnCombination(BlockState primary, BlockState secondary, BlockPos pos) {
		playCombinationStepSounds(primary, secondary);
	}
}
