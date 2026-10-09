package net.minecraft.world.level.block.state;

import net.minecraft.world.level.block.Block;

/** Fixture stand-in. */
public record BlockState(Block block) {
	@Override
	public String toString() {
		return block.toString();
	}
}
