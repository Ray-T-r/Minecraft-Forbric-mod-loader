package net.minecraft.world.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/** A stand-in for vanilla's LivingEntity landing sound: block coordinates in three int locals, then the state's group. */
public class LivingEntity extends Entity {
	public LivingEntity(Level level) {
		super(level);
	}

	protected void playBlockFallSound() {
		int xx = Mth.floor(this.getX());
		int yy = Mth.floor(this.getY() - 0.2F);
		int zz = Mth.floor(this.getZ());
		BlockState state = this.level().getBlockState(new BlockPos(xx, yy, zz));
		if (!state.isAir()) {
			SoundType sound = state.getSoundType();
			this.level().playSound(this, null, sound, 0.5F, 0.75F);
		}
	}

	/** Lands at the position given. */
	public void fallAt(double x, double y, double z) {
		setPos(x, y, z);
		playBlockFallSound();
	}
}
