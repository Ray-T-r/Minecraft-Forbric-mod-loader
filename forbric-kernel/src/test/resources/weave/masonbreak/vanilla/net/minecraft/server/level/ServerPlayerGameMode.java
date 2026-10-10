package net.minecraft.server.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A stand-in for vanilla's ServerPlayerGameMode, the class a mod is compiled against: where destroyBlock asks the level
 * to remove the block, the one state it holds is the one playerWillDestroy returned (vanilla's own build no longer
 * holds the state it read first there).
 */
public class ServerPlayerGameMode {
	protected ServerLevel level;

	public ServerPlayerGameMode(ServerLevel level) {
		this.level = level;
	}

	public boolean destroyBlock(BlockPos pos) {
		Block block = this.level.getBlockState(pos).getBlock();
		BlockState adjustedState = block.playerWillDestroy(this.level, pos, this.level.getBlockState(pos));
		boolean changed = this.level.removeBlock(pos, false);
		return changed;
	}
}
