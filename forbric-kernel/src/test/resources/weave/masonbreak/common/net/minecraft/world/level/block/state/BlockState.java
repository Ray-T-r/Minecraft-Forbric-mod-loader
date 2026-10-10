package net.minecraft.world.level.block.state;

import net.minecraft.world.level.block.Block;

/** A stand-in: a named state of a block. */
public class BlockState {
	private final String name;
	private final Block block;

	public BlockState(String name, Block block) {
		this.name = name;
		this.block = block;
	}

	public String name() {
		return name;
	}

	public Block getBlock() {
		return block;
	}
}
