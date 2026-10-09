package net.minecraft.world.level.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Fixture stand-in: every position starts as air; the chunk is as loaded as it was made. */
public class LevelChunk {
	private final FullChunkStatus status;

	public LevelChunk(FullChunkStatus status) {
		this.status = status;
	}

	public BlockState setBlockState(BlockPos pos, BlockState state) {
		return new BlockState(new Block("minecraft:air"));
	}

	public FullChunkStatus getFullStatus() {
		return status;
	}
}
