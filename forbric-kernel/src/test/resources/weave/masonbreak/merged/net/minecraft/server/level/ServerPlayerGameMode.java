package net.minecraft.server.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A stand-in for the merged base's ServerPlayerGameMode: NeoForge's destroyBlock keeps the state it read first live, in
 * a lower slot, beside the state playerWillDestroy returned, and hands that one to its own removeBlock where vanilla
 * asked the level. A capture of "the state" read here by ordinal or as the only one would read NeoForge's earlier state,
 * or find two.
 */
public class ServerPlayerGameMode {
	protected ServerLevel level;

	public ServerPlayerGameMode(ServerLevel level) {
		this.level = level;
	}

	public boolean destroyBlock(BlockPos pos) {
		BlockState state = this.level.getBlockState(pos);
		Block block = state.getBlock();
		BlockState adjustedState = block.playerWillDestroy(this.level, pos, state);
		boolean changed = this.removeBlock(pos, adjustedState, true);
		return changed;
	}

	private boolean removeBlock(BlockPos pos, BlockState state, boolean canHarvest) {
		return this.level.removeBlock(pos, false);
	}
}
