package net.minecraft.world.level.block.state;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;

/** A stand-in for vanilla's BlockState: the sound group is asked without context. */
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
}
