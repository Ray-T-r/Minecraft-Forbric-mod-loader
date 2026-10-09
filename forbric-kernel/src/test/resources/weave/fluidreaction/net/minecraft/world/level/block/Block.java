package net.minecraft.world.level.block;

import net.minecraft.world.level.block.state.BlockState;

/** Fixture stand-in. */
public class Block {
	private final String name;

	public Block(String name) {
		this.name = name;
	}

	public BlockState defaultBlockState() {
		return new BlockState(this);
	}

	@Override
	public String toString() {
		return name;
	}
}
