package net.minecraft.world.level.block.state;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;

/**
 * A stand-in for the merged BlockState: vanilla's getSoundType(), NeoForge's context-aware one, and NeoForge's step and
 * fall playback, which hand the whole sound to the block.
 */
public class BlockState {
	private final Block block;

	public BlockState(Block block) {
		this.block = block;
	}

	public Block getBlock() {
		return block;
	}

	public boolean isAir() {
		return false;
	}

	public SoundType getSoundType() {
		return block.soundType();
	}

	public SoundType getSoundType(LevelReader level, BlockPos pos, Entity entity) {
		return block.soundType();
	}

	public void playStepSound(Level level, BlockPos pos, Entity entity, float volume, float pitch) {
		block.playStepSound(this, level, pos, entity, volume, pitch);
	}

	public void playFallSound(Level level, BlockPos pos, LivingEntity entity) {
		block.playFallSound(this, level, pos, entity);
	}
}
