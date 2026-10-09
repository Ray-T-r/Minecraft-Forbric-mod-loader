package net.neoforged.neoforge.common.extensions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/** A stand-in for NeoForge's native block sounds: each asks the state for its context-aware sound group, then plays it. */
public interface IBlockExtension {
	default void playStepSound(BlockState state, Level level, BlockPos pos, Entity entity, float volume, float pitch) {
		SoundType sound = state.getSoundType(level, pos, entity);
		level.playSound(entity, pos, sound, volume, pitch);
	}

	default void playFallSound(BlockState state, Level level, BlockPos pos, Entity entity) {
		SoundType sound = state.getSoundType(level, pos, entity);
		level.playSound(entity, pos, sound, 0.5F, 0.75F);
	}
}
