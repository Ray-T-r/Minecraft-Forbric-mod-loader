package net.minecraft.world.level;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/** A stand-in that records each sound played, and answers one block state for every position. */
public class Level implements LevelReader {
	public final List<String> sounds = new ArrayList<>();
	public BlockState everywhere;

	public BlockState getBlockState(BlockPos pos) {
		return everywhere;
	}

	public void playSound(Entity entity, BlockPos pos, SoundType sound, float volume, float pitch) {
		sounds.add(sound.name() + " " + volume);
	}
}
