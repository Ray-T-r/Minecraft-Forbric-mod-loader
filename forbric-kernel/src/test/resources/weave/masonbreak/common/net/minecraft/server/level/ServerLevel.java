package net.minecraft.server.level;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** A stand-in: which state stands at which named position. */
public class ServerLevel {
	private final Map<String, BlockState> blocks = new HashMap<>();

	public BlockState getBlockState(BlockPos pos) {
		return blocks.get(pos.name());
	}

	public void setBlock(BlockPos pos, BlockState state) {
		blocks.put(pos.name(), state);
	}

	public boolean removeBlock(BlockPos pos, boolean moving) {
		return blocks.remove(pos.name()) != null;
	}

	/** What stands at {@code pos}: a state's name, or air. */
	public String at(BlockPos pos) {
		BlockState state = blocks.get(pos.name());
		return state == null ? "air" : state.name();
	}
}
