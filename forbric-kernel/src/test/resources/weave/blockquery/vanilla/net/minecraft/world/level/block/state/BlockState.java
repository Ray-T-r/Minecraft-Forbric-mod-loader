package net.minecraft.world.level.block.state;

import net.minecraft.world.level.block.Block;

/** A stand-in for vanilla's BlockState: its block. */
public class BlockState {
	private final Block block;

	public BlockState(Block block) {
		this.block = block;
	}

	public Block getBlock() {
		return block;
	}
}
