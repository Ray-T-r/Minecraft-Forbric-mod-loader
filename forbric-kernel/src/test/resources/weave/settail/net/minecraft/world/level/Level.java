package net.minecraft.world.level;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Fixture stand-in for the merged level: setBlock hands the notification to NeoForge's markAndNotifyBlock, which sends the
 * block update to clients under UPDATE_CLIENTS (flags & 2) for a chunk ticking blocks, and makes the neighbour update
 * under UPDATE_NEIGHBORS (flags & 1). setBlock itself makes neither. Positions with x below 2 are in a ticking chunk.
 */
public class Level {
	public final List<String> trace = new ArrayList<>();

	public boolean setBlock(BlockPos pos, BlockState state, int flags, int updateLimit) {
		LevelChunk chunk = new LevelChunk(pos.x() < 2 ? FullChunkStatus.BLOCK_TICKING : FullChunkStatus.FULL);
		BlockState old = chunk.setBlockState(pos, state);
		markAndNotifyBlock(pos, chunk, old, state, flags, updateLimit);
		return true;
	}

	public void markAndNotifyBlock(BlockPos pos, LevelChunk chunk, BlockState oldState, BlockState newState, int flags, int updateLimit) {
		if ((flags & 2) != 0 && chunk.getFullStatus() != null && chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)) {
			sendBlockUpdated(pos, oldState, newState, flags);
		}
		if ((flags & 1) != 0) {
			updateNeighborsAt(pos, oldState.getBlock());
		}
	}

	public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
		trace.add("updated@" + pos);
	}

	public void updateNeighborsAt(BlockPos pos, Block block) {
		trace.add("neighbours@" + pos);
	}
}
