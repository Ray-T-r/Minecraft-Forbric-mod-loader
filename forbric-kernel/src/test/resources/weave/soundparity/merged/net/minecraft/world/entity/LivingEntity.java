package net.minecraft.world.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A stand-in for the merged LivingEntity landing sound: the same three int locals, then a BlockPos local NeoForge added,
 * and the landing handed to NeoForge's BlockState.playFallSound.
 */
public class LivingEntity extends Entity {
	public LivingEntity(Level level) {
		super(level);
	}

	protected void playBlockFallSound() {
		if (!this.isSilentStandIn()) {
			int xx = Mth.floor(this.getX());
			int yy = Mth.floor(this.getY() - 0.2F);
			int zz = Mth.floor(this.getZ());
			BlockPos pos = new BlockPos(xx, yy, zz);
			BlockState state = this.level().getBlockState(pos);
			if (!state.isAir()) {
				state.playFallSound(this.level(), pos, this);
			}
		}
	}

	private boolean isSilentStandIn() {
		return false;
	}

	/** Lands at the position given. */
	public void fallAt(double x, double y, double z) {
		setPos(x, y, z);
		playBlockFallSound();
	}
}
