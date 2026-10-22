package net.minecraft.world.level.block.state;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/** Stand-in: a block state that is always removed when broken. */
public record BlockState(String name) {
	private static final Block BLOCK = new Block();

	public Block getBlock() {
		return BLOCK;
	}

	public boolean canHarvest() {
		return true;
	}

	public boolean onDestroyedByPlayer(Level level, BlockPos pos, boolean canHarvest) {
		return true;
	}

	public boolean onDestroyedByPlayer(Level level, BlockPos pos, ItemStack tool, boolean canHarvest) {
		return true;
	}

	@Override
	public String toString() {
		return name;
	}
}
