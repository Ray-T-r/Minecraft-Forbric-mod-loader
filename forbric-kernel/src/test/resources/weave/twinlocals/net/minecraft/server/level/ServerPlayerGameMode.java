package net.minecraft.server.level;

import fixture.twinlocals.Trace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stand-in with the merged shape (carrier-twins.txt's removeBlock row): one carrier's removal helper, taking the adjusted state
 * and the tool, declared first and called on both paths; the other carrier's {@code removeBlock(pos, canHarvest)} kept after
 * it and called by nothing.
 */
public class ServerPlayerGameMode {
	public final Level level = new Level();

	public boolean destroyBlock(BlockPos pos) {
		BlockState state = this.level.getBlockState(pos);
		Trace.add("event " + pos);
		BlockState adjusted = state.getBlock().playerWillDestroy(this.level, pos, state);
		if (pos.creative()) {
			this.removeBlock(pos, adjusted, false, new ItemStack("hand").copy());
			return true;
		}
		boolean canHarvest = adjusted.canHarvest();
		return this.removeBlock(pos, adjusted, canHarvest, new ItemStack("pick").copy());
	}

	private boolean removeBlock(BlockPos pos, BlockState state, boolean canHarvest, ItemStack toolStack) {
		boolean removed = state.onDestroyedByPlayer(this.level, pos, toolStack, canHarvest);
		if (removed) state.getBlock().destroy(this.level, pos, state);
		return removed;
	}

	private boolean removeBlock(BlockPos pos, boolean canHarvest) {
		BlockState state = this.level.getBlockState(pos);
		boolean removed = state.onDestroyedByPlayer(this.level, pos, canHarvest);
		if (removed) state.getBlock().destroy(this.level, pos, state);
		return removed;
	}
}
