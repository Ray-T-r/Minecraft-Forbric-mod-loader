package net.minecraft.server.level;

import fixture.twinlocals.Trace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** As the guest's loader has it: removeBlock(pos, canHarvest) reads the state and destroys it, on both paths. */
public class ServerPlayerGameMode {
	public final Level level = new Level();

	public boolean destroyBlock(BlockPos pos) {
		BlockState state = this.level.getBlockState(pos);
		Trace.add("event " + pos);
		if (pos.creative()) {
			this.removeBlock(pos, false);
			return true;
		}
		boolean canHarvest = state.canHarvest();
		return this.removeBlock(pos, canHarvest);
	}

	private boolean removeBlock(BlockPos pos, boolean canHarvest) {
		BlockState state = this.level.getBlockState(pos);
		boolean removed = state.onDestroyedByPlayer(this.level, pos, canHarvest);
		if (removed) state.getBlock().destroy(this.level, pos, state);
		return removed;
	}
}
